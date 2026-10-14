package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度节流：只验证「哪些字节数会被放行」这一件事，不涉及下载与桥接。
 *
 * 用例逐条对应界面上的两种坏结果：一直停在 0%（首笔被吞）、永远差一点（末笔被吞）。
 */
class AppUpdateProgressThrottleTest {
    @Test
    fun `首笔一定放行，重复的字节数丢弃`() {
        val throttle = AppUpdateProgressThrottle(100L * 1024L * 1024L)
        assertTrue(throttle.shouldReport(0L))
        assertFalse(throttle.shouldReport(0L))
    }

    @Test
    fun `未跨过步长不上报，跨过才上报`() {
        val throttle = AppUpdateProgressThrottle(1024L * 1024L * 1024L)
        assertTrue(throttle.shouldReport(0L))
        // 步长取 1% 与 256 KB 里较大的那个：1 GB 的包步长是 10.7 MB 左右。
        assertFalse(throttle.shouldReport(1024L * 1024L))
        assertTrue(throttle.shouldReport(11L * 1024L * 1024L))
        assertFalse(throttle.shouldReport(12L * 1024L * 1024L))
    }

    @Test
    fun `小包用 256 KB 兜底，不会按 1% 碎成上千次`() {
        val throttle = AppUpdateProgressThrottle(4L * 1024L * 1024L)
        assertTrue(throttle.shouldReport(0L))
        assertFalse(throttle.shouldReport(64L * 1024L))
        assertTrue(throttle.shouldReport(256L * 1024L))
    }

    @Test
    fun `末笔一定放行`() {
        val throttle = AppUpdateProgressThrottle(1024L * 1024L * 1024L)
        assertTrue(throttle.shouldReport(0L))
        assertTrue(throttle.shouldReport(1024L * 1024L * 1024L))
    }

    @Test
    fun `总长未知时只放行首笔与重复丢弃`() {
        val throttle = AppUpdateProgressThrottle(0L)
        assertTrue(throttle.shouldReport(0L))
        assertFalse(throttle.shouldReport(0L))
        assertFalse(throttle.shouldReport(4096L))
    }
}
