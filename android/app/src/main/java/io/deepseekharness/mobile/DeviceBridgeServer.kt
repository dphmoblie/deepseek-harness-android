package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.runtime.RuntimeScopedResource
import io.deepseekharness.mobile.shizuku.DeviceCommand
import io.deepseekharness.mobile.shizuku.DeviceFilePolicy
import io.deepseekharness.mobile.shizuku.DeviceCommandRunner
import io.deepseekharness.mobile.shizuku.ShizukuRuntime
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import io.deepseekharness.mobile.accessibility.AccessibilityAutomationStore
import io.deepseekharness.mobile.capability.DeviceCapabilityFacts
import io.deepseekharness.mobile.capability.DeviceCapabilityPolicy
import io.deepseekharness.mobile.virtualscreen.VirtualScreenService
import io.deepseekharness.mobile.virtualscreen.VirtualScreenShellPolicy
import io.deepseekharness.mobile.runtime.TaskNotification
import io.deepseekharness.mobile.runtime.TurnCompletionPolicy
import org.json.JSONObject
import android.content.Context
import android.os.Build
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest

/**
 * 设备命令桥：把容器内 dsh 的工具调用转成宿主 Shizuku 执行。
 *
 * 容器内 agent 通过 dsh-device 的固定白名单命令调用；文件命令只允许投递区 inbox/outbox 的相对路径。
 * http://127.0.0.1:<动态端口>/device-command（容器与宿主共享 loopback）。
 * 桥按白名单命令执行：自动创建一次性设备 Shell 会话 -> DeviceCommandRunner -> 关闭。
 * 认证：Bearer token（App 生成并注入容器环境 DSH_DEVICE_BRIDGE_TOKEN）。
 *
 * 不经过 Shell 的内建命令：`automationPolicy`（自动化授权闸门）、`deviceCapabilities`（统一能力清单）与 `notify-turn-complete`
 * （访客侧「一轮任务已完成」的收单点，只触发固定文案的通知，见
 * `TurnCompletionPolicy`）。后者受严格参数校验与限流约束，限流命中静默忽略。
 *
 * 注意：Android 运行时没有 com.sun.net.httpserver，这里用 ServerSocket 实现
 * 极简 HTTP/1.1 服务（只支持单个 POST 端点 + 固定 Content-Length 请求体）。
 *
 * 生命周期：由 `RuntimeHost` 以进程级资源持有，与 Harness 运行时同生共死。
 * 保活生效时 Harness 进程仍在运行，Activity 重建不得重建或拆除本桥，
 * 否则 guest 注入的端口与 Bearer token 会立即失效。
 */
