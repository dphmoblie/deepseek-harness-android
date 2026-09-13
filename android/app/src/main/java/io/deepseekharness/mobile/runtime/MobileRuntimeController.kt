package io.deepseekharness.mobile.runtime

import android.content.Context
import io.deepseekharness.mobile.shizuku.ShizukuState
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

/**
 * 本机运行时控制器。
 *
 * 实例由 [RuntimeHost] 持有：Capacitor 插件销毁（含划掉最近任务）后，前台服务可以
 * 继续复用同一实例与会话；插件或服务都不再持有时才真正 [shutdown]。
 *
 * 事件出口通过 [RuntimeEventSink] 注入，运行时不直接引用 WebView，也不保存任何凭据。
 */
class MobileRuntimeController(
    context: Context,
    private val events: RuntimeEventSink,
) {
    private val lifecycleLock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    val store = RuntimeStore(context)
    val status = RuntimeStatus(store).also { it.progressListener = events::onProgress }
    private val installer = RuntimeInstaller(store, status, externalCancellation = closed::get)
    private val supervisor = RuntimeSupervisor(context, store, status)
    private val plugins = RuntimePluginManager(context, store)
    val terminals = TerminalCoordinator(context, store, events::onTerminalOutput, events::onTerminalExit)

    fun install(source: RuntimeSource) = lifecycleLock.withLock {
        ensureOpen()
        if (supervisor.isRunning() || terminals.hasRuntimeSessions()) {
            throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
        }
        installer.install(source)
    }

    fun startHarness(): RuntimeStateSnapshot = lifecycleLock.withLock {
        ensureOpen()
        if (!supervisor.isRunning()) {
            supervisor.preparePluginManagement()
            plugins.recoverIfNeeded()
            // 启动前自愈：运行时升级后插件目录里的链接可能已悬空，修复必须在插件被加载前完成。
            plugins.repairInstalledIfNeeded()
            // 启动前取证：自愈按代次指纹只跑一次，探测则每次都跑 —— 它只读、只遍历固定候选根，
            // 而「装了新插件」不换代次，缓存会漏掉那个时机。
            // 结果只写计数（MODULE_GRAPH）。判读要分清方向：**count=1 是很强的否定结论**
            // （该故障与模块重复无关）；count>1 只说明「存在」两份物理副本 —— 可能只是 pnpm
            // store 里已无引用的陈旧目录，**不等于**运行中的进程确实加载了两份。
            plugins.recordModuleGraph()
        }
        supervisor.startHarness()
    }

    /** 权限：仅应用内部；生命周期锁防止插件写入与启动、安装、终端并发。 */
    fun managePlugins(operation: String, id: String?, enabled: Boolean?, childId: String?): com.getcapacitor.JSObject = lifecycleLock.withLock {
        ensureOpen()
        if (operation != "list") {
            if (supervisor.isRunning() || terminals.hasRuntimeSessions()) {
                throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
            }
            supervisor.preparePluginManagement()
        }
        plugins.run(operation, id, enabled, childId)
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

    /**
     * 是否存在本进程无法复用的 Harness 残留进程。
     * 应用进程被系统回收后 PRoot→node 子进程可能仍在运行，但临时 Basic Auth 凭据
     * 已随进程丢失，只能提示用户重新连接。
     */
    fun hasResidualHarness(): Boolean = try {
        supervisor.hasResidualHarness()
    } catch (_: Throwable) {
        // 判定失败按“无残留”处理：不额外弹提示，启动流程仍会自行回收残留。
        false
    }

    /**
     * 后台保持与恢复状态快照。
     *
     * 只读取设置、Shizuku 状态、残留进程标记与持久化意图；不启动进程、不执行 Shell
     * 命令、不返回任何凭据，可安全回传 WebView。
     */
    fun keepAliveSnapshot(foregroundServiceActive: Boolean): RuntimeKeepAliveSnapshot = lifecycleLock.withLock {
        ensureOpen()
        val record = store.runtimeIntentRecord()
        val ownedRunning = status.snapshot().phase == RuntimePhase.RUNNING
        RuntimeKeepAliveSnapshot(
            keepRuntimeInBackground = store.keepRuntimeInBackground(),
            foregroundServiceActive = foregroundServiceActive,
            deviceShellReady = deviceShellReadyLocked(),
            reconnectRequired = HarnessKeepAlivePolicy.requiresReconnect(
                runtimeOwnedRunning = ownedRunning,
                residualProcess = hasResidualHarness(),
                lastIntent = record.intent,
            ),
            lastIntent = record.intent,
            lastPhase = record.phase,
            lastUpdatedAtMillis = record.updatedAtMillis,
        )
    }

    /**
     * Shizuku 健康检查：只读取 binder、授权与 UserService 状态，不执行任何 Shell 命令
     * 与设备操作。Shizuku 未安装、未授权或断开时返回 false，运行时按无设备 Shell 降级。
     */
    private fun deviceShellReadyLocked(): Boolean = try {
        val state = terminals.shizuku.healthCheck()
        state.installed && state.running && state.permission == "granted"
    } catch (_: Throwable) {
        false
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
