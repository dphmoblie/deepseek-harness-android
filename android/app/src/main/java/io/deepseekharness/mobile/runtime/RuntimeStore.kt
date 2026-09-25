package io.deepseekharness.mobile.runtime

import android.content.Context
import android.content.SharedPreferences
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.deepseekharness.mobile.BuildConfig
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class RuntimeStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val credentialCipher = RuntimeCredentialCipher()

    val runtimeParent = File(appContext.noBackupFilesDir, "dsh-runtime")
    val currentRoot = File(runtimeParent, "current")
    val currentManifest = File(runtimeParent, "current-manifest.json")

    /**
     * 安装期间的瞬时槽：提升新运行时前，旧运行时先改名到这里。
     * 它**不是**用户可以切换的版本，提升成功后会被 [retainedRoot] 接手（见 RuntimeInstaller）。
     */
    val backupRoot = File(runtimeParent, "previous")
    val backupManifest = File(runtimeParent, "previous-manifest.json")

    /**
     * 上一版本槽：上一次提升成功后，被换下来的那一份运行时留在这里。
     *
     * 与 [backupRoot] 分开是有意的：`previous*` 的语义是「提升正在进行」，
     * 中断恢复逻辑据此判断该前滚还是回滚；已完成的版本必须用一个不会被恢复逻辑
     * 误判的独立名字保存，否则用户会在下次安装开始时丢掉可回退的副本。
     */
    val retainedRoot = File(runtimeParent, "retained")
    val retainedManifest = File(runtimeParent, "retained-manifest.json")
    val runnerFile get() = File(appContext.applicationInfo.nativeLibraryDir, RUNNER_NAME)
    val loaderFile get() = File(appContext.applicationInfo.nativeLibraryDir, LOADER_NAME)
    private val launchDirectory = File(appContext.noBackupFilesDir, "dsh-runner")
    val launchRunnerFile = File(launchDirectory, "proot")
    val launchLoaderFile = File(launchDirectory, "loader")
    val resolverFile = File(appContext.filesDir, "runtime-resolv.conf")
    /** Host name map is generated alongside resolver configuration and bind-mounted into guest. */
    val hostsFile = File(appContext.filesDir, "runtime-hosts")
    val harnessPidFile = File(appContext.noBackupFilesDir, "dsh-harness.pid")

    /**
     * 应用自诊断日志。
     *
     * 放在 store 上的原因：运行时各组件（状态机、supervisor、插件、前台服务）都持有 store，
     * 排障所需的埋点因此不需要新增构造参数。它只写受控枚举与受控键值，绝不保存凭据。
     */
    val diagnostics: DiagnosticLog by lazy { DiagnosticLog(appContext) }

    private val launcherConfigDirectory = File(currentRoot, "root/.dsh-mobile")
    private val providerPatchFile = File(launcherConfigDirectory, PROVIDER_PATCH_FILENAME)

    @Volatile private var manifestCacheLoaded = false
    @Volatile private var manifestCache: RuntimeManifest? = null
    @Volatile private var retainedCacheLoaded = false
    @Volatile private var retainedCache: RuntimeManifest? = null
    @Volatile private var bundledManifestCacheLoaded = false
    @Volatile private var bundledManifestCache: RuntimeManifest? = null

    // UI lifecycle reads do not need credentials or a working Keystore service.
    fun keepScreenAwake(): Boolean = preferences.getBoolean(KEY_KEEP_AWAKE, false)

    /**
     * 「后台保持 Harness」开关。
     * 旧版本配置里没有该键，缺失时统一按 false 处理，与设置默认值保持一致。
     */
    fun keepRuntimeInBackground(): Boolean = preferences.getBoolean(KEY_KEEP_BACKGROUND, false)

    /** 悬浮球开关。缺键时按 false 处理：老版本升级上来的用户不会突然多出一个球。 */
    fun overlayBallEnabled(): Boolean = preferences.getBoolean(KEY_OVERLAY_BALL, false)

    /** 不读取凭据；缺键或损坏的偏好回落到要求沙箱的默认值。 */
    fun harnessPermissionMode(): HarnessPermissionMode = try {
        HarnessPermissionMode.parse(preferences.getString(KEY_HARNESS_PERMISSION_MODE, "workspace-write"))
    } catch (_: RuntimeFailure) {
        HarnessPermissionMode.WORKSPACE_WRITE
    } catch (_: ClassCastException) {
        HarnessPermissionMode.WORKSPACE_WRITE
    }

    /**
     * 由悬浮球菜单在原生侧直接关闭开关。
     *
     * 菜单是原生界面，没有前端调用栈可以回写设置，因此这里直接落盘。
     * 本方法只负责写入持久化存储，不负责通知前端；界面要看到新值，
     * 需要界面侧在进入设置页时重新读取设置，这是界面侧的职责。
     * 只写这一个布尔量，不触碰其他设置。
     */
    fun setOverlayBallEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_OVERLAY_BALL, enabled).apply()
    }

    /**
     * 把应用界面语言同步到 Harness 运行时的 `~/.dsh/settings.yaml`（locale.preference）。
     *
     * 运行时尚未安装时视为合法空操作：允许在安装前选择语言，由 startHarness() 重放。
     * 安全校验点：路径全程 NoFollow 防符号链接逃逸；目录 0700、文件 0600（仅属主可读写）；
     * 先写临时文件并 fsync，再原子替换，避免写出半截配置。
     */
    @Synchronized
    fun syncHarnessLocale(language: String): Boolean {
        val harnessLanguage = when (language) {
            "zh-CN" -> "zh"
            "en" -> "en"
            else -> throw RuntimeFailure("LANGUAGE_INVALID", "不支持的应用语言")
        }
        // 安装前选择语言是允许的；运行时文件系统就绪后由 startHarness() 重放已保存的选择。
        if (!RuntimeFiles.isDirectoryNoFollow(currentRoot)) return true
        val rootHome = File(currentRoot, "root")
        if (!RuntimeFiles.isDirectoryNoFollow(rootHome)) {
            throw RuntimeFailure("LANGUAGE_SYNC_FAILED", "Harness 主目录无效")
        }
        val settingsFile = File(rootHome, ".dsh/settings.yaml")
        val settingsDirectory = settingsFile.parentFile
            ?: throw RuntimeFailure("LANGUAGE_SYNC_FAILED", "Harness 语言设置路径无效")
        // dsh-settings-file 惰性创建该文件。这里补齐父目录与 locale 文档，
        // 使首次 Harness 写入设置前选择的语言也能持久化并被其文件监听拾取。
        if (!RuntimeFiles.existsNoFollow(settingsDirectory)) {
            if (!settingsDirectory.mkdir() && !RuntimeFiles.existsNoFollow(settingsDirectory)) {
                throw RuntimeFailure("LANGUAGE_SYNC_FAILED", "无法创建 Harness 语言设置目录")
            }
        }
        if (!RuntimeFiles.isDirectoryNoFollow(settingsDirectory)) {
            throw RuntimeFailure("LANGUAGE_SYNC_FAILED", "Harness 语言设置目录无效")
        }
        Os.chmod(settingsDirectory.absolutePath, 0x1c0)
        val settingsExists = RuntimeFiles.existsNoFollow(settingsFile)
        if (settingsExists && !isRegularFileNoFollow(settingsFile)) {
            throw RuntimeFailure("LANGUAGE_SYNC_FAILED", "Harness 语言设置文件无效")
        }
        val current = try {
            if (settingsExists) settingsFile.readText(Charsets.UTF_8) else ""
        } catch (error: Exception) {
            throw RuntimeFailure("LANGUAGE_SYNC_FAILED", "无法读取 Harness 语言设置", error)
        }
        val updated = HarnessLocaleSettings.updateYaml(current, harnessLanguage)
        if (settingsExists && updated == current) return true
        val temporary = File(settingsDirectory, ".settings.yaml.dsh-locale.tmp")
        try {
            if (RuntimeFiles.existsNoFollow(temporary)) temporary.delete()
            FileChannel.open(
                temporary.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                Channels.newOutputStream(channel).use { output ->
                    output.write(updated.toByteArray(Charsets.UTF_8))
                    output.flush()
                    channel.force(true)
                }
            }
            Os.chmod(temporary.absolutePath, 0x180)
            try {
                java.nio.file.Files.move(
                    temporary.toPath(), settingsFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // 个别文件系统不支持原子改名时退化为普通替换，NoFollow 约束仍保留。
                java.nio.file.Files.move(
                    temporary.toPath(), settingsFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                )
            }
            return true
        } catch (error: Exception) {
            try { temporary.delete() } catch (_: Exception) { }
            throw RuntimeFailure("LANGUAGE_SYNC_FAILED", "无法保存 Harness 语言设置", error)
        }
    }

    private fun isRegularFileNoFollow(file: File): Boolean = try {
        val mode = Os.lstat(file.absolutePath).st_mode
        OsConstants.S_ISREG(mode)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw RuntimeFailure("FILESYSTEM_ERROR", "无法检查 Harness 设置文件", error)
    }

    /**
     * 写入运行时恢复记录：只保存运行意图、最近阶段与时间。
     *
     * 这里刻意不保存任何凭据——Harness 的临时 Basic Auth 密码只存在于进程内存中。
     * Android 进程被系统强制停止后，该密码不可恢复，恢复流程必须据此提示重新连接，
     * 而不是假装旧会话仍在。
     *
     * 必须同步落盘（[android.content.SharedPreferences.Editor.commit]）：这条记录是
     * 进程消失后判定「上次是否正常结束」的唯一依据，而进程消失恰恰可能紧跟在写入之后
     * （正常退出后立即被系统或厂商清理；实测 MIUI 上出现过正常退出仍被误判为
     * unclean_exit 的案例）。异步 apply 的磁盘写可能来不及完成就随进程丢失。
     * 写入是低频的（仅在阶段变化时触发），commit 的开销可忽略。
     */
    fun recordRuntimeIntent(intent: RuntimeIntent, phase: RuntimePhase, updatedAtMillis: Long) {
        preferences.edit()
            .putString(KEY_RUNTIME_INTENT, intent.wireValue)
            .putString(KEY_RUNTIME_PHASE, phase.wireValue)
            .putLong(KEY_RUNTIME_UPDATED_AT, updatedAtMillis.coerceAtLeast(0))
            .commit()
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
        val harnessCredentials = harnessCredentialStatus(customProviders)
        return RuntimeSettings(
            manifestUrl = if (usePinnedDefault) BuildConfig.DEFAULT_MANIFEST_URL else storedUrl.orEmpty(),
            manifestSha256 = if (usePinnedDefault) BuildConfig.DEFAULT_MANIFEST_SHA256 else storedSha256.orEmpty(),
            keepScreenAwake = keepScreenAwake(),
            keepRuntimeInBackground = keepRuntimeInBackground(),
            overlayBallEnabled = overlayBallEnabled(),
            harnessPermissionMode = harnessPermissionMode(),
            terminalFontSize = preferences.getInt(KEY_FONT_SIZE, 14).coerceIn(11, 24),
            configuredModelProviders = ModelProvider.entries.filterTo(linkedSetOf()) { providerApiKeys.containsKey(it) },
            harnessConfiguredModelProviders = harnessCredentials.modelProviders,
            customModelProviders = customProviders,
            configuredCustomModelProviders = customProviders.mapNotNullTo(linkedSetOf()) { provider ->
                provider.id.takeIf(customProviderApiKeys::containsKey)
            },
            harnessConfiguredCustomModelProviders = harnessCredentials.customProviderIds,
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
        overlayBallEnabledUpdate: Boolean? = settings.overlayBallEnabled,
        harnessPermissionModeUpdate: HarnessPermissionMode? = settings.harnessPermissionMode,
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
        overlayBallEnabledUpdate?.let { editor.putBoolean(KEY_OVERLAY_BALL, it) }
        harnessPermissionModeUpdate?.let { editor.putString(KEY_HARNESS_PERMISSION_MODE, it.wireValue) }
        if (encryptedCredentials == null) editor.remove(KEY_PROVIDER_CREDENTIALS)
        else editor.putString(KEY_PROVIDER_CREDENTIALS, encryptedCredentials)
        if (encryptedCustomCredentials == null) editor.remove(KEY_CUSTOM_PROVIDER_CREDENTIALS)
        else editor.putString(KEY_CUSTOM_PROVIDER_CREDENTIALS, encryptedCustomCredentials)
        val committed = editor.commit()
        if (!committed) throw RuntimeFailure("SETTINGS_WRITE_FAILED", "无法保存运行时设置")
        val harnessCredentials = harnessCredentialStatus(customProviders)
        return settings.copy(
            // Omitted updates are read after commit so a concurrent native-menu hide is preserved.
            overlayBallEnabled = overlayBallEnabledUpdate ?: overlayBallEnabled(),
            harnessPermissionMode = harnessPermissionModeUpdate ?: harnessPermissionMode(),
            configuredModelProviders = ModelProvider.entries.filterTo(linkedSetOf()) { providerApiKeys.containsKey(it) },
            harnessConfiguredModelProviders = harnessCredentials.modelProviders,
            customModelProviders = customProviders,
            configuredCustomModelProviders = customProviders.mapNotNullTo(linkedSetOf()) { provider ->
                provider.id.takeIf(customProviderApiKeys::containsKey)
            },
            harnessConfiguredCustomModelProviders = harnessCredentials.customProviderIds,
        )
    }

    private fun harnessCredentialStatus(customProviders: List<CustomModelProvider>): HarnessCredentialStatus =
        HarnessCredentialStatusReader.read(harnessCredentialsFile, customProviders)

    /**
     * Harness 自己的凭据文档（`$DSH_HOME/.credentials.yaml`，访客内 `/root/.dsh/.credentials.yaml`）。
     *
     * 官方前端「模型页」写的就是这一份；它也在 [RuntimePreservePolicy] 的保留白名单里，
     * 因此 App 侧写入的是**同一份**用户数据，不引入第二处真源。
     */
    val harnessCredentialsFile = File(currentRoot, "${RuntimePreservePolicy.GUEST_DSH_HOME}/.credentials.yaml")

    /**
     * 访客侧 0600 环境文件（[RuntimeCommand.GUEST_SECRET_ENV_PATH] 对应的宿主路径）。
     *
     * 与 `launcher-providers.patch.json` 同目录、同样每次启动重新生成，不在保留白名单内 ——
     * 运行时升级换代时随旧根目录一起消失。
     */
    val runtimeSecretFile = File(currentRoot, RuntimeCommand.GUEST_SECRET_ENV_PATH.removePrefix("/"))

    /**
     * 把本次启动的秘密投递到访客侧，返回**只含位置**的投递描述。
     *
     * 分两路：
     *  - **模型凭据优先并写 [harnessCredentialsFile]（0600）**。pinned dsh 的
     *    `@deepseek-ai/dsh-credentials-local` 分层是
     *    「继承的进程环境 > `$DSH_HOME/.credentials.yaml` > `.env`」，因此**不设同名环境变量**时
     *    dsh 就从它自己的凭据文档读取，取值连进程环境都不进。这条路径同时修掉一个既有症状：
     *    环境层存在时 dsh 把该引用判为**只读**，模型页对同一把 key 的保存会直接报错。
     *  - **每次启动生成的临时令牌**（`DSH_MOBILE_AUTH_TOKEN` / `DSH_DEVICE_BRIDGE_TOKEN`）
     *    不能进那份跨升级保留的长期文档，写进访客侧 0600 环境文件，
     *    由 argv 里那层固定的 `sh` 包装 `source` 之后 exec 真正的入口。
     *
     * 凭据文档不可安全编辑（形态陌生、目录/文件不可信、写入失败）时**回落**：模型凭据改由同一个
     * 环境文件承载 —— 功能不变、argv 依旧只有路径。回落是刻意的：为了 argv 的清洁让 Harness 起不来
     * 是本末倒置，而两条路径的 argv 形态完全相同。
     *
     * 顺序：先落文件、再返回描述；`RuntimeCommand.prootArgv` 只使用描述里的路径。
     */
    @Synchronized
    internal fun prepareRuntimeSecrets(
        harnessAuthToken: String?,
        deviceBridgeAccess: DeviceBridgeAccess?,
    ): RuntimeSecretDelivery {
        if (harnessAuthToken != null && !RuntimeSecretPolicy.TOKEN_PATTERN.matches(harnessAuthToken)) {
            throw RuntimeFailure("HARNESS_AUTH_INVALID", "Harness 临时凭据无效")
        }
        deviceBridgeAccess?.let { access ->
            if (access.port !in 1024..65535 || !RuntimeSecretPolicy.TOKEN_PATTERN.matches(access.token)) {
                throw RuntimeFailure("DEVICE_BRIDGE_INVALID", "设备桥临时连接信息无效")
            }
        }
        val ephemeralSecrets = linkedMapOf<String, String>().apply {
            harnessAuthToken?.let { put(MOBILE_AUTH_TOKEN_ENV, it) }
            deviceBridgeAccess?.let { put(DEVICE_BRIDGE_TOKEN_ENV, it.token) }
        }
        val modelCredentials = modelCredentialValues()
        val deliveredByFile = modelCredentials.isNotEmpty() && writeHarnessCredentials(modelCredentials)
        val environmentValues = RuntimeSecretPolicy.route(modelCredentials, ephemeralSecrets, deliveredByFile)
        val delivery = RuntimeSecretPolicy.delivery(environmentValues, modelCredentials.size)
        writeRuntimeSecretFile(delivery)
        return delivery
    }

    /**
     * 尽力删除访客侧环境文件。
     *
     * 删除的是固定路径本身（`unlink` 不跟随符号链接）：访客里的同 uid 代码可以把这个路径换成
     * 符号链接，但被摘掉的只会是链接。删除失败不算错误 —— 临时令牌每次启动重新生成，
     * 残留文件里的旧值对下一次运行没有任何作用，且下次启动会整份重写。
     */
    fun deleteRuntimeSecrets() {
        try {
            Os.remove(runtimeSecretFile.absolutePath)
        } catch (_: ErrnoException) {
            // ENOENT 是常态（本次启动没有需要投递的秘密）。
        }
    }

    /** 本机保存的模型凭据：环境变量名 → 取值。只用于写入 0600 文件与 Harness 凭据文档。 */
    private fun modelCredentialValues(): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val providerApiKeys = providerApiKeysLocked()
        ModelProvider.entries.forEach { provider ->
            providerApiKeys[provider]?.let { values[provider.environmentVariable] = it }
        }
        val customProviders = customModelProvidersLocked()
        val customProviderApiKeys = customProviderApiKeysLocked(customProviders.mapTo(linkedSetOf()) { it.id })
        customProviders.forEach { provider ->
            customProviderApiKeys[provider.id]?.let { values[provider.environmentVariable] = it }
        }
        return values
    }

    /**
     * 把模型凭据并写进 Harness 自己的凭据文档。
     *
     * @return 文档现在确实承载了全部 [values]（因此不需要再把取值放进环境文件）。
     *   任何一步不确定都返回 false，由调用方回落到环境文件路径，绝不猜、也绝不覆盖可疑文件。
     *
     * 只并写 `refs` 段：`records`（OAuth 授权记录等）与全部注释、格式逐行保留，
     * 且**从不删除**已有条目 —— 用户可能在模型页配了另一把 key，删掉它是破坏用户数据。
     */
    private fun writeHarnessCredentials(values: Map<String, String>): Boolean = try {
        upsertHarnessCredentials(values)
    } catch (_: Throwable) {
        // 凭据文档不可用不是启动失败：回落到访客环境文件，功能不变、argv 依旧只有路径。
        false
    }

    /** [writeHarnessCredentials] 的本体；任何一步不确定都返回 false。 */
    private fun upsertHarnessCredentials(values: Map<String, String>): Boolean {
        val directory = harnessCredentialsFile.parentFile ?: return false
        if (!ensurePrivateDirectory(directory)) return false
        val existing = when (val document = readCredentialsDocument()) {
            is CredentialsDocument.Absent -> null
            is CredentialsDocument.Text -> document.text
            is CredentialsDocument.Untrusted -> return false
        }
        val updated = HarnessCredentialsDocument.upsert(existing, values) ?: return false
        // 文档已经承载全部凭据时一个字节都不动：避免与模型页/另一个实例的写入互相覆盖。
        if (updated == existing) return true
        return writePrivateFile(harnessCredentialsFile, updated.toByteArray(Charsets.UTF_8))
    }

    /**
     * 读取现有凭据文档原文。
     *
     * 只接受**不跟随符号链接**打开的常规文件，与 [HarnessCredentialStatusReader] 的信任模型一致。
     */
    private fun readCredentialsDocument(): CredentialsDocument {
        if (!RuntimeFiles.existsNoFollow(harnessCredentialsFile)) return CredentialsDocument.Absent
        val descriptor = try {
            Os.open(
                harnessCredentialsFile.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (_: ErrnoException) {
            return CredentialsDocument.Untrusted
        }
        return try {
            val stat = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(stat.st_mode) ||
                stat.st_size !in 0..HarnessCredentialsDocument.MAX_DOCUMENT_BYTES.toLong()
            ) {
                Os.close(descriptor)
                CredentialsDocument.Untrusted
            } else {
                val text = FileInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
                if (text.isBlank()) CredentialsDocument.Absent else CredentialsDocument.Text(text)
            }
        } catch (_: Exception) {
            try {
                Os.close(descriptor)
            } catch (_: Exception) {
                // FileInputStream owns the descriptor after successful construction.
            }
            CredentialsDocument.Untrusted
        }
    }

    /**
     * 写入访客侧秘密环境文件；本次启动没有秘密可投递时删除它。
     *
     * 写不进去就是受控启动失败：没有这份文件 dsh 会缺临时令牌起来，
     * 用户看到的是下游的「认证不可用」，把投递故障伪装成运行故障。
     */
    private fun writeRuntimeSecretFile(delivery: RuntimeSecretDelivery) {
        if (delivery.isEmpty) {
            deleteRuntimeSecrets()
            return
        }
        val directory = runtimeSecretFile.parentFile
            ?: throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "运行时秘密文件路径无效")
        if (!ensurePrivateDirectory(directory)) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "无法创建运行时秘密文件目录")
        }
        val bytes = RuntimeSecretPolicy.renderEnvironmentFile(delivery.environmentValues).toByteArray(Charsets.UTF_8)
        if (!writePrivateFile(runtimeSecretFile, bytes)) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "无法写入运行时秘密文件")
        }
    }

    /** 确保目录存在且为 0700 的真目录；不可信时返回 false 而不是抛异常（调用方要据此回落）。 */
    private fun ensurePrivateDirectory(directory: File): Boolean {
        if (RuntimeFiles.existsNoFollow(directory)) {
            if (!RuntimeFiles.isDirectoryNoFollow(directory)) return false
        } else if (!directory.mkdirs() && !RuntimeFiles.existsNoFollow(directory)) {
            return false
        }
        return try {
            Os.chmod(directory.absolutePath, 0x1c0)
            true
        } catch (_: ErrnoException) {
            false
        }
    }

    /**
     * 原子写入一个 0600 文件：同目录临时文件 → fsync → chmod → rename。
     *
     * 权限必须在 rename **之前**设好：dsh 的 `assertOwnerOnly` 会拒绝任何带 group/other 位的
     * 凭据文档并让插件激活失败，而「先 rename 再 chmod」会留下一个已经就位、权限还宽的窗口。
     */
    private fun writePrivateFile(file: File, bytes: ByteArray): Boolean {
        val directory = file.parentFile ?: return false
        // 去掉前导点再拼 `.name.new`：`.credentials.yaml` 的临时名因此是 `.credentials.yaml.new`，
        // 而不是看起来像父目录引用的 `..credentials.yaml.new`。
        val pending = File(directory, ".${file.name.trimStart('.')}.new")
        return try {
            if (RuntimeFiles.existsNoFollow(pending) && !pending.delete()) return false
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
            Os.rename(pending.absolutePath, file.absolutePath)
            true
        } catch (_: Throwable) {
            try {
                pending.delete()
            } catch (_: Throwable) {
                // 清理失败不影响返回值；下次启动会覆盖这个名字。
            }
            false
        }
    }

    /** 现有凭据文档的三种状态；`Untrusted` 一律拒绝编辑，绝不覆盖。 */
    private sealed interface CredentialsDocument {
        /** 不存在或为空：可以新建。 */
        object Absent : CredentialsDocument

        /** 现有文档原文。 */
        class Text(val text: String) : CredentialsDocument

        /** 存在但不是可安全编辑的常规文件（符号链接、目录、超限、读取失败）。 */
        object Untrusted : CredentialsDocument
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
            // 展示字段：装上的是哪个 dsh 版本，版本列表不必进访客读包内 package.json 就能显示。
            .apply { manifest.dshVersion?.let { put("dshVersion", it) } }
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

    /**
     * 上一版本（被换下来的那一份）的清单。
     *
     * 与 [installedManifest] 采用同一套读取规则：清单必须与根目录同时存在，
     * 且必须是常规文件、不跟随符号链接、大小受限。任一条不满足都视为「没有上一版本」，
     * 界面据此不展示切换入口——版本信息宁可少报，也不能指向一个不可用的根目录。
     */
    @Synchronized
    fun retainedManifest(): RuntimeManifest? {
        if (retainedCacheLoaded) return retainedCache
        retainedCache = readManifestAt(retainedRoot, retainedManifest)
        retainedCacheLoaded = true
        return retainedCache
    }

    @Synchronized
    fun invalidateRetainedManifest() {
        retainedCache = null
        retainedCacheLoaded = false
    }

    private fun readInstalledManifest(): RuntimeManifest? = readManifestAt(currentRoot, currentManifest)

    private fun readManifestAt(root: File, manifestFile: File): RuntimeManifest? {
        if (!RuntimeFiles.isDirectoryNoFollow(root)) return null
        val descriptor = try {
            Os.open(manifestFile.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
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
        private const val KEY_OVERLAY_BALL = "overlay_ball_enabled"
        private const val KEY_HARNESS_PERMISSION_MODE = "harness_permission_mode"
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
        // 只经访客环境文件投递的两个临时令牌；取值每次启动重新生成，绝不进 .credentials.yaml。
        private const val MOBILE_AUTH_TOKEN_ENV = "DSH_MOBILE_AUTH_TOKEN"
        private const val DEVICE_BRIDGE_TOKEN_ENV = "DSH_DEVICE_BRIDGE_TOKEN"
    }
}
