package io.deepseekharness.mobile.runtime

import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * 展示用的运行时版本信息。
 *
 * `slot` 只有三个取值，与磁盘上的位置一一对应：
 * - `current`：正在使用的运行时（[RuntimeStore.currentRoot]）；
 * - `previous`：上一次被换下来、保留着可一键切回去的版本（[RuntimeStore.retainedRoot]）；
 * - `bundled`：APK 资产里内置的运行时（未解压，只在出现更新时才会被安装）。
 *
 * 这里只放展示需要的最小信息：**不包含任何 URL 或凭据**，界面上也不需要它们。
 */
data class RuntimeVersionInfo(
    val slot: String,
    val version: String,
    val dshVersion: String?,
    val runtimeId: String,
    val extractedBytes: Long,
    val active: Boolean,
)

/**
 * 版本槽的纯逻辑判定：哪些操作当前是允许的、目标参数是否合法。
 *
 * 与文件系统无关，因此可以脱离设备直接单测；真正的文件操作在 [RuntimeInstaller] 里。
 */
object RuntimeVersionPolicy {
    const val SLOT_CURRENT = "current"
    const val SLOT_PREVIOUS = "previous"
    const val SLOT_BUNDLED = "bundled"

    /** 支持的切换目标：保留下来的上一版本、出厂内置版本，或版本库中的 64 位 SHA-256 ID。 */
    const val TARGET_PREVIOUS = "previous"
    const val TARGET_BUNDLED = "bundled"

    val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

    /**
     * 「什么算一个可以展示的 dsh 版本号」的唯一规则。
     *
     * 三处必须一致，否则界面上的版本号会随读取路径变化：
     * 运行时自检回执（[RuntimeDshVersion.read]）、清单里的 `dshVersion` 字段（[RuntimeManifest.parse]）、
     * 版本列表（[RuntimeVersionCatalog]）。不匹配一律按「读不到」处理，绝不回传原文。
     */
    val DSH_VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?$")

    /** 解析并校验切换/删除目标；未知目标一律以受控错误码拒绝。 */
    fun requireTarget(raw: String?): String {
        val target = raw?.trim().orEmpty()
        if (target != TARGET_PREVIOUS && target != TARGET_BUNDLED && !SHA256_PATTERN.matches(target)) {
            throw RuntimeFailure("RUNTIME_VERSION_TARGET_INVALID", "运行时版本操作目标无效")
        }
        return target
    }

    /**
     * 能否切换：当前运行时与上一版本必须同时可用。
     *
     * 上一版本只有「根目录 + 清单」同时存在才算可用（[RuntimeStore.retainedManifest] 已经在读取时
     * 保证了这一点），缺少任一半时按钮必须不可用——半份副本无法被提升为当前运行时。
     */
    fun canSwitch(currentPresent: Boolean, previousPresent: Boolean): Boolean = currentPresent && previousPresent

    /** 能否删除上一版本。 */
    fun canDelete(previousPresent: Boolean): Boolean = previousPresent

    /**
     * 版本库容量：只有已下载的归档占配额。
     *
     * 出厂内置版本（APK 资产）与「上一版本」槽位不算配额——它们是回退路径，不是可下载的历史版本。
     */
    const val LIBRARY_CAPACITY = 5

    /** 版本库条目的淘汰事实。纯数据，不碰磁盘。 */
    data class LibraryEntry(
        val id: String,
        val downloadedAt: Long,
        val pinned: Boolean,
        val active: Boolean,
    )

    /** 能不能再放一个：同一版本重下是覆盖，不占新配额。 */
    fun canAddToLibrary(existing: Boolean, count: Int): Boolean = existing || count < LIBRARY_CAPACITY

