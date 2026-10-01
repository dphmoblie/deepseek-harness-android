package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 发布目录解析的纯 JVM 断言：喂固定 JSON，不联网、不碰 android.*。 */
class GitHubReleasesTest {
    private val digest = "a".repeat(64)
    private val apkUrl = "${GitHubReleases.DOWNLOAD_URL_PREFIX}v0.2.1-mobile-2/app-release.apk"

    private fun asset(
        name: String,
        url: String = apkUrl,
        digest: String? = this.digest,
        size: Long? = 1024L,
    ): JSONObject = JSONObject().put("name", name).put("browser_download_url", url).apply {
        // digest 是 GitHub 现代接口才带的字段：缺失就构造出「没有 digest 键」的资产。
        digest?.let { put("digest", "sha256:$it") }
        size?.let { put("size", it) }
    }

    private fun release(tag: String, body: String = "", vararg assets: JSONObject): JSONObject {
        val array = JSONArray()
        assets.forEach { array.put(it) }
        return JSONObject().put("tag_name", tag).put("body", body).put("assets", array)
    }

    private fun bytes(vararg releases: JSONObject): ByteArray {
        val array = JSONArray()
        releases.forEach { array.put(it) }
        return array.toString().toByteArray()
    }

    @Test
    fun parsesReleaseFieldsAndNormalizesTheDigest() {
        val parsed = GitHubReleases.parse(bytes(release("v0.2.1-mobile-2", "更新说明", asset("app-release.apk"))))
        assertEquals(1, parsed.size)
        assertEquals("v0.2.1-mobile-2", parsed[0].tag)
        assertEquals("更新说明", parsed[0].notes)
        val asset = GitHubReleases.asset(parsed[0], "app-release.apk")
        assertNotNull(asset)
        assertEquals(apkUrl, asset!!.url)
        assertEquals(digest, asset.sha256)
        assertEquals(1024L, asset.bytes)
    }

    @Test
    fun rejectsACatalogThatIsNotAJsonArray() {
        val failure = assertThrows(RuntimeFailure::class.java) { GitHubReleases.parse("{}".toByteArray()) }
        assertEquals("RELEASE_CATALOG_FAILED", failure.code)
        assertEquals("版本目录格式无效", failure.message)
    }

    @Test
    fun boundsParsingToTheFirstTwentyReleases() {
        val releases = (0 until 25).map { release("v0.2.$it") }.toTypedArray()
        assertEquals(GitHubReleases.MAX_RELEASES, GitHubReleases.parse(bytes(*releases)).size)
    }

    @Test
    fun skipsEntriesWithoutATag() {
        assertEquals(0, GitHubReleases.parse(bytes(JSONObject().put("body", "无标签"))).size)
    }

    @Test
    fun dropsAssetsWhoseDigestIsNotSixtyFourLowercaseHexChars() {
        for (bad in listOf("c".repeat(63), "C".repeat(64), "c".repeat(65), "z".repeat(64), "")) {
            val parsed = GitHubReleases.parse(bytes(release("v0.2.1", "", asset("app-release.apk", digest = bad))))
            assertNull("digest=$bad 不该被采信", GitHubReleases.asset(parsed[0], "app-release.apk"))
        }
    }

    @Test
    fun dropsAssetsWithoutADigestFieldAtAll() {
        val parsed = GitHubReleases.parse(bytes(release("v0.2.1", "", asset("app-release.apk", digest = null))))
        assertNull(GitHubReleases.asset(parsed[0], "app-release.apk"))
    }

    @Test
    fun dropsAssetsWhoseDownloadUrlIsOutsideThisRepository() {
        for (bad in listOf(
            "https://example.com/dphmoblie/deepseek-harness-android/releases/download/v0.2.1/app-release.apk",
            "http://github.com/dphmoblie/deepseek-harness-android/releases/download/v0.2.1/app-release.apk",
            "https://github.com/other/repo/releases/download/v0.2.1/app-release.apk",
            "https://github.com/dphmoblie/deepseek-harness-android/releases/latest",
        )) {
            val parsed = GitHubReleases.parse(bytes(release("v0.2.1", "", asset("app-release.apk", url = bad))))
            assertNull("url=$bad 不该被采信", GitHubReleases.asset(parsed[0], "app-release.apk"))
        }
    }

    @Test
    fun dropsAssetsWhoseDownloadUrlExceedsTheFrontendLimit() {
        // 前缀合法但超长：前端对清单地址有 1024 字符上限，超了就得在这里挡住。
        val long = GitHubReleases.DOWNLOAD_URL_PREFIX + "a".repeat(GitHubReleases.MAX_URL_CHARS)
        assertTrue(long.length > GitHubReleases.MAX_URL_CHARS)
        val parsed = GitHubReleases.parse(bytes(release("v0.2.1", "", asset("app-release.apk", url = long))))
        assertNull(GitHubReleases.asset(parsed[0], "app-release.apk"))
    }

    @Test
    fun versionFromTagStripsOneLeadingVAndRequiresAnIdentifier() {
        assertEquals("0.2.1-mobile-2", GitHubReleases.versionFromTag("v0.2.1-mobile-2"))
        assertEquals("0.2.1-mobile-2", GitHubReleases.versionFromTag("0.2.1-mobile-2"))
        assertEquals("runtime-2026.08.18", GitHubReleases.versionFromTag("vruntime-2026.08.18"))
        assertNull(GitHubReleases.versionFromTag("v带空格 0.2.1"))
        // 上限按「去掉前导 v 之后」的长度算：96 位合法，97 位越界。
        assertEquals(
            "a".repeat(GitHubReleases.MAX_IDENTIFIER_CHARS),
            GitHubReleases.versionFromTag("v" + "a".repeat(GitHubReleases.MAX_IDENTIFIER_CHARS)),
        )
        assertNull(GitHubReleases.versionFromTag("v" + "a".repeat(GitHubReleases.MAX_IDENTIFIER_CHARS + 1)))
    }
}
