package io.deepseekharness.mobile.runtime

import android.content.Context
import android.system.Os
import com.getcapacitor.JSObject
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** 不启动 Harness 的配置管理通道；不向管理进程传递模型或设备桥接凭据。 */
class RuntimePluginManager(context: Context, private val store: RuntimeStore) {
    private val appContext = context.applicationContext
    private val resolver = RuntimeLaunchResolver(context, store, includeCredentials = false)
    private val directory get() = File(store.currentRoot, "root/.dsh-mobile")

    /**
     * 插件管理区（`root/.dsh-mobile/plugin-manager`）：访客脚本的 `home`，也是
     * `RuntimePreservePolicy` 里跨运行时升级**唯一**保留的那一项。
     * `versions/<事务>/node_modules/<包名>`、`state-snapshot.json`、`previous.json`、
     * `installed-lock.json` 与 `installed-migration.json` 都在这里。
     */
    private val home get() = File(directory, "plugin-manager")

    /** 最近一次**成功**修复所对应的运行时代次指纹；见 [repairInstalledIfNeeded]。 */
    @Volatile
    private var repairedGeneration: String? = null

    /** 最近一次**失败**所对应的运行时代次；配合下面时刻构成失败冷却窗口。 */
    @Volatile
    private var repairFailureGeneration: String? = null

    /** 最近一次修复失败的时刻；从未失败时为 0。 */
    @Volatile
    private var repairFailureAtMillis: Long = 0L

    /** 最近一次运行时升级后的插件迁移结果；只保存受控计数，供插件页面展示。 */
    @Volatile
    private var recoveryStatus: String = "none"
    @Volatile
    private var recoveryRestored: Int = 0
    @Volatile
    private var recoveryRelinked: Int = 0
    @Volatile
    private var recoveryMissing: Int = 0
    @Volatile
    private var recoveryFailed: Int = 0
    @Volatile
    private var recoveryRefused: Int = 0

    /** 应用升级迁移的结果：`none`（本次进程没跑）/ `done` / `partial` / `failed`。 */
    @Volatile
    private var migrationStatus: String = "none"

    /**
     * 版本账本（`installed-lock.json`）的可用性：
     * `ok`（已按账本核对）/ `missing`（还没有账本）/ `invalid`（账本损坏）/ `unwritable`（访客报告写入失败）。
     */
    @Volatile
    private var lockStatus: String = "missing"

    /** 上次核对账本时逐条检查过的插件条目数；还没有账本时为 0。 */
    @Volatile
    private var lockChecked: Int = 0

    /** 访客最近一次操作是否报告「账本写不进去」；由 [run] 从受控字段读出，下一次成功写入即清掉。 */
    @Volatile
    private var lockWriteFailed: Boolean = false

    /** 返回插件迁移结果快照，不包含路径、日志或插件内容。 */
    fun recoverySnapshot(): JSObject = JSObject()
        .put("status", recoveryStatus)
        .put("restored", recoveryRestored)
        .put("relinked", recoveryRelinked)
        .put("missing", recoveryMissing)
        .put("failed", recoveryFailed)
        .put("refused", recoveryRefused)
        .put("migration", migrationStatus)
        .put("lock", lockStatus)
        .put("checked", lockChecked)

    fun recoverIfNeeded() {
        // 运行时升级通常不会留下 transaction.json：它会重建 profiles 清单，但保留
        // plugin-manager/state-snapshot.json。只检查事务文件会跳过正常升级后的状态回填，
        // 结果是市场插件本体还在保留区，清单却没有插件名，后续 repair 也无法接回链接。
        // 两个文件都不存在时不启动 Node，避免首次安装每次启动都做无意义的恢复探测。
        val transaction = File(directory, "transaction.json").exists()
        val snapshot = File(directory, "state-snapshot.json").exists()
        if (transaction || snapshot) {
            try {
                val result = run("recover", null, null, null)
                val state = result.optJSONObject("state")
                if (state?.optBoolean("restored", false) == true) {
                    recoveryRestored = state.optInt("plugins", 0).coerceAtLeast(0)
                    if (recoveryRestored > 0) recoveryStatus = "restored"
                } else if (state?.optString("reason") == "failed") {
                    recoveryStatus = "failed"
                    recoveryFailed = 1
                }
            } catch (error: Exception) {
                recoveryStatus = "failed"
                recoveryFailed = 1
                throw error
            }
        }
    }

