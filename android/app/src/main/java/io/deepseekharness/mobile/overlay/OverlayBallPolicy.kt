package io.deepseekharness.mobile.overlay

/**
 * 悬浮球的纯决策逻辑：不依赖 Android API，便于单元测试。
 *
 * 重要限制：悬浮球由前台服务承载，只能提高本应用进程被系统回收的优先级，
 * 不能阻止 Android 或厂商系统在内存压力、电量策略或后台限制下结束进程 ——
 * 本策略不做也无法做保活承诺，球可能随时随进程一起消失。
 */
object OverlayBallPolicy {
    /** 短按判定阈值：手指移动不超过这个距离（相对 touchSlop 的倍数）才算点击。 */
    fun isClick(distanceX: Float, distanceY: Float, touchSlop: Int): Boolean =
        kotlin.math.hypot(distanceX.toDouble(), distanceY.toDouble()) <= touchSlop.toDouble()

    /**
     * 长按判定：必须同时满足「手指基本没动」与「按住时间够长」。
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
        val maxX = (screenWidth - ballSize).coerceAtLeast(0)
        val maxY = (screenHeight - ballSize).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * 松手后吸附到最近的左右边缘，返回吸附后的 x 坐标。
     *
     * 以球心而非左边缘判断，否则宽球会在左半屏被判成右侧。
     * 球心恰好位于屏幕正中时判给左侧，避免同一位置在不同屏幕宽度下抖动。
     */
    fun snapToEdge(x: Int, screenWidth: Int, ballSize: Int): Int {
        val maxX = (screenWidth - ballSize).coerceAtLeast(0)
        val center = x + ballSize / 2
        return if (center * 2 <= screenWidth) 0 else maxX
    }
}
