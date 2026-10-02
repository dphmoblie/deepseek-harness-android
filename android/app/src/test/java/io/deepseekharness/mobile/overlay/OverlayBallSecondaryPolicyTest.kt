package io.deepseekharness.mobile.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二级球（长按主球展开的按钮组）的几何与状态机（登记册 §5.5 悬浮球部分）。
 *
 * 这里钉的是两类**不会崩但会很难用**的错误：
 *  - 排布算错：按钮落到屏幕外或压在系统手势区上，用户看得见却点不中；
 *  - 动作错位：「关闭无障碍服务」是一个用户很难自己恢复的系统设置变更，
 *    它和「隐藏悬浮球」的顺序换一位，代价完全不同。
 */
class OverlayBallSecondaryPolicyTest {

    /** 真机上的常见比例：1080×1920，400dpi 下 48dp 的球约 120px，这里取整为便于断言的 100。 */
    private val screenWidth = 1080
    private val screenHeight = 1920
    private val ballSize = 100
    private val buttonSize = 100
    private val gap = 8
    private val reserve = 24

    private fun layout(ballX: Int, ballY: Int, count: Int = 3) = OverlayBallSecondaryPolicy.layout(
        ballX = ballX,
        ballY = ballY,
        ballSize = ballSize,
        buttonSize = buttonSize,
        count = count,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        gap = gap,
        reserveTop = reserve,
        reserveBottom = reserve,
    )

    private fun buttons(ballX: Int, ballY: Int, count: Int = 3): List<Pair<Int, Int>> {
        val current = layout(ballX, ballY, count)
        return (0 until count).map { index ->
            OverlayBallSecondaryPolicy.buttonPosition(
                layout = current,
                index = index,
                layerX = 0,
                layerY = 0,
                buttonSize = buttonSize,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                reserveTop = reserve,
                reserveBottom = reserve,
            )
        }
    }

    @Test
    fun `球贴右边缘时按钮排在左侧`() {
        val ballX = screenWidth - ballSize
        val current = layout(ballX, 800)
        // 右侧只剩 ballSize 的死角，放不下按钮；排到左侧才不会出屏。
        assertEquals(ballX - gap - buttonSize, current.anchorX)
    }

    @Test
    fun `球贴左边缘时按钮排在右侧`() {
        val current = layout(0, 800)
        assertEquals(ballSize + gap, current.anchorX)
    }

    @Test
    fun `默认向下排列 按钮之间保留间距`() {
        val current = layout(500, 800)
        assertEquals(800, current.firstY)
        assertEquals(buttonSize + gap, current.stepY)
        assertTrue(current.stacked)
    }

    @Test
    fun `下方放不下时整组翻到球的上方`() {
        // 让下方连第二个按钮都放不下。
        val ballY = screenHeight - reserve - buttonSize - 10
        val current = layout(500, ballY)
        assertFalse("应当向上排列", current.stacked)
        assertEquals(ballY - gap - OverlayBallSecondaryPolicy.totalHeight(buttonSize, 3, gap), current.firstY)
    }

    @Test
    fun `两侧都放不下时整组仍被夹进安全区`() {
        // 小屏 + 球贴底：向下不够、向上也不够，此时必须由 clampButton 兜住，
        // 不能让按钮停在屏幕外（用户点不到，也没有任何反馈）。
        val smallScreen = 600
        val count = 3
        val current = OverlayBallSecondaryPolicy.layout(
            ballX = smallScreen - ballSize,
            ballY = smallScreen - ballSize,
            ballSize = ballSize,
            buttonSize = buttonSize,
            count = count,
            screenWidth = smallScreen,
            screenHeight = smallScreen,
            gap = gap,
            reserveTop = reserve,
            reserveBottom = reserve,
        )
        for (index in 0 until count) {
            val (x, y) = OverlayBallSecondaryPolicy.buttonPosition(
                layout = current,
                index = index,
                layerX = 0,
                layerY = 0,
                buttonSize = buttonSize,
                screenWidth = smallScreen,
                screenHeight = smallScreen,
                reserveTop = reserve,
                reserveBottom = reserve,
            )
            assertTrue("x 越界：$x", x in 0..(smallScreen - buttonSize))
            assertTrue("y 越界：$y", y >= reserve && y <= smallScreen - reserve - buttonSize)
        }
    }

