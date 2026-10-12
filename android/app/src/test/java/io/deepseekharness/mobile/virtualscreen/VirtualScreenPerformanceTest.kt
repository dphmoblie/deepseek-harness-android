package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 副屏性能读数的口径用例。
 *
 * 这里钉的都是**界面会照着显示的边界**：拿不到读数时给 0（而不是编一个像样的数字）、
 * 时钟回退不给负数、样本为 0 表示「一次都没测到」。
 */
class VirtualScreenPerformanceTest {

    @Test
    fun `目标帧率取档位与刷新率里较小的那个`() {
        // 档位 16 毫秒（约 62.5 FPS）与面板 60 Hz：谁也超不过谁，目标是 60。
        assertEquals(60.0, VirtualScreenPerformance.targetFps(16L, 60f), 0.0)
        // 反过来：省电档 66 毫秒（约 15 FPS）比面板低，目标是档位。
        assertEquals(15.2, VirtualScreenPerformance.targetFps(66L, 60f), 0.0)
    }

    @Test
    fun `只拿到一个上限时就用那一个`() {
        assertEquals(125.0, VirtualScreenPerformance.targetFps(8L, 0f), 0.0)
        assertEquals(120.0, VirtualScreenPerformance.targetFps(0L, 120f), 0.0)
    }

    @Test
    fun `两个上限都拿不到时是零而不是承诺`() {
        assertEquals(0.0, VirtualScreenPerformance.targetFps(0L, 0f), 0.0)
        assertEquals(0.0, VirtualScreenPerformance.targetFps(-1L, -60f), 0.0)
    }

    @Test
    fun `目标帧率只保留一位小数`() {
        // 1000 / 33 ≈ 30.303…
        assertEquals(30.3, VirtualScreenPerformance.targetFps(33L, 0f), 0.0)
    }

    @Test
    fun `画面年龄在没有帧或时钟回退时是零`() {
        assertEquals(0L, VirtualScreenPerformance.frameAgeMillis(1_000L, 0L))
        assertEquals(0L, VirtualScreenPerformance.frameAgeMillis(1_000L, 1_000L))
        assertEquals(0L, VirtualScreenPerformance.frameAgeMillis(1_000L, 1_200L))
    }

    @Test
    fun `画面年龄就是读状态时刻减去采集时刻`() {
        assertEquals(800L, VirtualScreenPerformance.frameAgeMillis(5_000L, 4_200L))
    }

    @Test
    fun `一次都没测过时响应耗时全为零`() {
        val latency = VirtualScreenLatency()
        assertEquals(0, latency.sampleCount)
        assertEquals(0L, latency.lastMillis)
        assertEquals(0L, latency.averageMillis)
    }

    @Test
    fun `响应耗时给出最近一次与平均`() {
        val latency = VirtualScreenLatency()
        latency.record(120L)
        latency.record(80L)
        latency.record(100L)
        assertEquals(3, latency.sampleCount)
        assertEquals(100L, latency.lastMillis)
        assertEquals(100L, latency.averageMillis)
    }

    @Test
    fun `超过容量后只保留最近的那些`() {
        val latency = VirtualScreenLatency(capacity = 3)
        for (value in 1L..5L) latency.record(value)
        assertEquals(3, latency.sampleCount)
        assertEquals(5L, latency.lastMillis)
        // 只剩 3、4、5 三个样本。
        assertEquals(4L, latency.averageMillis)
    }

    @Test
    fun `负样本被丢弃不算一次测量`() {
        val latency = VirtualScreenLatency()
        latency.record(-5L)
        assertEquals(0, latency.sampleCount)
        assertEquals(0L, latency.lastMillis)
    }

    @Test
    fun `清空之后回到未测到`() {
        val latency = VirtualScreenLatency()
        latency.record(90L)
        latency.clear()
        assertEquals(0, latency.sampleCount)
        assertEquals(0L, latency.lastMillis)
        assertEquals(0L, latency.averageMillis)
    }

    @Test
    fun `容量非法直接拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenLatency(capacity = 0) }
    }
}
