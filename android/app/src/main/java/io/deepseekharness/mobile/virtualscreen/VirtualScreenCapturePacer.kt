package io.deepseekharness.mobile.virtualscreen

/**
 * 副屏连续操作时的采集节拍器（纯 Kotlin，无 Android 依赖，可直接 JVM 单测）。
 *
 * 解决旧实现里两个导致连续操作掉帧与高延迟的问题：
 * 1. 旧实现在 `OnImageAvailableListener` 里无条件 `postDelayed(frameInterval)`，即使画面之前处于静止、
 *    刚刚响应用户点击/滑动产生第一帧，也会被人为延迟 33ms~180ms 才读取；
 * 2. 在 `limited`（180ms）/ `15fps`（66ms）等省电档位下，用户连续拖动或 AI 连续操作期间画面明显卡顿。
 *
 * 策略（前缘立即采集 + 后缘合并 + 交互突发加速）：
 * - **交互突发窗口**（最近输入后 [INTERACTIVE_BURST_MS] 毫秒内）：有效采集间隔收窄到
 *   `minOf(configuredInterval, INTERACTIVE_CAP_MS)`，连续操作时维持流畅帧率，停止操作后自动回落省电档位；
 * - **前缘（leading edge）**：距上次采集已满有效间隔时返回 `0L`，调用方立刻读取首帧，消除静止后的首帧额外等待；
 * - **后缘（trailing edge）**：仍在间隔窗口内时只安排一次剩余毫秒数的延迟采集，合并突发回调同时绝不漏掉最后一帧。
 */
internal class VirtualScreenCapturePacer {
    private var lastCollectedAtMillis = 0L
    private var trailingScheduled = false

    @Synchronized
    fun activeIntervalMillis(configuredIntervalMillis: Int, nowMillis: Long, latestInputAtMillis: Long): Long {
        val base = configuredIntervalMillis.coerceAtLeast(1).toLong()
        if (latestInputAtMillis > 0L) {
            val sinceInput = nowMillis - latestInputAtMillis
            if (sinceInput in 0L..INTERACTIVE_BURST_MS) {
                return minOf(base, INTERACTIVE_CAP_MS)
            }
        }
        return base
    }

    /**
     * 收到新帧可用回调时决定下一步动作：
     * - 返回 `null`：后缘采集已经排期，本次合并跳过；
     * - 返回 `0L`：已满足前缘条件，立刻采集（不走 `postDelayed`）；
     * - 返回 `> 0L`：按该毫秒数排一次后缘采集。
     */
    @Synchronized
    fun onFrameAvailable(nowMillis: Long, configuredIntervalMillis: Int, latestInputAtMillis: Long): Long? {
        if (trailingScheduled) return null
        val interval = activeIntervalMillis(configuredIntervalMillis, nowMillis, latestInputAtMillis)
        val elapsed = if (lastCollectedAtMillis <= 0L) interval else (nowMillis - lastCollectedAtMillis).coerceAtLeast(0L)
        if (elapsed >= interval) {
            return 0L
        }
        trailingScheduled = true
        return (interval - elapsed).coerceAtLeast(1L)
    }

    /** 真正开始读帧时调用：记下采集时刻并清掉后缘排期标记。 */
    @Synchronized
    fun onCollectStarted(nowMillis: Long) {
        trailingScheduled = false
        lastCollectedAtMillis = nowMillis
    }

    /** 会话结束或重建时复位。 */
    @Synchronized
    fun reset() {
        trailingScheduled = false
        lastCollectedAtMillis = 0L
    }

    companion object {
        /** 输入落地后的高响应突发窗口：覆盖连续点击、拖动、惯性滚动阶段。 */
        const val INTERACTIVE_BURST_MS = 800L

        /** 突发窗口内的采集间隔上限（约 60fps），不影响更高刷新率档位。 */
        const val INTERACTIVE_CAP_MS = 16L
    }
}

/**
 * 副屏目标可见性短时缓存（纯 Kotlin，用于输入热路径避免每次点击都 fork `dumpsys activity activities`）。
 *
 * 真机上一次 `dumpsys activity activities` 耗时约 100–300ms 且占用 ShellVirtualScreen 实例锁；
 * 连续点击/拖动期间目标应用在短窗口内保持前台，在 [TTL_MILLIS] 内复用最近一次确认结论可显著降低连续操作延迟。
 * 任何切换/启动/跟随/关闭/前台变化都会立刻 [invalidate]，绝不拿旧目标掩盖切离事件。
 */
internal class VirtualScreenTargetVisibilityCache(
    private val ttlMillis: Long = TTL_MILLIS,
) {
    private var cachedSession = ""
    private var cachedDisplayId = -1
    private var cachedPackage = ""
    private var verifiedAtMillis = 0L

    @Synchronized
    fun isFresh(session: String, displayId: Int, targetPackage: String, nowMillis: Long): Boolean {
        if (session.isEmpty() || displayId <= 0 || targetPackage.isEmpty() || verifiedAtMillis <= 0L) return false
        if (session != cachedSession || displayId != cachedDisplayId || targetPackage != cachedPackage) return false
        val age = nowMillis - verifiedAtMillis
        return age in 0L..ttlMillis
    }

    @Synchronized
    fun record(session: String, displayId: Int, targetPackage: String, visible: Boolean, nowMillis: Long) {
        if (!visible || session.isEmpty() || displayId <= 0 || targetPackage.isEmpty()) {
            invalidate()
            return
        }
        cachedSession = session
        cachedDisplayId = displayId
        cachedPackage = targetPackage
        verifiedAtMillis = nowMillis
    }

    @Synchronized
    fun invalidate() {
        cachedSession = ""
        cachedDisplayId = -1
        cachedPackage = ""
        verifiedAtMillis = 0L
    }

    companion object {
        const val TTL_MILLIS = 1_500L
    }
}
