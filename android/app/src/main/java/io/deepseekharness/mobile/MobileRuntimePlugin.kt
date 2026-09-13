package io.deepseekharness.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import io.deepseekharness.mobile.runtime.HarnessKeepAlivePolicy
import io.deepseekharness.mobile.runtime.HarnessOutputTailSource
import io.deepseekharness.mobile.runtime.MobileRuntimeController
import io.deepseekharness.mobile.runtime.DeviceBridgeAccess
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeIntent
import io.deepseekharness.mobile.runtime.RuntimeKeepAliveSnapshot
import io.deepseekharness.mobile.runtime.RuntimePhase
import io.deepseekharness.mobile.runtime.RuntimeSettings
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import io.deepseekharness.mobile.runtime.RuntimeValidation
import io.deepseekharness.mobile.runtime.audit.AuditEvent
import io.deepseekharness.mobile.runtime.audit.AuditResult
import io.deepseekharness.mobile.runtime.audit.PrivateAuditLog
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticExport
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticState
import io.deepseekharness.mobile.shizuku.DeviceCommand
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import io.deepseekharness.mobile.shizuku.ShizukuState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.withLock

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
    fun setAppLanguage(call: PluginCall) {
        val language = call.getString("language").orEmpty()
        if (language != "zh-CN" && language != "en") {
            call.reject("不支持的应用语言", "LANGUAGE_INVALID")
            return
        }
        try {
            if (AppLanguage.save(context, language)) call.resolve()
            else call.reject("无法保存应用语言", "LANGUAGE_SAVE_FAILED")
        } catch (_: Exception) {
            call.reject("无法保存应用语言", "LANGUAGE_SAVE_FAILED")
        }
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
            val settings = RuntimeValidation.settings(
                call.getString("manifestUrl"),
                call.getString("manifestSha256"),
                call.getBoolean("keepScreenAwake", false) ?: false,
                fontSize,
                call.getBoolean("autoLaunch", true) ?: true,
                call.getBoolean("keepRuntimeInBackground", false) ?: false,
            )
            val saved = controller.saveSettings(
                settings,
                providerApiKeyUpdates,
                clearedProviderApiKeys,
                customProviders,
                customProviderApiKeyUpdates,
                clearedCustomProviderApiKeys,
            )
            applyKeepScreenAwake(saved.keepScreenAwake)
            syncKeepAliveService(saved.keepRuntimeInBackground)
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
     * 返回 Harness 访客进程 stdout/stderr 的有界尾部（默认 8192 字节，按 UTF-8 字符边界截断），
     * 供设置页的「运行日志」展示。
     *
     * 为什么需要它：工具调用失败时界面往往只显示一句没有栈的 JS 报错，排查无法进行；
     * 而 dsh 自己打印的完整异常就在访客进程输出里，此前被有界缓冲保留、却没有任何出口。
     *
     * 隐私边界：这段文本可能包含会话内容，因此**只回传当前界面**——
     * 不写入诊断日志、不新增诊断事件或字段、不落盘、不随诊断日志导出。
     * 运行时不持有 Harness 输出时如实返回 available=false，不猜造内容。
     */
    @PluginMethod
    fun getHarnessLog(call: PluginCall) {
        resolveWhileActive(call) {
            val text = HarnessOutputTailSource.read()
            JSObject()
                .put("available", text != null)
                .put("text", text.orEmpty())
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
        .put("autoLaunch", autoLaunch)
        .put("keepRuntimeInBackground", keepRuntimeInBackground)

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