    /**
     * 已安装插件的运行时链接修复（幂等自愈，不需要重新下载插件）。
     *
     * 需要它的两种坏形态：插件目录会被运行时升级保留，而里面指向运行时包的绝对链接带着旧
     * 版本号与 peer 哈希，新根目录里已经不存在（悬空链接）；早期安装留下的真实副本则会让
     * 运行时里出现第二份 dsh-tools —— 它的 Symbol 是每个物理模块副本各造一个，身份失配后
     * `ctx.tools[调度器 Symbol]` 取到 undefined，**每一次工具调用**都会失败。
     *
     * 同一运行时代次只做一次（指纹为 runtimeId + 版本 + rootfs 摘要）：没有换运行时就不会
     * 产生新的坏形态。失败不抛给调用方，并进入冷却窗口（判定见 [PluginRepairPolicy]）——
     * 插件列表与 Harness 启动不应因为一次自愈失败而反复中断。
     *
     * @param explicit 是否由用户主动触发（设置页的「立即修复插件」）。true 时不受上面那道自愈
     *   闸门约束：用户点了按钮就是要现在重试一次，同一代次、冷却窗口内都照做；计数按本次结果
     *   覆盖而不再取历史最大值（否则重装好插件后再修一次，界面还会停在上一轮的「缺失 N 个」）；
     *   失败也会**抛出**而不是吞掉，让界面能如实回显失败原因。
     */
    fun repairInstalledIfNeeded(explicit: Boolean = false) {
        val generation = installedGeneration()
        // 自愈路径读不到清单就直接放弃；用户主动修复继续往下走，由 run() 抛出受控错误码
        // （例如 RUNTIME_NOT_INSTALLED），而不是按钮点下去毫无反应。
        if (generation == null && !explicit) return
        val now = System.currentTimeMillis()
        val attempt = PluginRepairPolicy.shouldAttempt(
            generation = generation.orEmpty(),
            repairedGeneration = repairedGeneration,
            lastFailureGeneration = repairFailureGeneration,
            lastFailureAtMillis = repairFailureAtMillis,
            nowMillis = now,
            explicit = explicit,
        )
        if (!attempt) return
        try {
            val summary = run("repair", null, null, null)
            if (generation != null) repairedGeneration = generation
            // 只记计数，让下次导出的诊断日志能直接回答两个问题：
            //  一、设备上到底有没有重复的运行时包：files = 扫到多少个包条目，count = 其中多少个
            //      被重新指向运行时实例（**count > 0 即确实存在重复副本**）；
            //  二、这次运行时升级有没有抹掉插件链接：plugins = 清单里登记的第三方插件数，
            //      relinked = 其中多少个被接回 profile 模块根（**relinked > 0 即确实抹掉过**，
            //      包本体仍在保留区，所以不需要重装），missing = 保留区里已无副本、只能重装的数量；
            //  - result = denied 表示有包没能修复（拒绝或失败），需要人工介入。
            val linked = summary.optInt("linked", 0)
            val unfixed = summary.optInt("failed", 0) + summary.optInt("refused", 0)
            val restored = recoveryRestored
            val relinked = summary.optInt("relinked", 0)
            val missing = summary.optInt("missing", 0)
            val failed = summary.optInt("failed", 0)
            val refused = summary.optInt("refused", 0)
            // 用户主动修复时按本次结果覆盖历史计数：上一轮缺失的插件可能已经重装好了，
            // 继续取历史最大值会让界面永远停在「缺失 N 个」，反而看不出这次修复到底有没有用。
            recoveryRelinked = relinked
            recoveryMissing = if (explicit) missing else maxOf(recoveryMissing, missing)
            recoveryFailed = if (explicit) failed else maxOf(recoveryFailed, failed)
            recoveryRefused = if (explicit) refused else maxOf(recoveryRefused, refused)
            recoveryStatus = when {
                recoveryFailed > 0 || recoveryRefused > 0 -> if (restored + relinked > 0) "partial" else "failed"
                recoveryMissing > 0 -> if (restored + relinked > 0) "partial" else "failed"
                restored + relinked > 0 -> "restored"
                else -> "none"
            }
            store.diagnostics.record(
                DiagnosticLevel.INFO,
                DiagnosticEvent.REPAIR,
                mapOf(
                    "result" to if (unfixed > 0) "denied" else "ok",
                    "count" to linked.toString(),
                    "files" to summary.optInt("scanned", 0).toString(),
                    "plugins" to summary.optInt("plugins", 0).toString(),
                    "relinked" to summary.optInt("relinked", 0).toString(),
                    "missing" to summary.optInt("missing", 0).toString(),
                ),
            )
            // 修复刚改过盘：顺手按版本账本核对一遍（只读，代价是一次小文件读取 + 逐条 stat），
            // 这样用户点一次「立即修复插件」之后，界面上的「核对数 / 缺失数」都是本次的新结果。
            verifyInstallLock()
        } catch (failure: Exception) {
            // run() 已把失败转成受控错误码；自愈是尽力而为，因此不抛出。
            // 记录失败时刻并进入冷却：失败通常是确定性的，立刻重试只会让下一次
            // 读取插件列表再白等一次完整超时。
            store.diagnostics.record(
                DiagnosticLevel.WARN,
                DiagnosticEvent.REPAIR,
                mapOf("result" to "failed", "count" to "0"),
            )
            recoveryStatus = "failed"
            recoveryFailed = 1
            if (generation != null) {
                repairFailureGeneration = generation
                repairFailureAtMillis = now
            }
            // 用户主动点的修复必须如实回显失败：把受控错误码交给调用方（自愈路径照旧吞掉）。
            if (explicit) throw failure
        }
    }

