package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 应用自更新的纯 JVM 断言：注入固定 JSON，不联网、不碰 android.*。 */
class AppReleaseCatalogTest {
    private val installed = InstalledAppInfo(versionName = "0.2.0", versionCode = 22L)
    private val digest = "b".repeat(64)
    private val notes = "本次更新修复了若干问题。"

    private fun apkUrl(tag: String) = "${GitHubReleases.DOWNLOAD_URL_PREFIX}$tag/app-release.apk"

    private fun release(
        tag: String,
        body: String = notes,
        size: Long? = 4096L,
        digest: String? = this.digest,
        url: String = apkUrl(tag),
        assetName: String = AppReleaseCatalog.APK_ASSET,
        extraAsset: JSONObject? = null,
    ): JSONObject {
        val assets = JSONArray()
        val apk = JSONObject()
            .put("name", assetName)
            .put("browser_download_url", url)
        digest?.let { apk.put("digest", "sha256:$it") }
        size?.let { apk.put("size", it) }
        assets.put(apk)
        extraAsset?.let { assets.put(it) }
        return JSONObject().put("tag_name", tag).put("body", body).put("assets", assets)
    }

    private fun catalog(vararg releases: JSONObject): AppReleaseCatalog {
        val array = JSONArray()
        releases.forEach { array.put(it) }
        val payload = array.toString().toByteArray()
        return AppReleaseCatalog { payload }
    }

    // ---- 版本号比较 ----

    @Test
    fun ordersBuildSuffixAfterTheBareVersionOfTheSameRelease() {
        val bare = AppUpdateVersion.parse("0.2.0")!!
        val build = AppUpdateVersion.parse("0.2.0-mobile-308")!!
        val next = AppUpdateVersion.parse("0.2.1-mobile-2")!!
        assertTrue(bare < build)
        assertTrue(build < next)
        // 前导 v 只影响原文，不影响排序；缺后缀就是序号 0。
        assertEquals(0, AppUpdateVersion.parse("v0.2.0-mobile-308")!!.compareTo(build))
        assertEquals(0, AppUpdateVersion.parse("0.2.0-mobile-0")!!.compareTo(bare))
        assertTrue(AppUpdateVersion.parse("0.2.1")!! > AppUpdateVersion.parse("0.2.0-mobile-999")!!)
    }

    @Test
    fun rejectsTagsOutsideTheAcceptedShape() {
        for (bad in listOf("", "0.2", "0.2.0.1", "0.2.0-mobile", "0.2.0-mobile-x", "0.2.0-rc.1", "nightly", "v")) {
            assertNull("tag=$bad 不该被当成应用版本", AppUpdateVersion.parse(bad))
        }
        assertEquals("0.2.0", AppUpdateVersion.parse(" v0.2.0 ")?.text)
    }

    // ---- 只有严格更新才出现 available ----

    @Test
    fun offersOnlyStrictlyNewerReleases() {
        assertNull(catalog(release("v0.2.0")).availableRelease(installed))
        assertNull(catalog(release("v0.1.9-mobile-9")).availableRelease(installed))
        assertEquals("0.2.0-mobile-308", catalog(release("v0.2.0-mobile-308")).availableRelease(installed)?.version)
        // 多个可选时取最大的那个，而不是列表里最靠前的。
        val picked = catalog(release("v0.2.0-mobile-308"), release("v0.2.1-mobile-2"), release("v0.3.0"))
        assertEquals("0.3.0", picked.availableRelease(installed)?.version)
    }

    @Test
    fun refusesToOfferAnUpdateWhenTheInstalledVersionCannotBeParsed() {
        // 读不出本机版本就证明不了「严格更新」，宁可什么都不提示。
        assertNull(catalog(release("v0.2.1-mobile-2")).availableRelease(InstalledAppInfo(AppReleaseCatalog.UNKNOWN_VERSION, 22L)))
        assertNull(catalog(release("v0.2.1-mobile-2")).availableRelease(InstalledAppInfo("0.2.0-custom", 22L)))
    }

