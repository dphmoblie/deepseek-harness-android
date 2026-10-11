package io.deepseekharness.mobile.virtualscreen

/** 主线程维护生命周期；后台取帧与输入任务读取同一代号，临时重挂不作废在途任务。 */
internal class VirtualScreenPreviewLifecycle {
    @Volatile var generation = 0
        private set
    private var running = false

    /** 只有新一轮挂载才需要投递首帧。 */
    fun attach(): Boolean {
        if (running) return false
        generation++
        running = true
        return true
    }

    /** 返回 true 才需要取消调度并释放资源；重复释放不再推进代号。 */
    fun detach(retain: Boolean): Boolean {
        if (retain || !running) return false
        generation++
        running = false
        return true
    }
}