    /** 当前已安装运行时的代次指纹（runtimeId + 版本 + rootfs 摘要）；未安装时为 null。 */
    private fun installedGeneration(): String? =
        store.installedManifest()?.let { "${it.runtimeId}|${it.version}|${it.rootfs.sha256}" }

    /**
     * 应用（APK）升级后的插件迁移。
     *
     * 判定在 [PluginMigrationPolicy]（纯逻辑，可单测）；动作**串行复用**已有的两步 ——
     * 先 [recoverIfNeeded]（回放未提交事务、按状态快照回填启停状态），再
     * [repairInstalledIfNeeded]（按运行时代次自愈插件链接）—— 最后按版本账本核对每个已装插件的
     * 包目录是否还在。**不另写一套接链接逻辑**：`plugin-manager.cjs` 的 repair 分支已经处理了
     * 「保留区里没有副本只记 missing」「已经能解析就不动」这些边界，重写一遍只会多出第二个真相。
     *
     * 三条硬约束：
     *  - **绝不覆盖或删除用户数据**：整个过程只有 recover 与 repair 两类动作，而数据目录
     *    （`data`/`config`/`storage`/`.config`）的复制只发生在更新与导入的提交路径上，
     *    原样受既有的 `PLUGIN_DATA_UNSAFE` / `PLUGIN_DATA_TOO_LARGE` 约束；
     *  - **失败不许静默**：结果写进 [recoverySnapshot] 的 `migration` / `lock` / `checked`，
     *    失败与部分完成另写一条 `REPAIR` 诊断记录（受控 `code`，见下）；
     *  - **记账失败不能让启动失败**：只有 recover 自己的异常照旧往上抛（与改动前逐字一致），
     *    记账与核对失败一律只记录状态。
     *
     * @param currentVersionCode 当前 `BuildConfig.VERSION_CODE`（由调用方传入，本类不依赖 BuildConfig，
     *   这样策略与判定都能在纯 JVM 单测里覆盖）。
     * @return true 表示本次判定需要迁移并已执行；调用方不必再单独调用上面那两步。
     */
    fun migrateIfNeeded(currentVersionCode: Int): Boolean {
        val stateFile = File(home, PluginMigrationState.FILE_NAME)
        val recorded = PluginMigrationState.read(stateFile)
        val generation = installedGeneration()
        val decision = PluginMigrationPolicy.decide(
            lastMigratedVersionCode = recorded?.appVersionCode,
            currentVersionCode = currentVersionCode,
            generationChanged = recorded != null && recorded.runtimeGeneration != generation,
        )
        if (!decision.migrate) return false
        try {
            recoverIfNeeded()
            repairInstalledIfNeeded()
        } catch (failure: Exception) {
            // recover 的失败在本次改动前就会中断启动（recoverIfNeeded 直接向上抛）：保持同一语义，
            // 但先留下「迁移没跑成」的状态与一条受控诊断，免得只看到一句运行时错误。
            migrationStatus = "failed"
            recordMigration(DiagnosticLevel.WARN, "failed", PLUGIN_MIGRATION_RECOVER_FAILED, decision.reason)
            throw failure
        }
        val lock = verifyInstallLock()
        // 迁移成功与否的记账：写不进去就等于"这一步没做成"，下次启动照旧重试一次
        // （recover 幂等、repair 受代次闸门约束，重试的代价是一次访客进程，不是重复改动）。
        val recordedNow = try {
            PluginMigrationState.write(
                stateFile,
                PluginMigrationState.Record(currentVersionCode, generation, System.currentTimeMillis(), decision.reason),
            )
            true
        } catch (_: Exception) {
            false
        }
        val settled = recoveryMissing == 0 && recoveryFailed == 0 && recoveryRefused == 0
        migrationStatus = when {
            !recordedNow -> "failed"
            lock == "ok" && settled -> "done"
            else -> "partial"
        }
        // 失败与部分完成各带一个受控错误码：`code` 是诊断记录里唯一能区分「卡在哪一步」的字段。
        // 成功不写 code（成功由 `result=ok` 与 `installed-migration.json` 自证）。
        val code = when {
            !recordedNow -> PLUGIN_MIGRATION_RECORD_FAILED
            migrationStatus == "partial" -> PLUGIN_MIGRATION_PARTIAL
            else -> null
        }
        val level = if (migrationStatus == "failed") DiagnosticLevel.WARN else DiagnosticLevel.INFO
        val result = when (migrationStatus) {
            "done" -> "ok"
            "partial" -> "denied"
            else -> "failed"
        }
        recordMigration(level, result, code, decision.reason)
        return true
    }

