package io.deepseekharness.mobile.runtime

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Only public release metadata is persisted; installed-package facts and permissions stay live. */
internal class AppUpdateCache(
    private val storage: Storage,
    private val catalog: AppReleaseCatalog = AppReleaseCatalog(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    interface Storage {
        fun read(): String?
        fun write(value: String)
    }

    private data class Snapshot(
        val installed: InstalledAppInfo,
        val checkedAt: Long,
        val available: AppReleaseCatalog.Release?,
    )
    private data class Result(val snapshot: Snapshot, val cached: Boolean)
    private val inFlight = mutableMapOf<InstalledAppInfo, CompletableFuture<Snapshot>>()

    fun state(installed: InstalledAppInfo, installAllowed: Boolean, forceRefresh: Boolean = false): JSONObject {
        val result = lookup(installed, forceRefresh)
        return catalog.state(installed, installAllowed, result.snapshot.available)
            .put("cached", result.cached)
            .put("checkedAt", result.snapshot.checkedAt)
    }

    fun availableRelease(installed: InstalledAppInfo): AppReleaseCatalog.Release? =
        lookup(installed, forceRefresh = false).snapshot.available

    private fun lookup(installed: InstalledAppInfo, forceRefresh: Boolean): Result {
        val pending: CompletableFuture<Snapshot>
        val refresh: Boolean
        synchronized(this) {
            val stored = read()
            if (!forceRefresh && stored != null && usable(stored, installed)) return Result(stored, cached = true)
            val existing = inFlight[installed]
            if (existing == null) {
                refresh = true
                pending = CompletableFuture<Snapshot>().also { inFlight[installed] = it }
            } else {
                refresh = false
                pending = existing
            }
        }
        if (refresh) {
            try {
                val available = catalog.availableRelease(installed)
                val snapshot = Snapshot(installed, now(), available)
                synchronized(this) { storage.write(encode(snapshot)) }
                pending.complete(snapshot)
            } catch (error: Throwable) {
                // A failed forced check must not replace a previously verified release.
                pending.completeExceptionally(error)
            } finally {
                synchronized(this) { inFlight.remove(installed) }
            }
        }
        return try {
            Result(pending.get(), cached = false)
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeFailure("APP_UPDATE_STATE_FAILED", "应用更新检查已中断", error)
        }
    }

    private fun usable(snapshot: Snapshot, installed: InstalledAppInfo): Boolean {
        if (snapshot.installed != installed || snapshot.checkedAt > now()) return false
        return snapshot.available?.let { catalog.isUpdateOf(installed, it) }
            ?: (now() - snapshot.checkedAt < NO_UPDATE_TTL_MILLIS)
    }

    private fun read(): Snapshot? = try {
        val text = storage.read()
        if (text == null || text.length > MAX_CACHE_CHARS) null else decode(JSONObject(text))
    } catch (_: Exception) {
        null
    }

    private fun decode(json: JSONObject): Snapshot? {
        if (json.opt("schema") != 1) return null
        val name = json.opt("installedVersion") as? String ?: return null
        if (!GitHubReleases.isIdentifier(name)) return null
        val code = integer(json.opt("installedVersionCode")) ?: return null
        val checkedAt = integer(json.opt("checkedAt"))?.takeIf { it > 0 } ?: return null
        val installed = InstalledAppInfo(name, code)
        val release = if (json.has("available")) {
            val item = json.optJSONObject("available") ?: return null
            val version = item.opt("version") as? String ?: return null
            if (AppUpdateVersion.parse(version)?.text != version) return null
            val url = item.opt("url") as? String ?: return null
            if (url.length > GitHubReleases.MAX_URL_CHARS || !url.startsWith(GitHubReleases.DOWNLOAD_URL_PREFIX)) return null
            RuntimeValidation.requireHttpsUri(url, rejectPrivateHost = true)
            val bytes = integer(item.opt("bytes"))?.takeIf { it in 1..RuntimeLimits.MAX_COMPRESSED_BYTES } ?: return null
            val sha256 = item.opt("sha256") as? String ?: return null
            RuntimeValidation.requireSha256(sha256)
            val notes = item.opt("notes") as? String ?: return null
            if (notes.length > AppReleaseCatalog.MAX_NOTES_CHARS) return null
            AppReleaseCatalog.Release(version, url, bytes, sha256, notes).also {
                if (!catalog.isUpdateOf(installed, it)) return null
            }
        } else null
        return Snapshot(installed, checkedAt, release)
    }

    private fun encode(snapshot: Snapshot): String {
        val json = JSONObject().put("schema", 1)
            .put("installedVersion", snapshot.installed.versionName)
            .put("installedVersionCode", snapshot.installed.versionCode)
            .put("checkedAt", snapshot.checkedAt)
        snapshot.available?.let { release ->
            json.put("available", JSONObject().put("version", release.version).put("url", release.url)
                .put("bytes", release.bytes).put("sha256", release.sha256).put("notes", release.notes))
        }
        return json.toString().also { check(it.length <= MAX_CACHE_CHARS) }
    }

    private fun integer(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it in 0..9_007_199_254_740_991L }

    companion object {
        const val NO_UPDATE_TTL_MILLIS = 24 * 60 * 60 * 1000L
        const val MAX_CACHE_CHARS = 32 * 1024
        private var instance: AppUpdateCache? = null

        @Synchronized
        fun from(context: Context): AppUpdateCache {
            instance?.let { return it }
            val preferences = context.applicationContext.getSharedPreferences("dsh-app-update-cache", Context.MODE_PRIVATE)
            return AppUpdateCache(object : Storage {
                override fun read(): String? = preferences.getString("snapshot", null)
                override fun write(value: String) {
                    preferences.edit().putString("snapshot", value).apply()
                }
            }).also { instance = it }
        }
    }
}
