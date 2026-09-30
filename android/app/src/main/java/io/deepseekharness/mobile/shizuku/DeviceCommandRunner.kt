package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Device UI automation over an existing, user-visible device Shell session.
 *
 * 安全模型（不变的部分）：不引入新的可执行文件；调用方只能从固定白名单操作里选一个，
 * 参数在这里校验，危险 shell 语法在构造阶段就被拒绝；命令内容不写入审计日志。
 *
 * 变化的部分（本次修复）：注入文本由「直接打进交互式 PTY 的一行命令」改为
 * `/system/bin/sh -c '<固定模板>'`。原因是 mksh 的行编辑器**无视终端 ECHO**
 * （`stty -echo` 无效），注入文本必然被回显：既污染 payload（截图 base64 头部），
 * 又让「按回显间隙解析」在短命令上必然误判。内层脚本是非交互式 shell，不会回显
 * 自己的命令行，因此两个真实标记之间的 payload 只包含命令自身的输出。
 * 全部参数拼接后还要整体过一遍单引号转义（见 [singleQuote]），
 * 即使字段校验被绕过也无法逃出模板。
 */
enum class DeviceCommand {
    SCREENSHOT,
    UI_DUMP,
    TAP,
    INPUT_TEXT,
    DEVICE_INFO,
    LIST_PACKAGES,
    GET_SETTING,
    BATTERY,
    /** 启动指定包名的默认入口。 */
    LAUNCH_APP,
    /** 读取当前前台 Activity 对应的包名诊断行。 */
    FOREGROUND_PACKAGE,
    /** 等待有限时长，用于等待应用状态稳定。 */
    WAIT,
    /** 只读列出投递区目录的一层条目。 */
    FILE_LIST,
    /** 读取投递区文件并返回有界 Base64。 */
    FILE_READ,
    /** 写入投递区文件，默认不覆盖已有文件。 */
    FILE_WRITE,
    /** 创建投递区目录。 */
    FILE_MKDIR,
    /** 文件通道的语义别名：从 Android 下载到 AI。 */
    FILE_DOWNLOAD,
    /** 文件通道的语义别名：从 AI 上传到 Android。 */
    FILE_UPLOAD,
    /** AI 提交给 Shizuku 的一次性 Shell 脚本；脚本正文不写入日志。 */
    SHELL,
    /** 查询系统允许 Shell 观察的进程、服务或 Activity 摘要。 */
    BACKGROUND_TASKS;

    companion object {
        fun fromName(name: String): DeviceCommand? = when (name) {
            "screenshot" -> SCREENSHOT
            "uiDump" -> UI_DUMP
            "tap" -> TAP
            "inputText" -> INPUT_TEXT
            "deviceInfo" -> DEVICE_INFO
            "listPackages" -> LIST_PACKAGES
            "getSetting" -> GET_SETTING
            "battery" -> BATTERY
            "launchApp", "appLaunch" -> LAUNCH_APP
            "foregroundPackage", "currentApp" -> FOREGROUND_PACKAGE
            "wait" -> WAIT
            "fileList" -> FILE_LIST
            "fileRead" -> FILE_READ
            "fileWrite" -> FILE_WRITE
            "fileMkdir" -> FILE_MKDIR
            "fileDownload" -> FILE_DOWNLOAD
            "fileUpload" -> FILE_UPLOAD
            "shell" -> SHELL
            "backgroundTasks" -> BACKGROUND_TASKS
            else -> null
        }
    }
}

/**
 * 一次设备命令的结果。
 *
 * [text] 是回给上层的正文：成功时只有命令自身的输出；**受控失败**时除设备原始输出外，
 * 还会带一段简体中文的说明与下一步（见 [DeviceCommandRunner.explain]）——
 * 因为上层往往只拿到 [errorCode] 这一个受控码，没有说明就只能对着码猜。
 * [errorCode] 的取值是冻结契约，说明文字不改变它。
 */
data class DeviceCommandResult(
    val ok: Boolean,
    val exitCode: Int,
    val text: String,
    val truncated: Boolean,
    val errorCode: String?,
)

