package io.deepseekharness.mobile

import android.annotation.SuppressLint
import android.net.Uri
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import io.deepseekharness.mobile.overlay.OverlayBallPolicy
import io.deepseekharness.mobile.overlay.OverlayBallPreferences
import io.deepseekharness.mobile.overlay.OverlayBallSecondaryPolicy
import io.deepseekharness.mobile.overlay.OverlayConversationSizePolicy
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLog
import io.deepseekharness.mobile.runtime.RuntimeStore
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.HarnessAccess
import io.deepseekharness.mobile.virtualscreen.VirtualScreenService
import java.util.UUID

/**
 * 悬浮球前台服务。
 *
 * 职责边界：
 *  - 显示可拖动的球；短按展开二级球（对话 / 副屏 / 回到应用 / 隐藏球 / 关闭无障碍），
 *    长按直接展开同源 Harness 对话小窗；
 *  - 不执行 Shell 命令，也不连接 Shizuku；小窗仅在已授权会话存活时使用内存中的一次性凭据；
 *  - 通知内容固定，不含 URL、端口、密码、终端输出或其他用户数据；
 *  - 明确不承诺常驻：Android 与厂商系统的内存回收、强制停止、电池与后台策略
 *    仍然可以随时结束本进程，球会随进程一起消失。
 *
 * 与 [HarnessKeepAliveService] 分开：两者启动条件互不相干，合并会让用户无法只选其一。
 */
class OverlayBallService : Service() {
    /** 服务侧自有实例：不依赖插件是否存活，服务启停本身也要进诊断时间线。 */
    private val diagnostics by lazy { DiagnosticLog(this) }
    private val preferences by lazy { OverlayBallPreferences.from(this) }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null

    /**
     * 球视图当前是否挂在 WindowManager 上。
     *
     * 不能只看 [ballView] 是否为空：权限被系统撤销时窗口会从 WindowManager 上移除，
     * 而视图引用仍然非空。此时若按下「引用非空」早退，之后每次同步都会误判成球已经在显示，
     * 球再也挂不回来。状态由视图的附着回调维护，并在挂载成功时置位、摘除时清零。
     */
    private var ballAttached = false

    private var conversationView: View? = null
    private var conversationWebView: WebView? = null
    private var conversationError: TextView? = null
    private var conversationParams: WindowManager.LayoutParams? = null
    private var conversationAccess: HarnessAccess? = null
    private var conversationOpening = false
    private var conversationRequest = 0L
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserRequest: String? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    /**
     * 已挂到窗口上的二级球（按 [OverlayBallSecondaryPolicy.CHOICE_ORDER] 顺序）。
     *
     * 二级球的「是否展开」就等于这个列表是否为空 —— 不另设布尔字段，理由见策略里的说明。
     */
    private val secondaryViews = mutableListOf<View>()

    /** 缩放手柄所在的独立 overlay 窗口；小窗关闭时一并摘除。 */
    private var conversationResizeHandle: View? = null

    /** 拖动开始时的尺寸基准：以「按下时的尺寸 + 位移」计算，保证拖动可逆。 */
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0

    /** 按下时的绝对坐标：手柄窗口会随缩放移动，只能用 raw 坐标算位移。 */
    private var resizeTouchStartX = 0f
    private var resizeTouchStartY = 0f

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartAt = 0L
    private var initialX = 0
    private var initialY = 0
    private var longPressFired = false

    override fun onCreate() {
        super.onCreate()
        activeService = this
        ensureNotificationChannel()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 可能收到系统重启产生的空 Intent；每次启动都重新读取持久化开关，
        // 避免用户已关闭悬浮球后服务仍被系统拉起并重新显示。
        val enabled = runCatching { RuntimeStore(applicationContext).overlayBallEnabled() }
            .getOrDefault(false)
        if (!enabled) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // 必须最先进入前台状态：Android 12+ 对 startForegroundService() 启动的服务有
        // 5 秒硬性要求，超时未调用 startForeground() 会终结整个进程。
        if (!startForegroundCompat()) {
            // 进入前台失败时立即收尾：既没有通知也没有前台身份，继续运行只会留下
            // 一个无法自解释的进程。球不显示，应用其余部分照常工作。
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // 权限可能在服务运行期间被系统或用户在设置里撤销，每次启动都重新确认。
        if (!OverlayBallPolicy.shouldShowBall(
                enabled = enabled,
                canDrawOverlays = Settings.canDrawOverlays(this),
            )
        ) {
            diagnostics.record(
                DiagnosticLevel.WARN,
                DiagnosticEvent.KEEP_ALIVE,
                mapOf("reason" to "overlay_denied", "active" to "false"),
            )
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!attachBall()) {
            // 失败原因已由 attachBall 记录并已调用 stopSelf；这里必须直接返回，
            // 否则下面那条「active=true」会把失败覆盖成成功，排障时看到自相矛盾的时间线。
            return START_NOT_STICKY
        }
        diagnostics.record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.KEEP_ALIVE,
            mapOf("reason" to "overlay_ball", "active" to "true"),
        )
        // 允许系统在进程被回收后按用户开关重启服务；启动路径会重新校验悬浮窗权限，
        // 并对保存坐标执行 clamp，避免恢复到屏幕外。用户显式 stopService 后不会重启。
        return START_STICKY
    }

