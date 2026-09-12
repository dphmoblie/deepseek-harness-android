package io.deepseekharness.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import io.deepseekharness.mobile.runtime.HarnessKeepAlivePolicy
import io.deepseekharness.mobile.runtime.MobileRuntimeController
import io.deepseekharness.mobile.runtime.DeviceBridgeAccess
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeKeepAliveSnapshot
import io.deepseekharness.mobile.runtime.RuntimePhase
import io.deepseekharness.mobile.runtime.RuntimeSettings
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import io.deepseekharness.mobile.runtime.RuntimeValidation
import io.deepseekharness.mobile.runtime.audit.AuditEvent
import io.deepseekharness.mobile.runtime.audit.AuditResult
import io.deepseekharness.mobile.runtime.audit.PrivateAuditLog
import io.deepseekharness.mobile.shizuku.DeviceCommand
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import io.deepseekharness.mobile.shizuku.DeviceCommandRunner
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

@CapacitorPlugin(
    name = "MobileRuntime",
    permissions = [
        Permission(
            alias = NOTIFICATION_PERMISSION_ALIAS,
            strings = [Manifest.permission.POST_NOTIFICATIONS],
        ),
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
    private lateinit var deviceCommands: DeviceCommandRunner
    private var deviceBridge: DeviceBridgeServer? = null

    /**
     * 运行时事件出口。运行时由 [RuntimeHost] 跨插件实例持有，因此这里必须是稳定的
     * 订阅者对象：插件销毁后取消订阅，运行时不会继续向已销毁的 WebView 派发事件。
     */
    private val eventSink = PluginEventSink()

    companion object {
        private const val DEVICE_COMMAND_TIMEOUT_MS = 60_000L
        private const val DESTROY_WAIT_SECONDS = 10L
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
            deviceCommands = DeviceCommandRunner(
                writer = { sessionId, dataBase64 -> controller.writeTerminal(sessionId, dataBase64) },
            )
            applyKeepScreenAwake(controller.store.keepScreenAwake())
            ensureDeviceBridge()
            recordAudit(AuditEvent.PLUGIN_LOAD, AuditResult.SUCCEEDED)
        } catch (error: Throwable) {
            stopDeviceBridge()
            recordAudit(AuditEvent.PLUGIN_LOAD, AuditResult.FAILED)
            throw error
        }
    }

    /**
     * Capacitor 插件销毁（Activity 销毁，含划掉最近任务）。
     *
     * 这里只取消订阅并回收插件自己拥有的资源（线程池、设备桥、设备命令）：
     * 运行时由 [RuntimeHost] 统一持有，前台服务仍在负责时不得立即 shutdown，
     * 否则「后台保持 Harness」会形同虚设。两者都不再持有时由 RuntimeHost 释放运行时，
     * 语义与旧实现一致。
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
            if (this::deviceCommands.isInitialized) deviceCommands.cancelAll()
            stopDeviceBridge()
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
                    controller.startHarness().toJs().also {
                        // Harness 启动成功后才按设置提升前台优先级；失败时不留空转服务。
                        // 这里直接读开关，避免为读设置而触发凭据解密。
                        syncKeepAliveService(controller.store.keepRuntimeInBackground())
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
                stopDeviceBridge()
                deviceCommands.cancelAll()
                controller.stopRuntime().toJs()
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
                stopDeviceBridge()
                deviceCommands.cancelAll()
                controller.reset(confirmation).toJs()
            }
        }
    }

    @Synchronized
    private fun ensureDeviceBridge() {
        if (deviceBridge != null) return
        val bridgeTokenBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val bridgeToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bridgeTokenBytes)
        bridgeTokenBytes.fill(0)
        val bridge = DeviceBridgeServer(
            shizuku = controller.terminals.shizuku,
            runner = deviceCommands,
            token = bridgeToken,
        )
        try {
            bridge.start()
            controller.configureDeviceBridge(DeviceBridgeAccess(bridge.localPort, bridgeToken))
            deviceBridge = bridge
        } catch (error: Throwable) {
            bridge.stop()
            throw error
        }
    }

    @Synchronized
    private fun stopDeviceBridge() {
        deviceBridge?.stop()
        deviceBridge = null
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
            val result = deviceCommands.execute(sessionId, command, call.getString("param") ?: "", DEVICE_COMMAND_TIMEOUT_MS)
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
     * 按设置与当前运行时阶段同步前台服务。
     * 只在用户开启且 Harness 确实由本进程运行时保持服务，避免留下无法解释的通知。
     */
    private fun syncKeepAliveService(keepRuntimeInBackground: Boolean) {
        val running = controller.state().phase == RuntimePhase.RUNNING
        if (HarnessKeepAlivePolicy.shouldRunService(keepRuntimeInBackground, running)) {
            HarnessKeepAliveService.start(context)
        } else {
            HarnessKeepAliveService.stop(context)
        }
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
                    if (this@MobileRuntimePlugin::deviceCommands.isInitialized) deviceCommands.onOutput(id, data)
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
