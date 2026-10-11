package io.deepseekharness.mobile.virtualscreen

/**
 * 副屏预览的性能预算（纯 Kotlin：不读时钟、不持帧、不碰 Handler，假时钟喂数即可逐拍覆盖）。
 *
 * 为什么要有它：取帧间隔一直跟着档位走（见 [VirtualScreenPolicy.previewPullDelay]），但**「跟不跟得上」
 * 从来没被记下来**——档位写 60fps、实测只有 22fps 时，界面上只有一行帧率读数，看不出是设备慢、
 * 还是这一档根本吃不下。这里把每一拍的耗时与「这一档允许的预算」逐拍比一次，给出可判读的结论。
 *
 * 边界：
 * - **只记账，不降档**。自动降档会替用户改设置，而且在真机上「慢」的原因不止一种（渲染慢、
 *   目标应用不刷新、副屏后端限流），在这里替用户决定会掩盖真正的原因。
 * - **按拍当刻的档位记账**：窗口里每一拍各存自己的预算，中途换档不会被旧档位的预算冤枉。
 * - 预算就是这一档的取帧间隔（[VirtualScreenPolicy.previewPullInterval]）——一拍耗时超过间隔，
 *   这一档的帧率就达不到名义值，这正是「超预算」的定义。
 */
internal object VirtualScreenBudgetPolicy {
    const val OK = "ok"
    const val DEGRADED = "degraded"
    const val SATURATED = "saturated"

    /** 滑动窗口的拍数：60fps 下约 1 秒，足够看出持续偏慢，又不至于把一次偶发卡顿记很久。 */
    const val WINDOW_BEATS = 60

    /** 少于这么多拍不下结论：刚起预览的头几拍必然包含建线程、首帧解码等一次性开销。 */
    const val MIN_SAMPLE_BEATS = 12

    /** 窗口内超预算比例达到这个百分比算 [DEGRADED]。 */
    const val DEGRADED_PERCENT = 20

    /** 窗口内超预算比例达到这个百分比算 [SATURATED]。 */
    const val SATURATED_PERCENT = 50

    /** 连续这么多拍超预算也算 [SATURATED]：比例还没上来，但已经卡住了。 */
    const val SATURATED_RUN = 5

    /** 这一档每拍允许花掉的时间（毫秒）。档位名不在允许列表里时抛异常，与续排延迟同一套契约。 */
    fun budgetMillis(mode: String): Long = VirtualScreenPolicy.previewPullInterval(mode).toLong()

    /** 百分比取整（向下），用于判读阈值与展示，避免各处各算一份。 */
    fun percent(part: Int, total: Int): Int =
        if (total <= 0) 0 else (part.coerceAtLeast(0).toLong() * 100L / total).toInt()

    /**
     * 结论：样本不够就是 [OK]（不知道，不猜）；连续超预算或过半超预算都是 [SATURATED]；
     * 达到 [DEGRADED_PERCENT] 是 [DEGRADED]。
     */
    fun verdict(beats: Int, overBudget: Int, runOverBudget: Int): String = when {
        beats < MIN_SAMPLE_BEATS -> OK
        runOverBudget >= SATURATED_RUN -> SATURATED
        percent(overBudget, beats) >= SATURATED_PERCENT -> SATURATED
        percent(overBudget, beats) >= DEGRADED_PERCENT -> DEGRADED
        else -> OK
    }
}

/**
 * 一次预算快照。[overBudget] / [runOverBudget] 是记账得来的，「最慢 / 中位 / 95 分位」只在展示时用。
 *
 * [medianMillis] 与 [p95Millis] 是**最近邻取整**：拍数有限，取整数名比插值更好解释。
 */
internal data class PreviewBudgetSnapshot(
    val beats: Int,
    val overBudget: Int,
    val runOverBudget: Int,
    val worstMillis: Long,
    val medianMillis: Long,
    val p95Millis: Long,
    val verdict: String,
) {
    val ok: Boolean get() = verdict == VirtualScreenBudgetPolicy.OK

    /** 超预算占比（向下取整百分比）。 */
    val percentOver: Int get() = VirtualScreenBudgetPolicy.percent(overBudget, beats)

    companion object {
        /** 一拍都没记过时的快照：不是「很快」，是「还不知道」。 */
        val EMPTY = PreviewBudgetSnapshot(
            beats = 0,
            overBudget = 0,
            runOverBudget = 0,
            worstMillis = 0L,
            medianMillis = 0L,
            p95Millis = 0L,
            verdict = VirtualScreenBudgetPolicy.OK,
        )
    }
}

