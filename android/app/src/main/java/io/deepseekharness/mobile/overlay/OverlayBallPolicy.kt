package io.deepseekharness.mobile.overlay

import kotlin.math.hypot

/**
 * 悬浮球的纯决策逻辑：不依赖 Android API，便于单元测试。
 *
 * 重要限制：悬浮球由前台服务承载，只能提高本应用进程被系统回收的优先级，
 * 不能阻止 Android 或厂商系统在内存压力、电量策略或后台限制下结束进程 ——
 * 本策略不做也无法做保活承诺，球可能随时随进程一起消失。
 */
object OverlayBallPolicy {
    /** 没有存过位置时，球的初始垂直位置比例（屏幕垂直居中）。 */
    const val DEFAULT_VERTICAL_RATIO = 0.5f

    /**
     * 短按判定：手指位移不超过 touchSlop（像素）时算点击（含恰好等于）。
     *
     * touchSlop 本身就是距离阈值（通常传 ViewConfiguration 的触摸阈值），无需再乘倍数。
     * 这里只比较位移、不比较时长：调用方必须先排除长按，
     * 否则按住不动 10 秒也会返回 true。
     */
    fun isClick(distanceX: Float, distanceY: Float, touchSlop: Int): Boolean =
        hypot(distanceX.toDouble(), distanceY.toDouble()) <= touchSlop.toDouble()

    /**
     * 长按判定：必须同时满足「手指基本没动」与「按住时间够长」（时长含恰好等于）。
     *
     * 只用时间判定会在用户按住拖动时误弹菜单，因此移动距离同样参与判断。
     */
    fun isLongPress(
        distanceX: Float,
        distanceY: Float,
        touchSlop: Int,
        heldMillis: Long,
        longPressMillis: Long,
    ): Boolean = isClick(distanceX, distanceY, touchSlop) && heldMillis >= longPressMillis

    /** 是否应当显示悬浮球：必须同时满足「用户开启」与「系统已授予悬浮窗权限」。 */
    fun shouldShowBall(enabled: Boolean, canDrawOverlays: Boolean): Boolean =
        enabled && canDrawOverlays

    /** 诊断里的 `reason`：权限明确被撤销。 */
    const val REASON_OVERLAY_DENIED = "overlay_denied"

    /** 诊断里的 `reason`：这一次没读到权限（不是撤销）。 */
    const val REASON_OVERLAY_UNREADABLE = "overlay_permission_unreadable"

    /**
     * 悬浮窗权限的**三态**。
     *
     * `Settings.canDrawOverlays` 要跨进程查系统，个别 ROM 上会直接抛
     * `SecurityException` / `DeadObjectException`。读不到权限既不能当成「有」，也不能当成「没有」：
     * 按「没有」处理，一次 binder 抖动就会让服务按「权限被撤销」收尾，
     * 用户看到的是「悬浮球自己没了」，而真实原因只是这一次没读到。
     */
    enum class OverlayPermissionState { GRANTED, REVOKED, UNREADABLE }

    /** 把「读到的值」映射成三态：`null` 表示这次没读出来。 */
    fun overlayPermissionState(grantsOverlay: Boolean?): OverlayPermissionState = when (grantsOverlay) {
        true -> OverlayPermissionState.GRANTED
        false -> OverlayPermissionState.REVOKED
        null -> OverlayPermissionState.UNREADABLE
    }

    /** 只有明确读到「未授予」才算被撤销；读不到不算。 */
    fun permissionRevoked(state: OverlayPermissionState): Boolean =
        state == OverlayPermissionState.REVOKED

    /**
     * 是否应当尝试挂球。
     *
     * 与 [shouldShowBall] 的差别只在「读不到权限」这一态：布尔形式要求拿到 `true` 才显示，
     * 而这里把读不到当成「暂时还不知道」并继续尝试——真没权限时 `addView` 会自己抛错，
     * 由挂载失败路径按实际情况收尾，比先替系统下结论更诚实。
     */
    fun shouldAttemptBall(enabled: Boolean, state: OverlayPermissionState): Boolean =
        enabled && !permissionRevoked(state)