    /**
     * 按版本账本核对每个已装插件的包目录是否还在（**只读**：读一份小 JSON + 逐条 stat 目录）。
     *
     * 账本由访客脚本维护，原生侧只读。结论写进 [lockStatus] / [lockChecked]；其中「缺失」的条数
     * 并入 [recoveryMissing]（取较大值：repair 的 missing 与账本的缺失是同一件事的两次观测，
     * 谁都不该把对方的缺口抹平）。访客报告过写入失败时状态是 `unwritable` —— 那比"文件损坏"
     * 更接近根因（磁盘或权限），界面文案也不同。
     */
    private fun verifyInstallLock(): String {
        val file = File(home, PluginInstallLock.FILE_NAME)
        val snapshot = PluginInstallLock.read(file)
        if (snapshot == null) {
            lockChecked = 0
            // 文件在却读不出来 = 账本损坏；文件不在 = 还没有账本（新设备或从未装过插件）。
            lockStatus = when {
                lockWriteFailed -> "unwritable"
                file.exists() -> "invalid"
                else -> "missing"
            }
            return lockStatus
        }
        val verification = PluginInstallLock.verify(home, snapshot)
        lockChecked = verification.checked
        if (verification.missing > 0) recoveryMissing = maxOf(recoveryMissing, verification.missing)
        lockStatus = if (lockWriteFailed) "unwritable" else "ok"
        return lockStatus
    }

    /** 迁移的诊断记录：受控取值只有 `result` / `code` / `reason` / `count`，**不含路径与文件名**。 */
    private fun recordMigration(level: DiagnosticLevel, result: String, code: String?, reason: String) {
        store.diagnostics.record(
            level,
            DiagnosticEvent.REPAIR,
            buildMap {
                put("result", result)
                put("reason", reason)
                put("count", recoveryMissing.toString())
                if (code != null) put("code", code)
            },
        )
    }

    /**
     * 只读模块图探测：统计访客里 `@deepseek-ai/dsh-tools` 出现了几处，以及**不同真实路径数**。
     *
     * 返回 `{ total, realCopies, links, distinctRealpaths }`，**只有计数、没有任何路径**。
     *
     * 与 [repairInstalledIfNeeded] 的区别：本方法只读、不改动任何文件，也不参与代次指纹，
     * 因此可以在每次 Harness 启动前独立触发。
     */
    fun scanModuleGraph(): JSObject = run("graph", null, null, null)

