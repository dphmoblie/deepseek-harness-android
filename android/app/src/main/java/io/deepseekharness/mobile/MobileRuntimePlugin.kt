package io.deepseekharness.mobile

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import io.deepseekharness.mobile.overlay.OverlayBallPolicy
import io.deepseekharness.mobile.runtime.HarnessKeepAlivePolicy
import io.deepseekharness.mobile.runtime.HarnessPermissionMode
import io.deepseekharness.mobile.runtime.HarnessOutputTailSource
import io.deepseekharness.mobile.runtime.clampHarnessTailBytes
import io.deepseekharness.mobile.runtime.MailboxExportOutcome
import io.deepseekharness.mobile.runtime.MailboxImportOutcome
import io.deepseekharness.mobile.runtime.MailboxState
import io.deepseekharness.mobile.runtime.MobileRuntimeController
import io.deepseekharness.mobile.runtime.DeviceBridgeAccess
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeIntent
import io.deepseekharness.mobile.runtime.RuntimeKeepAliveSnapshot
import io.deepseekharness.mobile.runtime.RuntimeMailbox
import io.deepseekharness.mobile.runtime.RuntimePhase
import io.deepseekharness.mobile.runtime.RuntimeSelfCheckPolicy
import io.deepseekharness.mobile.runtime.RuntimeSettings
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import io.deepseekharness.mobile.runtime.RuntimeStorageDirs
import io.deepseekharness.mobile.runtime.RuntimeValidation
import io.deepseekharness.mobile.runtime.RuntimeWorkspaceFiles
import io.deepseekharness.mobile.runtime.StorageDirCodes
import io.deepseekharness.mobile.runtime.StorageDirStatus
import io.deepseekharness.mobile.runtime.StorageDirsState
import io.deepseekharness.mobile.runtime.audit.AuditEvent
import io.deepseekharness.mobile.runtime.audit.AuditResult
import io.deepseekharness.mobile.runtime.audit.PrivateAuditLog
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticExport
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticPolicy
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticState
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticText
import io.deepseekharness.mobile.shizuku.DeviceCommand
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import io.deepseekharness.mobile.shizuku.ShizukuState
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.security.SecureRandom
import java.util.Base64
import java.io.File
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.withLock

internal fun optionalOverlayBallEnabled(data: JSONObject): Boolean? {
    if (!data.has("overlayBallEnabled")) return null
    val value = data.opt("overlayBallEnabled")
    if (value !is Boolean) throw RuntimeFailure("SETTINGS_INVALID", "悬浮球开关格式无效")
    return value
}

/** 权限：应用私有桥接；省略保留原值，null、非法类型及未知模式一律拒绝。 */
internal fun optionalHarnessPermissionMode(data: JSONObject): HarnessPermissionMode? =
    if (data.has("harnessPermissionMode")) HarnessPermissionMode.parse(data.opt("harnessPermissionMode")) else null

/**
 * 投递区导出的「指定子目录」参数。
 *
 * 省略、`null` 或空白一律等价于「整个工作区」；**非字符串直接拒绝**（不猜、不转字符串）。
 * 路径本身（绝对路径、`..`、`.` 分段、超长、超深）由 `RuntimeMailboxPolicy.normalizeSubdirectory`
 * 统一校验，这里只管类型，避免两套规则漂移。
 */
internal fun optionalMailboxSubdirectory(data: JSONObject): String? {
    if (!data.has("subdirectory")) return null
    val value = data.opt("subdirectory")
    if (value == null || value == JSONObject.NULL) return null
    if (value !is String) throw RuntimeFailure("MAILBOX_INPUT_INVALID", "投递区导出目标格式无效")
    return value
}

/** 前台服务通知权限别名；Android 13 以下系统不需要该权限。 */
private const val NOTIFICATION_PERMISSION_ALIAS = "notifications"

/*
 * 存储权限别名。
 * 媒体读取自 Android 13 起由 READ_MEDIA_* 取代 READ_EXTERNAL_STORAGE，两者不能塞进同一个
 * 别名：在不适用的系统版本上 checkSelfPermission 恒为拒绝，会让状态显示永远不正确。
 * 因此按系统版本分别声明、分别申请，状态查询也只看 API 对应的那一个。
 */
private const val MEDIA_IMAGES_ALIAS = "mediaImages"
private const val MEDIA_VIDEO_ALIAS = "mediaVideo"
private const val LEGACY_STORAGE_ALIAS = "legacyStorage"

/**
 * 本进程是否已经记录过「上次非正常结束」。
 *
 * 每个进程只记一次：Activity 重建会重复走 load()，但「上次是怎么死的」只与进程启动有关。
 * 放在文件级是刻意的 —— 它必须随进程重置，而 RuntimeHost 里的状态会被跨 Activity 复用。
 */
@Volatile
private var uncleanExitRecorded = false

@CapacitorPlugin(
    name = "MobileRuntime",
    permissions = [
        Permission(
            alias = NOTIFICATION_PERMISSION_ALIAS,
            strings = [Manifest.permission.POST_NOTIFICATIONS],
        ),
        Permission(alias = MEDIA_IMAGES_ALIAS, strings = [Manifest.permission.READ_MEDIA_IMAGES]),
        Permission(alias = MEDIA_VIDEO_ALIAS, strings = [Manifest.permission.READ_MEDIA_VIDEO]),
        Permission(alias = LEGACY_STORAGE_ALIAS, strings = [Manifest.permission.READ_EXTERNAL_STORAGE]),
    ],
)
class MobileRuntimePlugin : Plugin() {
    private lateinit var controller: MobileRuntimeController
    private lateinit var auditLog: PrivateAuditLog
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val destroying = AtomicBoolean(false)
    private val harnessStartScheduled = AtomicBoolean(false)
    private val harnessStartGeneration = AtomicLong(0)
    private val auditedOperationLock = ReentrantLock()

    /**
     * 运行时事件出口。运行时由 [RuntimeHost] 跨插件实例持有，因此这里必须是稳定的
     * 订阅者对象：插件销毁后取消订阅，运行时不会继续向已销毁的 WebView 派发事件。
     */
    private val eventSink = PluginEventSink()

    companion object {
        private const val DEVICE_COMMAND_TIMEOUT_MS = 60_000L
        private const val DESTROY_WAIT_SECONDS = 10L

        /** 受控错误码：大写字母、数字与下划线，与审计日志的策略一致。 */
        private val CONTROLLED_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    }

    /** 权限：应用内桥接；校验语言白名单；仅返回保存结果，不返回私有配置。 */
    @PluginMethod
    fun setAppLanguage(call: PluginCall) {        val language = call.getString("language").orEmpty()
        if (language != "zh-CN" && language != "en") {
            call.reject("不支持的应用语言", "LANGUAGE_INVALID")
            return
        }
        try {
            // 先持久化应用语言，再尽力同步到已安装运行时；运行时未安装时该调用为空操作。
            val saved = AppLanguage.save(context, language) &&
                io.deepseekharness.mobile.runtime.RuntimeStore(context).syncHarnessLocale(language)
            if (saved) call.resolve()
            else call.reject("无法保存应用语言", "LANGUAGE_SAVE_FAILED")
        } catch (failure: RuntimeFailure) {
            // 受控错误码（如 LANGUAGE_INVALID / LANGUAGE_SYNC_FAILED）直接回传，便于前端区分提示。
            call.reject(failure.message ?: "无法同步 Harness 语言", failure.code)
        } catch (_: Exception) {
            call.reject("无法保存应用语言", "LANGUAGE_SAVE_FAILED")
        }
    }

    /**
     * 保存应用主题模式并把它落到当前窗口的状态栏上（登记册 5.4 的原生半边）。
     *
     * 为什么需要它：主题选择是 Web 侧的偏好，但**状态栏属于窗口，Web 改不了**——
     * `meta[name=theme-color]` 只有 Chrome for Android 认，Android WebView 不认。
     * 不接这一步，真机上选浅色主题时状态栏仍是深色。
     *
     * 只存 mode 不存「深/浅」：`system` 模式下系统在用户使用期间切换深色也要跟随，
     * 存结论会把那一刻固化成显式选择。解析由 [AppThemePreference.apply] 每次按 `uiMode` 现算。
     */
    @PluginMethod
    fun setAppTheme(call: PluginCall) {
        val mode = call.getString("mode").orEmpty()
        if (mode != AppThemePreference.MODE_SYSTEM &&
            mode != AppThemePreference.MODE_LIGHT &&
            mode != AppThemePreference.MODE_DARK
        ) {
            call.reject("不支持的主题模式", "THEME_INVALID")
            return
        }
        val saved = try {
            AppThemePreference.save(context, mode)
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!saved) {
            call.reject("无法保存主题", "THEME_SAVE_FAILED")
            return
        }
        // 落盘成功后再改窗口；没有可用的 Activity（例如后台调用）不算失败，下次 onResume 会补上。
        (activity as? android.app.Activity)?.let { AppThemePreference.apply(it) }
        call.resolve()
    }

