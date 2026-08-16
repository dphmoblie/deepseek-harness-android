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
import java.io.InputStream
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
    val loaderFile get() = File(appContext.applicationInfo.nativeLibraryDir, LOADER_NAME)
    private val launchDirectory = File(appContext.noBackupFilesDir, "dsh-runner")
    val launchRunnerFile = File(launchDirectory, "proot")
    val launchLoaderFile = File(launchDirectory, "loader")
    val resolverFile = File(appContext.filesDir, "runtime-resolv.conf")

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

    fun runnerAvailable(): Boolean {
        // 信任来自 APK 打包与签名，不依赖提取库的 x 位：部分机型（如荣耀）
        // 上 nativeLibraryDir 提取文件的 canExecute() 恒为 false，但硬链接后
        // 经系统加载路径执行不受 x 位影响。
        val missing = listOf(RUNNER_NAME to runnerFile, LOADER_NAME to loaderFile).filter { (_, file) ->
            !file.isFile || !file.canRead()
        }
        if (missing.isNotEmpty()) {
            android.util.Log.w(
                "dsh-runtime",
                "runner check failed: " + missing.joinToString(", ") { (name, file) ->
                    name + "(exists=" + file.exists() + ",isFile=" + file.isFile +
                        ",readable=" + file.canRead() + ",executable=" + file.canExecute() +
                        ",length=" + file.length() + ")"
                },
            )
        }
        return missing.isEmpty()
    }

    @Synchronized
    fun prepareLaunchFiles() {
        if (!runnerAvailable()) {
            throw RuntimeFailure("RUNNER_UNAVAILABLE", "APK 未包含当前架构的受信任运行器")
        }
        if (RuntimeFiles.existsNoFollow(launchDirectory)) {
            if (!RuntimeFiles.isDirectoryNoFollow(launchDirectory)) {
                throw RuntimeFailure("RUNNER_PREPARE_FAILED", "运行器私有目录无效")
            }
        } else if (!launchDirectory.mkdir()) {
            throw RuntimeFailure("RUNNER_PREPARE_FAILED", "无法创建运行器私有目录")
        }
        try {
            Os.chmod(launchDirectory.absolutePath, 0x1c0)
            refreshExecutableLink(runnerFile, launchRunnerFile)
            refreshExecutableLink(loaderFile, launchLoaderFile)
        } catch (error: Throwable) {
            if (error is RuntimeFailure) throw error
            throw RuntimeFailure("RUNNER_PREPARE_FAILED", "无法准备受信任运行器", error)
        }
    }

    fun openBundledManifest(): InputStream = openBundledAsset(BUNDLED_MANIFEST_ASSET)

    fun openBundledRootfs(): InputStream = openBundledAsset(BUNDLED_ROOTFS_ASSET)

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
                    .put("extractedBytes", manifest.rootfs.extractedBytes)
                    .put("compression", manifest.rootfs.compression.wireValue),
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

    private fun openBundledAsset(path: String): InputStream = try {
        appContext.assets.open(path)
    } catch (error: Exception) {
        throw RuntimeFailure("BUNDLED_RUNTIME_MISSING", "APK 未包含完整的初始化运行时", error)
    }

    private fun refreshExecutableLink(target: File, link: File) {
        if (isExecutableLinkTo(target, link)) return

        val pending = File(launchDirectory, ".${link.name}.new")
        if (RuntimeFiles.existsNoFollow(pending) && !pending.delete()) {
            throw RuntimeFailure("RUNNER_PREPARE_FAILED", "无法清理运行器临时链接")
        }
        try {
            Os.symlink(target.absolutePath, pending.absolutePath)
            if (!isExecutableLinkTo(target, pending)) {
                throw RuntimeFailure("RUNNER_PREPARE_FAILED", "运行器临时链接不可执行")
            }
            // rename replaces an old link atomically, so active PRoot processes never observe a missing loader.
            Os.rename(pending.absolutePath, link.absolutePath)
        } finally {
            if (RuntimeFiles.existsNoFollow(pending)) pending.delete()
        }
        if (!isExecutableLinkTo(target, link)) {
            throw RuntimeFailure("RUNNER_PREPARE_FAILED", "运行器私有链接不可执行")
        }
    }

    private fun isExecutableLinkTo(target: File, link: File): Boolean = try {
        val stat = Os.lstat(link.absolutePath)
        OsConstants.S_ISLNK(stat.st_mode) &&
            Os.readlink(link.absolutePath) == target.absolutePath &&
            link.isFile && link.canRead() && link.canExecute()
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }

    companion object {
        private const val PREFERENCES = "runtime_settings"
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_MANIFEST_SHA256 = "manifest_sha256"
        private const val KEY_KEEP_AWAKE = "keep_screen_awake"
        private const val KEY_FONT_SIZE = "terminal_font_size"
        private const val RUNNER_NAME = "libdsh_proot.so"
        private const val LOADER_NAME = "libdsh_proot_loader.so"
        private const val BUNDLED_MANIFEST_ASSET = "runtime/runtime-manifest.json"
        private const val BUNDLED_ROOTFS_ASSET = "runtime/rootfs.bundle"
    }
}
