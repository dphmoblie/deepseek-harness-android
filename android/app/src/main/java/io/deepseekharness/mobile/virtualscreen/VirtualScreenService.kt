package io.deepseekharness.mobile.virtualscreen

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.deepseekharness.mobile.runtime.*
import io.deepseekharness.mobile.runtime.audit.AuditEvent
import io.deepseekharness.mobile.runtime.audit.AuditResult
import io.deepseekharness.mobile.runtime.audit.PrivateAuditLog
import io.deepseekharness.mobile.shizuku.DeviceShellAccess
import io.deepseekharness.mobile.shizuku.ShizukuRuntime
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 持有副屏会话；退出预览页或收起小窗不会销毁目标应用。 */
class VirtualScreenService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val owner = Binder()
    private val ending = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val audit by lazy { PrivateAuditLog(this) }
    private var acquiredRuntime = false
    private val actionSlot = java.util.concurrent.Semaphore(1)
    private var shizuku: ShizukuRuntime? = null
    @Volatile private var session = ""
    @Volatile private var snapshot = JSONObject().put("active", false)
    private var overlay: View? = null
    private var preview: VirtualScreenPreview? = null
    private val sink = object : RuntimeEventSink {
        override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
        override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
        override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "目标应用副屏", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, VirtualScreenActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("目标应用副屏运行中").setContentText("点此查看画面；可随时结束副屏")
            .setContentIntent(open).setOngoing(true).addAction(0, "结束副屏", stop).build()
        try { ServiceCompat.startForeground(this, 7321, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) }
        catch (_: Exception) { lastError = "无法启动副屏前台服务"; stopSelf(); return START_NOT_STICKY }
        if (intent == null || intent.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (current != null) return START_NOT_STICKY
        current = this
        lastError = ""
        val component = intent.getStringExtra("component") ?: ""
        val landscape = intent.getBooleanExtra("landscape", false)
        executor.execute {
            audit.record(AuditEvent.VIRTUAL_SCREEN_SESSION, AuditResult.STARTED)
            try {
                requireAccess()
                val runtime = RuntimeHost.acquire(this, sink).terminals.shizuku
                acquiredRuntime = true
                shizuku = runtime
                check(!ending.get())
                val value = runtime.startVirtualScreen(component, if (landscape) 1280 else 726, if (landscape) 580 else 1600, if (landscape) 256 else 320, owner)
                session = value.getString("sessionId")
                snapshot = value
                audit.record(AuditEvent.VIRTUAL_SCREEN_SESSION, AuditResult.SUCCEEDED)
            } catch (_: Exception) {
                audit.record(AuditEvent.VIRTUAL_SCREEN_SESSION, AuditResult.FAILED)
                lastError = "副屏启动失败：请检查 Shizuku 授权、目标应用和设备副屏支持"; main.post { stopSelf() }
            }
        }
        main.postDelayed(health, 1500)
        return START_NOT_STICKY
    }

    private val health = object : Runnable {
        override fun run() {
            if (ending.get()) return
            executor.execute {
                try {
                    check(DeviceShellAccess.enabled(this@VirtualScreenService))
                    if (session.isNotEmpty()) {
                        val live = checkNotNull(shizuku).virtualScreenState()
                        check(live.optString("sessionId") == session && live.optBoolean("active"))
                        snapshot = live
                    }
                } catch (_: Exception) { lastError = "副屏已停止：授权、Shizuku 连接或副屏会话失效"; main.post { stopSelf() } }
                if (!ending.get()) main.postDelayed(this, 1500)
            }
        }
    }

    fun state(): JSONObject = JSONObject(snapshot.toString()).put("starting", session.isEmpty() && !ending.get())
        .put("active", snapshot.optBoolean("active") && !ending.get()).put("stopping", ending.get()).put("error", lastError)

    fun canObserve(): Boolean = !ending.get() && DeviceShellAccess.enabled(this) &&
        !getSystemService(KeyguardManager::class.java).isDeviceLocked && getSystemService(PowerManager::class.java).isInteractive

    private fun requireAccess() {
        if (!DeviceShellAccess.enabled(this)) throw RuntimeFailure("DEVICE_SHELL_DISABLED", "请先开启 AI Shell")
        if (ending.get()) throw RuntimeFailure("VIRTUAL_SCREEN_STOPPED", "副屏正在结束")
        if (!canObserve()) throw RuntimeFailure("VIRTUAL_SCREEN_LOCKED", "锁屏或熄屏时暂停读取与操作")
    }

    fun screenshot(id: String, fromAi: Boolean = false): ByteArray {
        requireAccess()
        check(id.isNotEmpty() && id == session) { "副屏会话已失效" }
        if (!actionSlot.tryAcquire()) throw RuntimeFailure("VIRTUAL_SCREEN_BUSY", "上一步副屏操作尚未完成")
        return try {
            checkNotNull(shizuku).virtualScreenSnapshot(id).also {
                requireAccess()
                if (fromAi) audit.record(AuditEvent.VIRTUAL_SCREEN_READ, AuditResult.SUCCEEDED)
            }
        } catch (e: Exception) {
            if (fromAi) audit.record(AuditEvent.VIRTUAL_SCREEN_READ, AuditResult.FAILED)
            throw e
        } finally { actionSlot.release() }
    }

    fun action(parameters: JSONObject): JSONObject {
        check(parameters.getString("sessionId") == session && session.isNotEmpty()) { "副屏会话已失效" }
        if (parameters.getString("action") == "stop") {
            main.post { stopSelf() }
            return JSONObject().put("stopping", true)
        }
        if (!actionSlot.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)) throw RuntimeFailure("VIRTUAL_SCREEN_BUSY", "上一步副屏操作尚未完成")
        return try {
            requireAccess()
            checkNotNull(shizuku).virtualScreenAction(parameters.toString()).also { audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.SUCCEEDED) }
        } catch (e: Exception) { audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.FAILED); throw e }
        finally { actionSlot.release() }
    }

    fun showOverlay() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(Settings.canDrawOverlays(this)) { "请先授予悬浮窗权限" }
        check(session.isNotEmpty() && !ending.get()) { "副屏尚未就绪" }
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val metrics = resources.displayMetrics
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xf0222630.toInt()) }
        val toolbar = LinearLayout(this)
        val drag = TextView(this).apply { text = "目标应用 · 拖动"; setTextColor(-1); setPadding(12, 12, 12, 12) }
        toolbar.addView(drag, LinearLayout.LayoutParams(0, -2, 1f))
        val page = Button(this).apply {
            text = "展开"
            setOnClickListener {
                runCatching { startActivity(Intent(this@VirtualScreenService, VirtualScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onSuccess { hideOverlay() }
                    .onFailure { Toast.makeText(this@VirtualScreenService, "无法展开，请从通知打开副屏页面", Toast.LENGTH_LONG).show() }
            }
        }
        val close = Button(this).apply { text = "收起"; setOnClickListener { hideOverlay() } }
        toolbar.addView(page); toolbar.addView(close); panel.addView(toolbar)
        val image = VirtualScreenPreview(this).also { preview = it }
        panel.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val w = minOf((320 * metrics.density).toInt(), (metrics.widthPixels * .9f).toInt())
        val h = minOf((480 * metrics.density).toInt(), (metrics.heightPixels * .7f).toInt())
        val params = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE, android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 0; y = (60 * metrics.density).toInt()
        }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        drag.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; originX = params.x; originY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (originX + e.rawX - downX).toInt().coerceIn(0, maxOf(0, metrics.widthPixels - w))
                    params.y = (originY + e.rawY - downY).toInt().coerceIn(0, maxOf(0, metrics.heightPixels - h))
                    if (overlay === panel) runCatching { wm.updateViewLayout(panel, params) }.onFailure { hideOverlay() }
                }
                MotionEvent.ACTION_UP -> view.performClick()
            }
            true
        }
        try { wm.addView(panel, params); overlay = panel }
        catch (e: Exception) { preview = null; throw e }
    }

    fun hideOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null; preview = null
    }

    override fun onDestroy() {
        ending.set(true)
        if (current === this) current = null
        main.removeCallbacks(health)
        hideOverlay()
        executor.execute {
            var released = true
            try {
                if (session.isNotEmpty()) shizuku?.closeVirtualScreen(session)
            } catch (_: Exception) { released = false }
            finally {
                audit.record(AuditEvent.VIRTUAL_SCREEN_SESSION, if (released) AuditResult.SUCCEEDED else AuditResult.FAILED,
                    if (released) "CLOSED" else "CLOSE_FAILED")
                if (acquiredRuntime) RuntimeHost.detachPluginSink(sink)
            }
        }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "target-virtual-screen"
        @Volatile var current: VirtualScreenService? = null; private set
        @Volatile var lastError = ""; private set
    }
}

