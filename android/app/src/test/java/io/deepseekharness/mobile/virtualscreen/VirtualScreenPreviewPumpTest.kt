package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VirtualScreenPreviewPump] 的拍子语义：一拍只续排一次、延迟扣掉本拍耗时、慢帧不追赶积压。
 *
 * 用的是假时钟 + 假主线程队列（照 [VirtualScreenPreviewLifecycleTest] 的范式），
 * 所以这些时序断言既不用真机，也不用真的等。
 */
class VirtualScreenPreviewPumpTest {
    /**
     * 假主线程：投递与取消按 Runnable 实例匹配（与 `Handler.removeCallbacks` 同一套语义），
     * 队列里存的必须是控件传进来的那一个 beat 实例，否则取消不掉。
     */
    private class FakeMain {
        var nowMillis = 0L
        val pending = ArrayDeque<Runnable>()
        val delays = mutableListOf<Long>()
        var beats = 0

        /** 一拍的动作；真实实现是 VirtualScreenPreview.tick，这里只数它被跑了几次。 */
        val beat = Runnable { beats++ }

        val pump = VirtualScreenPreviewPump(
            now = { nowMillis },
            postDelayed = { delay, action -> delays.add(delay); pending.addLast(action) },
            removeCallbacks = { action -> pending.remove(action) },
            beat = beat,
        )

        /**
         * 走完一拍：先把排队的那一拍拿出来执行，再按真实 tick 的顺序
         * 「记下本拍起始时刻 → 取帧与上屏花掉 [costMillis] → 落地时续排下一拍」。
         */
        fun runBeat(mode: String, costMillis: Long) {
            pending.removeFirst().run()
            pump.fetchStarted()
            nowMillis += costMillis
            pump.fetchLanded(mode)
        }
    }

    @Test fun `重复 start 不复制调度且首拍与原来的 post 等价`() {
        val main = FakeMain()
        assertTrue("首次 start 投递首拍", main.pump.start())
        assertFalse("已经跑着就不该再排一条拍子", main.pump.start())
        assertFalse(main.pump.start())
        // 首拍延迟 0：Handler 里 post(r) 就是 postDelayed(r, 0)，重挂/首次挂载的行为不变。
        assertEquals(listOf(0L), main.delays)
        assertEquals("队列里只留一拍", 1, main.pending.size)
    }

    @Test fun `三拍的续排延迟等于各拍扣减耗时后的间隔之和`() {
        val main = FakeMain()
        main.pump.start()

        // 30fps 档间隔 33 毫秒：每拍各自扣掉自己的耗时，既不补拍也不多等一个整周期。
        val costs = listOf(5L, 0L, 12L)
        for (cost in costs) main.runBeat("30fps", cost)

        val expected = costs.map { VirtualScreenPolicy.previewPullDelay("30fps", it) }
        assertEquals(listOf(28L, 33L, 21L), expected)
        assertEquals(listOf(0L) + expected, main.delays)
        assertEquals("三拍总延迟 = Σ previewPullDelay", expected.sum(), main.delays.drop(1).sum())
        assertEquals(3, main.beats)
        assertEquals("一拍只续排一次：走完三拍，队列里始终只留一拍", 1, main.pending.size)
    }

    @Test fun `慢帧不追赶积压`() {
        val main = FakeMain()
        main.pump.start()

        // 15fps 档间隔 66 毫秒，这一拍却花掉 200 毫秒：下一拍只按最小 1 毫秒续排，不连发补拍。
        main.runBeat("15fps", 200)
        assertEquals(listOf(0L, 1L), main.delays)
        assertEquals("积压不补拍", 1, main.pending.size)

        // 下一拍恢复正常后立刻回到档位间隔：慢帧只影响它自己那一拍，不留后账。
        main.runBeat("15fps", 0)
        assertEquals(66L, main.delays.last())
        assertEquals(2, main.beats)
    }

    @Test fun `stop 之后队列为空且不再续排`() {
        val main = FakeMain()
        main.pump.start()
        main.pump.fetchStarted()

        main.pump.stop()
        assertTrue("排队中的那一拍必须被取消", main.pending.isEmpty())
        assertEquals("停拍之后本拍落地不许再投递", 0L, main.pump.fetchLanded("30fps"))
        assertEquals(listOf(0L), main.delays)

        // 重新 start（重挂场景）才会重新投递，并且仍然只有一条拍子。
        assertTrue(main.pump.start())
        assertEquals(listOf(0L, 0L), main.delays)
        assertEquals(1, main.pending.size)
    }

    @Test fun `续排延迟不短于 1 毫秒`() {
        val main = FakeMain()
        main.pump.start()
        // 240fps 档间隔 4 毫秒；本拍耗时再大也不能出现 0 或负数延迟（那就是忙等连发）。
        for (cost in listOf(0L, 1L, 4L, 1_000L, 100_000L)) {
            main.runBeat("240fps", cost)
            assertTrue("延迟必须 >= 1 毫秒，实际 ${main.delays.last()}", main.delays.last() >= 1L)
            assertEquals(1, main.pending.size)
        }
        assertEquals(listOf(0L, 4L, 3L, 1L, 1L, 1L), main.delays)
    }
}
