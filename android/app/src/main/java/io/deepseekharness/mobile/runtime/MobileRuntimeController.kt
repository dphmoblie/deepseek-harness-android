package io.deepseekharness.mobile.runtime

import android.content.Context
import io.deepseekharness.mobile.shizuku.ShizukuState
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

class MobileRuntimeController(
    context: Context,
    onProgress: (RuntimeStateSnapshot) -> Unit,
    onTerminalOutput: (sessionId: String, dataBase64: String) -> Unit,
    onTerminalExit: (sessionId: String, exitCode: Int) -> Unit,
) {
    private val lifecycleLock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    val store = RuntimeStore(context)
    val status = RuntimeStatus(store).also { it.progressListener = onProgress }
    private val installer = RuntimeInstaller(store, status, externalCancellation = closed::get)
    private val supervisor = RuntimeSupervisor(context, store, status)
    val terminals = TerminalCoordinator(context, store, onTerminalOutput, onTerminalExit)

    fun install(source: RuntimeSource) = lifecycleLock.withLock {
        ensureOpen()
        if (supervisor.isRunning() || terminals.hasRuntimeSessions()) {
            throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
        }
        installer.install(source)
    }

    fun startHarness(): RuntimeStateSnapshot = lifecycleLock.withLock {
        ensureOpen()
        supervisor.startHarness()
    }

    fun stopRuntime(): RuntimeStateSnapshot = lifecycleLock.withLock {
        ensureOpen()
        supervisor.stop()
        terminals.closeAllAndWait()
        status.refreshIdle()
    }

    fun reset(confirmation: String?): RuntimeStateSnapshot = lifecycleLock.withLock {
        ensureOpen()
        if (confirmation != "RESET_RUNTIME") {
            throw RuntimeFailure("RESET_CONFIRMATION_INVALID", "重置确认文本无效")
        }
        supervisor.stop()
        terminals.closeAllAndWait()
        installer.resetWorkspace()
        status.snapshot()
    }

    fun createTerminal(kind: String, columns: Int, rows: Int): String = lifecycleLock.withLock {
        ensureOpen()
        terminals.create(kind, columns, rows)
    }

    fun writeTerminal(sessionId: String, dataBase64: String) = lifecycleLock.withLock {
        ensureOpen()
        terminals.write(sessionId, dataBase64)
    }

    fun resizeTerminal(sessionId: String, columns: Int, rows: Int) = lifecycleLock.withLock {
        ensureOpen()
        terminals.resize(sessionId, columns, rows)
    }

    fun closeTerminal(sessionId: String) = lifecycleLock.withLock {
        ensureOpen()
        terminals.closeAndWait(sessionId)
    }

    fun requestShizukuPermission(): ShizukuState = lifecycleLock.withLock {
        ensureOpen()
        terminals.shizuku.requestPermission()
    }

    fun openShizukuManager() = lifecycleLock.withLock {
        ensureOpen()
        terminals.shizuku.openManager()
    }

    fun openHarnessAccess(): HarnessAccess = lifecycleLock.withLock {
        ensureOpen()
        supervisor.access()
    }

    fun state(): RuntimeStateSnapshot = status.snapshot()
    fun shizukuState(): ShizukuState = lifecycleLock.withLock {
        ensureOpen()
        terminals.shizuku.state()
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        installer.cancelInstall()
        lifecycleLock.withLock {
            BestEffortCleanup.runAll(
                { supervisor.stop() },
                { terminals.shutdown() },
            )
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw RuntimeFailure("RUNTIME_CLOSED", "本机运行时正在关闭")
    }
}
