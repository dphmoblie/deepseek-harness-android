package io.deepseekharness.mobile.runtime

/**
 * 「任务完成」通知的**节流与合并**策略（登记册 §5.5）。
 *
 * 为什么这一条需要单独的节流，而退出/安装/导出三条不需要：后面三种是**异常或一次性操作**，
 * 一个下午最多发生一两次；而「任务完成」是**正常收尾**，一次深度使用可以发生几十轮。
 * 一轮一条通知的结果不是「用户更了解进度」，而是用户直接把 `dsh_tasks` 通道关掉——
 * 那时连「Harness 已停止」也一起收不到了。节流在这里是**保护整条通道可用性**的手段。
 *
 * 规则（时间参数全部是显式常量，见 [MIN_COALESCE_MS] / [MAX_COALESCE_WINDOW_MS]）：
 *  1. **静默期合并**：一个完成事件到达后先等 [MIN_COALESCE_MS]；这段时间内再来的事件
 *     并入同一条，并把静默期顺延。用户把一个任务拆成几次追问时只会响一次。
 *  2. **累计窗口封顶**：[MAX_COALESCE_WINDOW_MS] 是「从第一个事件算起」的硬上限。
 *     没有它，持续不断的完成事件会把静默期无限顺延，通知永远不发——那比刷屏更糟。
 *  3. **最小间隔**：[MIN_INTERVAL_MS] 是两次真正发出的通知之间的下限。触及下限时
 *     **不顺延、不丢弃**，而是把发出时刻推到下限边界（见 [Coalescer.onTimer]）。
 *  4. **前台抑制**：应用在前台时一条都不发。界面自己在显示进度，而且外壳用 5 秒轮询
 *     读 `TaskNotification.turnCompletionSequence` 就能立刻提示「任务已完成」——
 *     通知在这里纯属噪声（设计文档第四节）。
 *
 * 全部是纯函数：不碰 Android API、不读时钟（时间由调用方传入），因此可以在 JVM 单测里穷举。
 * 与 [TurnCompletionPolicy] 的分工：那边管**请求能不能收**，这边管**收下之后什么时候响**。
 */
internal object TurnCompletionNotificationPolicy {

    /** 静默期：这段时间内没有新事件就可以发。 */
    const val MIN_COALESCE_MS = 1_500L

    /** 累计窗口硬上限：从第一个事件算起，再久也必须发。 */
    const val MAX_COALESCE_WINDOW_MS = 10_000L

    /** 两次通知之间的最小间隔。 */
    const val MIN_INTERVAL_MS = 6_000L

    /** 合并计数上限：防止窗口内异常多的完成事件把文案撑成四位数。 */
    const val MAX_BATCH = 20

    /**
     * 为什么这一笔没有发出去。只有这两个取值，便于审计按同一组码排查。
     */
    enum class Hold {
        /** 应用在前台：不发也不累计，界面自己会说。 */
        FOREGROUND,

        /** 攒着（静默期未到 / 最小间隔未到），到点再发。 */
        COALESCED,
    }

    /**
     * 一次事件之后该等多久再发通知。
     *
     * @param nowMs 事件到达时刻（`SystemClock.elapsedRealtime()`）。
     * @param windowStartedAtMs 本批第一个事件的时刻；null 表示这是本批第一个。
     * @param lastPostedAtMs 上一次**真正发出**通知的时刻；null 表示从未发过。
     * @param foreground `AppForeground.isForeground()` 的结果。
     * @return null 表示**不发**（前台抑制）；否则是「再过这么多毫秒发」。
     */
    fun delayUntilNotify(
        nowMs: Long,
        windowStartedAtMs: Long?,
        lastPostedAtMs: Long?,
        foreground: Boolean,
    ): Long? {
        if (foreground) return null
        val started = windowStartedAtMs ?: nowMs
        val quietAt = nowMs + MIN_COALESCE_MS
        // 窗口封顶：即使刚来了新事件，也不能把发出时刻推到第一个事件之后 MAX 以外。
        val dueAt = minOf(quietAt, started + MAX_COALESCE_WINDOW_MS)
        val floorAt = floorAt(lastPostedAtMs)
        return (maxOf(dueAt, floorAt) - nowMs).coerceAtLeast(0L)
    }

    /**
     * 定时器到点后的判定。
     *
     * @return true 表示现在可以发；false 表示被最小间隔挡住，调用方必须按
     *   [rescheduleAfter] 重新排一次定时器。**不丢弃**是有意的：累计的事件已经发生过，
     *   丢掉等于漏报，而用户对漏报的容忍度远低于对延迟的容忍度。
     */
    fun canFlush(nowMs: Long, lastPostedAtMs: Long?): Boolean = nowMs >= floorAt(lastPostedAtMs)

    /** 被最小间隔挡住时，下一次定时器应当排到多久之后。 */
    fun rescheduleAfter(nowMs: Long, lastPostedAtMs: Long?): Long =
        (floorAt(lastPostedAtMs) - nowMs).coerceAtLeast(0L)

    /**
     * 合并后的条数：至少 1（合并窗口从 1 开始计数），至多 [MAX_BATCH]。
     *
     * 上限是**截断而不是拒绝**：真到了 20 条以上，用户需要知道的只是「完成了很多」，
     * 精确数字既拿不准也没有意义。
     */
    fun mergedCount(accumulated: Int): Int = accumulated.coerceIn(1, MAX_BATCH)

    /**
     * 一次通知允许发出的最早时刻：`last + MIN_INTERVAL_MS`。
     *
     * 时钟回拨（重启或系统对时后可能出现）时退回「不发过」的处理：把时间差算成负数会让
     * floor 落在遥远的过去或未来，前者无害，后者会把通知永久卡死——这是本次实现里
     * 唯一一个「宁可多响一次也不能永久静音」的判断点。
     */
    private fun floorAt(lastPostedAtMs: Long?): Long {
        val last = lastPostedAtMs ?: return Long.MIN_VALUE
        return last + MIN_INTERVAL_MS
    }

    /**
     * 节流状态的容器。刻意只有一件事在里面：**这一批攒了几个、从什么时候开始攒**。
     *
     * 所有时间判断仍走上面的纯函数，因此这类可以在单测里直接构造并断言，不需要时钟注入。
     */
    class Coalescer {
        private var windowStartedAtMs: Long? = null
        private var accumulated: Int = 0

        /** 事件到达时登记；返回本批第一个事件的时刻（用于 [delayUntilNotify]）。 */
        @Synchronized
        fun onEvent(nowMs: Long): Long {
            if (windowStartedAtMs == null) windowStartedAtMs = nowMs
            accumulated = mergedCount(accumulated + 1)
            return windowStartedAtMs!!
        }

        /** 当前累计条数（≥1 表示有待发的批次）。 */
        @Synchronized
        fun pending(): Int = accumulated

        /** 发出去之后清空本批。 */
        @Synchronized
        fun clear() {
            windowStartedAtMs = null
            accumulated = 0
        }
    }
}
