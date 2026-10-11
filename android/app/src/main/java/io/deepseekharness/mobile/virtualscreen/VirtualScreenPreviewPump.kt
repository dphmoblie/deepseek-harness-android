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

    /** 开始取帧：投递首拍，只有首次返回 true —— 重挂或重复调用不会复制出第二条拍子。 */
    fun start(): Boolean {
        if (running) return false
        running = true
        startedAtMillis = 0L
        // 首拍等价于原来的 `Handler.post(tick)`：Handler 里 post(r) 就是 postDelayed(r, 0)。
        postDelayed(0L, beat)
        return true
    }

    /** 停止取帧：取消排队中的那一拍，之后本拍落地不再续排。 */
    fun stop() {
        running = false
        removeCallbacks(beat)
    }

    /** 本拍开始：记下起始时刻并返回它（本拍的取帧与上屏耗时随后从这里扣）。 */
    fun fetchStarted(): Long {
        startedAtMillis = now()
        return startedAtMillis
    }

    /**
     * 本拍落地：按档位续排下一拍并返回延迟毫秒数。
     *
     * 延迟 = [VirtualScreenPolicy.previewPullDelay]（即档位间隔减去本拍耗时，且不短于 1 毫秒）；
     * 本拍耗时超过档位间隔时不追赶积压：多花的时间从下一拍的等待里扣掉，不会连发补拍。
     * 已经 [stop] 则不再投递，返回 0。
     */
    fun fetchLanded(mode: String): Long {
        if (!running) return 0L
        val delay = VirtualScreenPolicy.previewPullDelay(mode, now() - startedAtMillis)
        postDelayed(delay, beat)
        return delay
    }
}
