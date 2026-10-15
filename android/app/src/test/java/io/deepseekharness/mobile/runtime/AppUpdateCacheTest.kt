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
        val first = initial.state(installed, installAllowed = false, AppUpdateChannel.STABLE)
        assertFalse(first.getBoolean("cached"))
        assertEquals(1000L, first.getLong("checkedAt"))
        assertEquals("stable", first.getString("channel"))
        now += 90 * AppUpdateCache.NO_UPDATE_TTL_MILLIS
        val restored = AppUpdateCache(storage, AppReleaseCatalog { error("must not fetch") }, { now })
        val cached = restored.state(installed, installAllowed = true, AppUpdateChannel.STABLE)
        assertTrue(cached.getBoolean("cached"))
        assertTrue(cached.getBoolean("installAllowed"))
        assertEquals(first.getJSONObject("available").toString(), cached.getJSONObject("available").toString())
        assertFalse(cached.getJSONObject("available").has("url"))
        assertEquals("0.2.1", restored.availableRelease(installed, AppUpdateChannel.STABLE)?.version)
    }

    @Test
    fun negativeCacheExpiresExactlyAtTwentyFourHours() {
        var now = 1000L
        var requests = 0
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { requests++; payload("0.2.0") }, { now })
        assertFalse(cache.state(installed, false, AppUpdateChannel.STABLE).has("available"))
        now += AppUpdateCache.NO_UPDATE_TTL_MILLIS - 1
        assertTrue(cache.state(installed, false, AppUpdateChannel.STABLE).getBoolean("cached"))
        assertEquals(1, requests)
        now++
        assertFalse(cache.state(installed, false, AppUpdateChannel.STABLE).getBoolean("cached"))
        assertEquals(2, requests)
    }

    @Test
    fun refreshReplacesAndCanWithdrawAnAvailableRelease() {
        var version = "0.2.1"
        var requests = 0
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { requests++; payload(version) }, { 1000 })
        cache.state(installed, false, AppUpdateChannel.STABLE)
        version = "0.3.0"
        assertEquals("0.2.1", cache.state(installed, false, AppUpdateChannel.STABLE).getJSONObject("available").getString("version"))
        assertEquals("0.3.0", cache.state(installed, false, AppUpdateChannel.STABLE, true).getJSONObject("available").getString("version"))
        assertEquals("0.3.0", cache.availableRelease(installed, AppUpdateChannel.STABLE)?.version)
        version = "0.2.0"
        assertFalse(cache.state(installed, false, AppUpdateChannel.STABLE, true).has("available"))
        assertFalse(cache.state(installed, false, AppUpdateChannel.STABLE).has("available"))
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
        cache.state(installed, false, AppUpdateChannel.STABLE)
        val saved = storage.text
        offline = true
        assertThrows(RuntimeFailure::class.java) { cache.state(installed, false, AppUpdateChannel.STABLE, true) }
        assertEquals(saved, storage.text)
        assertTrue(cache.state(installed, true, AppUpdateChannel.STABLE).getBoolean("cached"))
    }

    @Test
    fun installedVersionOrCodeChangesInvalidateTheCache() {
        var requests = 0
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { requests++; payload() }, { 1000 })
        cache.state(installed, false, AppUpdateChannel.STABLE)
        val upgraded = cache.state(InstalledAppInfo("0.2.1", 23), true, AppUpdateChannel.STABLE)
        assertFalse(upgraded.has("available"))
        assertEquals("0.2.1", upgraded.getString("installedVersion"))
        assertEquals(23L, upgraded.getLong("installedVersionCode"))
        cache.state(InstalledAppInfo("0.2.1", 24), false, AppUpdateChannel.STABLE)
        assertEquals(3, requests)
    }

    @Test
    fun sameVersionStableReleaseRemainsAnUpdateForPreviewInstalls() {
        val preview = InstalledAppInfo("0.2.1-mobile-5", 23)
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { payload() }, { 1000 })
        cache.state(preview, false, AppUpdateChannel.STABLE)
        assertEquals("0.2.1", cache.availableRelease(preview, AppUpdateChannel.STABLE)?.version)
        assertFalse(cache.state(InstalledAppInfo("0.2.1", 24), false, AppUpdateChannel.STABLE).has("available"))
    }

    @Test
    fun channelSwitchInvalidatesPositiveAndNegativeSnapshots() {
        // 正向缓存：stable 查到的 available，切到 beta 必须重查，不能接着用。
        var requests = 0
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { requests++; payload() }, { 1000 })
        cache.state(installed, false, AppUpdateChannel.STABLE)
        assertFalse(cache.state(installed, false, AppUpdateChannel.BETA).getBoolean("cached"))
        assertEquals(2, requests)
        // 负向缓存同理：beta 下的「没有更新」对 stable 不作数。
        var negativeRequests = 0
        val negative = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { negativeRequests++; payload("0.2.0") }, { 1000 })
        negative.state(installed, false, AppUpdateChannel.BETA)
        assertFalse(negative.state(installed, false, AppUpdateChannel.STABLE).getBoolean("cached"))
        assertEquals(2, negativeRequests)
    }

    @Test
    fun channelIsPersistedAndSurvivesRecreation() {
        val storage = MemoryStorage()
        AppUpdateCache(storage, AppReleaseCatalog { payload() }, { 1000 })
            .state(installed, false, AppUpdateChannel.BETA)
        // 新实例以同一频道读：命中；换频道读：整条作废重查。
        val beta = AppUpdateCache(storage, AppReleaseCatalog { error("must not fetch") }, { 1000 })
        assertTrue(beta.state(installed, false, AppUpdateChannel.BETA).getBoolean("cached"))
        var requests = 0
        val stable = AppUpdateCache(storage, AppReleaseCatalog { requests++; payload() }, { 1000 })
        assertFalse(stable.state(installed, false, AppUpdateChannel.STABLE).getBoolean("cached"))
        assertEquals(1, requests)
    }

    @Test
    fun legacySchemaOneSnapshotsAreDiscarded() {
        // schema 1 没有 channel 概念：升级后旧缓存一次性作废，按当前频道重查。
        val storage = MemoryStorage()
        var requests = 0
        val cache = AppUpdateCache(storage, AppReleaseCatalog { requests++; payload() }, { 1000 })
        storage.text = JSONObject()
            .put("schema", 1)
            .put("installedVersion", "0.2.0")
            .put("installedVersionCode", 22L)
            .put("checkedAt", 1000L)
            .put("available", JSONObject()
                .put("version", "0.2.1")
                .put("url", "${GitHubReleases.DOWNLOAD_URL_PREFIX}v0.2.1/app-release.apk")
                .put("bytes", 4096L)
                .put("sha256", "b".repeat(64))
                .put("notes", "更新说明"))
            .toString()
        val state = cache.state(installed, false, AppUpdateChannel.STABLE)
        assertFalse(state.getBoolean("cached"))
        assertEquals(1, requests)
        assertEquals("0.2.1", state.getJSONObject("available").getString("version"))
    }

    @Test
    fun corruptAndOversizedStorageFallsBackToNetworkWithoutTrustingItsFields() {
        val storage = MemoryStorage()
        var requests = 0
        val cache = AppUpdateCache(storage, AppReleaseCatalog { requests++; payload() }, { 1000 })
        cache.state(installed, false, AppUpdateChannel.STABLE)
        val valid = storage.text!!
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("schema", 99) },
            { it.put("channel", "nightly") },
            { it.remove("channel") },
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
            assertFalse(cache.state(installed, false, AppUpdateChannel.STABLE).getBoolean("cached"))
            assertEquals(valid, storage.text)
        }
        assertEquals(1 + badEntries.size, requests)
    }

    @Test
    fun invalidCacheAndOfflineDoesNotPretendTheCheckSucceeded() {
        val storage = MemoryStorage().apply { text = "not-json" }
        val cache = AppUpdateCache(storage, AppReleaseCatalog { throw RuntimeFailure("DOWNLOAD_HTTP_ERROR", "offline") })
        assertThrows(RuntimeFailure::class.java) { cache.state(installed, true, AppUpdateChannel.STABLE) }
    }

    @Test
    fun futureTimestampDoesNotKeepANegativeResultForever() {
        var now = 2000L
        var requests = 0
        val cache = AppUpdateCache(MemoryStorage(), AppReleaseCatalog { requests++; payload("0.2.0") }, { now })
        cache.state(installed, false, AppUpdateChannel.STABLE)
        now = 1000L
        assertFalse(cache.state(installed, false, AppUpdateChannel.STABLE).getBoolean("cached"))
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
            try { assertEquals(allowed, cache.state(installed, allowed, AppUpdateChannel.STABLE, true).getBoolean("installAllowed")) }
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

    @Test
    fun concurrentChecksOnDifferentChannelsDoNotShareOneRequest() {
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
        fun worker(channel: AppUpdateChannel) = Thread {
            try { assertEquals(channel.id, cache.state(installed, false, channel, true).getString("channel")) }
            catch (failure: Throwable) { error.set(failure) }
        }
        val first = worker(AppUpdateChannel.STABLE)
        val second = worker(AppUpdateChannel.BETA)
        try {
            first.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            second.start()
            // 第二个频道不复用第一个频道的在途请求：它自己也发起了一次抓取。
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (requests.get() < 2 && System.nanoTime() < deadline) Thread.yield()
        } finally {
            release.countDown()
            first.join(3000)
            second.join(3000)
        }
        error.get()?.let { throw it }
        assertEquals(2, requests.get())
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
    }
}