    /**
     * 探测并把结果写进诊断日志（只记计数，见 [DiagnosticEvent.MODULE_GRAPH]）。
     *
     * 判据是 `distinctRealpaths`（不同真实路径数），不是出现次数：同一个真实目录被多个链接
     * 引用多少次都只算一份，只有真正出现**两份物理副本**时才会 > 1 —— 那正是
     * `ctx.tools[调度器 Symbol]` 取到 undefined、所有工具调用在 `.prepare` 上抛错的成因。
     *
     * 每次 Harness 启动都探测，不按代次缓存。理由：探测只读、只遍历固定候选根且有深度与
     * 条目上限，代价与一次插件列表读取相当；而按代次缓存会在「装了新插件」这种不换代次、
     * 却最容易产生第二份副本的场景下给出过期结论 —— 诊断日志必须回答的是**当场**有几份。
     * 探测失败不抛给调用方（run() 已把失败转成受控错误码），也不能让 Harness 启动失败。
     */
    fun recordModuleGraph() {
        try {
            val summary = scanModuleGraph()
            val distinct = summary.optInt("distinctRealpaths", 0)
            val duplicated = distinct > 1
            store.diagnostics.record(
                if (duplicated) DiagnosticLevel.WARN else DiagnosticLevel.INFO,
                DiagnosticEvent.MODULE_GRAPH,
                mapOf(
                    "result" to if (duplicated) "failed" else "ok",
                    "count" to distinct.toString(),
                    "files" to summary.optInt("total", 0).toString(),
                ),
            )
        } catch (_: Exception) {
            // 探测本身失败：用受控码与 result=denied 区分于上面的「确实有两份」（result=failed），
            // 避免把一次失败的探测误读成判据成立。
            store.diagnostics.record(
                DiagnosticLevel.WARN,
                DiagnosticEvent.MODULE_GRAPH,
                mapOf("result" to "denied", "count" to "0", "code" to "PROBE_FAILED"),
            )
        }
    }

