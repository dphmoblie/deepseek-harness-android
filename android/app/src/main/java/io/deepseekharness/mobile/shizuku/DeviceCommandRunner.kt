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
 * 安全模型（不变的部分）：不引入新的可执行文件；调用方只能从四个白名单操作里选一个，
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
    SCREENSHOT, UI_DUMP, TAP, INPUT_TEXT;

    companion object {
        fun fromName(name: String): DeviceCommand? = when (name) {
            "screenshot" -> SCREENSHOT
            "uiDump" -> UI_DUMP
            "tap" -> TAP
            "inputText" -> INPUT_TEXT
            else -> null
        }
    }
}

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
                DeviceCommandResult(false, -1, pending.snapshot(), pending.truncated, "DEVICE_COMMAND_TIMEOUT")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeFailure("DEVICE_COMMAND_INTERRUPTED", "设备命令被中断", error)
            } catch (_: ExecutionException) {
                DeviceCommandResult(false, -1, "", false, "DEVICE_COMMAND_FAILED")
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
            if (!pending.done.isDone) pending.done.complete(DeviceCommandResult(false, -1, "", false, "PLUGIN_DESTROYED"))
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

    private fun completed(parsed: DeviceCommandParseResult.Completed, pending: Pending): DeviceCommandResult =
        DeviceCommandResult(
            ok = parsed.exitCode == 0,
            exitCode = parsed.exitCode,
            text = parsed.payload,
            // 正文窗口被截断，或 payload 本身就超出了窗口，都按「输出被截断」上报。
            truncated = pending.truncated || parsed.payloadTruncated,
            errorCode = errorCodeFor(pending.command, parsed.exitCode),
        )

    private fun failure(errorCode: String, pending: Pending): DeviceCommandResult =
        DeviceCommandResult(false, -1, pending.snapshot(), pending.truncated, errorCode)

    /**
     * uiDump 的失败分级：退出码 3/4/5 分别是「没有 uiautomator」「dump 失败」「dump 成功但为空」，
     * 不再像旧实现那样被 `cat` 的 ENOENT 掩盖成统一的 DEVICE_COMMAND_FAILED。
     */
    private fun errorCodeFor(command: DeviceCommand, exitCode: Int): String? = when {
        exitCode == 0 -> null
        command == DeviceCommand.UI_DUMP -> when (exitCode) {
            UI_DUMP_EXIT_NO_TOOL -> "UI_DUMP_NO_TOOL"
            UI_DUMP_EXIT_FAILED -> "UI_DUMP_FAILED"
            UI_DUMP_EXIT_EMPTY -> "UI_DUMP_EMPTY"
            else -> "DEVICE_COMMAND_FAILED"
        }
        else -> "DEVICE_COMMAND_FAILED"
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
    }

    /**
     * uiDump 加固：先探测工具，再把 dump 的失败与 stderr 原样保留（不再被 `cat` 的 ENOENT 掩盖），
     * 最后校验产物非空。三类失败用不同退出码区分，由 [errorCodeFor] 映射成不同错误码。
     *
     * 不做降级：本 ROM 上 `uiautomator dump` 是「退出码 0 + 零输出 + 零文件 + 约 0ms」的静默空壳，
     * 而 `dumpsys window` 之类并不提供无障碍节点边界，冒充 UI 层级只会误导模型，故如实上报
     * UI_DUMP_EMPTY / UI_DUMP_NO_TOOL / UI_DUMP_FAILED，由调用方决定后续动作。
     */
    private fun uiDumpScript(requestId: String): String {
        val temporary = "/data/local/tmp/dsh-ui-$requestId.xml"
        // dsh_rc 先落到 0（成功），失败分支再覆盖成 3/4/5：END 标记因此永远带着一个合法数字。
        // trap 用双引号定义：内层脚本因此不含单引号，$dsh_tmp 在定义时即展开成固定路径。
        return "dsh_tmp=$temporary; $UI_DUMP_STATUS_VARIABLE=0; " +
            "trap \"rm -f \$dsh_tmp\" EXIT HUP INT TERM; " +
            "if command -v uiautomator >/dev/null 2>&1; then " +
            "if uiautomator dump \$dsh_tmp 2>&1; then " +
            "if [ -s \$dsh_tmp ]; then cat \$dsh_tmp; " +
            "else echo \"UI_DUMP_EMPTY: uiautomator exited 0 but wrote no file\" >&2; " +
            "$UI_DUMP_STATUS_VARIABLE=$UI_DUMP_EXIT_EMPTY; fi; " +
            "else echo \"UI_DUMP_FAILED: uiautomator dump failed\" >&2; " +
            "$UI_DUMP_STATUS_VARIABLE=$UI_DUMP_EXIT_FAILED; fi; " +
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
        private const val MAX_BUFFER_CHARS = 8 * 1024 * 1024

        /**
         * 尾部窗口只需要容纳「真实 END 标记 + 其后的小尾巴」；标记本身含 36 字符请求标识
         * 与 `${dsh_nonce}` 展开后的数字 token，因此比旧哨兵长，这里留出更大余量。
         */
        private const val MAX_CONTROL_TAIL_CHARS = 512
        private const val MAX_TEXT_CHARS = 1024
        private val REQUEST_ID_PATTERN = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
    }
}