/** 限帧预览只在可见时拉取 PNG；副屏采集在 Shizuku 进程持续运行。 */
class VirtualScreenPreview(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    private var executor: java.util.concurrent.ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    private var displayed: android.graphics.Bitmap? = null
    private var observedSession = ""
    private var observedAt = 0L
    private val inputBusy = AtomicBoolean(false)
    private var down: FloatArray? = null
    private var downSession = ""
    private var downAt = 0L
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) { clearFrame() }
    }
    var report: (String) -> Unit = {}
    init {
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(android.graphics.Color.BLACK)
        contentDescription = "目标应用副屏画面，支持点击和滑动"
        setOnTouchListener { view, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                down = point(e); downSession = observedSession; downAt = e.eventTime
            } else if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                down = null
            } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                view.performClick()
                val start = down; down = null
                val end = point(e)
                val service = VirtualScreenService.current
                val worker = executor
                if (start != null && end != null && service != null && worker != null && downSession == observedSession &&
                    service.canObserve() && SystemClock.elapsedRealtime() - observedAt < 3000 && inputBusy.compareAndSet(false, true)) {
                    val swipe = kotlin.math.hypot(end[0] - start[0], end[1] - start[1]) > 12
                    val request = JSONObject().put("sessionId", observedSession).put("action", if (swipe) "swipe" else "tap")
                        .put("x", start[0].toInt()).put("y", start[1].toInt())
                    if (swipe) request.put("endX", end[0].toInt()).put("endY", end[1].toInt()).put("durationMs", (e.eventTime - downAt).coerceIn(100, 2000).toInt())
                    val version = generation
                    worker.execute {
                        try {
                            if (version == generation) service.action(request)
                        } catch (_: Exception) { main.post { report("操作未执行，请确认副屏中的目标应用状态") } }
                        finally { inputBusy.set(false) }
                    }
                }
            }
            true
        }
    }

    private fun point(event: MotionEvent): FloatArray? {
        val bitmap = displayed ?: return null
        val value = floatArrayOf(event.x - paddingLeft, event.y - paddingTop)
        val inverse = android.graphics.Matrix()
        if (!imageMatrix.invert(inverse)) return null
        inverse.mapPoints(value)
        return value.takeIf { it[0] >= 0 && it[1] >= 0 && it[0] < bitmap.width && it[1] < bitmap.height }
    }

    private fun clearFrame() {
        down = null
        setImageDrawable(null); displayed?.recycle(); displayed = null
        observedSession = ""; observedAt = 0
    }

    private val tick = object : Runnable {
        override fun run() {
            val worker = executor ?: return
            val version = generation
            worker.execute {
                var bitmap: android.graphics.Bitmap? = null
                var id = ""
                val message = try {
                    val service = checkNotNull(VirtualScreenService.current)
                    id = service.state().getString("sessionId")
                    val bytes = service.screenshot(id)
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    checkNotNull(bitmap)
                    "副屏预览 · 点击或滑动操作 · 静止页面复用最近一帧"
                } catch (_: Exception) { "等待副屏画面；锁屏、断连或应用离开副屏时暂停显示" }
                main.post {
                    if (version != generation || executor == null) { bitmap?.recycle(); return@post }
                    val current = VirtualScreenService.current
                    if (current?.canObserve() != true || current.state().optString("sessionId") != id) { bitmap?.recycle(); bitmap = null }
                    setImageBitmap(bitmap)
                    displayed?.recycle(); displayed = bitmap
                    observedSession = id; observedAt = SystemClock.elapsedRealtime()
                    report(message)
                    main.postDelayed(this, 250)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        androidx.core.content.ContextCompat.registerReceiver(context, screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        executor = Executors.newSingleThreadExecutor(); generation++; main.post(tick)
    }
    override fun onDetachedFromWindow() {
        generation++; main.removeCallbacks(tick); executor?.shutdown(); executor = null
        runCatching { context.unregisterReceiver(screenOff) }
        clearFrame()
        super.onDetachedFromWindow()
    }
}
