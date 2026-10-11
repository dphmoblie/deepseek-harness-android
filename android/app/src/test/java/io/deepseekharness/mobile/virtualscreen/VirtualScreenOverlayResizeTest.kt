package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮小窗「可缩放」这一线的纯逻辑单测：只有算术，没有窗口、没有设备。
 *
 * 覆盖两件事：
 * 1. [VirtualScreenWindow.overlaySize] 新增的 `requestedWidthPx` 重载——不给宽度时必须与改造前逐字一致，
 *    给了宽度时要夹进可用矩形，并且**高度永远由比例反算**（否则 `FIT_CENTER` 会补黑边、触摸坐标会失真）；
 * 2. [VirtualScreenWindow.resizedOverlayWidth] 的夹取与可逆性——基准是按下那一刻的宽度，
 *    而不是当前宽度（用当前宽度逐帧累加会把夹取结果当成用户意图，拖回原处却回不到原大小）。
 *
 * 未在此验证的：真机上窗口重挂是否闪屏、手势是否被系统取消、输入法与系统面板的层级 —— 那些只能实测。
 */
class VirtualScreenOverlayResizeTest {

    private val portrait = VirtualScreenSpec.PORTRAIT
    private val screenWidth = 1080
    private val screenHeight = 2400
    private val widthCap = (screenWidth * VirtualScreenWindow.MAX_SCREEN_WIDTH_RATIO).toInt()
    private val heightCap = (screenHeight * VirtualScreenWindow.MAX_SCREEN_HEIGHT_RATIO).toInt()

    // ---------------------------------------------------------------- 指定宽度时的尺寸换算

    @Test
    fun `不给宽度时与旧签名逐字一致`() {
        val legacy = VirtualScreenWindow.overlaySize(portrait, screenWidth, screenHeight, 3f)
        val viaNull = VirtualScreenWindow.overlaySize(portrait, screenWidth, screenHeight, 3f, null)
        assertEquals(legacy.widthPx, viaNull.widthPx)
        assertEquals(legacy.heightPx, viaNull.heightPx)
    }

    @Test
    fun `指定宽度按比例定高且比例不变`() {
        val target = widthCap / 2
        val size = VirtualScreenWindow.overlaySize(portrait, screenWidth, screenHeight, 3f, target)
        assertEquals("宽度就是用户要的那个宽度", target, size.widthPx)
        assertEquals("画面宽高比必须等于副屏比例", portrait.aspectRatio,
            size.contentWidthPx.toDouble() / size.contentHeightPx.toDouble(), 0.01)
        assertTrue("高度不超 84% 屏高", size.heightPx <= heightCap)
    }

    @Test
    fun `指定宽度超过屏宽上限时被夹回`() {
        val size = VirtualScreenWindow.overlaySize(portrait, screenWidth, screenHeight, 3f, screenWidth * 2)
        assertTrue("宽不超 94% 屏宽", size.widthPx <= widthCap)
        assertTrue("高不超 84% 屏高", size.heightPx <= heightCap)
    }

    @Test
    fun `指定宽度会让高度超上限时改由高度收紧宽度`() {
        // 竖屏比例很窄：94% 屏宽（1015）顶出来高度 2236 会超过 84% 屏高（2016），
        // 于是实际宽度必须小于请求值，且高度正好顶满可用高度。
        val requested = widthCap
        val size = VirtualScreenWindow.overlaySize(portrait, screenWidth, screenHeight, 3f, requested)
        assertEquals("高度顶满 84% 屏高", heightCap, size.heightPx)
        assertTrue("宽度必须被收紧到请求值以下：${size.widthPx}", size.widthPx < requested)
        assertEquals("比例必须仍然是副屏比例", portrait.aspectRatio,
            size.widthPx.toDouble() / size.heightPx.toDouble(), 0.01)
    }

    @Test
    fun `零与负数表示跟随可用区域最大`() {
        val legacy = VirtualScreenWindow.overlaySize(portrait, screenWidth, screenHeight, 3f)
        for (value in listOf(0, -1, Int.MIN_VALUE)) {
            val size = VirtualScreenWindow.overlaySize(portrait, screenWidth, screenHeight, 3f, value)
            assertEquals("宽度应为默认最大：$value", legacy.widthPx, size.widthPx)
            assertEquals("高度应为默认最大：$value", legacy.heightPx, size.heightPx)
        }
    }

    // ---------------------------------------------------------------- 拖手柄后的宽度

    @Test
    fun `向右拖变大向左拖变小`() {
        val start = 600
        assertTrue(VirtualScreenWindow.resizedOverlayWidth(start, 120, screenWidth, screenHeight,
            VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, portrait.aspectRatio) > start)
        assertTrue(VirtualScreenWindow.resizedOverlayWidth(start, -120, screenWidth, screenHeight,
            VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, portrait.aspectRatio) < start)
    }

