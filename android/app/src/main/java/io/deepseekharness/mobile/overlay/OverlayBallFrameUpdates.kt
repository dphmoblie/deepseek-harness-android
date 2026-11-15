package io.deepseekharness.mobile.overlay

/** 同一显示帧只保留最后一个位置；松手立即应用，摘除窗口时丢弃。 */
internal class OverlayBallFrameUpdates(
    private val postFrame: (Runnable) -> Unit,
    private val removeFrame: (Runnable) -> Unit,
    private val applyPosition: (Int, Int) -> Unit,
) {
    private var pending: Pair<Int, Int>? = null
    private var scheduled = false
    private val frame = Runnable {
        scheduled = false
        flush()
    }

    fun moveTo(x: Int, y: Int) {
        pending = x to y
        if (scheduled) return
        scheduled = true
        postFrame(frame)
    }

    fun flush() {
        if (scheduled) removeFrame(frame)
        scheduled = false
        val position = pending ?: return
        pending = null
        applyPosition(position.first, position.second)
    }

    fun cancel() {
        if (scheduled) removeFrame(frame)
        scheduled = false
        pending = null
    }
}
