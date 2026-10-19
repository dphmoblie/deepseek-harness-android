package io.deepseekharness.mobile.runtime

/**
 * Harness 启动等待的时间参数与轮询节奏。
 *
 * 抽成策略对象的原因：这几个值决定「点开之后多久能用」，但它们既不适合散落在
 * `RuntimeSupervisor` 的私有 companion 里（单测摸不到，还容易和「启动超时」这类完全不同的量
 * 混在一起），也不该为了改一个常数就去动启动状态机。这里只放两件事，语义都写清楚：
 *
 * 1. [STABILITY_MS]：第一次看到「401 + 正确 realm」之后，再等一小段时间复查进程是否还活着。
 *    它的语义是**确认服务没在拿到就绪信号的瞬间挂掉**，不是压测、也不是等一个固定冷启动。
 *    原值 600ms 是纯等待：真正决定启动耗时的是 dsh 打印鉴权入口的时刻，这 600ms 只是把用户
 *    能点开界面的时间往后推。降到 200ms 仍覆盖单核设备上的进程调度抖动；更晚的退出由
 *    `RuntimeSupervisor.watchHarnessProcess` 收尾（阶段回落到 READY + 通知 + 诊断），不会静默。
 * 2. 轮询间隔：前 [FAST_WINDOW_MS] 用 [FAST_POLL_INTERVAL_MS]，之后退到 [POLL_INTERVAL_MS]。
 *    冷启动最慢的一段是「PRoot/node 已拉起、还没开始监听」，这段时间探得勤一点能把发现就绪的
 *    延迟压到 100ms 以内；超过窗口说明这台设备启动就是慢，继续高频探测只会白耗 CPU 与电量，
 *    还会让 120 秒启动超时期间持续每秒十次连接尝试。
 *
 * 轮询节奏用「启动已过去的时长」而不是「第几次轮询」来切换：单次探测本身的耗时不定
 * （连接超时 200ms，首次 PRoot 冷启动时可能更久），按次数算会让慢设备提前掉出快速档。
 */
internal object HarnessStartupTimingPolicy {
    /**
     * 就绪信号后的复核窗口。
     *
     * 200ms 约等于两次 [FAST_POLL_INTERVAL_MS]，足以覆盖一次线程调度抖动，也仍保留
     * 「等一下再看一眼」的语义：进程若在这段时间内退出，本次启动会以受控错误码失败，
     * 而不是把一个马上要消失的地址交给界面。
     */
    const val STABILITY_MS = 200L

    /** 快速轮询窗口：覆盖绝大多数设备「进程已拉起、还没开始监听」的阶段。 */
    const val FAST_WINDOW_MS = 2_500L

    /** 快速窗口内的轮询间隔。 */
    const val FAST_POLL_INTERVAL_MS = 100L

    /** 快速窗口之后的轮询间隔：避免整个启动超时期间持续高频探测。 */
    const val POLL_INTERVAL_MS = 200L

    /**
     * 本次启动已过去 [elapsedMillis] 毫秒时，下一次轮询前应等待的毫秒数。
     *
     * 边界是「小于窗口」：恰好到达窗口边界就退到慢档，避免出现 2.5 秒之后还按 100ms 探测的
     * 半档状态（窗口只有一个，好解释也好测）。
     */
    fun pollIntervalMillis(elapsedMillis: Long): Long =
        if (elapsedMillis < FAST_WINDOW_MS) FAST_POLL_INTERVAL_MS else POLL_INTERVAL_MS
}
