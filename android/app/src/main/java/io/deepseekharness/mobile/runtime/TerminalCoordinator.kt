package io.deepseekharness.mobile.runtime

import android.content.Context
import io.deepseekharness.mobile.shizuku.ShizukuRuntime
import java.util.Base64
import java.util.concurrent.TimeUnit

class TerminalCoordinator(
    context: Context,
    store: RuntimeStore,
    onOutput: (sessionId: String, dataBase64: String) -> Unit,
    onExit: (sessionId: String, exitCode: Int) -> Unit,
) {
    val shizuku = ShizukuRuntime(context, onOutput, onExit)
    private val ubuntu = UbuntuTerminalManager(context, store, onOutput, onExit)

    fun create(kind: String, columns: Int, rows: Int): String = when (kind) {
        "ubuntu" -> ubuntu.create(columns, rows)
        "device" -> shizuku.create(columns, rows)
        else -> throw RuntimeFailure("TERMINAL_KIND_INVALID", "终端类型无效")
    }

    fun write(sessionId: String, dataBase64: String) {
        if (dataBase64.isEmpty() || dataBase64.length > RuntimeLimits.MAX_TERMINAL_INPUT_BYTES * 2) {
            throw RuntimeFailure("TERMINAL_INPUT_INVALID", "终端输入长度无效")
        }
        val data = try {
            Base64.getDecoder().decode(dataBase64)
        } catch (error: IllegalArgumentException) {
            throw RuntimeFailure("TERMINAL_INPUT_INVALID", "终端输入 Base64 编码无效", error)
        }
        when {
            ubuntu.contains(sessionId) -> ubuntu.write(sessionId, data)
            shizuku.contains(sessionId) -> shizuku.write(sessionId, data)
            else -> throw RuntimeFailure("SESSION_NOT_FOUND", "终端会话不存在或已结束")
        }
    }

    fun resize(sessionId: String, columns: Int, rows: Int) {
        when {
            ubuntu.contains(sessionId) -> ubuntu.resize(sessionId, columns, rows)
            shizuku.contains(sessionId) -> shizuku.resize(sessionId, columns, rows)
            else -> throw RuntimeFailure("SESSION_NOT_FOUND", "终端会话不存在或已结束")
        }
    }

    fun close(sessionId: String) {
        when {
            ubuntu.contains(sessionId) -> ubuntu.close(sessionId)
            shizuku.contains(sessionId) -> shizuku.close(sessionId)
            else -> throw RuntimeFailure("SESSION_NOT_FOUND", "终端会话不存在或已结束")
        }
    }

    fun closeAndWait(sessionId: String, timeoutMillis: Long = 4_000) {
        close(sessionId)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while ((ubuntu.contains(sessionId) || shizuku.contains(sessionId)) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeFailure("TERMINAL_CLOSE_INTERRUPTED", "等待终端关闭时被中断", error)
            }
        }
        if (ubuntu.contains(sessionId) || shizuku.contains(sessionId)) {
            throw RuntimeFailure("TERMINAL_CLOSE_TIMEOUT", "终端未在限定时间内关闭")
        }
    }

    fun hasRuntimeSessions(): Boolean = ubuntu.hasSessions()
    fun hasAnySessions(): Boolean = ubuntu.hasSessions() || shizuku.hasSessions()

    fun closeAllAndWait() {
        ubuntu.closeAllAndWait()
        shizuku.closeAllAndWait()
    }

    fun shutdown() {
        BestEffortCleanup.runAll(
            { ubuntu.shutdown() },
            { shizuku.shutdown() },
        )
    }
}
