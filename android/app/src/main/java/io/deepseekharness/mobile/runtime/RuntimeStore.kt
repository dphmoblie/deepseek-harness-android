package io.deepseekharness.mobile.runtime

import android.content.Context
import android.content.SharedPreferences
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.deepseekharness.mobile.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

class RuntimeStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val runtimeParent = File(appContext.noBackupFilesDir, "dsh-runtime")
    val currentRoot = File(runtimeParent, "current")
    val currentManifest = File(runtimeParent, "current-manifest.json")
    val backupRoot = File(runtimeParent, "previous")
    val backupManifest = File(runtimeParent, "previous-manifest.json")
    val runnerFile get() = File(appContext.applicationInfo.nativeLibraryDir, RUNNER_NAME)

    @Volatile private var manifestCacheLoaded = false
    @Volatile private var manifestCache: RuntimeManifest? = null

    @Synchronized
    fun settings(): RuntimeSettings = RuntimeSettings(
        manifestUrl = preferences.getString(KEY_MANIFEST_URL, null) ?: BuildConfig.DEFAULT_MANIFEST_URL,
        manifestSha256 = preferences.getString(KEY_MANIFEST_SHA256, null) ?: BuildConfig.DEFAULT_MANIFEST_SHA256,
        keepScreenAwake = preferences.getBoolean(KEY_KEEP_AWAKE, false),
        terminalFontSize = preferences.getInt(KEY_FONT_SIZE, 14).coerceIn(11, 24),
    )

    @Synchronized
    fun saveSettings(settings: RuntimeSettings) {
        val committed = preferences.edit()
            .putString(KEY_MANIFEST_URL, settings.manifestUrl)
            .putString(KEY_MANIFEST_SHA256, settings.manifestSha256)
            .putBoolean(KEY_KEEP_AWAKE, settings.keepScreenAwake)
            .putInt(KEY_FONT_SIZE, settings.terminalFontSize)
            .commit()
        if (!committed) throw RuntimeFailure("SETTINGS_WRITE_FAILED", "无法保存运行时设置")
    }

    fun runnerAvailable(): Boolean = runnerFile.isFile && runnerFile.canRead() && runnerFile.canExecute()

    @Synchronized
    fun installedManifest(): RuntimeManifest? {
        if (manifestCacheLoaded) return manifestCache
        manifestCache = readInstalledManifest()
        manifestCacheLoaded = true
        return manifestCache
    }

    @Synchronized
    fun updateInstalledManifest(manifest: RuntimeManifest?) {
        manifestCache = manifest
        manifestCacheLoaded = true
    }

    @Synchronized
    fun invalidateInstalledManifest() {
        manifestCache = null
        manifestCacheLoaded = false
    }

    fun writeInstalledManifest(destination: File, manifest: RuntimeManifest) {
        val bytes = JSONObject()
            .put("schemaVersion", 1)
            .put("runtimeId", manifest.runtimeId)
            .put("version", manifest.version)
            .put("architecture", manifest.architecture)
            .put(
                "rootfs",
                JSONObject()
                    // The signed download URL is deliberately not persisted in guest-visible metadata.
                    .put("url", "https://installed.invalid/rootfs")
                    .put("sha256", manifest.rootfs.sha256)
                    .put("compressedBytes", manifest.rootfs.compressedBytes)
                    .put("extractedBytes", manifest.rootfs.extractedBytes),
            )
            .put(
                "entrypoints",
                JSONObject()
                    .put("shell", JSONArray(manifest.shellArgv))
                    .put("harness", JSONArray(manifest.harnessArgv)),
            )
            .put("harnessUrl", manifest.harnessUri.toASCIIString())
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (bytes.size > RuntimeLimits.MAX_MANIFEST_BYTES) {
            throw RuntimeFailure("MANIFEST_SIZE_INVALID", "运行时元数据超过限制")
        }
        FileChannel.open(
            destination.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            Channels.newOutputStream(channel).use { output ->
                output.write(bytes)
                output.flush()
                channel.force(true)
            }
        }
    }

    private fun readInstalledManifest(): RuntimeManifest? {
        if (!RuntimeFiles.isDirectoryNoFollow(currentRoot)) return null
        val descriptor = try {
            Os.open(currentManifest.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ELOOP) return null
            return null
        }
        return try {
            val stat = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size !in 1..RuntimeLimits.MAX_MANIFEST_BYTES.toLong()) {
                Os.close(descriptor)
                return null
            }
            FileInputStream(descriptor).use { input ->
                val output = ByteArrayOutputStream(stat.st_size.toInt())
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > RuntimeLimits.MAX_MANIFEST_BYTES) return null
                    output.write(buffer, 0, read)
                }
                RuntimeManifest.parse(output.toByteArray())
            }
        } catch (_: Exception) {
            try {
                Os.close(descriptor)
            } catch (_: Exception) {
                // FileInputStream owns the descriptor after successful construction.
            }
            null
        }
    }

    companion object {
        private const val PREFERENCES = "runtime_settings"
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_MANIFEST_SHA256 = "manifest_sha256"
        private const val KEY_KEEP_AWAKE = "keep_screen_awake"
        private const val KEY_FONT_SIZE = "terminal_font_size"
        private const val RUNNER_NAME = "libdsh_proot.so"
    }
}
