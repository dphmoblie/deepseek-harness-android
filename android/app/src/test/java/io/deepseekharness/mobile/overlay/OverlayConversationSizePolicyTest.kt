package io.deepseekharness.mobile.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话小窗的尺寸策略（登记册 §5.5／§5.6-C 相关部分）。
 *
 * 这里钉的是「窗口会不会变成用户没法自救的形状」：越界的存盘值、旋转之后的旧尺寸、
 * 拖到屏幕外的尺寸，都必须收敛回一个看得见、点得到的范围。真机上的拖拽手感、
 * 与系统手势/输入法的冲突只能真机验证，见 `docs/悬浮球与内容分享.md`。
 */
class OverlayConversationSizePolicyTest {

    private val availableWidth = 1080
    private val availableHeight = 1920
    private val minWidth = 240
    private val minHeight = 360

    @Test
    fun `合法尺寸原样保留`() {
        assertEquals(600, OverlayConversationSizePolicy.clampWidth(600, availableWidth))
        assertEquals(900, OverlayConversationSizePolicy.clampHeight(900, availableHeight))
    }

    @Test
    fun `超过可用区域九成时被夹到上限`() {
        // 1080 * 0.9 = 972；给一个明显更大的值，防止将来把比例改成 1.0 而没人发现。
        assertEquals(972, OverlayConversationSizePolicy.clampWidth(5000, availableWidth))
        // 1920 * 0.9 = 1728
        assertEquals(1728, OverlayConversationSizePolicy.clampHeight(5000, availableHeight))
    }

    @Test
    fun `小于最小尺寸时被抬到下限`() {
        assertEquals(minWidth, OverlayConversationSizePolicy.clampWidth(10, availableWidth))
        assertEquals(minHeight, OverlayConversationSizePolicy.clampHeight(10, availableHeight))
        // 负值/0（存盘损坏的常见形态）同样收敛到下限而不是原样透传。
        assertEquals(minWidth, OverlayConversationSizePolicy.clampWidth(-1, availableWidth))
    }

    @Test
    fun `屏幕比最小尺寸还小时以下限为准`() {
        // 比例上限算出 180 < 240：此时若取比例值，小窗会小到连标题栏都放不下，
        // 用户只能在「太小没法用」和「超过屏幕」之间二选一。选择是：以下限为准，
        // 允许它超出「九成」这条软约束。
        assertEquals(minWidth, OverlayConversationSizePolicy.clampWidth(200, availableWidth = 200))
        assertEquals(minHeight, OverlayConversationSizePolicy.clampHeight(300, availableHeight = 300))
    }

    @Test
    fun `手柄贴在面板右下角并内缩`() {
        val handleSize = 44
        val inset = 4
        val (x, y) = OverlayConversationSizePolicy.handlePosition(
            panelWidth = 600,
            panelHeight = 800,
            handleSize = handleSize,
            inset = inset,
        )
        assertEquals(600 - handleSize - inset, x)
        assertEquals(800 - handleSize - inset, y)
        // 面板比手柄还小时收敛到 0，不能是负数（负坐标会让 WindowManager 抛异常）。
        val (smallX, smallY) = OverlayConversationSizePolicy.handlePosition(
            panelWidth = 10,
            panelHeight = 10,
            handleSize = handleSize,
            inset = inset,
        )
        assertEquals(0, smallX)
        assertEquals(0, smallY)
    }

    @Test
    fun `手柄命中判定以圆心为基准`() {
        val (x, y) = OverlayConversationSizePolicy.handlePosition(600, 800, 44, 4)
        val centerX = (x + 22).toFloat()
        val centerY = (y + 22).toFloat()
        assertTrue(OverlayConversationSizePolicy.hitsHandle(centerX, centerY, x, y, 44))
        // 圆心外侧 40px（仍在半径 22 + 容差 20 = 42 之内）算命中。
        assertTrue(OverlayConversationSizePolicy.hitsHandle(centerX + 40f, centerY, x, y, 44, tolerance = 20f))
        // 没有容差时同样的点不命中：容差是显式参数，不是隐含魔法。
        assertFalse(OverlayConversationSizePolicy.hitsHandle(centerX + 40f, centerY, x, y, 44))
        // 面板中央绝不算点在手柄上（否则整个面板都没法用）。
        assertFalse(OverlayConversationSizePolicy.hitsHandle(centerX - 200f, centerY - 200f, x, y, 44))
    }

