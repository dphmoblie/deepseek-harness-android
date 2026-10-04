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
    private var lastAiFrameAt = 0L
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
        // 节点树能不能读只取决于无障碍服务是否已连接：把它放进状态里，
        // 让调用方一眼看出「读不到树」是无障碍没开还是副屏没起来。
        .put("treeSupported", VirtualScreenTree.available())

    fun canObserve(): Boolean = !ending.get() && DeviceShellAccess.enabled(this) &&
        !getSystemService(KeyguardManager::class.java).isDeviceLocked && getSystemService(PowerManager::class.java).isInteractive

    private fun requireAccess() {
        if (!DeviceShellAccess.enabled(this)) throw RuntimeFailure("DEVICE_SHELL_DISABLED", "请先开启 AI Shell")
        if (ending.get()) throw RuntimeFailure("VIRTUAL_SCREEN_STOPPED", "副屏正在结束")
        if (!canObserve()) throw RuntimeFailure("VIRTUAL_SCREEN_LOCKED", "锁屏或熄屏时暂停读取与操作")
    }

    /**
     * 会话校验：请求的会话与当前会话不一致说明用户已重新开始或结束副屏，属于可重试情形，
     * 按 [VirtualScreenPolicy.sessionFailure] 返回 `VIRTUAL_SCREEN_STOPPED`；
     * 会话尚未建立时返回瞬时的 `VIRTUAL_SCREEN_BUSY`，都不再报成「副屏不可用」。
     */
    private fun requireSession(requested: String) {
        VirtualScreenPolicy.sessionFailure(session, requested)?.let { throw it }
    }

    /** 副屏显示编号来自宿主状态快照；还没拿到时属于瞬时状态，调用方稍后重试即可。 */
    private fun displayId(): Int {
        val id = snapshot.optInt("displayId", 0)
        if (id <= 0) throw RuntimeFailure("VIRTUAL_SCREEN_BUSY", "尚未取得副屏显示编号，请稍后重试")
        return id
    }

    /**
     * 文本分流：可打印 ASCII 继续走设备 Shell 的 `input text`（与现有行为完全一致），
     * 含中文、emoji 等字符时改由无障碍服务定向注入——只有它能送出 Unicode。
     */
    private fun needsAccessibilityText(value: String): Boolean = VirtualScreenPolicy.needsAccessibilityText(value)

    fun screenshot(id: String, fromAi: Boolean = false): ByteArray {
        requireAccess()
        requireSession(id)
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

    /**
     * 返回截图与帧新鲜度元数据；图像仍只在内存和受控管道中传输。
     * `frameBlank` 由宿主状态原样透传，宿主状态里没有该字段时不给出默认值。
     */
    fun screenshotEnvelope(id: String): JSONObject {
        val bytes = screenshot(id, fromAi = true)
        val meta = runCatching { checkNotNull(shizuku).virtualScreenState() }
            .getOrElse { state() }
        snapshot = meta
        val frameAt = meta.optLong("frameAtElapsedMs", 0L)
        val reused = frameAt > 0L && frameAt == lastAiFrameAt
        lastAiFrameAt = frameAt
        val envelope = JSONObject().put("imageBase64", java.util.Base64.getEncoder().encodeToString(bytes))
            .put("packageName", meta.optString("packageName"))
            .put("frameAtElapsedMs", frameAt)
            .put("frameReused", reused)
        if (meta.has("frameBlank")) envelope.put("frameBlank", meta.optBoolean("frameBlank"))
        return envelope
    }

    fun action(parameters: JSONObject): JSONObject {
        requireSession(parameters.getString("sessionId"))
        val verb = parameters.getString("action")
        if (verb == "stop") {
            main.post { stopSelf() }
            return JSONObject().put("stopping", true)
        }
        // 触摸直传是逐事件的高频路径（拖动时每秒可能几十个事件）：
        // 不能和单次动作共用那把 2 秒的信号量，否则第二个事件就会报成「上一步尚未完成」。
        // 真正的串行由宿主侧的 @Synchronized 保证，这里只做访问前提检查。
        if (verb == "touch") {
            requireAccess()
            return checkNotNull(shizuku).virtualScreenAction(parameters.toString())
        }
        // 节点树与中文注入都跑在本进程的无障碍服务里，不经过设备 Shell：
        // 显示编号取宿主状态里的值，读不到时按可重试的瞬时状态报错。
        if (verb == "tree" || (verb == "text" && needsAccessibilityText(parameters.optString("text")))) {
            requireAccess()
            val id = displayId()
            return if (verb == "tree") {
                VirtualScreenTree.dump(id, parameters.optInt("maxDepth", 4), VirtualScreenTree.MAX_NODES).also {
                    audit.record(AuditEvent.VIRTUAL_SCREEN_READ, AuditResult.SUCCEEDED)
                }
            } else {
                val text = parameters.optString("text")
                val injected = runCatching { VirtualScreenTree.setText(id, text) }.getOrDefault(false)
                audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, if (injected) AuditResult.SUCCEEDED else AuditResult.FAILED)
                if (!injected) {
                    throw RuntimeFailure("VIRTUAL_SCREEN_UNAVAILABLE", "中文文本注入未完成：请确认已在系统设置里开启 DeepSeek 的无障碍服务，并让目标输入框保持聚焦")
                }
                JSONObject().put("sessionId", parameters.getString("sessionId")).put("displayId", id)
                    .put("injected", text.length)
            }
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

/** 逐事件触摸直传时 MOVE 的最小发送间隔；屏幕采样可到 120 Hz，合并后只丢中间采样点，抬起前那一个点仍会补发。 */
private const val MOVE_INTERVAL_MILLIS = 16L

/** 限帧预览只在可见时拉取 PNG；副屏采集在 Shizuku 进程持续运行。 */
class VirtualScreenPreview(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    private var executor: java.util.concurrent.ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    private var displayed: android.graphics.Bitmap? = null
    private var observedSession = ""
    private var observedAt = 0L
    /** 最近一次状态里的触摸通道：`stream` 表示可逐事件直传，其它值退回离散点击/滑动。 */
    private var observedChannel = ""
    private val inputBusy = AtomicBoolean(false)
    private var down: FloatArray? = null
    private var downSession = ""
    private var downAt = 0L
    private var streaming = false
    private var lastMoveAt = 0L
    private var queuedMove: FloatArray? = null
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) { clearFrame() }
    }
    var report: (String) -> Unit = {}
    init {
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(android.graphics.Color.BLACK)
        contentDescription = "目标应用副屏画面，支持点击和滑动"
        setOnTouchListener { view, e ->
            val service = VirtualScreenService.current
            val worker = executor
            val at = point(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = at; downSession = observedSession; downAt = e.eventTime
                    streaming = false; queuedMove = null; lastMoveAt = 0L
                    // 只有设备端报告 stream 通道、且会话与画面都新鲜时才逐事件直传；
                    // 否则保持原先的「抬起时发一个 tap/swipe」行为。
                    if (at != null && service != null && worker != null && observedChannel == "stream" &&
                        service.canObserve() && SystemClock.elapsedRealtime() - observedAt < 3000) {
                        streaming = true
                        sendTouch(worker, service, "down", at)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (streaming && at != null) {
                        // 直传的 MOVE 按最短间隔合并：屏幕采样可能到 120 Hz，逐个转发会压满 Binder，
                        // 合并后只丢中间采样点，抬起前那一个点仍会补发。
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastMoveAt < MOVE_INTERVAL_MILLIS) queuedMove = at
                        else { lastMoveAt = now; queuedMove = null; sendTouch(worker, service, "move", at) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    val start = down; down = null
                    if (streaming) {
                        streaming = false
                        (queuedMove ?: at)?.let { sendTouch(worker, service, "up", it) }
                        queuedMove = null
                    } else if (start != null && at != null && service != null && worker != null && downSession == observedSession &&
                        service.canObserve() && SystemClock.elapsedRealtime() - observedAt < 3000 && inputBusy.compareAndSet(false, true)) {
                        val swipe = kotlin.math.hypot(at[0] - start[0], at[1] - start[1]) > 12
                        val request = JSONObject().put("sessionId", observedSession).put("action", if (swipe) "swipe" else "tap")
                            .put("x", start[0].toInt()).put("y", start[1].toInt())
                        if (swipe) request.put("endX", at[0].toInt()).put("endY", at[1].toInt()).put("durationMs", (e.eventTime - downAt).coerceIn(100, 2000).toInt())
                        val version = generation
                        worker.execute {
                            try {
                                if (version == generation) service.action(request)
                            } catch (_: Exception) { main.post { report("操作未执行，请确认副屏中的目标应用状态") } }
                            finally { inputBusy.set(false) }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (streaming) {
                        streaming = false
                        (queuedMove ?: at)?.let { sendTouch(worker, service, "cancel", it) }
                        queuedMove = null
                    }
                    down = null
                }
            }
            true
        }
    }

    /** 逐事件触摸直传；设备端对 touch 不占用单次动作信号量，串行由宿主侧保证。 */
    private fun sendTouch(worker: java.util.concurrent.ExecutorService?, service: VirtualScreenService?, phase: String, at: FloatArray) {
        if (worker == null || service == null) return
        val version = generation
        val session = downSession.ifEmpty { observedSession }
        val request = JSONObject().put("sessionId", session).put("action", "touch").put("phase", phase)
            .put("x", at[0].toInt()).put("y", at[1].toInt())
        worker.execute {
            try {
                if (version == generation) service.action(request)
            } catch (_: Exception) {
                if (phase == "down") main.post { report("触摸直传未生效，可改用点击或滑动操作") }
            }
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
        setImageDrawable(null); displayed = null
        observedSession = ""; observedAt = 0
    }

    private val tick = object : Runnable {
        override fun run() {
            val worker = executor ?: return
            val version = generation
            worker.execute {
                var bitmap: android.graphics.Bitmap? = null
                var id = ""
                var channel = ""
                val message = try {
                    val service = checkNotNull(VirtualScreenService.current)
                    val state = service.state()
                    id = state.getString("sessionId")
                    channel = state.optString("touchChannel", "")
                    val fps = state.optDouble("frameFps", 0.0)
                    val bytes = service.screenshot(id)
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    checkNotNull(bitmap)
                    // 状态行如实展示当前通道与实测帧率，方便用户判断直传/帧率是否生效。
                    val touch = if (channel == "stream") "触摸直传" else "点击或滑动操作"
                    val rate = if (fps > 0.0) String.format(java.util.Locale.US, "%.1f fps", fps) else "等待首帧"
                    "副屏预览 · $touch · $rate · 静止页面复用最近一帧"
                } catch (_: Exception) { "等待副屏画面；锁屏、断连或应用离开副屏时暂停显示" }
                main.post {
                    if (version != generation || executor == null) { return@post }
                    val current = VirtualScreenService.current
                    if (current?.canObserve() != true || current.state().optString("sessionId") != id) { bitmap = null }
                    setImageBitmap(bitmap)
                    displayed = bitmap
                    observedSession = id; observedAt = SystemClock.elapsedRealtime()
                    if (channel.isNotEmpty()) observedChannel = channel
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
