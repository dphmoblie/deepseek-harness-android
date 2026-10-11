package io.deepseekharness.mobile.virtualscreen

import android.app.*
import android.Manifest
import android.annotation.SuppressLint
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
    /**
     * 悬浮小窗的窗口参数：几何（位置 / 尺寸 / 可用区域）的权威在 [overlayMachine] 里，
     * 这里保留的只是交给 `WindowManager` 的那一个实例，按 effect 把值写回去。
     */
    @Volatile private var overlayParams: WindowManager.LayoutParams? = null

    /**
     * 悬浮小窗的生命周期状态机：幂等、置前节流、拖动记账、缩放夹取与失败回滚都在它里面，
     * 这一段只负责把它发出的 effect 落到真窗口上（见 [applyOverlayEffects]）并维护窗口句柄。
     */
    private val overlayMachine = VirtualScreenOverlayMachine(
        uptimeMillis = { SystemClock.uptimeMillis() },
        canDrawOverlays = { Settings.canDrawOverlays(this) },
        sessionReady = { session.isNotEmpty() && !ending.get() },
        density = { resources.displayMetrics.density },
        spec = { VirtualScreenSpec.decodeState(snapshot) },
        viewWidthPx = { overlay?.width ?: 0 },
        viewHeightPx = { overlay?.height ?: 0 },
        frontThrottleMillis = FRONT_THROTTLE_MILLIS,
    )
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
        overlayMachine.stop()
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
        requireAccess()
        requireSession(id)
        if (!actionSlot.tryAcquire()) throw RuntimeFailure("VIRTUAL_SCREEN_BUSY", "上一步副屏操作尚未完成")
        return try {
            val meta = checkNotNull(shizuku).virtualScreenScreenshot(id)
            requireAccess()
            audit.record(AuditEvent.VIRTUAL_SCREEN_READ, AuditResult.SUCCEEDED)
            val frameAt = meta.optLong("frameAtElapsedMs", 0L)
            val reused = frameAt > 0L && frameAt == lastAiFrameAt
            lastAiFrameAt = frameAt
            val envelope = JSONObject().put("imageBase64", meta.getString("imageBase64"))
                .put("packageName", meta.optString("packageName"))
                .put("frameAtElapsedMs", frameAt)
                .put("frameReused", reused)
            if (meta.has("frameBlank")) envelope.put("frameBlank", meta.optBoolean("frameBlank"))
            envelope
        } catch (error: Exception) {
            audit.record(AuditEvent.VIRTUAL_SCREEN_READ, AuditResult.FAILED)
            throw error
        } finally { actionSlot.release() }
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
        // 权限、会话与幂等三道闸门都在状态机里；布局推迟到闸门之后才算，
        // 所以「已经显示过」时连一次屏幕读数都不会发生（与改造前逐字同序）。
        val layout = overlayMachine.show { overlayWindowLayout() }
            .filterIsInstance<OverlayEffect.AddView>().firstOrNull()?.layout ?: return
        // 小窗只有应用画面：位置靠直接拖小窗本身（拖动之外的手势照旧透传给副屏），
        // 收起与结束仍在独立设置页或通知里操作。
        // 画面与缩放手柄收在同一个 VirtualScreenFrame 里：手柄是它的子视图而不是另一个窗口，
        // 于是预览页与小窗能共用同一份「多大、怎么调」的行为。
        val frame = VirtualScreenFrame(this).also { preview = it.preview }
        frame.preview.dragHost = overlayDragHost
        frame.installResize(overlayResizeGesture)
        val params = WindowManager.LayoutParams(layout.widthPx, layout.heightPx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = layout.x
            y = layout.y
        }
        try {
            getSystemService(WindowManager::class.java).addView(frame, params)
            overlay = frame
            // 拖动需要的窗口参数与边界只在这里赋值：收起时清掉，
            // 免得下一次拖动拿的是上一次会话、上一次旋转之前的边界去做夹取。
            overlayParams = params
            overlayMachine.viewAdded()
        } catch (e: Exception) {
            preview = null
            overlayMachine.viewAddFailed()
            throw e
        }
    }

    /**
     * 这一次要显示的小窗几何（像素）：整屏边界、系统栏与刘海边距、按画面比例算出的尺寸，
     * 以及之后缩放与拖动共用的可用区域。
     *
     * 几何的权威在 [overlayMachine]：这里只把屏幕读数变成一份布局，不自己做任何夹取判断。
     */
    private fun overlayWindowLayout(): OverlayLayout {
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
        // 可用来放小窗的区域（扣掉系统栏与刘海）：既是这次算尺寸的输入，也是之后缩放与拖动的夹取边界，
        // 因此要随窗口一起存下来 —— 缩放时屏幕没变，但若重新用 displayMetrics 算，就可能与当初不一致。
        val sizingWidth = bounds.width() - insetLeft - insetRight
        val sizingHeight = bounds.height() - insetTop - insetBottom
        val size = VirtualScreenWindow.overlaySize(VirtualScreenSpec.decodeState(snapshot), sizingWidth, sizingHeight, metrics.density,
            // 用户上次拖出来的宽度（0 = 跟随屏幕最大）。横竖屏切换后可能已经超出新屏幕，
            // 夹取在 overlaySize 内部完成，这里不再判断。
            VirtualScreenPreferences.read(this).overlayWidthPx)
        return OverlayLayout(
            x = (bounds.width() - size.widthPx) / 2,
            y = (bounds.height() - insetTop - insetBottom - size.heightPx) / 2,
            widthPx = size.widthPx,
            heightPx = size.heightPx,
            area = OverlayArea(bounds.width(), bounds.height(), insetTop, insetBottom, sizingWidth, sizingHeight),
        )
    }

    /**
     * 把副屏悬浮窗重新放到窗口队列末端，让刚点过的那个窗口成为同层里最后加入的那个。
     *
     * 为什么只能摘下来重挂：`TYPE_APPLICATION_OVERLAY` 的窗口之间，系统只按「谁后 addView」决定叠放顺序；
     * `View.bringToFront()` 改的是父容器里的子视图顺序（对窗口层无效），`updateViewLayout()` 也不重排队列。
     * 所以真正能改顺序的只有 `removeViewImmediate` + `addView`（两者必须在同一帧里完成，否则会看到一次空窗）。
     *
     * 摘下来会不会把画面弄死：不会 —— 重挂前把 [VirtualScreenPreview.retainFrameLoopOnDetach] 置位，
     * 取帧线程与调度因此活过这次 detach（否则 `onDetachedFromWindow` 会关线程、清帧，重挂后就永远停在最后一帧）。
     *
     * 代价与取舍：重挂毕竟是一次可见的重建，所以只允许「点一下小窗」触发，并在 [FRONT_THROTTLE_MILLIS] 内去重。
     * 副屏窗口永远压在状态栏、输入法与系统面板之下，这一点第三方应用改不了。
     */
    fun bringOverlayToFront() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { bringOverlayToFront() }
            return
        }
        val effects = overlayMachine.bringToFront()
        if (effects.isEmpty()) return
        // 拿的是**重挂开始时**那个预览控件：重挂失败那一支要靠它显式释放帧循环
        // （收起会把 preview 字段清空，抓晚了就释放不到真正需要释放的对象）。
        val image = preview
        if (image == null) {
            // 改造前这里是 `preview ?: return`：窗口与预览控件同时赋值、同时清空，这一支不可达；
            // 保留它只为与改造前逐字一致，同时把机器的中间态收回来。
            overlayMachine.viewReAdded()
            return
        }
        if (applyOverlayEffects(effects, image)) overlayMachine.viewReAdded() else forgetOverlayWindow()
    }

    /**
     * 执行窗口生命周期 effect；返回 false 表示「重新挂窗口失败，已经就地收尾」。
     *
     * 真机才有的对象（`View` / `WindowManager` / `LayoutParams`）都留在这里，机器只管状态与顺序。
     * [frameLoopTarget] 是本次调用要操作的预览控件：普通路径下就是当前那一个，
     * 「重挂失败」那一支必须传**重挂开始时**抓到的引用（收起会把 `preview` 字段清空）。
     */
    private fun applyOverlayEffects(effects: List<OverlayEffect>, frameLoopTarget: VirtualScreenPreview? = preview): Boolean {
        for (effect in effects) {
            when (effect) {
                OverlayEffect.KeepFrameLoop -> frameLoopTarget?.retainFrameLoopOnDetach = true
                OverlayEffect.ReleaseFrameLoop -> frameLoopTarget?.retainFrameLoopOnDetach = false
                OverlayEffect.ReleaseDetachedFrameLoop -> frameLoopTarget?.releaseOnDetach(retain = false)
                OverlayEffect.RemoveView ->
                    overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
                OverlayEffect.RemoveAndReAddView -> {
                    val view = overlay
                    val params = overlayParams
                    try {
                        if (view != null && params != null) {
                            val wm = getSystemService(WindowManager::class.java)
                            wm.removeViewImmediate(view)
                            wm.addView(view, params)
                        }
                    } catch (_: Exception) {
                        // addView 失败时视图可能已摘下，不会再收到 detach 回调，必须显式释放。
                        applyOverlayEffects(overlayMachine.viewReAddFailed(), frameLoopTarget)
                        return false
                    }
                }
                is OverlayEffect.UpdateLayout -> {
                    val view = overlay
                    val params = overlayParams
                    if (view != null && params != null) {
                        params.x = effect.x
                        params.y = effect.y
                        params.width = effect.widthPx
                        params.height = effect.heightPx
                        // 更新窗口位置可能因为窗口已被系统移除而抛异常（拖到一半按了「结束副屏」），
                        // 这种收尾竞态不该把整次手势打崩。
                        runCatching { getSystemService(WindowManager::class.java).updateViewLayout(view, params) }
                    }
                }
                // 建窗由 showOverlay 自己消费：它要建 View 与窗口参数，不是一句 effect 能落地的事。
                is OverlayEffect.AddView -> Unit
            }
        }
        return true
    }

    /** 窗口句柄与窗口参数只有这里会清：机器里已经没有窗口了，服务这边也不能再留着。 */
    private fun forgetOverlayWindow() {
        overlay = null
        preview = null
        overlayParams = null
    }

    /** 跑完 effect 再丢掉窗口句柄；清空时机见 [hideOverlay] 的注释。 */
    private fun releaseOverlayWindow(effects: List<OverlayEffect>) {
        applyOverlayEffects(effects)
        forgetOverlayWindow()
    }

    /**
     * 按新宽度等比缩放悬浮小窗（设置页改宽度、拖手柄都走这里）。
     *
     * 只认宽度，高度由 [VirtualScreenWindow.overlaySize] 按画面比例反算 —— 比例一变，
     * `FIT_CENTER` 就会在画面四周补黑边，触摸坐标也会跟着失真。
     * 位置保持不动，只做一次夹取：缩小后可能离边缘更远（无需处理），放大后才可能越界。
     */
    fun applyOverlayWidth(widthPx: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { applyOverlayWidth(widthPx) }
            return
        }
        applyOverlayEffects(overlayMachine.resize(widthPx))
    }

    /**
     * 手柄拖动：读数都取**当前**窗口与当前会话的值，因此旋转、换档、重启副屏之后不需要换一份手势对象。
     */
    private val overlayResizeGesture = VirtualScreenResizeGesture(
        displayWidthPx = { overlayMachine.layout?.area?.sizingWidthPx ?: resources.displayMetrics.widthPixels },
        displayHeightPx = { overlayMachine.layout?.area?.sizingHeightPx ?: resources.displayMetrics.heightPixels },
        minWidthPx = { maxOf((VirtualScreenWindow.MIN_OVERLAY_WIDTH_DP * resources.displayMetrics.density).toInt(), VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX) },
        aspectRatio = { VirtualScreenSpec.decodeState(snapshot).aspectRatio },
        currentWidthPx = { overlayMachine.layout?.widthPx ?: 0 },
        applyWidth = { applyOverlayWidth(it) },
        persistWidth = { VirtualScreenPreferences.saveOverlayWidth(this, it) },
    )

    /**
     * 收起小窗。顺序仍是「先撤掉保留取帧循环的开关，再摘窗口」（见 [OverlayEffect.ReleaseFrameLoop]）；
     * 窗口句柄与窗口参数在 effect 跑完之后才清 —— 置前重建失败那一支要靠它们把窗口再摘一次。
     */
    fun hideOverlay() {
        releaseOverlayWindow(overlayMachine.hide())
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
            return overlayMachine.beginDrag()
        }

        override fun drag(deltaX: Int, deltaY: Int) {
            applyOverlayEffects(overlayMachine.dragBy(deltaX, deltaY))
        }

        override fun endDrag(deltaX: Int, deltaY: Int) {
            applyOverlayEffects(overlayMachine.endDrag(deltaX, deltaY))
        }
    }

    override fun onDestroy() {
        ending.set(true)
        main.removeCallbacks(health)
        // 销毁与「收起」是同一套 effect，只是状态记到 DEAD：之后不再接受显示请求。
        releaseOverlayWindow(overlayMachine.destroy())
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

/**
 * 两次「重新挂到最前」之间的最小间隔（毫秒）。
 *
 * 摘下来重挂是一次可见的重建，连点小窗时不该每次都重建；1200 毫秒足够让用户感觉到「点一下就上来了」，
 * 又不至于在一次连续操作里反复闪。节流判定本身在 [VirtualScreenOverlayMachine] 里。
 */
private const val FRONT_THROTTLE_MILLIS = 1200L

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
    private val lifecycle = VirtualScreenPreviewLifecycle()
    private var displayed: android.graphics.Bitmap? = null
    /**
     * 上屏判定与观测状态（画面时刻 / 会话 / 触摸通道 / 上一次采样）都在这里：判定与它改动的状态
     * 必须是同一份，各存一份迟早会漂移。纯 Kotlin，可在 JVM 单测里逐拍覆盖。
     */
    private val core = VirtualScreenPreviewCore(
        sessionAlive = { id ->
            val current = VirtualScreenService.current
            // 会话不可观察（已切换、已释放）才是唯一允许清屏的情形。
            current?.canObserve() == true && current.state().optString("sessionId") == id
        },
        modeTitle = { mode -> context.getString(modeTitleRes(mode)) },
    )
    private val inputBusy = AtomicBoolean(false)
    /**
     * 副屏预览的性能预算记账（纯 Kotlin，见 [VirtualScreenPreviewBudget]）：每一拍记一次实际耗时与
     * 当刻档位允许的预算，偏慢时把说明并到画面状态行上——档位写着 60fps、实测只有一半帧率时，
     * 用户需要看到的是「跟不上档位」，而不是自己去把两个数字对一遍。
     */
    private val budget = VirtualScreenPreviewBudget()
    /**
     * 拖动端口：悬浮小窗会装上它，原生预览页不装 —— 于是预览页里的手势语义与改造前完全一致。
     */
    var dragHost: VirtualScreenDragHost? = null
    /**
     * 取「现在」的单调时钟（毫秒），触摸流用它算长按门槛。默认是 `SystemClock.uptimeMillis()`。
     */
    var clock: () -> Long = { SystemClock.uptimeMillis() }
    /**
     * 触摸手势流：按下/移动/抬起/取消的判定、直传合并、长按改判拖窗全在这里，纯 Kotlin 可逐事件喂。
     * 控件只提供现场事实（会话、通道、时钟、拖动端口），并负责 effect 的落地（发直传、补点击、post 置前）。
     */
    private val touchFlow = VirtualScreenPreviewTouchFlow(
        uptimeMillis = { clock() },
        realtimeMillis = { SystemClock.elapsedRealtime() },
        density = { resources.displayMetrics.density },
        observedSession = { core.observedSession },
        streamable = {
            val service = VirtualScreenService.current
            service != null && touchExecutor != null && core.observedChannel == "stream" &&
                service.canObserve() && SystemClock.elapsedRealtime() - core.observedAtMillis < 3000
        },
        discreteReady = { session ->
            // 会话与按下时一致、可观察、画面新鲜，并且已经占住 inputBusy（一次 CAS，投递结束后必须放开）。
            val service = VirtualScreenService.current
            session == core.observedSession && service != null && touchExecutor != null &&
                service.canObserve() && SystemClock.elapsedRealtime() - core.observedAtMillis < 3000 &&
                inputBusy.compareAndSet(false, true)
        },
        generation = { lifecycle.generation },
        dragHost = { dragHost },
        dispatch = { effect -> dispatchTouchEffect(effect) },
    )
    /** 逐采样行读像素时复用的整行缓冲，避免每一拍都新建数组。 */
    private var rowPixels = IntArray(0)
    private var displayedHardware: android.hardware.HardwareBuffer? = null
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) { clearFrame() }
    }
    /** 息屏接收器是否已注册：重挂时不能重复注册（否则息屏会收到两次）。 */
    private var screenOffRegistered = false
    var report: (String) -> Unit = {}
    /**
     * 触摸监听：判定与交付都在触摸流里，这里只把 MotionEvent 读成一笔纯数据的触摸；
     * 未列入的 action 与改造前一样什么都不做（只把事件吞掉）。
     *
     * `@SuppressLint("ClickableViewAccessibility")`：点击上报（`performClick()`）随 `Click` effect 在
     * [VirtualScreenService.dispatchTouchEffect] 里发出，lint 在这个 lambda 里看不到那次调用
     * —— 改造前它写在同一个 lambda 内，所以这是等价搬迁带来的阅读差异，行为没变。
     */
    @SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, e ->
        val at = point(e)
        val pointer = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> PreviewPointer.Down(at, e.rawX, e.rawY, e.eventTime)
            MotionEvent.ACTION_MOVE -> PreviewPointer.Move(at, e.rawX, e.rawY)
            MotionEvent.ACTION_UP -> PreviewPointer.Up(at, e.rawX, e.rawY, e.eventTime)
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> PreviewPointer.Cancel(at)
            else -> null
        }
        if (pointer != null) touchFlow.accept(pointer)
        true
    }
    init {
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(android.graphics.Color.BLACK)
        contentDescription = "目标应用副屏画面，支持点击和滑动"
        setOnTouchListener(touchListener)
    }

    /**
     * 画面状态行 + 预算说明（读得出来时才加）：偏慢这件事必须和画面读数同一行，
     * 否则用户得自己去把帧率与档位对一遍才知道跟不上。
     */
    private fun reportFrame(line: String, beat: PreviewBudgetSnapshot) {
        val note = previewBudgetNote(beat)
        report(if (note == null) line else line + note)
    }

    /**
     * 把触摸流的 effect 落到真机上：交付顺序就是流里判定的顺序，与改造前逐条调用同序
     * （`sendTouch(cancel)` 排在 `drag.drag(...)` 之前这件事由流内的交付顺序保证）。
     */
    private fun dispatchTouchEffect(effect: PreviewTouchEffect) {
        when (effect) {
            // 手势结束后再提升层级：不会取消本次触摸，也不会打断目标应用的输入。
            // 同层窗口只能靠 remove + addView 改叠放顺序（见 bringOverlayToFront），
            // 而取帧循环靠 VirtualScreenPreview.retainFrameLoopOnDetach 活过那次 detach；
            // 只在「点一下」之后触发（拖动结束不触发），再加 1200 毫秒去重，避免频繁重建窗口。
            PreviewTouchEffect.BringToFront -> main.post { VirtualScreenService.current?.bringOverlayToFront() }
            // 拖动结束时不再补发一次「点击」：这次手势的归属是窗口位置，不是副屏内容。
            PreviewTouchEffect.Click -> performClick()
            is PreviewTouchEffect.Stream -> sendTouch(touchExecutor, VirtualScreenService.current, effect)
            is PreviewTouchEffect.Discrete -> {
                // 这两个读数与离散闸门里同一处：闸门过了就一定非空，取不到时什么都不做。
                val worker = touchExecutor ?: return
                val service = VirtualScreenService.current ?: return
                sendDiscrete(worker, service, effect)
            }
        }
    }

    /** 逐事件触摸直传；设备端对 touch 不占用单次动作信号量，串行由宿主侧保证。 */
    private fun sendTouch(worker: java.util.concurrent.ExecutorService?, service: VirtualScreenService?, effect: PreviewTouchEffect.Stream) {
        if (worker == null || service == null) return
        val request = JSONObject().put("sessionId", effect.session).put("action", "touch").put("phase", effect.phase)
            .put("x", effect.x).put("y", effect.y)
        worker.execute {
            try {
                if (previewTouchDeliveryAllowed(effect.phase, effect.version, lifecycle.generation)) service.action(request)
            } catch (_: Exception) {
                if (effect.phase == "down") main.post { report("触摸直传未生效，可改用点击或滑动操作") }
            }
        }
    }

    /** 抬起时的离散点击/滑动：投递体里一定要放开 inputBusy，否则之后再也发不出第二次。 */
    private fun sendDiscrete(worker: java.util.concurrent.ExecutorService, service: VirtualScreenService, effect: PreviewTouchEffect.Discrete) {
        val request = JSONObject().put("sessionId", effect.session).put("action", if (effect.swipe) "swipe" else "tap")
            .put("x", effect.startX).put("y", effect.startY)
        if (effect.swipe) request.put("endX", effect.endX).put("endY", effect.endY).put("durationMs", effect.durationMs)
        worker.execute {
            try {
                if (previewDiscreteDeliveryAllowed(effect.version, lifecycle.generation)) service.action(request)
            } catch (_: Exception) { main.post { report("操作未执行，请确认副屏中的目标应用状态") } }
            finally { inputBusy.set(false) }
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
        // 清屏时丢掉按下点（触摸流内部的那一份），与改造前 `down = null` 同一位置、同一语义。
        touchFlow.clearDownPoint()
        setImageDrawable(null); displayed = null
        // 观测状态（画面时刻 / 会话 / 上一次采样）与画面一起清空：判定核心持有它们，清屏只有这一条路径。
        core.clear()
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
            val version = lifecycle.generation
            // 本拍开始时刻由拍子调度器记账：续排延迟要用它扣掉本拍耗时（见 VirtualScreenPreviewPump）。
            pump.fetchStarted()
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
                    if (version != lifecycle.generation || executor == null) {
                        fetchedHardware?.close()
                        return@post
                    }
                    // 判定权威在客户端：宿主那个只看固定采样线的 frameBlank 只作提示，不再决定清不清屏
                    // （0.2.9 的真机黑屏就是把它当成了「不能显示」）。判定与状态行已抽到
                    // VirtualScreenPreviewCore：这里只把这一拍的事实交给它，再按结论转移缓冲所有权。
                    val decoded = bitmap != null
                    val result = core.accept(
                        PreviewSample(
                            sessionId = id,
                            channel = channel,
                            mode = mode,
                            fps = fps,
                            decoded = decoded,
                            samples = samples,
                            hardwareFrame = hardwareFrame,
                        ),
                        SystemClock.elapsedRealtime(),
                    )
                    // 本拍从开始取帧到画面回到主线程花了多久：与续排延迟同一起点（见 pump.elapsedMillis）。
                    val beat = budget.record(mode, pump.elapsedMillis())
                    when (result.action) {
                        VirtualScreenPolicy.PreviewFrameAction.SHOW -> {
                            bitmap?.let {
                                val oldHardware = displayedHardware
                                displayedHardware = fetchedHardware
                                fetchedHardware = null
                                setImageBitmap(it); displayed = it
                                oldHardware?.close()
                            }
                            reportFrame(result.line, beat)
                        }
                        VirtualScreenPolicy.PreviewFrameAction.KEEP -> {
                            // 接近纯色的新帧或这一拍读不到帧：保留最近一张非空白画面（绝不清屏），也不动画面时刻。
                            fetchedHardware?.close()
                            fetchedHardware = null
                            reportFrame(result.line, beat)
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
                    pump.fetchLanded(mode)
                }
            }
        }
    }

    /**
     * 取帧拍子：一拍只续排一次、延迟 = 档位间隔 − 本拍耗时、慢帧不追赶积压（见 VirtualScreenPreviewPump）。
     *
     * `beat = tick`：投递与取消必须是同一个 Runnable 实例，`Handler.removeCallbacks` 按实例匹配。
     */
    private val pump: VirtualScreenPreviewPump = VirtualScreenPreviewPump(
        now = { SystemClock.elapsedRealtime() },
        postDelayed = { delay, action -> main.postDelayed(action, delay) },
        removeCallbacks = { action -> main.removeCallbacks(action) },
        beat = tick,
    )

    /**
     * 取下窗口时是否保留取帧循环。默认 false —— 离开页面/收起小窗就该停线程、清画面。
     *
     * 「重新挂到最前」是唯一把它置位的场景：那一次 detach 之后马上还会 attach，
     * 若照默认逻辑关掉线程、清掉画面，重新挂回就只能停在最后一帧。
     */
    var retainFrameLoopOnDetach = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 重挂时这两件事必须只做一次：接收器重复注册会收到两次息屏通知，
        // 而每重挂一次就新建一对线程池，等于每次点击都泄漏一对线程。
        if (executor == null) executor = Executors.newSingleThreadExecutor()
        if (touchExecutor == null) touchExecutor = Executors.newSingleThreadExecutor()
        if (!screenOffRegistered) {
            androidx.core.content.ContextCompat.registerReceiver(context, screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            screenOffRegistered = true
        }
        // 临时重挂保持同代号：在途帧负责续排下一拍，排队中的 UP 也必须执行。
        if (lifecycle.attach()) {
            // 重新挂回意味着取帧线程池可能刚建、首帧要现解码：旧窗口的读数不能代表现在。
            budget.reset()
            pump.start()
        }
    }

    override fun onDetachedFromWindow() {
        releaseOnDetach(retainFrameLoopOnDetach)
        super.onDetachedFromWindow()
    }

    internal fun releaseOnDetach(retain: Boolean) {
        if (!lifecycle.detach(retain)) return
        // 先把未完成的触摸取消排到输入队列尾部，避免离开页面后目标仍处于按下状态。
        touchFlow.cancelForDetach()?.let { sendTouch(touchExecutor, VirtualScreenService.current, it) }
        // 停拍子：取消排队中的那一拍，在途帧回到主线程后也不再续排。
        pump.stop()
        executor?.shutdown(); executor = null
        touchExecutor?.shutdown(); touchExecutor = null
        if (screenOffRegistered) {
            runCatching { context.unregisterReceiver(screenOff) }
            screenOffRegistered = false
        }
        clearFrame()
    }
}