    @Test
    fun `拖动尺寸以按下时的尺寸为基准`() {
        val (width, height) = OverlayConversationSizePolicy.resized(
            startWidth = 600,
            startHeight = 800,
            deltaX = 100,
            deltaY = 100,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
        )
        assertEquals(700, width)
        assertEquals(900, height)
    }

    @Test
    fun `缩到比最小还小时停在下限 放大超过九成时停在上限`() {
        val (minW, minH) = OverlayConversationSizePolicy.resized(
            startWidth = 600,
            startHeight = 800,
            deltaX = -5000,
            deltaY = -5000,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
        )
        assertEquals(minWidth, minW)
        assertEquals(minHeight, minH)

        val (maxW, maxH) = OverlayConversationSizePolicy.resized(
            startWidth = 600,
            startHeight = 800,
            deltaX = 5000,
            deltaY = 5000,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
        )
        assertEquals(972, maxW)
        assertEquals(1728, maxH)
    }

    @Test
    fun `拖动可逆 手指回到原处尺寸就回到原值`() {
        // 先拖到上限之外再拖回来：如果实现是「逐帧以当前尺寸为基准累加」，
        // 夹取结果会被当成用户意图，回程的 5000 会从一个被夹小了的基准算起。
        val clamped = OverlayConversationSizePolicy.resized(
            startWidth = 600,
            startHeight = 800,
            deltaX = 5000,
            deltaY = 5000,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
        )
        assertEquals(972, clamped.first)
        val returned = OverlayConversationSizePolicy.resized(
            startWidth = 600,
            startHeight = 800,
            deltaX = 0,
            deltaY = 0,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
        )
        assertEquals(600, returned.first)
        assertEquals(800, returned.second)
    }

    @Test
    fun `没存过尺寸就用默认值`() {
        assertEquals(
            OverlayConversationSizePolicy.Stored.ABSENT,
            OverlayConversationSizePolicy.storedState(
                storedWidth = null,
                storedHeight = null,
                storedScreenWidth = null,
                storedScreenHeight = null,
                screenWidth = availableWidth,
                screenHeight = availableHeight,
            ),
        )
        // 只写了一半（写过程中被杀）也按「没存过」处理，不能拿半个值去开窗。
        assertEquals(
            OverlayConversationSizePolicy.Stored.ABSENT,
            OverlayConversationSizePolicy.storedState(
                storedWidth = 600,
                storedHeight = null,
                storedScreenWidth = availableWidth,
                storedScreenHeight = availableHeight,
                screenWidth = availableWidth,
                screenHeight = availableHeight,
            ),
        )
        assertEquals(
            OverlayConversationSizePolicy.Stored.ABSENT,
            OverlayConversationSizePolicy.storedState(
                storedWidth = 600,
                storedHeight = -1,
                storedScreenWidth = availableWidth,
                storedScreenHeight = availableHeight,
                screenWidth = availableWidth,
                screenHeight = availableHeight,
            ),
        )
    }

    @Test
    fun `同一块屏幕上的旧尺寸可以复用`() {
        assertEquals(
            OverlayConversationSizePolicy.Stored.USABLE,
            OverlayConversationSizePolicy.storedState(
                storedWidth = 700,
                storedHeight = 900,
                storedScreenWidth = availableWidth,
                storedScreenHeight = availableHeight,
                screenWidth = availableWidth,
                screenHeight = availableHeight,
            ),
        )
    }

    @Test
    fun `屏幕变了就退回默认尺寸`() {
        // 旋转 / 分屏 / 换屏：一份「横屏时定下的宽窗」套到竖屏上会变成怪形状。
        assertEquals(
            OverlayConversationSizePolicy.Stored.STALE,
            OverlayConversationSizePolicy.storedState(
                storedWidth = 700,
                storedHeight = 900,
                storedScreenWidth = availableWidth,
                storedScreenHeight = availableHeight,
                screenWidth = availableHeight,
                screenHeight = availableWidth,
            ),
        )
        // 记不全屏幕尺寸时同样判定为过期，而不是盲目信任存下来的宽高。
        assertEquals(
            OverlayConversationSizePolicy.Stored.STALE,
            OverlayConversationSizePolicy.storedState(
                storedWidth = 700,
                storedHeight = 900,
                storedScreenWidth = null,
                storedScreenHeight = null,
                screenWidth = availableWidth,
                screenHeight = availableHeight,
            ),
        )
    }
}