    @Test
    fun `拖动主球时按钮跟着走且始终在安全区内`() {
        val first = buttons(500, 800)
        val moved = buttons(300, 1200)
        // 主球只移动了 x，整组的横坐标就应当整体跟着左移（间距关系不变）。
        assertEquals(first[0].first - 200, moved[0].first)
        assertEquals(first[0].second + 400, moved[0].second)
        for ((x, y) in moved) {
            assertTrue(x in 0..(screenWidth - buttonSize))
            assertTrue(y in reserve..(screenHeight - reserve - buttonSize))
        }
    }

    @Test
    fun `按钮完全落在安全区边界内`() {
        // 四个极端角各排一次：任何一处的按钮都不许压到状态栏 / 手势条。
        val corners = listOf(
            0 to 0,
            (screenWidth - ballSize) to 0,
            0 to (screenHeight - ballSize),
            (screenWidth - ballSize) to (screenHeight - ballSize),
        )
        for ((ballX, ballY) in corners) {
            for ((x, y) in buttons(ballX, ballY)) {
                assertTrue("角 $ballX,$ballY 的 x 越界：$x", x in 0..(screenWidth - buttonSize))
                assertTrue(
                    "角 $ballX,$ballY 的 y 越界：$y",
                    y >= reserve && y <= screenHeight - reserve - buttonSize,
                )
            }
        }
    }

    @Test
    fun `命中区不足四十 dp 时判定不达标`() {
        assertTrue(OverlayBallSecondaryPolicy.isHitTargetEnough(hitSize = 40 * 3, minTouchSize = 40 * 3))
        assertFalse(OverlayBallSecondaryPolicy.isHitTargetEnough(hitSize = 36 * 3, minTouchSize = 40 * 3))
        // 视觉尺寸小于命中区是设计的一部分：圆点可以小，触摸目标必须够。
        assertTrue(
            OverlayBallSecondaryPolicy.visualSize(
                buttonSize = 40 * 3,
                minTouchSize = 40 * 3,
                visualSizePx = 28 * 3,
            ) <= 40 * 3,
        )
    }

    @Test
    fun `动作顺序是回到应用 隐藏悬浮球 关闭无障碍`() {
        assertEquals(
            listOf(
                OverlayBallSecondaryPolicy.Choice.RETURN_TO_APP,
                OverlayBallSecondaryPolicy.Choice.HIDE_BALL,
                OverlayBallSecondaryPolicy.Choice.DISABLE_ACCESSIBILITY,
            ),
            OverlayBallSecondaryPolicy.CHOICE_ORDER,
        )
    }

    @Test
    fun `展开收起由视图存在性决定`() {
        assertEquals(OverlayBallSecondaryPolicy.Decision.EXPAND, OverlayBallSecondaryPolicy.toggleChoice(false))
        assertEquals(OverlayBallSecondaryPolicy.Decision.COLLAPSE, OverlayBallSecondaryPolicy.toggleChoice(true))
    }

    @Test
    fun `无障碍未开启时不调用 disableSelf 并显示禁用态`() {
        assertEquals(
            OverlayBallSecondaryPolicy.AccessibilityAction.ALREADY_OFF,
            OverlayBallSecondaryPolicy.accessibilityState(serviceConnected = false),
        )
        assertEquals(
            OverlayBallSecondaryPolicy.AccessibilityAction.DISABLE_SELF,
            OverlayBallSecondaryPolicy.accessibilityState(serviceConnected = true),
        )
        assertTrue(OverlayBallSecondaryPolicy.showsDisabledState(serviceConnected = false))
        assertFalse(OverlayBallSecondaryPolicy.showsDisabledState(serviceConnected = true))
    }
}