    @Test
    fun keepsTheAvailableKeyOutOfThePayloadWhenThereIsNoUpdate() {
        val state = catalog(release("v0.2.0")).state(installed, installAllowed = true)
        assertFalse(state.has(AppReleaseCatalog.UPDATE_KEY))
        assertEquals("0.2.0", state.getString("installedVersion"))
        assertEquals(22L, state.getLong("installedVersionCode"))
        assertTrue(state.getBoolean("installAllowed"))
    }

    @Test
    fun reportsTheAvailableUpdateWithBytesDigestAndNotes() {
        val state = catalog(release("v0.2.1-mobile-2")).state(installed, installAllowed = false)
        val available = state.getJSONObject(AppReleaseCatalog.UPDATE_KEY)
        assertEquals("0.2.1-mobile-2", available.getString("version"))
        assertEquals(4096L, available.getLong("bytes"))
        assertEquals(digest, available.getString("sha256"))
        assertEquals(notes, available.getString("notes"))
        assertFalse(state.getBoolean("installAllowed"))
        // 下载地址只留在原生侧，不进桥载荷。
        assertFalse(available.has("url"))
    }

    // ---- 更新说明 ----

    @Test
    fun truncatesNotesToTheFrontendLimitAndTrimsTheEdges() {
        assertEquals(4000, AppReleaseCatalog.MAX_NOTES_CHARS)
        val long = "更".repeat(AppReleaseCatalog.MAX_NOTES_CHARS + 120)
        val available = catalog(release("v0.2.1", body = "\n  $long  \n")).availableRelease(installed)!!
        assertEquals(AppReleaseCatalog.MAX_NOTES_CHARS, available.notes.length)
        assertTrue(available.notes.startsWith("更"))
        assertEquals("没有正文", catalog(release("v0.2.1", body = "  没有正文  ")).availableRelease(installed)!!.notes)
        // 恰好等于上限时不能少一个字。
        val exact = "更".repeat(AppReleaseCatalog.MAX_NOTES_CHARS)
        assertEquals(AppReleaseCatalog.MAX_NOTES_CHARS, catalog(release("v0.2.1", body = exact)).availableRelease(installed)!!.notes.length)
    }

    // ---- 资产校验 ----

    @Test
    fun requiresAWellFormedSha256DigestOnTheApkAsset() {
        for (bad in listOf("c".repeat(63), "C".repeat(64), "c".repeat(65), "z".repeat(64), "", null)) {
            assertNull("digest=$bad 的包不该被当成可用更新", catalog(release("v0.2.1", digest = bad)).availableRelease(installed))
        }
    }

    @Test
    fun requiresAnApkAssetWithAUsableSize() {
        val tooBig = RuntimeLimits.MAX_COMPRESSED_BYTES + 1
        for (bad in listOf<Long?>(0L, -1L, tooBig, null)) {
            assertNull("size=$bad 的包不该被当成可用更新", catalog(release("v0.2.1", size = bad)).availableRelease(installed))
        }
        assertEquals(1L, catalog(release("v0.2.1", size = 1L)).availableRelease(installed)?.bytes)
        assertNull(catalog(release("v0.2.1", assetName = "app-debug.apk")).availableRelease(installed))
    }

    @Test
    fun dropsApkAssetsWhoseDownloadUrlIsNotThisRepository() {
        val foreign = catalog(release("v0.2.1", url = "https://example.com/app-release.apk"))
        assertNull(foreign.availableRelease(installed))
        val plainHttp = catalog(release("v0.2.1", url = "http://github.com/dphmoblie/deepseek-harness-android/releases/download/v0.2.1/app-release.apk"))
        assertNull(plainHttp.availableRelease(installed))
    }