    /** 划掉最近任务时不主动停止：由系统与厂商后台策略决定后续行为。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 有意留空。
    }

    /**
     * 屏幕旋转后把球拉回新的可视范围。
     *
     * 球的坐标是绝对像素值，旋转会让屏幕在某一维变小（横屏 x≈1700 转竖屏后超出新的
     * maxX）。而球是 FLAG_NOT_FOCUSABLE 的 overlay 窗口——它一旦落在屏幕外，用户既看
     * 不到也点不到，没法靠拖动自救。因此这里必须主动重夹一次并落盘。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 服务能收到的最稳定的系统回调：顺带复查一次权限。撤销后就不要再动
        // 一个可能已经失效的窗口，由 onDestroy 统一摘除。
        if (stopIfOverlayPermissionRevoked()) return
        val view = ballView ?: return
        val metrics = resources.displayMetrics
        val (x, y) = OverlayBallPolicy.clampPosition(
            layoutParams.x,
            layoutParams.y,
            metrics.widthPixels,
            metrics.heightPixels,
            layoutParams.width,
        )
        layoutParams.x = x
        layoutParams.y = y
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
        preferences.writePosition(x, y)
        // 二级球跟随主球，并重新做一次安全区夹取：旋转后可用宽高都变了，
        // 原来「排得下」的一列可能已经压到手势条区域。
        layoutSecondaryChoices()
        conversationParams?.let { params ->
            val (width, height) = conversationSize(metrics.widthPixels, metrics.heightPixels)
            params.width = width
            params.height = height
            val (panelX, panelY) = OverlayBallPolicy.menuPosition(
                x, y, layoutParams.width, width, height, metrics.widthPixels, metrics.heightPixels,
            )
            params.x = panelX
            params.y = panelY
            conversationView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
            // 小窗尺寸变了，手柄必须跟着挪，否则它会悬在小窗外面（那里已经没有可拖的边角）。
            updateResizeHandlePosition()
        }
    }

    override fun onDestroy() {
        detachConversation()
        // 二级球必须先摘：它们的位置依赖 layoutParams，虽然在 onDestroy 里已经用不到，
        // 但顺序反过来会让「先摘主球、后摘二级球」出现一次以失效坐标重排的窗口调用。
        detachSecondaryChoices()
        detachBall()
        stopForegroundCompat()
        // 服务结束（含进入前台失败、权限被撤销、用户点「隐藏悬浮球」等自停路径）后必须
        // 清掉运行标记：它对外表示「当前是否在运行」，只有 onDestroy 是所有结束路径的
        // 唯一汇合点；不在这里清，界面会把已经结束的服务一直显示成运行中。
        isRunning = false
        if (activeService === this) activeService = null
        diagnostics.record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.KEEP_ALIVE,
            mapOf("reason" to "overlay_ball_destroyed", "active" to "false"),
        )
        super.onDestroy()
    }

    /** 仅以启动方式运行，不提供绑定接口。 */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 运行期权限复查：悬浮窗权限已撤销时立即收尾，返回 true 表示调用方必须马上返回、
     * 不要再碰任何窗口。
     *
     * 设计承诺「权限被系统收回时立即 stopSelf()」，但系统不会为这个权限变化回调服务，
     * 也不该为此做常驻轮询。这里只在现有的两个时机顺带复查：用户仍然点得到球（触摸）时，
     * 以及系统配置变化（旋转）时；其余情况由插件在每次回到前台时的对齐负责收尾。
     * 记录字段与 onStartCommand 的驳回路径保持一致，便于按同一组条件排查。
     */
    private fun stopIfOverlayPermissionRevoked(): Boolean {
        if (Settings.canDrawOverlays(this)) return false
        diagnostics.record(
            DiagnosticLevel.WARN,
            DiagnosticEvent.KEEP_ALIVE,
            mapOf("reason" to "overlay_denied", "active" to "false"),
        )
        stopForegroundCompat()
        stopSelf()
        return true
    }

    // ── 球体 ────────────────────────────────────────────────────────────────

    /** 尝试把球挂到窗口上；返回 false 表示已经记录原因并请求停止服务。 */
    @SuppressLint("ClickableViewAccessibility") // handleTouch 会在短按分支调用 view.performClick()。
    private fun attachBall(): Boolean {
        // 早退条件必须是「球确实还挂在窗口上」，不能只判引用非空：权限被系统撤销时窗口可能
        // 已被移除而 ballView 仍非空，无条件早退会让之后每次同步都直接返回 true，球再也回不来。
        if (OverlayBallPolicy.canReuseBallView(
                viewPresent = ballView != null,
                viewAttached = ballAttached,
            )
        ) {
            return true
        }
        // 陈旧视图（窗口已被系统移除，或上一次挂载失败）：先安全摘除再重建，
        // 避免窗口泄漏与重复添加。已经不在 WindowManager 上时 removeView 会抛异常，吞掉即可。
        detachBall()
        val metrics = resources.displayMetrics
        val size = (BALL_SIZE_DP * metrics.density).toInt()

        // 充气必须显式带 AppCompat 主题：厂商「强制深色 / 强制主题」会把服务当前主题
        // 换成平台主题，此时布局里的 AppCompat 属性解析不到，inflate 抛
        // UnsupportedOperationException，异常从这里逃出去会终结整个进程 ——
        // 真机表现为「一打开悬浮球开关，App 启动就闪退」。
        val themed = ContextThemeWrapper(this, R.style.AppTheme)
        val view = runCatching { LayoutInflater.from(themed).inflate(R.layout.view_overlay_ball, null) }
            .getOrElse { error ->
                diagnostics.record(
                    DiagnosticLevel.WARN,
                    DiagnosticEvent.KEEP_ALIVE,
                    mapOf("reason" to "overlay_inflate_failed", "active" to "false"),
                )
                android.util.Log.w(
                    "dsh-runtime",
                    "overlay ball inflate failed: ${error.javaClass.simpleName}",
                )
                stopForegroundCompat()
                stopSelf()
                return false
            }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不可获焦：球不能抢输入法与按键，否则会打断用户正在用的应用。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val stored = preferences.readPosition()
        val (x, y) = if (stored != null) {
            OverlayBallPolicy.clampPosition(
                stored.first, stored.second,
                metrics.widthPixels, metrics.heightPixels, size,
            )
        } else {
            // 没存过位置：贴右边缘、垂直居中。
            OverlayBallPolicy.defaultPosition(
                metrics.widthPixels,
                metrics.heightPixels,
                size,
                (DEFAULT_MARGIN_DP * metrics.density).toInt(),
            )
        }
        params.x = x
        params.y = y

        // 触摸与无障碍操作共用语义点击入口，TalkBack 可直接点击或长按球体。
        // 短按＝展开/收起二级球：二级球上给出「会话」与「副屏」两个入口，以及
        // 回到应用 / 隐藏悬浮球 / 关闭无障碍。短按是最高频的动作，因此把「选择去哪里」
        // 放在短按上，而不是继续沿用「短按直接进对话」——那会让副屏没有同等入口。
        view.setOnClickListener {
            if (!stopIfOverlayPermissionRevoked()) toggleSecondaryChoices()
        }
        view.setOnLongClickListener {
            // 长按＝直接打开 Harness 对话小窗（原来短按的动作搬到长按），
            // 让熟手不必经二级球绕一次。
            if (!stopIfOverlayPermissionRevoked()) toggleConversation()
            true
        }
        view.setOnTouchListener { touched, event -> handleTouch(touched, event, size) }
        // 球是否还在窗口上由视图的附着回调维护：客户端侧的移除一定会回调，系统侧的移除
        // 视 ROM 实现而定，因此它只用于避免「已被摘掉的窗口挡住重挂」这一类误判，
        // 真正的清理由插件每次回前台的权限对齐（撤销 → 停服务 → 摘视图）兜底。
        // 只认当前这颗球的回调，避免重建期间旧视图的回调覆盖新状态。
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                if (attached === ballView) ballAttached = true
            }

