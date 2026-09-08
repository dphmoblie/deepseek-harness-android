package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.shizuku.DeviceCommand
import io.deepseekharness.mobile.shizuku.DeviceCommandRunner
import io.deepseekharness.mobile.shizuku.ShizukuRuntime
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest

/**
 * 设备命令桥：把容器内 dsh 的工具调用转成宿主 Shizuku 执行。
 *
 * 容器内 agent 通过 dsh-device screenshot|uiDump|tap|inputText [param] 调用
 * http://127.0.0.1:<动态端口>/device-command（容器与宿主共享 loopback）。
 * 桥按白名单命令执行：自动创建一次性设备 Shell 会话 -> DeviceCommandRunner -> 关闭。
 * 认证：Bearer token（App 生成并注入容器环境 DSH_DEVICE_BRIDGE_TOKEN）。
 *
 * 注意：Android 运行时没有 com.sun.net.httpserver，这里用 ServerSocket 实现
 * 极简 HTTP/1.1 服务（只支持单个 POST 端点 + 固定 Content-Length 请求体）。
 */
class DeviceBridgeServer(
    private val shizuku: ShizukuRuntime,
    private val runner: DeviceCommandRunner,
    private val token: String,
    port: Int = 0,
) {
    private val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(4))
    private val running = AtomicBoolean(true)
    val localPort: Int get() = server.localPort

    fun start() {
        val thread = Thread({ acceptLoop() }, "dsh-device-bridge")
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        running.set(false)
        try {
            server.close()
        } catch (_: Throwable) {
        }
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = server.accept()
                try { executor.execute { handle(socket) } } catch (_: RejectedExecutionException) { socket.close() }
            } catch (_: SocketException) {
                break // server.close() 后退出
            } catch (_: Throwable) {
                if (!running.get()) break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                try {
                    val requestLine = readLine(input) ?: return
                    val parts = requestLine.split(" ")
                    if (
                        parts.size != 3 || parts[0] != "POST" || parts[1] != "/device-command" ||
                        parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")
                    ) {
                        respond(output, 405, "{\"ok\":false,\"text\":\"\",\"errorCode\":\"METHOD_NOT_ALLOWED\"}")
                        return
                    }
                    var auth = ""
                    var authSeen = false
                    var contentLength = 0
                    var headerCount = 0
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        if (++headerCount > 32) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求头过多")
                        val idx = line.indexOf(':')
                        if (idx <= 0) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求头格式无效")
                        val name = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        when (name) {
                            "authorization" -> {
                                if (authSeen) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥认证头重复")
                                authSeen = true
                                auth = value
                            }
                            "content-length" -> {
                                if (contentLength != 0) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求长度重复")
                                contentLength = value.toIntOrNull()?.takeIf { it in 1..16_384 }
                                    ?: throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求长度无效")
                            }
                            "transfer-encoding" -> throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥不支持分块请求")
                        }
                    }
                    val supplied = auth.removePrefix("Bearer ").toByteArray(StandardCharsets.US_ASCII)
                    if (!auth.startsWith("Bearer ") || !MessageDigest.isEqual(supplied, token.toByteArray(StandardCharsets.US_ASCII))) {
                        respond(output, 401, "{\"ok\":false,\"text\":\"\",\"errorCode\":\"UNAUTHORIZED\"}")
                        return
                    }
                    val body = readBody(input, contentLength)
                    val parsed = JSONObject(body)
                    val commandName = parsed.opt("command") as? String
                        ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备命令格式无效")
                    val param = parsed.opt("param") as? String
                        ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备参数格式无效")
                    if (commandName.length > 32 || param.length > 1024) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备参数过长")
                    val command = DeviceCommand.fromName(commandName)
                        ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备命令不支持")
                    val sessionId = shizuku.create(DEFAULT_COLUMNS, DEFAULT_ROWS, suppressPublicOutput = true)
                    try {
                        val result = runner.execute(sessionId, command, param, COMMAND_TIMEOUT_MS)
                        val errorJson = result.errorCode?.let { JSONObject.quote(it) } ?: "null"
                        val textJson = JSONObject.quote(result.text)
                        respond(
                            output,
                            200,
                            "{\"ok\":" + result.ok + ",\"exitCode\":" + result.exitCode + ",\"text\":" + textJson + ",\"truncated\":" + result.truncated + ",\"errorCode\":" + errorJson + "}",
                        )
                    } finally {
                        try {
                            shizuku.close(sessionId)
                        } catch (_: Throwable) {
                        }
                    }
                } catch (error: Throwable) {
                    val code = (error as? RuntimeFailure)?.code ?: "BRIDGE_FAILED"
                    respond(
                        output,
                        200,
                        JSONObject().put("ok", false).put("text", "").put("exitCode", 1)
                            .put("truncated", false).put("errorCode", code).toString(),
                    )
                }
            }
        } catch (_: Throwable) {
            // 连接异常：忽略
        }
    }

    private fun readBody(input: BufferedInputStream, contentLength: Int): String {
        if (contentLength !in 1..16_384) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求长度无效")
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var remaining = contentLength
        while (remaining > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (n < 0) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求被截断")
            buffer.write(chunk, 0, n)
            remaining -= n
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            if (buffer.size() >= 4096) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求行过长")
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8.name())
            if (b == 10) break // LF
            if (b != 13) buffer.write(b) // 丢弃 CR
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    private fun respond(output: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val statusText = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 60_000L
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
    }
}