/**
 * 副屏预览的预算记账器：`record` 一拍、`snapshot` 看结论、`reset` 清空。
 *
 * 环形缓冲按拍数固定（[VirtualScreenBudgetPolicy.WINDOW_BEATS]），运行期间不分配新数组；
 * 每一拍各存自己的预算与耗时，所以中途换档不会用旧档位的预算评判新档位的拍。
 */
internal class VirtualScreenPreviewBudget(
    window: Int = VirtualScreenBudgetPolicy.WINDOW_BEATS,
) {
    private val size = window.coerceAtLeast(1)
    private val elapsed = LongArray(size)
    private val budget = LongArray(size)
    private var count = 0
    private var cursor = 0
    private var runOverBudget = 0

    /** 记一拍：[elapsedMillis] 是这一拍从开始到画面落到主线程的耗时，[mode] 是当刻档位。 */
    fun record(mode: String, elapsedMillis: Long): PreviewBudgetSnapshot {
        val spent = elapsedMillis.coerceAtLeast(0L)
        val allowed = VirtualScreenBudgetPolicy.budgetMillis(mode).coerceAtLeast(1L)
        elapsed[cursor] = spent
        budget[cursor] = allowed
        cursor = (cursor + 1) % size
        if (count < size) count += 1
        runOverBudget = if (spent > allowed) runOverBudget + 1 else 0
        return snapshot()
    }

    /** 清空：预览重挂（窗口换了、线程池重开）之后，旧窗口的读数不再代表现在。 */
    fun reset() {
        count = 0
        cursor = 0
        runOverBudget = 0
        elapsed.fill(0L)
        budget.fill(0L)
    }

    /** 当前窗口的结论；没有样本时返回 [PreviewBudgetSnapshot.EMPTY]。 */
    fun snapshot(): PreviewBudgetSnapshot {
        if (count == 0) return PreviewBudgetSnapshot.EMPTY
        val samples = LongArray(count)
        var over = 0
        var worst = 0L
        for (index in 0 until count) {
            val spent = elapsed[index]
            samples[index] = spent
            if (spent > budget[index]) over += 1
            if (spent > worst) worst = spent
        }
        samples.sort()
        return PreviewBudgetSnapshot(
            beats = count,
            overBudget = over,
            runOverBudget = runOverBudget,
            worstMillis = worst,
            medianMillis = samples[(count - 1) / 2],
            p95Millis = samples[percentileIndex(count, 95)],
            verdict = VirtualScreenBudgetPolicy.verdict(count, over, runOverBudget),
        )
    }

    private fun percentileIndex(total: Int, percentile: Int): Int {
        // 最近邻：第 ceil(total × p / 100) 个（1 起）样本的下标，越界夹到边界。
        val rank = (total.toLong() * percentile + 99L) / 100L
        return (rank - 1L).coerceIn(0L, (total - 1).toLong()).toInt()
    }
}

/**
 * 状态行上的补充说明：只有读得出来才说话（[PreviewBudgetSnapshot.ok] 或样本不足时返回 null）。
 *
 * 文案刻意写「超预算」而不是「丢帧」：这里量的是**取帧与投递耗时**，慢的原因可能是设备、
 * 目标应用不刷新或者副屏后端限流，一口咬定丢帧会把人引到错误的方向。
 */
internal fun previewBudgetNote(snapshot: PreviewBudgetSnapshot): String? = when {
    snapshot.beats < VirtualScreenBudgetPolicy.MIN_SAMPLE_BEATS -> null
    snapshot.verdict == VirtualScreenBudgetPolicy.SATURATED ->
        " · 预览跟不上档位：最近 ${snapshot.beats} 拍里 ${snapshot.overBudget} 拍超预算（最慢 ${snapshot.worstMillis} 毫秒）"
    snapshot.verdict == VirtualScreenBudgetPolicy.DEGRADED ->
        " · 预览偏慢：最近 ${snapshot.beats} 拍里 ${snapshot.overBudget} 拍超预算"
    else -> null
}