            override fun onViewDetachedFromWindow(detached: View) {
                if (detached === ballView) ballAttached = false
            }
        })
        // 权限缺失时 addView 会直接抛异常（SecurityException / BadTokenException）。
        // onStartCommand 里已经检查过 canDrawOverlays，但用户完全可能在检查之后、
        // addView 之前把权限撤销掉 —— 这是真实竞态。这里兜一层：加不上球就结束服务，
        // 而不是让异常从 onStartCommand 逃出去终结整个进程（用户看到的是「闪退」）。
        val attached = runCatching { windowManager.addView(view, params) }
        if (attached.isFailure) {
            diagnostics.record(
                DiagnosticLevel.WARN,
                DiagnosticEvent.KEEP_ALIVE,
                mapOf("reason" to "overlay_attach_failed", "active" to "false"),
            )
            // 诊断日志只收白名单内的取值（reason 只接受全小写 token），异常类名进不去；
            // 而这里恰恰最需要区分「权限竞态 / token 非法 / 参数非法」，因此额外在
            // logcat 留一行类名，格式与前台失败路径一致。
            android.util.Log.w(
                "dsh-runtime",
                "overlay ball attach failed: ${attached.exceptionOrNull()?.javaClass?.simpleName}",
            )
            stopForegroundCompat()
            stopSelf()
            return false
        }
        ballView = view
        layoutParams = params
        // 乐观置位：addView 返回时窗口已登记，首次遍历（附着回调）还没跑完，
        // 这一小段空窗期不能把刚挂上的球误判成陈旧视图。
        ballAttached = true
        return true
    }

    private fun detachBall() {
        ballView?.let { view ->
            ballAttached = false
            runCatching { windowManager.removeView(view) }
        }
        ballView = null
    }

    /**
     * 触摸处理：短按弹二级球、长按直接回对话、拖动移动球。
     *
     * 判定复用 [OverlayBallPolicy]，与单测覆盖的是同一套规则。
     */
    private fun handleTouch(view: View, event: MotionEvent, size: Int): Boolean {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 运行期复查权限：球还在屏幕上、用户仍然点得到，说明权限可能是在本服务运行期间
                // 被撤销的（部分 ROM 不杀进程也不摘窗口）。此时立即收尾并吞掉本次触摸，
                // 不留「球还在、通知也在」的假象。
                if (stopIfOverlayPermissionRevoked()) return true
                touchStartX = event.rawX
                touchStartY = event.rawY
                touchStartAt = SystemClock.uptimeMillis()
                initialX = layoutParams.x
                initialY = layoutParams.y
                longPressFired = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - touchStartX
                val deltaY = event.rawY - touchStartY
                // 多指手势一律不拖动：rawX/rawY 恒取 pointer 0，第一根手指抬起、剩余手指
                // 重编号时坐标会跳，按位移算出来的球位会跟着跳一段。
                if (event.pointerCount > 1) return true
                // 长按已弹出菜单后不再拖动：此时 ACTION_UP 会直接返回，跳过吸附与落盘，
                // 球会停在非吸附位置且位置没保存，全程还被不透明遮罩挡着。
                if (!longPressFired && !OverlayBallPolicy.isClick(deltaX, deltaY, slop)) {
                    val metrics = resources.displayMetrics
                    val (x, y) = OverlayBallPolicy.clampPosition(
                        initialX + deltaX.toInt(),
                        initialY + deltaY.toInt(),
                        metrics.widthPixels, metrics.heightPixels, size,
                    )
                    layoutParams.x = x
                    layoutParams.y = y
                    runCatching { windowManager.updateViewLayout(ballView, layoutParams) }
                    // 二级球跟随主球：它们的位置全部由主球坐标推导，因此这里不需要缓存
                    // 每个按钮的旧坐标，直接按新坐标重排一次即可。
                    layoutSecondaryChoices()
                } else if (!longPressFired &&
                    OverlayBallPolicy.isLongPress(
                        deltaX, deltaY, slop,
                        SystemClock.uptimeMillis() - touchStartAt,
                        ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                ) {
                    // 拖动分支先被 isClick 排除，走到这里说明手指基本没动；
                    // 按住不动的期间收不到新的 MOVE 事件，靠这一次到达阈值时弹出菜单。
                    longPressFired = true
                    view.performLongClick()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = event.rawX - touchStartX
                val deltaY = event.rawY - touchStartY
                if (longPressFired) return true
                val held = SystemClock.uptimeMillis() - touchStartAt
                if (OverlayBallPolicy.isLongPress(
                        deltaX, deltaY, slop, held,
                        ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                ) {
                    view.performLongClick()
                    return true
                }
                if (OverlayBallPolicy.isClick(deltaX, deltaY, slop)) {
                    view.performClick()
                } else {
                    // 拖动结束：吸附到最近边缘并记住位置。
                    val metrics = resources.displayMetrics
                    layoutParams.x = OverlayBallPolicy.snapToEdge(
                        layoutParams.x, metrics.widthPixels, size,
                    )
                    runCatching { windowManager.updateViewLayout(ballView, layoutParams) }
                    preferences.writePosition(layoutParams.x, layoutParams.y)
                    layoutSecondaryChoices()
                }
                return true
            }
        }
        return false
    }

    /**
     * 短按：经 [PendingIntent] 转到 [KeepAliveEntryActivity]。
     *
     * 不直接 startActivity —— Android 10+ 限制后台启动 Activity，从 overlay 直接起
     * 会被静默丢弃。也不指向 singleTask 的 MainActivity：那会 clear-top 掉正在显示的
     * HarnessActivity 并撤销会话凭据。
     */
    private fun openHarness() {
        val intent = Intent(this, KeepAliveEntryActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { pending.send() }
    }

    private fun toggleConversation() {
        if (conversationView != null || conversationOpening) {
            detachConversation()
        } else {
            conversationOpening = true
            val request = ++conversationRequest
            Thread({
                // Startup may hold the runtime lock for several seconds; never wait on the overlay UI thread.
                val access = RuntimeHost.controllerOrNull()?.let {
                    runCatching { it.openHarnessAccess() }.getOrNull()
                }
                Handler(Looper.getMainLooper()).post {
                    if (request != conversationRequest || activeService !== this) return@post
                    conversationOpening = false
                    if (!stopIfOverlayPermissionRevoked()) showConversation(access)
                }
            }, "dsh-overlay-access").start()
        }
    }

    /** The small window uses the same authenticated local page as the full Harness screen. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun showConversation(access: HarnessAccess?) {
        // 同一时刻只应有一个「展开物」：小窗打开时收起二级球，否则按钮会浮在小窗之上，
        // 用户点哪里都像在跟另一个界面打架。
        detachSecondaryChoices()
        val metrics = resources.displayMetrics
        val (width, height) = conversationSize(metrics.widthPixels, metrics.heightPixels)
        val (x, y) = OverlayBallPolicy.menuPosition(
            layoutParams.x, layoutParams.y, layoutParams.width,
            width, height, metrics.widthPixels, metrics.heightPixels,
        )
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(overlayColor(R.color.overlay_panel_background))
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(4).toFloat()
            clipToOutline = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener(object : View.OnTouchListener {
                var startX = 0f
                var startY = 0f
                var panelX = 0
                var panelY = 0

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    val params = conversationParams ?: return false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX
                            startY = event.rawY
                            panelX = params.x
                            panelY = params.y
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val screen = resources.displayMetrics
                            params.x = (panelX + (event.rawX - startX).toInt())
                                .coerceIn(0, (screen.widthPixels - params.width).coerceAtLeast(0))
                            params.y = (panelY + (event.rawY - startY).toInt())
                                .coerceIn(0, (screen.heightPixels - params.height).coerceAtLeast(0))
                            conversationView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                        }
                    }
                    return true
                }
            })
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.overlay_conversation_title)
            setTextColor(overlayColor(R.color.overlay_panel_foreground))
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(conversationAction(R.string.overlay_conversation_expand) {
            if (access != null) AppAuthenticationState.authorizeHarnessLaunch(access)
            detachConversation()
            openHarness()
        })
        header.addView(conversationAction(R.string.overlay_conversation_close) { detachConversation() })
        frame.addView(header)

        val body = FrameLayout(this)
        frame.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val origin = HarnessActivity.Origin.parse(access?.url)
        val cookie = access?.password?.let { runCatching { HarnessSessionCookie.authenticated(it) }.getOrNull() }
        if (access == null || origin == null || cookie == null) {
            body.addView(TextView(this).apply {
                text = getString(R.string.overlay_conversation_unavailable)
                setTextColor(overlayColor(R.color.overlay_panel_foreground))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(16), dp(24), dp(16))
                setOnClickListener {
                    detachConversation()
                    openHarness()
                }
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val web = WebView(this)
            web.setBackgroundColor(overlayColor(R.color.harness_page_background))
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                // SAF grants access only to the user's selected content:// URI.
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                safeBrowsingEnabled = true
                textZoom = AppTextScale.percentOf(resources.configuration.fontScale)
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(web, false)
            }
            val error = TextView(this).apply {
                text = getString(R.string.harness_page_failed)
                setTextColor(overlayColor(R.color.overlay_panel_foreground))
                gravity = Gravity.CENTER
                setBackgroundColor(overlayColor(R.color.overlay_panel_background))
                setPadding(dp(24), dp(16), dp(24), dp(16))
                visibility = View.GONE
            }
            body.addView(web, FrameLayout.LayoutParams(-1, -1))
            body.addView(error, FrameLayout.LayoutParams(-1, -1))
            conversationWebView = web
            conversationError = error
            web.webViewClient = HarnessActivity.RestrictedWebViewClient(
                origin, access.username, access.password,
                onMainFrameFailure = { if (conversationWebView === web) error.visibility = View.VISIBLE },
                onRendererGone = { detachConversation() },
            )
            web.webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?,
                ): Boolean {
                    if (callback == null) return false
                    if (view !== conversationWebView) {
                        callback.onReceiveValue(null)
                        return true
                    }
                    cancelFileChooser()
                    val request = UUID.randomUUID().toString()
                    fileChooserRequest = request
                    fileChooserCallback = callback
                    if (!OverlayFileChooserActivity.open(this@OverlayBallService, request, params)) {
                        deliverFileChooserResult(request, emptyList())
                    }
                    return true
                }
            }
        }

        val params = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        if (runCatching { windowManager.addView(frame, params) }.isFailure) {
            conversationWebView?.destroy()
            conversationWebView = null
            conversationError = null
            return
        }
        conversationView = frame
        conversationParams = params
        conversationAccess = access
        attachResizeHandle()
        if (access == null || origin == null || cookie == null) return
        val web = conversationWebView ?: return
        val entry = runCatching {
            HarnessPageUrl.withVersions(
                origin.initialUrl, BuildConfig.VERSION_NAME,
                RuntimeStore(this).installedManifest()?.version,
            )
        }.getOrNull() ?: run {
            conversationError?.visibility = View.VISIBLE
            return
        }
        web.settings.cacheMode = when (HarnessPageCache.modeFor(entry)) {
            HarnessCacheMode.NORMAL -> WebSettings.LOAD_DEFAULT
            HarnessCacheMode.BYPASS -> WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setCookie(HarnessSessionCookie.origin(origin.port), cookie) { accepted ->
            if (conversationWebView !== web) return@setCookie
            // This window can be opened before the full-page Activity creates its own auth state.
            if (!accepted || conversationAccess !== access) {
                conversationError?.visibility = View.VISIBLE
                return@setCookie
            }
            CookieManager.getInstance().flush()
            web.loadUrl(entry)
        }
    }

    // ── 小窗缩放 ────────────────────────────────────────────────────────────

    /**
     * 挂上右下角的缩放手柄。
     *
     * 为什么手柄是**独立窗口**而不是小窗内部的子视图：小窗的根是 `showConversation` 里
     * 现搭的 `LinearLayout`，往里面塞一个悬浮在内容之上的手柄需要把根换成 `FrameLayout`
     * 并重排 header/body 两层（还要动 `res/layout`，那不在本轮范围内）。独立窗口只依赖
     * 已经存在的 `WindowManager` 与 `conversationParams`，和二级球用的是同一套机制。
     *
     * 代价说清楚：手柄窗口会盖住小窗右下角约 44dp 的一小块内容（它就落在那里，否则用户
     * 找不到它），以及拖动时多一次 `updateViewLayout`。两者都不影响内容本身。
     */
    private fun attachResizeHandle() {
        detachResizeHandle()
        val params = conversationParams ?: return
        val size = dp(OverlayConversationSizePolicy.HANDLE_TOUCH_SIZE_DP)
        val handle = FrameLayout(this).apply {
            contentDescription = getString(R.string.overlay_conversation_resize_description)
            isClickable = true
            addView(TextView(this@OverlayBallService).apply {
                text = RESIZE_GLYPH
                setTextColor(overlayColor(R.color.overlay_panel_foreground))
                textSize = 14f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(overlayColor(R.color.overlay_resize_background))
                }
                val visual = dp(OverlayConversationSizePolicy.HANDLE_VISUAL_SIZE_DP)
                layoutParams = FrameLayout.LayoutParams(visual, visual, Gravity.CENTER)
                isClickable = false
                isFocusable = false
                contentDescription = (parent as? View)?.contentDescription
            }, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            setOnTouchListener { _, event -> handleResizeTouch(event, size) }
        }
        val windowParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val (handleX, handleY) = resizeHandlePosition(params, size)
        windowParams.x = handleX
        windowParams.y = handleY
        if (runCatching { windowManager.addView(handle, windowParams) }.isFailure) {
            // 手柄挂不上不算失败：小窗本身照常用，只是这次不能缩放。
            return
        }
        conversationResizeHandle = handle
    }

    private fun detachResizeHandle() {
        conversationResizeHandle?.let { runCatching { windowManager.removeView(it) } }
        conversationResizeHandle = null
    }

    /**
     * 手柄在小窗**外面**的那点内缩怎么算：手柄的左上角 = 面板右下角 - 手柄边长 - 内缩。
     *
     * 全部用绝对屏幕坐标，因为手柄是独立窗口，不共享小窗的坐标系。
     */
    private fun resizeHandlePosition(
        params: WindowManager.LayoutParams,
        handleSize: Int,
    ): Pair<Int, Int> {
        val inset = dp(OverlayConversationSizePolicy.HANDLE_INSET_DP)
        val (localX, localY) = OverlayConversationSizePolicy.handlePosition(
            panelWidth = params.width,
            panelHeight = params.height,
            handleSize = handleSize,
            inset = inset,
        )
        return params.x + localX to params.y + localY
    }

    private fun updateResizeHandlePosition() {
        val handle = conversationResizeHandle ?: return
        val params = conversationParams ?: return
        val size = dp(OverlayConversationSizePolicy.HANDLE_TOUCH_SIZE_DP)
        val windowParams = handle.layoutParams as? WindowManager.LayoutParams ?: return
        val (x, y) = resizeHandlePosition(params, size)
        windowParams.x = x
        windowParams.y = y
        runCatching { windowManager.updateViewLayout(handle, windowParams) }
    }

    /**
     * 手柄拖动：按下时记基准尺寸，移动时按位移算新尺寸并夹取。
     *
     * 以**按下时的尺寸 + 位移**为基准（而不是逐帧累加当前尺寸）：后者会把夹取结果当成
     * 用户意图，手指拖回原处时窗口回不到原大小。
     *
     * 用 `event.rawX/rawY` 而不是局部坐标：手柄窗口本身会随着缩放一起移动，
     * 局部坐标在拖动过程中同时被「手指位移」和「窗口位移」改变，算出来的位移会翻倍。
     */
    private fun handleResizeTouch(event: MotionEvent, handleSize: Int): Boolean {
        val params = conversationParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartWidth = params.width
                resizeStartHeight = params.height
                resizeTouchStartX = event.rawX
                resizeTouchStartY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val screen = resources.displayMetrics
                val minWidth = dp(OverlayConversationSizePolicy.MIN_WIDTH_DP)
                val minHeight = dp(OverlayConversationSizePolicy.MIN_HEIGHT_DP)
                val availableWidth = (screen.widthPixels - dp(CONVERSATION_MARGIN_H_DP)).coerceAtLeast(1)
                val availableHeight = (screen.heightPixels - dp(CONVERSATION_MARGIN_V_DP)).coerceAtLeast(1)
                val (width, height) = OverlayConversationSizePolicy.resized(
                    startWidth = resizeStartWidth,
                    startHeight = resizeStartHeight,
                    deltaX = (event.rawX - resizeTouchStartX).toInt(),
                    deltaY = (event.rawY - resizeTouchStartY).toInt(),
                    availableWidth = availableWidth,
                    availableHeight = availableHeight,
                    minWidth = minWidth,
                    minHeight = minHeight,
                )
                applyConversationSize(width, height)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 只在手指抬起时落盘：拖动过程中每一帧都写一次偏好没有意义，
                // 而且中途被杀会留下一个用户从没确认过的尺寸。
                persistConversationSize()
                return true
            }
        }
        return false
    }

    /**
     * 应用新的小窗尺寸，并把内容重新量一遍。
     *
     * 内容不会溢出/裁切，靠的是三层：
     *  1. `updateViewLayout` 带上新宽高会让 WindowManager 重新 measure/layout 整个
     *     overlay 视图树（header 是固定 52dp + weight，body 是 weight=1 的 FrameLayout），
     *     因此 WebView 自身会拿到新的尺寸；
     *  2. 这里再显式 `requestLayout()` 一次：拖动到系统帧边界时窗口尺寸可能没变
     *     （被夹取到同一个值），此时 WebView 仍需要一次重排来刷新它的 viewport；
     *  3. 窗口参数里的 `SOFT_INPUT_ADJUST_RESIZE` 保持不变，因此键盘弹出/收起时
     *     内容区高度照旧由系统重新计算，不依赖这一次缩放。
     */
    private fun applyConversationSize(width: Int, height: Int) {
        val params = conversationParams ?: return
        params.width = width
        params.height = height
        val screen = resources.displayMetrics
        // 变大之后右下角可能越界：位置必须一起夹，否则窗口会有一半在屏幕外。
        params.x = params.x.coerceIn(0, (screen.widthPixels - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.heightPixels - height).coerceAtLeast(0))
        conversationView?.let { view ->
            runCatching { windowManager.updateViewLayout(view, params) }
            view.requestLayout()
            view.invalidate()
        }
        conversationWebView?.let { web ->
            web.requestLayout()
            web.invalidate()
        }
        updateResizeHandlePosition()
    }

    private fun persistConversationSize() {
        val params = conversationParams ?: return
        val screen = resources.displayMetrics
        writeStoredSize(params.width, params.height, screen.widthPixels, screen.heightPixels)
    }

    private fun conversationAction(label: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = getString(label)
        contentDescription = text
        setTextColor(overlayColor(R.color.overlay_panel_foreground))
        gravity = Gravity.CENTER
        textSize = 14f
        minWidth = dp(48)
        minHeight = dp(48)
        isFocusable = true
        background = overlayPalette().getDrawable(R.drawable.bg_overlay_action)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { action() }
    }

    /**
     * 悬浮球与二级菜单取颜色、取 drawable 用的资源上下文。
     *
     * 直接复用 [AppThemePreference.palette]（不再在这里手抄一遍覆写 uiMode 的逻辑）：
     * 只有按**已保存的应用主题**覆写过 `uiMode`，`R.color.*` 才会解析成用户选的那一套；
     * 否则「应用选了浅色、系统停在深色」时悬浮球菜单会拿到系统主题的颜色，看起来就是没跟主题走。
     */
    private fun overlayPalette(): Context = AppThemePreference.palette(this)

    /** 取一个随应用主题变化的颜色令牌（`res/values{,-night}/colors.xml`）。 */
    private fun overlayColor(resId: Int): Int = AppThemePreference.color(this, resId)

    /**
     * 小窗的初始尺寸。
     *
     * 优先用用户上次调好并留下的大小（同屏幕宽高时），否则用默认值；两种情况都会按
     * 「最小尺寸 / 屏幕可用区域的 90%」夹取一次，因此历史值损坏、或来自另一块屏幕的
     * 离谱数值都不会被直接套用（[OverlayConversationSizePolicy.storedState] 只负责判断
     * 「还算不算数」，合法范围一律走 clamp）。
     */
    private fun conversationSize(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val minWidth = dp(OverlayConversationSizePolicy.MIN_WIDTH_DP)
        val minHeight = dp(OverlayConversationSizePolicy.MIN_HEIGHT_DP)
        val availableWidth = (screenWidth - dp(CONVERSATION_MARGIN_H_DP)).coerceAtLeast(1)
        val availableHeight = (screenHeight - dp(CONVERSATION_MARGIN_V_DP)).coerceAtLeast(1)
        val storedState = OverlayConversationSizePolicy.storedState(
            storedWidth = storedInt(KEY_CONVERSATION_WIDTH),
            storedHeight = storedInt(KEY_CONVERSATION_HEIGHT),
            storedScreenWidth = storedInt(KEY_CONVERSATION_SCREEN_W),
            storedScreenHeight = storedInt(KEY_CONVERSATION_SCREEN_H),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        val requestedWidth = if (storedState == OverlayConversationSizePolicy.Stored.USABLE) {
            storedInt(KEY_CONVERSATION_WIDTH) ?: 0
        } else {
            dp(OverlayConversationSizePolicy.DEFAULT_WIDTH_DP)
        }
        val requestedHeight = if (storedState == OverlayConversationSizePolicy.Stored.USABLE) {
            storedInt(KEY_CONVERSATION_HEIGHT) ?: 0
        } else {
            dp(OverlayConversationSizePolicy.DEFAULT_HEIGHT_DP)
        }
        val width = OverlayConversationSizePolicy.clampWidth(requestedWidth, availableWidth, minWidth)
        val height = OverlayConversationSizePolicy.clampHeight(requestedHeight, availableHeight, minHeight)
        // 记下「这套尺寸是在哪块屏幕上定的」：旋转/分屏之后这份记录就对不上了，
        // 下次开窗会退回默认尺寸而不是把一张横向的宽窗硬塞进竖向屏幕。
        writeStoredSize(width, height, screenWidth, screenHeight)
        return width to height
    }

    /**
     * 尺寸存盘。
     *
     * 与球的位置共用 `dsh-overlay-ball` 偏好文件（同一份纯视图状态，不需要参与备份/清理），
     * 但**不复用 `OverlayBallPreferences`**：那个类的 `Storage` 抽象只暴露 int 的键值读写，
     * 尺寸需要的是「一组四个整数必须同时成立或同时作废」的语义，塞进去只会让位置那条
     * 已经稳定的路径跟着变复杂。这里用同一份 SharedPreferences 的独立键，互不影响。
     */
    private fun writeStoredSize(width: Int, height: Int, screenWidth: Int, screenHeight: Int) {
        runCatching {
            overlayPreferences().edit()
                .putInt(KEY_CONVERSATION_WIDTH, width)
                .putInt(KEY_CONVERSATION_HEIGHT, height)
                .putInt(KEY_CONVERSATION_SCREEN_W, screenWidth)
                .putInt(KEY_CONVERSATION_SCREEN_H, screenHeight)
                .apply()
        }
    }

    private fun storedInt(key: String): Int? = runCatching {
        val preferences = overlayPreferences()
        if (preferences.contains(key)) preferences.getInt(key, 0) else null
    }.getOrNull()

    private fun overlayPreferences() = applicationContext
        .getSharedPreferences(OVERLAY_PREFERENCES_FILE, Context.MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun cancelFileChooser() {
        fileChooserRequest = null
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        runCatching { callback.onReceiveValue(null) }
    }

    private fun deliverFileChooserResult(request: String, uris: List<Uri>) {
        if (request != fileChooserRequest) return
        val callback = fileChooserCallback ?: return
        fileChooserRequest = null
        fileChooserCallback = null
        // The picker result is external input; WebView must never receive file:// or another scheme.
        val accepted = uris.filter {
            it.scheme == ContentResolver.SCHEME_CONTENT &&
                (it.authority?.length?.let { length -> length in 1..255 } == true) &&
                it.toString().length <= 4096
        }.take(16)
        runCatching { callback.onReceiveValue(accepted.takeIf { it.isNotEmpty() }?.toTypedArray()) }
    }

    private fun detachConversation() {
        conversationRequest++
        conversationOpening = false
        cancelFileChooser()
        detachResizeHandle()
        val access = conversationAccess
        conversationAccess = null
        conversationView?.let { runCatching { windowManager.removeView(it) } }
        conversationView = null
        conversationParams = null
        conversationError = null
        conversationWebView?.let { web ->
            conversationWebView = null
            runCatching {
                web.stopLoading()
                web.webChromeClient = null
                web.webViewClient = WebViewClient()
                web.destroy()
            }
        }
        // The full-page Activity owns its cookie; a standalone small window clears only its own.
        if (!AppAuthenticationState.isHarnessAuthenticated() && access != null) {
            HarnessActivity.Origin.parse(access.url)?.let { origin ->
                val cookies = CookieManager.getInstance()
                cookies.setCookie(HarnessSessionCookie.origin(origin.port), HarnessSessionCookie.expired()) {
                    cookies.flush()
                }
            }
        }
    }

    // ── 二级球（长按主球展开） ──────────────────────────────────────────────

    /**
     * 长按主球：展开或收起二级球。
     *
     * 为什么用**长按**而不是单击展开：单击已经是「打开/关闭对话小窗」，那是这个球最高频的
     * 动作，把它换成二级球会让老用户每次都要多点一下；而长按本来就没有占用（旧实现是弹
     * 文字菜单）。代价是二级球的可发现性依赖用户愿意长按 —— 这一点由资源文案里的
     * `contentDescription` 与文档里的真机验收项兜住，不做额外引导蒙层。
     *
     * 打开对话小窗 / 隐藏球 / 服务结束都会收起二级球：悬浮球同一时刻只应有一个「展开物」，
     * 否则用户点哪个都像是在跟另一个打架。
     */
    private fun toggleSecondaryChoices() {
        when (OverlayBallSecondaryPolicy.toggleChoice(expanded = secondaryViews.isNotEmpty())) {
            OverlayBallSecondaryPolicy.Decision.COLLAPSE -> detachSecondaryChoices()
            OverlayBallSecondaryPolicy.Decision.EXPAND -> showSecondaryChoices()
        }
    }

    private fun showSecondaryChoices() {
        detachSecondaryChoices()
        if (ballView == null) return
        val order = OverlayBallSecondaryPolicy.CHOICE_ORDER
        val size = dp(OverlayBallSecondaryPolicy.BUTTON_SIZE_DP)
        for (choice in order) {
            val view = buildSecondaryChoice(choice, size)
            // 先加到屏幕外再统一排位：直接放在 0,0 会让第一帧闪现在左上角。
            val params = secondaryParams(0, 0, size)
            val added = runCatching { windowManager.addView(view, params) }
            if (added.isFailure) {
                // 加不上就整组收回：只剩一半的二级球比没有更让人困惑。
                detachSecondaryChoices()
                return
            }
            secondaryViews += view
        }
        layoutSecondaryChoices()
    }

    private fun buildSecondaryChoice(
        choice: OverlayBallSecondaryPolicy.Choice,
        size: Int,
    ): View {
        val connected = DeepSeekAccessibilityService.current() != null
        val labelRes = when (choice) {
            OverlayBallSecondaryPolicy.Choice.CONVERSATION ->
                R.string.overlay_ball_secondary_conversation_description
            OverlayBallSecondaryPolicy.Choice.VIRTUAL_SCREEN ->
                R.string.overlay_ball_secondary_virtual_screen_description
            OverlayBallSecondaryPolicy.Choice.RETURN_TO_APP ->
                R.string.overlay_ball_secondary_return_description
            OverlayBallSecondaryPolicy.Choice.HIDE_BALL ->
                R.string.overlay_ball_secondary_hide_description
            OverlayBallSecondaryPolicy.Choice.DISABLE_ACCESSIBILITY ->
                if (OverlayBallSecondaryPolicy.showsDisabledState(connected)) {
                    R.string.overlay_ball_secondary_accessibility_off_description
                } else {
                    R.string.overlay_ball_secondary_accessibility_on_description
                }
        }
        return FrameLayout(this).apply {
            // 命中区就是这个视图本身，尺寸由布局参数钉死不小于 40dp；里面那个圆点更小，
            // 但按下判定的边界仍是这里，因此不需要额外的 touch delegate。
            contentDescription = getString(labelRes)
            isClickable = true
            isFocusable = true
            background = overlayPalette().getDrawable(R.drawable.bg_overlay_secondary_action)
            addView(secondaryGlyph(choice, size, connected), FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            setOnClickListener {
                // 点完先收起：动作本身可能让球消失（隐藏球）或让应用切到前台，
                // 留着展开态只会出现「球没了、按钮还在」。
                detachSecondaryChoices()
                onSecondaryChoice(choice)
            }
        }
    }

    private fun secondaryGlyph(
        choice: OverlayBallSecondaryPolicy.Choice,
        size: Int,
        accessibilityConnected: Boolean,
    ): TextView = TextView(this).apply {
        val visual = dp(OverlayBallSecondaryPolicy.VISUAL_SIZE_DP)
        text = when (choice) {
            OverlayBallSecondaryPolicy.Choice.CONVERSATION -> SECONDARY_GLYPH_CONVERSATION
            OverlayBallSecondaryPolicy.Choice.VIRTUAL_SCREEN -> SECONDARY_GLYPH_SCREEN
            OverlayBallSecondaryPolicy.Choice.RETURN_TO_APP -> SECONDARY_GLYPH_RETURN
            OverlayBallSecondaryPolicy.Choice.HIDE_BALL -> SECONDARY_GLYPH_HIDE
            OverlayBallSecondaryPolicy.Choice.DISABLE_ACCESSIBILITY ->
                if (OverlayBallSecondaryPolicy.showsDisabledState(accessibilityConnected)) {
                    SECONDARY_GLYPH_DISABLED
                } else {
                    SECONDARY_GLYPH_CLOSE
                }
        }
        setTextColor(overlayColor(R.color.overlay_secondary_foreground))
        textSize = SECONDARY_TEXT_SIZE_SP
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                if (OverlayBallSecondaryPolicy.showsDisabledState(accessibilityConnected) &&
                    choice == OverlayBallSecondaryPolicy.Choice.DISABLE_ACCESSIBILITY
                ) {
                    overlayColor(R.color.overlay_secondary_background_disabled)
                } else {
                    overlayColor(R.color.overlay_secondary_background)
                },
            )
        }
        layoutParams = FrameLayout.LayoutParams(visual, visual, Gravity.CENTER)
        // 圆点自己也报一次：TalkBack 的焦点会落在最外层的可点击 FrameLayout 上，
        // 但那层没有文字；两层都有描述时读数一致，不会出现「焦点在一处、描述在另一处」。
        contentDescription = (parent as? View)?.contentDescription
        // 不能设为可点击：抢占焦点会让无障碍用户在按钮组里多跳一层空视图。
        isClickable = false
        isFocusable = false
    }

    private fun onSecondaryChoice(choice: OverlayBallSecondaryPolicy.Choice) {
        when (choice) {
            OverlayBallSecondaryPolicy.Choice.CONVERSATION -> toggleConversation()
            OverlayBallSecondaryPolicy.Choice.VIRTUAL_SCREEN -> openVirtualScreen()
            OverlayBallSecondaryPolicy.Choice.RETURN_TO_APP -> returnToApp()
            OverlayBallSecondaryPolicy.Choice.HIDE_BALL -> hideBall()
            OverlayBallSecondaryPolicy.Choice.DISABLE_ACCESSIBILITY -> disableAccessibilityService()
        }
    }

    /**
     * 「打开副屏」：把目标应用副屏以系统级悬浮窗的形式显示出来。
     *
     * 窗口本身、触摸直传与帧率档位都在 [VirtualScreenService.showOverlay] 里
     * （见 `docs/目标应用副屏.md`），这里只负责一件事：副屏没在跑时说清楚该从哪里打开，
     * 而不是让按钮看起来坏了。
     */
    private fun openVirtualScreen() {
        val screen = VirtualScreenService.current
        val shown = runCatching { screen?.showOverlay() }
        if (screen == null || shown.isFailure) {
            toastCurrentThread(getString(R.string.overlay_ball_secondary_virtual_screen_unavailable_toast))
        }
    }

    /**
     * 「关闭无障碍服务」。
     *
     * `disableSelf()` 只在**本应用进程当前正持有该服务**时有效（它是 `AccessibilityService`
     * 的实例方法），因此判断服务是否连着的唯一入口就是 `current()`：
     *  - 连着 → 关闭。系统会在服务断开后回调 `onDestroy`，`current()` 随之变 null。
     *  - 没连着 → 明确提示一次，不做任何动作。这里**不能**静默：用户点了一个按钮却什么都没发生，
     *    会以为是按钮坏了，而真实原因（服务早就关掉了）恰恰是他最需要知道的那一条。
     *
     * 厂商 ROM 上 `disableSelf()` 的实际行为需要在真机上确认（见 `docs/悬浮球与内容分享.md`）：
     * 有的 ROM 会把它当成「用户主动关闭」并记住，有的会立刻被系统重新拉起。
     * 外壳设置页的状态来自 5 秒轮询，因此关闭之后界面可能短暂仍显示「已开启」——
     * 这是**已知且不掩盖**的时延，不在这里假装同步。
     */
    private fun disableAccessibilityService() {
        val service = DeepSeekAccessibilityService.current()
        when (OverlayBallSecondaryPolicy.accessibilityState(serviceConnected = service != null)) {
            OverlayBallSecondaryPolicy.AccessibilityAction.DISABLE_SELF -> {
                runCatching { service?.disableSelf() }
                toastCurrentThread(getString(R.string.overlay_ball_secondary_accessibility_closed_toast))
            }
            OverlayBallSecondaryPolicy.AccessibilityAction.ALREADY_OFF ->
                toastCurrentThread(getString(R.string.overlay_ball_secondary_accessibility_disabled_toast))
        }
    }

    private fun toastCurrentThread(message: String) {
        // 点按来自窗口回调，本来就在主线程；仍然走一次 Handler 是为了让本方法在任何
        // 调用点都成立（例如将来从桥的回调里复用），而不是靠「现在只在主线程调」的隐含前提。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        } else {
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** 按当前主球坐标重排二级球；没有展开时是无操作。 */
    private fun layoutSecondaryChoices() {
        if (secondaryViews.isEmpty()) return
        val metrics = resources.displayMetrics
        val size = dp(OverlayBallSecondaryPolicy.BUTTON_SIZE_DP)
        val gap = dp(OverlayBallSecondaryPolicy.GAP_DP)
        val layout = OverlayBallSecondaryPolicy.layout(
            ballX = layoutParams.x,
            ballY = layoutParams.y,
            ballSize = layoutParams.width,
            buttonSize = size,
            count = secondaryViews.size,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            gap = gap,
            reserveTop = overlayReserveTop(),
            reserveBottom = overlayReserveBottom(),
        )
        secondaryViews.forEachIndexed { index, view ->
            val (x, y) = OverlayBallSecondaryPolicy.buttonPosition(
                layout = layout,
                index = index,
                layerX = 0,
                layerY = 0,
                buttonSize = size,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                reserveTop = overlayReserveTop(),
                reserveBottom = overlayReserveBottom(),
            )
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@forEachIndexed
            params.x = x
            params.y = y
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun detachSecondaryChoices() {
        // 倒序摘除：后加的在下层，先摘上层可以避免出现一帧「缺了一个洞」的画面。
        for (view in secondaryViews.asReversed()) {
            runCatching { windowManager.removeView(view) }
        }
        secondaryViews.clear()
    }

    private fun secondaryParams(x: Int, y: Int, size: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 与主球同一组标志：不可获焦，但必须能收触摸。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    /**
     * 二级球要避开的安全区高度。
     *
     * 取「状态栏 / 导航栏」与一个固定余量的较大者：overlay 窗口的坐标是相对整个屏幕的，
     * 而 `FLAG_LAYOUT_IN_SCREEN` 会让它铺到状态栏下面，因此不加余量时贴边的二级球
     * 会压在状态栏或手势条上——看得见、点不准（系统手势区会先吃掉那一下）。
     * 真实 inset 需要 `WindowInsets`，而 Service 侧拿不到可靠的 Activity 窗口，
     * 所以这里用固定余量：宁可多让一点，也不要压到系统手势区。
     */
    private fun overlayReserveTop(): Int = dp(SYSTEM_BAR_RESERVE_DP)

    private fun overlayReserveBottom(): Int = dp(SYSTEM_BAR_RESERVE_DP)

    /**
     * 「回到应用」：把应用带回前台。
     *
     * 之所以要分两种情况，是因为 `MainActivity` 是 `singleTask`：对话界面存活时启动它会
     * 触发 clear-top，把后台的 `HarnessActivity` 连同一次性会话凭据一起销毁
     * （[KeepAliveEntryActivity] 的 KDoc 记录了同一失败模式）。也没有启动标志能绕开：
     * `singleTask` 实例只能是任务栈根，无法在不清理栈上活动的前提下被带到
     * `HarnessActivity` 之上。因此对话存活时退回到与短按相同的转发入口 ——
     * [KeepAliveEntryActivity] 正是为绕开这一点而存在的 —— 把应用带回前台，
     * 销毁只由用户在对话界面里主动发起。
     *
     * 对话不在时，任务栈里没有会被 clear-top 清掉的受害者，所以直接启动管理界面。
     */
    private fun returnToApp() {
        if (!OverlayBallPolicy.canOpenManagementDirectly(
                harnessActivityAlive = AppAuthenticationState.isHarnessAuthenticated(),
            )
        ) {
            openHarness()
            return
        }
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK,
        )
        runCatching { startActivity(intent) }
    }

    /** 「隐藏悬浮球」：直接关闭设置开关并停止服务，不引入额外的「临时隐藏」状态。 */
    private fun hideBall() {
        // 直接构造 store，不依赖 RuntimeHost 是否还持有 controller：
        // controller 与悬浮球服务相互独立，「只开球不开后台保持」的用户划掉最近任务后
        // controller 会被释放，而此时长按菜单隐藏球是最常见的操作之一。
        // 构造 store 无副作用，也不读取任何凭据。
        runCatching { RuntimeStore(applicationContext).setOverlayBallEnabled(false) }
        stopSelf()
    }

    // ── 前台通知 ────────────────────────────────────────────────────────────

    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            }
            true
        } catch (error: Throwable) {
            diagnostics.record(
                DiagnosticLevel.WARN,
                DiagnosticEvent.KEEP_ALIVE,
                mapOf("reason" to "overlay_foreground_failed", "active" to "false"),
            )
            android.util.Log.w("dsh-runtime", "overlay ball foreground failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** 通知只使用固定资源文案，在锁屏与通知栏都不会泄露用户数据。 */
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, KeepAliveEntryActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_ball)
            .setContentTitle(getString(R.string.overlay_ball_notification_title))
            .setContentText(getString(R.string.overlay_ball_notification_text))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_ball_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.overlay_ball_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        @Volatile private var activeService: OverlayBallService? = null

        /** A full-page logout invalidates the shared one-time session immediately. */
        fun closeConversation() {
            activeService?.detachConversation()
        }

        /** Result from the non-exported SAF trampoline; stale picker results are ignored. */
        internal fun deliverFileChooserResult(request: String, uris: List<Uri>) {
            activeService?.deliverFileChooserResult(request, uris)
        }

        private const val CHANNEL_ID = "harness_overlay_ball"
        private const val NOTIFICATION_ID = 0x44534802
        private const val BALL_SIZE_DP = 48
        private const val DEFAULT_MARGIN_DP = 12
        private const val MENU_TEXT_SIZE_SP = 15f

        /**
         * 二级球外观。
         *
         * 图标用 Unicode 字符而不是新 drawable：本仓的 `res/drawable` 不在本轮可改范围，
         * 而这三个动作的语义（回首页 / 关闭 / 禁用）在 Unicode 里都有无歧义的现成字符。
         * 每个按钮同时带 `contentDescription`，因此即便字体缺字（只有豆腐块）也不会失去含义。
         */
        private const val SECONDARY_GLYPH_CONVERSATION = "\u2630" // ☰ 打开 Harness 对话
        private const val SECONDARY_GLYPH_SCREEN = "\u25A3"       // ▣ 打开目标应用副屏
        private const val SECONDARY_GLYPH_RETURN = "\u2302"      // ⌂
        private const val SECONDARY_GLYPH_HIDE = "\u2715"        // ✕
        private const val SECONDARY_GLYPH_CLOSE = "\u2298"       // ⊘ 关闭无障碍（已开启）
        private const val SECONDARY_GLYPH_DISABLED = "\u2013"    // – 无障碍未开启（禁用态）
        private const val SECONDARY_TEXT_SIZE_SP = 18f

        /**
         * overlay 窗口要避开的顶部 / 底部安全区高度（dp）。
         *
         * 24dp 覆盖绝大多数状态栏与手势条；取固定值而不是真实 inset，是因为 Service 侧
         * 拿不到可靠的 Activity 窗口（见调用点的说明）。宁可多让一点。
         */
        private const val SYSTEM_BAR_RESERVE_DP = 24

        /**
         * 小窗的可缩放范围与留白。
         *
         * 上下留出 72dp / 左右留出 24dp 是改动前 `conversationSize` 里的既有边距，
         * 保持不变：它同时保证了「小窗不会铺满屏幕」与「窗口边缘不贴手势区」。
         */
        private const val CONVERSATION_MARGIN_H_DP = 24
        private const val CONVERSATION_MARGIN_V_DP = 72

        /** 缩放手柄的图标：右下角的双斜线是缩放手柄的通用符号。 */
        private const val RESIZE_GLYPH = "\u25E2" // ◢

        /** 小窗尺寸存盘用的键；与球的位置共用 `dsh-overlay-ball` 偏好文件。 */
        private const val OVERLAY_PREFERENCES_FILE = "dsh-overlay-ball"
        private const val KEY_CONVERSATION_WIDTH = "conversation_width"
        private const val KEY_CONVERSATION_HEIGHT = "conversation_height"
        private const val KEY_CONVERSATION_SCREEN_W = "conversation_screen_w"
        private const val KEY_CONVERSATION_SCREEN_H = "conversation_screen_h"

        /** 服务当前是否在运行；仅用于界面显示状态，不参与任何决策。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 启动前台服务。
         *
         * 系统可能因后台启动限制或厂商策略拒绝启动；此时静默降级，
         * 球不显示，应用其余部分不受影响。
         */
        fun start(context: Context) {
            isRunning = true
            val intent = Intent(context, OverlayBallService::class.java)
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Throwable) {
                isRunning = false
            }
        }

        /** 显式停止：由 stopService 触发 [onDestroy]。 */
        fun stop(context: Context) {
            isRunning = false
            try {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, OverlayBallService::class.java),
                )
            } catch (_: Throwable) {
                // 服务未运行时 stopService 本身即为无操作。
            }
        }
    }
}
