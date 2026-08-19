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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设备命令桥（ROADMAP T2 / P1-1）：把容器内 dsh 的工具调用转成宿主 Shizuku 执行。
 *
 * 容器内 agent 通过 dsh-device screenshot|uiDump|tap|inputText [param] 调用
 * http://127.0.0.1:3082/device-command（容器与宿主共享 loopback）。
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
    port: Int = DEFAULT_PORT,
) {
    private val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(true)

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
                executor.execute { handle(socket) }
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
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                try {
                    val requestLine = readLine(input) ?: return
                    val parts = requestLine.split(" ")
                    if (parts.size < 2 || parts[0] != "POST" || parts[1] != "/device-command") {
                        respond(output, 405, "{\"ok\":false,\"text\":\"\",\"errorCode\":\"METHOD_NOT_ALLOWED\"}")
                        return
                    }
                    var auth = ""
                    var contentLength = 0
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx <= 0) continue
                        val name = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        when (name) {
                            "authorization" -> auth = value
                            "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                    if (auth != "Bearer " + token) {
                        respond(output, 401, "{\"ok\":false,\"text\":\"\",\"errorCode\":\"UNAUTHORIZED\"}")
                        return
                    }
                    val body = readBody(input, contentLength)
                    val parsed = JSONObject(body)
                    val commandName = parsed.optString("command")
                    val param = parsed.optString("param")
                    val command = DeviceCommand.fromName(commandName)
                        ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备命令不支持: " + commandName)
                    val sessionId = shizuku.create(DEFAULT_COLUMNS, DEFAULT_ROWS)
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
                    val message = JSONObject.quote(error.message ?: error.javaClass.simpleName)
                    respond(
                        output,
                        200,
                        "{\"ok\":false,\"text\":\"\",\"exitCode\":1,\"truncated\":false,\"errorCode\":\"BRIDGE_FAILED\",\"message\":" + message + "}",
                    )
                }
            }
        } catch (_: Throwable) {
            // 连接异常：忽略
        }
    }

    private fun readBody(input: BufferedInputStream, contentLength: Int): String {
        if (contentLength <= 0) return ""
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var remaining = contentLength
        while (remaining > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (n < 0) break
            buffer.write(chunk, 0, n)
            remaining -= n
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
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
        const val DEFAULT_PORT = 3082
        private const val COMMAND_TIMEOUT_MS = 60_000L
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
    }
}