    fun run(operation: String, id: String?, enabled: Boolean?, childId: String?, source: String? = null): JSObject {
        if (operation !in setOf("list", "enable", "child", "update", "import", "rollback", "recover", "repair", "graph")) invalid()
        if (operation == "import") {
            // 来源必填，且与访客脚本 `validateImportSource`、前端 `src/platform/plugins.ts` 是
            // **同一套规则**（逐条对应见 `PluginSourcePolicy`）：三处任意一处放松，
            // 别的入口就能把危险字符串送进 npm。这里只做"是否合法"的判定，不回显输入。
            if (PluginSourcePolicy.classify(source) == null) invalid()
            // 包名只是"期望的名字"，用于装完后消歧；给了就必须合法。
            if (id != null && (id.length !in 1..214 || !PACKAGE.matches(id) || ".." in id)) invalid()
        } else if (operation !in setOf("list", "recover", "repair", "graph")) {
            if (id == null || id.length !in 1..214 || !PACKAGE.matches(id) || ".." in id) invalid()
            if (operation in setOf("enable", "child") && enabled == null) invalid()
            if (operation == "child" && (childId == null || !ENTRY.matches(childId))) invalid()
        }
        if (store.installedManifest() == null) throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "请先安装 Ubuntu 运行时")
        prepareScript()
        val argv = mutableListOf("/opt/node/bin/node", "/root/.dsh-mobile/plugin-manager.cjs", operation)
        if (operation == "import") {
            // 约定：`import <来源> [<包名>]`——来源在前，包名只在需要消歧时给。
            argv.add(source.orEmpty())
            if (id != null) argv.add(id)
        } else {
            if (id != null) argv.add(id)
            if (enabled != null) argv.add(enabled.toString())
            if (childId != null) argv.add(childId)
        }
        val timeoutSeconds = when (operation) {
            "update" -> 250L
            // 导入要跑一次 npm 安装（可能下载 tarball 或 clone 仓库），与更新同一量级。
            "import" -> 250L
            // 修复要遍历已安装的插件目录，给足时间但仍是有限等待。
            "repair" -> 90L
            // 模块图探测是只读遍历（有深度与条目上限），不需要修复那么长的等待。
            "graph" -> 30L
            else -> 30L
        }
        val result = ProcessProbe.run(resolver.launch(argv), store.currentRoot, timeoutSeconds, outputLimit = 256 * 1024)
        val payload = try {
            // 运行器可能输出诊断行；只接受最后一行的有界 JSON，不回传原始日志。
            val line = result.output.trimEnd().lineSequence().lastOrNull().orEmpty()
            if (line.length > 220000) throw IllegalArgumentException()
            JSObject(line)
        } catch (_: Exception) {
            throw RuntimeFailure("PLUGIN_OPERATION_FAILED", "插件操作失败，请重试")
        }
        if (!result.succeeded || payload.has("error")) {
            val code = payload.optString("error").takeIf { it in ERROR_CODES } ?: "PLUGIN_OPERATION_FAILED"
            // 受控详情：只接受包名/版本/范围字符集。它来自 guest 脚本，必须在此再校验一次，
            // 否则路径或异常原文会随消息一路回到 WebView。字符集允许 `/`（作用域包名需要），
            // 因此额外拒绝以 `/`、`~`、`.` 开头以及含 `//` 的取值，挡住路径形态。
            val detail = payload.optString("detail").takeIf { candidate ->
                DETAIL.matches(candidate) &&
                    !candidate.startsWith("/") && !candidate.startsWith("~") && !candidate.startsWith(".") &&
                    !candidate.contains("//")
            }
            throw RuntimeFailure(code, detail ?: "插件操作失败，请检查运行时状态后重试")
        }
        if (operation in setOf("list", "enable", "child", "update", "import", "rollback") && payload.optJSONArray("plugins") == null) {
            throw RuntimeFailure("PLUGIN_OPERATION_FAILED", "插件返回数据无效")
        }
        // 修复只回传计数（处理了多少个包、失败多少个），没有插件清单。
        if (operation == "repair" && payload.optInt("scanned", -1) < 0) {
            throw RuntimeFailure("PLUGIN_OPERATION_FAILED", "插件返回数据无效")
        }
        // 模块图探测同样只回传计数。判据字段是「不同真实路径数」：缺失或为负说明载荷不可信，
        // 宁可报错也不能把 0 当成「只有一份」写进诊断日志。
        if (operation == "graph" && payload.optInt("distinctRealpaths", -1) < 0) {
            throw RuntimeFailure("PLUGIN_OPERATION_FAILED", "插件返回数据无效")
        }
        // 版本账本（`installed-lock.json`）的写入结果由访客脚本随操作结果回传：只有 `failed`
        // 才算问题（`written` 是正常写入，`skipped` 表示本次没有可登记的事实 —— 例如清单都还不
        // 存在），且下一次成功写入会清掉这个标志。这里是**唯一**的读取点：所有操作都走本方法。
        val lockWrite = payload.optString("lock")
        if (lockWrite.isNotEmpty()) lockWriteFailed = lockWrite == "failed"
        return payload
    }

    private fun prepareScript() {
        try {
            // 安全校验：固定目录逐层拒绝符号链接；脚本来自 APK，临时文件独占创建。
            for (folder in listOf(File(store.currentRoot, "root"), directory)) {
                if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(folder.toPath())
                if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) invalid()
            }
            Os.chmod(directory.absolutePath, 0x1c0)
            val target = File(directory, "plugin-manager.cjs")
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) invalid()
            val pending = Files.createTempFile(directory.toPath(), ".plugin-manager-", ".tmp")
            try {
                Os.chmod(pending.toString(), 0x180)
                appContext.assets.open("support/plugin-manager.cjs").use { input ->
                    Files.newOutputStream(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output -> input.copyTo(output) }
                }
                Os.rename(pending.toString(), target.absolutePath)
            } finally { Files.deleteIfExists(pending) }
        } catch (failure: RuntimeFailure) { throw failure }
        catch (error: Exception) { throw RuntimeFailure("PLUGIN_OPERATION_FAILED", "无法准备插件管理器", error) }
    }

    private fun invalid(): Nothing = throw RuntimeFailure("PLUGIN_INPUT_INVALID", "插件参数或路径无效")

    private companion object {
        val PACKAGE = Regex("^(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")
        val ENTRY = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        /** 受控详情：包名、版本号与 semver 范围字符集；拒绝引号、反斜杠、冒号与控制字符。 */
        val DETAIL = Regex("^[A-Za-z0-9@/._+, =!<>~^|()\\-]{1,300}$")
        val ERROR_CODES = setOf("PLUGIN_INPUT_INVALID", "PLUGIN_PATH_INVALID", "PLUGIN_CONFIG_INVALID", "PLUGIN_NOT_FOUND", "PLUGIN_PROTECTED", "PLUGIN_RECOVERY_FAILED", "PLUGIN_UPDATER_MISSING", "PLUGIN_UPDATE_FAILED", "PLUGIN_LINK_UNSUPPORTED", "PLUGIN_DEPENDENCY_UNSUPPORTED", "PLUGIN_ENGINE_UNSUPPORTED", "PLUGIN_GROUP_DISABLED", "PLUGIN_OPERATION_FAILED", "PLUGIN_SOURCE_INVALID", "PLUGIN_IMPORT_UNRESOLVED", "PLUGIN_GIT_MISSING", "PLUGIN_GIT_UNVERIFIED", "PLUGIN_DATA_TOO_LARGE", "PLUGIN_DATA_UNSAFE", "PLUGIN_ROLLBACK_UNAVAILABLE", "PLUGIN_ROLLBACK_FAILED")

        /** 迁移在 recover 这一步就失败：异常已向上抛出，启动照旧中断。 */
        const val PLUGIN_MIGRATION_RECOVER_FAILED = "PLUGIN_MIGRATION_RECOVER_FAILED"

        /** 迁移跑完了，但「上次迁移成功」的记账没写成：下次启动会再跑一次。 */
        const val PLUGIN_MIGRATION_RECORD_FAILED = "PLUGIN_MIGRATION_RECORD_FAILED"

        /** 迁移跑完了，但账本不可用或仍有缺失/失败/拒绝的缺口。 */
        const val PLUGIN_MIGRATION_PARTIAL = "PLUGIN_MIGRATION_PARTIAL"
    }
}