class DeviceCommandRunner(
    private val writer: (sessionId: String, dataBase64: String) -> Unit,
) {
    private class Pending(val requestId: String, val command: DeviceCommand) {
        val buffer = StringBuilder()
        val controlTail = StringBuilder()
        val done = CompletableFuture<DeviceCommandResult>()
        var truncated = false

        /** 已接收的总字符数（正文截断前）。 */
        var received = 0L

        /** [controlTail] 首字符在整条流里的绝对偏移。 */
        var tailStart = 0L

        /** 超时/协议失败时的诊断快照：正文窗口的内容（可能含回显）。 */
        fun snapshot(): String = synchronized(buffer) { buffer.toString() }
    }

    private val inflight = ConcurrentHashMap<String, Pending>()

    /**
     * Run one allowlisted command in [sessionId] and wait for its end marker.
     * Blocks the calling thread (a plugin executor worker) up to [timeoutMs].
     */
    fun execute(sessionId: String, command: DeviceCommand, param: String, timeoutMs: Long): DeviceCommandResult {
        val pending = Pending(UUID.randomUUID().toString(), command)
        if (inflight.putIfAbsent(sessionId, pending) != null) {
            throw RuntimeFailure("DEVICE_COMMAND_BUSY", "设备 Shell 会话正忙")
        }
        try {
            val input = buildInput(pending.requestId, command, param)
            // 不再有 stty -echo / 固定 sleep / 清空缓冲的时序：那套做法既关不掉 mksh 行编辑器的
            // 自带回显，又引入竞态（sleep 结束前到达的输出会被误清）。现在解析只认 shell 内部
            // 展开的双哨兵，回显既不能伪造标记，也不会落进两个标记之间的 payload。
            writer(sessionId, Base64.getEncoder().encodeToString(input.toByteArray(Charsets.US_ASCII)))
            return try {
                pending.done.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                DeviceCommandResult(
                    false,
                    -1,
                    explain("DEVICE_COMMAND_TIMEOUT", pending.snapshot()),
                    pending.truncated,
                    "DEVICE_COMMAND_TIMEOUT",
                )
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeFailure("DEVICE_COMMAND_INTERRUPTED", "设备命令被中断", error)
            } catch (_: ExecutionException) {
                DeviceCommandResult(false, -1, explain("DEVICE_COMMAND_FAILED", ""), false, "DEVICE_COMMAND_FAILED")
            }
        } finally {
            inflight.remove(sessionId, pending)
        }
    }

    /** Terminal output tap: aggregate until the shell-expanded end marker closes the request. */
    fun onOutput(sessionId: String, dataBase64: String) {
        val pending = inflight[sessionId] ?: return
        if (pending.done.isDone) return
        val text = try {
            String(Base64.getDecoder().decode(dataBase64), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return
        }
        synchronized(pending.buffer) {
            val remaining = MAX_BUFFER_CHARS - pending.buffer.length
            if (remaining > 0) {
                pending.buffer.append(text, 0, minOf(text.length, remaining))
                if (text.length > remaining) pending.truncated = true
            } else {
                pending.truncated = true
            }

            // 输出达到上限后仍保留一个独立的小尾窗：真实 END 标记位于流末尾，
            // 因而不会因正文截断而丢失并等待到超时。
            pending.controlTail.append(text)
            if (pending.controlTail.length > MAX_CONTROL_TAIL_CHARS) {
                pending.controlTail.delete(0, pending.controlTail.length - MAX_CONTROL_TAIL_CHARS)
            }
            pending.received += text.length
            pending.tailStart = pending.received - pending.controlTail.length

            // 廉价门：尾窗里还没出现「token 已展开」的 END 形态就不必全量扫描正文
            // （外层回显里带的 END 前缀是未展开字面量，不会触发扫描）。
            if (!DeviceCommandProtocol.appearsComplete(pending.requestId, pending.controlTail.toString())) return

            when (val parsed = parse(pending, streamEnded = false)) {
                is DeviceCommandParseResult.Pending -> Unit
                is DeviceCommandParseResult.Failed -> pending.done.complete(failure(parsed.errorCode, pending))
                is DeviceCommandParseResult.Completed -> pending.done.complete(completed(parsed, pending))
            }
        }
    }

    /**
     * 设备 Shell 会话已结束（Shizuku UserService 报告会话退出）。
     *
     * 没有这一步时，「BEGIN 已出现但 END 永远不来」只能等到 60 秒超时；
     * 有了它就能立刻给出受控错误码。已经完成的命令不受影响。
     */
    fun onSessionExit(sessionId: String) {
        val pending = inflight[sessionId] ?: return
        synchronized(pending.buffer) {
            if (pending.done.isDone) return
            when (val parsed = parse(pending, streamEnded = true)) {
                is DeviceCommandParseResult.Completed -> pending.done.complete(completed(parsed, pending))
                is DeviceCommandParseResult.Failed -> pending.done.complete(failure(parsed.errorCode, pending))
                is DeviceCommandParseResult.Pending -> pending.done.complete(failure(DeviceCommandProtocol.ERROR_PROTOCOL, pending))
            }
        }
    }

    /** Complete all pending requests (plugin teardown). */
    fun cancelAll() {
        inflight.values.forEach { pending ->
            if (!pending.done.isDone) {
                pending.done.complete(DeviceCommandResult(false, -1, explain("PLUGIN_DESTROYED", ""), false, "PLUGIN_DESTROYED"))
            }
        }
        inflight.clear()
    }

    private fun parse(pending: Pending, streamEnded: Boolean): DeviceCommandParseResult =
        DeviceCommandProtocol.parse(
            requestId = pending.requestId,
            head = pending.buffer.toString(),
            tail = pending.controlTail.toString(),
            tailStart = pending.tailStart,
            streamEnded = streamEnded,
        )

    private fun completed(parsed: DeviceCommandParseResult.Completed, pending: Pending): DeviceCommandResult {
        val errorCode = errorCodeFor(pending.command, parsed.exitCode)
        return DeviceCommandResult(
            ok = parsed.exitCode == 0,
            exitCode = parsed.exitCode,
            // 成功时正文原样返回（截图 base64 必须保持纯净）；失败时才追加说明。
            text = if (pending.command == DeviceCommand.SHELL) parsed.payload else explain(errorCode, parsed.payload),
            // 正文窗口被截断，或 payload 本身就超出了窗口，都按「输出被截断」上报。
            truncated = pending.truncated || parsed.payloadTruncated,
            errorCode = errorCode,
        )
    }

    private fun failure(errorCode: String, pending: Pending): DeviceCommandResult =
        DeviceCommandResult(false, -1, explain(errorCode, pending.snapshot()), pending.truncated, errorCode)

    /**
     * uiDump 的失败分级：退出码 3/4/5 分别是「没有 uiautomator」「dump 失败」「dump 成功但为空」，
     * 不再像旧实现那样被 `cat` 的 ENOENT 掩盖成统一的 DEVICE_COMMAND_FAILED。
     */
    private fun errorCodeFor(command: DeviceCommand, exitCode: Int): String? = when {
        exitCode == 0 -> null
        command.isFileCommand() -> when (exitCode) {
            FILE_NOT_FOUND_EXIT -> "DEVICE_FILE_NOT_FOUND"
            FILE_ESCAPE_EXIT -> "DEVICE_FILE_ESCAPE"
            FILE_TOO_LARGE_EXIT -> "DEVICE_FILE_TOO_LARGE"
            FILE_EXISTS_EXIT -> "DEVICE_FILE_EXISTS"
            FILE_FAILED_EXIT -> "DEVICE_FILE_FAILED"
            else -> "DEVICE_FILE_FAILED"
        }
        command == DeviceCommand.UI_DUMP -> when (exitCode) {
            UI_DUMP_EXIT_NO_TOOL -> "UI_DUMP_NO_TOOL"
            UI_DUMP_EXIT_FAILED -> "UI_DUMP_FAILED"
            UI_DUMP_EXIT_EMPTY -> "UI_DUMP_EMPTY"
            else -> "DEVICE_COMMAND_FAILED"
        }
        else -> "DEVICE_COMMAND_FAILED"
    }

    private fun DeviceCommand.isFileCommand(): Boolean = when (this) {
        DeviceCommand.FILE_LIST,
        DeviceCommand.FILE_READ,
        DeviceCommand.FILE_WRITE,
        DeviceCommand.FILE_MKDIR,
        DeviceCommand.FILE_DOWNLOAD,
        DeviceCommand.FILE_UPLOAD,
        -> true
        else -> false
    }

    /**
     * 受控失败码的**人话说明**：把「只报一个码」补成「说得清发生了什么、下一步能做什么」。
     *
     * 边界（不要越过）：
     *  - **码本身不变**。受控码是冻结契约（上层按码分流、按 `^[A-Z0-9_]{1,64}$` 转发），
     *    这里只往 [DeviceCommandResult.text] 里补说明；[guidanceFor] 的键必须与 [errorCodeFor]
     *    以及 [DeviceCommandProtocol] 的常量逐一对应，取值不准改动；
     *  - 设备原始输出**原样保留**在说明之后，说明不能替换证据；
     *  - 措辞不做绝对断言（不写「一定」「永远」），也不承诺修好设备或 ROM 侧的问题；
     *    只描述本机能确认的现象与用户可执行的下一步。
     */
    private fun explain(errorCode: String?, rawText: String): String {
        val guidance = errorCode?.let { guidanceFor(it) } ?: return rawText
        val evidence = rawText.trim()
        return if (evidence.isEmpty()) guidance else "$guidance\n\n设备原始输出：\n$evidence"
    }

    /** 受控失败码对应的简体中文说明与下一步；没有说明的码返回 null（正文保持不变）。 */
    private fun guidanceFor(errorCode: String): String? = when (errorCode) {
        "UI_DUMP_EMPTY" ->
            "本机 uiautomator 两次调用都返回 0，但在 /data/local/tmp 与 /sdcard 两条写入路径上都没有产出可读的层级文件。" +
                "常见于把 uiautomator 做成空壳的 ROM（命令返回 0 却不写产物），也可能是产物没有落到我们检查的那两个位置。" +
                "据此，本机的无障碍层级读取当前不可用，也没有可替代的层级来源。\n" +
                "下一步：改用 mobile_device_screenshot 观察当前界面；点击坐标应当来自截图或其它可信观察，不要凭猜测点击。"
        "UI_DUMP_NO_TOOL" ->
            "本机找不到可用的 uiautomator 命令（工具探测失败），因此读不到无障碍层级。\n" +
                "下一步：改用 mobile_device_screenshot 观察当前界面；本应用不会自行安装 uiautomator，" +
                "也不会用其它命令伪造一份层级。"
        "UI_DUMP_FAILED" ->
            "uiautomator dump 以非零退出码结束，设备侧的报错已原样保留在下面的「设备原始输出」里。\n" +
                "下一步：先看那段原始输出；也可以改用 mobile_device_screenshot 观察当前界面。"
        "DEVICE_COMMAND_FAILED" ->
            "设备命令以非零退出码结束（通用失败），设备侧输出已保留在下面的「设备原始输出」里。\n" +
                "下一步：结合原始输出判断原因；需要观察界面时优先用 mobile_device_screenshot，" +
                "失败后不要盲目重复同一操作。"
        "DEVICE_COMMAND_TIMEOUT" ->
            "设备命令在超时时间内没有闭合，而设备 Shell 会话仍然存活：命令可能没有跑完，" +
                "或设备侧没有按协议给出结束标记。\n" +
                "下一步：重试一次；若反复超时，请回到应用确认 Shizuku 状态与设备 Shell 是否正常。"
        DeviceCommandProtocol.ERROR_PROTOCOL ->
            "设备 Shell 的输出与协议不符：已经看到命令开始的标记，但没有等到配对的结束标记。\n" +
                "下一步：重试一次；若反复出现，请回到应用确认 Shizuku 状态与设备 Shell 是否正常。"
        DeviceCommandProtocol.ERROR_SESSION_LOST ->
            "设备 Shell 会话已经结束，而且没有看到命令开始的标记：这次调用没有真正执行。\n" +
                "下一步：回到应用确认 Shizuku 已授权并已连接，然后重试。"
        "PLUGIN_DESTROYED" ->
            "运行时已经结束，在途的设备命令被统一收口，这次调用没有执行。\n" +
            "下一步：重新启动运行时后再试。"
        "DEVICE_COMMAND_INVALID" ->
            "设备命令参数未通过安全校验，本次调用没有执行。\n下一步：只使用界面提供的白名单选项与格式。"
        "DEVICE_FILE_INVALID" ->
            "文件通道参数未通过安全校验，本次调用没有执行。\n下一步：只使用投递区 inbox/outbox 和相对路径。"
        "DEVICE_FILE_NOT_FOUND" ->
            "投递区目标不存在，或目标类型与操作不匹配。\n下一步：先用 mobile_device_file_list 查看目录条目。"
        "DEVICE_FILE_ESCAPE" ->
            "投递区路径解析越出固定目录，或目录包含不受信任的符号链接；本次调用已拒绝。\n下一步：改用 inbox/outbox 下的普通相对路径。"
        "DEVICE_FILE_TOO_LARGE" ->
            "文件超过 128 KiB 的 Shizuku 小文件通道限额。\n下一步：使用应用内投递区归档搬运较大文件。"
        "DEVICE_FILE_EXISTS" ->
            "目标已经存在；为避免静默覆盖，本次调用已拒绝。\n下一步：明确设置 overwrite=true 后再重试。"
        "DEVICE_FILE_FAILED" ->
            "投递区文件操作在设备侧失败，设备输出已保留在下面的原始输出中。\n下一步：检查投递区权限与目标目录后再重试。"
        else -> null
    }

    internal fun buildInput(requestId: String, command: DeviceCommand, param: String): String {
        if (!REQUEST_ID_PATTERN.matches(requestId)) {
            throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备命令请求标识无效")
        }
        val body = buildBody(requestId, command, param)
        // 标记里的 ${dsh_nonce} 保持**未展开的字面量**：回显看到的永远是它，
        // 只有内层 shell 展开后才会出现 `__DSH_B_<请求标识>_<pid>-<随机数>__` 这样的真实标记。
        val inner = "dsh_nonce=\$\$-\$RANDOM; " +
            "echo \"${DeviceCommandProtocol.beginMarker(requestId)}\"; " +
            body.script +
            "; echo \"${DeviceCommandProtocol.endMarker(requestId)}:${body.status}\""
        return "$INNER_SHELL -c ${singleQuote(inner)}\n"
    }

    /** 命令正文，以及 END 标记应当读取的退出码表达式。 */
    private class Body(val script: String, val status: String)

    private fun buildBody(requestId: String, command: DeviceCommand, param: String): Body = when (command) {
        DeviceCommand.SCREENSHOT -> Body("screencap -p | toybox base64", "\$?")
        // uiDump 的失败分级由脚本内的状态变量携带（3/4/5），而不是 `$?` 的 0/1。
        DeviceCommand.UI_DUMP -> Body(uiDumpScript(requestId), "\$$UI_DUMP_STATUS_VARIABLE")
        DeviceCommand.TAP -> {
            val parts = param.split(",", limit = 2)
            val x = parts.getOrNull(0)?.trim()?.toIntOrNull()
                ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "点击坐标无效")
            val y = parts.getOrNull(1)?.trim()?.toIntOrNull()
                ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "点击坐标无效")
            if (x !in 0..65535 || y !in 0..65535) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "点击坐标无效")
            Body("input tap $x $y", "\$?")
        }
        DeviceCommand.INPUT_TEXT -> {
            if (param.isEmpty() || param.length > MAX_TEXT_CHARS) {
                throw RuntimeFailure("DEVICE_COMMAND_INVALID", "输入文本无效")
            }
            if (param.any { it.code !in 0x20..0x7e || it == '\'' || it == '"' || it == '\\' || it == ';' || it == '$' || it == '\u0060' }) {
                throw RuntimeFailure("DEVICE_COMMAND_INVALID", "输入文本仅支持 ASCII 且不含引号/分号/反斜杠等字符")
            }
            // 双引号包裹（参数已排除 `"`、`$`、反引号、反斜杠），内层脚本因此完全不含单引号，
            // 与外层的单引号包裹不冲突。
            Body("input text \"$param\"", "\$?")
        }
        // 只读设备信息：限定字段，避免把完整 getprop（可能含厂商私有信息）回传到 WebView。
        DeviceCommand.DEVICE_INFO -> Body(
            "printf 'manufacturer='; getprop ro.product.manufacturer; " +
                "printf 'model='; getprop ro.product.model; " +
                "printf 'androidRelease='; getprop ro.build.version.release; " +
                "printf 'sdk='; getprop ro.build.version.sdk",
            "\$?",
        )
        // 仅列出已安装包名；查询字符串严格限制为包名字符，不能拼出 Shell 语法。
        DeviceCommand.LIST_PACKAGES -> {
            if (param.length > MAX_PACKAGE_QUERY_CHARS ||
                param.any { it !in PACKAGE_QUERY_CHARS }
            ) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "包名筛选条件无效")
            val filter = param.trim()
            val script = if (filter.isEmpty()) {
                "pm list packages | head -n 256"
            } else {
                "pm list packages | grep -F -- \"$filter\" | head -n 256"
            }
            Body(script, "\$?")
        }
        // 只开放对保活、屏幕与调试状态有用的非敏感设置键，拒绝读取 android_id 等隐私字段。
        DeviceCommand.GET_SETTING -> {
            if (param.length > MAX_SETTING_PARAM_CHARS) {
                throw RuntimeFailure("DEVICE_COMMAND_INVALID", "系统设置参数过长")
            }
            val parts = param.split(',', limit = 2).map { it.trim() }
            val namespace = parts.getOrNull(0).orEmpty()
            val key = parts.getOrNull(1).orEmpty()
            if (namespace !in SETTING_NAMESPACES || key !in SAFE_SETTING_KEYS) {
                throw RuntimeFailure("DEVICE_COMMAND_INVALID", "系统设置键不在允许范围内")
            }
            Body("settings get $namespace $key", "\$?")
        }
        // 电池状态是只读诊断信息，输出由上层的固定缓冲窗口限制大小。
        DeviceCommand.BATTERY -> {
            if (param.isNotBlank()) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "电池诊断不接受参数")
            Body("dumpsys battery | head -n 64", "\$?")
        }
        // 只允许标准 Android 包名，并通过 monkey 启动其默认入口；不接受 Activity、Intent 或 Shell 参数。
        DeviceCommand.LAUNCH_APP -> {
            if (param.length > MAX_PACKAGE_NAME_CHARS || !PACKAGE_NAME_PATTERN.matches(param)) {
                throw RuntimeFailure("DEVICE_COMMAND_INVALID", "应用包名格式无效")
            }
            Body("monkey -p \"$param\" 1", "\$?")
        }
        // 读取当前前台包名相关的系统诊断行，不返回完整 dumpsys，减少设备信息暴露。
        DeviceCommand.FOREGROUND_PACKAGE -> {
            if (param.isNotBlank()) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "前台应用查询不接受参数")
            Body(
                "dumpsys activity activities | grep -E \"mResumedActivity|mFocusedApp|topResumedActivity\" | head -n 4",
                "\$?",
            )
        }
        // 等待仅接受 100ms..10s，使用固定秒数表达式，避免把任意字符串送入 Shell。
        DeviceCommand.WAIT -> {
            val millis = param.toLongOrNull()
                ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "等待时长无效")
            if (millis !in MIN_WAIT_MILLIS..MAX_WAIT_MILLIS) {
                throw RuntimeFailure("DEVICE_COMMAND_INVALID", "等待时长超出允许范围")
            }
            val seconds = millis / 1000.0
            Body("sleep $seconds", "\$?")
        }
        DeviceCommand.FILE_LIST -> fileListBody(param)
        DeviceCommand.FILE_READ, DeviceCommand.FILE_DOWNLOAD -> fileReadBody(param)
        DeviceCommand.FILE_WRITE, DeviceCommand.FILE_UPLOAD -> fileWriteBody(param)
        DeviceCommand.FILE_MKDIR -> fileMkdirBody(param)
        DeviceCommand.BACKGROUND_TASKS -> {
            if (param.length > 240) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "后台任务查询参数过长")
            val request = try { org.json.JSONObject(param) } catch (_: Exception) {
                throw RuntimeFailure("DEVICE_COMMAND_INVALID", "后台任务查询参数格式无效")
            }
            val packageName = request.opt("packageName") as? String
                ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "需要目标应用包名")
            val kind = request.opt("kind") as? String
            if (request.length() != 2 || packageName.length > MAX_PACKAGE_NAME_CHARS || !PACKAGE_NAME_PATTERN.matches(packageName)) {
                throw RuntimeFailure("DEVICE_COMMAND_INVALID", "后台任务查询包名无效")
            }
            val target = singleQuote(packageName)
            val script = when (kind) {
                // 精确匹配主进程及包名冒号后缀，避免读到名称相似的其他应用。
                "processes" -> "ps -A -o PID,PPID,NAME | awk -v packageName=$target 'NR == 1 || \$3 == packageName || index(\$3, packageName \":\") == 1' | head -n 128"
                "services" -> "dumpsys activity services $target | grep -E 'ServiceRecord|app=ProcessRecord|isForeground=' | head -n 128"
                "activities" -> "dumpsys activity activities $target | grep -E 'ActivityRecord|Task\\{|mResumedActivity|topResumedActivity' | head -n 128"
                else -> throw RuntimeFailure("DEVICE_COMMAND_INVALID", "后台任务查询类型无效")
            }
            Body(script, "\$?")
        }
        DeviceCommand.SHELL -> {
            if (param.isBlank() || param.toByteArray(Charsets.UTF_8).size > MAX_SHELL_SCRIPT_CHARS || param.any { it == '\u0000' || it == '\r' }) {
                throw RuntimeFailure("DEVICE_COMMAND_INVALID", "Shell 脚本为空、过长或包含非法控制字符")
            }
            // 脚本作为编码后的标准输入送入独立解释器，不能破坏外层结果协议。
            // 不使用关键字黑名单；命令的实际能力由用户授予的 Shizuku 身份决定。
            val encoded = Base64.getEncoder().encodeToString(param.toByteArray(Charsets.UTF_8))
            Body("printf '%s' '$encoded' | toybox base64 -d | /system/bin/sh -s", "\$?")
        }
    }

    /** 生成投递区文件命令的公共安全检查：根目录固定，真实路径由 readlink 再次收敛。 */
    private fun filePrelude(request: DeviceFilePolicy.Request): String {
        val root = singleQuote(request.root.absolutePath)
        val path = singleQuote(request.path)
        return "dsh_root=$root; " +
            "dsh_root_real=\$(readlink -f \"\$dsh_root\" 2>/dev/null) || exit $FILE_ESCAPE_EXIT; " +
            "[ \"\$dsh_root_real\" = \"\$dsh_root\" ] || exit $FILE_ESCAPE_EXIT; " +
            "dsh_target=\"\$dsh_root\"/$path; " +
            "dsh_parent=\$(dirname \"\$dsh_target\"); " +
            "dsh_parent_real=\$(readlink -f \"\$dsh_parent\" 2>/dev/null) || exit $FILE_ESCAPE_EXIT; " +
            "case \"\$dsh_parent_real\" in \"\$dsh_root\"|\"\$dsh_root\"/*) ;; *) exit $FILE_ESCAPE_EXIT ;; esac; "
    }

    private fun fileListBody(param: String): Body {
        val request = DeviceFilePolicy.parse(param)
        val script = filePrelude(request) +
            "if [ -L \"\$dsh_target\" ] || [ ! -d \"\$dsh_target\" ]; then exit $FILE_NOT_FOUND_EXIT; fi; " +
            "ls -1Ap \"\$dsh_target\" | head -n ${DeviceFilePolicy.MAX_LIST_ENTRIES}"
        return Body(script, "\$?")
    }

    private fun fileReadBody(param: String): Body {
        val request = DeviceFilePolicy.parse(param)
        if (request.path.isEmpty()) throw RuntimeFailure("DEVICE_FILE_INVALID", "读取目标不能为空")
        val script = filePrelude(request) +
            "if [ -L \"\$dsh_target\" ] || [ ! -f \"\$dsh_target\" ]; then exit $FILE_NOT_FOUND_EXIT; fi; " +
            "dsh_real=\$(readlink -f \"\$dsh_target\" 2>/dev/null) || exit $FILE_ESCAPE_EXIT; " +
            "case \"\$dsh_real\" in \"\$dsh_root\"|\"\$dsh_root\"/*) ;; *) exit $FILE_ESCAPE_EXIT ;; esac; " +
            "dsh_size=\$(wc -c < \"\$dsh_target\") || exit $FILE_FAILED_EXIT; " +
            "[ \"\$dsh_size\" -le ${DeviceFilePolicy.MAX_FILE_BYTES} ] || exit $FILE_TOO_LARGE_EXIT; " +
            "toybox base64 \"\$dsh_target\""
        return Body(script, "\$?")
    }

    private fun fileWriteBody(param: String): Body {
        val request = DeviceFilePolicy.parse(param, requireContent = true)
        if (request.path.isEmpty()) throw RuntimeFailure("DEVICE_FILE_INVALID", "写入目标不能为空")
        val content = request.contentBase64 ?: throw RuntimeFailure("DEVICE_FILE_INVALID", "文件内容缺失")
        val encoded = singleQuote(content)
        val overwrite = if (request.overwrite) "1" else "0"
        val script = filePrelude(request) +
            "if [ -e \"\$dsh_target\" ] || [ -L \"\$dsh_target\" ]; then " +
            "[ \"$overwrite\" = 1 ] || exit $FILE_EXISTS_EXIT; fi; " +
            "dsh_tmp=\"\$dsh_parent/.dsh-upload-\$\$\"; " +
            "rm -f \"\$dsh_tmp\"; " +
            "printf '%s' $encoded | toybox base64 -d > \"\$dsh_tmp\" || { rm -f \"\$dsh_tmp\"; exit $FILE_FAILED_EXIT; }; " +
            "dsh_size=\$(wc -c < \"\$dsh_tmp\") || { rm -f \"\$dsh_tmp\"; exit $FILE_FAILED_EXIT; }; " +
            "[ \"\$dsh_size\" -le ${DeviceFilePolicy.MAX_FILE_BYTES} ] || { rm -f \"\$dsh_tmp\"; exit $FILE_TOO_LARGE_EXIT; }; " +
            "mv -f \"\$dsh_tmp\" \"\$dsh_target\" || { rm -f \"\$dsh_tmp\"; exit $FILE_FAILED_EXIT; }"
        return Body(script, "\$?")
    }

    private fun fileMkdirBody(param: String): Body {
        val request = DeviceFilePolicy.parse(param)
        if (request.path.isEmpty()) throw RuntimeFailure("DEVICE_FILE_INVALID", "目录目标不能为空")
        // mkdir -p 会跟随中间目录的符号链接；先后逐级检查，避免投递区内的链接把目标带出固定根目录。
        val noFollowChecks = buildString {
            append("dsh_cursor=\"\$dsh_root\"; ")
            append("[ ! -L \"\$dsh_cursor\" ] || exit $FILE_ESCAPE_EXIT; ")
            request.path.split('/').forEach { component ->
                append("dsh_cursor=\"\$dsh_cursor\"/${singleQuote(component)}; ")
                append("[ ! -L \"\$dsh_cursor\" ] || exit $FILE_ESCAPE_EXIT; ")
            }
        }
        val script = filePrelude(request) +
            noFollowChecks +
            "if [ -e \"\$dsh_target\" ] || [ -L \"\$dsh_target\" ]; then " +
            "[ ! -L \"\$dsh_target\" ] || exit $FILE_ESCAPE_EXIT; " +
            "[ -d \"\$dsh_target\" ] || exit $FILE_EXISTS_EXIT; " +
            "else mkdir -p \"\$dsh_target\" || exit $FILE_FAILED_EXIT; fi" +
            noFollowChecks +
            "[ -d \"\$dsh_cursor\" ] || exit $FILE_FAILED_EXIT; "
        return Body(script, "\$?")
    }

    /**
     * uiDump 加固：先探测工具，再把 dump 的失败与 stderr 原样保留（不再被 `cat` 的 ENOENT 掩盖），
     * 最后校验产物非空。三类失败用不同退出码区分，由 [errorCodeFor] 映射成不同错误码。
     *
     * 不使用 `dumpsys window` 伪造无障碍层级；它不提供节点边界，冒充 UI 层级会误导模型。
     * 两条真实 uiautomator 路径都没有有效 XML 时，才如实上报 UI_DUMP_EMPTY / UI_DUMP_FAILED。
     */
    private fun uiDumpScript(requestId: String): String {
        val temporary = "/data/local/tmp/dsh-ui-$requestId.xml"
        val fallback = "/sdcard/dsh-ui-$requestId.xml"
        // dsh_rc 先落到 0（成功），失败分支再覆盖成 3/4/5：END 标记因此永远带着一个合法数字。
        // trap 用双引号定义：内层脚本因此不含单引号，$dsh_tmp 在定义时即展开成固定路径。
        return "dsh_tmp=$temporary; dsh_fallback=$fallback; $UI_DUMP_STATUS_VARIABLE=0; " +
            "trap \"rm -f \$dsh_tmp \$dsh_fallback\" EXIT HUP INT TERM; " +
            "if command -v uiautomator >/dev/null 2>&1; then " +
            "dsh_first=0; uiautomator dump --compressed \$dsh_tmp 2>&1 || dsh_first=\$?; " +
            "if [ -s \$dsh_tmp ]; then cat \$dsh_tmp; " +
            // Older Android releases accept the legacy form but reject --compressed.
            "else dsh_second=0; uiautomator dump \$dsh_fallback 2>&1 || dsh_second=\$?; " +
            "if [ -s \$dsh_fallback ]; then cat \$dsh_fallback; " +
            "elif [ \$dsh_first -ne 0 ] || [ \$dsh_second -ne 0 ]; then echo \"UI_DUMP_FAILED: uiautomator dump failed\" >&2; " +
            "$UI_DUMP_STATUS_VARIABLE=$UI_DUMP_EXIT_FAILED; " +
            "else echo \"UI_DUMP_EMPTY: uiautomator produced no readable hierarchy\" >&2; " +
            "$UI_DUMP_STATUS_VARIABLE=$UI_DUMP_EXIT_EMPTY; fi; fi; " +
            "else echo \"UI_DUMP_NO_TOOL: uiautomator is not available\" >&2; " +
            "$UI_DUMP_STATUS_VARIABLE=$UI_DUMP_EXIT_NO_TOOL; fi"
    }

    private fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val INNER_SHELL = "/system/bin/sh"
        private const val UI_DUMP_STATUS_VARIABLE = "dsh_rc"
        private const val UI_DUMP_EXIT_NO_TOOL = 3
        private const val UI_DUMP_EXIT_FAILED = 4
        private const val UI_DUMP_EXIT_EMPTY = 5
        private const val FILE_NOT_FOUND_EXIT = 4
        private const val FILE_TOO_LARGE_EXIT = 5
        private const val FILE_ESCAPE_EXIT = 6
        private const val FILE_EXISTS_EXIT = 7
        private const val FILE_FAILED_EXIT = 8
        private const val MAX_BUFFER_CHARS = 8 * 1024 * 1024

        /**
         * 尾部窗口只需要容纳「真实 END 标记 + 其后的小尾巴」；标记本身含 36 字符请求标识
         * 与 `${dsh_nonce}` 展开后的数字 token，因此比旧哨兵长，这里留出更大余量。
         */
        private const val MAX_CONTROL_TAIL_CHARS = 512
        private const val MAX_TEXT_CHARS = 1024
        private const val MAX_SHELL_SCRIPT_CHARS = 16 * 1024
        private const val MAX_PACKAGE_QUERY_CHARS = 128
        private const val MAX_PACKAGE_NAME_CHARS = 192
        private const val MAX_SETTING_PARAM_CHARS = 256
        private const val MIN_WAIT_MILLIS = 100L
        private const val MAX_WAIT_MILLIS = 10_000L
        private val PACKAGE_QUERY_CHARS = ('a'..'z').toSet() + ('A'..'Z').toSet() + ('0'..'9').toSet() + setOf('.', '_', '-')
        private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$")
        private val SETTING_NAMESPACES = setOf("system", "secure", "global")
        private val SAFE_SETTING_KEYS = setOf(
            "adb_enabled",
            "development_settings_enabled",
            "stay_on_while_plugged_in",
            "screen_brightness",
            "screen_off_timeout",
            "accelerometer_rotation",
            "user_rotation",
        )
        private val REQUEST_ID_PATTERN = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
    }
}
