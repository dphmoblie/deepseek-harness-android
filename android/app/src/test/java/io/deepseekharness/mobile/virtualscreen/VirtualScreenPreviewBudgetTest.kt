package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VirtualScreenPreviewBudget] 的判读语义：样本不够不下结论、超预算按拍当刻的档位算、
 * 结论只记账不改档。
 *
 * 用的是纯假数据（喂耗时即可），所以阈值边界（19%/20%、比例 50%、连续 5 拍）都能逐个数到，
 * 不用真机也不用等。
 */
class VirtualScreenPreviewBudgetTest {
    /** 60fps 档的预算就是取帧间隔 16 毫秒（见 [VirtualScreenBudgetPolicy.budgetMillis]）。 */
    private fun record(budget: VirtualScreenPreviewBudget, mode: String, elapsed: Long, times: Int = 1) {
        repeat(times) { budget.record(mode, elapsed) }
    }

    @Test fun `预算就是这一档的取帧间隔`() {
        assertEquals(16L, VirtualScreenBudgetPolicy.budgetMillis("60fps"))
        assertEquals(4L, VirtualScreenBudgetPolicy.budgetMillis("240fps"))
        assertEquals(180L, VirtualScreenBudgetPolicy.budgetMillis("limited"))
        // 档位名不在允许列表里时不许悄悄回落到某个预算：这条契约与续排延迟完全一致
        // （previewPullInterval → frameInterval 会抛），所以记账这一侧也不另造口径。
        assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenBudgetPolicy.budgetMillis("不认识的档位")
        }
    }

    @Test fun `样本不足时不下结论且状态行不加说明`() {
        val budget = VirtualScreenPreviewBudget()
        // 11 拍全部远超预算，但还没到 12 拍的下限：这是「首帧解码 / 建线程」的一次性开销区间。
        record(budget, "60fps", 17L, times = VirtualScreenBudgetPolicy.MIN_SAMPLE_BEATS - 1)

        val snapshot = budget.snapshot()
        assertEquals(VirtualScreenBudgetPolicy.MIN_SAMPLE_BEATS - 1, snapshot.beats)
        assertEquals(11, snapshot.overBudget)
        assertEquals("样本不够时只能是 ok（不知道，不猜）", VirtualScreenBudgetPolicy.OK, snapshot.verdict)
        assertTrue(snapshot.ok)
        assertNull(previewBudgetNote(snapshot))
    }

    @Test fun `比例达到 20 百分比算偏慢_连续 5 拍算跟不上`() {
        val degraded = VirtualScreenPreviewBudget()
        // 25 拍里 5 拍超预算 = 20%（正好卡在阈值上），且刻意隔开：任意连续超预算不超过 1 拍。
        val overIndices = setOf(0, 4, 8, 12, 16)
        repeat(25) { index ->
            degraded.record("60fps", if (index in overIndices) 17L else 5L)
        }
        val degradedSnapshot = degraded.snapshot()
        assertEquals(5, degradedSnapshot.overBudget)
        assertEquals(20, degradedSnapshot.percentOver)
        assertEquals("连续只到 1 拍，这里只能是比例触发的偏慢", VirtualScreenBudgetPolicy.DEGRADED, degradedSnapshot.verdict)
        assertEquals(" · 预览偏慢：最近 25 拍里 5 拍超预算", previewBudgetNote(degradedSnapshot))

        val saturated = VirtualScreenPreviewBudget()
        // 12 拍里最后 5 拍连续超预算：比例只有 41%（没到 50%），但连续跑够 5 拍就是跟不上。
        record(saturated, "60fps", 5L, times = 7)
        record(saturated, "60fps", 17L, times = VirtualScreenBudgetPolicy.SATURATED_RUN)
        val saturatedSnapshot = saturated.snapshot()
        assertEquals(5, saturatedSnapshot.runOverBudget)
        assertEquals(41, saturatedSnapshot.percentOver)
        assertEquals(VirtualScreenBudgetPolicy.SATURATED, saturatedSnapshot.verdict)
        assertEquals(
            " · 预览跟不上档位：最近 12 拍里 5 拍超预算（最慢 17 毫秒）",
            previewBudgetNote(saturatedSnapshot),
        )
    }

    @Test fun `低于阈值只按 ok 处理_比例刚好过半也是跟不上`() {
        val below = VirtualScreenPreviewBudget()
        // 25 拍里 4 拍超预算 = 16%：低于 20% 阈值，且没有连续超预算，不下结论。
        val overIndices = setOf(1, 6, 11, 16)
        repeat(25) { index -> below.record("60fps", if (index in overIndices) 100L else 5L) }
        val belowSnapshot = below.snapshot()
        assertEquals(16, belowSnapshot.percentOver)
        assertEquals(VirtualScreenBudgetPolicy.OK, belowSnapshot.verdict)
        assertNull(previewBudgetNote(belowSnapshot))

        val half = VirtualScreenPreviewBudget()
        // 20 拍里 10 拍超预算 = 50%，一个个隔开：只能由比例触发。
        repeat(20) { index -> half.record("60fps", if (index % 2 == 0) 20L else 5L) }
        val halfSnapshot = half.snapshot()
        assertEquals(10, halfSnapshot.overBudget)
        // 连续计数只算「一直到最近这一拍都在超」：中间缓过来过就不算卡住（索引 19 是奇数拍，没超）。
        assertEquals(0, halfSnapshot.runOverBudget)
        assertEquals(50, halfSnapshot.percentOver)
        assertEquals(VirtualScreenBudgetPolicy.SATURATED, halfSnapshot.verdict)
    }

    @Test fun `按拍当刻的档位记账_换档不被旧预算冤枉`() {
        val budget = VirtualScreenPreviewBudget()
        // 同样 100 毫秒的耗时：limited 档预算 180 毫秒不算超，60fps 档预算 16 毫秒就是超。
        record(budget, "limited", 100L, times = 10)
        var snapshot = budget.snapshot()
        assertEquals("limited 档下 100 毫秒没超预算", 0, snapshot.overBudget)
        assertEquals(VirtualScreenBudgetPolicy.OK, snapshot.verdict)

        record(budget, "60fps", 100L, times = 10)
        snapshot = budget.snapshot()
        assertEquals("切成 60fps 之后这 10 拍才算超预算", 10, snapshot.overBudget)
        assertEquals(100L, snapshot.worstMillis)
        assertEquals(
            "窗口里各拍存各自的预算，前 10 拍不能被 60fps 的预算倒算成超预算",
            VirtualScreenBudgetPolicy.SATURATED,
            snapshot.verdict,
        )
        assertEquals(10, snapshot.runOverBudget)
    }

    @Test fun `环形窗口只算最近若干拍_旧拍被淘汰后不再计入`() {
        val budget = VirtualScreenPreviewBudget(window = 12)
        // 前 28 拍全部超预算，后 12 拍恢复正常：窗口里只剩后 12 拍，旧账不该继续压着结论。
        record(budget, "60fps", 200L, times = 28)
        assertEquals(VirtualScreenBudgetPolicy.SATURATED, budget.snapshot().verdict)

        record(budget, "60fps", 5L, times = 12)
        val snapshot = budget.snapshot()
        assertEquals(12, snapshot.beats)
        assertEquals(0, snapshot.overBudget)
        assertEquals(0, snapshot.runOverBudget)
        assertEquals(5L, snapshot.worstMillis)
        assertEquals(VirtualScreenBudgetPolicy.OK, snapshot.verdict)
        assertNull(previewBudgetNote(snapshot))
    }

    @Test fun `reset 之后窗口清空_重新从一拍开始记`() {
        val budget = VirtualScreenPreviewBudget()
        record(budget, "60fps", 200L, times = 20)
        assertEquals(VirtualScreenBudgetPolicy.SATURATED, budget.snapshot().verdict)

        // 重挂预览会换线程池、首帧要现解码：旧窗口的读数不能代表现在。
        budget.reset()
        assertEquals(PreviewBudgetSnapshot.EMPTY, budget.snapshot())
        assertTrue(budget.snapshot().ok)

        record(budget, "60fps", 5L)
        val snapshot = budget.snapshot()
        assertEquals(1, snapshot.beats)
        assertEquals(0, snapshot.overBudget)
    }

    @Test fun `窗口下限为一拍_负耗时夹到零`() {
        val budget = VirtualScreenPreviewBudget(window = 0)
        record(budget, "60fps", -50L)
        val snapshot = budget.snapshot()
        assertEquals(1, snapshot.beats)
        assertEquals("负耗时按 0 记，不能变成超预算", 0, snapshot.overBudget)
        assertEquals(0L, snapshot.worstMillis)
        assertEquals(VirtualScreenBudgetPolicy.OK, snapshot.verdict)
    }

    @Test fun `最慢中位与 95 分位都取最近邻`() {
        val budget = VirtualScreenPreviewBudget(window = 20)
        // 10、20、…、200 毫秒各一拍：limited 档预算 180 毫秒，所以只有最后两拍超预算。
        for (value in 1..20) budget.record("limited", value * 10L)

        val snapshot = budget.snapshot()
        assertEquals(20, snapshot.beats)
        assertEquals(2, snapshot.overBudget)
        assertEquals(200L, snapshot.worstMillis)
        assertEquals("20 拍的中位取第 10 个（下标 9）= 100 毫秒", 100L, snapshot.medianMillis)
        assertEquals("95 分位取 ceil(20 × 95%) = 第 19 个（下标 18）= 190 毫秒", 190L, snapshot.p95Millis)
        assertEquals(VirtualScreenBudgetPolicy.OK, snapshot.verdict)
    }

    @Test fun `百分比向下取整_分母为零按零算`() {
        assertEquals(66, VirtualScreenBudgetPolicy.percent(2, 3))
        assertEquals(33, VirtualScreenBudgetPolicy.percent(1, 3))
        assertEquals(0, VirtualScreenBudgetPolicy.percent(0, 0))
        assertEquals(0, VirtualScreenBudgetPolicy.percent(3, 0))
        assertEquals(0, VirtualScreenBudgetPolicy.percent(-1, 5))
        assertEquals(100, VirtualScreenBudgetPolicy.percent(5, 5))
    }

    @Test fun `空快照是还不知道而不是很快`() {
        val empty = PreviewBudgetSnapshot.EMPTY
        assertEquals(0, empty.beats)
        assertTrue("没有样本时 ok 为真，但读数是 0", empty.ok)
        assertEquals(0, empty.percentOver)
        assertNull(previewBudgetNote(empty))

        // 真记账时「OK」必须只来自「样本不足」或「确实没超」：连续超预算要能被判成不 ok。
        val budget = VirtualScreenPreviewBudget()
        record(budget, "60fps", 200L, times = VirtualScreenBudgetPolicy.MIN_SAMPLE_BEATS)
        assertFalse(budget.snapshot().ok)
    }
}