class DeviceBridgeServer(
    private val shizuku: ShizukuRuntime,
    private val runner: DeviceCommandRunner,
    private val token: String,
    port: Int = 0,
    private val shellEnabled: () -> Boolean = { false },
) : RuntimeScopedResource {
    private val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(4))
    private val running = AtomicBoolean(true)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    val localPort: Int get() = server.localPort

    fun start() {
        val thread = Thread({ acceptLoop() }, "dsh-device-bridge")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 取得进程级 Application 上下文；拿不到时返回 null。
     *
     * 为什么用反射而不是构造参数：本类的构造点固定在 `RuntimeHost.ensureDeviceBridge`
     * 内，而只有 `notify-turn-complete` 这一条命令需要 Context（发通知与写审计）。
     * 为了它给全类加一个构造参数，会让所有既有调用方与顺序依赖一起动；而
     * `ActivityThread.currentApplication()` 是进程级单例，桥只可能在
     * `Application.onCreate()` 之后被创建，因此**实际可达的失败只有一种**：
     * 拿不到 Context，此时该命令返回 `TURN_NOTIFY_UNAVAILABLE` 并静默降级，
     * 绝不影响任何别的命令。若后续要接更正规的注入，把本方法换成构造参数即可，
     * 调用点只有下面那一处。
     */
    /**
     * 根通道开关（用户在应用设置里显式打开）。
     *
     * 读不到 Context 时按**关**处理：这与 [DeviceCapabilityFacts] 的默认值同一方向，
     * 也意味着「读不到设置」永远不会被当成「用户开着根通道」。
     */
    private fun rootEngineEnabled(): Boolean = applicationContextOrNull()
        ?.let { io.deepseekharness.mobile.shizuku.RootEngineAccess.enabled(it) }
        ?: false

    private fun applicationContextOrNull(): Context? = try {
        val current = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null)
        (current as? Context)?.applicationContext
    } catch (_: Throwable) {
        null
    }

    override fun stop() {
        running.set(false)
        try {
            server.close()
        } catch (_: Throwable) {
        }
        activeSockets.forEach(::closeQuietly)
        executor.shutdownNow()
        // Close a socket accepted concurrently with server.close(), then briefly wait
        // so no late device request can reconnect Shizuku after runtime cleanup.
        activeSockets.forEach(::closeQuietly)
        try {
            executor.awaitTermination(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = server.accept()
                if (!running.get()) {
                    closeQuietly(socket)
                    break
                }
                activeSockets.add(socket)
                try {
                    executor.execute {
                        try {
                            handle(socket)
                        } finally {
                            activeSockets.remove(socket)
                            closeQuietly(socket)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    activeSockets.remove(socket)
                    closeQuietly(socket)
                }
            } catch (_: SocketException) {
                break // server.close() 后退出
            } catch (_: Throwable) {
                if (!running.get()) break
            }
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Throwable) {
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
                                contentLength = value.toIntOrNull()?.takeIf { it in 1..MAX_REQUEST_BODY_BYTES }
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
                    if (commandName.length > 32 || param.length > DeviceFilePolicy.MAX_PARAM_CHARS) {
                        throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备参数过长")
                    }
                    // 自动化授权与设备能力都不经过 Shell；能力判定复用原生统一策略，不在 AI 插件里另算一套。
                    if (commandName == "automationPolicy") {
                        if (param.isNotEmpty()) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "能力查询不接受参数")
                        respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(
                            true, 0, JSONObject().put("schemaVersion", 3).put("allowlistedAutomation", true)
                                .put("shellEnabled", shellEnabled()).put("backgroundTasks", true)
                                .put("virtualScreen", true)
                                .put("directDeviceOperations", true).toString(), false, null,
                        ))
                        return
                    }
                    if (commandName == "deviceCapabilities") {
                        if (param.isNotEmpty()) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备能力查询不接受参数")
                        val context = applicationContextOrNull()
                            ?: throw RuntimeFailure("DEVICE_CAPABILITIES_UNAVAILABLE", "暂时无法读取设备能力")
                        val shizukuState = shizuku.state()
                        val root = io.deepseekharness.mobile.shizuku.RootEngineAccess.status(
                            context,
                            packageChecker = io.deepseekharness.mobile.shizuku.RootEngineAccess.packageChecker(context),
                        )
                        val facts = DeviceCapabilityFacts(
                            sdkInt = Build.VERSION.SDK_INT,
                            shizukuInstalled = shizukuState.installed,
                            shizukuRunning = shizukuState.running,
                            shizukuPermission = shizukuState.permission,
                            shizukuConnected = shizukuState.connected,
                            accessibilityStatus = AccessibilityAutomationStore.serviceStatus(context),
                            rootEngineEnabled = root.enabled,
                            rootEngineStatus = if (root.enabled) root.kernelSuStatus else null,
                            rootEngineReason = if (root.enabled) root.reason else null,
                            rootSandboxDegraded = root.degraded,
                        )
                        val entries = org.json.JSONArray().also { array ->
                            DeviceCapabilityPolicy.report(facts).forEach { capability ->
                                array.put(JSONObject()
                                    .put("id", capability.id)
                                    .put("state", capability.state)
                                    .put("reason", capability.reason)
                                    .put("action", capability.action ?: JSONObject.NULL)
                                    .put("alternative", capability.alternative ?: JSONObject.NULL))
                            }
                        }
                        respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(
                            true, 0,
                            JSONObject().put("atMs", System.currentTimeMillis())
                                .put("sdkInt", Build.VERSION.SDK_INT)
                                .put("capabilities", entries).toString(),
                            false, null,
                        ))
                        return
                    }
                    // 无障碍命令不经过 Shell：由用户手动开启的服务在原生侧再次校验
                    // 白名单、前台包名、锁屏状态、敏感窗口和动作频率。
                    // automationTask 与 accessibilityTree 同一条闸门：它只做计划式执行的闸门与记账，不执行动作。
                    if (commandName in setOf(
                            "accessibilityTree",
                            "accessibilityAction",
                            "tap",
                            "inputText",
                            "automationTask",
                        )
                    ) {
                        val service = DeepSeekAccessibilityService.current()
                        val result = service?.execute(commandName, param)
                            ?: io.deepseekharness.mobile.shizuku.DeviceCommandResult(
                                ok = false,
                                exitCode = 1,
                                text = "请先在系统设置中手动开启 Harness 无障碍服务",
                                truncated = false,
                                errorCode = "ACCESSIBILITY_SERVICE_DISABLED",
                            )
                        respondResult(output, result)
                        return
                    }
                    if (commandName in setOf("virtualScreenState", "virtualScreenCapture", "virtualScreenAction")) {
                        if (!shellEnabled()) throw RuntimeFailure("DEVICE_SHELL_DISABLED", "请在 Shizuku 设置中开启 AI Shell")
                        respondResult(output, io.deepseekharness.mobile.virtualscreen.VirtualScreenCommands.execute(commandName, param))
                        return
                    }
                    // 副屏直通 Shell：把会话副屏的 displayId 注入脚本环境后，走与通用命令**同一条**
                    // 一次性 Shell 路径。param 是 JSON：{"script":"…","displayId":38}（displayId 可省）。
                    // 校验与错误码全部由 VirtualScreenShellPolicy 决定，这里只负责取只读状态并执行。
                    if (commandName == VirtualScreenShellPolicy.COMMAND) {
                        if (!shellEnabled()) throw RuntimeFailure("DEVICE_SHELL_DISABLED", "请在 Shizuku 设置中开启 AI Shell")
                        val request = try {
                            JSONObject(param)
                        } catch (error: Throwable) {
                            throw RuntimeFailure("DEVICE_COMMAND_INVALID", "副屏 Shell 参数不是合法 JSON", error)
                        }
                        // 只认 JSON 字符串与 JSON 数字：`{"script":null}` 这类形状不能变成一段可执行脚本文本。
                        val script = request.opt("script") as? String
                        val requestedDisplayId = when (val raw = request.opt("displayId")) {
                            null -> null
                            is Number -> raw.toInt()
                            else -> throw RuntimeFailure("DEVICE_COMMAND_INVALID", "displayId 必须是整数")
                        }
                        val state = VirtualScreenService.current?.state()
                        val plan = VirtualScreenShellPolicy.plan(
                            script = script,
                            requestedDisplayId = requestedDisplayId,
                            active = state?.optBoolean("active") == true,
                            sessionDisplayId = state?.optInt("displayId", 0) ?: 0,
                        )
                        respondResult(output, runOneShot(DeviceCommand.SHELL, plan.command), plan.displayId)
                        return
                    }
                    // 访客侧「一轮任务已完成」的收单点（登记册 §5.5）。
                    // 这条命令**只**能触发一条固定文案的通知，不读会话内容、不返回任何用户数据：
                    // 访客进程因此无法用它在通知栏或锁屏上写任意文本。
                    // 限流命中一律静默忽略（ok=true, accepted=false），不报错刷屏。
                    if (commandName == "notify-turn-complete") {
                        if (param.length > TurnCompletionPolicy.MAX_PARAM_CHARS) {
                            throw RuntimeFailure("DEVICE_COMMAND_INVALID", "任务完成事件参数过长")
                        }
                        val receipt = TaskNotification.recordTurnCompleted(
                            applicationContextOrNull(),
                            param,
                        )
                        respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(
                            ok = receipt.ok,
                            exitCode = if (receipt.ok) 0 else 1,
                            text = JSONObject()
                                .put("accepted", receipt.accepted)
                                .put("queued", receipt.queued)
                                .toString(),
                            truncated = false,
                            errorCode = receipt.errorCode,
                        ))
                        return
                    }
                    val command = DeviceCommand.fromName(commandName)
                        ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备命令不支持")
                    // 根通道（用户在设置里显式打开）时，SHELL 命令由应用进程自己起 `su -c`，
                    // 既不建 Shizuku 会话，也不受「AI Shell」这个 Shizuku 通道开关约束——
                    // 那条会话的父进程是 UID 2000 的 shell，根命令的归属会变得不确定。
                    val rootEngine = command == DeviceCommand.SHELL && rootEngineEnabled()
                    if (command == DeviceCommand.SHELL && !rootEngine && !shellEnabled()) {
                        throw RuntimeFailure("DEVICE_SHELL_DISABLED", "请在 Shizuku 设置中开启 AI Shell，或在应用设置里打开根通道")
                    }
                    if (command == DeviceCommand.BACKGROUND_TASKS && !shellEnabled()) {
                        throw RuntimeFailure("DEVICE_SHELL_DISABLED", "请在 Shizuku 设置中开启 AI Shell")
                    }
                    respondResult(output, runOneShot(command, param, rootEngine))
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

    /**
     * 一次性设备 Shell 会话：创建 → 执行一条命令 → 关闭。
     *
     * 会话退出（Shell 死亡 / Shizuku 断开）后不会再有输出：在途的设备命令立刻按协议错误收口，
     * 不必空等到 60 秒超时。通用命令与副屏直通命令共用这一条路径，只有命令与脚本文本不同。
     */
    private fun runOneShot(
        command: DeviceCommand,
        param: String,
        rootEngine: Boolean = false,
    ): io.deepseekharness.mobile.shizuku.DeviceCommandResult {
        // 根通道不建 Shizuku 会话：会话 ID 对这个分支没有意义（`execute` 会先于任何会话操作返回）。
        if (rootEngine) return runner.execute(ROOT_ENGINE_SESSION_ID, command, param, COMMAND_TIMEOUT_MS, useRootEngine = true)
        val sessionId = shizuku.create(
            DEFAULT_COLUMNS,
            DEFAULT_ROWS,
            suppressPublicOutput = true,
            permitted = running::get,
            onSessionExit = { id -> runner.onSessionExit(id) },
        )
        try {
            return runner.execute(sessionId, command, param, COMMAND_TIMEOUT_MS)
        } finally {
            try {
                shizuku.close(sessionId)
            } catch (_: Throwable) {
            }
        }
    }

    private fun respondResult(
        output: BufferedOutputStream,
        result: io.deepseekharness.mobile.shizuku.DeviceCommandResult,
        // 只有副屏直通命令会带上它：调用方据此确认这次脚本到底落在哪块屏上。
        displayId: Int? = null,
    ) {
        val errorJson = result.errorCode?.let { JSONObject.quote(it) } ?: "null"
        val textJson = JSONObject.quote(result.text)
        val displayJson = displayId?.let { ",\"displayId\":" + it } ?: ""
        respond(
            output,
            200,
            "{\"ok\":" + result.ok + ",\"exitCode\":" + result.exitCode + ",\"text\":" + textJson + ",\"truncated\":" + result.truncated + displayJson + ",\"errorCode\":" + errorJson + "}",
        )
    }

    private fun readBody(input: BufferedInputStream, contentLength: Int): String {
        if (contentLength !in 1..MAX_REQUEST_BODY_BYTES) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求长度无效")
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

        /** 根通道分支的占位会话标识：该分支不建 Shizuku 会话，这个值不会与任何真实会话重名。 */
        private const val ROOT_ENGINE_SESSION_ID = "root-engine"
        private const val STOP_WAIT_MILLIS = 500L
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        /** 文件上传包含 Base64；仍保持一个受控上限，避免把桥变成无限内存入口。 */
        private const val MAX_REQUEST_BODY_BYTES = 200_000
    }
}