    /** 需要记进诊断的 `reason`；`null` 表示这次权限正常，不必记告警。 */
    fun permissionWarningReason(state: OverlayPermissionState): String? = when (state) {
        OverlayPermissionState.GRANTED -> null
        OverlayPermissionState.REVOKED -> REASON_OVERLAY_DENIED
        OverlayPermissionState.UNREADABLE -> REASON_OVERLAY_UNREADABLE
    }

    /**
     * 已存在的球视图能否直接复用。
     *
     * 只判「视图引用非空」是不够的：窗口可能已经被系统移除（例如运行期撤销悬浮窗权限），
     * 此时必须重新挂载，否则之后每一次同步都会误判成「球已经在显示」而不再挂载 ——
     * 连重新保存设置都救不回来。
     */
    fun canReuseBallView(viewPresent: Boolean, viewAttached: Boolean): Boolean =
        viewPresent && viewAttached

    /**
     * 「回到应用」能否直接启动管理界面。
     *
     * 对话界面（`HarnessActivity`）存活时不能：此时应先回到已有对话页。
     * 对话不在时可以直接打开管理界面。
     */
    fun canOpenManagementDirectly(harnessActivityAlive: Boolean): Boolean = !harnessActivityAlive

    /**
     * 把球的左上角坐标约束在屏幕可视范围内。
     *
     * 屏幕比球还小时收敛到 0，而不是返回负数 —— 负的 LayoutParams 坐标会让
     * WindowManager 抛异常并终结服务。
     */
    fun clampPosition(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        ballSize: Int,
    ): Pair<Int, Int> {
        val maxX = maxOffset(screenWidth, ballSize)
        val maxY = maxOffset(screenHeight, ballSize)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * 松手后吸附到最近的左右边缘，返回吸附后的 x 坐标。
     *
     * 以球心而非左边缘判断：只按左边缘判定时，球大部分已移入右半屏、
     * 但左边缘仍留在左半屏，会被误判吸附到左侧。
     * 球心恰好落在屏幕正中（平局）时判给左侧，避免正中位置左右闪烁。
     */
    fun snapToEdge(x: Int, screenWidth: Int, ballSize: Int): Int {
        val maxX = maxOffset(screenWidth, ballSize)
        val center = x + ballSize / 2
        return if (center * 2 <= screenWidth) 0 else maxX
    }

    /**
     * 计算长按菜单的左上角坐标。
     *
     * 球在左半屏时菜单贴在球的右侧，右边放不下就试左边，两边都放不下则收进屏幕内；
     * 垂直方向同理，避免菜单有一半落在屏幕外。
     */
    fun menuPosition(
        ballX: Int,
        ballY: Int,
        ballSize: Int,
        menuWidth: Int,
        menuHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> {
        val right = ballX + ballSize
        val left = ballX - menuWidth
        val x = when {
            right + menuWidth <= screenWidth -> right
            left >= 0 -> left
            else -> (screenWidth - menuWidth).coerceAtLeast(0)
        }
        val y = if (ballY + menuHeight <= screenHeight) {
            ballY
        } else {
            (screenHeight - menuHeight).coerceAtLeast(0)
        }
        return x to y
    }

    /**
     * 没存过位置时的初始落点：贴右边缘、垂直居中。
     *
     * 复用 [clampPosition] 保证屏幕比球还小时也不会返回负坐标。
     */
    fun defaultPosition(
        screenWidth: Int,
        screenHeight: Int,
        ballSize: Int,
        marginPx: Int,
    ): Pair<Int, Int> = clampPosition(
        screenWidth - ballSize - marginPx,
        ((screenHeight - ballSize) * DEFAULT_VERTICAL_RATIO).toInt(),
        screenWidth,
        screenHeight,
        ballSize,
    )

    /**
     * 球可用的最大偏移量：屏幕尺寸减球尺寸，屏幕比球还小时取 0。
     *
     * 坐标不得为负 —— 负的 LayoutParams 坐标会让 WindowManager 拒绝并拖垮服务。
     */
    private fun maxOffset(screen: Int, ball: Int): Int = (screen - ball).coerceAtLeast(0)
}
