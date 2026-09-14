package io.deepseekharness.mobile.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球策略的纯逻辑测试。
 *
 * 球的实际绘制、触摸与窗口管理依赖 Android 框架，这里只覆盖可脱离设备验证的决策：
 * 何时显示、拖动时如何约束在屏幕内、松手后吸附到哪一侧。
 * 真机上的拖动手感、吸附动画与菜单弹出属于人工验收项（见 docs/悬浮球与内容分享.md）。
 */
class OverlayBallPolicyTest {
    @Test
    fun showsBallOnlyWhenEnabledAndPermissionGranted() {
        assertTrue(OverlayBallPolicy.shouldShowBall(enabled = true, canDrawOverlays = true))
        // 权限缺失是硬约束：此时 WindowManager.addView 会直接失败抛异常并拖垮服务，
        // 并不是「球画不出来只是体验变差」的问题。
        assertFalse(OverlayBallPolicy.shouldShowBall(enabled = true, canDrawOverlays = false))
        assertFalse(OverlayBallPolicy.shouldShowBall(enabled = false, canDrawOverlays = true))
        assertFalse(OverlayBallPolicy.shouldShowBall(enabled = false, canDrawOverlays = false))
    }

    @Test
    fun reusesBallViewOnlyWhileItStaysAttached() {
        // 视图还在窗口上：直接复用，不重复添加。
        assertTrue(OverlayBallPolicy.canReuseBallView(viewPresent = true, viewAttached = true))
        // 视图引用还在、但窗口已被系统移除（运行期撤销权限）：必须重建，否则球永远挂不上。
        assertFalse(OverlayBallPolicy.canReuseBallView(viewPresent = true, viewAttached = false))
        assertFalse(OverlayBallPolicy.canReuseBallView(viewPresent = false, viewAttached = false))
    }

    @Test
    fun opensManagementDirectlyOnlyWhenNoConversationIsAlive() {
        // 对话不在：直接打开管理界面，任务栈里没有会被 clear-top 清掉的受害者。
        assertTrue(OverlayBallPolicy.canOpenManagementDirectly(harnessActivityAlive = false))
        // 对话存活：启动 singleTask 的 MainActivity 会销毁它并撤销一次性会话凭据，
        // 只能改走转发入口（与悬浮球短按、常驻通知一致）。
        assertFalse(OverlayBallPolicy.canOpenManagementDirectly(harnessActivityAlive = true))
    }

    @Test
    fun clampsPositionInsideScreen() {
        // 正常范围内原样返回。
        assertEquals(100 to 200, OverlayBallPolicy.clampPosition(100, 200, 1080, 1920, 144))
        // 左/上越界收敛到 0。
        assertEquals(0 to 0, OverlayBallPolicy.clampPosition(-50, -80, 1080, 1920, 144))
        // 右/下越界收敛到「屏幕尺寸 - 球尺寸」，球不会有一半在屏幕外。
        assertEquals(936 to 1776, OverlayBallPolicy.clampPosition(2000, 3000, 1080, 1920, 144))
    }

    @Test
    fun clampsToZeroWhenBallLargerThanScreen() {
        // 屏幕比球还小时不能返回负数，否则 LayoutParams 会抛异常。
        assertEquals(0 to 0, OverlayBallPolicy.clampPosition(50, 50, 100, 100, 144))
    }

    @Test
    fun snapsToNearestEdge() {
        // 球心在左半屏 → 吸附左边；右半屏 → 吸附右边。
        assertEquals(0, OverlayBallPolicy.snapToEdge(x = 100, screenWidth = 1080, ballSize = 144))
        assertEquals(936, OverlayBallPolicy.snapToEdge(x = 900, screenWidth = 1080, ballSize = 144))
        // 正中间时判给左边，避免同一位置在不同屏幕宽度下抖动。
        assertEquals(0, OverlayBallPolicy.snapToEdge(x = 468, screenWidth = 1080, ballSize = 144))
    }

