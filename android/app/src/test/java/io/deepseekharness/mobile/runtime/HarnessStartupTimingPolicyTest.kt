package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动等待节奏的用例。
 *
 * 这些数字是「点开之后多久能用」的直接构成，单测测不了真机冷启动，但能钉住三件确定的事：
 * 快速档确实比慢档快、窗口边界只有一处切换、复核窗口只是一次短暂确认而不是固定长等待。
 */
class HarnessStartupTimingPolicyTest {
    @Test
    fun `fast polling covers only the early part of the start`() {
        assertEquals(HarnessStartupTimingPolicy.FAST_POLL_INTERVAL_MS, HarnessStartupTimingPolicy.pollIntervalMillis(0))
        assertEquals(
            HarnessStartupTimingPolicy.FAST_POLL_INTERVAL_MS,
            HarnessStartupTimingPolicy.pollIntervalMillis(HarnessStartupTimingPolicy.FAST_WINDOW_MS - 1),
        )
        // 恰好到达窗口边界就退到慢档：只有一个切换点，好解释也好测。
        assertEquals(
            HarnessStartupTimingPolicy.POLL_INTERVAL_MS,
            HarnessStartupTimingPolicy.pollIntervalMillis(HarnessStartupTimingPolicy.FAST_WINDOW_MS),
        )
        // 启动超时是 120 秒，后半段不应该仍在高频探测。
        assertEquals(HarnessStartupTimingPolicy.POLL_INTERVAL_MS, HarnessStartupTimingPolicy.pollIntervalMillis(120_000))
    }

    @Test
    fun `the early interval is finer than the steady state`() {
        assertTrue(HarnessStartupTimingPolicy.FAST_POLL_INTERVAL_MS < HarnessStartupTimingPolicy.POLL_INTERVAL_MS)
        assertTrue(HarnessStartupTimingPolicy.FAST_WINDOW_MS > 0)
    }

    /**
     * 复核窗口的语义是「等一下再看一眼」，量级应当是一两次轮询；
     * 一旦变成几百毫秒以上的固定等待，它就不再是确认，而是纯延迟。
     */
    @Test
    fun `the stability window stays a brief confirmation`() {
        assertTrue(HarnessStartupTimingPolicy.STABILITY_MS > 0)
        assertTrue(
            HarnessStartupTimingPolicy.STABILITY_MS >= HarnessStartupTimingPolicy.FAST_POLL_INTERVAL_MS,
        )
        assertTrue(
            HarnessStartupTimingPolicy.STABILITY_MS <=
                3 * HarnessStartupTimingPolicy.FAST_POLL_INTERVAL_MS,
        )
    }
}
