package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.diagnostics.DIAGNOSTIC_DIRECTORY_NAME
import java.io.File

/**
 * 可清理的缓存条目（`context.cacheDir` 的直接子项）。
 *
 * 只认白名单里的名字：缓存目录将来多出任何条目（系统、WebView 或第三方库自己的目录）都不会被
 * 顺手删掉 ——「没登记的东西一律不动」是这一档最重要的性质，比「尽量多删一点」重要得多。
 *
 * 名字**不另抄一份字面量**：诊断导出目录与待安装更新包直接引用它们各自的常量，工作区分享暂存
 * 与 PRoot 临时目录由本对象提供常量、调用点引用它 —— 任何一侧改名都会立刻编译失败或让
 * `RuntimeDataCleanupTest` 的比对用例变红，而不是某天悄悄清错目录。
 */
internal object RuntimeCacheEntries {
    /** 诊断日志的**导出**目录（`cacheDir/diagnostics`）；与 `DiagnosticLog` 同名不同根。 */
    const val DIAGNOSTIC_EXPORTS = DIAGNOSTIC_DIRECTORY_NAME

    /** 工作区对外分享的暂存目录（[RuntimeWorkspaceFiles.copyForSharing] 用的就是它）。 */
    const val WORKSPACE_SHARE = "share"

    /** 文件管理交给其它应用的副本；不纳入手动缓存清理，避免接收方延迟读取 URI。 */
    const val EXTERNAL_SHARE = "external-share"

    /** PRoot 的临时目录（[RuntimeCommand.hostEnvironment] 里的 `PROOT_TMP_DIR` 与 `TMPDIR`）。 */
    const val PROOT_TMP = "proot-tmp"

    /**
     * **明确不在清理范围内**：已下载完成、等着安装的 APK。
     *
     * 它是「待安装的产物」而不是缓存：清掉它，用户会看到更新从「已下载」退回「要重新下载」，
     * 而省下的那点空间并不值得让一次更新白跑一趟。
     */
    const val PENDING_UPDATE = AppUpdateStorage.DIRECTORY_NAME

    /** 这一档会动的全部条目，也是诊断里 `count` 的取值来源。 */
    val CLEARABLE = listOf(DIAGNOSTIC_EXPORTS, WORKSPACE_SHARE, PROOT_TMP)
}

/**
 * 插件**自己的数据目录**（在包目录内部：`versions/<事务>/node_modules/<包名>/…`）。
 *
 * 与访客脚本 `plugin-manager.cjs` 的 `DATA_DIRECTORIES` 逐字一致：更新时保留这四个、清理时也只删
 * 这四个。两边任何一侧改了名字都会让「更新保数据」与「清理删数据」不再指向同一批目录，
 * 因此 `scripts/native-bridge-parity.test.mjs` 会逐项比对这份清单与脚本里的常量。
 */
internal object PluginDataDirectories {
    val NAMES = listOf("data", "config", "storage", ".config")
}

/**
 * 一次清理的账：删了几项、回收了多少、失败几项。
 *
 * [scope] 是这一档覆盖的条目数（缓存档＝白名单条目数、插件数据档＝账本里核对到的包数），
 * [removed] 含走兜底删除器删干净的那些（[fallback] 是其中的子集）。字节与文件数按**删除前**的
 * 不跟随符号链接统计，统计不出来时按 0 计 —— 宁可少报，也不把「删了多少」说大。
 */
internal data class RuntimeCleanupOutcome(
    val scope: Int,
    val targets: Int,
    val removed: Int,
    val fallback: Int,
    val failed: Int,
    val files: Int,
    val bytes: Long,
    val skipped: Int,
) {
    val ok: Boolean get() = failed == 0
}

/** [RuntimeCleanupRunner] 的可变累加器；只在一次清理内部使用。 */
internal class RuntimeCleanupTally {
    private var targets = 0
    private var removed = 0
    private var fallback = 0
    private var failed = 0
    private var files = 0
    private var bytes = 0L
    private var skipped = 0

    /** 条目不存在：不算失败，也不算已删。 */
    fun countSkipped() {
        skipped += 1
    }

