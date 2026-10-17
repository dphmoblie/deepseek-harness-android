package io.deepseekharness.mobile.runtime

import android.content.Context
import io.deepseekharness.mobile.AppAuthenticationState
import io.deepseekharness.mobile.BuildConfig
import io.deepseekharness.mobile.shizuku.ShizukuState
import io.deepseekharness.mobile.shizuku.ShizukuAutoReconnectPolicy
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

/** 重启恢复时 Shizuku 自动连接的受控结果，不携带远程异常文本。 */
enum class ShizukuReconnectResult { NOT_ELIGIBLE, CONNECTED, FAILED }

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
    /** 自检实例与 store、生命周期锁同源：插件侧不自行构造 RuntimeStore；插件管理器复用上面那一个实例（计数是实例内状态）。 */
    private val selfCheck = RuntimeSelfCheck(context, store, plugins)
    /** 版本列表只读清单与小文件；切换、删除仍由 installer 在安装锁内完成。 */
    private val versions = RuntimeVersionCatalog(store)
    private val harnessVersions = HarnessVersions(appContext, store)

    /** 应用内版本操作：精确版本号；安装与切换互斥，不传入模型凭据。 */
    fun manageHarnessVersions(operation: String, version: String?): org.json.JSONObject = lifecycleLock.withLock {
        ensureOpen()
        when (operation) {
            "list" -> return@withLock harnessVersions.list()
            "install", "select", "delete" -> Unit
            else -> throw RuntimeFailure("HARNESS_VERSION_INVALID", "Harness 版本操作无效")
        }
        val target = if (operation == "select" && version == HarnessVersions.BUNDLED) version else HarnessPackageCatalog.requireVersion(version)
        if (terminals.hasRuntimeSessions()) throw RuntimeFailure("RUNTIME_BUSY", "请先关闭 Ubuntu 终端再管理 Harness 版本")
        if (operation != "select" && supervisor.isRunning()) throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness，再安装或删除版本")
        if (operation == "select") {
            // 启动成功才提交默认版本；失败或进程中断均恢复旧选择。
            val wasRunning = supervisor.isRunning()
            supervisor.stop()
            try {
                plugins.checkpointBeforeUpdate()
                harnessVersions.beginSwitch(target)
                harnessVersions.prepareProfile()
                plugins.recoverIfNeeded()
                plugins.repairInstalledIfNeeded(explicit = true)
                plugins.run("verify", null, null, null)
                supervisor.startHarness()
                harnessVersions.commitSwitch()
            } catch (error: Exception) {
                try {
                    supervisor.stop()
                    harnessVersions.rollbackSwitch()
                    harnessVersions.prepareProfile()
                    plugins.repairInstalledIfNeeded(explicit = true)
                    if (wasRunning) supervisor.startHarness()
                } catch (rollbackError: Exception) {
                    throw RuntimeFailure("HARNESS_ROLLBACK_FAILED", "切换失败，旧版本仍保留，但自动恢复未完成；请切回内置版本", rollbackError)
                }
                val reason = (error as? RuntimeFailure)?.message ?: "启动检查未通过"
                throw RuntimeFailure("HARNESS_SWITCH_FAILED", "新版本未通过检查，已恢复旧默认版本：$reason", error)
            }
        } else {
            supervisor.preparePluginManagement()
            if (operation == "install") harnessVersions.install(target) else harnessVersions.remove(target)
        }
        harnessVersions.list()
    }

    /**
     * Harness 启动成功后的非关键诊断任务专用单线程。
     *
     * 模块图探测（[RuntimePluginManager.recordModuleGraph]）是纯只读诊断：只遍历固定候选目录、
     * 只写一条计数日志。它原先在启动关键路径上串行执行，每次冷启动都要多拉起一次完整的
     * PRoot+Node 访客进程；放到 Harness 就绪之后在这里后台执行，关键路径少一次访客进程往返，
     * 诊断结论仍在本次启动时间线内落盘。守护线程：即使探测仍在运行，进程也可以正常退出。
     */
    private val postStartDiagnosticsExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-poststart-diagnostics").apply { isDaemon = true }
    }
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
        supervisor.preparePluginManagement()
        // 判定必须在安装**之前**取：装完之后「本来有没有运行时」就无从分辨，
        // 而它决定通知说「已安装」还是「已更新」（见 TaskNotificationPolicy.forInstallCompleted）。
        val hadRuntimeBefore = store.installedManifest() != null
        // 自动快照与手动快照走**同一套实现**（RuntimeSessionSnapshots.createBeforeUpdate），
        // 它内部永不抛异常：这里不需要 try/catch，也不允许它挡住 installer.install。
        val autoSnapshot = snapshots.createBeforeUpdate()
        plugins.checkpointBeforeUpdate()
        installer.install(source)
        try {
            supervisor.preparePluginManagement()
            plugins.recoverIfNeeded()
            plugins.repairInstalledIfNeeded(explicit = true)
        } catch (_: Exception) {
            // 运行时更新后的自愈属于防御性动作，失败不阻断安装流程
        }
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
     * 在运行时生命周期锁内执行一个只读或受控的桥接查询。
     * 会话目录读取需要启动访客探测并更新脚本文件，不能与启动、停止、重置或安装并发。
     */
    fun <T> withLifecycleLock(operation: () -> T): T = lifecycleLock.withLock {
        ensureOpen()
        operation()
    }

    /**
     * 切换到上一版本。
     *
     * 与安装同等对待：它会改名 `currentRoot` 并把用户数据搬过去，所以必须先停掉 Harness 与终端，
     * 否则正在跑的访客进程会踩到被改名根目录。
     */
    fun switchRuntimeVersion(target: String): List<RuntimeVersionInfo> = lifecycleLock.withLock {
        ensureOpen()
        val normalizedTarget = RuntimeVersionPolicy.requireTarget(target)
        if (supervisor.isRunning() || terminals.hasRuntimeSessions()) {
            throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
        }
        supervisor.preparePluginManagement()
        plugins.checkpointBeforeUpdate()
        when (normalizedTarget) {
            RuntimeVersionPolicy.TARGET_PREVIOUS -> installer.switchToRetained()
            RuntimeVersionPolicy.TARGET_BUNDLED -> installer.install(RuntimeSource(null, null))
            else -> installer.install(RuntimeSource(null, null, libraryId = normalizedTarget))
        }
        try {
            supervisor.preparePluginManagement()
            plugins.recoverIfNeeded()
            plugins.repairInstalledIfNeeded(explicit = true)
        } catch (_: Exception) {
            // 版本切换后的自愈属于防御性动作，失败不阻断切换流程
        }
        versions.list()
    }

    /**
     * 删除保留下来的上一版本或版本库中的历史版本。
     *
     * 这里**不**要求运行时已停止：删除只动 `retained*` 或 `library/` 目录，与正在运行的当前运行时无关，
     * 用户清理磁盘空间不该被迫先停服务。
     */
    fun deleteRuntimeVersion(target: String): List<RuntimeVersionInfo> = lifecycleLock.withLock {
        ensureOpen()
        val normalizedTarget = RuntimeVersionPolicy.requireTarget(target)
        if (normalizedTarget == RuntimeVersionPolicy.TARGET_BUNDLED) {
            throw RuntimeFailure("RUNTIME_VERSION_DELETE_FORBIDDEN", "出厂内置版本不可删除")
        }
        if (normalizedTarget == RuntimeVersionPolicy.TARGET_PREVIOUS) {
            installer.deleteRetained()
        } else {
            val currentSha = store.installedManifest()?.rootfs?.sha256
            if (normalizedTarget == currentSha) {
                throw RuntimeFailure("RUNTIME_VERSION_BUSY", "不能删除正在使用的版本")
            }
            RuntimeLibrary(store).delete(normalizedTarget)
        }
        versions.list()
    }

    /**
     * 锁定/解锁版本库里的一个已下载版本。
     *
     * 与删除一样**不**要求运行时已停止：锁定只写版本库目录里的一个 sidecar 文件，与正在跑的
     * 当前运行时无关。只有版本库条目能锁定——`previous`/`bundled` 槽位不是可淘汰对象，
     * 这里以 `RUNTIME_VERSION_TARGET_INVALID` 明确拒绝，免得界面上出现一个「锁了却毫无作用」的按钮。
     */
    fun setRuntimeVersionPinned(target: String, pinned: Boolean): List<RuntimeVersionInfo> = lifecycleLock.withLock {
        ensureOpen()
        val normalizedTarget = RuntimeVersionPolicy.requireTarget(target)
        if (normalizedTarget == RuntimeVersionPolicy.TARGET_PREVIOUS || normalizedTarget == RuntimeVersionPolicy.TARGET_BUNDLED) {
            throw RuntimeFailure("RUNTIME_VERSION_TARGET_INVALID", "只有版本库里的版本可以锁定")
        }
        RuntimeLibrary(store).setPinned(normalizedTarget, pinned)
        versions.list()
    }

    fun downloadToLibrary(source: RuntimeSource) = lifecycleLock.withLock {
        ensureOpen()
        RuntimeLibrary(store).download(source)
    }

    fun startHarness(expectedStartEpoch: Long? = null): RuntimeStateSnapshot = lifecycleLock.withLock {
        ensureOpen()
        val coldStart = !supervisor.isRunning()
        if (coldStart) {
            supervisor.preparePluginManagement()
            // 选择文件损坏或包已失效时退回内置：一个坏包不能挡住 Harness 启动，
            // 也不能让设置页失去「切回内置」的入口。
            val recoveredSwitch = runCatching { harnessVersions.rollbackSwitch() }.getOrDefault(false)
            val selectedHarness = HarnessVersions.selectedOrBundled(store)
            val usable = selectedHarness == HarnessVersions.BUNDLED ||
                runCatching { harnessVersions.verify(selectedHarness) }.isSuccess
            if (!usable) runCatching { HarnessVersions.fallBackToBundled(store) }
            if (recoveredSwitch || selectedHarness != HarnessVersions.BUNDLED) harnessVersions.prepareProfile()
            if (!terminals.hasRuntimeSessions()) {
                RuntimeSharedInbox.withLock {
                    val sharedInbox = RuntimeSharedInbox(store)
                    // 访客已停止才创建目录；异常时保留暂存原件，不能阻断 Harness 启动。
                    runCatching { sharedInbox.prepareWorkspace() }
                    sharedInbox.deliver()
                }
            }
            // 应用（APK）升级迁移：判定是「上次迁移成功的 versionCode + 当前 versionCode +
            // 运行时代次是否变化」（[PluginMigrationPolicy]，纯逻辑）。需要迁移时由它**串行复用**
            // 下面两步（先回放未提交事务，再按代次自愈链接）并按版本账本核对包目录，因此这时不再
            // 重复调用 —— recover 虽然幂等，但多调用一次就多起一次访客 Node 进程。
            if (!plugins.migrateIfNeeded(BuildConfig.VERSION_CODE)) {
                plugins.recoverIfNeeded()
                // 启动前自愈：运行时升级后插件目录里的链接可能已悬空，修复必须在插件被加载前完成。
                plugins.repairInstalledIfNeeded()
            }
        }
        val snapshot = supervisor.startHarness(expectedStartEpoch)
        // 启动前取证：自愈按代次指纹只跑一次，探测则每次冷启动都跑 —— 它只读、只遍历固定候选根，
        // 而「装了新插件」不换代次，缓存会漏掉那个时机。
        // 结果只写计数（MODULE_GRAPH）。判读要分清方向：**count=1 是很强的否定结论**
        // （该故障与模块重复无关）；count>1 只说明「存在」两份物理副本 —— 可能只是 pnpm
        // store 里已无引用的陈旧目录，**不等于**运行中的进程确实加载了两份。
        //
        // 时序：探测纯粹服务诊断、结果不参与任何启动判定，因此放在 Harness 就绪**之后**后台执行，
        // 不再串行在启动关键路径上（那会多阻塞一次完整的 PRoot+Node 访客进程往返）。
        // 只读遍历与正在运行的 Harness 并发是安全的；启动失败时不调度——失败本身已有
        // HARNESS_START 诊断记录，模块图在该场景下没有判读价值。
        if (coldStart) schedulePostStartModuleGraph()
        snapshot
    }

    /**
     * 把模块图探测提交到 [postStartDiagnosticsExecutor]。
     *
     * 不持有 [lifecycleLock]：探测只读且超时受控（30s），不应阻塞随后的停止/重置/插件管理。
     * 但不能对「运行时已被停掉/替换」视而不见：任务真正开始前先确认控制器仍开着且 Harness
     * 仍在运行，否则直接放弃探测——那时它只会与 rootfs 的拆除/替换并发，结论也没有判读价值。
     * 若停止/重置与探测并发，访客侧读到变动中的目录只会让探测失败，按既有约定记一条
     * result=denied 的 MODULE_GRAPH，绝不抛出；[shutdown] 会中断等待并回收探测进程。
     */
    private fun schedulePostStartModuleGraph() {
        // 控制器已关闭时执行器已被 shutdownNow：此时 execute 会抛 RejectedExecutionException，
        // 那会从启动路径逃出去、把一次成功的启动报成失败。前置判断 + 兜底都做上。
        if (postStartDiagnosticsExecutor.isShutdown) return
        runCatching {
            postStartDiagnosticsExecutor.execute {
                // 探测只对「仍在运行的冷启动」有判读价值：控制器已关闭、或运行时已被 stop 掉，
                // 再拉起访客探测只会与 rootfs 的拆除/替换并发，且结论已无意义。这里只读两个
                // 线程安全的状态（AtomicBoolean / synchronized），不碰 lifecycleLock。
                if (closed.get() || !supervisor.isRunning()) return@execute
                try {
                    plugins.recordModuleGraph()
                } catch (_: Throwable) {
                    // recordModuleGraph 自身已吞掉所有 Exception 并记 denied；
                    // 这里再兜一层，防止任何意外终止后台诊断线程。
                }
            }
        }
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
                supervisor.preparePluginManagement()
                autoSnapshot = snapshots.createBeforeUpdate()
                plugins.checkpointBeforeUpdate()
                installer.install(RuntimeSource(null, null, RuntimeLibrary.requireId(id.orEmpty())))
                try {
                    supervisor.preparePluginManagement()
                    plugins.recoverIfNeeded()
                    plugins.repairInstalledIfNeeded(explicit = true)
                } catch (_: Exception) {
                    // 版本切换后的自愈属于防御性动作，失败不阻断切换流程
                }
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
    fun managePlugins(operation: String, id: String?, enabled: Boolean?, childId: String?, source: String? = null): com.getcapacitor.JSObject = lifecycleLock.withLock {
        ensureOpen()
        if (operation != "list") {
            if (supervisor.isRunning() || terminals.hasRuntimeSessions()) {
                throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
            }
            supervisor.preparePluginManagement()
        } else {
            // 用户可能刚更新了 APK 或运行时，还没有点击启动 Harness 就直接进入了插件设置页。
            // 若当前运行时未在运行，防御性执行一次 migrateIfNeeded，自动接回保留区的插件软链接，
            // 避免用户打开插件列表时看到全部插件呈现“未安装”假象。
            if (!supervisor.isRunning() && !terminals.hasRuntimeSessions()) {
                try {
                    supervisor.preparePluginManagement()
                    plugins.migrateIfNeeded(BuildConfig.VERSION_CODE)
                } catch (_: Exception) {
                    // 防御性迁移失败不阻断只读列表读取
                }
            }
        }
        // `repair` 是用户主动触发的显式修复：绕过「同一代次只修一次」的自愈闸门，也不进冷却
        // 窗口退避。它不另造返回形状，而是紧接着回读一次 `list` —— 刚接回的插件直接出现在列表里，
        // 修复失败则按受控错误码 reject（不吞异常，否则按钮点下去界面上什么都不会变）。
        if (operation == "repair") {
            plugins.repairInstalledIfNeeded(explicit = true)
            return@withLock withRecovery(plugins.run("list", null, null, null, null))
        }
        if (operation == "prune") {
            plugins.run("prune", null, null, null, null)
            return@withLock withRecovery(plugins.run("list", null, null, null, null))
        }
        return@withLock withRecovery(plugins.run(operation, id, enabled, childId, source))
    }

    /** 插件页面只需要受控迁移状态；计数由原生侧生成，避免把路径或访客日志回传到 WebView。 */
    private fun withRecovery(payload: com.getcapacitor.JSObject): com.getcapacitor.JSObject =
        payload.apply { put("recovery", plugins.recoverySnapshot()) }

    /**
     * 插件数据档清理的只读读数（账本可用性、涉及包数、逐包占用），不触碰任何文件。
     *
     * 与 [managePlugins] 不同，这里**不要求运行时已停止**：读数只读账本与目录属性，用户开着
     * Harness 时打开设置页也应该能看到这一档占了多少、能不能清；真正删除才需要停机。
     */
    fun pluginCleanupState(): com.getcapacitor.JSObject = plugins.cleanupState()

    /**
     * 缓存档清理（破坏性，只由用户显式触发）。
     *
     * 要求运行时已停止：[RuntimeCacheEntries.PROOT_TMP] 是运行中沙箱的 `TMPDIR`、`WORKSPACE_SHARE`
     * 是投递暂存，运行中删掉会把一次正在进行的会话打断。生命周期锁同时排除与安装、重置、插件
     * 写入并发——清理不是「顺便删一下」，它必须与其它文件系统操作互斥。
     */
    internal fun clearRuntimeCache(cacheDir: File): RuntimeCleanupOutcome = lifecycleLock.withLock {
        ensureOpen()
        requireRuntimeStoppedForCleanup()
        RuntimeCacheCleanup(cacheDir).clear()
    }

    /**
     * 插件数据档清理（破坏性，只由用户显式触发）。
     *
     * 与缓存档同一道停机要求：插件进程正在写自己的存储时删目录，会得到「损坏一半」的状态，
     * 比清不掉更糟。返回 `null` 表示版本账本不可用，此时一个字节都没动。
     *
     * [packageName] 非空时只清这一个包（界面上的「只清这个插件」），为空时清全部已登记插件。
     */
    internal fun purgePluginData(packageName: String? = null): RuntimeCleanupOutcome? =
        lifecycleLock.withLock {
            ensureOpen()
            requireRuntimeStoppedForCleanup()
            plugins.purgePluginData(packageName)
        }

    /** 清理的前置条件：运行时与终端都停下，否则删目录会打断正在跑的会话。 */
    private fun requireRuntimeStoppedForCleanup() {
        if (supervisor.isRunning() || terminals.hasRuntimeSessions()) {
            throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
        }
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

    /**
     * Mutates the shared-directory allowlist while holding the same lock as launch/stop.
     * Mounts are snapshotted when PRoot starts, so changing the list during a live session
     * would otherwise make the UI and the guest disagree until the next restart.
     */
    fun <T> withStorageDirsMutation(operation: () -> T): T = lifecycleLock.withLock {
        ensureOpen()
        if (supervisor.isRunning() || terminals.hasRuntimeSessions()) {
            throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness 和 Ubuntu 终端")
        }
        operation()
    }

    fun saveSettings(
        settings: RuntimeSettings,
        providerApiKeyUpdates: Map<ModelProvider, String>,
        clearedProviderApiKeys: Set<ModelProvider>,
        customProviders: List<CustomModelProvider>,
        customProviderApiKeyUpdates: Map<String, String>,
        clearedCustomProviderApiKeys: Set<String>,
        overlayBallEnabledUpdate: Boolean? = settings.overlayBallEnabled,
    ): RuntimeSettings = lifecycleLock.withLock {
        ensureOpen()
        val modelConfigurationChanged = providerApiKeyUpdates.isNotEmpty() || clearedProviderApiKeys.isNotEmpty() ||
            customProviderApiKeyUpdates.isNotEmpty() || clearedCustomProviderApiKeys.isNotEmpty() ||
            customProviders != store.settings().customModelProviders
        val launchConfigurationChanged = modelConfigurationChanged
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

    /**
     * 回收 `stale-*` 残留（删除失败后被改名挪到一边的整份运行时目录），返回本次回收的份数与字节数。
     *
     * 为什么在这里多一条转发：版本管理页的「清理残留」需要一个模块外可见的入口，而
     * [RuntimeInstaller.retireStaleResidue] 是 internal。转发的意义是不让插件层另建一份清扫器——
     * 删除器、字节统计与记账回调必须始终只有 [installer] 那一份，否则两处将来会漂移。
     * 它与安装、重置共用同一把生命周期锁，所以不会和一次正在进行的安装删到同一份残留；
     * 单份删不掉不抛，在返回的 `failed` 与诊断日志（`phase=cleanup`）里如实体现。
     */
    internal fun retireStaleResidue(): RuntimeResidueSweepResult = lifecycleLock.withLock {
        ensureOpen()
        installer.retireStaleResidue()
    }

    fun createTerminal(kind: String, columns: Int, rows: Int): String = lifecycleLock.withLock {
        ensureOpen()
        if (kind == "ubuntu" && !supervisor.isRunning() && !terminals.hasRuntimeSessions()) {
            supervisor.preparePluginManagement()
            RuntimeSharedInbox.withLock {
                val sharedInbox = RuntimeSharedInbox(store)
                // 与 Harness 冷启动一致：先确认访客已停止，迁移失败不能阻断终端。
                runCatching { sharedInbox.prepareWorkspace() }
                sharedInbox.deliver()
            }
        }
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

    fun requestShizukuPermission(): ShizukuState {
        ensureOpen()
        return terminals.shizuku.requestPermission()
    }

    fun connectShizuku(): ShizukuState {
        ensureOpen()
        // 授权已存在时直接绑定 Shizuku UserService；未授权时
        // ShizukuRuntime.connect() 会在 requirePermission() 中拒绝（fail-closed）。
        return terminals.shizuku.connect()
    }

    /**
     * 在系统重启恢复线程中尝试复用已有授权连接。
     *
     * 这里只接受已安装、已运行、已授权的 Shizuku；不会弹授权对话框，也不会等待服务
     * 启动。失败只返回受控结果，调用方可投递固定提醒而不会把 Binder 原文写入日志。
     */
    fun reconnectShizukuIfAuthorized(permitted: () -> Boolean = { true }): ShizukuReconnectResult {
        ensureOpen()
        val state = try {
            terminals.shizuku.healthCheck()
        } catch (_: Throwable) {
            return ShizukuReconnectResult.NOT_ELIGIBLE
        }
        if (!ShizukuAutoReconnectPolicy.shouldAttempt(state)) {
            return ShizukuReconnectResult.NOT_ELIGIBLE
        }
        return try {
            val connected = terminals.shizuku.connectIfAuthorized(permitted, permitted)
            when {
                connected -> ShizukuReconnectResult.CONNECTED
                !permitted() -> ShizukuReconnectResult.NOT_ELIGIBLE
                // A permission/binder race can make the guarded call return false after the
                // initial health check. Re-read state before deciding whether to notify.
                !ShizukuAutoReconnectPolicy.shouldAttempt(terminals.shizuku.healthCheck()) ->
                    ShizukuReconnectResult.NOT_ELIGIBLE
                else -> ShizukuReconnectResult.FAILED
            }
        } catch (_: Throwable) {
            if (!permitted()) {
                ShizukuReconnectResult.NOT_ELIGIBLE
            } else {
                // A binder death during bind changes eligibility; avoid an inaccurate alert.
                runCatching {
                    if (ShizukuAutoReconnectPolicy.shouldAttempt(terminals.shizuku.healthCheck())) {
                        ShizukuReconnectResult.FAILED
                    } else {
                        ShizukuReconnectResult.NOT_ELIGIBLE
                    }
                }.getOrDefault(ShizukuReconnectResult.NOT_ELIGIBLE)
            }
        }
    }

    fun openShizukuManager() {
        ensureOpen()
        terminals.shizuku.openManager()
    }

    fun openHarnessAccess(): HarnessAccess = lifecycleLock.withLock {
        ensureOpen()
        supervisor.access()
    }

    /**
     * Read and publish the current one-cycle access while holding the runtime lock. A caller
     * must use this for a launch so stop/restart cannot slip between reading the token and
     * authorizing it.
     */
    internal fun authorizeHarnessAccess(
        expected: HarnessAccess? = null,
        stillValid: () -> Boolean = { true },
    ): AppAuthenticationState.HarnessAuthorization = lifecycleLock.withLock {
        ensureOpen()
        supervisor.authorizeAccess(expected, stillValid)
    }

    fun state(): RuntimeStateSnapshot = status.snapshot()
    fun shizukuState(): ShizukuState {
        // 可选的 Shizuku 状态查询不能占住 Harness 启动/停止的生命周期锁。
        ensureOpen()
        return terminals.shizuku.state()
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
    fun keepAliveSnapshot(foregroundServiceActive: Boolean): RuntimeKeepAliveSnapshot {
        // 先在锁外读取可选的 Shizuku 状态；Binder 无响应时不能阻塞运行时生命周期锁。
        ensureOpen()
        val deviceShellReady = deviceShellReady()
        return lifecycleLock.withLock {
            ensureOpen()
            val record = store.runtimeIntentRecord()
            val ownedRunning = status.snapshot().phase == RuntimePhase.RUNNING
            RuntimeKeepAliveSnapshot(
                keepRuntimeInBackground = store.keepRuntimeInBackground(),
                foregroundServiceActive = foregroundServiceActive,
                deviceShellReady = deviceShellReady,
                reconnectRequired = HarnessKeepAlivePolicy.requiresReconnect(
                    runtimeOwnedRunning = ownedRunning,
                    residualProcess = hasResidualHarness(),
                    lastIntent = record.intent,
                ),
                lastIntent = record.intent,
                lastPhase = record.phase,
                lastUpdatedAtMillis = record.updatedAtMillis,
                lastTurnCompletedAtMillis = TaskNotification.lastTurnCompletedAtMillis,
                turnCompletionSequence = TaskNotification.turnCompletionSequence,
            )
        }
    }

    /**
     * Shizuku 健康检查：只读取 binder、授权与 UserService 状态，不执行任何 Shell 命令
     * 与设备操作。Shizuku 未安装、未授权或断开时返回 false，运行时按无设备 Shell 降级。
     */
    private fun deviceShellReady(): Boolean = try {
        val state = terminals.shizuku.healthCheck()
        state.installed && state.running && state.permission == "granted"
    } catch (_: Throwable) {
        false
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        installer.cancelInstall()
        supervisor.requestStartCancellation()
        // 不再接受启动后诊断任务；shutdownNow 中断探测等待，ProcessProbe 会随即终止
        // 它拉起的访客进程，避免把只读探测残留在拆除后的运行时上。
        postStartDiagnosticsExecutor.shutdownNow()
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
