package io.deepseekharness.mobile.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import io.deepseekharness.mobile.BuildConfig
import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.runtime.RuntimeLimits
import io.deepseekharness.mobile.runtime.UbuntuTerminalManager
import rikka.shizuku.Shizuku
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ShizukuState(
    val installed: Boolean,
    val running: Boolean,
    val permission: String,
    val connected: Boolean,
    /** Shizuku 服务端版本（未安装时为空串；诊断用）。 */
    val version: String,
)

class ShizukuRuntime(
    context: Context,
    private val onOutput: (sessionId: String, dataBase64: String) -> Unit,
    private val onExit: (sessionId: String, exitCode: Int) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val binderStateLock = Any()
    private val binderFutureLock = Any()
    private val connectionFutureLock = Any()
    private var binderFuture: CompletableFuture<Unit>? = null
    private var connectionFuture: CompletableFuture<IDeviceShellService>? = null
    private var binderGeneration = 0L
    @Volatile private var permissionDeniedThisSession = false
    @Volatile private var permissionFuture: CompletableFuture<Int>? = null
    @Volatile private var service: IDeviceShellService? = null

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, DeviceShellUserService::class.java),
    )
        .daemon(false)
        .tag("dsh-device-shell")
        .processNameSuffix("device_shell")
        .debuggable(BuildConfig.DEBUG)
        .version(USER_SERVICE_VERSION)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        synchronized(binderStateLock) {
            binderGeneration++
            permissionDeniedThisSession = false
        }
        synchronized(binderFutureLock) {
            binderFuture?.complete(Unit)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(binderStateLock) {
            binderGeneration++
        }
        service = null
        permissionFuture?.completeExceptionally(RemoteException("Shizuku binder died"))
        synchronized(binderFutureLock) {
            binderFuture?.completeExceptionally(RemoteException("Shizuku binder died"))
            binderFuture = null
        }
        synchronized(connectionFutureLock) {
            connectionFuture?.completeExceptionally(RemoteException("Shizuku binder died"))
            connectionFuture = null
        }
        failSessions(255)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.isBinderAlive) {
                failServiceConnection(RemoteException("Shizuku returned an invalid UserService binder"))
                return
            }
            val connected = IDeviceShellService.Stub.asInterface(binder)
            service = connected
            synchronized(connectionFutureLock) {
                connectionFuture?.complete(connected)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            failServiceConnection(RemoteException("Shizuku UserService disconnected"))
        }

        override fun onBindingDied(name: ComponentName?) {
            failServiceConnection(RemoteException("Shizuku UserService binding died"))
        }

        override fun onNullBinding(name: ComponentName?) {
            failServiceConnection(RemoteException("Shizuku returned a null UserService binding"))
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener, mainHandler)
        Shizuku.addBinderDeadListener(binderDeadListener, mainHandler)
    }

    private val callback = object : IDeviceShellCallback.Stub() {
        override fun onOutput(sessionId: String?, data: ByteArray?) {
            if (sessionId == null || !SESSION_PATTERN.matches(sessionId) || data == null || data.isEmpty() || data.size > 32 * 1024) return
            onOutput(sessionId, Base64.getEncoder().encodeToString(data))
        }

        override fun onExit(sessionId: String?, exitCode: Int) {
            if (sessionId != null && SESSION_PATTERN.matches(sessionId)) {
                sessions.remove(sessionId)
                onExit(sessionId, exitCode.coerceIn(0, 255))
            }
        }
    }

    fun state(): ShizukuState {
        val installed = appContext.packageManager.resolveContentProvider(SHIZUKU_AUTHORITY, 0) != null
        val running = installed && try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        val granted = running && try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        val permission = when {
            granted -> "granted"
            running && (permissionDeniedThisSession || permissionRationaleVisible()) -> "denied"
            else -> "undetermined"
        }
        val connected = granted && liveService() != null
        val version = if (installed && running) {
            try {
                Shizuku.getVersion().toString()
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }
        return ShizukuState(installed, running, permission, connected, version)
    }

    @Synchronized
    fun requestPermission(): ShizukuState {
        val current = state()
        if (!current.installed) throw RuntimeFailure("SHIZUKU_UNAVAILABLE", "请先安装 Shizuku")
        if (!current.running) awaitBinder()
        if (Shizuku.isPreV11()) {
            throw RuntimeFailure("SHIZUKU_VERSION_UNSUPPORTED", "当前 Shizuku 版本过旧，请升级后重试")
        }
        if (state().permission == "granted") return connect()

        val requestGeneration = synchronized(binderStateLock) { binderGeneration }
        val result = CompletableFuture<Int>()
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) result.complete(grantResult)
        }
        permissionFuture = result
        var listenerAdded = false
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            listenerAdded = true
            val posted = mainHandler.post {
                try {
                    Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
            if (!posted) {
                result.completeExceptionally(IllegalStateException("main handler rejected permission request"))
            }
            val grantResult = result.get(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            synchronized(binderStateLock) {
                if (binderGeneration != requestGeneration) {
                    throw RuntimeFailure("SHIZUKU_BINDER_UNAVAILABLE", "Shizuku 连接已更新，请重试授权")
                }
                permissionDeniedThisSession = grantResult != PackageManager.PERMISSION_GRANTED
            }
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                throw RuntimeFailure("SHIZUKU_PERMISSION_DENIED", "Shizuku 权限未授予")
            }
            return connect()
        } catch (error: TimeoutException) {
            throw RuntimeFailure("SHIZUKU_PERMISSION_TIMEOUT", "等待 Shizuku 授权超时", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeFailure("SHIZUKU_PERMISSION_INTERRUPTED", "等待 Shizuku 授权被中断", error)
        } catch (error: ExecutionException) {
            throw RuntimeFailure("SHIZUKU_PERMISSION_FAILED", "无法向 Shizuku 请求授权", error.cause ?: error)
        } catch (error: RuntimeFailure) {
            throw error
        } catch (error: Throwable) {
            throw RuntimeFailure("SHIZUKU_PERMISSION_FAILED", "无法向 Shizuku 请求授权", error)
        } finally {
            if (listenerAdded) {
                try {
                    Shizuku.removeRequestPermissionResultListener(listener)
                } catch (_: Throwable) {
                    // Binder death already invalidates the listener registration.
                }
            }
            if (permissionFuture === result) permissionFuture = null
        }
    }

    fun openManager() {
        val launch = appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch ?: Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$SHIZUKU_PACKAGE"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
        } catch (error: Exception) {
            try {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (fallbackError: Exception) {
                throw RuntimeFailure("SHIZUKU_OPEN_FAILED", "无法打开 Shizuku", fallbackError)
            }
        }
    }

    fun connect(): ShizukuState {
        requirePermission()
        requireService()
        return state()
    }

    fun create(columns: Int, rows: Int): String {
        UbuntuTerminalManager.validateSize(columns, rows)
        requirePermission()
        val id = try {
            requireService().createSession(columns, rows, callback)
        } catch (error: RemoteException) {
            service = null
            throw RuntimeFailure("SHIZUKU_SERVICE_FAILED", "无法创建设备 Shell", error)
        }
        if (!SESSION_PATTERN.matches(id)) {
            throw RuntimeFailure("SESSION_ID_INVALID", "设备 Shell 返回无效会话标识")
        }
        sessions.add(id)
        return id
    }

    fun contains(sessionId: String): Boolean = sessions.contains(sessionId)

    fun hasSessions(): Boolean = sessions.isNotEmpty()

    fun write(sessionId: String, data: ByteArray) {
        requireSession(sessionId)
        if (data.isEmpty() || data.size > RuntimeLimits.MAX_TERMINAL_INPUT_BYTES) {
            throw RuntimeFailure("TERMINAL_INPUT_INVALID", "终端输入长度无效")
        }
        try {
            requireService().write(sessionId, data)
        } catch (error: RemoteException) {
            throw RuntimeFailure("TERMINAL_WRITE_FAILED", "无法写入设备 Shell", error)
        }
    }

    fun resize(sessionId: String, columns: Int, rows: Int) {
        UbuntuTerminalManager.validateSize(columns, rows)
        requireSession(sessionId)
        try {
            requireService().resize(sessionId, columns, rows)
        } catch (error: RemoteException) {
            throw RuntimeFailure("TERMINAL_RESIZE_FAILED", "无法调整设备 Shell", error)
        }
    }

    fun close(sessionId: String) {
        requireSession(sessionId)
        try {
            requireService().closeSession(sessionId)
        } catch (error: RemoteException) {
            sessions.remove(sessionId)
            throw RuntimeFailure("TERMINAL_CLOSE_FAILED", "无法关闭设备 Shell", error)
        }
    }

    fun closeAllAndWait(timeoutMillis: Long = 4_000) {
        val current = service
        if (current != null) {
            try {
                current.closeAll()
            } catch (_: RemoteException) {
                service = null
            }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (sessions.isNotEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeFailure("TERMINAL_STOP_INTERRUPTED", "等待设备 Shell 关闭时被中断", error)
            }
        }
        if (sessions.isNotEmpty()) failSessions(255)
    }

    fun shutdown() {
        var failure: RuntimeFailure? = null
        try {
            closeAllAndWait()
        } catch (error: RuntimeFailure) {
            failure = error
        }
        val current = service
        if (current != null) {
            try {
                current.destroy()
            } catch (_: RemoteException) {
                // A dead UserService is already stopped.
            }
        }
        val unbound = CountDownLatch(1)
        val unbind = Runnable {
            try {
                Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
            } catch (_: Throwable) {
                // Binding may already be gone after service death or permission revocation.
            } finally {
                unbound.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) unbind.run() else mainHandler.post(unbind)
        try {
            if (!unbound.await(2, TimeUnit.SECONDS) && failure == null) {
                failure = RuntimeFailure("SHIZUKU_UNBIND_TIMEOUT", "等待 Shizuku 用户服务退出超时")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            if (failure == null) failure = RuntimeFailure("SHIZUKU_UNBIND_INTERRUPTED", "等待 Shizuku 用户服务退出被中断", error)
        }
        service = null
        synchronized(connectionFutureLock) {
            connectionFuture?.cancel(true)
            connectionFuture = null
        }
        failSessions(255)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        failure?.let { throw it }
    }

    private fun requireService(): IDeviceShellService {
        awaitBinder()
        liveService()?.let { return it }
        val future = synchronized(connectionFutureLock) {
            liveService()?.let { return it }
            connectionFuture?.takeUnless { it.isDone } ?: CompletableFuture<IDeviceShellService>().also {
                connectionFuture = it
                val posted = mainHandler.post {
                    try {
                        Shizuku.bindUserService(serviceArgs, serviceConnection)
                    } catch (error: Throwable) {
                        it.completeExceptionally(error)
                    }
                }
                if (!posted) {
                    it.completeExceptionally(IllegalStateException("main handler rejected Shizuku binding"))
                }
            }
        }
        return try {
            future.get(SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            future.completeExceptionally(error)
            throw RuntimeFailure("SHIZUKU_SERVICE_TIMEOUT", "连接 Shizuku 用户服务超时", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            future.completeExceptionally(error)
            throw RuntimeFailure("SHIZUKU_SERVICE_INTERRUPTED", "连接 Shizuku 用户服务被中断", error)
        } catch (error: ExecutionException) {
            throw RuntimeFailure("SHIZUKU_SERVICE_FAILED", "Shizuku 用户服务连接失败", error.cause ?: error)
        } catch (error: Exception) {
            throw RuntimeFailure("SHIZUKU_SERVICE_TIMEOUT", "连接 Shizuku 用户服务超时", error)
        }
    }

    private fun requirePermission() {
        awaitBinder()
        val granted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (!granted) {
            throw RuntimeFailure("SHIZUKU_PERMISSION_REQUIRED", "设备 Shell 需要 Shizuku 授权")
        }
    }

    private fun awaitBinder() {
        if (tryPingBinder()) return
        val future = synchronized(binderFutureLock) {
            if (tryPingBinder()) return
            binderFuture?.takeUnless { it.isDone } ?: CompletableFuture<Unit>().also { binderFuture = it }
        }
        try {
            future.get(BINDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            future.completeExceptionally(error)
            throw RuntimeFailure("SHIZUKU_BINDER_TIMEOUT", "未能连接 Shizuku，请确认服务已启动", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            future.completeExceptionally(error)
            throw RuntimeFailure("SHIZUKU_BINDER_INTERRUPTED", "等待 Shizuku 连接被中断", error)
        } catch (error: ExecutionException) {
            throw RuntimeFailure("SHIZUKU_BINDER_UNAVAILABLE", "Shizuku 连接已断开", error.cause ?: error)
        }
        if (!tryPingBinder()) throw RuntimeFailure("SHIZUKU_BINDER_UNAVAILABLE", "Shizuku 服务不可用")
    }

    private fun tryPingBinder(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun permissionRationaleVisible(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.shouldShowRequestPermissionRationale()
    } catch (_: Throwable) {
        false
    }

    private fun liveService(): IDeviceShellService? {
        val current = service ?: return null
        return if (current.asBinder().isBinderAlive && current.asBinder().pingBinder()) current else {
            service = null
            null
        }
    }

    private fun failServiceConnection(error: Throwable) {
        service = null
        synchronized(connectionFutureLock) {
            connectionFuture?.completeExceptionally(error)
            connectionFuture = null
        }
        failSessions(255)
    }

    private fun requireSession(sessionId: String) {
        if (!SESSION_PATTERN.matches(sessionId) || !sessions.contains(sessionId)) {
            throw RuntimeFailure("SESSION_NOT_FOUND", "设备 Shell 会话不存在或已结束")
        }
    }

    private fun failSessions(exitCode: Int) {
        sessions.toList().forEach { id -> if (sessions.remove(id)) onExit(id, exitCode) }
    }

    companion object {
        private const val SHIZUKU_AUTHORITY = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST_CODE = 7319
        private const val PERMISSION_TIMEOUT_SECONDS = 60L
        private const val BINDER_TIMEOUT_SECONDS = 8L
        private const val SERVICE_TIMEOUT_SECONDS = 10L
        private const val USER_SERVICE_VERSION = 2
        private val SESSION_PATTERN = Regex("^[a-f0-9-]{36}$")
    }
}