    /** @param outcome 只应传删除成功的两种结论；[size] 为 `null` 表示删除前没统计出来。 */
    fun countRemoved(outcome: ResidueDeleteOutcome, size: RuntimeResidueSizing.Size?) {
        targets += 1
        removed += 1
        if (outcome is ResidueDeleteOutcome.CleanedByFallback) fallback += 1
        files += size?.entries ?: 0
        bytes += size?.bytes ?: 0
    }

    fun countFailed() {
        targets += 1
        failed += 1
    }

    fun outcome(scope: Int): RuntimeCleanupOutcome = RuntimeCleanupOutcome(
        scope = scope,
        targets = targets,
        removed = removed,
        fallback = fallback,
        failed = failed,
        files = files,
        bytes = bytes,
        skipped = skipped,
    )
}

/**
 * 单条目清理，两个档位共用同一份实现。
 *
 * 语义是**尽力而为、绝不抛出**：单条失败只累加失败数，不影响其余条目 —— 与
 * [RuntimeResidueSweeper] 同一口径。作用域校验完全交给 [RuntimeResidueCleanup]（严格删除器
 * 自带 `normalizedRoot.parent != normalizedParent ⇒ RESET_SCOPE_INVALID`），这里不放宽任何校验。
 *
 * [existsNoFollow] 是存在性判定的注入口：默认就是真机上的 lstat 版本；JVM 单测里 `Os.lstat`
 * 不可用（Android 桩件会抛「not mocked」），必须能换成朴素的文件系统判断，否则「条目不存在就
 * 跳过」这条分支在单测里永远走不到 —— 而它正是「别把不存在的东西算成已删」的那条分支。
 */
internal class RuntimeCleanupRunner(
    private val cleanup: RuntimeResidueCleanup = RuntimeResidueCleanup(),
    private val sizeOf: (File) -> RuntimeResidueSizing.Size = { RuntimeResidueSizing.of(it) },
    private val existsNoFollow: (File) -> Boolean = { RuntimeFiles.existsNoFollow(it) },
) {
    fun attempt(target: File, allowedParent: File, tally: RuntimeCleanupTally) {
        // 存在性判定用 lstat（不走符号链接）：读不出来的条目当**存在**处理，交给删除器如实报失败，
        // 否则一次权限问题会让这个条目从账上悄悄消失。
        val exists = try {
            existsNoFollow(target)
        } catch (_: Throwable) {
            true
        }
        if (!exists) {
            tally.countSkipped()
            return
        }
        val size = try {
            sizeOf(target)
        } catch (_: Throwable) {
            null
        }
        val outcome = try {
            cleanup.delete(target, allowedParent)
        } catch (_: Throwable) {
            // 删除器只应抛 RuntimeFailure；其它异常同样按失败记，绝不静默。
            tally.countFailed()
            return
        }
        when (outcome) {
            is ResidueDeleteOutcome.Cleaned, is ResidueDeleteOutcome.CleanedByFallback ->
                tally.countRemoved(outcome, size)

            is ResidueDeleteOutcome.Failed -> tally.countFailed()
        }
    }
}

/**
 * 缓存档清理：只删 [RuntimeCacheEntries.CLEARABLE] 里登记的三个目录。
 *
 * 调用方必须在**运行时停止**时才调用它：`proot-tmp` 是运行中沙箱的 `TMPDIR`、`share` 是投递暂存，
 * 运行中删掉会打断正在进行的会话（这条判定在桥接层，见 `MobileRuntimePlugin.clearRuntimeCache`）。
 *
 * 不删 [RuntimeCacheEntries.PENDING_UPDATE]（待安装的更新包），也不碰运行时数据 —— 后者是
 * 「重置运行环境」那一档的事，破坏面完全不同，绝不混在一个按钮里。
 */
internal class RuntimeCacheCleanup(
    private val cacheDir: File,
    private val runner: RuntimeCleanupRunner = RuntimeCleanupRunner(),
) {
    fun clear(): RuntimeCleanupOutcome {
        val tally = RuntimeCleanupTally()
        for (name in RuntimeCacheEntries.CLEARABLE) {
            runner.attempt(File(cacheDir, name), cacheDir, tally)
        }
        return tally.outcome(scope = RuntimeCacheEntries.CLEARABLE.size)
    }
}