    @Test
    fun ignoresReleasesWhoseTagIsNotAnAppVersion() {
        assertNull(catalog(release("nightly")).availableRelease(installed))
        assertNull(catalog(release("v0.2.1-rc.1")).availableRelease(installed))
    }

    // ---- 落地目录 ----

    @Test
    fun commitTurnsThePartialDownloadIntoTheOnlyRetainedApk() {
        withCacheDir { cacheDir ->
            val partial = AppUpdateStorage.partialFile(cacheDir, "0.2.1-mobile-2")
            partial.writeBytes(byteArrayOf(1, 2, 3))
            val older = AppUpdateStorage.partialFile(cacheDir, "0.2.0-mobile-308")
            older.writeBytes(byteArrayOf(9))

            val apk = AppUpdateStorage.commit(cacheDir, "0.2.1-mobile-2")

            assertEquals("app-release-0.2.1-mobile-2.apk", apk.name)
            assertEquals(3, apk.readBytes().size)
            assertFalse(partial.exists())
            assertFalse(older.exists())
            assertEquals(apk.name, AppUpdateStorage.downloaded(cacheDir)?.name)
        }
    }

    @Test
    fun neverTreatsAHalfDownloadedFileAsAnInstallableUpdate() {
        withCacheDir { cacheDir ->
            AppUpdateStorage.partialFile(cacheDir, "0.2.1").writeBytes(byteArrayOf(1))
            assertNull(AppUpdateStorage.downloaded(cacheDir))
            assertNull(AppUpdateStorage.downloaded(File(cacheDir, "not-created-yet")))
        }
    }

    @Test
    fun refusesToCommitAnUpdateThatWasNeverDownloaded() {
        withCacheDir { cacheDir ->
            val failure = assertThrows(RuntimeFailure::class.java) { AppUpdateStorage.commit(cacheDir, "0.2.1") }
            assertEquals("APP_UPDATE_FILE_MISSING", failure.code)
        }
    }

    @Test
    fun refusesVersionsThatCouldEscapeTheDownloadDirectory() {
        withCacheDir { cacheDir ->
            val failure = assertThrows(RuntimeFailure::class.java) { AppUpdateStorage.partialFile(cacheDir, "../0.2.1") }
            assertEquals("APP_UPDATE_VERSION_INVALID", failure.code)
        }
    }

    // ---- 安装前检查 ----

    @Test
    fun refusesInstallWithoutTheUnknownSourcesPermission() {
        withCacheDir { cacheDir ->
            AppUpdateStorage.partialFile(cacheDir, "0.2.1").writeBytes(byteArrayOf(1))
            val apk = AppUpdateStorage.commit(cacheDir, "0.2.1")
            val failure = assertThrows(RuntimeFailure::class.java) {
                AppUpdateInstallPolicy.requireInstallable(installAllowed = false, apk = apk)
            }
            assertEquals("APP_UPDATE_INSTALL_PERMISSION", failure.code)
            // 授权后同一个文件就能交给系统安装器：被拦下的是权限，不是文件。
            assertEquals(apk, AppUpdateInstallPolicy.requireInstallable(installAllowed = true, apk = apk))
        }
    }

    @Test
    fun refusesInstallWhenTheApkIsMissing() {
        withCacheDir { cacheDir ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                AppUpdateInstallPolicy.requireInstallable(installAllowed = true, apk = null)
            }
            assertEquals("APP_UPDATE_FILE_MISSING", failure.code)
            val gone = File(cacheDir, "app-release-0.2.1.apk")
            assertThrows(RuntimeFailure::class.java) {
                AppUpdateInstallPolicy.requireInstallable(installAllowed = true, apk = gone)
            }
        }
    }

    /** 建一个临时 cacheDir，用完删掉；单测不落任何持久状态。 */
    private fun withCacheDir(block: (File) -> Unit) {
        val cacheDir = Files.createTempDirectory("app-updates").toFile()
        try {
            block(cacheDir)
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