    /**
     * 满额时该淘汰哪一条：最旧的「未锁定、非当前、且不是本次要写入的那条」。
     *
     * 没有可淘汰的对象时返回 null，由调用方明确失败——**绝不**悄悄删掉用户锁定的版本。
     * 时间并列时按 id 排序，保证同一份磁盘状态永远给出同一个结果（可测试、可复现）。
     */
    fun evictionCandidate(entries: List<LibraryEntry>, incomingId: String?): String? =
        entries.asSequence()
            .filterNot { it.pinned || it.active || it.id == incomingId }
            .sortedWith(compareBy({ it.downloadedAt }, { it.id }))
            .firstOrNull()
            ?.id

    /**
     * 版本库给界面展示的顺序：**最新的在前**。
     *
     * 这是契约，不是装饰：界面按「最新的在前」折叠（默认只露前几条），
     * 所以顺序不能碰运气 —— `RuntimeLibrary.list()` 原先直接返回
     * `newDirectoryStream` 的枚举顺序，而那个顺序由文件系统决定，跟下载时间无关。
     *
     * 与 [evictionCandidate] 方向相反：那边要淘汰最旧的，这边要展示最新的。
     * 时间并列时按 id **降序**，同样是为了「同一份磁盘状态永远给出同一个顺序」。
     */
    fun listOrder(entries: List<LibraryEntry>): List<LibraryEntry> =
        entries.sortedWith(compareByDescending<LibraryEntry> { it.downloadedAt }.thenByDescending { it.id })
}

/**
 * 从已解压的运行时里读 dsh 版本（`opt/dsh/node_modules/@deepseek-ai/dsh/package.json` 的 `version`）。
 *
 * 与运行时自检用的是同一份规则：**只有通过形态校验才回传**，读不到、格式不对、读出错都回 null。
 * 绝不回传路径或未校验的原文。
 */
object RuntimeDshVersion {
    const val RELATIVE_PATH = "opt/dsh/node_modules/@deepseek-ai/dsh/package.json"

    private const val LIMIT_BYTES = 256L * 1024L

    fun read(root: File?): String? {
        if (root == null) return null
        return try {
            val manifest = File(root, RELATIVE_PATH)
            val readable = Files.isRegularFile(manifest.toPath()) && manifest.length() in 1..LIMIT_BYTES
            val raw = if (readable) manifest.readText() else ""
            JSONObject(raw).optString("version", "").takeIf { RuntimeVersionPolicy.DSH_VERSION.matches(it) }
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * 汇总磁盘上现有的运行时版本，供界面展示与「切换/删除上一版本」决策。
 *
 * 读取顺序即优先级：当前运行时 → 上一版本 → APK 内置版本。每一项都来自各自清单，
 * 因此界面显示的版本号与运行时真正加载的清单是同一来源，不存在第二套版本口径。
 */
class RuntimeVersionCatalog(private val store: RuntimeStore) {
    fun list(): List<RuntimeVersionInfo> {
        val entries = mutableListOf<RuntimeVersionInfo>()
        store.installedManifest()?.let {
            entries += it.toInfo(RuntimeVersionPolicy.SLOT_CURRENT, active = true, dshRoot = store.currentRoot)
        }
        store.retainedManifest()?.let {
            entries += it.toInfo(RuntimeVersionPolicy.SLOT_PREVIOUS, active = false, dshRoot = store.retainedRoot)
        }
        store.bundledManifestOrNull()?.let {
            // 内置运行时没有解压目录，读不到包内 package.json，只能依赖清单里声明的版本。
            entries += it.toInfo(RuntimeVersionPolicy.SLOT_BUNDLED, active = false, dshRoot = null)
        }
        return entries
    }

    private fun RuntimeManifest.toInfo(slot: String, active: Boolean, dshRoot: File?): RuntimeVersionInfo =
        RuntimeVersionInfo(
            slot = slot,
            version = version,
            dshVersion = dshVersion ?: RuntimeDshVersion.read(dshRoot),
            runtimeId = runtimeId,
            extractedBytes = rootfs.extractedBytes,
            active = active,
        )
}