/**
 * 插件数据档清理：删掉已登记插件包内部的四个数据目录，**包本体一律不动**。
 *
 * 由**版本账本**驱动（[PluginInstallLock]）：只有账本里登记、且包目录确实还在保留区内的包才参与。
 * 账本读不出来（缺失、损坏、任何一条不合法）时返回 `null` —— 不猜哪些目录是插件数据，也绝不遍历
 * `versions/` 去「看起来像插件目录」的东西上删数据：清错一个目录比清不掉严重得多。
 *
 * 调用方同样必须在运行时停止时才调用它：插件进程正在写自己的存储时删目录，会把「清理」变成
 * 「损坏一半的状态」。
 *
 * @param packageName 只清这一个包（界面上的「清这个插件的数据」）；`null` 表示清全部已登记的包。
 *   给了包名时，**绝不拿请求里的字符串去拼路径** —— 只用它与账本核对出来的条目做相等比较，
 *   删的是账本条目的目录。核对不到（账本里没这个包、或它的目录已经不在保留区）就抛
 *   `CLEANUP_PLUGIN_UNKNOWN` 且一个字节都不动：这时候「什么都不做」比「猜一个目录删掉」正确。
 */
internal class PluginDataPurge(
    private val home: File,
    private val lockFile: File = File(home, PluginInstallLock.FILE_NAME),
    private val runner: RuntimeCleanupRunner = RuntimeCleanupRunner(),
) {
    fun purge(packageName: String? = null): RuntimeCleanupOutcome? {
        val snapshot = PluginInstallLock.read(lockFile) ?: return null
        val known = PluginInstallLock.directories(home, snapshot)
        val packages = if (packageName == null) known else known.filter { it.id == packageName }
        if (packageName != null && packages.isEmpty()) {
            throw RuntimeFailure("CLEANUP_PLUGIN_UNKNOWN", "账本里核对不到这个插件的包目录，未清理任何数据")
        }
        val tally = RuntimeCleanupTally()
        for (packageDirectory in packages) {
            for (name in PluginDataDirectories.NAMES) {
                runner.attempt(File(packageDirectory.directory, name), packageDirectory.directory, tally)
            }
        }
        return tally.outcome(scope = packages.size)
    }
}

/**
 * 插件数据档的**逐条现状**：账本里核对到的每个包，各自四个数据目录合计占了多少。
 *
 * 与 [PluginDataPurge] 共用同一套「哪些目录算插件数据」的判据（[PluginInstallLock.directories] 加上
 * [PluginDataDirectories.NAMES]），否则会出现「界面说这个插件占了 3 MB，点清理却删不动」这种自相
 * 矛盾的读数 —— 界面上的数字与按钮背后的动作必须同源。
 *
 * 账本不可用时返回 `null`（与 `purge` 同一口径：不猜）。[existsNoFollow] 与 [sizeOf] 是接缝：
 * JVM 单测里 Android 的 `Os.lstat` 会抛「not mocked」，没有接缝就走不到「这个插件没有数据目录」
 * 那条分支 —— 而那正是「显示 0 但列出来」与「列都不列」的分界。
 */
internal class PluginDataInventory(
    private val home: File,
    private val lockFile: File = File(home, PluginInstallLock.FILE_NAME),
    private val sizeOf: (File) -> RuntimeResidueSizing.Size = { RuntimeResidueSizing.of(it) },
    private val existsNoFollow: (File) -> Boolean = { RuntimeFiles.existsNoFollow(it) },
) {
    /** @param present 四个数据目录里**至少有一个存在**（空目录也算存在）；一个都不存在时占用为 0。 */
    data class Entry(val id: String, val present: Boolean, val files: Int, val bytes: Long)

    fun snapshot(): List<Entry>? {
        val lock = PluginInstallLock.read(lockFile) ?: return null
        return PluginInstallLock.directories(home, lock).map { packageDirectory ->
            var present = false
            var files = 0
            var bytes = 0L
            for (name in PluginDataDirectories.NAMES) {
                val target = File(packageDirectory.directory, name)
                // 存在性读不出来时按存在处理：让界面显示「有这个目录但读不到」，与缓存档同一口径。
                val exists = try {
                    existsNoFollow(target)
                } catch (_: Throwable) {
                    true
                }
                if (!exists) continue
                present = true
                val size = try {
                    sizeOf(target)
                } catch (_: Throwable) {
                    null
                } ?: continue
                files += size.entries
                bytes += size.bytes
            }
            Entry(packageDirectory.id, present, files, bytes)
        }
    }
}
