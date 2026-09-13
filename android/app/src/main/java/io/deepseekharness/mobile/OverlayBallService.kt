package io.deepseekharness.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.overlay.OverlayBallPolicy
import io.deepseekharness.mobile.overlay.OverlayBallPreferences
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLog
import io.deepseekharness.mobile.runtime.RuntimeStore

/**
 * 悬浮球前台服务。
 *
 * 职责边界：
 *  - 只做一件事：在其他应用上层显示一个可拖动的球，短按回到 Harness 对话；
 *  - 不执行任何 Shell 命令，不连接 Shizuku，不持有或读取任何凭据；
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

    private var menuScrim: View? = null
    private var menuView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartAt = 0L
    private var initialX = 0
    private var initialY = 0
    private var longPressFired = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        // 这里只确认权限：用户开关由调用方负责（设置项写入后才启动本服务），
        // 传 true 表示「走到这一步就说明开关已开」，不是要再核对一次偏好。
        if (!OverlayBallPolicy.shouldShowBall(
                enabled = true,
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
        // 故意不使用 START_STICKY：系统重启本服务时球的位置与权限状态都可能已变，
        // 重新拉起只会留下一个位置错误的球。
        return START_NOT_STICKY
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
    }

    override fun onDestroy() {
        detachMenu()
        detachBall()
        stopForegroundCompat()
        // 服务结束（含进入前台失败、权限被撤销、用户点「隐藏悬浮球」等自停路径）后必须
        // 清掉运行标记：它对外表示「当前是否在运行」，只有 onDestroy 是所有结束路径的
        // 唯一汇合点；不在这里清，界面会把已经结束的服务一直显示成运行中。
        isRunning = false
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

        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_ball, null)
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

        view.setOnTouchListener { _, event -> handleTouch(event, size) }
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
     * 触摸处理：短按回对话、长按弹菜单、拖动移动球。
     *
     * 判定复用 [OverlayBallPolicy]，与单测覆盖的是同一套规则。
     */
    private fun handleTouch(event: MotionEvent, size: Int): Boolean {
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
                    showMenu()
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
                    showMenu()
                    return true
                }
                if (OverlayBallPolicy.isClick(deltaX, deltaY, slop)) {
                    openHarness()
                } else {
                    // 拖动结束：吸附到最近边缘并记住位置。
                    val metrics = resources.displayMetrics
                    layoutParams.x = OverlayBallPolicy.snapToEdge(
                        layoutParams.x, metrics.widthPixels, size,
                    )
                    runCatching { windowManager.updateViewLayout(ballView, layoutParams) }
                    preferences.writePosition(layoutParams.x, layoutParams.y)
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

    // ── 长按菜单 ────────────────────────────────────────────────────────────

    /**
     * 弹出长按菜单。
     *
     * **刻意不用 [`PopupWindow`]**：从 Service 弹 PopupWindow 需要一个有效的 window
     * token 来挂载，而 Service 上下文没有（那要 Activity 的 token），`showAsDropDown`
     * 会抛 `BadTokenException` —— 就算用 try/catch 兜住不崩，菜单也根本不会出现。
     * 球本身就是 WindowManager 上的 overlay 视图，菜单用同一机制添加更可靠，
     * 位置也完全可控。
     *
     * 另外补一层全屏透明遮罩：overlay 菜单带 `FLAG_NOT_FOCUSABLE`，无法感知「点击别处」，
     * 没有遮罩的话菜单会一直挂在屏幕上直到用户点了某一项。
     */
    private fun showMenu() {
        detachMenu()
        if (ballView == null) return

        val scrim = View(this).apply { setOnClickListener { detachMenu() } }
        runCatching { windowManager.addView(scrim, fullScreenOverlayParams()) }
        menuScrim = scrim

        val menu = buildMenu()
        // 位置计算依赖菜单的实际尺寸，而 WRAP_CONTENT 的尺寸要先测量才知道。
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val metrics = resources.displayMetrics
        val (x, y) = OverlayBallPolicy.menuPosition(
            ballX = layoutParams.x,
            ballY = layoutParams.y,
            ballSize = layoutParams.width,
            menuWidth = menu.measuredWidth,
            menuHeight = menu.measuredHeight,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 同样不可获焦：菜单不该抢输入法。能接收触摸（不加 NOT_TOUCHABLE）。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        runCatching { windowManager.addView(menu, params) }
        menuView = menu
    }

    /** 全屏透明遮罩的窗口参数：只负责接住「点击别处」这一下。 */
    private fun fullScreenOverlayParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun buildMenu(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MENU_BACKGROUND_COLOR)
            setPadding(0, MENU_PADDING_DP, 0, MENU_PADDING_DP)
        }
        container.addView(menuItem(R.string.overlay_ball_menu_settings) {
            detachMenu()
            openSettings()
        })
        container.addView(menuItem(R.string.overlay_ball_menu_hide) {
            detachMenu()
            hideBall()
        })
        return container
    }

    private fun menuItem(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = getString(labelRes)
            setTextColor(MENU_TEXT_COLOR)
            textSize = MENU_TEXT_SIZE_SP
            setPadding(MENU_ITEM_PADDING_H_DP, MENU_ITEM_PADDING_V_DP,
                MENU_ITEM_PADDING_H_DP, MENU_ITEM_PADDING_V_DP)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun detachMenu() {
        // 先移菜单再移遮罩：顺序反了会让遮罩短暂盖住菜单，出现一次闪烁。
        menuView?.let { view -> runCatching { windowManager.removeView(view) } }
        menuView = null
        menuScrim?.let { view -> runCatching { windowManager.removeView(view) } }
        menuScrim = null
    }

    /**
     * 「打开设置」：回到管理界面。
     *
     * 对话界面存活时不能直接启动 `MainActivity` —— 它是 `singleTask`，任务栈为
     * `[MainActivity, HarnessActivity]` 时启动会触发 clear-top，把正在运行的对话界面连同
     * 一次性会话凭据一起销毁（[KeepAliveEntryActivity] 的 KDoc 记录了同一失败模式）。
     * 也没有启动标志能绕开：`singleTask` 实例只能是任务栈根，无法在不清理栈上活动的前提下
     * 被带到 `HarnessActivity` 之上。因此对话存活时退回到与短按相同的转发入口，
     * 把应用带回前台，由用户在对话界面里按「返回管理」自行离开 —— 销毁只由用户发起。
     * 没有对话在运行时不存在会被 clear-top 清掉的受害者，仍直接打开管理界面。
     */
    private fun openSettings() {
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
        private const val CHANNEL_ID = "harness_overlay_ball"
        private const val NOTIFICATION_ID = 0x44534802
        private const val BALL_SIZE_DP = 48
        private const val DEFAULT_MARGIN_DP = 12
        private const val MENU_PADDING_DP = 8
        private const val MENU_ITEM_PADDING_H_DP = 20
        private const val MENU_ITEM_PADDING_V_DP = 12
        private const val MENU_TEXT_SIZE_SP = 15f
        private const val MENU_BACKGROUND_COLOR = 0xFF1B2220.toInt()
        private const val MENU_TEXT_COLOR = 0xFFE8F5EF.toInt()

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
