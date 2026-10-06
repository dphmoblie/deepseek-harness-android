package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮小窗拖动数学（[VirtualScreenWindow] 的拖动部分）单测。
 *
 * 拖动是在「只显示画面」的小窗上做的，手指落点同时也是副屏的触摸输入，所以下面的规则直接决定
 * 手感与可用性，出错在真机上表现为「副屏里滑不动」或「小窗跑出屏幕」：
 * 1. **先长按再拖**：按住不动满 [VirtualScreenWindow.LONG_PRESS_DRAG_MILLIS] 才改判成拖窗，
 *    这之前的移动一律透传给副屏，所以副屏里的滚动、翻页不会被吞掉；
 * 2. 拖动后的位置一定夹在可用区域内 —— 左右不出屏、上不盖状态栏、下不盖导航栏。
 *
 * 判定与夹取都是纯函数，时间由调用方（视图里的 `clock`）以毫秒喂进来，所以这里不需要 Android 时钟。
 */
class VirtualScreenDragTest {
    @Test
    fun `按住不到长按门槛不算拖动`() {
        assertFalse(VirtualScreenWindow.dragArmed(0L, 0f, 0f, 8f))
        // 门槛前 1ms：长按差一点就不能改判，否则「按一下就移动」的老问题会回来。
        assertFalse(VirtualScreenWindow.dragArmed(349L, 0f, 0f, 8f))
    }

    @Test
    fun `按满长按门槛且手没动才算拖动`() {
        assertTrue(VirtualScreenWindow.dragArmed(350L, 0f, 0f, 8f))
        // 按更久当然也算；手抖落在静置容差内不影响判定。
        assertTrue(VirtualScreenWindow.dragArmed(1200L, 3f, 4f, 5f))
    }

    @Test
    fun `长按期间手动了就不算拖动`() {
        // 慢速滚动也会按满 350ms：位移超过静置容差就说明用户是在操作副屏，窗口不能被抢走。
        assertFalse(VirtualScreenWindow.dragArmed(1000L, 40f, 0f, 8f))
        // 正好等于容差算「没动」（判定用的是「不超容差」）——这是行为边界，写清楚免得日后被误改。
        assertTrue(VirtualScreenWindow.dragArmed(350L, 8f, 0f, 8f))
    }

    @Test
    fun `静置容差按直线距离判定`() {
        // 单轴比较会让 45 度方向变得迟钝；直线距离保证任何方向手感一致。
        assertTrue(VirtualScreenWindow.dragArmed(350L, 5f, 5f, 8f))
        assertFalse(VirtualScreenWindow.dragArmed(350L, 6f, 6f, 8f))
    }

    @Test
    fun `非有限位移或非法静置容差不算拖动`() {
        assertFalse(VirtualScreenWindow.dragArmed(350L, Float.NaN, 0f, 8f))
        assertFalse(VirtualScreenWindow.dragArmed(350L, 0f, Float.POSITIVE_INFINITY, 8f))
        assertFalse(VirtualScreenWindow.dragArmed(350L, 0f, 0f, 0f))
        assertFalse(VirtualScreenWindow.dragArmed(350L, 0f, 0f, -4f))
    }

    @Test
    fun `门槛为零时不要求长按但仍要求手没动`() {
        // 门槛传 0/负数表示「不要求长按」，判定退化成只看静置：将来若要改回「直接拖」，
        // 复用这条纯函数即可，不用另写一套。
        assertTrue(VirtualScreenWindow.dragArmed(0L, 0f, 0f, 8f, thresholdMillis = 0L))
        assertTrue(VirtualScreenWindow.dragArmed(0L, 0f, 0f, 8f, thresholdMillis = -5L))
        assertFalse(VirtualScreenWindow.dragArmed(0L, 100f, 0f, 8f, thresholdMillis = 0L))
    }

    @Test
    fun `界内位移直接相加`() {
        val (x, y) = VirtualScreenWindow.dragPosition(
            startX = 100, startY = 200, deltaX = 30, deltaY = 40,
            screenWidthPx = 1080, screenHeightPx = 2340,
            topInsetPx = 0, bottomInsetPx = 0, viewWidthPx = 400, viewHeightPx = 600,
        )
        assertEquals(130, x)
        assertEquals(240, y)
    }

    @Test
    fun `左边不出屏`() {
        val (x, _) = VirtualScreenWindow.dragPosition(
            startX = 10, startY = 200, deltaX = -500, deltaY = 0,
            screenWidthPx = 1080, screenHeightPx = 2340,
            topInsetPx = 0, bottomInsetPx = 0, viewWidthPx = 400, viewHeightPx = 600,
        )
        assertEquals(0, x)
    }

    @Test
    fun `右边不出屏`() {
        val (x, _) = VirtualScreenWindow.dragPosition(
            startX = 600, startY = 200, deltaX = 500, deltaY = 0,
            screenWidthPx = 1080, screenHeightPx = 2340,
            topInsetPx = 0, bottomInsetPx = 0, viewWidthPx = 400, viewHeightPx = 600,
        )
        assertEquals(1080 - 400, x)
    }

    @Test
    fun `上边不盖状态栏`() {
        val (_, y) = VirtualScreenWindow.dragPosition(
            startX = 100, startY = 60, deltaX = 0, deltaY = -200,
            screenWidthPx = 1080, screenHeightPx = 2340,
            topInsetPx = 50, bottomInsetPx = 40, viewWidthPx = 400, viewHeightPx = 600,
        )
        assertEquals(50, y)
    }

    @Test
    fun `下边不盖导航栏`() {
        val (_, y) = VirtualScreenWindow.dragPosition(
            startX = 100, startY = 1400, deltaX = 0, deltaY = 800,
            screenWidthPx = 1080, screenHeightPx = 2340,
            topInsetPx = 50, bottomInsetPx = 40, viewWidthPx = 400, viewHeightPx = 600,
        )
        assertEquals(2340 - 40 - 600, y)
    }

    @Test
    fun `可用区域比小窗还小时贴左上角`() {
        // 小窗比屏幕还大（换屏幕/旋转后窗口参数没刷新）时不能算出负坐标，否则小窗会跑到看不见的地方。
        val (x, y) = VirtualScreenWindow.dragPosition(
            startX = 10, startY = 10, deltaX = 30, deltaY = 30,
            screenWidthPx = 300, screenHeightPx = 400,
            topInsetPx = 20, bottomInsetPx = 20, viewWidthPx = 500, viewHeightPx = 500,
        )
        assertEquals(0, x)
        assertEquals(20, y)
    }

    @Test
    fun `界外的起点会被拉回界内`() {
        val (x, y) = VirtualScreenWindow.dragPosition(
            startX = 5000, startY = -300, deltaX = 0, deltaY = 0,
            screenWidthPx = 1080, screenHeightPx = 2340,
            topInsetPx = 50, bottomInsetPx = 40, viewWidthPx = 400, viewHeightPx = 600,
        )
        assertEquals(1080 - 400, x)
        assertEquals(50, y)
    }
}
