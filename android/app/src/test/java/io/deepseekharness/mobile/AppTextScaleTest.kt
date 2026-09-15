package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `AppTextScale` 的判定表。这里断言的是**边界行为**而不是实现细节：
 * 系统给的缩放怎么折算、离谱的值怎么夹紧、不可用值怎么回落。
 */
class AppTextScaleTest {
    @Test
    fun `系统默认缩放到默认百分比`() {
        assertEquals(100, AppTextScale.percentOf(1.0f))
    }

    @Test
    fun `常见的系统字号档位按线性折算`() {
        // Android 的「小/默认/大/最大」大约是 0.85 / 1.0 / 1.15 / 1.3。
        assertEquals(85, AppTextScale.percentOf(0.85f))
        assertEquals(115, AppTextScale.percentOf(1.15f))
        assertEquals(130, AppTextScale.percentOf(1.3f))
    }

    @Test
    fun `超过上限时夹紧而不是无限放大`() {
        // 2.5 与 10 都必须落到同一个上限：放大 3 倍只会把窄屏界面挤坏，
        // 那不是「支持无障碍字体」。
        assertEquals(AppTextScale.MAX_PERCENT, AppTextScale.percentOf(2.5f))
        assertEquals(AppTextScale.MAX_PERCENT, AppTextScale.percentOf(10f))
    }

    @Test
    fun `低于下限时也夹紧`() {
        // 系统字号调到最小时应当跟着变小，但不该小到不可读。
        assertEquals(AppTextScale.MIN_PERCENT, AppTextScale.percentOf(0.5f))
        assertEquals(AppTextScale.MIN_PERCENT, AppTextScale.percentOf(0.01f))
    }

    @Test
    fun `不可用的缩放回落到默认值而不是抛异常或产生荒谬结果`() {
        // 0 与负数如果直接参与运算会得到 0 或负数；NaN 会让 roundToInt 产出 Int.MIN_VALUE。
        // 三者都必须回落到默认大小。
        assertEquals(AppTextScale.DEFAULT_PERCENT, AppTextScale.percentOf(0f))
        assertEquals(AppTextScale.DEFAULT_PERCENT, AppTextScale.percentOf(-1f))
        assertEquals(AppTextScale.DEFAULT_PERCENT, AppTextScale.percentOf(Float.NaN))
        assertEquals(AppTextScale.DEFAULT_PERCENT, AppTextScale.percentOf(Float.POSITIVE_INFINITY))
        assertEquals(AppTextScale.DEFAULT_PERCENT, AppTextScale.percentOf(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `round 到最近的整数百分比`() {
        assertEquals(124, AppTextScale.percentOf(1.244f))
        assertEquals(125, AppTextScale.percentOf(1.25f))
    }
}