    override fun load() {
        auditLog = PrivateAuditLog(context)
        recordAudit(AuditEvent.PLUGIN_LOAD, AuditResult.STARTED)
        try {
            // 运行时由 RuntimeHost 跨插件实例持有：前台服务保留的会话在这里被复用，
            // 不会因为 WebView 重建而重新安装或重新生成认证凭据。
            controller = RuntimeHost.acquire(context, eventSink)
            applyKeepScreenAwake(controller.store.keepScreenAwake())
        } catch (error: Throwable) {
            // 插件注册失败会让整个管理界面失去原生桥：这里必须释放已经登记的订阅，
            // 否则 RuntimeHost 永远判不出「没有订阅者」，运行时就再也释放不掉。
            RuntimeHost.detachPluginSink(eventSink)
            recordAudit(AuditEvent.PLUGIN_LOAD, AuditResult.FAILED)
            throw error
        }
        // 设备桥只服务设备 Shell，属于可选能力。保活生效时 Harness 进程仍在运行，
        // 桥本应由 RuntimeHost 复用；即便这里真的失败，也绝不能让插件注册失败——
        // 那正是「点通知后设置页打不开」的成因。
        var bridgeReady = true
        try {
            ensureDeviceBridge()
        } catch (_: Throwable) {
            bridgeReady = false
            android.util.Log.w("dsh-runtime", "device bridge unavailable; device shell disabled")
        }
        // 悬浮球可以在插件加载时恢复，keep-alive 不能：后者要求运行时确实在跑，
        // 没跑就启动只会留下一个无法解释的通知；悬浮球与运行时无关，只取决于
        // 「用户开关 + 系统权限」（syncOverlayBallService 内部判断），所以重启应用后
        // 必须自动把球恢复出来，否则用户强行停止后重开，球要再保存一次设置才会出现。
        // 与设备桥同理：恢复失败（如后台启动前台服务被系统限制）不能影响插件注册，
        // 详细结果由 syncOverlayBallService 自己记入诊断日志。
        try {
            syncOverlayBallService(controller.store.overlayBallEnabled())
        } catch (_: Throwable) {
            android.util.Log.w("dsh-runtime", "overlay ball restore failed")
        }
        recordAudit(AuditEvent.PLUGIN_LOAD, AuditResult.SUCCEEDED)
        recordUncleanExitIfNeeded()
        diagnostics()?.record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.APP_START,
            mapOf(
                "result" to "ok",
                "active" to bridgeReady.toString(),
                "enabled" to RuntimeHost.isForegroundServiceActive().toString(),
            ),
        )
    }

    /**
     * 回到前台时重新对齐悬浮球服务。
     *
     * 「显示在其他应用之上」是特殊权限，只能由用户到系统设置页手动开启，而且**授权不会结束进程**：
     * 用户按界面引导去授权再返回时，插件与运行时都还活着，不会重走 [load]；前端对悬浮球状态
     * 只读不写，也没有别的入口能把球拉起来。因此这里在主界面每次 onResume 时对齐一次，
     * 补上「权限从已撤销恢复为已授予」的复位路径。
     *
     * 反向变化同样由这次对齐覆盖：权限在运行期被撤销时，同一次对齐会停掉服务并撤掉通知，
     * 不留「球没了、通知还在」的状态。刻意不做轮询：对齐只发生在用户真正回到应用时。
     * 与 [load] 一致，失败原因由 [syncOverlayBallService] 记入诊断日志，不阻塞界面。
     */
    override fun handleOnResume() {
        super.handleOnResume()
        if (destroying.get() || !::controller.isInitialized) return
        try {
            syncOverlayBallService(controller.store.overlayBallEnabled())
        } catch (_: Throwable) {
            android.util.Log.w("dsh-runtime", "overlay ball resume sync failed")
        }
    }

    /**
     * Capacitor 插件销毁（Activity 销毁，含划掉最近任务）。
     *
     * 这里只回收插件自己拥有的资源（线程池、发起中的设备命令）并注销事件订阅者：
     * 运行时与设备桥都由 [RuntimeHost] 进程级持有，前台服务仍在负责时不得立即
     * shutdown 或拆桥，否则「后台保持 Harness」会形同虚设、guest 注入的桥端口也会失效。
     * 两者都不再持有时由 RuntimeHost 释放，语义与旧实现一致。
     */
    override fun handleOnDestroy() {
        if (!destroying.compareAndSet(false, true)) return
        harnessStartGeneration.incrementAndGet()
        recordAudit(AuditEvent.PLUGIN_DESTROY, AuditResult.STARTED)
        var result = AuditResult.SUCCEEDED
        try {
            executor.shutdownNow()
                .filterIsInstance<PluginTask>()
                .forEach { task -> task.rejectRuntimeClosed() }
            // 只终结本实例发起中的设备命令：发起它们的 WebView 已经不在了。
            RuntimeHost.cancelDeviceCommands()
        } catch (_: Throwable) {
            result = AuditResult.FAILED
        }
        try {
            RuntimeHost.detachPluginSink(eventSink)
        } catch (_: Throwable) {
            result = AuditResult.FAILED
            // 销毁流程继续；不记录终端数据或进程细节。
        } finally {
            try {
                if (!executor.awaitTermination(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    result = AuditResult.FAILED
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                result = AuditResult.FAILED
            }
            try {
                super.handleOnDestroy()
            } catch (error: Throwable) {
                result = AuditResult.FAILED
                throw error
            } finally {
                recordAudit(AuditEvent.PLUGIN_DESTROY, result)
                // 记录销毁结果与此刻运行时是否仍被前台服务保留：这正是排查
                // 「划掉最近任务后运行时是否还在」时需要的第一手信息。
                diagnostics()?.record(
                    DiagnosticLevel.INFO,
                    DiagnosticEvent.APP_DESTROY,
                    mapOf(
                        "result" to result.name.lowercase(),
                        "active" to RuntimeHost.isForegroundServiceActive().toString(),
                    ),
                )
            }
        }
    }

    /** 权限：应用私有桥接；白名单操作、包名与条目标识校验；只返回插件元数据。 */
    @PluginMethod
    fun managePlugins(call: PluginCall) {
        execute(call) {
            val operation = call.getString("operation").orEmpty()
            val event = when (operation) {
                "list" -> AuditEvent.PLUGIN_LIST
                "enable", "child" -> AuditEvent.PLUGIN_ENABLE
                "update" -> AuditEvent.PLUGIN_UPDATE
                else -> throw RuntimeFailure("PLUGIN_INPUT_INVALID", "插件操作无效")
            }
            audited(event) {
                controller.managePlugins(operation, call.getString("id"), call.getBoolean("enabled"), call.getString("childId"))
            }
        }
    }

    /**
     * 权限：应用内桥接。
     * 运行时自检：`check` 只读（可用空间、dsh 版本与十一项检查），`repair` 只补可执行位并创建附件目录。
     * 只回传受控枚举与计数，不含路径、文件内容或原始报错文本。
     */
    @PluginMethod
    fun runRuntimeSelfCheck(call: PluginCall) {
        execute(call) {
            val operation = RuntimeSelfCheckPolicy.operation(call.getString("operation"))
                ?: throw RuntimeFailure("SELF_CHECK_INPUT_INVALID", "自检操作无效")
            requireSelfCheck(operation)
        }
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        resolveWhileActive(call) { controller.state().toJs() }
    }

    @PluginMethod
    fun getSettings(call: PluginCall) {
        resolveWhileActive(call) { controller.store.settings().toJs() }
    }

    @PluginMethod
    fun saveSettings(call: PluginCall) {
        execute(call) {
            val fontSize = call.getInt("terminalFontSize")
                ?: throw RuntimeFailure("SETTINGS_INVALID", "终端字号缺失")
            val providerApiKeyUpdates = RuntimeValidation.providerApiKeyUpdates(call.getObject("providerApiKeys"))
                .toMutableMap()
            call.getString("apiKey")?.trim()?.takeIf { it.isNotEmpty() }?.let { legacyKey ->
                providerApiKeyUpdates.putIfAbsent(
                    io.deepseekharness.mobile.runtime.ModelProvider.DEEPSEEK,
                    RuntimeValidation.requireProviderApiKey(legacyKey),
                )
            }
            val clearedProviderApiKeys = RuntimeValidation.clearedProviderApiKeys(call.getArray("clearProviderApiKeys"))
            val customProviders = RuntimeValidation.customModelProviders(call.getArray("customModelProviders"))
            val allowedCustomIds = customProviders.mapTo(linkedSetOf()) { it.id }
            val customProviderApiKeyUpdates = RuntimeValidation.customProviderApiKeyUpdates(
                call.getObject("customProviderApiKeys"),
                allowedCustomIds,
            )
            val clearedCustomProviderApiKeys = RuntimeValidation.clearedCustomProviderApiKeys(
                call.getArray("clearCustomProviderApiKeys"),
                allowedCustomIds,
            )
            val overlayBallEnabledUpdate = optionalOverlayBallEnabled(call.data)
            val harnessPermissionModeUpdate = optionalHarnessPermissionMode(call.data)
            val settings = RuntimeValidation.settings(
                call.getString("manifestUrl"),
                call.getString("manifestSha256"),
                call.getBoolean("keepScreenAwake", false) ?: false,
                fontSize,
                call.getBoolean("autoLaunch", true) ?: true,
                call.getBoolean("keepRuntimeInBackground", false) ?: false,
                // 省略值只作为构造设置对象时的占位；是否写入由下面的可空更新参数决定。
                overlayBallEnabledUpdate ?: false,
                harnessPermissionModeUpdate ?: HarnessPermissionMode.WORKSPACE_WRITE,
            )
            val saved = controller.saveSettings(
                settings,
                providerApiKeyUpdates,
                clearedProviderApiKeys,
                customProviders,
                customProviderApiKeyUpdates,
                clearedCustomProviderApiKeys,
                overlayBallEnabledUpdate = overlayBallEnabledUpdate,
                harnessPermissionModeUpdate = harnessPermissionModeUpdate,
            )
            applyKeepScreenAwake(saved.keepScreenAwake)
            syncKeepAliveService(saved.keepRuntimeInBackground)
            // 菜单可能与本次保存并发关闭悬浮球；服务启停以同步瞬间的单字段真值为准。
            syncOverlayBallService(controller.store.overlayBallEnabled())
            saved.toJs()
        }
    }

    @PluginMethod
    fun install(call: PluginCall) {
        execute(call) {
            audited(AuditEvent.RUNTIME_INSTALL) {
                val settings = controller.store.settings()
                val source = RuntimeValidation.source(
                    call.getString("manifestUrl") ?: settings.manifestUrl,
                    call.getString("manifestSha256") ?: settings.manifestSha256,
                )
                controller.install(source)
                null
            }
        }
    }

    @PluginMethod
    fun startHarness(call: PluginCall) {
        if (!harnessStartScheduled.compareAndSet(false, true)) {
            resolveWhileActive(call) { controller.state().toJs() }
            return
        }
        val generation = harnessStartGeneration.get()
        val accepted = execute(call) {
            try {
                audited(AuditEvent.RUNTIME_START) {
                    if (generation != harnessStartGeneration.get()) return@audited controller.state().toJs()
                    ensureDeviceBridge()
                    // 应用语言可能在运行时创建 settings 文件之前就已选择，每次启动 Harness 前重放一次。
                    controller.store.syncHarnessLocale(AppLanguage.current(context))
                    try {
                        controller.startHarness().toJs().also { snapshot ->
                            // Harness 启动成功后才按设置提升前台优先级；失败时不留空转服务。
                            // 这里直接读开关，避免为读设置而触发凭据解密。
                            syncKeepAliveService(controller.store.keepRuntimeInBackground())
                            diagnostics()?.record(
                                DiagnosticLevel.INFO,
                                DiagnosticEvent.HARNESS_START,
                                mapOf("result" to "ok", "phase" to snapshot.optString("phase")),
                            )
                        }
                    } catch (failure: Throwable) {
                        // 启动失败码是排障的核心线索：它是受控枚举，不含任何凭据或路径。
                        diagnostics()?.record(
                            DiagnosticLevel.ERROR,
                            DiagnosticEvent.HARNESS_START,
                            mapOf("result" to "failed", "code" to failureCode(failure)),
                        )
                        throw failure
                    }
                }
            } finally {
                harnessStartScheduled.set(false)
            }
        }
        if (!accepted) harnessStartScheduled.set(false)
    }

    @PluginMethod
    fun openHarness(call: PluginCall) {
        resolveWhileActive(call) {
            val access = controller.openHarnessAccess()
            AppAuthenticationState.authorizeHarnessLaunch(access)
            val intent = Intent(context, HarnessActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (error: Throwable) {
                AppAuthenticationState.revokeHarness()
                throw error
            }
            null
        }
    }

    @PluginMethod
    fun stopRuntime(call: PluginCall) {
        harnessStartGeneration.incrementAndGet()
        requestHarnessStartCancellation()
        stopKeepAliveService()
        execute(call) {
            audited(AuditEvent.RUNTIME_STOP) {
                // 设备桥是进程级资源：停止 Harness 不等于释放运行时，桥必须留着，
                // 否则 supervisor 里保存的端口与令牌会变成指向死端口的陈旧配置。
                // 只终结发起中的设备命令——它们的终端会话即将被关闭。
                RuntimeHost.cancelDeviceCommands()
                controller.stopRuntime().toJs().also { snapshot ->
                    diagnostics()?.record(
                        DiagnosticLevel.INFO,
                        DiagnosticEvent.HARNESS_STOP,
                        mapOf("result" to "ok", "phase" to snapshot.optString("phase")),
                    )
                }
            }
        }
    }

    @PluginMethod
    fun reset(call: PluginCall) {
        if (call.getString("confirmation") == "RESET_RUNTIME") {
            harnessStartGeneration.incrementAndGet()
            requestHarnessStartCancellation()
            // 重置会清除运行时层，必须先撤掉前台服务，避免服务保留已失效的运行时。
            stopKeepAliveService()
        }
        execute(call) {
            audited(AuditEvent.RUNTIME_RESET) {
                val confirmation = call.getString("confirmation")
                if (confirmation != "RESET_RUNTIME") {
                    throw RuntimeFailure("RESET_CONFIRMATION_INVALID", "重置确认文本无效")
                }
                RuntimeHost.cancelDeviceCommands()
                controller.reset(confirmation).toJs()
            }
        }
    }

    /**
     * 取得进程级设备桥。
     *
     * 构建与配置只在 RuntimeHost 首次创建时执行一次：保活生效时 Harness 进程仍在运行，
     * `RuntimeSupervisor.configureDeviceBridge` 会拒绝重复配置（`RUNTIME_BUSY`），
     * 而 Activity 重建时重复配置正是「插件注册失败」的根因。
     */
    private fun ensureDeviceBridge() {
        if (RuntimeHost.deviceBridgeOrNull() != null) {
            // 复用的是前台服务保留下来的同一个桥：这正是保活生效时的正常路径。
            diagnostics()?.record(
                DiagnosticLevel.INFO,
                DiagnosticEvent.DEVICE_BRIDGE,
                mapOf("result" to "reused"),
            )
            return
        }
        RuntimeHost.acquireDeviceBridge {
            val bridgeTokenBytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val bridgeToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bridgeTokenBytes)
            bridgeTokenBytes.fill(0)
            val bridge = DeviceBridgeServer(
                shizuku = controller.terminals.shizuku,
                runner = RuntimeHost.deviceCommands(),
                token = bridgeToken,
            )
            try {
                bridge.start()
                controller.configureDeviceBridge(DeviceBridgeAccess(bridge.localPort, bridgeToken))
            } catch (error: Throwable) {
                diagnostics()?.record(
                    DiagnosticLevel.WARN,
                    DiagnosticEvent.DEVICE_BRIDGE,
                    mapOf("result" to "failed", "code" to failureCode(error)),
                )
                bridge.stop()
                throw error
            }
            diagnostics()?.record(
                DiagnosticLevel.INFO,
                DiagnosticEvent.DEVICE_BRIDGE,
                mapOf("result" to "created"),
            )
            bridge
        }
    }

    /**
     * 受控失败码：RuntimeFailure 携带固定枚举码，其他异常统一归一化为 INTERNAL_ERROR。
     * 绝不写入异常消息——那里可能包含路径或凭据片段。
     */
    private fun failureCode(error: Throwable): String =
        (error as? RuntimeFailure)?.code?.takeIf { code -> code.matches(CONTROLLED_CODE) } ?: "INTERNAL_ERROR"

    /** 诊断日志；控制器尚未就绪时返回 null（排障不得影响主流程）。 */
    private fun diagnostics() = if (this::controller.isInitialized) controller.store.diagnostics else null

    /**
     * 诊断日志（必需）。
     * 只有插件成功加载后才可能被调用的方法使用它；未就绪即属于运行时已关闭。
     */
    private fun requireDiagnostics() = diagnostics()
        ?: throw RuntimeFailure("RUNTIME_CLOSED", "本机运行时正在关闭")

    /**
     * 运行时自检（必需）。
     * 自检实例由控制器持有（同一个 store 与同一把生命周期锁）：插件侧绝不自行构造 RuntimeStore，
     * 自检也不会与安装、重置或 Harness 启停并发。
     */
    private fun requireSelfCheck(operation: String): JSObject {
        if (this::controller.isInitialized) return controller.runRuntimeSelfCheck(operation)
        throw RuntimeFailure("RUNTIME_CLOSED", "本机运行时正在关闭")
    }

    /**
     * 记录「上次进程非正常结束」。
     *
     * 进程被系统杀死（强制停止、内存回收、厂商清理，以及**安装新版本 APK**）时不会走到
     * handleOnDestroy，持久化的运行意图会停留在 RUNNING。因此判据是：上次意图为 RUNNING、
     * 而本进程并没有持有正在运行的 Harness。
     *
     * 这是排查「会话为什么会坏」时最需要的第一手证据 —— 一次被硬中断的 agent 轮次会留下
     * 悬空的 tool_calls，之后每一轮都会因历史不合法而失败。
     */
    private fun recordUncleanExitIfNeeded() {
        if (uncleanExitRecorded) return
        uncleanExitRecorded = true
        val log = diagnostics() ?: return
        if (controller.store.runtimeIntentRecord().intent != RuntimeIntent.RUNNING) return
        val phase = controller.state().phase
        if (phase == RuntimePhase.RUNNING) return
        log.record(
            DiagnosticLevel.WARN,
            DiagnosticEvent.RECOVERY,
            mapOf("reason" to "unclean_exit", "phase" to phase.wireValue),
        )
    }

    @PluginMethod
    fun createTerminal(call: PluginCall) {
        execute(call) {
            audited(AuditEvent.TERMINAL_OPEN) {
                val kind = call.getString("kind") ?: throw RuntimeFailure("TERMINAL_KIND_INVALID", "终端类型缺失")
                val columns = call.getInt("columns") ?: throw RuntimeFailure("TERMINAL_SIZE_INVALID", "终端列数缺失")
                val rows = call.getInt("rows") ?: throw RuntimeFailure("TERMINAL_SIZE_INVALID", "终端行数缺失")
                JSObject().put("sessionId", controller.createTerminal(kind, columns, rows))
            }
        }
    }

    @PluginMethod
    fun writeTerminal(call: PluginCall) {
        execute(call) {
            val sessionId = call.getString("sessionId") ?: throw RuntimeFailure("SESSION_ID_INVALID", "终端会话标识缺失")
            val dataBase64 = call.getString("dataBase64") ?: throw RuntimeFailure("TERMINAL_INPUT_INVALID", "终端输入缺失")
            controller.writeTerminal(sessionId, dataBase64)
            null
        }
    }

    @PluginMethod
    fun resizeTerminal(call: PluginCall) {
        execute(call) {
            val sessionId = call.getString("sessionId") ?: throw RuntimeFailure("SESSION_ID_INVALID", "终端会话标识缺失")
            val columns = call.getInt("columns") ?: throw RuntimeFailure("TERMINAL_SIZE_INVALID", "终端列数缺失")
            val rows = call.getInt("rows") ?: throw RuntimeFailure("TERMINAL_SIZE_INVALID", "终端行数缺失")
            controller.resizeTerminal(sessionId, columns, rows)
            null
        }
    }

    @PluginMethod
    fun closeTerminal(call: PluginCall) {
        execute(call) {
            audited(AuditEvent.TERMINAL_CLOSE) {
                val sessionId = call.getString("sessionId") ?: throw RuntimeFailure("SESSION_ID_INVALID", "终端会话标识缺失")
                controller.closeTerminal(sessionId)
                null
            }
        }
    }

    @PluginMethod
    fun execDeviceCommand(call: PluginCall) {
        execute(call) {
            val sessionId = call.getString("sessionId") ?: throw RuntimeFailure("SESSION_ID_INVALID", "终端会话标识缺失")
            val commandName = call.getString("command") ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备命令缺失")
            val command = DeviceCommand.fromName(commandName) ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备命令不支持")
            if (!controller.hasDeviceSession(sessionId)) {
                throw RuntimeFailure("SESSION_NOT_FOUND", "设备 Shell 会话不存在或已结束")
            }
            val result = RuntimeHost.deviceCommands()
                .execute(sessionId, command, call.getString("param") ?: "", DEVICE_COMMAND_TIMEOUT_MS)
            JSObject()
                .put("ok", result.ok)
                .put("exitCode", result.exitCode)
                .put("text", result.text)
                .put("truncated", result.truncated)
                .also { if (result.errorCode != null) it.put("errorCode", result.errorCode) }
        }
    }

    @PluginMethod
    fun getShizukuState(call: PluginCall) {
        resolveWhileActive(call) { controller.shizukuState().toJs() }
    }

    @PluginMethod
    fun requestShizukuPermission(call: PluginCall) {
        execute(call) { requestShizukuPermissionAudited().toJs() }
    }

    @PluginMethod
    fun connectShizuku(call: PluginCall) {
        execute(call) { controller.connectShizuku().toJs() }
    }

    @PluginMethod
    fun openShizuku(call: PluginCall) {
        resolveWhileActive(call) {
            controller.openShizukuManager()
            null
        }
    }

    /**
     * 权限：应用内桥接。
     * 只返回后台保持与恢复状态（布尔值、枚举、时间戳），不含 URL、凭据或终端内容。
     */
    @PluginMethod
    fun getKeepAliveState(call: PluginCall) {
        resolveWhileActive(call) {
            controller.keepAliveSnapshot(RuntimeHost.isForegroundServiceActive()).toJs()
        }
    }

    /**
     * 权限：应用内桥接；仅申请前台服务通知权限。
     * Android 13 以下不需要该权限，直接返回已授予；被拒绝时只返回结果，
     * 不阻止 Harness 运行，由界面提示用户自行在系统设置中开启。
     */
    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            call.resolve(JSObject().put("granted", true).put("supported", false))
            return
        }
        if (notificationPermissionGranted()) {
            call.resolve(JSObject().put("granted", true).put("supported", true))
            return
        }
        val currentActivity = activity
        if (currentActivity == null) {
            // 没有前台 Activity 时无法弹系统对话框：如实返回未授予，不挂起调用。
            call.resolve(JSObject().put("granted", false).put("supported", true))
            return
        }
        // Capacitor 的插件方法运行在桥接线程上，而权限申请必须从主线程发起。
        currentActivity.runOnUiThread {
            requestPermissionForAlias(NOTIFICATION_PERMISSION_ALIAS, call, "notificationPermissionCallback")
        }
    }

    @PermissionCallback
    private fun notificationPermissionCallback(call: PluginCall) {
        call.resolve(
            JSObject()
                .put("granted", notificationPermissionGranted())
                .put("supported", true),
        )
    }

    /**
     * 权限：应用内桥接。
     * 返回 Harness 访客进程 stdout/stderr 的有界尾部（按 UTF-8 字符边界截断），
     * 供设置页的「运行日志」展示。
     *
     * 为什么需要它：工具调用失败时界面往往只显示一句没有栈的 JS 报错，排查无法进行；
     * 而 dsh 自己打印的完整异常就在访客进程输出里，此前被有界缓冲保留、却没有任何出口。
     *
     * 隐私边界：这段文本可能包含会话内容，因此**只回传当前界面**——
     * 不写入诊断日志、不新增诊断事件或字段、不落盘、不随诊断日志导出。
     *
     * 运行时不持有 Harness 输出时如实返回 available=false，不猜造内容。
     *
     * 窗口：`maxBytes` 由 [clampHarnessTailBytes] 收敛到 8 / 64 / 256 KB 三档，
     * 缺省 8 KB。放大窗口不会读到缓冲区里没有的内容，因此界面可以放心多给几档。
     */
    @PluginMethod
    fun getHarnessLog(call: PluginCall) {
        resolveWhileActive(call) {
            // 窗口由原生侧收敛到受控档位（8 / 64 / 256 KB），界面只能请求这几种大小。
            val window = clampHarnessTailBytes(call.getInt("maxBytes"))
            val text = HarnessOutputTailSource.read(window)
            JSObject()
                .put("available", text != null)
                .put("text", text.orEmpty())
                .put("maxBytes", window)
        }
    }

    /**
     * 权限：应用内桥接。
     * 只返回诊断日志的状态（开关、保留天数、文件数、总字节数、最近记录时间），
     * 不回传任何日志内容。
     */
    @PluginMethod
    fun getDiagnosticLogState(call: PluginCall) {
        resolveWhileActive(call) { requireDiagnostics().state().toJs() }
    }

    /**
     * 权限：应用内桥接。
     * 返回诊断日志的尾部窗口，供设置页在应用内直接查看。
     *
     * 内容仍然是受控字段（见 [DiagnosticPolicy]）：不含 URL、凭据、终端内容或用户数据，
     * 因此读进 WebView 不构成新的泄露面。窗口由原生侧夹到 1 KB..256 KB，
     * 缺省 64 KB；只读、不落盘、不产生导出文件。
     */
    @PluginMethod
    fun readDiagnosticLog(call: PluginCall) {
        resolveWhileActive(call) {
            val maxBytes = DiagnosticPolicy.clampReadBytes(call.getInt("maxBytes"))
            requireDiagnostics().read(maxBytes).toJs()
        }
    }

    /**
     * 权限：应用内桥接。
     * 更新收集开关与保留天数；保留天数由原生侧夹到 1..30，非法输入直接拒绝。
     */
    @PluginMethod
    fun setDiagnosticLogSettings(call: PluginCall) {
        execute(call) {
            val enabled = call.getBoolean("enabled")
                ?: throw RuntimeFailure("DIAGNOSTIC_SETTINGS_INVALID", "诊断日志开关缺失")
            val retentionDays = call.getInt("retentionDays")
                ?: throw RuntimeFailure("DIAGNOSTIC_SETTINGS_INVALID", "诊断日志保留天数缺失")
            requireDiagnostics().setSettings(enabled, retentionDays).toJs()
        }
    }

    /**
     * 权限：应用内桥接。
     * 导出全部诊断日志并用系统分享面板交给用户选择去向；没有内容时明确失败，
     * 不生成空文件。诊断日志只含受控状态码，因此分享本身不构成凭据外泄。
     */
    @PluginMethod
    fun shareDiagnosticLog(call: PluginCall) {
        execute(call) {
            val log = requireDiagnostics()
            val export = log.export()
                ?: throw RuntimeFailure("DIAGNOSTIC_EXPORT_EMPTY", "当前没有可导出的诊断日志")
            val uri = FileProvider.getUriForFile(context, log.fileProviderAuthority(), log.exportedFile(export))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_share_subject))
                clipData = ClipData.newRawUri(export.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(
                    Intent.createChooser(send, context.getString(R.string.diagnostic_share_title))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (error: Throwable) {
                throw RuntimeFailure("DIAGNOSTIC_SHARE_FAILED", "无法打开分享面板", error)
            }
            log.state().toJs().also { json ->
                json.put("fileName", export.fileName)
                json.put("exportedBytes", export.sizeBytes)
            }
        }
    }

    /**
     * 权限：应用内桥接。
     * 将运行时工作区复制到临时 ZIP 后交给系统分享面板；不直接暴露私有运行时路径。
     * 只跟随普通文件，拒绝符号链接，并限制条目数与总大小，避免意外打包整个运行时。
     */
    @PluginMethod
    fun shareRuntimeWorkspace(call: PluginCall) {
        execute(call) {
            val workspace = File(controller.store.currentRoot, "root/1")
            if (!workspace.isDirectory || Files.isSymbolicLink(workspace.toPath())) {
                throw RuntimeFailure("WORKSPACE_EXPORT_UNAVAILABLE", "运行时工作区尚未准备好")
            }
            val exportDir = File(context.cacheDir, "share")
            if ((!exportDir.isDirectory && !exportDir.mkdirs()) || Files.isSymbolicLink(exportDir.toPath())) {
                throw RuntimeFailure("WORKSPACE_EXPORT_FAILED", "无法准备工作区分享目录")
            }
            val export = File.createTempFile("dsh-workspace-", ".zip", exportDir)
            var entries = 0
            var bytes = 0L
            try {
                ZipOutputStream(FileOutputStream(export)).use { zip ->
                    Files.walkFileTree(workspace.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
                        override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (!attrs.isRegularFile || attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                            if (++entries > 2_000) throw RuntimeFailure("WORKSPACE_EXPORT_TOO_LARGE", "工作区文件数量超过限制")
                            if (attrs.size() > 128L * 1024 * 1024 - bytes) {
                                throw RuntimeFailure("WORKSPACE_EXPORT_TOO_LARGE", "工作区大小超过限制")
                            }
                            val relative = workspace.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                            zip.putNextEntry(ZipEntry(relative))
                            BufferedInputStream(
                                Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                            ).use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    bytes += read
                                    if (bytes > 128L * 1024 * 1024) {
                                        throw RuntimeFailure("WORKSPACE_EXPORT_TOO_LARGE", "工作区大小超过限制")
                                    }
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                            return FileVisitResult.CONTINUE
                        }
                    })
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", export)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "DSH 工作区")
                    clipData = ClipData.newRawUri(export.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "分享 DSH 工作区").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (failure: RuntimeFailure) {
                export.delete()
                throw failure
            } catch (error: Throwable) {
                export.delete()
                throw RuntimeFailure("WORKSPACE_EXPORT_FAILED", "无法导出 DSH 工作区", error)
            }
        }
    }

    @PluginMethod
    fun listRuntimeWorkspaceFiles(call: PluginCall) {
        execute(call) {
            val files = RuntimeWorkspaceFiles(controller.store, context.cacheDir).list()
            JSObject().put("files", org.json.JSONArray(files))
        }
    }

    private fun shareWorkspaceFile(relative: String, open: Boolean) {
        val manager = RuntimeWorkspaceFiles(controller.store, context.cacheDir)
        val target = manager.copyForSharing(relative)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", target)
        val intent = Intent(if (open) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
            val mime = manager.mimeType(relative)
            if (open) setDataAndType(uri, mime) else {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            clipData = ClipData.newRawUri(target.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, if (open) "打开 DSH 文件" else "分享 DSH 文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @PluginMethod
    fun shareRuntimeWorkspaceFile(call: PluginCall) {
        execute(call) { shareWorkspaceFile(call.getString("path") ?: throw RuntimeFailure("WORKSPACE_PATH_INVALID", "工作区文件路径缺失"), false); null }
    }

    @PluginMethod
    fun openRuntimeWorkspaceFile(call: PluginCall) {
        execute(call) { shareWorkspaceFile(call.getString("path") ?: throw RuntimeFailure("WORKSPACE_PATH_INVALID", "工作区文件路径缺失"), true); null }
    }

    @PluginMethod
    fun deleteRuntimeWorkspaceFile(call: PluginCall) {
        execute(call) {
            val path = call.getString("path")
                ?: throw RuntimeFailure("WORKSPACE_PATH_INVALID", "工作区文件路径缺失")
            RuntimeWorkspaceFiles(controller.store, context.cacheDir).delete(path)
            null
        }
    }

    /** 权限：应用内桥接；清空全部诊断日志。 */
    @PluginMethod
    fun clearDiagnosticLog(call: PluginCall) {
        execute(call) { requireDiagnostics().clear().toJs() }
    }

    /**
     * 权限：应用内桥接。
     * 只返回存储访问的布尔与枚举状态，不含任何文件路径或目录内容。
     */
    @PluginMethod
    fun getStorageAccessState(call: PluginCall) {
        resolveWhileActive(call) { storageAccessStateJson() }
    }

    /**
     * 权限：应用内桥接。
     * 投递区状态：用户可见路径、访客挂载点、可用性与 inbox 计数（最多列 5 个 tar）。
     * **不含**任何私有路径、宿主 canonical 路径或文件内容。
     */
    @PluginMethod
    fun mailboxState(call: PluginCall) {
        resolveWhileActive(call) { RuntimeMailbox(controller.store).state().toJs() }
    }

    /**
     * 权限：应用内桥接。
     *
     * 一键导入：inbox 的 tar → 工作区 `mailbox-import/`。
     * 越界（`..` / 绝对路径 / 绝对符号链接）、超限、重复条目与摘要不符，全部在写第一个字节
     * **之前**被拒绝；解包先落到应用私有暂存目录，最后整体改名就位，因此失败不留半截产物。
     * 重复执行是幂等的：落点被整体替换，不会产生第二份条目。
     */
    @PluginMethod
    fun importMailbox(call: PluginCall) {
        execute(call) {
            audited(AuditEvent.MAILBOX_IMPORT) {
                RuntimeMailbox(controller.store).importInbox().toJs()
            }
        }
    }

    /**
     * 权限：应用内桥接；`subdirectory` 省略或留空表示导出整个工作区。
     *
     * 一键导出：工作区（或指定子目录）→ outbox 的 `dsh-workspace.tar` +
     * `dsh-workspace.manifest.json` + `dsh-workspace.tar.sha256`。三个文件名固定，
     * 重复执行只覆盖同一组产物，不会在 outbox 里堆出多份。
     */
    @PluginMethod
    fun exportMailbox(call: PluginCall) {
        execute(call) {
            val subdirectory = optionalMailboxSubdirectory(call.data)
            audited(AuditEvent.MAILBOX_EXPORT) {
                RuntimeMailbox(controller.store).exportWorkspace(subdirectory).toJs()
            }
        }
    }

    /**
     * 权限：应用内桥接。
     *
     * ≤8 目录白名单状态：每条的用户可见路径、展示名、访客挂载点 `/mnt/user/<序号>`、可用性与
     * 不可用时的受控错误码，以及上限与权限档。
     *
     * **序号来自持久化顺序**：某条目录失效时只跳过该条（访客里留下空洞），不重排 —— 否则一次
     * 失效就会让别的条目换到另一个挂载点上。**不含**应用私有路径、rootfs 路径或文件内容。
     */
    @PluginMethod
    fun storageDirsState(call: PluginCall) {
        resolveWhileActive(call) { storageDirs().state().toJs() }
    }

    /**
     * 权限：应用内桥接。
     *
     * 新增一个要绑进访客的目录：Android 侧弹 SAF 目录选择器（`ACTION_OPEN_DOCUMENT_TREE`），
     * 回调里做「document id → 真实路径 → 白名单准入」全套校验，**全部通过才落盘**。
     *
     * 拒绝一律给受控错误码（非 primary 卷、共享存储根、Android/、应用私有目录、符号链接逃逸、
     * 重复、超限、路径含运行时不支持的字符），失败不写入任何东西。用户取消返回
     * `STORAGE_DIR_CANCELLED`，界面据此不当作故障。
     */
    @PluginMethod
    fun addStorageDirectory(call: PluginCall) {
        val currentActivity = activity
        if (currentActivity == null) {
            call.reject("当前没有可用的界面，无法打开目录选择器", StorageDirCodes.PICKER_UNAVAILABLE)
            return
        }
        try {
            // 先判档位再弹选择器：让用户白点一次目录、回来才被告知「需要授权」是纯粹的浪费，
            // 而判定规则仍然只有白名单门面那一份（这里不重复实现）。
            storageDirs().requireReady()
        } catch (failure: RuntimeFailure) {
            call.reject(failure.message ?: "存储目录白名单当前不可用", failure.code)
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        // Capacitor 的插件方法运行在桥接线程上，而启动选择器必须从主线程发起（同 requestMediaPermission）。
        currentActivity.runOnUiThread {
            try {
                startActivityForResult(call, intent, "storageDirectoryPicked")
            } catch (_: Throwable) {
                // 部分精简 ROM 没有文件选择器：如实报「没有可用入口」，而不是静默什么都不发生。
                call.reject("设备上没有可用的目录选择器", StorageDirCodes.PICKER_UNAVAILABLE)
            }
        }
    }

    /**
     * SAF 目录选择器回调。
     *
     * 这里**刻意不调用 `takePersistableUriPermission`**：白名单存的是解析后的真实路径，
     * 之后再也不碰 tree URI，持久化一份用不到的授权只会多出一条不随白名单条目一起消失的访问路径。
     * 决定权在用户（存的是用户点过的目录），而实际读写能力来自 T2 的「所有文件访问」。
     */
    @ActivityCallback
    private fun storageDirectoryPicked(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || uri == null) {
            call.reject("已取消目录选择", StorageDirCodes.CANCELLED)
            return
        }
        val documentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Throwable) {
            call.reject("目录选择结果不是可识别的目录树", StorageDirCodes.DOCUMENT_ID_INVALID)
            return
        }
        // 解析与落盘放到执行器上：canonicalFile 与目录探测都要碰文件系统。
        // execute 支持「稍后 resolve」——回调此刻返回不影响这条调用最终的结果。
        execute(call) {
            audited(AuditEvent.STORAGE_DIR_ADD) { storageDirs().add(documentId).toJs() }
        }
    }

    /**
     * 权限：应用内桥接；按 `path` 移除一条白名单目录（`path` 取自 [storageDirsState] 的条目）。
     *
     * 用路径而不是序号作为标识：序号会因增删而变，用序号删除可能删掉另一条目录。
     * 不在白名单里时如实报 `STORAGE_DIR_NOT_FOUND`，不做「看起来成功」的空操作。
     */
    @PluginMethod
    fun removeStorageDirectory(call: PluginCall) {
        execute(call) {
            val path = call.getString("path")?.takeIf { it.isNotEmpty() }
                ?: throw RuntimeFailure(StorageDirCodes.PATH_REQUIRED, "存储目录路径缺失")
            audited(AuditEvent.STORAGE_DIR_REMOVE) { storageDirs().remove(path).toJs() }
        }
    }

    /**
     * 权限：应用内桥接；仅申请相册/视频的媒体读取权限。
     * Android 13 起用 READ_MEDIA_*，12 及以下用 READ_EXTERNAL_STORAGE；被拒绝只返回结果，
     * 不阻断其他功能（容器仍可读应用私有目录）。
     */
    @PluginMethod
    fun requestMediaPermission(call: PluginCall) {
        val currentActivity = activity
        if (currentActivity == null) {
            call.resolve(JSObject().put("granted", mediaPermissionGranted()))
            return
        }
        val aliases = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(MEDIA_IMAGES_ALIAS, MEDIA_VIDEO_ALIAS)
        } else {
            arrayOf(LEGACY_STORAGE_ALIAS)
        }
        // Capacitor 的插件方法运行在桥接线程上，而权限申请必须从主线程发起。
        currentActivity.runOnUiThread {
            requestPermissionForAliases(aliases, call, "mediaPermissionCallback")
        }
    }

    @PermissionCallback
    private fun mediaPermissionCallback(call: PluginCall) {
        call.resolve(JSObject().put("granted", mediaPermissionGranted()))
    }

    /**
     * 悬浮球开关、系统悬浮窗权限与服务运行状态的当前快照。
     *
     * 返回值只有布尔量，不含任何用户数据。`canDrawOverlays` 必须每次实时读取：
     * 用户可能在系统设置里随时撤销，缓存下来会让界面显示错误状态。
     */
    @PluginMethod
    fun overlayBallState(call: PluginCall) {
        execute(call) {
            JSObject()
                .put("enabled", controller.store.overlayBallEnabled())
                .put("canDrawOverlays", android.provider.Settings.canDrawOverlays(context))
                .put("serviceActive", OverlayBallService.isRunning)
        }
    }

    /**
     * 引导用户到「显示在其他应用上层」设置页。
     *
     * 该权限不弹运行时对话框，只能由用户手动开启；本方法只负责跳转，
     * 授权结果由界面在 onResume 后重新查询 [overlayBallState] 获得。
     */
    @PluginMethod
    fun openOverlaySettings(call: PluginCall) {
        execute(call) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: Throwable) {
                // 部分 ROM 没有该设置页：退回应用详情页，至少让用户能进系统设置。
                try {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (fallbackError: Throwable) {
                    throw RuntimeFailure(
                        "OVERLAY_SETTINGS_UNAVAILABLE",
                        "无法打开系统设置页",
                        fallbackError,
                    )
                }
            }
            null
        }
    }

    /**
     * 权限：应用内桥接。
     * 「所有文件访问」是特殊权限，没有运行时对话框可弹：只能跳到系统设置页由用户手动开启。
     * Android 11 以下不存在该权限，返回 supported=false 由界面隐藏入口。
     */
    @PluginMethod
    fun openAllFilesAccessSettings(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            call.resolve(JSObject().put("supported", false).put("granted", true))
            return
        }
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: Throwable) {
            // 部分 ROM 没有该设置页：退回应用详情页，至少让用户能进系统设置。
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (fallbackError: Throwable) {
                throw RuntimeFailure("STORAGE_SETTINGS_UNAVAILABLE", "无法打开系统存储设置", fallbackError)
            }
        }
        call.resolve(JSObject().put("supported", true).put("granted", allFilesAccessGranted()))
    }

    private fun mediaPermissionGranted(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            permissionGranted(Manifest.permission.READ_MEDIA_IMAGES) ||
                permissionGranted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> permissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * 「所有文件访问」状态。
     * Android 11 以下不存在该权限，视为无需申请（返回 true），避免界面显示成"未授权"。
     */
    private fun allFilesAccessGranted(): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> true
        else -> try {
            android.os.Environment.isExternalStorageManager()
        } catch (_: Throwable) {
            false
        }
    }

    private fun permissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 目录白名单门面。
     *
     * 懒初始化：构造它要读偏好，而在插件 load 阶段读一份用户设置既没必要、也可能在
     * 「插件注册失败」这条路径上多出一个失败点（插件注册失败的代价是整个管理界面失去原生桥）。
     */
    private val storageDirsFacade: RuntimeStorageDirs by lazy {
        RuntimeStorageDirs.from(context, controller.store) { level, fields ->
            controller.store.diagnostics.record(level, DiagnosticEvent.STORAGE_DIRS, fields)
        }
    }

    private fun storageDirs(): RuntimeStorageDirs = storageDirsFacade

    /** 存储访问状态：只有布尔与枚举，不含路径或目录内容。 */
    private fun storageAccessStateJson(): JSObject = JSObject()
        .put("mediaGranted", mediaPermissionGranted())
        .put("allFilesGranted", allFilesAccessGranted())
        // allFilesSupported=false 表示系统版本低于 Android 11，界面应隐藏「所有文件访问」入口。
        .put("allFilesSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        .put("sdkInt", Build.VERSION.SDK_INT)

    /**
     * 按设置与当前运行时阶段同步前台服务。
     * 只在用户开启且 Harness 确实由本进程运行时保持服务，避免留下无法解释的通知。
     */
    private fun syncKeepAliveService(keepRuntimeInBackground: Boolean) {
        val running = controller.state().phase == RuntimePhase.RUNNING
        val shouldRun = HarnessKeepAlivePolicy.shouldRunService(keepRuntimeInBackground, running)
        if (shouldRun) {
            HarnessKeepAliveService.start(context)
        } else {
            HarnessKeepAliveService.stop(context)
        }
        diagnostics()?.record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.KEEP_ALIVE,
            mapOf(
                "active" to shouldRun.toString(),
                "running" to running.toString(),
                "enabled" to keepRuntimeInBackground.toString(),
            ),
        )
    }

    /**
     * 按设置同步悬浮球服务。
     *
     * 与 [syncKeepAliveService] 分开：两者的启动条件互不相干，合在一起会让
     * 「只想开悬浮球」的用户被动拉起运行时保活服务。
     */
    private fun syncOverlayBallService(enabled: Boolean) {
        val canDraw = android.provider.Settings.canDrawOverlays(context)
        val shouldRun = OverlayBallPolicy.shouldShowBall(enabled, canDraw)
        if (shouldRun) {
            OverlayBallService.start(context)
        } else {
            OverlayBallService.stop(context)
        }
        // 这里有两条静默失败的路径：权限被撤销时走的是 stop，而 stopService 对未运行的
        // 服务是空操作、不触发 onDestroy 的记录；start 内部也会吞掉系统拒绝启动前台服务的
        // 异常。没有这条记录，「开关开着但球不出现」在诊断日志里完全查不到原因。
        diagnostics()?.record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.KEEP_ALIVE,
            mapOf(
                "reason" to "overlay_sync",
                "active" to shouldRun.toString(),
                "enabled" to enabled.toString(),
                "permission" to if (canDraw) "granted" else "denied",
                "running" to OverlayBallService.isRunning.toString(),
            ),
        )
    }

    /** 显式停止运行时或重置：立即撤销前台服务，由 RuntimeHost 统一收尾。 */
    private fun stopKeepAliveService() {
        HarnessKeepAliveService.stop(context)
    }

    private fun notificationPermissionGranted(): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> true
        else -> ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 通知权限状态：unsupported 表示系统版本低于 Android 13；
     * prompt 表示尚未授予（可能已拒绝，可在系统设置中开启），不代表通知一定无法显示。
     */
    private fun notificationPermissionState(): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "unsupported"
        notificationPermissionGranted() -> "granted"
        else -> "prompt"
    }

    private fun execute(call: PluginCall, operation: () -> JSObject?): Boolean {
        if (destroying.get()) {
            rejectRuntimeClosed(call)
            return false
        }
        return try {
            executor.execute(PluginTask(call, operation))
            true
        } catch (_: RejectedExecutionException) {
            rejectRuntimeClosed(call)
            false
        }
    }

    private fun requestHarnessStartCancellation() {
        if (!destroying.get() && ::controller.isInitialized) {
            controller.requestStartCancellation()
        }
    }

    private fun resolveWhileActive(call: PluginCall, operation: () -> JSObject?) {
        if (destroying.get()) {
            rejectRuntimeClosed(call)
        } else {
            resolveSafely(call, operation)
        }
    }

    private fun resolveSafely(call: PluginCall, operation: () -> JSObject?) {
        try {
            val result = operation()
            if (result == null) call.resolve() else call.resolve(result)
        } catch (failure: RuntimeFailure) {
            call.reject(failure.message ?: "操作失败", failure.code)
        } catch (_: Throwable) {
            call.reject("本机运行时操作失败", "INTERNAL_ERROR")
        }
    }

    private fun <T> audited(event: AuditEvent, operation: () -> T): T {
        return auditedOperationLock.withLock {
            ensurePluginActive()
            recordAudit(event, AuditResult.STARTED)
            try {
                operation().also { recordAudit(event, AuditResult.SUCCEEDED) }
            } catch (failure: RuntimeFailure) {
                val result = if (event == AuditEvent.RUNTIME_INSTALL && failure.code == "INSTALL_CANCELLED") {
                    AuditResult.CANCELLED
                } else {
                    AuditResult.FAILED
                }
                recordAudit(event, result, failure.code)
                throw failure
            } catch (error: Throwable) {
                recordAudit(event, AuditResult.FAILED, "INTERNAL_ERROR")
                throw error
            }
        }
    }

    private fun requestShizukuPermissionAudited(): ShizukuState {
        return auditedOperationLock.withLock {
            ensurePluginActive()
            recordAudit(AuditEvent.SHIZUKU_PERMISSION, AuditResult.STARTED)
            try {
                controller.requestShizukuPermission().also { state ->
                    val result = if (state.permission == "granted") AuditResult.SUCCEEDED else AuditResult.DENIED
                    recordAudit(AuditEvent.SHIZUKU_PERMISSION, result)
                }
            } catch (failure: RuntimeFailure) {
                val result = when (failure.code) {
                    "SHIZUKU_PERMISSION_DENIED" -> AuditResult.DENIED
                    "SHIZUKU_PERMISSION_INTERRUPTED" -> AuditResult.CANCELLED
                    else -> AuditResult.FAILED
                }
                recordAudit(AuditEvent.SHIZUKU_PERMISSION, result)
                throw failure
            } catch (error: Throwable) {
                recordAudit(AuditEvent.SHIZUKU_PERMISSION, AuditResult.FAILED)
                throw error
            }
        }
    }

    private fun recordAudit(event: AuditEvent, result: AuditResult, detail: String? = null) {
        if (::auditLog.isInitialized) auditLog.record(event, result, detail)
    }

    private fun ensurePluginActive() {
        if (destroying.get()) throw RuntimeFailure("RUNTIME_CLOSED", "本机运行时正在关闭")
    }

    private fun rejectRuntimeClosed(call: PluginCall) {
        call.reject("本机运行时正在关闭", "RUNTIME_CLOSED")
    }

    private inner class PluginTask(
        private val call: PluginCall,
        private val operation: () -> JSObject?,
    ) : Runnable {
        override fun run() {
            if (destroying.get()) {
                rejectRuntimeClosed()
            } else {
                resolveSafely(call) {
                    ensurePluginActive()
                    operation()
                }
            }
        }

        fun rejectRuntimeClosed() {
            this@MobileRuntimePlugin.rejectRuntimeClosed(call)
        }
    }

    /**
     * WebView 侧事件出口：只在插件仍然存活时派发。
     * 插件销毁后运行时可能仍由前台服务持有，此时事件被安全丢弃——不缓存、不落盘。
     */
    private inner class PluginEventSink : RuntimeEventSink {
        override fun onProgress(snapshot: RuntimeStateSnapshot) {
            if (!destroying.get()) notifyListeners("runtimeProgress", snapshot.toProgressJs())
        }

        override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) {
            dispatchTerminalOutput(
                sessionId,
                dataBase64,
                suppressPublicOutput,
                onDeviceCommandOutput = { id, data ->
                    // 热路径：没有执行器时不要顺手创建（未授权设备 Shell 时永远用不到）。
                    RuntimeHost.deviceCommandsOrNull()?.onOutput(id, data)
                },
                onPublicOutput = { id, data ->
                    if (!destroying.get()) {
                        notifyListeners(
                            "terminalOutput",
                            JSObject().put("sessionId", id).put("dataBase64", data),
                        )
                    }
                },
            )
        }

        override fun onTerminalExit(sessionId: String, exitCode: Int) {
            if (!destroying.get()) {
                notifyListeners(
                    "terminalExit",
                    JSObject().put("sessionId", sessionId).put("exitCode", exitCode),
                )
            }
        }
    }

    private fun applyKeepScreenAwake(enabled: Boolean) {
        activity?.runOnUiThread {
            if (enabled) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun RuntimeSettings.toJs(): JSObject = JSObject()
        .put("manifestUrl", manifestUrl)
        .put("manifestSha256", manifestSha256)
        .put("keepScreenAwake", keepScreenAwake)
        .put("terminalFontSize", terminalFontSize)
        .put("configuredModelProviders", org.json.JSONArray(configuredModelProviders.map { it.wireValue }))
        .put("harnessConfiguredModelProviders", org.json.JSONArray(harnessConfiguredModelProviders.map { it.wireValue }))
        .put("customModelProviders", org.json.JSONArray().also { providers ->
            customModelProviders.forEach { provider ->
                providers.put(JSObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("api", provider.api.wireValue)
                    .put("baseUrl", provider.baseUrl)
                    .put("models", org.json.JSONArray().also { models ->
                        provider.models.forEach { model ->
                            models.put(JSObject().put("id", model.id).put("name", model.name)
                                .put("contextWindow", model.contextWindow).put("maxTokens", model.maxTokens))
                        }
                    }))
            }
        })
        .put("configuredCustomModelProviders", org.json.JSONArray(configuredCustomModelProviders))
        .put("harnessConfiguredCustomModelProviders", org.json.JSONArray(harnessConfiguredCustomModelProviders))
        .put("autoLaunch", autoLaunch)
        .put("keepRuntimeInBackground", keepRuntimeInBackground)
        .put("overlayBallEnabled", overlayBallEnabled)
        .put("harnessPermissionMode", harnessPermissionMode.wireValue)

    private fun RuntimeKeepAliveSnapshot.toJs(): JSObject = JSObject()
        .put("keepRuntimeInBackground", keepRuntimeInBackground)
        .put("foregroundServiceActive", foregroundServiceActive)
        .put("notificationPermission", notificationPermissionState())
        .put("deviceShellReady", deviceShellReady)
        .put("reconnectRequired", reconnectRequired)
        .put("lastIntent", lastIntent.wireValue)
        .also { json ->
            lastPhase?.let { json.put("lastPhase", it.wireValue) }
            json.put("lastUpdatedAtMillis", lastUpdatedAtMillis.coerceAtLeast(0L))
        }

    private fun RuntimeStateSnapshot.toProgressJs(): JSObject = JSObject()
        .put("phase", phase.wireValue)
        .put("downloadedBytes", downloadedBytes)
        .put("totalBytes", totalBytes)
        .also { json -> errorCode?.let { json.put("errorCode", it) } }

    private fun RuntimeStateSnapshot.toJs(): JSObject = JSObject()
        .put("phase", phase.wireValue)
        .put("architecture", architecture)
        .put("updateAvailable", updateAvailable)
        .put("downloadedBytes", downloadedBytes)
        .put("totalBytes", totalBytes)
        .put("runnerAvailable", runnerAvailable)
        .also { json ->
            installedVersion?.let { json.put("installedVersion", it) }
            harnessUrl?.let { json.put("harnessUrl", it) }
            errorCode?.let { json.put("errorCode", it) }
        }

    private fun ShizukuState.toJs(): JSObject = JSObject()
        .put("installed", installed)
        .put("running", running)
        .put("permission", permission)
        .put("connected", connected)
        .put("version", version)

    /** 诊断日志状态：只有布尔值、计数与时间戳，不含任何日志内容。 */
    private fun DiagnosticState.toJs(): JSObject = JSObject()
        .put("enabled", enabled)
        .put("retentionDays", retentionDays)
        .put("fileCount", fileCount)
        .put("totalBytes", totalBytes)
        .put("lastEntryAtMillis", lastEntryAtMillis)

    /**
     * 投递区状态。
     *
     * `available` 与 `availability` 是同一事实的两种表达：前者给按钮的禁用条件，
     * 后者给文案分档（可用 / 需要授权 / 不支持 / 已授权但不可写）。
     * 路径是**用户可见路径**与访客挂载点，二者都在文档里公开，不属于私有信息。
     */
    private fun MailboxState.toJs(): JSObject = JSObject()
        .put("availability", availability.wireValue)
        .put("level", availability.level)
        .put("available", available)
        .put("supported", supported)
        .put("granted", granted)
        .put("inboxPath", inboxPath)
        .put("outboxPath", outboxPath)
        .put("guestInboxPath", guestInboxPath)
        .put("guestOutboxPath", guestOutboxPath)
        .put("inboxFileCount", inboxFileCount)
        .put(
            "inboxTars",
            org.json.JSONArray().also { array ->
                inboxTars.forEach { candidate ->
                    array.put(JSObject().put("name", candidate.name).put("bytes", candidate.bytes))
                }
            },
        )
        .put("exportTarName", exportTarName)
        .put("exportManifestName", exportManifestName)
        .put("importDirectory", importDirectory)

    /**
     * 目录白名单状态。
     *
     * `level` 给界面展示口径（T2 / T0），逐条 `availability` + `level` 给「这一条现在能不能用」，
     * `reasonCode` 只在不可用时出现（受控错误码，界面据此给不同提示）。
     * 路径是**用户自己选过的用户可见路径**与访客挂载点，与投递区的 inboxPath 同类，不属于私有信息。
     */
    private fun StorageDirsState.toJs(): JSObject = JSObject()
        .put("supported", supported)
        .put("granted", granted)
        .put("level", level)
        .put("maxDirectories", maxDirectories)
        .put("count", entries.size)
        .put("active", active)
        .put(
            "entries",
            org.json.JSONArray().also { array -> entries.forEach { status -> array.put(status.toJs()) } },
        )

    private fun StorageDirStatus.toJs(): JSObject = JSObject()
        .put("index", index)
        .put("path", entry.path)
        .put("displayName", entry.displayName)
        .put("guestPath", guestPath)
        .put("availability", availability.wireValue)
        .put("level", availability.level)
        .put("available", available)
        .also { json -> reasonCode?.let { json.put("reasonCode", it) } }

    /** 导入结果：只有计数、字节数、文件名与落点，不含内容。 */
    private fun MailboxImportOutcome.toJs(): JSObject = JSObject()        .put("entryCount", entryCount)
        .put("fileCount", fileCount)
        .put("directoryCount", directoryCount)
        .put("symlinkCount", symlinkCount)
        .put("hardlinkCount", hardlinkCount)
        .put("bytes", bytes)
        .put("tarName", tarName)
        .put("tarBytes", tarBytes)
        .put("verified", verified)
        .put("ignoredFiles", ignoredFiles)
        .put("target", target)
        .also { json -> manifestName?.let { json.put("manifestName", it) } }

    /** 导出结果：产物文件名、字节数与摘要，以及被跳过的条目数。 */
    private fun MailboxExportOutcome.toJs(): JSObject = JSObject()
        .put("entryCount", entryCount)
        .put("bytes", bytes)
        .put("tarName", tarName)
        .put("tarBytes", tarBytes)
        .put("tarSha256", tarSha256)
        .put("manifestName", manifestName)
        .put("skippedLinks", skippedLinks)
        .put("skippedSpecial", skippedSpecial)
        .also { json -> subdirectory?.let { json.put("subdirectory", it) } }

    /** 应用内查看结果：受控字段文本 + 窗口与截断状态，不含路径或文件名。 */
    private fun DiagnosticText.toJs(): JSObject = JSObject()
        .put("text", text)
        .put("maxBytes", maxBytes)
        .put("totalBytes", totalBytes)
        .put("truncated", truncated)
}

internal fun dispatchTerminalOutput(
    sessionId: String,
    dataBase64: String,
    suppressPublicOutput: Boolean,
    onDeviceCommandOutput: (sessionId: String, dataBase64: String) -> Unit,
    onPublicOutput: (sessionId: String, dataBase64: String) -> Unit,
) {
    onDeviceCommandOutput(sessionId, dataBase64)
    if (!suppressPublicOutput) onPublicOutput(sessionId, dataBase64)
}
