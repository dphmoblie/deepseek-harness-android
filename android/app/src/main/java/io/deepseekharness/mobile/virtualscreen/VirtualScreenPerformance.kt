package io.deepseekharness.mobile.virtualscreen

/**
 * 副屏性能读数里**能纯算**的那部分。
 *
 * 这一层刻意不碰 `android.*`：目标帧率、画面年龄、响应耗时的口径都要能在 JVM 单测里精确构造
 * （尤其是「时钟回退」「还没有帧」「一次都还没测过」这三种边界），否则界面上的数字只是看起来像读数。
 *
 * 三个口径的共同约定：**拿不到就给 0，且 0 不等于「没问题」**。界面必须结合 `active` 与
 * `frameAtElapsedMs` 判断，不能把 0 显示成「0 FPS 但一切正常」。
 */
internal object VirtualScreenPerformance {

    /**
     * 目标帧率：帧率上限里**较小的那个**。
     *
     * 采集间隔（档位）是这一侧的采样上限，面板刷新率是那块虚拟显示器的物理上限，两个都成立时
     * 谁也超不过谁，所以取小；只拿得到一个就用那一个。两个都拿不到返回 0.0——
     * 这是「读不到」，不是「目标是 0 帧」。
     */
    fun targetFps(frameIntervalMillis: Long, refreshRate: Float): Double {
        val byInterval = if (frameIntervalMillis > 0L) 1000.0 / frameIntervalMillis else 0.0
        val byRefresh = if (refreshRate > 0f) refreshRate.toDouble() else 0.0
        val value = when {
            byInterval > 0.0 && byRefresh > 0.0 -> minOf(byInterval, byRefresh)
            else -> maxOf(byInterval, byRefresh)
        }
        return oneDecimal(value)
    }

    /**
     * 画面年龄：调用方读状态的时刻减去最近一帧的采集时刻。
     *
     * 还没有任何一帧（`frameAtMillis <= 0`）返回 0；时钟回退或同一毫秒也返回 0。
     * 绝不返回负数——负数在界面上会被格式化成「-3 毫秒」，比读不到更糟。
     * 副屏静止时这个值会一直变大，那是**正常**的：静止页面本来就不再产生新帧。
     */
    fun frameAgeMillis(nowMillis: Long, frameAtMillis: Long): Long =
        if (frameAtMillis <= 0L || nowMillis <= frameAtMillis) 0L else nowMillis - frameAtMillis

    /** 状态里的浮点读数只要一位小数，调用方不需要更高精度（与实测帧率同口径）。 */
    fun oneDecimal(value: Double): Double = Math.round(value * 10.0) / 10.0
}

/**
 * 输入到画面的响应耗时：最近若干次「注入落地 → 等到下一帧」的样本。
 *
 * 只保留最近 [capacity] 次：这个数字要回答的是「现在这一下点了多久才看见反应」，
 * 把几分钟前的样本一起平均会把当前的卡顿抹平。容量固定、无分配，注入路径上可以直接调用。
 */
internal class VirtualScreenLatency(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 1) { "响应耗时样本容量至少为 1" }
    }

    private val samples = LongArray(capacity)
    private var next = 0
    private var count = 0

    /** 记一次样本；负值直接丢弃（那说明时钟回退了，不是一次真实响应）。 */
    fun record(millis: Long) {
        if (millis < 0L) return
        samples[next] = millis
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** 已记录的样本数；为 0 表示本次会话还没有测到过一次（界面必须说「尚未测到」）。 */
    val sampleCount: Int get() = count

    /** 最近一次的响应耗时；没有样本时为 0。 */
    val lastMillis: Long get() = if (count == 0) 0L else samples[(next - 1 + capacity) % capacity]

    /** 最近 [sampleCount] 次的平均；没有样本时为 0。 */
    val averageMillis: Long
        get() {
            if (count == 0) return 0L
            var sum = 0L
            for (index in 0 until count) sum += samples[index]
            return sum / count
        }

    /** 会话结束或换目标时清空：上一段会话的响应耗时不代表这一段。 */
    fun clear() {
        next = 0
        count = 0
    }

    internal companion object {
        /** 最近 8 次：够看出「现在是不是卡」，又不至于把旧样本带进来。 */
        const val DEFAULT_CAPACITY = 8
    }
}
