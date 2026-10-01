package io.deepseekharness.mobile.runtime

import android.content.Context
import io.deepseekharness.mobile.shizuku.ShizukuState
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

/**
 * 版本管理操作的结果。
 *
 * 拆成「载荷 + 自动快照结论」两个字段而不是塞进同一个 JSON：自动快照的结论要同时进审计与
 * 诊断日志，塞进载荷后桥层就没法区分「这次操作根本没跑快照」（如 `list`/`check`）与
 * 「跑了但因为没有会话数据而跳过」，而这两件事对用户的含义完全不同。
 */
class RuntimeLibraryResult(
    val payload: org.json.JSONObject,
    val autoSnapshot: RuntimeAutoSnapshotOutcome?,
)

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
    /** 任务通知要 Context 才能投递；只保留 application context，不持有 Activity。 */
    private val appContext = context.applicationContext
    val store = RuntimeStore(context)
    val status = RuntimeStatus(store).also { it.progressListener = events::onProgress }
    private val installer = RuntimeInstaller(store, status, externalCancellation = closed::get)
    private val supervisor = RuntimeSupervisor(context, store, status)
    private val plugins = RuntimePluginManager(context, store)
    /** 自检实例与 store、生命周期锁同源：插件侧不自行构造 RuntimeStore。 */
    private val selfCheck = RuntimeSelfCheck(context, store)
    /** 版本列表只读清单与小文件；切换、删除仍由 installer 在安装锁内完成。 */
    private val versions = RuntimeVersionCatalog(store)
    val terminals = TerminalCoordinator(context, store, events::onTerminalOutput, events::onTerminalExit)

    /**
     * 运行时会话快照（备份与恢复）。
     *
     * 快照根在应用私有 `filesDir` 下，**绝不在 rootfs 里**：rootfs 在更新事务中会被整体替换，
     * 把备份放进去等于没有备份（`AndroidManifest.xml` 的 `allowBackup="false"` 同时保证它
     * 不会被云备份带走）。会话目录取「当前已安装运行时」的访客路径，与 [RuntimePreservePolicy]
     * 的保留项同源。
     */
    private val snapshots = RuntimeSessionSnapshots(
        sessionRoot = File(store.currentRoot, RuntimeSessionSnapshotLimits.SESSIONS_RELATIVE_PATH),
        snapshotsRoot = File(appContext.filesDir, RuntimeSessionSnapshotLimits.DIRECTORY_NAME),
        identity = {
            // 版本读不到就写 null，不猜：元数据里写错版本会让用户以为恢复的是另一份运行时。
            val manifest = store.installedManifest()
            RuntimeIdentitySnapshot(
                dshVersion = manifest?.dshVersion ?: RuntimeDshVersion.read(store.currentRoot),
                runtimeVersion = manifest?.version,
                runtimeId = manifest?.runtimeId,
            )
        },
    )

    /**
     * 安装/更新运行时。
     *
     * 返回值是**更新前自动快照**的结论：快照失败**不阻断安装**（用户选的是「更新前自动备份」，
     * 不是「备份失败就别更新」），但结论必须回到界面与审计，否则用户会以为已经有备份了。
     */
    fun install(source: RuntimeSource): RuntimeAutoSnapshotOutcome = lifecycleLock.withLock {
        ensureOpen()
        if (supervisor.isRunning() || terminals.hasRuntimeSessions()) {
            throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
        }
        // 判定必须在安装**之前**取：装完之后「本来有没有运行时」就无从分辨，
        // 而它决定通知说「已安装」还是「已更新」（见 TaskNotificationPolicy.forInstallCompleted）。
        val hadRuntimeBefore = store.installedManifest() != null
        // 自动快照与手动快照走**同一套实现**（RuntimeSessionSnapshots.createBeforeUpdate），
        // 它内部永不抛异常：这里不需要 try/catch，也不允许它挡住 installer.install。
        val autoSnapshot = snapshots.createBeforeUpdate()
        installer.install(source)
        // 安装要下载并解压整个 rootfs（数百 MB），用户几乎必然切走：
        // 装完只在界面上更新状态的话，他切回来之前什么都不知道。
        TaskNotification.postInstallCompleted(
            appContext,
            TaskNotificationPolicy.forInstallCompleted(hadRuntimeBefore),
        )
        autoSnapshot
    }

    /**
     * 运行时版本列表：当前版本、保留下来的上一版本、APK 内置版本。
     *
     * 只读（清单与包内 `package.json`），因此**不要求**运行时已停止：用户正跑着 Harness 时
     * 也能看到自己装的是什么版本。
     */
    fun runtimeVersions(): List<RuntimeVersionInfo> = lifecycleLock.withLock {
        ensureOpen()
        versions.list()
    }

    /**
     * 切换到上一版本。
     *
     * 与安装同等对待：它会改名 `currentRoot` 并把用户数据搬过去，所以必须先停掉 Harness 与终端，
     * 否则正在跑的访客进程会踩到被改名根目录。
     */
    fun switchRuntimeVersion(target: String): List<RuntimeVersionInfo> = lifecycleLock.withLock {
        ensureOpen()
        RuntimeVersionPolicy.requireTarget(target)
        if (supervisor.isRunning() || terminals.hasRuntimeSessions()) {
            throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
        }
        installer.switchToRetained()
        versions.list()
    }

    /**
     * 删除保留下来的上一版本。
     *
     * 这里**不**要求运行时已停止：删除只动 `retained*`，与正在运行的当前运行时无关，
     * 用户清理磁盘空间不该被迫先停服务。
     */
    fun deleteRuntimeVersion(target: String): List<RuntimeVersionInfo> = lifecycleLock.withLock {
        ensureOpen()
        RuntimeVersionPolicy.requireTarget(target)
        installer.deleteRetained()
        versions.list()
    }

    fun startHarness(expectedStartEpoch: Long? = null): RuntimeStateSnapshot = lifecycleLock.withLock {        ensureOpen()
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
        supervisor.startHarness(expectedStartEpoch)
    }

    /** 仅应用内版本管理调用；切换后的 current 持久化为下次启动的默认版本。 */
    fun manageRuntimeLibrary(operation: String, id: String?, source: RuntimeSource?): RuntimeLibraryResult = lifecycleLock.withLock {
        ensureOpen()
        val library = RuntimeLibrary(store)
        // 只有「安装某个本地版本」（select）才会替换 rootfs，因此在那一支才拍自动快照；
        // 切换回上一版本（switchVersion）刻意不拍：它不经过这里，也不换镜像。
        var autoSnapshot: RuntimeAutoSnapshotOutcome? = null
        when (operation) {
            "list" -> Unit
            // 远端可用版本改由 MobileRuntimePlugin.listRuntimeReleases 单独回传：
            // 它要发网络请求，混进「本地版本管理」会让一次检查同时依赖本地与远端两件事。
            "check" -> return@withLock RuntimeLibraryResult(org.json.JSONObject().put("local", library.list()), null)
            "download" -> library.download(source ?: throw RuntimeFailure("SOURCE_INCOMPLETE", "缺少版本来源"))
            "select" -> {
                if (supervisor.isRunning() || terminals.hasRuntimeSessions()) throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
                autoSnapshot = snapshots.createBeforeUpdate()
                installer.install(RuntimeSource(null, null, RuntimeLibrary.requireId(id.orEmpty())))
            }
            else -> throw RuntimeFailure("RUNTIME_VERSION_INVALID", "版本管理操作无效")
        }
        RuntimeLibraryResult(org.json.JSONObject().put("local", library.list()), autoSnapshot)
    }

    /**
     * 会话快照总览：只读，**不要求**运行时已停止（界面在任何阶段都能看到现有备份）。
     */
    fun sessionSnapshotState(): RuntimeSessionSnapshotState = lifecycleLock.withLock {
        ensureOpen()
        snapshots.state()
    }

    /** 手动创建一份会话快照；会话目录不存在或为空时拒绝，不产出空快照。 */
    fun createSessionSnapshot(): RuntimeSessionSnapshotState = lifecycleLock.withLock {
        ensureOpen()
        snapshots.create()
    }

    /**
     * 把一份快照合并回填到当前运行时的会话目录：**同名文件不覆盖**。
     *
     * 刻意**不要求**运行时已停止：更新后发现会话读不出来时，用户不该被迫先去做「停服务」这一步。
     * 不覆盖同名文件这条规则正是为了让「新 dsh 已经写过的新会话」不被旧内容盖掉。
     */
    fun restoreSessionSnapshot(id: String): RuntimeSessionSnapshotRestoreResult = lifecycleLock.withLock {
        ensureOpen()
        snapshots.restore(id)
    }

    /** 删除一份快照（只删备份，绝不触碰会话目录）。 */
    fun deleteSessionSnapshot(id: String): RuntimeSessionSnapshotState = lifecycleLock.withLock {
        ensureOpen()
        snapshots.delete(id)
    }

    /** 返回当前启动代次，供系统恢复入口校验控制器身份与取消竞态。 */
    fun currentStartEpoch(): Long = lifecycleLock.withLock {
        ensureOpen()
        supervisor.currentStartEpoch()
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

    /**
     * 权限：仅应用内部。
     * 运行时自检：`check` 只读，`repair` 只补可执行位与创建附件目录。
     *
     * 与插件操作一致地持有生命周期锁：`repair` 会改文件系统，自检不得与安装、重置或
     * Harness 启停并发；两个操作都要求运行时已安装（未安装由自检自己抛受控错误）。
     */
    fun runRuntimeSelfCheck(operation: String): com.getcapacitor.JSObject = lifecycleLock.withLock {
        ensureOpen()
        selfCheck.run(operation)
    }

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
        overlayBallEnabledUpdate: Boolean? = settings.overlayBallEnabled,
        harnessPermissionModeUpdate: HarnessPermissionMode? = settings.harnessPermissionMode,
    ): RuntimeSettings = lifecycleLock.withLock {
        ensureOpen()
        val modelConfigurationChanged = providerApiKeyUpdates.isNotEmpty() || clearedProviderApiKeys.isNotEmpty() ||
            customProviderApiKeyUpdates.isNotEmpty() || clearedCustomProviderApiKeys.isNotEmpty() ||
            customProviders != store.settings().customModelProviders
        val permissionChanged = harnessPermissionModeUpdate != null && harnessPermissionModeUpdate != store.harnessPermissionMode()
        val launchConfigurationChanged = modelConfigurationChanged || permissionChanged
        val restartHarness = launchConfigurationChanged && supervisor.isRunning()
        if (launchConfigurationChanged) supervisor.stop()
        val saved = store.saveSettings(
            settings,
            providerApiKeyUpdates,
            clearedProviderApiKeys,
            customProviders,
            customProviderApiKeyUpdates,
            clearedCustomProviderApiKeys,
            overlayBallEnabledUpdate = overlayBallEnabledUpdate,
            harnessPermissionModeUpdate = harnessPermissionModeUpdate,
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
