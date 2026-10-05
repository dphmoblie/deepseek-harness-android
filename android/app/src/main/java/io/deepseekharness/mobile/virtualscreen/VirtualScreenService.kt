package io.deepseekharness.mobile.virtualscreen

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.deepseekharness.mobile.AppThemePreference
import io.deepseekharness.mobile.R
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
/** 档位 → 标题资源；副屏页面、副屏悬浮窗与状态行共用，避免几处各写一份中文。 */
private fun modeTitleRes(mode: String): Int = when (mode) {
    "15fps" -> R.string.virtual_screen_mode_15fps
    "30fps" -> R.string.virtual_screen_mode_30fps
    "60fps" -> R.string.virtual_screen_mode_60fps
    "120fps" -> R.string.virtual_screen_mode_120fps
    "185fps" -> R.string.virtual_screen_mode_185fps
    else -> R.string.virtual_screen_mode_limited
}

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
    /**
     * 本次会话使用的副屏规格，在 onStartCommand 里从 Intent 解码后写入。
     * 悬浮小窗按它换算面板比例；`onStartCommand` 一开始就写入，所以「重开服务」不会沿用上一次的方向。
     */
    @Volatile private var spec = VirtualScreenSpec.PORTRAIT
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
        // 尺寸来源收敛到规格层：三个整型 extra 读不到或读到非法值一律回退竖屏预设，
        // 所以只有旧 `landscape` 布尔的 Intent（升级中途的旧页面、旧通知）仍按原样工作。
        // 解码后立刻写进字段：小窗换算用的是本次会话真正生效的那一份规格。
        spec = VirtualScreenSpec.decode(intent)
        executor.execute {
            audit.record(AuditEvent.VIRTUAL_SCREEN_SESSION, AuditResult.STARTED)
            try {
                requireAccess()
                val runtime = RuntimeHost.acquire(this, sink).terminals.shizuku
                acquiredRuntime = true
                shizuku = runtime
                check(!ending.get())
                val sessionSpec = spec
                val value = runtime.startVirtualScreen(component, sessionSpec.widthPx, sessionSpec.heightPx, sessionSpec.densityDpi, owner)
                session = value.getString("sessionId")
                snapshot = value
                audit.record(AuditEvent.VIRTUAL_SCREEN_SESSION, AuditResult.SUCCEEDED)
            } catch (error: Exception) {
                audit.record(AuditEvent.VIRTUAL_SCREEN_SESSION, AuditResult.FAILED)
                // 原生失败原因（调用方的 check/require 文案）直接带给界面：否则只剩一句泛化提示，真机排查无从下手。
                val reason = error.cause?.message ?: error.message
                lastError = if (reason.isNullOrBlank()) "副屏启动失败：请检查 Shizuku 授权、目标应用和设备副屏支持" else "副屏启动失败：$reason"
                main.post { stopSelf() }
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

    /** 预览专用硬件帧，绕过 PNG；调用方必须在替换或销毁画面时关闭缓冲区。 */
    fun hardwareFrame(id: String): android.hardware.HardwareBuffer {
        requireAccess()
        requireSession(id)
        return checkNotNull(shizuku).virtualScreenFrame(id)
    }

    /**
     * 向宿主刷新一次状态快照并返回（预览与 AI 截图共用，因此不写审计、不参与 frameReused 判定）。
     * `frameBlank` 与 `frameAtElapsedMs` 是判断「这一帧是不是空白、是不是新帧」的唯一依据：
     * health 每 1500 毫秒才刷一次快照，拿旧快照给当前帧贴标签会慢半拍到一拍半。
     * 读不到宿主状态时回落到本地快照，不抛异常——预览不该因为一次状态读取失败就停摆。
     */
    fun freshState(): JSONObject = runCatching { checkNotNull(shizuku).virtualScreenState() }
        .getOrElse { state() }
        .also { snapshot = it }

    /**
     * 返回截图与帧新鲜度元数据；图像仍只在内存和受控管道中传输。
     * `frameBlank` 由宿主状态原样透传，宿主状态里没有该字段时不给出默认值。
     */
    fun screenshotEnvelope(id: String): JSONObject {
        val bytes = screenshot(id, fromAi = true)
        val meta = freshState()
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
        // 跟随要排除「本应用自己」（副屏预览界面就跑在本应用里，把自己拉到副屏等于盖掉会话），
        // 而 Shizuku 用户服务进程里拿不到宿主包名：统一在这里注入一次；调用方显式传值时以调用方为准。
        if (verb == "follow" && !parameters.has(VirtualScreenPolicy.SELF_PACKAGE_FIELD)) {
            parameters.put(VirtualScreenPolicy.SELF_PACKAGE_FIELD, packageName)
        }
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
        // 节点树与**所有**文字注入都跑在本进程：无障碍回退链只有这里才拿得到服务实例；
        // 纯 ASCII 的按键兜底再由 textOnDisplay 接回设备 Shell 的既有通道（见该函数）。
        // 显示编号取宿主状态里的值，读不到时按可重试的瞬时状态报错。
        if (verb == "tree" || verb == "text") {
            requireAccess()
            val id = displayId()
            if (verb == "tree") {
                return VirtualScreenTree.dump(id, parameters.optInt("maxDepth", 4), VirtualScreenTree.MAX_NODES).also {
                    audit.record(AuditEvent.VIRTUAL_SCREEN_READ, AuditResult.SUCCEEDED)
                }
            }
            return textOnDisplay(parameters, id)
        }
        if (!actionSlot.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)) throw RuntimeFailure("VIRTUAL_SCREEN_BUSY", "上一步副屏操作尚未完成")
        return try {
            requireAccess()
            checkNotNull(shizuku).virtualScreenAction(parameters.toString()).also { audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.SUCCEEDED) }
        } catch (e: Exception) { audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.FAILED); throw e }
        finally { actionSlot.release() }
    }

    /**
     * `text` 动作：一律走本进程的回退链（[VirtualScreenTree.writeText]），再把「无障碍这条链没落地、
     * 但纯 ASCII 还能靠设备 Shell 送」的那一级接到既有 Shizuku 按键通道上。
     *
     * 返回结构与设备 Shell 侧共用同一份字段形状：`method`/`chars`/`submit`/`submitRequested`/`steps`。
     * 失败时抛 [RuntimeFailure]，码用回退链给出的那个（`VIRTUAL_SCREEN_TEXT_UNSUPPORTED` 等）——
     * **不再一律报 `VIRTUAL_SCREEN_UNAVAILABLE`**：副屏本身正常，写不进去是目标输入框的限制。
     */
    private fun textOnDisplay(parameters: JSONObject, displayId: Int): JSONObject {
        val submit = parameters.optBoolean("submit", false)
        val write = try {
            VirtualScreenTree.writeText(displayId, parameters.optString("text"), submit = submit)
        } catch (e: Exception) {
            audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.FAILED)
            throw RuntimeFailure(
                VirtualScreenTextPolicy.FailureCode.INVALID,
                VirtualScreenTextPolicy.failureMessage(VirtualScreenTextPolicy.FailureCode.INVALID),
                e,
            )
        }
        // 无障碍这一侧写不进去、链路里只剩纯 ASCII 的按键兜底：交给设备 Shell 再算一次真实结果。
        if (write.awaitsKeyEvents) return shellKeyEvents(parameters, write, submit)
        if (!write.succeeded) {
            audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.FAILED)
            throw RuntimeFailure(
                write.code ?: VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                write.message,
            )
        }
        audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.SUCCEEDED)
        return textEnvelope(parameters, displayId, write.method, write.chars, write.submitted, submit, write.steps)
    }

    /**
     * 纯 ASCII 的按键兜底：复用既有 Shizuku 通道（`ShellVirtualScreen.textAction` 的 `input text` /
     * 逐字符 `input keyevent`），把它的**真实**读数并进返回结构。
     *
     * `chars`/`submit` 只认实际写进去的数字：通道拿不到、写了一半、回车没按成，都如实报出来，
     * 不因为「请求过」就宣称成功；失败时把已经写进多少个字符也写清楚。
     */
    private fun shellKeyEvents(
        parameters: JSONObject,
        write: VirtualScreenTree.TextWrite,
        submit: Boolean,
    ): JSONObject {
        val total = parameters.optString("text").length
        // 设备 Shell 的回执分两种：正常回执是结构化 JSON（失败时带 code/reason，chars 仍是真实写入数）；
        // Binder 侧直接抛异常时（Shizuku 未就绪、会话已换、目标已离开副屏）这里如实降级并带上原因。
        val raw = runCatching { checkNotNull(shizuku).virtualScreenAction(parameters.toString()) }
        val shell = raw.getOrNull()
        val chars = shell?.optInt("chars", 0) ?: 0
        val submitted = shell?.optBoolean("submit", false) ?: false
        val shellSteps = shell?.optString("steps").orEmpty()
        val succeeded = shell != null && !shell.has("code")
        val attemptSteps = if (succeeded) {
            VirtualScreenTextPolicy.describe(keyEventAttempts(write.attempts)) +
                VirtualScreenTextPolicy.submitNote(true, submitted, submit)
        } else {
            VirtualScreenTextPolicy.describe(write.attempts) +
                VirtualScreenTextPolicy.submitNote(false, false, submit)
        }
        val steps = attemptSteps + if (shellSteps.isEmpty()) "" else "；设备 Shell：$shellSteps"
        if (succeeded) {
            audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.SUCCEEDED)
            return textEnvelope(
                parameters,
                displayId(),
                VirtualScreenTextPolicy.TextMethod.KEY_EVENTS,
                chars,
                submitted,
                submit,
                steps,
            )
        }
        audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.FAILED)
        val reason = shell?.optString("reason").orEmpty().ifEmpty {
            if (shell == null) {
                val detail = raw.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
                "设备 Shell 按键通道不可用（Shizuku 或副屏会话未就绪$detail）"
            } else {
                "设备 Shell 按键通道没能写入"
            }
        }
        throw RuntimeFailure(
            VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
            VirtualScreenTextPolicy.failureMessage(VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED) +
                "（$reason；${if (chars <= 0) "一个字符也没写进去" else "只写进了 $chars/$total 个字符"}）；$steps",
        )
    }

    /** 按键兜底成功后的补记账：把「等待调用方的按键通道」翻成成功；没有这一条时补一条。 */
    private fun keyEventAttempts(
        attempts: List<VirtualScreenTextPolicy.TextAttempt>,
    ): List<VirtualScreenTextPolicy.TextAttempt> {
        val marked = VirtualScreenTree.markKeyEvents(attempts)
        if (marked.any { it.method == VirtualScreenTextPolicy.TextMethod.KEY_EVENTS && it.succeeded }) return marked
        return marked + VirtualScreenTextPolicy.TextAttempt(
            VirtualScreenTextPolicy.TextMethod.KEY_EVENTS,
            true,
            null,
            "已通过设备 Shell 的 input 通道输入",
        )
    }

    /** 文字注入的结构化回执；`method`/`chars`/`submit` 都取真实读数，`submitRequested` 记录调用方的请求。 */
    private fun textEnvelope(
        parameters: JSONObject,
        displayId: Int,
        method: VirtualScreenTextPolicy.TextMethod?,
        chars: Int,
        submitted: Boolean,
        submitRequested: Boolean,
        steps: String,
    ): JSONObject = JSONObject()
        .put("sessionId", parameters.getString("sessionId"))
        .put("displayId", displayId)
        .put("method", method?.name)
        .put("label", method?.label)
        .put("chars", chars)
        .put("submit", submitted)
        .put("submitRequested", submitRequested)
        .put("steps", steps)

    fun showOverlay() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(Settings.canDrawOverlays(this)) { "请先授予悬浮窗权限" }
        check(session.isNotEmpty() && !ending.get()) { "副屏尚未就绪" }
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val metrics = resources.displayMetrics
        // 悬浮面板的颜色与控件默认样式都按**已保存的应用主题**解析。
        // Service 没有 Activity 那样的主题入口，直接 new Button(this) 会拿系统深浅去解析默认样式：
        // 应用选了深色、系统还是浅色时，一块深色面板上会浮出几个浅色按钮，看着就是「没跟主题走」。
        // 这里沿用悬浮球那套 ContextThemeWrapper(this, R.style.AppTheme)（见 OverlayBallService），
        // 再叠一层 AppThemePreference.palette() 把 uiMode 换成应用主题。
        val ui: Context = ContextThemeWrapper(AppThemePreference.palette(this), R.style.AppTheme)
        // 底色与文字色一律取主题令牌（res/values{,-night}/colors.xml），不再写死 0xf0222630 这种值：
        // 那个深色底在浅色主题下就是一块突兀的深色砖。
        val panel = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppThemePreference.color(this@VirtualScreenService, R.color.overlay_panel_background))
        }
        val toolbar = LinearLayout(ui)
        val drag = TextView(ui).apply {
            text = "目标应用 · 拖动"
            setTextColor(AppThemePreference.color(this@VirtualScreenService, R.color.overlay_panel_foreground))
            setPadding(12, 12, 12, 12)
        }
        toolbar.addView(drag, LinearLayout.LayoutParams(0, -2, 1f))
        val page = Button(ui).apply {
            text = "展开"
            setOnClickListener {
                runCatching { startActivity(Intent(this@VirtualScreenService, VirtualScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onSuccess { hideOverlay() }
                    .onFailure { Toast.makeText(this@VirtualScreenService, "无法展开，请从通知打开副屏页面", Toast.LENGTH_LONG).show() }
            }
        }
        val close = Button(ui).apply { text = "收起"; setOnClickListener { hideOverlay() } }
        // 状态行：档位按钮下面一行，如实显示当前档位、实测帧率与画面状态（「画面暂无变化」「当前帧接近纯色」
        // 等措辞都来自 VirtualScreenPolicy）。没有它时，预览暂停或黑屏时用户在悬浮窗里看不到任何原因——
        // 0.2.9 真机上就是一块黑面板。声明放在档位按钮之前，按钮的点击回调要在切档成功后立刻改这一行。
        val status = TextView(ui).apply {
            setTextColor(AppThemePreference.color(this@VirtualScreenService, R.color.overlay_panel_foreground))
            setPadding(12, 4, 12, 4)
            setTextSize(12f)
            text = getString(R.string.virtual_screen_overlay_status_waiting)
        }
        toolbar.addView(page); toolbar.addView(close); panel.addView(toolbar)
        // 档位行：副屏页里能选的几档，在悬浮窗上也能选，不必「先展开、改完再收回来」。
        // 档位只改采集间隔；预览拉取另有下限（见 VirtualScreenPolicy.previewPullInterval）。
        val modeRow = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }
        val modeButtons = mutableListOf<Button>()
        val currentMode = {
            VirtualScreenPolicy.frameModeOf(state().optString(VirtualScreenPolicy.PREVIEW_FIELD))
        }
        val paintModes = {
            val active = currentMode()
            modeButtons.forEach { button -> button.alpha = if (button.tag == active) 1f else 0.5f }
        }
        VirtualScreenPolicy.FRAME_MODES.keys.forEach { mode ->
            val button = Button(ui).apply {
                text = getString(modeTitleRes(mode))
                tag = mode
                setPadding(6, 0, 6, 0)
                setOnClickListener {
                    runCatching {
                        action(
                            JSONObject()
                                .put("sessionId", session)
                                .put("action", "config")
                                .put(VirtualScreenPolicy.PREVIEW_FIELD, mode),
                        )
                    }.onSuccess {
                        // 如实说明「已经切了、读数还没到」；下一次取帧会用真实读数覆盖这一行。
                        status.text = getString(R.string.virtual_screen_overlay_mode_switched, getString(modeTitleRes(mode)))
                    }.onFailure {
                        Toast.makeText(this@VirtualScreenService, "切换档位失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                    paintModes()
                }
            }
            modeButtons += button
            modeRow.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(modeRow)
        paintModes()
        panel.addView(status)
        // 小窗尺寸由虚拟屏规格换算（VirtualScreenWindow.overlaySize）。面板宽度取**画面宽度**而不是
        // 标称的 320dp：面板固定高度里已经含了工具栏与状态行的预算，若宽度还按 320dp 满宽，
        // 画面区比例就会比副屏更宽，`FIT_CENTER` 照样会在左右/上下留黑边。
        // 面板宽度 == 画面宽度，画面区就是「面板减去上面几行的预算」这块矩形，比例等于副屏比例。
        val liveSpec = VirtualScreenSpec.decodeState(snapshot)
        val size = VirtualScreenWindow.overlaySize(liveSpec, metrics.widthPixels, metrics.heightPixels, metrics.density)
        val image = VirtualScreenPreview(this).also { preview = it }
        // 预览每一拍把状态行文案回报到这里（档位、实测帧率、画面状态都在里面）。
        image.report = { line -> if (overlay === panel) status.text = line }
        // 画面区不参与 LinearLayout 的均分（weight = 0，高度 = 换算出来的像素值），
        // 多出来/不够的高度只会落在这一块之外，不会把画面的宽高比拉变形。
        panel.addView(image, LinearLayout.LayoutParams(-1, size.contentHeightPx))
        // 画面区是第一个加进面板的子控件，画在最底层；档位行与状态行必须压在它上面，
        // 否则预览会把「省电/15fps/…」这一排按钮吃掉（规则见 VirtualScreenPolicy.foregroundOnTop）。
        modeRow.bringToFront()
        status.bringToFront()
        val w = size.widthPx
        val h = size.heightPx
        // 悬浮小窗**不加 FLAG_SECURE**：加了以后用户自己截图和 adb screencap 都会失败
        // （0.2.9 真机实测报 "Failed to take take screenshot. Capturing failed."），而用户明确要能看到小窗内容。
        // 全屏查看器 VirtualScreenActivity 的 FLAG_SECURE 保持不动，隐私提醒见 docs/目标应用副屏.md。
        val params = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 0; y = (60 * metrics.density).toInt()
        }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        drag.setOnTouchListener { view, e ->
            // 面板尺寸按会话建立时算出的 w/h 固定，拖动过程中不变，因此这里的夹取基准始终有效。
            val current = panel.layoutParams as? WindowManager.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    originX = current?.x ?: params.x; originY = current?.y ?: params.y
                }
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
    /** 上一张已显示画面的亮度采样，用来判断「画面暂无变化」；null 表示还没显示过画面。 */
    private var previousSamples: IntArray? = null
    /** 逐采样行读像素时复用的整行缓冲，避免每一拍都新建数组。 */
    private var rowPixels = IntArray(0)
    private var displayedHardware: android.hardware.HardwareBuffer? = null
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
        previousSamples = null
        displayedHardware?.close(); displayedHardware = null
    }

    /**
     * 把一帧采成亮度样本（[VirtualScreenPolicy.previewSampleRows] × [VirtualScreenPolicy.previewSampleColumns]）：
     * 每个采样行只调一次 `getPixels` 取整行，再按采样列挑点，既避开上万次逐像素 JNI 调用，
     * 也不会像 0.2.9 之前那样只取固定几行、正好从文字上下穿过去。
     */
    private fun sampleFrame(bitmap: android.graphics.Bitmap): IntArray {
        val rows = VirtualScreenPolicy.previewSampleRows(bitmap.height)
        val columns = VirtualScreenPolicy.previewSampleColumns(bitmap.width)
        if (rows.isEmpty() || columns.isEmpty()) return IntArray(0)
        val line = rowPixels.takeIf { it.size >= bitmap.width } ?: IntArray(bitmap.width).also { rowPixels = it }
        val samples = IntArray(rows.size * columns.size)
        var index = 0
        for (row in rows) {
            bitmap.getPixels(line, 0, bitmap.width, 0, row, bitmap.width, 1)
            for (column in columns) samples[index++] = VirtualScreenPolicy.luminance(line[column])
        }
        return samples
    }

    private val tick = object : Runnable {
        override fun run() {
            val worker = executor ?: return
            val version = generation
            worker.execute {
                var bitmap: android.graphics.Bitmap? = null
                var id = ""
                var channel = ""
                var mode = "limited"
                var fps = 0.0
                var hardwareFrame = false
                var fetchedHardware: android.hardware.HardwareBuffer? = null
                // 这一拍的亮度采样；null 表示这一拍没拿到可解码的画面（不等于「画面是空的」）。
                var samples: IntArray? = null
                try {
                    val service = checkNotNull(VirtualScreenService.current)
                    // 帧率必须来自与这一帧同一拍的状态：health 的快照最多旧 1500 毫秒，拿它贴帧率会慢半拍。
                    val state = service.freshState()
                    id = state.getString("sessionId")
                    channel = state.optString("touchChannel", "")
                    mode = VirtualScreenPolicy.frameModeOf(state.optString(VirtualScreenPolicy.PREVIEW_FIELD))
                    fps = state.optDouble("frameFps", 0.0)
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val hardware = service.hardwareFrame(id)
                        hardwareFrame = true
                        fetchedHardware = hardware
                        bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                            hardware,
                            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),
                        )
                    } else {
                        val bytes = service.screenshot(id)
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) samples = sampleFrame(bitmap)
                    }
                } catch (_: Exception) {
                    fetchedHardware?.close()
                    fetchedHardware = null
                    samples = null
                    bitmap = null
                }
                main.post {
                    if (version != generation || executor == null) {
                        fetchedHardware?.close()
                        return@post
                    }
                    val current = VirtualScreenService.current
                    // 会话不可观察（已切换、已释放）才是唯一允许清屏的情形。
                    val alive = current?.canObserve() == true && current.state().optString("sessionId") == id
                    // 判定权威在客户端：宿主那个只看固定采样线的 frameBlank 只作提示，不再决定清不清屏
                    // （0.2.9 的真机黑屏就是把它当成了「不能显示」）。接近纯色的帧一律不显示，并如实写进状态行。
                    // HardwareBuffer 已由 Bitmap.wrapHardwareBuffer 成功解码，但不会生成 CPU samples；
                    // 也不能拿尚未做 CPU 采样的 frameBlank=true 把有效硬件帧误判成纯色。
                    // 硬件路径的内容判定交给 Bitmap 解码结果，PNG 路径继续使用采样启发式。
                    val decoded = bitmap != null
                    val hasContent = if (hardwareFrame) decoded
                        else samples != null && VirtualScreenPolicy.frameHasContent(samples)
                    val changed = if (hardwareFrame) decoded
                        else samples == null || VirtualScreenPolicy.frameChanged(previousSamples, samples)
                    val outcome = VirtualScreenPolicy.previewOutcome(alive, decoded, !hasContent, changed)
                    // 还没有任何可显示画面时必须说「等待……」，不能假称「画面暂无变化」；
                    // 这里用 lambda 在读数时求值：SHOW 已经把画面画上去之后，状态行就不该再说「等待」。
                    // 文案由 VirtualScreenPolicy.previewLine 统一生成：页面大预览与悬浮小窗共用同一份规则。
                    val line = {
                        VirtualScreenPolicy.previewLine(
                            channel, outcome.pause, fps, context.getString(modeTitleRes(mode)), displayed == null,
                        )
                    }
                    when (outcome.action) {
                        VirtualScreenPolicy.PreviewFrameAction.SHOW -> {
                            bitmap?.let {
                                val oldHardware = displayedHardware
                                displayedHardware = fetchedHardware
                                fetchedHardware = null
                                setImageBitmap(it); displayed = it
                                oldHardware?.close()
                                // 只有真帧才刷新画面时刻：不能让触摸与「画面新鲜」判定误以为刚更新过。
                                observedSession = id; observedAt = SystemClock.elapsedRealtime()
                                // 只有真正显示过的画面才算「上一帧」，状态行的「画面暂无变化」才有所指。
                                previousSamples = samples
                            }
                            if (channel.isNotEmpty()) observedChannel = channel
                            report(line())
                        }
                        VirtualScreenPolicy.PreviewFrameAction.KEEP -> {
                            // 接近纯色的新帧或这一拍读不到帧：保留最近一张非空白画面（绝不清屏），也不动画面时刻。
                            fetchedHardware?.close()
                            fetchedHardware = null
                            if (channel.isNotEmpty()) observedChannel = channel
                            report(line())
                        }
                        VirtualScreenPolicy.PreviewFrameAction.CLEAR -> {
                            fetchedHardware?.close()
                            fetchedHardware = null
                            clearFrame()
                            report(VirtualScreenPolicy.PREVIEW_GONE_MESSAGE)
                        }
                    }
                    // 拉取间隔跟随档位（下限见 VirtualScreenPolicy.previewPullInterval）：
                    // 档位在这里改、运行中立刻生效，不必重开副屏。
                    main.postDelayed(this, VirtualScreenPolicy.previewPullInterval(mode).toLong())
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
