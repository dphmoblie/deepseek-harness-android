package io.deepseekharness.mobile

import android.content.Intent
import android.view.WindowManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import io.deepseekharness.mobile.runtime.MobileRuntimeController
import io.deepseekharness.mobile.runtime.RuntimeFailure
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@CapacitorPlugin(name = "MobileRuntime")
class MobileRuntimePlugin : Plugin() {
    private lateinit var controller: MobileRuntimeController
    private lateinit var auditLog: PrivateAuditLog
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val destroying = AtomicBoolean(false)
    private val auditedOperationLock = ReentrantLock()
    private lateinit var deviceCommands: DeviceCommandRunner
    private var deviceBridge: DeviceBridgeServer? = null

    companion object {
        private const val DEVICE_COMMAND_TIMEOUT_MS = 60_000L
        private const val DESTROY_WAIT_SECONDS = 10L
    }

    override fun load() {
        auditLog = PrivateAuditLog(context)
        recordAudit(AuditEvent.PLUGIN_LOAD, AuditResult.STARTED)
        try {
            controller = MobileRuntimeController(
                context = context,
                onProgress = { snapshot ->
                    if (!destroying.get()) {
                        notifyListeners("runtimeProgress", snapshot.toProgressJs())
                    }
                },
                onTerminalOutput = { sessionId, dataBase64 ->
                    if (::deviceCommands.isInitialized) {
                        deviceCommands.onOutput(sessionId, dataBase64)
                    }
                    if (!destroying.get()) {
                        notifyListeners(
                            "terminalOutput",
                            JSObject().put("sessionId", sessionId).put("dataBase64", dataBase64),
                        )
                    }
                },
                onTerminalExit = { sessionId, exitCode ->
                    if (!destroying.get()) {
                        notifyListeners(
                            "terminalExit",
                            JSObject().put("sessionId", sessionId).put("exitCode", exitCode),
                        )
                    }
                },
            )
            deviceCommands = DeviceCommandRunner(
                writer = { sessionId, dataBase64 -> controller.writeTerminal(sessionId, dataBase64) },
            )
            applyKeepScreenAwake(controller.store.settings().keepScreenAwake)
            deviceBridge = DeviceBridgeServer(
                shizuku = controller.terminals.shizuku,
                runner = deviceCommands,
                token = controller.store.deviceBridgeToken(),
            ).also { it.start() }
            recordAudit(AuditEvent.PLUGIN_LOAD, AuditResult.SUCCEEDED)
        } catch (error: Throwable) {
            recordAudit(AuditEvent.PLUGIN_LOAD, AuditResult.FAILED)
            throw error
        }
    }

    override fun handleOnDestroy() {
        if (!destroying.compareAndSet(false, true)) return
        recordAudit(AuditEvent.PLUGIN_DESTROY, AuditResult.STARTED)
        var result = AuditResult.SUCCEEDED
        try {
            executor.shutdownNow()
                .filterIsInstance<PluginTask>()
                .forEach { task -> task.rejectRuntimeClosed() }
            if (::deviceCommands.isInitialized) deviceCommands.cancelAll()
            deviceBridge?.stop()
            deviceBridge = null
        } catch (_: Throwable) {
            result = AuditResult.FAILED
        }
        try {
            if (::controller.isInitialized) controller.shutdown()
        } catch (_: Throwable) {
            result = AuditResult.FAILED
            // Destruction still proceeds; no terminal data or process details are logged.
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
            val settings = RuntimeValidation.settings(
                call.getString("manifestUrl"),
                call.getString("manifestSha256"),
                call.getBoolean("keepScreenAwake", false) ?: false,
                fontSize,
                call.getString("apiKey"),
                call.getBoolean("autoLaunch", true) ?: true,
            )
            controller.store.saveSettings(settings)
            applyKeepScreenAwake(settings.keepScreenAwake)
            settings.toJs()
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
        execute(call) { audited(AuditEvent.RUNTIME_START) { controller.startHarness().toJs() } }
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
        execute(call) { audited(AuditEvent.RUNTIME_STOP) { controller.stopRuntime().toJs() } }
    }

    @PluginMethod
    fun reset(call: PluginCall) {
        execute(call) {
            audited(AuditEvent.RUNTIME_RESET) {
                controller.reset(call.getString("confirmation")).toJs()
            }
        }
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

    private fun execute(call: PluginCall, operation: () -> JSObject?) {
        if (destroying.get()) {
            rejectRuntimeClosed(call)
            return
        }
        try {
            executor.execute(PluginTask(call, operation))
        } catch (_: RejectedExecutionException) {
            rejectRuntimeClosed(call)
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
        .put("apiKey", apiKey)
        .put("autoLaunch", autoLaunch)

    private fun RuntimeStateSnapshot.toProgressJs(): JSObject = JSObject()
        .put("phase", phase.wireValue)
        .put("downloadedBytes", downloadedBytes)
        .put("totalBytes", totalBytes)
        .also { json -> errorCode?.let { json.put("errorCode", it) } }

    private fun RuntimeStateSnapshot.toJs(): JSObject = JSObject()
        .put("phase", phase.wireValue)
        .put("architecture", architecture)
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
