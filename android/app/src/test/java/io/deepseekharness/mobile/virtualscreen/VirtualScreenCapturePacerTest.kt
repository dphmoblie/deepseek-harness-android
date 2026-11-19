package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副屏连续操作采集节拍器（[VirtualScreenCapturePacer]）与目标可见性短时缓存
 * （[VirtualScreenTargetVisibilityCache]）的纯 JVM 单元测试。
 */
class VirtualScreenCapturePacerTest {
    @Test
    fun `collects immediately on leading edge after idle instead of waiting a full interval`() {
        val pacer = VirtualScreenCapturePacer()

        // 首次有帧（静止启动后首帧）：前缘立刻采集（0ms），不空等 180ms。
        assertEquals(0L, pacer.onFrameAvailable(nowMillis = 1_000L, configuredIntervalMillis = 180, latestInputAtMillis = 0L))
        pacer.onCollectStarted(1_000L)

        // 20ms 后又来一帧：落在 180ms 窗口内，安排一次 160ms 后缘采集。
        assertEquals(160L, pacer.onFrameAvailable(nowMillis = 1_020L, configuredIntervalMillis = 180, latestInputAtMillis = 0L))
        // 后缘已排期期间的连续帧直接合并（返回 null），不重复排任务。
        assertNull(pacer.onFrameAvailable(nowMillis = 1_050L, configuredIntervalMillis = 180, latestInputAtMillis = 0L))

        // 后缘真正落地后清除排期标记。
        pacer.onCollectStarted(1_180L)
        // 再次静止超过 180ms 后来了新帧：立刻前缘采集。
        assertEquals(0L, pacer.onFrameAvailable(nowMillis = 1_500L, configuredIntervalMillis = 180, latestInputAtMillis = 0L))
    }

    @Test
    fun `interactive burst caps capture interval to 60fps during continuous input and relaxes afterwards`() {
        val pacer = VirtualScreenCapturePacer()

        // 省电档 limited=180ms，刚发生输入（1_000ms），1_005ms 来帧：前缘立即采。
        assertEquals(0L, pacer.onFrameAvailable(nowMillis = 1_005L, configuredIntervalMillis = 180, latestInputAtMillis = 1_000L))
        pacer.onCollectStarted(1_005L)

        // 6ms 后下一帧到达：在突发窗口（800ms）内上限压到 16ms，因此只需等 10ms 而非 174ms。
        assertEquals(10L, pacer.onFrameAvailable(nowMillis = 1_011L, configuredIntervalMillis = 180, latestInputAtMillis = 1_000L))
        pacer.onCollectStarted(1_021L)

        // 90fps/120fps 等本身比 16ms 更快的档位不被反向限慢。
        assertEquals(11L, pacer.activeIntervalMillis(configuredIntervalMillis = 11, nowMillis = 1_050L, latestInputAtMillis = 1_000L))

        // 停止输入超过 800ms 后自动回落到配置的 180ms 省电档。
        assertEquals(180L, pacer.activeIntervalMillis(configuredIntervalMillis = 180, nowMillis = 2_000L, latestInputAtMillis = 1_000L))
    }

    @Test
    fun `target visibility cache reuses fresh confirmation and invalidates on context change or expiry`() {
        val cache = VirtualScreenTargetVisibilityCache(ttlMillis = 1_500L)

        assertFalse(cache.isFresh("s1", 6, "com.demo.app", 1_000L))

        cache.record("s1", 6, "com.demo.app", visible = true, nowMillis = 1_000L)
        assertTrue(cache.isFresh("s1", 6, "com.demo.app", 1_000L))
        assertTrue(cache.isFresh("s1", 6, "com.demo.app", 2_500L))
        // 超出 TTL 失效。
        assertFalse(cache.isFresh("s1", 6, "com.demo.app", 2_501L))

        // 会话号、显示器编号或包名任一变化都不命中缓存。
        assertFalse(cache.isFresh("s2", 6, "com.demo.app", 1_200L))
        assertFalse(cache.isFresh("s1", 7, "com.demo.app", 1_200L))
        assertFalse(cache.isFresh("s1", 6, "com.other.app", 1_200L))

        // 显式记录不可见或调用 invalidate 会立即清空缓存。
        cache.record("s1", 6, "com.demo.app", visible = false, nowMillis = 1_300L)
        assertFalse(cache.isFresh("s1", 6, "com.demo.app", 1_301L))
    }
}
