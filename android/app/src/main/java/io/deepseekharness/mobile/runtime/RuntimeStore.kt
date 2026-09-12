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
    private val credentialCipher = RuntimeCredentialCipher()

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
    val harnessPidFile = File(appContext.noBackupFilesDir, "dsh-harness.pid")
    private val launcherConfigDirectory = File(currentRoot, "root/.dsh-mobile")
    private val providerPatchFile = File(launcherConfigDirectory, PROVIDER_PATCH_FILENAME)

    @Volatile private var manifestCacheLoaded = false
    @Volatile private var manifestCache: RuntimeManifest? = null
    @Volatile private var bundledManifestCacheLoaded = false
    @Volatile private var bundledManifestCache: RuntimeManifest? = null

    // UI lifecycle reads do not need credentials or a working Keystore service.
    fun keepScreenAwake(): Boolean = preferences.getBoolean(KEY_KEEP_AWAKE, false)

    /**
     * 「后台保持 Harness」开关。
     * 旧版本配置里没有该键，缺失时统一按 false 处理，与设置默认值保持一致。
     */
    fun keepRuntimeInBackground(): Boolean = preferences.getBoolean(KEY_KEEP_BACKGROUND, false)

    /**
     * 写入运行时恢复记录：只保存运行意图、最近阶段与时间。
     *
     * 这里刻意不保存任何凭据——Harness 的临时 Basic Auth 密码只存在于进程内存中。
     * Android 进程被系统强制停止后，该密码不可恢复，恢复流程必须据此提示重新连接，
     * 而不是假装旧会话仍在。
     */
    fun recordRuntimeIntent(intent: RuntimeIntent, phase: RuntimePhase, updatedAtMillis: Long) {
        preferences.edit()
            .putString(KEY_RUNTIME_INTENT, intent.wireValue)
            .putString(KEY_RUNTIME_PHASE, phase.wireValue)
            .putLong(KEY_RUNTIME_UPDATED_AT, updatedAtMillis.coerceAtLeast(0))
            .apply()
    }

    /** 读取运行时恢复记录；从未记录或内容损坏时返回 [RuntimeIntentRecord.EMPTY]。 */
    fun runtimeIntentRecord(): RuntimeIntentRecord {
        val phase = preferences.getString(KEY_RUNTIME_PHASE, null)
            ?.let { raw -> RuntimePhase.entries.firstOrNull { it.wireValue == raw } }
        return RuntimeIntentRecord(
            intent = RuntimeIntent.parse(preferences.getString(KEY_RUNTIME_INTENT, null)),
            phase = phase,
            updatedAtMillis = preferences.getLong(KEY_RUNTIME_UPDATED_AT, 0L).coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun settings(): RuntimeSettings {
        val storedUrl = preferences.getString(KEY_MANIFEST_URL, null)
        val storedSha256 = preferences.getString(KEY_MANIFEST_SHA256, null)
        val pinnedDefaultAvailable = BuildConfig.DEFAULT_MANIFEST_URL.isNotEmpty() &&
            BuildConfig.DEFAULT_MANIFEST_SHA256.isNotEmpty()
        val migrateEmptyBundledSource = storedUrl == "" && storedSha256 == "" && pinnedDefaultAvailable
        val usePinnedDefault = (storedUrl == null && storedSha256 == null) || migrateEmptyBundledSource
        val providerApiKeys = providerApiKeysLocked()
        val customProviders = customModelProvidersLocked()
        val customProviderApiKeys = customProviderApiKeysLocked(customProviders.mapTo(linkedSetOf()) { it.id })
        return RuntimeSettings(
            manifestUrl = if (usePinnedDefault) BuildConfig.DEFAULT_MANIFEST_URL else storedUrl.orEmpty(),
            manifestSha256 = if (usePinnedDefault) BuildConfig.DEFAULT_MANIFEST_SHA256 else storedSha256.orEmpty(),
            keepScreenAwake = keepScreenAwake(),
            keepRuntimeInBackground = keepRuntimeInBackground(),
            terminalFontSize = preferences.getInt(KEY_FONT_SIZE, 14).coerceIn(11, 24),
            configuredModelProviders = ModelProvider.entries.filterTo(linkedSetOf()) { providerApiKeys.containsKey(it) },
            customModelProviders = customProviders,
            configuredCustomModelProviders = customProviders.mapNotNullTo(linkedSetOf()) { provider ->
                provider.id.takeIf(customProviderApiKeys::containsKey)
            },
            autoLaunch = preferences.getBoolean(KEY_AUTO_LAUNCH, false),
        )
    }

    @Synchronized
    fun saveSettings(
        settings: RuntimeSettings,
        providerApiKeyUpdates: Map<ModelProvider, String> = emptyMap(),
        clearedProviderApiKeys: Set<ModelProvider> = emptySet(),
        customProviders: List<CustomModelProvider> = emptyList(),
        customProviderApiKeyUpdates: Map<String, String> = emptyMap(),
        clearedCustomProviderApiKeys: Set<String> = emptySet(),
    ): RuntimeSettings {
        if (providerApiKeyUpdates.keys.any(clearedProviderApiKeys::contains)) {
            throw RuntimeFailure("SETTINGS_INVALID", "同一模型凭据不能同时更新和清除")
        }
        val allowedCustomIds = customProviders.mapTo(linkedSetOf()) { it.id }
        if (customProviderApiKeyUpdates.keys.any(clearedCustomProviderApiKeys::contains) ||
            customProviderApiKeyUpdates.keys.any { it !in allowedCustomIds } || clearedCustomProviderApiKeys.any { it !in allowedCustomIds }
        ) throw RuntimeFailure("SETTINGS_INVALID", "自定义模型凭据更新与供应商配置不一致")
        val providerApiKeys = providerApiKeysLocked().toMutableMap()
        clearedProviderApiKeys.forEach(providerApiKeys::remove)
        providerApiKeys.putAll(providerApiKeyUpdates)
        val storedCustomIds = customModelProvidersLocked().mapTo(linkedSetOf()) { it.id }
        val customProviderApiKeys = customProviderApiKeysLocked(storedCustomIds).toMutableMap()
        customProviderApiKeys.keys.retainAll(allowedCustomIds)
        clearedCustomProviderApiKeys.forEach(customProviderApiKeys::remove)
        customProviderApiKeys.putAll(customProviderApiKeyUpdates)
        val encryptedCredentials = encryptProviderApiKeys(providerApiKeys)
        val encryptedCustomCredentials = encryptCustomProviderApiKeys(customProviderApiKeys)
        val editor = preferences.edit()
            .putString(KEY_MANIFEST_URL, settings.manifestUrl)
            .putString(KEY_MANIFEST_SHA256, settings.manifestSha256)
            .putBoolean(KEY_KEEP_AWAKE, settings.keepScreenAwake)
            .putBoolean(KEY_KEEP_BACKGROUND, settings.keepRuntimeInBackground)
            .putInt(KEY_FONT_SIZE, settings.terminalFontSize)
            // Retired frontend choices must not redirect the single official entrypoint.
            .remove(KEY_LEGACY_DEFAULT_FRONTEND)
            .putBoolean(KEY_AUTO_LAUNCH, settings.autoLaunch)
            .putString(KEY_CUSTOM_PROVIDERS, customProvidersToJson(customProviders).toString())
            .remove(KEY_API_KEY)
            .remove("device_bridge_token")
        if (encryptedCredentials == null) editor.remove(KEY_PROVIDER_CREDENTIALS)
        else editor.putString(KEY_PROVIDER_CREDENTIALS, encryptedCredentials)
        if (encryptedCustomCredentials == null) editor.remove(KEY_CUSTOM_PROVIDER_CREDENTIALS)
        else editor.putString(KEY_CUSTOM_PROVIDER_CREDENTIALS, encryptedCustomCredentials)
        val committed = editor.commit()
        if (!committed) throw RuntimeFailure("SETTINGS_WRITE_FAILED", "无法保存运行时设置")
        return settings.copy(
            configuredModelProviders = ModelProvider.entries.filterTo(linkedSetOf()) { providerApiKeys.containsKey(it) },
            customModelProviders = customProviders,
            configuredCustomModelProviders = customProviders.mapNotNullTo(linkedSetOf()) { provider ->
                provider.id.takeIf(customProviderApiKeys::containsKey)
            },
        )
    }

    @Synchronized
    fun providerApiKeys(): Map<ModelProvider, String> = providerApiKeysLocked().toMap()

    @Synchronized
    fun customProviderApiKeys(): Map<String, String> {
        val allowedIds = customModelProvidersLocked().mapTo(linkedSetOf()) { it.id }
        return customProviderApiKeysLocked(allowedIds).toMap()
    }

    /** Writes a fixed, secret-free Cordis overlay for the configured pi-ai routes. */
    @Synchronized
    fun prepareProviderPatch(configuredProviders: Set<ModelProvider>): String? {
        val enabled = ModelProvider.entries.filter { it != ModelProvider.DEEPSEEK && configuredProviders.contains(it) }
        val customProviders = customModelProvidersLocked()
        if (enabled.isEmpty() && customProviders.isEmpty()) {
            deleteGeneratedFile(providerPatchFile)
            return null
        }
        val rootHome = File(currentRoot, "root")
        if (!RuntimeFiles.isDirectoryNoFollow(rootHome)) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "Ubuntu 主目录无效")
        }
        if (RuntimeFiles.existsNoFollow(launcherConfigDirectory)) {
            if (!RuntimeFiles.isDirectoryNoFollow(launcherConfigDirectory)) {
                throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "启动器配置目录无效")
            }
        } else if (!launcherConfigDirectory.mkdir()) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "无法创建启动器配置目录")
        }

        val providers = JSONObject()
        enabled.forEach { provider ->
            providers.put(provider.wireValue, JSONObject().put("apiKeyEnv", provider.environmentVariable))
        }
        customProviders.forEach { provider ->
            providers.put(
                provider.id,
                JSONObject()
                    .put("apiKeyEnv", provider.environmentVariable)
                    .put("displayName", provider.name)
                    .put("api", provider.api.wireValue)
                    .put("baseURL", provider.baseUrl)
                    .put("models", JSONArray().also { models ->
                        provider.models.forEach { model ->
                            models.put(
                                JSONObject()
                                    .put("id", model.id)
                                    .put("name", model.name)
                                    .put("contextWindow", model.contextWindow)
                                    .put("maxTokens", model.maxTokens),
                            )
                        }
                    }),
            )
        }
        val bytes = JSONArray()
            .put(
                JSONObject()
                    .put("id", "llm-pi-ai")
                    .put("config", JSONObject().put("providers", providers)),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        val pending = File(launcherConfigDirectory, ".$PROVIDER_PATCH_FILENAME.new")
        try {
            Os.chmod(launcherConfigDirectory.absolutePath, 0x1c0)
            deleteGeneratedFile(pending)
            requireRegularGeneratedFileOrMissing(providerPatchFile)
            FileChannel.open(
                pending.toPath(),
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
            Os.chmod(pending.absolutePath, 0x180)
            Os.rename(pending.absolutePath, providerPatchFile.absolutePath)
        } catch (error: Throwable) {
            try {
                deleteGeneratedFile(pending)
            } catch (_: Throwable) {
                // Preserve the original bounded configuration failure.
            }
            if (error is RuntimeFailure) throw error
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "无法生成模型供应商启动配置", error)
        }
        return RuntimeCommand.PROVIDER_PATCH_GUEST_PATH
    }

    private fun providerApiKeysLocked(): Map<ModelProvider, String> {
        val encrypted = preferences.getString(KEY_PROVIDER_CREDENTIALS, null)
        if (!encrypted.isNullOrEmpty()) {
            return try {
                val plaintext = credentialCipher.decrypt(encrypted)
                try {
                    RuntimeValidation.providerApiKeyUpdates(JSONObject(plaintext.toString(Charsets.UTF_8)))
                } finally {
                    plaintext.fill(0)
                }
            } catch (error: Exception) {
                // A temporary Keystore failure must never erase saved credentials.
                throw RuntimeFailure(
                    "CREDENTIALS_DECRYPT_FAILED",
                    "无法读取已保存的模型凭据；原有数据已保留，请稍后重试",
                    error,
                )
            }
        }

        val legacy = preferences.getString(KEY_API_KEY, null)?.trim().orEmpty()
        if (legacy.isEmpty()) return emptyMap()
        val migrated = try {
            mapOf(ModelProvider.DEEPSEEK to RuntimeValidation.requireProviderApiKey(legacy))
        } catch (error: RuntimeFailure) {
            throw RuntimeFailure(
                "CREDENTIALS_DECRYPT_FAILED",
                "无法读取已保存的模型凭据；原有数据已保留，请稍后重试",
                error,
            )
        }
        val encryptedMigration = encryptProviderApiKeys(migrated)
            ?: throw RuntimeFailure("SETTINGS_WRITE_FAILED", "无法迁移模型凭据")
        val committed = preferences.edit()
            .putString(KEY_PROVIDER_CREDENTIALS, encryptedMigration)
            .remove(KEY_API_KEY)
            .commit()
        if (!committed) throw RuntimeFailure("SETTINGS_WRITE_FAILED", "无法迁移模型凭据")
        return migrated
    }

    private fun encryptProviderApiKeys(providerApiKeys: Map<ModelProvider, String>): String? {
        if (providerApiKeys.isEmpty()) return null
        val json = JSONObject()
        ModelProvider.entries.forEach { provider ->
            providerApiKeys[provider]?.let { json.put(provider.wireValue, it) }
        }
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        return try {
            credentialCipher.encrypt(plaintext)
        } catch (error: Throwable) {
            throw RuntimeFailure("CREDENTIALS_ENCRYPT_FAILED", "无法安全保存模型凭据", error)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun customModelProvidersLocked(): List<CustomModelProvider> {
        val raw = preferences.getString(KEY_CUSTOM_PROVIDERS, null) ?: return emptyList()
        return try {
            RuntimeValidation.customModelProviders(JSONArray(raw))
        } catch (error: Exception) {
            throw RuntimeFailure("SETTINGS_READ_FAILED", "无法读取自定义模型供应商配置", error)
        }
    }

    private fun customProviderApiKeysLocked(allowedIds: Set<String>): Map<String, String> {
        val encrypted = preferences.getString(KEY_CUSTOM_PROVIDER_CREDENTIALS, null) ?: return emptyMap()
        return try {
            val plaintext = credentialCipher.decrypt(encrypted)
            try {
                RuntimeValidation.customProviderApiKeyUpdates(JSONObject(plaintext.toString(Charsets.UTF_8)), allowedIds)
            } finally {
                plaintext.fill(0)
            }
        } catch (error: Exception) {
            throw RuntimeFailure("CREDENTIALS_DECRYPT_FAILED", "无法读取已保存的自定义模型凭据；原有数据已保留", error)
        }
    }

    private fun encryptCustomProviderApiKeys(values: Map<String, String>): String? {
        if (values.isEmpty()) return null
        val plaintext = JSONObject(values).toString().toByteArray(Charsets.UTF_8)
        return try {
            credentialCipher.encrypt(plaintext)
        } catch (error: Throwable) {
            throw RuntimeFailure("CREDENTIALS_ENCRYPT_FAILED", "无法安全保存自定义模型凭据", error)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun customProvidersToJson(providers: List<CustomModelProvider>): JSONArray = JSONArray().also { result ->
        providers.forEach { provider ->
            result.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("api", provider.api.wireValue)
                    .put("baseUrl", provider.baseUrl)
                    .put("models", JSONArray().also { models ->
                        provider.models.forEach { model ->
                            models.put(JSONObject().put("id", model.id).put("name", model.name)
                                .put("contextWindow", model.contextWindow).put("maxTokens", model.maxTokens))
                        }
                    }),
            )
        }
    }

    private fun requireRegularGeneratedFileOrMissing(file: File) {
        if (!RuntimeFiles.existsNoFollow(file)) return
        val stat = try {
            Os.lstat(file.absolutePath)
        } catch (error: ErrnoException) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "无法检查启动器配置文件", error)
        }
        if (!OsConstants.S_ISREG(stat.st_mode)) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "启动器配置文件类型无效")
        }
    }

    private fun deleteGeneratedFile(file: File) {
        if (!RuntimeFiles.existsNoFollow(file)) return
        requireRegularGeneratedFileOrMissing(file)
        if (!file.delete()) throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "无法清理旧的启动器配置")
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

    /**
     * APK assets are immutable for the lifetime of this process. A missing or malformed bundled
     * manifest is treated as "no bundled update" here; installation still reports its precise
     * validation error when the user explicitly selects the bundled source.
     */
    @Synchronized
    fun bundledManifestOrNull(): RuntimeManifest? {
        if (bundledManifestCacheLoaded) return bundledManifestCache
        bundledManifestCache = try {
            openBundledManifest().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > RuntimeLimits.MAX_MANIFEST_BYTES) {
                        throw RuntimeFailure("MANIFEST_SIZE_INVALID", "APK 内置运行时清单大小无效")
                    }
                    output.write(buffer, 0, read)
                }
                RuntimeManifest.parse(output.toByteArray()).also {
                    // A thin APK may carry no rootfs asset; do not advertise an update it cannot apply.
                    openBundledRootfs().use { }
                }
            }
        } catch (_: Exception) {
            null
        }
        bundledManifestCacheLoaded = true
        return bundledManifestCache
    }

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
        if (isPreparedRunner(target, link)) return

        val pending = File(launchDirectory, ".${link.name}.new")
        if (RuntimeFiles.existsNoFollow(pending) && !pending.delete()) {
            throw RuntimeFailure("RUNNER_PREPARE_FAILED", "无法清理运行器临时链接")
        }
        try {
            try {
                Os.symlink(target.absolutePath, pending.absolutePath)
            } catch (error: android.system.ErrnoException) {
                // 荣耀等 ROM 的 SELinux 禁止应用创建符号链接（EACCES/EPERM）：
                // 降级为复制运行器文件并标记可执行，保证启动路径在任何设备上可用。
                if (error.errno == android.system.OsConstants.EACCES ||
                    error.errno == android.system.OsConstants.EPERM ||
                    error.errno == android.system.OsConstants.ENOTSUP ||
                    error.errno == android.system.OsConstants.EXDEV
                ) {
                    copyRunnerFallback(target, pending)
                } else {
                    throw error
                }
            }
            if (!isPreparedRunner(target, pending)) {
                throw RuntimeFailure("RUNNER_PREPARE_FAILED", "运行器临时链接不可执行")
            }
            // rename replaces an old link atomically, so active PRoot processes never observe a missing loader.
            Os.rename(pending.absolutePath, link.absolutePath)
        } finally {
            if (RuntimeFiles.existsNoFollow(pending)) pending.delete()
        }
        if (!isPreparedRunner(target, link)) {
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

    /** 运行器已就绪的判定：符号链接形态，或 ROM 拒绝链接时的降级复制形态（常规文件且非空）。 */
    private fun isPreparedRunner(target: File, path: File): Boolean {
        if (isExecutableLinkTo(target, path)) return true
        return try {
            val stat = Os.lstat(path.absolutePath)
            OsConstants.S_ISREG(stat.st_mode) && stat.st_size > 0
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) false else throw error
        }
    }

    /**
     * 符号链接被 ROM 拒绝时的降级：把运行器复制到私有启动目录，
     * 设置 owner 可执行并尝试打 Android 15+ 要求的 security.android.exec 标记
     * （与 rootfs 可执行文件盖章一致；旧系统不支持时忽略）。
     */
    private fun copyRunnerFallback(target: File, pending: File) {
        target.copyTo(pending, overwrite = false)
        pending.setExecutable(true, false)
        try {
            Os.setxattr(pending.absolutePath, EXEC_XATTR_NAME, EXEC_XATTR_VALUE, 0)
        } catch (_: Throwable) {
            // 旧内核/ROM 不支持该属性时忽略，能否执行由系统策略决定。
        }
    }

    companion object {
        private const val PREFERENCES = "runtime_settings"
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_MANIFEST_SHA256 = "manifest_sha256"
        private const val KEY_KEEP_AWAKE = "keep_screen_awake"
        private const val KEY_KEEP_BACKGROUND = "keep_runtime_in_background"
        // 运行时恢复记录：只保存运行意图、最近阶段与时间，绝不保存凭据。
        private const val KEY_RUNTIME_INTENT = "runtime_intent"
        private const val KEY_RUNTIME_PHASE = "runtime_last_phase"
        private const val KEY_RUNTIME_UPDATED_AT = "runtime_last_updated_at"
        private const val KEY_FONT_SIZE = "terminal_font_size"
        private const val KEY_API_KEY = "model_api_key"
        private const val KEY_PROVIDER_CREDENTIALS = "provider_credentials_encrypted_v1"
        private const val KEY_CUSTOM_PROVIDERS = "custom_model_providers_v1"
        private const val KEY_CUSTOM_PROVIDER_CREDENTIALS = "custom_provider_credentials_encrypted_v1"
        private const val KEY_LEGACY_DEFAULT_FRONTEND = "default_frontend"
        private const val KEY_AUTO_LAUNCH = "auto_launch"
        private const val RUNNER_NAME = "libdsh_proot.so"
        private const val LOADER_NAME = "libdsh_proot_loader.so"
        private const val EXEC_XATTR_NAME = "security.android.exec"
        private val EXEC_XATTR_VALUE: ByteArray = byteArrayOf('1'.code.toByte())
        private const val BUNDLED_MANIFEST_ASSET = "runtime/runtime-manifest.json"
        private const val BUNDLED_ROOTFS_ASSET = "runtime/rootfs.bundle"
        private const val PROVIDER_PATCH_FILENAME = "launcher-providers.patch.json"
    }
}