    @Test
    fun `基准是按下那一刻的宽度所以可逆`() {
        val start = 600
        val ratio = portrait.aspectRatio
        val dragged = VirtualScreenWindow.resizedOverlayWidth(start, 300, screenWidth, screenHeight,
            VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, ratio)
        // 拖过头被上限夹住之后，再拖回按下点必须回到原宽度，而不是「从被夹住的值往回退」。
        val overshoot = VirtualScreenWindow.resizedOverlayWidth(start, screenWidth, screenWidth, screenHeight,
            VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, ratio)
        val back = VirtualScreenWindow.resizedOverlayWidth(start, 0, screenWidth, screenHeight,
            VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, ratio)
        assertEquals("拖回来必须回到原宽度", start, back)
        assertTrue("拖过头不超过上限", overshoot <= widthCap)
        assertTrue("拖过头不超过按高度反算的宽度", overshoot <= Math.round(heightCap * ratio).toInt())
        assertTrue("正常拖动应真的变大", dragged > start)
    }

    @Test
    fun `下限夹取且不会小于 1`() {
        val ratio = portrait.aspectRatio
        val tiny = VirtualScreenWindow.resizedOverlayWidth(300, -100000, screenWidth, screenHeight,
            VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, ratio)
        assertEquals("应夹到下限", VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, tiny)
        val zeroMin = VirtualScreenWindow.resizedOverlayWidth(300, -100000, screenWidth, screenHeight, 0, ratio)
        assertTrue("下限为 0 时也必须至少 1 像素", zeroMin >= 1)
    }

    @Test
    fun `上限取屏宽 屏高与硬上限三者最小值`() {
        val square = VirtualScreenSpec(1000, 1000, 320)
        // 宽屏 + 正方形比例：屏宽上限（3760）比「按 84% 屏高反算」（840）宽松，于是由高度那一项决定。
        val byHeight = VirtualScreenWindow.resizedOverlayWidth(1000, 100000, 4000, 1000,
            VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, square.aspectRatio)
        assertEquals("正方形比例下由 84% 屏高反算", (1000 * VirtualScreenWindow.MAX_SCREEN_HEIGHT_RATIO).toInt(), byHeight)
        // 屏幕两项都极大时，由硬上限兜底，避免生成一块没有意义的巨大窗口。
        val huge = VirtualScreenWindow.resizedOverlayWidth(1000, 100000, 100000, 100000,
            VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, square.aspectRatio)
        assertEquals("超大屏时由硬上限兜底", VirtualScreenWindow.MAX_OVERLAY_WIDTH_PX, huge)
    }

    @Test
    fun `小屏上下限冲突时上限优先且不产生 0 尺寸`() {
        val ratio = portrait.aspectRatio
        val min = VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX
        // 80×120 的可用区域（超小屏，或分屏里只剩一条）比用户可操作的下限 160dp 还小：
        // 这时以「不许有一半跑到屏幕外」为准 —— 下限让位于上限，但结果必须仍是正数。
        val value = VirtualScreenWindow.resizedOverlayWidth(50, -50, 80, 120, min, ratio)
        assertTrue("必须仍是正数：$value", value >= 1)
        assertTrue("不能超出小屏的 94% 宽：$value",
            value <= (80 * VirtualScreenWindow.MAX_SCREEN_WIDTH_RATIO).toInt())
        assertTrue("下限必须让位于上限，此时拿不到 160dp：$value", value < min)
    }

    @Test
    fun `比例异常时回退为 1 比 1 而不是抛异常或产生 0`() {
        for (ratio in listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val value = VirtualScreenWindow.resizedOverlayWidth(400, 100, screenWidth, screenHeight,
                VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, ratio)
            assertTrue("必须是正数：$ratio → $value", value >= 1)
            assertTrue("不超过屏宽上限：$ratio → $value", value <= widthCap)
        }
    }

    @Test
    fun `拖动后的宽度再走一次尺寸换算仍然等比且不越界`() {
        val ratio = VirtualScreenSpec.LANDSCAPE.aspectRatio
        val dragged = VirtualScreenWindow.resizedOverlayWidth(500, 600, screenWidth, screenHeight,
            VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX, ratio)
        val size = VirtualScreenWindow.overlaySize(VirtualScreenSpec.LANDSCAPE, screenWidth, screenHeight, 3f, dragged)
        assertTrue("宽不超 94% 屏宽", size.widthPx <= widthCap)
        assertTrue("高不超 84% 屏高", size.heightPx <= heightCap)
        assertEquals("比例必须仍然是副屏比例", ratio, size.widthPx.toDouble() / size.heightPx.toDouble(), 0.01)
    }
}
