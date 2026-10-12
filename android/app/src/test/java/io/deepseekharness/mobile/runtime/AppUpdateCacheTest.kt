package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AppUpdateCacheTest {
    private val installed = InstalledAppInfo("0.2.0", 22L)
    private class MemoryStorage : AppUpdateCache.Storage {
        var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }

    private fun payload(version: String = "0.2.1", notes: String = "更新说明"): ByteArray = JSONArray().put(
        JSONObject().put("tag_name", "v$version").put("body", notes).put("assets", JSONArray().put(
            JSONObject().put("name", AppReleaseCatalog.APK_ASSET)
                .put("browser_download_url", "${GitHubReleases.DOWNLOAD_URL_PREFIX}v$version/app-release.apk")
                .put("size", 4096L).put("digest", "sha256:${"b".repeat(64)}"),
        )),
    ).toString().toByteArray()

    @Test
    fun persistsAvailableReleaseAcrossInstancesAndOfflineWithoutExpiringIt() {
        val storage = MemoryStorage()
        var now = 1000L
        val initial = AppUpdateCache(storage, AppReleaseCatalog { payload() }, { now })
        val first = initial.state(installed, installAllowed = false)
        assertFalse(first.getBoolean("cached"))
        assertEquals(1000L, first.getLong("checkedAt"))
        now += 90 * AppUpdateCache.NO_UPDATE_TTL_MILLIS
        val restored = AppUpdateCache(storage, AppReleaseCatalog { error("must not fetch") }, { now })
        val cached = restored.state(installed, installAllowed = true)
        assertTrue(cached.getBoolean("cached"))
        assertTrue(cached.getBoolean("installAllowed"))
        assertEquals(first.getJSONObject("available").toString(), cached.getJSONObject("available").toString())
        assertFalse(cached.getJSONObject("available").has("url"))
        assertEquals("0.2.1", restored.availableRelease(installed)?.version)
    }

    @Test
    fun negativeCacheExpiresExactlyAtTwentyFourHours() {
        var now = 1000L
        var requests = 0
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { requests++; payload("0.2.0") }, { now })
        assertFalse(cache.state(installed, false).has("available"))
        now += AppUpdateCache.NO_UPDATE_TTL_MILLIS - 1
        assertTrue(cache.state(installed, false).getBoolean("cached"))
        assertEquals(1, requests)
        now++
        assertFalse(cache.state(installed, false).getBoolean("cached"))
        assertEquals(2, requests)
    }

    @Test
    fun refreshReplacesAndCanWithdrawAnAvailableRelease() {
        var version = "0.2.1"
        var requests = 0
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { requests++; payload(version) }, { 1000 })
        cache.state(installed, false)
        version = "0.3.0"
        assertEquals("0.2.1", cache.state(installed, false).getJSONObject("available").getString("version"))
        assertEquals("0.3.0", cache.state(installed, false, true).getJSONObject("available").getString("version"))
        assertEquals("0.3.0", cache.availableRelease(installed)?.version)
        version = "0.2.0"
        assertFalse(cache.state(installed, false, true).has("available"))
        assertFalse(cache.state(installed, false).has("available"))
        assertEquals(3, requests)
    }

    @Test
    fun failedForcedRefreshThrowsAndPreservesTheOldCache() {
        val storage = MemoryStorage()
        var offline = false
        val cache = AppUpdateCache(storage, AppReleaseCatalog {
            if (offline) throw RuntimeFailure("DOWNLOAD_HTTP_ERROR", "offline")
            payload()
        }, { 1000 })
        cache.state(installed, false)
        val saved = storage.text
        offline = true
        assertThrows(RuntimeFailure::class.java) { cache.state(installed, false, true) }
        assertEquals(saved, storage.text)
        assertTrue(cache.state(installed, true).getBoolean("cached"))
    }

    @Test
    fun installedVersionOrCodeChangesInvalidateTheCache() {
        var requests = 0
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { requests++; payload() }, { 1000 })
        cache.state(installed, false)
        val upgraded = cache.state(InstalledAppInfo("0.2.1", 23), true)
        assertFalse(upgraded.has("available"))
        assertEquals("0.2.1", upgraded.getString("installedVersion"))
        assertEquals(23L, upgraded.getLong("installedVersionCode"))
        cache.state(InstalledAppInfo("0.2.1", 24), false)
        assertEquals(3, requests)
    }

    @Test
    fun sameVersionStableReleaseRemainsAnUpdateForPreviewInstalls() {
        val preview = InstalledAppInfo("0.2.1-mobile-5", 23)
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { payload() }, { 1000 })
        cache.state(preview, false)
        assertEquals("0.2.1", cache.availableRelease(preview)?.version)
        assertFalse(cache.state(InstalledAppInfo("0.2.1", 24), false).has("available"))
    }

    @Test
    fun corruptAndOversizedStorageFallsBackToNetworkWithoutTrustingItsFields() {
        val storage = MemoryStorage()
        var requests = 0
        val cache = AppUpdateCache(storage, AppReleaseCatalog { requests++; payload() }, { 1000 })
        cache.state(installed, false)
        val valid = storage.text!!
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("schema", 99) },
            { it.put("checkedAt", "1000") },
            { it.put("installedVersionCode", 22.5) },
            { it.put("available", JSONObject.NULL) },
            { it.getJSONObject("available").put("version", "../outside") },
            { it.getJSONObject("available").put("version", "0.1.0") },
            { it.getJSONObject("available").put("url", "https://example.com/app-release.apk") },
            { it.getJSONObject("available").put("url", GitHubReleases.DOWNLOAD_URL_PREFIX + "?bad=\n") },
            { it.getJSONObject("available").put("sha256", "not-a-digest") },
            { it.getJSONObject("available").put("bytes", 0) },
            { it.getJSONObject("available").put("notes", "x".repeat(AppReleaseCatalog.MAX_NOTES_CHARS + 1)) },
        )
        val badEntries = listOf("{broken", "x".repeat(AppUpdateCache.MAX_CACHE_CHARS + 1)) +
            mutations.map { mutate -> JSONObject(valid).also(mutate).toString() }
        badEntries.forEach { corrupt ->
            storage.text = corrupt
            assertFalse(cache.state(installed, false).getBoolean("cached"))
            assertEquals(valid, storage.text)
        }
        assertEquals(1 + badEntries.size, requests)
    }

    @Test
    fun invalidCacheAndOfflineDoesNotPretendTheCheckSucceeded() {
        val storage = MemoryStorage().apply { text = "not-json" }
        val cache = AppUpdateCache(storage, AppReleaseCatalog { throw RuntimeFailure("DOWNLOAD_HTTP_ERROR", "offline") })
        assertThrows(RuntimeFailure::class.java) { cache.state(installed, true) }
    }

    @Test
    fun futureTimestampDoesNotKeepANegativeResultForever() {
        var now = 2000L
        var requests = 0
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { requests++; payload("0.2.0") }, { now })
        cache.state(installed, false)
        now = 1000L
        assertFalse(cache.state(installed, false).getBoolean("cached"))
        assertEquals(2, requests)
    }

    @Test
    fun concurrentForcedChecksShareOneRequestAndPreservePerCallerPermission() {
        val requests = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog {
            requests.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            payload()
        }, { 1000 })
        fun worker(allowed: Boolean) = Thread {
            try { assertEquals(allowed, cache.state(installed, allowed, true).getBoolean("installAllowed")) }
            catch (failure: Throwable) { error.set(failure) }
        }
        val first = worker(false)
        val second = worker(true)
        try {
            first.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            second.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (second.state != Thread.State.WAITING && second.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, second.state)
        } finally {
            release.countDown()
            first.join(3000)
            second.join(3000)
        }
        error.get()?.let { throw it }
        assertEquals(1, requests.get())
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
    }
}
