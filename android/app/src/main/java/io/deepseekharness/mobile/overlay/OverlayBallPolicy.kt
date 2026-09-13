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
     * 球可用的最大偏移量：屏幕尺寸减球尺寸，屏幕比球还小时取 0。
     *
     * 坐标不得为负 —— 负的 LayoutParams 坐标会让 WindowManager 拒绝并拖垮服务。
     */
    private fun maxOffset(screen: Int, ball: Int): Int = (screen - ball).coerceAtLeast(0)
}