/**
 * 插件导入来源的判定规则。
 *
 * **为什么要有这个对象**：同一条规则要出现在三个地方——访客脚本 `validateImportSource`、
 * 这里（原生侧最后一道闸）、前端 `src/platform/plugins.ts`——三处任意一处放松，
 * 别的入口就能把危险字符串送进 npm。把它抽成不依赖 Android 的纯对象，是为了能用普通
 * 单元测试逐条对照三处规则，而不是只能靠真机跑一遍"看起来没问题"。
 *
 * 判据（与访客脚本逐条对应）：
 *  - 长度 1..512；
 *  - 拒绝以 `-` 开头（`npm install -x` 形态的选项注入）、任何 `..`（含 URL 编码的 `%2e`）；
 *  - 字符白名单 `[A-Za-z0-9@/:._~%+?#=&-]`：空白、引号、尖括号、反引号、换行都在此被拒；
 *  - 裸 npm 包名（含作用域名）直接合法；
 *  - 否则必须是 `https://` 或 `git+https://`，主机名只能是字母数字点横线（可带端口）——
 *    不允许 `@`，因此 `https://user:pass@host/x.tgz` 这类带 userinfo 的地址进不来；
 *  - `git+https://…#<ref>` 的 ref 只允许 `[A-Za-z0-9._/-]{1,128}`。
 */
internal object PluginSourcePolicy {
    const val MAX = 512
    const val GIT_PREFIX = "git+https://"
    const val HTTPS_PREFIX = "https://"
    private val packageName = Regex("^(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")
    private val chars = Regex("^[A-Za-z0-9@/:._~%+?#=&-]+$")
    private val host = Regex("^[A-Za-z0-9.-]+(?::[0-9]{1,5})?$")
    private val ref = Regex("^[A-Za-z0-9._/-]{1,128}$")
    /** 大小写不敏感的 `%2e`：URL 编码的点同样能拼出 `..`，必须和明文一起拒绝。 */
    private val percentDot = Regex("%2e", RegexOption.IGNORE_CASE)

    /** 合法时返回 `"npm"` / `"url"` / `"git"`，不合法时返回 `null`；调用方只看到结论。 */
    fun classify(raw: String?): String? {
        val candidate = raw ?: return null
        if (candidate.length !in 1..MAX) return null
        if (candidate.startsWith("-") || ".." in candidate || percentDot.containsMatchIn(candidate)) return null
        if (!chars.matches(candidate)) return null
        if (packageName.matches(candidate)) return "npm"
        val prefix = when {
            candidate.startsWith(GIT_PREFIX) -> GIT_PREFIX
            candidate.startsWith(HTTPS_PREFIX) -> HTTPS_PREFIX
            else -> return null
        }
        val rest = candidate.removePrefix(prefix)
        if (!host.matches(rest.split('/', '?', '#').first())) return null
        if (prefix == GIT_PREFIX) {
            val hash = rest.indexOf('#')
            if (hash >= 0 && !ref.matches(rest.substring(hash + 1))) return null
        }
        return if (prefix == GIT_PREFIX) "git" else "url"
    }
}
