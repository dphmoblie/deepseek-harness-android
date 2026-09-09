package io.deepseekharness.mobile.runtime

import android.content.Context
import io.deepseekharness.mobile.shizuku.ShizukuState
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

class MobileRuntimeController(
    context: Context,
    onProgress: (RuntimeStateSnapshot) -> Unit,
    onTerminalOutput: (sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) -> Unit,
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

    fun requestStartCancellation(): Boolean = supervisor.requestStartCancellation()

    fun configureDeviceBridge(access: DeviceBridgeAccess) = lifecycleLock.withLock {
        ensureOpen()
        supervisor.configureDeviceBridge(access)
    }

    fun saveSettings(
        settings: RuntimeSettings,
        providerApiKeyUpdates: Map<ModelProvider, String>,
        clearedProviderApiKeys: Set<ModelProvider>,
        customProviders: List<CustomModelProvider>,
        customProviderApiKeyUpdates: Map<String, String>,
        clearedCustomProviderApiKeys: Set<String>,
    ): RuntimeSettings = lifecycleLock.withLock {
        ensureOpen()
        val modelConfigurationChanged = providerApiKeyUpdates.isNotEmpty() || clearedProviderApiKeys.isNotEmpty() ||
            customProviderApiKeyUpdates.isNotEmpty() || clearedCustomProviderApiKeys.isNotEmpty() ||
            customProviders != store.settings().customModelProviders
        val restartHarness = modelConfigurationChanged && supervisor.isRunning()
        if (modelConfigurationChanged) supervisor.stop()
        val saved = store.saveSettings(
            settings,
            providerApiKeyUpdates,
            clearedProviderApiKeys,
            customProviders,
            customProviderApiKeyUpdates,
            clearedCustomProviderApiKeys,
        )
        if (restartHarness) supervisor.startHarness()
        saved
    }

    fun stopRuntime(): RuntimeStateSnapshot {
        supervisor.requestStartCancellation()
        return lifecycleLock.withLock {
            ensureOpen()
            BestEffortCleanup.runAll(
                { supervisor.stop() },
                { terminals.closeAllAndWait() },
            )
            status.refreshIdle()
        }
    }

    fun reset(confirmation: String?): RuntimeStateSnapshot {
        ensureOpen()
        if (confirmation != "RESET_RUNTIME") {
            throw RuntimeFailure("RESET_CONFIRMATION_INVALID", "重置确认文本无效")
        }
        supervisor.requestStartCancellation()
        return lifecycleLock.withLock {
            ensureOpen()
            BestEffortCleanup.runAll(
                { supervisor.stop() },
                { terminals.closeAllAndWait() },
            )
            installer.resetWorkspace()
            status.snapshot()
        }
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

    fun hasDeviceSession(sessionId: String): Boolean = lifecycleLock.withLock {
        ensureOpen()
        terminals.shizuku.contains(sessionId)
    }

    fun requestShizukuPermission(): ShizukuState = lifecycleLock.withLock {
        ensureOpen()
        terminals.shizuku.requestPermission()
    }

    fun connectShizuku(): ShizukuState = lifecycleLock.withLock {
        ensureOpen()
        // 授权已存在时直接绑定 Shizuku UserService；未授权时
        // ShizukuRuntime.connect() 会在 requirePermission() 中拒绝（fail-closed）。
        terminals.shizuku.connect()
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
        supervisor.requestStartCancellation()
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
