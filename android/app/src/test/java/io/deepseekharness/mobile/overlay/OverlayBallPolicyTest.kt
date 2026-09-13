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
}