    @Test
    fun treatsDragBeyondSlopAsDragNotClick() {
        assertFalse(OverlayBallPolicy.isClick(distanceX = 30f, distanceY = 40f, touchSlop = 12))
        // 阈值内算点击，超过才算拖动。
        assertTrue(OverlayBallPolicy.isClick(distanceX = 6f, distanceY = 6f, touchSlop = 12))
        assertFalse(OverlayBallPolicy.isClick(distanceX = 40f, distanceY = 0f, touchSlop = 12))
    }

    @Test
    fun treatsDistanceExactlyAtSlopAsClick() {
        // 包含性边界：位移恰好等于 touchSlop 仍算点击，超过才算拖动。
        assertTrue(OverlayBallPolicy.isClick(distanceX = 3f, distanceY = 4f, touchSlop = 5))
    }

    @Test
    fun treatsHeldTimeExactlyAtThresholdAsLongPress() {
        // 包含性边界：按住时长恰好等于阈值即算长按。
        assertTrue(
            OverlayBallPolicy.isLongPress(
                distanceX = 0f,
                distanceY = 0f,
                touchSlop = 12,
                heldMillis = 500,
                longPressMillis = 500,
            ),
        )
    }

    @Test
    fun treatsLongPressOnlyWithinSlopAndDuration() {
        // 按住不动超过阈值 → 长按。
        assertTrue(OverlayBallPolicy.isLongPress(distanceX = 2f, distanceY = 2f, touchSlop = 12, heldMillis = 600, longPressMillis = 500))
        // 时间够了但手指移开了 → 是拖动，不是长按。
        assertFalse(OverlayBallPolicy.isLongPress(distanceX = 40f, distanceY = 0f, touchSlop = 12, heldMillis = 600, longPressMillis = 500))
        // 位置没动但时间不够 → 还只是按下。
        assertFalse(OverlayBallPolicy.isLongPress(distanceX = 0f, distanceY = 0f, touchSlop = 12, heldMillis = 200, longPressMillis = 500))
    }

    @Test
    fun placesMenuBesideBallWhenItFits() {
        // 球在左侧、右边放得下 → 贴在球的右侧。
        assertEquals(200 to 300, OverlayBallPolicy.menuPosition(
            ballX = 100, ballY = 300, ballSize = 100,
            menuWidth = 400, menuHeight = 200,
            screenWidth = 1080, screenHeight = 1920,
        ))
        // 球在右侧、右边放不下 → 改贴左侧。
        assertEquals(500 to 300, OverlayBallPolicy.menuPosition(
            ballX = 900, ballY = 300, ballSize = 100,
            menuWidth = 400, menuHeight = 200,
            screenWidth = 1080, screenHeight = 1920,
        ))
    }

    @Test
    fun keepsMenuInsideScreenWhenNeitherSideFits() {
        // 两边都放不下 → 收进屏幕内，不越界。
        val position = OverlayBallPolicy.menuPosition(
            ballX = 500, ballY = 300, ballSize = 100,
            menuWidth = 900, menuHeight = 200,
            screenWidth = 1080, screenHeight = 1920,
        )
        assertEquals(180, position.first)
    }

    @Test
    fun liftsMenuAboveBottomEdge() {
        // 球贴近底部时菜单向上收，不能有一半在屏幕外。
        val position = OverlayBallPolicy.menuPosition(
            ballX = 100, ballY = 1800, ballSize = 100,
            menuWidth = 400, menuHeight = 300,
            screenWidth = 1080, screenHeight = 1920,
        )
        assertEquals(1620, position.second)
    }

    @Test
    fun placesDefaultPositionAtRightEdgeVerticallyCentered() {
        // 1080×1920 屏幕、球 144、边距 30：贴右边、垂直居中。
        assertEquals(906 to 888, OverlayBallPolicy.defaultPosition(1080, 1920, 144, 30))
    }

    @Test
    fun clampsDefaultPositionWhenScreenIsSmall() {
        // 屏幕比球还小时仍返回 0，不能出现负坐标（负的 LayoutParams 会被 WindowManager 拒绝）。
        assertEquals(0 to 0, OverlayBallPolicy.defaultPosition(100, 100, 144, 30))
    }
}
