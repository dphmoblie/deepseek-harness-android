package io.deepseekharness.mobile.virtualscreen

import android.app.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
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
    "90fps" -> R.string.virtual_screen_mode_90fps
    "144fps" -> R.string.virtual_screen_mode_144fps
    "165fps" -> R.string.virtual_screen_mode_165fps
    "240fps" -> R.string.virtual_screen_mode_240fps
    "185fps" -> R.string.virtual_screen_mode_185fps
    else -> R.string.virtual_screen_mode_limited
}

class VirtualScreenService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val owner = Binder()
    private val ending = AtomicBoolean(false)
    private val stopped = java.util.concurrent.CompletableFuture<Boolean>()
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
    /**
     * 本次会话生效的方向与自适应开关（请求里的值优先，否则取偏好）。
     * 状态合并要报告真正生效的那一份，而不是用户刚改过、还没重启副屏的那一份。
     */
    @Volatile private var orientation = "auto"
    @Volatile private var adaptive = false
    private var lastAiFrameAt = 0L
    @Volatile private var snapshot = JSONObject().put("active", false)
    private var overlay: View? = null
    private var preview: VirtualScreenPreview? = null
    /** 悬浮小窗的窗口参数与可用区域：拖动改的是这两个值，hideOverlay 时一并清掉。 */
    @Volatile private var overlayParams: WindowManager.LayoutParams? = null
    @Volatile private var overlayArea: OverlayArea? = null
    /** 拖动起点与最近一次生效的位置：位移换算成绝对坐标时用，避免把节流丢掉的中间位移累计错。 */
    private var dragOriginX = 0
    private var dragOriginY = 0
    private var dragLastX = 0
    private var dragLastY = 0
    private var dragging = false
    private val sink = object : RuntimeEventSink {
        override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
        override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
        override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "未授予通知权限，副屏已安全停止；请在设置中允许通知后重试"
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.createNotificationChannel(NotificationChannel(CHANNEL, "目标应用副屏", NotificationManager.IMPORTANCE_LOW)) }
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
        // 尺寸来源收敛到规格层，且**只在这里换算一次**：页面与桥只投递「意图」（三项尺寸 / 自适应开关 /
        // 方向），真正的宽高与 dpi 由这里结合真实屏幕和偏好算出来。
        // 历史 Intent（只有旧 `landscape` 布尔、没有新 extra）走同一套规则：三项尺寸读不到 = 没给自定义尺寸，
        // 偏好里也没有尺寸时就回退方向预设，与改造前行为一致；自适应开关的默认值也是「关」。
        val requested = virtualScreenStartRequest(intent)
        val settings = VirtualScreenPreferences.read(this)
        val screen = virtualScreenScreenSize()
        spec = VirtualScreenStart.resolve(requested, settings, screen.first, screen.second, resources.displayMetrics.density)
        orientation = requested.orientation ?: settings.orientation
        adaptive = requested.adaptive ?: settings.adaptive
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
                action(VirtualScreenPreferences.configuration(this).put("sessionId", session).put("action", "config"))
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
                        if (canObserve() && actionSlot.tryAcquire()) {
                            try { shizuku?.virtualScreenAction(JSONObject().put("sessionId", session).put("action", "autoFollowTick").put("selfPackage", packageName).toString()) }
                            finally { actionSlot.release() }
                        }
                        val live = checkNotNull(shizuku).virtualScreenState()
                        check(live.optString("sessionId") == session && live.optBoolean("active"))
                        snapshot = live
                    }
                } catch (_: Exception) { lastError = "副屏已停止：授权、Shizuku 连接或副屏会话失效"; main.post { stopSelf() } }
                if (!ending.get()) main.postDelayed(this, 800)
            }
        }
    }

    fun state(): JSONObject = JSONObject(snapshot.toString()).put("starting", session.isEmpty() && !ending.get())
        .put("active", snapshot.optBoolean("active") && !ending.get()).put("stopping", ending.get()).put("error", lastError)
        // 节点树能不能读只取决于无障碍服务是否已连接：把它放进状态里，
        // 让调用方一眼看出「读不到树」是无障碍没开还是副屏没起来。
        .put("treeSupported", VirtualScreenTree.available())

    /**
     * 副屏的**真实生效**参数（宽 / 高 / dpi / 刷新率）：直接问系统那块副屏 display，不回显请求值。
     *
     * 为什么必须读回：自适应规格是按屏幕预算算出来的，设备端建显示时还可能被系统缩放或裁掉一部分；
     * 桥里若回显请求值，设置页显示的就会是一个用户其实看不到的尺寸。
     *
     * 返回 null 表示「此刻拿不到读数」（会话刚建立、display 尚未就绪、系统不允许查询该显示）：
     * 调用方回退到会话快照与本次会话规格；刷新率读不到时最终回 0（未知），不去猜屏幕标称值。
     */
    fun displayReadback(): JSONObject? {
        val id = snapshot.optInt("displayId", 0)
        // 没有会话时绝不读默认显示：那会把手机自己的分辨率当副屏尺寸报给设置页。
        if (id <= 0) return null
        val manager = getSystemService(android.hardware.display.DisplayManager::class.java) ?: return null
        val display = runCatching { manager.getDisplay(id) }.getOrNull() ?: return null
        val metrics = android.util.DisplayMetrics()
        if (runCatching { display.getRealMetrics(metrics) }.isFailure) return null
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
        return JSONObject()
            .put("widthPx", metrics.widthPixels)
            .put("heightPx", metrics.heightPixels)
            .put("densityDpi", metrics.densityDpi)
            .put("refreshRate", runCatching { display.refreshRate.toDouble() }.getOrDefault(0.0))
    }

    /** 本次会话的启动参数（方向、自适应）与规格，供桥在合并状态时优先于偏好使用。 */
    fun sessionSummary(): JSONObject = JSONObject()
        .put("orientation", orientation)
        .put("adaptive", adaptive)
        .put("widthPx", spec.widthPx)
        .put("heightPx", spec.heightPx)
        .put("densityDpi", spec.densityDpi)

    /**
     * 从启动 Intent 还原「本次请求」。三项尺寸读不到时保持 0 语义（= 没给自定义尺寸），
     * 自适应开关只有显式写过才非 null —— 旧页面没有这个 extra，于是继续沿用偏好里的开关而不是被强制关掉。
     */
    private fun virtualScreenStartRequest(intent: Intent) = VirtualScreenStartRequest(
        target = intent.getStringExtra("component") ?: "",
        adaptive = if (intent.hasExtra(VirtualScreenStart.EXTRA_ADAPTIVE)) intent.getBooleanExtra(VirtualScreenStart.EXTRA_ADAPTIVE, false) else null,
        orientation = intent.getStringExtra(VirtualScreenStart.EXTRA_ORIENTATION)?.takeIf { it in VirtualScreenPreferences.ORIENTATION_VALUES },
        widthPx = intent.getIntExtra(VirtualScreenSpec.EXTRA_WIDTH, 0).takeIf { it > 0 },
        heightPx = intent.getIntExtra(VirtualScreenSpec.EXTRA_HEIGHT, 0).takeIf { it > 0 },
        densityDpi = intent.getIntExtra(VirtualScreenSpec.EXTRA_DPI, 0).takeIf { it > 0 },
    )

    /**
     * 自适应用的**真实屏幕**尺寸：`DisplayManager` 的默认显示优先，取不到再退回资源里的当前显示尺寸。
     *
     * 不用本服务的窗口尺寸：应用可能被分屏或自由窗口缩小，拿窗口尺寸会算出偏小的副屏；
     * 也不用 `resources.displayMetrics` 作为首选，它可能已经按窗口/系统裁剪过。
     */
    private fun virtualScreenScreenSize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        val display = runCatching {
            getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        }.getOrNull()
        if (display != null && runCatching { display.getRealMetrics(metrics) }.isSuccess &&
            metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return metrics.widthPixels to metrics.heightPixels
        }
        val fallback = resources.displayMetrics
        return fallback.widthPixels.coerceAtLeast(1) to fallback.heightPixels.coerceAtLeast(1)
    }

    fun canObserve(): Boolean = !ending.get() && DeviceShellAccess.enabled(this) &&
        !getSystemService(KeyguardManager::class.java).isDeviceLocked && getSystemService(PowerManager::class.java).isInteractive

    /** 停止请求立即封锁输入；完成信号只在旧显示真正释放后发出。 */
    fun requestStop(): java.util.concurrent.CompletableFuture<Boolean> {
        ending.set(true)
        main.post { stopSelf() }
        return stopped
    }

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
            checkNotNull(shizuku).virtualScreenAction(parameters.toString()).also {
                if (verb == "config") VirtualScreenPreferences.save(this, parameters)
                if (it.has("active")) snapshot = it
                audit.record(AuditEvent.VIRTUAL_SCREEN_ACTION, AuditResult.SUCCEEDED)
            }
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
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        // 系统栏与刘海的边距只在 API 30 以上取：`android.graphics.Insets` 是 API 29 才有的类型，
        // 把它放到三元表达式的 else 分支会让低版本机型加载到这个类，lint 也会按 NewApi 报错。
        // 低版本一律按 0 处理（与改动前 `Insets.NONE` 的行为一致）：只影响居中与夹取，不会越界。
        var insetLeft = 0
        var insetTop = 0
        var insetRight = 0
        var insetBottom = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetLeft = insets.left
            insetTop = insets.top
            insetRight = insets.right
            insetBottom = insets.bottom
        }
        val size = VirtualScreenWindow.overlaySize(VirtualScreenSpec.decodeState(snapshot),
            bounds.width() - insetLeft - insetRight, bounds.height() - insetTop - insetBottom, metrics.density)
        // 小窗只有应用画面：位置靠直接拖小窗本身（拖动之外的手势照旧透传给副屏），
        // 收起与结束仍在独立设置页或通知里操作。
        val image = VirtualScreenPreview(this).also { preview = it }
        image.dragHost = overlayDragHost
        val params = WindowManager.LayoutParams(size.widthPx, size.heightPx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bounds.width() - size.widthPx) / 2
            y = (bounds.height() - insetTop - insetBottom - size.heightPx) / 2
        }
        try {
            wm.addView(image, params)
            overlay = image
            // 拖动需要的窗口参数与边界只在这里赋值：hideOverlay 时清掉，
            // 免得下一次拖动拿的是上一次会话、上一次旋转之前的边界去做夹取。
            overlayParams = params
            overlayArea = OverlayArea(bounds.width(), bounds.height(), insetTop, insetBottom)
        } catch (e: Exception) {
            preview = null
            throw e
        }
    }

    fun hideOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null; preview = null
        overlayParams = null; overlayArea = null; dragging = false
    }

    /**
     * 拖动小窗：只改窗口的 x/y，尺寸与内容都不动。
     *
     * 位移一律相对**按下那一刻的窗口原点**换算（而不是累加上一次的位置），
     * 这样被 `MOVE_INTERVAL_MILLIS` 节流丢掉的中间点不会让窗口越拖越偏。
     */
    private val overlayDragHost = object : VirtualScreenDragHost {
        override fun beginDrag(): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            val params = overlayParams ?: return false
            if (overlay == null) return false
            dragOriginX = params.x
            dragOriginY = params.y
            dragLastX = params.x
            dragLastY = params.y
            dragging = true
            return true
        }

        override fun drag(deltaX: Int, deltaY: Int) = move(deltaX, deltaY)

        override fun endDrag(deltaX: Int, deltaY: Int) {
            if (dragging) move(deltaX, deltaY)
            dragging = false
        }

        private fun move(deltaX: Int, deltaY: Int) {
            if (!dragging) return
            val view = overlay ?: return
            val params = overlayParams ?: return
            val area = overlayArea ?: return
            // 视图还没量出尺寸时退回窗口参数里的尺寸，避免用 0 去算夹取上界。
            val viewWidth = if (view.width > 0) view.width else params.width
            val viewHeight = if (view.height > 0) view.height else params.height
            val (x, y) = VirtualScreenWindow.dragPosition(dragOriginX, dragOriginY, deltaX, deltaY,
                area.widthPx, area.heightPx, area.topInsetPx, area.bottomInsetPx, viewWidth, viewHeight)
            if (x == dragLastX && y == dragLastY) return
            params.x = x
            params.y = y
            dragLastX = x
            dragLastY = y
            // 更新窗口位置可能因为窗口已被系统移除而抛异常（拖到一半按了「结束副屏」），
            // 这种收尾竞态不该把整次手势打崩。
            runCatching { getSystemService(WindowManager::class.java).updateViewLayout(view, params) }
        }
    }

    override fun onDestroy() {
        ending.set(true)
        main.removeCallbacks(health)
        hideOverlay()
        executor.execute {
            var released = true
            try {
                if (session.isNotEmpty()) shizuku?.closeVirtualScreen(session)
            } catch (_: Exception) { released = false }
            finally {
                try {
                    audit.record(AuditEvent.VIRTUAL_SCREEN_SESSION, if (released) AuditResult.SUCCEEDED else AuditResult.FAILED,
                        if (released) "CLOSED" else "CLOSE_FAILED")
                } finally {
                    if (acquiredRuntime) runCatching { RuntimeHost.detachPluginSink(sink) }
                    main.post {
                        if (current === this) current = null
                        stopped.complete(released)
                    }
                }
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
private const val MOVE_INTERVAL_MILLIS = 8L

/** 悬浮小窗当前可用的屏幕区域（像素）：宽高是整屏，上下留出状态栏与导航栏。 */
private data class OverlayArea(val widthPx: Int, val heightPx: Int, val topInsetPx: Int, val bottomInsetPx: Int)

/**
 * 悬浮小窗的拖动端口：预览控件只判定「这次手势是拖动」，位置换算与窗口更新都由服务负责。
 *
 * 这样切分的原因：拖动数学（[VirtualScreenWindow.dragPosition]）要能在 JVM 单测里跑，
 * 而窗口更新必须发生在主线程、且只有服务手里有 `WindowManager.LayoutParams`。
 */
interface VirtualScreenDragHost {
    /** 手势被判定为拖动；返回 false 表示当前没有可拖的窗口，这次手势继续按原语义透传。 */
    fun beginDrag(): Boolean

    /** 拖动过程（已按 [MOVE_INTERVAL_MILLIS] 节流）；参数是相对按下点的位移，单位像素。 */
    fun drag(deltaX: Int, deltaY: Int)

    /** 拖动结束；参数是最后一次位移，用于补发被节流丢掉的末位置。 */
    fun endDrag(deltaX: Int, deltaY: Int)
}

/** 限帧预览只在可见时拉取 PNG；副屏采集在 Shizuku 进程持续运行。 */
class VirtualScreenPreview(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    private var executor: java.util.concurrent.ExecutorService? = null
    private var touchExecutor: java.util.concurrent.ExecutorService? = null
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
    /**
     * 拖动端口：悬浮小窗会装上它，原生预览页不装 —— 于是预览页里的手势语义与改造前完全一致。
     */
    var dragHost: VirtualScreenDragHost? = null
    /** 按下点的屏幕坐标：拖动改的是窗口位置，用屏幕位移而不是视图内坐标，不受缩放矩阵与视图边界影响。 */
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragActive = false
    private var dragLastAt = 0L
    private var queuedDrag: FloatArray? = null
    /**
     * 取「现在」的单调时钟（毫秒），长按判定用。默认是 `SystemClock.uptimeMillis()`；
     * 留成可注入的字段是为了让计时判定不必绑死 Android 时钟（判定本身抽在纯函数
     * `VirtualScreenWindow.dragArmed`，单测直接注入毫秒数）。
     */
    var clock: () -> Long = { SystemClock.uptimeMillis() }
    /** 按下时刻（`clock()` 的读数）：拖动改成长按触发后，这里决定「已经按了多久」。 */
    private var downHeldAt = 0L
    /** 静置容差（像素）：按下后累计位移不超过它才算「按住不动」，按下时按当前显示密度换算一次。 */
    private var dragSlopPx = 0f
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
            val worker = touchExecutor
            val at = point(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = at; downSession = observedSession; downAt = e.eventTime
                    streaming = false; queuedMove = null; lastMoveAt = 0L
                    downRawX = e.rawX; downRawY = e.rawY
                    dragActive = false; queuedDrag = null; dragLastAt = 0L
                    dragSlopPx = VirtualScreenWindow.DRAG_SLOP_DP * resources.displayMetrics.density
                    // 长按计时的起点：只有「按住不动够久」才允许改判成拖窗。
                    downHeldAt = clock()
                    // 只有设备端报告 stream 通道、且会话与画面都新鲜时才逐事件直传；
                    // 否则保持原先的「抬起时发一个 tap/swipe」行为。
                    if (at != null && service != null && worker != null && observedChannel == "stream" &&
                        service.canObserve() && SystemClock.elapsedRealtime() - observedAt < 3000) {
                        streaming = true
                        sendTouch(worker, service, "down", at)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val drag = dragHost
                    // 先长按再拖：按住不动 LONG_PRESS_DRAG_MILLIS 之后才允许改判成拖窗，
                    // 这之前的移动一律照常透传给副屏（所以滑动、翻页不会被吞掉）。
                    if (!dragActive && drag != null && VirtualScreenWindow.dragArmed(
                            clock() - downHeldAt,
                            e.rawX - downRawX,
                            e.rawY - downRawY,
                            dragSlopPx,
                        )) {
                        dragActive = drag.beginDrag()
                        if (dragActive) {
                            // 手势在这一刻改判为拖动：副屏那边已经收到 down，必须补一个 cancel，
                            // 否则目标应用会一直停在「按下」状态；之后的 MOVE 都归拖动，不再透传。
                            if (streaming) { streaming = false; (queuedMove ?: down ?: at)?.let { sendTouch(worker, service, "cancel", it) } }
                            queuedMove = null
                        }
                    }
                    if (dragActive && drag != null) {
                        // 位置更新按与触摸直传相同的最短间隔节流：屏幕采样可到 120 Hz，
                        // 每个采样都调一次 updateViewLayout 会白白占用合成器。
                        val now = SystemClock.elapsedRealtime()
                        val dx = (e.rawX - downRawX).toInt()
                        val dy = (e.rawY - downRawY).toInt()
                        if (now - dragLastAt < MOVE_INTERVAL_MILLIS) queuedDrag = floatArrayOf(e.rawX - downRawX, e.rawY - downRawY)
                        else { dragLastAt = now; queuedDrag = null; drag.drag(dx, dy) }
                    } else if (streaming && at != null) {
                        // 直传的 MOVE 按最短间隔合并：屏幕采样可能到 120 Hz，逐个转发会压满 Binder，
                        // 合并后只丢中间采样点，抬起前那一个点仍会补发。
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastMoveAt < MOVE_INTERVAL_MILLIS) queuedMove = at
                        else { lastMoveAt = now; queuedMove = null; sendTouch(worker, service, "move", at) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // 这里不做「把小窗提到最上层」：TYPE_APPLICATION_OVERLAY 的层序由系统按
                    // 窗口添加顺序决定，`updateViewLayout` 改不了 z-order，`View.bringToFront()`
                    // 只对同一 ViewGroup 内的兄弟视图有效（而这是窗口根视图）；唯一真正能提到顶的
                    // removeView+addView 会触发预览的 onDetachedFromWindow，关掉取帧线程，重新挂回后
                    // 反而永远停在最后一帧。用户刚刚点到的那块小窗本来就在他手指下面，不需要置顶。
                    // 拖动结束时不再补发一次「点击」：这次手势的归属是窗口位置，不是副屏内容。
                    if (!dragActive) view.performClick()
                    val start = down; down = null
                    if (dragActive) {
                        // 补发被节流丢掉的末位置（手指停下的地方才是用户要的位置），
                        // 不补发 up/tap/swipe —— 副屏那侧在 beginDrag 时已经收到 cancel。
                        val lastX = queuedDrag?.get(0) ?: (e.rawX - downRawX)
                        val lastY = queuedDrag?.get(1) ?: (e.rawY - downRawY)
                        dragActive = false; queuedDrag = null
                        dragHost?.endDrag(lastX.toInt(), lastY.toInt())
                    } else if (streaming) {
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
                    // 手势被取消（第二根手指按下、系统抢走手势）：窗口停在当前位置，不补发末位置。
                    dragActive = false; queuedDrag = null
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
                if (version == generation || phase == "cancel") service.action(request)
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
        executor = Executors.newSingleThreadExecutor()
        touchExecutor = Executors.newSingleThreadExecutor()
        generation++; main.post(tick)
    }
    override fun onDetachedFromWindow() {
        // 先把未完成的触摸取消排到输入队列尾部，避免离开页面后目标仍处于按下状态。
        if (streaming) sendTouch(touchExecutor, VirtualScreenService.current, "cancel", queuedMove ?: down ?: floatArrayOf(0f, 0f))
        generation++; main.removeCallbacks(tick); executor?.shutdown(); executor = null
        touchExecutor?.shutdown(); touchExecutor = null
        runCatching { context.unregisterReceiver(screenOff) }
        clearFrame()
        super.onDetachedFromWindow()
    }
}
