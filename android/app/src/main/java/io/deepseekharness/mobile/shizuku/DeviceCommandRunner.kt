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
 * Security model (unchanged): no new executable, no argv injection surface.
 * Commands are fixed strings typed into the already-open PTY session (which
 * runs the fixed /system/bin/sh under the Shizuku user service); the caller
 * only picks one of four allowlisted operations and parameters that are
 * validated here. Dangerous shell syntax is rejected at build time. Command
 * content is deliberately NOT written to the audit log (existing policy).
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
    private class Pending(val requestId: String) {
        val buffer = StringBuilder()
        val done = CompletableFuture<DeviceCommandResult>()
        var truncated = false
    }

    private val inflight = ConcurrentHashMap<String, Pending>()

    /**
     * Run one allowlisted command in [sessionId] and wait for its sentinel.
     * Blocks the calling thread (a plugin executor worker) up to [timeoutMs].
     */
    fun execute(sessionId: String, command: DeviceCommand, param: String, timeoutMs: Long): DeviceCommandResult {
        val pending = Pending(UUID.randomUUID().toString())
        if (inflight.putIfAbsent(sessionId, pending) != null) {
            throw RuntimeFailure("DEVICE_COMMAND_BUSY", "设备 Shell 会话正忙")
        }
        try {
            val input = buildInput(pending.requestId, command, param)
            // 1) 关回显（该行本身会在关闭前被回显），2) 等待回显落定后清空缓冲，3) 注入命令。
            writer(sessionId, Base64.getEncoder().encodeToString("stty -echo\n".toByteArray(Charsets.US_ASCII)))
            Thread.sleep(ECHO_SETTLE_MILLIS)
            synchronized(pending.buffer) { pending.buffer.setLength(0) }
            writer(sessionId, Base64.getEncoder().encodeToString(input.toByteArray(Charsets.US_ASCII)))
            return try {
                pending.done.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                DeviceCommandResult(false, -1, pending.buffer.toString(), pending.truncated, "DEVICE_COMMAND_TIMEOUT")
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

    /** Terminal output tap: aggregate until the per-request sentinel line. */
    fun onOutput(sessionId: String, dataBase64: String) {
        val pending = inflight[sessionId] ?: return
        if (pending.done.isDone) return
        val text = try {
            String(Base64.getDecoder().decode(dataBase64), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return
        }
        synchronized(pending.buffer) {
            if (pending.buffer.length >= MAX_BUFFER_CHARS) {
                pending.truncated = true
            } else {
                val remaining = MAX_BUFFER_CHARS - pending.buffer.length
                pending.buffer.append(text, 0, minOf(text.length, remaining))
                if (text.length > remaining) pending.truncated = true
            }
            val sentinel = "__DSH_END_" + pending.requestId + "__"
            val idx = pending.buffer.indexOf(sentinel)
            if (idx >= 0) {
                val payload = pending.buffer.substring(0, idx)
                val rest = pending.buffer.substring(idx + sentinel.length)
                val exitCode = Regex("^:(\\d{1,3})").find(rest)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 255) ?: -1
                pending.done.complete(DeviceCommandResult(true, exitCode, payload, pending.truncated, null))
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

    private fun buildInput(requestId: String, command: DeviceCommand, param: String): String {
        val line = when (command) {
            DeviceCommand.SCREENSHOT -> "screencap -p | toybox base64"
            DeviceCommand.UI_DUMP -> "uiautomator dump /data/local/tmp/dsh-ui.xml && cat /data/local/tmp/dsh-ui.xml"
            DeviceCommand.TAP -> {
                val parts = param.split(",", limit = 2)
                val x = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "点击坐标无效")
                val y = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "点击坐标无效")
                if (x < 0 || y < 0) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "点击坐标无效")
                "input tap $x $y"
            }
            DeviceCommand.INPUT_TEXT -> {
                if (param.isEmpty() || param.length > MAX_TEXT_CHARS) {
                    throw RuntimeFailure("DEVICE_COMMAND_INVALID", "输入文本无效")
                }
                if (param.any { it.code > 0x7f || it == '\'' || it == '"' || it == '\\' || it == ';' || it == '$' || it == '\u0060' }) {
                    throw RuntimeFailure("DEVICE_COMMAND_INVALID", "输入文本仅支持 ASCII 且不含引号/分号/反斜杠等字符")
                }
                "input text '$param'"
            }
        }
        return line + "; echo __DSH_END_" + requestId + "__:$?\n"
    }

    companion object {
        private const val ECHO_SETTLE_MILLIS = 250L
        private const val MAX_BUFFER_CHARS = 8 * 1024 * 1024
        private const val MAX_TEXT_CHARS = 1024
    }
}
