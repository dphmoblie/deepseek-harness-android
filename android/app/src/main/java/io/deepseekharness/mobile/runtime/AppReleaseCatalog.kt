package io.deepseekharness.mobile.runtime

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject

/** 本机已安装的应用版本：[versionName] 给人看，[versionCode] 只作为事实回报给界面。 */
internal data class InstalledAppInfo(val versionName: String, val versionCode: Long)

/**
 * 应用自身（APK）的版本号规则。
 *
 * 形态是 `0.2.0` 或 `0.2.0-mobile-308`：后缀是**同一版本内的构建序号**，不是预发布标记。
 * 所以 `0.2.0` 视为序号 0，排序为 `0.2.0` < `0.2.0-mobile-308` < `0.2.1-mobile-2`。
 *
 * 注意：运行时镜像的排序规则（[RuntimeUpdatePolicy.compareVersions]）把「无后缀」排在
 * 「有后缀」之前，与这里相反——两者不能互相复用。
 */
internal object AppUpdateVersion {
    private val pattern = Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-mobile-([0-9]+))?$")

    data class Version(
        /** 标签去掉前导 `v` 后的原文，直接拿去展示与拼文件名。 */
        val text: String,
        val major: Long,
        val minor: Long,
        val patch: Long,
        val suffix: Long,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.suffix })
    }

    /** 解析标签或已安装的版本名；形态不合规返回 null（调用方据此跳过或判定「没有更新」）。 */
    fun parse(value: String): Version? {
        val trimmed = value.trim()
        val match = pattern.matchEntire(trimmed) ?: return null
        val text = trimmed.removePrefix("v")
        // 版本号最终要过前端 `^[A-Za-z0-9._-]{1,96}$`：位数离谱时宁可当成没有更新。
        if (text.length > GitHubReleases.MAX_IDENTIFIER_CHARS) return null
        return Version(
            text = text,
            major = match.groupValues[1].toLongOrNull() ?: return null,
            minor = match.groupValues[2].toLongOrNull() ?: return null,
            patch = match.groupValues[3].toLongOrNull() ?: return null,
            suffix = match.groupValues[4].toLongOrNull() ?: 0L,
        )
    }
}

/**
 * 应用自身（APK）的更新目录。
 *
 * 与运行时镜像分开：运行时更新换的是 rootfs，这里换的是 APK，版本号体系也不同。
 * 只认本仓库发布里的 `app-release.apk`，且只信带 `sha256:` 摘要的那一个。
 *
 * 下载来的包**只能**交给系统安装器去装（Android 不允许应用静默安装），这里只负责
 * 「找到一个确实比本机新的包，并说清它多大、摘要是什么、更新说明写了什么」。
 */
internal class AppReleaseCatalog(
    /** 取发布目录 JSON 的接缝：单测喂固定 JSON，不联网。 */
    private val catalogBytes: () -> ByteArray = { RuntimeHttp().catalogBytes(GitHubReleases.URL) },
) {
    /**
     * 一个可安装的更新包；[notes] 已按前端上限截断。
     *
     * [url] 只给下载用：它不进桥载荷（界面拿到的只有版本号、体积、摘要与说明），也不进审计。
     */
    data class Release(val version: String, val url: String, val bytes: Long, val sha256: String, val notes: String)

    /**
     * 本机已安装版本 + 远端可用更新。
     *
     * 没有可用更新时**不出现** [UPDATE_KEY] 键：前端按「键缺失＝没有更新」判读，
     * 回一个 `available: null` 会被当成半截载荷拒绝。
     */
    fun state(installed: InstalledAppInfo, installAllowed: Boolean): JSONObject {
        val available = availableRelease(installed)
        val state = JSONObject()
            .put("installedVersion", installed.versionName)
            .put("installedVersionCode", installed.versionCode)
            .put("installAllowed", installAllowed)
        if (available != null) {
            state.put(
                UPDATE_KEY,
                JSONObject()
                    .put("version", available.version)
                    .put("bytes", available.bytes)
                    .put("sha256", available.sha256)
                    .put("notes", available.notes),
            )
        }
        return state
    }

    /** 严格大于已安装版本的最新发布；没有可用更新时返回 null。 */
    fun availableRelease(installed: InstalledAppInfo): Release? {
        // 读不出已安装版本就无法证明「严格更新」：宁可回「没有更新」，
        // 也不要怂恿用户去装一个可能比本机更旧的包。
        val installedVersion = AppUpdateVersion.parse(installed.versionName) ?: return null
        var best: Release? = null
        var bestVersion: AppUpdateVersion.Version? = null
        for (release in GitHubReleases.parse(catalogBytes())) {
            val version = AppUpdateVersion.parse(release.tag) ?: continue
            if (version <= installedVersion) continue
            if (bestVersion != null && version <= bestVersion) continue
            val asset = GitHubReleases.asset(release, APK_ASSET) ?: continue
            // 下载层只接受 1..MAX_COMPRESSED_BYTES：超出范围的包不拿出来当「可用更新」，
            // 否则界面会给出一个点了必然失败的按钮。
            if (asset.bytes !in 1..RuntimeLimits.MAX_COMPRESSED_BYTES) continue
            bestVersion = version
            best = Release(
                version = version.text,
                url = asset.url,
                bytes = asset.bytes,
                sha256 = asset.sha256,
                notes = notes(release.notes),
            )
        }
        return best
    }

    /** 更新说明：去掉首尾空白并截断到 [MAX_NOTES_CHARS]（前端超限直接报错，不会替我们截断）。 */
    fun notes(body: String): String = body.trim().take(MAX_NOTES_CHARS)

    companion object {
        /** 更新包资产名。 */
        const val APK_ASSET = "app-release.apk"

        /** 更新说明上限，与前端 `MAX_APP_UPDATE_NOTES_LENGTH` 同源，改一处要改两处。 */
        const val MAX_NOTES_CHARS = 4000

        /** 状态载荷里「可用更新」的键；**没有更新时这个键不出现**。 */
        const val UPDATE_KEY = "available"

        /** 读不到 versionName 时的占位：形态合法，且会让比较侧判定「没有可用更新」。 */
        const val UNKNOWN_VERSION = "unknown"

        /**
         * 读取本机已安装版本。
         *
         * 用 `PackageManager` 而不是 `BuildConfig`：后者是构建期常量，而这里要回答的是
         * 「这台设备上装着的那个包是哪个版本」——覆盖安装、多渠道分发时两者可能不一致，
         * 而这个值决定要不要提示更新。
         */
        fun installed(context: Context): InstalledAppInfo {
            val info = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (error: PackageManager.NameNotFoundException) {
                throw RuntimeFailure("APP_UPDATE_STATE_FAILED", "读不到本机应用版本", error)
            }
            // versionName 可能缺失或被改坏：回占位值，让界面显示一个诚实的「未知」而不是崩掉。
            val name = info.versionName?.takeIf { GitHubReleases.isIdentifier(it) } ?: UNKNOWN_VERSION
            return InstalledAppInfo(versionName = name, versionCode = PackageInfoCompat.getLongVersionCode(info))
        }

        /** 是否已允许安装未知应用（Android 8.0 起是每应用开关，minSdk 26 可直接问）。 */
        fun installAllowed(context: Context): Boolean = try {
            context.packageManager.canRequestPackageInstalls()
        } catch (_: Throwable) {
            // 个别 ROM 上 PackageManager 会抛 SecurityException/UnsupportedOperationException：
            // 一律按「未授权」处理——引导用户去系统设置页，也不要承诺一次必然被拦下的安装。
            false
        }
    }
}
