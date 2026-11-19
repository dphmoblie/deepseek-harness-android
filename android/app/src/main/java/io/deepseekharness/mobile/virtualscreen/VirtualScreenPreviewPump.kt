package io.deepseekharness.mobile.virtualscreen

/**
 * 副屏预览的取帧拍子调度器（纯 Kotlin：时钟与投递都是注入点，假时钟 + 假队列即可覆盖）。
 *
 * 为什么抽出来：「一拍只续排一次、延迟 = 档位间隔 − 本拍耗时、慢帧不追赶积压」这套语义原先散在
 * [VirtualScreenPreview.tick] 的首尾两行，中间隔着整段取帧与上屏流程，只能靠真机观察。抽出来之后，
 * start / stop / 本拍开始 / 本拍落地这四个动作的时序与延迟都能逐拍断言。
 *
 * 边界：这里只决定「下一拍什么时候投递」——不取帧、不判定画面、不碰 Handler，也不读时钟。
 * [beat] 是调用方提供的一拍动作，投递与取消必须用同一个实例：`Handler.removeCallbacks` 按实例匹配，
 * 换一个包装对象就取消不掉。
 *
 * @param now 单调时钟（真机传 `SystemClock.elapsedRealtime`）。
 * @param postDelayed 投递一拍（真机传 `Handler.postDelayed`）。
 * @param removeCallbacks 取消已投递但还没跑的那一拍（真机传 `Handler.removeCallbacks`）。
 * @param beat 一拍的动作；必须是稳定实例，见类注释。
 */
internal class VirtualScreenPreviewPump(
    private val now: () -> Long,
    private val postDelayed: (Long, Runnable) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val beat: Runnable,
) {
    /** 是否在跑：stop 之后本拍落地不再续排（在途那一拍回主线程时循环已经停掉）。 */
    private var running = false

    /** 本拍开始时刻（由 [fetchStarted] 记下）：续排延迟要用它扣掉本拍耗时。 */
    private var startedAtMillis = 0L

    /**
     * 本拍是否已经开始。`startedAtMillis` 的 0 是「还没开始」的哨兵，而真机时钟是
     * `SystemClock.elapsedRealtime`（开机至今的毫秒数，通常很大），只靠它区分不出「还没开始」
     * 与「本拍刚开始」——不加这个标志，`start` 之后到 [fetchStarted] 之间会把设备开机至今算成一拍。
     */
    private var beatStarted = false

    /** 当前是否有一拍正在后台取帧途中：在途时 [nudge] 只记待加速标记，不重复排并发拍子。 */
    private var inFlight = false

    /** 在途期间收到交互提醒：本拍落地后立刻按最短安全间隔续排下一拍。 */
    private var nudgePending = false

    /** 交互突发窗口截止时刻（单调时钟毫秒）：窗口内低帧率档位临时提速到 60fps 上限。 */
    private var burstUntilMillis = 0L

    /** 开始取帧：投递首拍，只有首次返回 true —— 重挂或重复调用不会复制出第二条拍子。 */
    fun start(): Boolean {
        if (running) return false
        running = true
        startedAtMillis = 0L
        beatStarted = false
        inFlight = false
        nudgePending = false
        burstUntilMillis = 0L
        // 首拍等价于原来的 `Handler.post(tick)`：Handler 里 post(r) 就是 postDelayed(r, 0)。
        postDelayed(0L, beat)
        return true
    }

    /** 停止取帧：取消排队中的那一拍，之后本拍落地不再续排。 */
    fun stop() {
        running = false
        inFlight = false
        nudgePending = false
        burstUntilMillis = 0L
        removeCallbacks(beat)
    }

    /**
     * 交互触发的即时拉帧提醒（由触摸/点击/滑动落地后在主线程调用）。
     *
     * 若当前正处于拍间休眠（例如 `limited` 档 180ms 等待中），立即取消长等待并改按安全下限
     * [VirtualScreenPolicy.PREVIEW_PULL_FLOOR_MILLIS] 续排；若本拍正在后台取帧，则记下 [nudgePending]，
     * 落地后立刻衔接下一拍，绝不制造重叠拍子。
     */
    fun nudge(burstMillis: Long = VirtualScreenCapturePacer.INTERACTIVE_BURST_MS): Boolean {
        if (!running) return false
        val current = now()
        if (burstMillis > 0L) {
            burstUntilMillis = maxOf(burstUntilMillis, current + burstMillis)
        }
        if (inFlight) {
            nudgePending = true
            return false
        }
        removeCallbacks(beat)
        postDelayed(VirtualScreenPolicy.PREVIEW_PULL_FLOOR_MILLIS.toLong(), beat)
        return true
    }

    /** 本拍开始：记下起始时刻并返回它（本拍的取帧与上屏耗时随后从这里扣）。 */
    fun fetchStarted(): Long {
        startedAtMillis = now()
        beatStarted = true
        inFlight = true
        return startedAtMillis
    }

    /**
     * 从本拍开始到此刻的耗时（毫秒）；还没开始过任何一拍时返回 0。
     *
     * 与 [fetchLanded] 内部算延迟用的是同一起点，所以「预算记账」与「续排延迟」量的是同一件事：
     * 把这行读数拿去和档位间隔比，就是 [VirtualScreenPreviewBudget] 判超预算的依据。
     */
    fun elapsedMillis(): Long =
        if (!beatStarted) 0L else (now() - startedAtMillis).coerceAtLeast(0L)

    /**
     * 本拍落地：按档位续排下一拍并返回延迟毫秒数。
     *
     * 延迟 = [VirtualScreenPolicy.previewPullDelay]（即档位间隔减去本拍耗时，且不短于 1 毫秒）；
     * 本拍耗时超过档位间隔时不追赶积压：多花的时间从下一拍的等待里扣掉，不会连发补拍。
     * 若本拍期间有交互提醒或在交互突发窗口内，自动把过长的休眠收窄，停止交互后平滑回落档位间隔。
     * 已经 [stop] 则不再投递，返回 0。
     */
    fun fetchLanded(mode: String): Long {
        inFlight = false
        if (!running) {
            nudgePending = false
            return 0L
        }
        val baseDelay = VirtualScreenPolicy.previewPullDelay(mode, now() - startedAtMillis)
        val delay = when {
            nudgePending -> {
                nudgePending = false
                minOf(baseDelay, VirtualScreenPolicy.PREVIEW_PULL_FLOOR_MILLIS.toLong())
            }
            now() < burstUntilMillis -> minOf(baseDelay, VirtualScreenCapturePacer.INTERACTIVE_CAP_MS)
            else -> baseDelay
        }
        postDelayed(delay, beat)
        return delay
    }
}
