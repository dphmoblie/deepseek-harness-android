package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * GitHub 发布目录的共用解析：运行时镜像与应用自身 APK 走同一套有界校验。
 *
 * 这里只解析、不联网，A/B 两侧的判定因此都能在纯 JVM 单测里用固定 JSON 钉住，
 * 不需要设备，也不会碰真实网络。
 */
internal object GitHubReleases {
    /**
     * 版本目录地址。
     *
     * 必须与 [RuntimeHttp.catalogBytes] 的白名单**逐字一致**：目录来源只有这一处，
     * 前端不能左右它，实测地址也不进审计或诊断日志。
     */
    const val URL = "https://api.github.com/repos/dphmoblie/deepseek-harness-android/releases?per_page=30"

    /** 一次目录请求最多解析的发布数：目录只会越来越长，处理量必须有界。 */
    const val MAX_RELEASES = 20

    /** 资产下载地址必须落在本仓库的 releases/download 下；GitHub 自己给的直链不会更长。 */
    const val DOWNLOAD_URL_PREFIX = "https://github.com/dphmoblie/deepseek-harness-android/releases/download/"

    /** 资产地址长度上限，与前端 `MAX_RELEASE_MANIFEST_URL_LENGTH` 同源，改一处要改两处。 */
    const val MAX_URL_CHARS = 1024

    /** 标识形态上限，与前端 `MAX_IDENTIFIER_LENGTH` 同源。 */
    const val MAX_IDENTIFIER_CHARS = 96

    /** 已通过形态校验的发布资产；[sha256] 是不带 `sha256:` 前缀的 64 位小写十六进制。 */
    data class Asset(val name: String, val url: String, val sha256: String, val bytes: Long)

    /** 一个发布；[notes] 是 release body 原文，截断由调用方按自己的上限处理。 */
    data class Release(val tag: String, val notes: String, val assets: List<Asset>)

    private val sha256Pattern = Regex("^[a-f0-9]{64}$")
    private val identifierPattern = Regex("^[A-Za-z0-9._-]{1,$MAX_IDENTIFIER_CHARS}$")

    /** 前端对版本号/标识的接受范围；不满足就不往桥上传，宁可少一条也不回一份界面用不了的载荷。 */
    fun isIdentifier(value: String): Boolean = identifierPattern.matches(value)

    /**
     * 解析发布目录。
     *
     * 目录本身取不到或不是 JSON 数组时抛 [RuntimeFailure]，**绝不返回空列表**：
     * 空列表会被界面显示成「没有可用版本」，等于把一次失败编造成一个成功的事实。
     */
    fun parse(bytes: ByteArray): List<Release> {
        val array = try {
            JSONArray(bytes.toString(Charsets.UTF_8))
        } catch (error: JSONException) {
            throw RuntimeFailure("RELEASE_CATALOG_FAILED", "版本目录格式无效", error)
        }
        val releases = ArrayList<Release>(minOf(array.length(), MAX_RELEASES))
        for (index in 0 until minOf(array.length(), MAX_RELEASES)) {
            val json = array.optJSONObject(index) ?: continue
            val tag = json.optString("tag_name", "").trim()
            if (tag.isEmpty()) continue
            releases += Release(tag = tag, notes = json.optString("body", ""), assets = assets(json))
        }
        return releases
    }

    /**
     * 找出指定名称、且已通过形态校验的资产；没有就是 null，调用方据此跳过这个发布。
     *
     * 校验不合格的资产在解析阶段就被丢弃，所以「拿到的资产」＝「可以下载并校对的资产」。
     */
    fun asset(release: Release, name: String): Asset? = release.assets.firstOrNull { it.name == name }

    /** tag 去掉前导 `v` 后的版本号；不是合法标识时返回 null。 */
    fun versionFromTag(tag: String): String? = tag.trim().removePrefix("v").takeIf { isIdentifier(it) }

    /**
     * 收集一个发布里可用的资产。
     *
     * 单条资产不合格只丢它自己：同一个 release 里其它资产仍然可用。
     * `digest` 是 GitHub 现代接口才有的字段，缺失即「没有可校对的摘要」，直接不采信。
     */
    private fun assets(json: JSONObject): List<Asset> {
        val array = json.optJSONArray("assets") ?: return emptyList()
        val assets = ArrayList<Asset>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name", "").trim()
            val url = item.optString("browser_download_url", "")
            val sha256 = item.optString("digest", "").removePrefix("sha256:")
            if (name.isEmpty() || !sha256Pattern.matches(sha256)) continue
            if (url.length > MAX_URL_CHARS || !url.startsWith(DOWNLOAD_URL_PREFIX)) continue
            try {
                RuntimeValidation.requireHttpsUri(url, rejectPrivateHost = true)
            } catch (_: RuntimeFailure) {
                continue
            }
            assets += Asset(name = name, url = url, sha256 = sha256, bytes = item.optLong("size", 0L))
        }
        return assets
    }
}
