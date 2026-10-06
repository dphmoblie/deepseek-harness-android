package io.deepseekharness.mobile.virtualscreen

/**
 * 离散触摸通道的事件编排（纯逻辑：不 import 任何 android 类，可以直接在 JVM 单测里覆盖）。
 *
 * 背景：进程内注入（`InputManager.injectInputEvent`）不可用时只能退回 `/system/bin/input`，而它
 * 每个事件要起一个进程（本仓库实测约 140 毫秒，见 [VirtualScreenInjector] 的类注释）。于是离散通道
 * 有两个都不能接受的极端：
 *
 * - 逐 16 毫秒采样直传：一段两秒的手势要起一百多个进程，实际会拖成十几秒；
 * - 只发一次 `input swipe`：命令返回 0 不等于事件落到了副屏上，改前就是这么「报成功但画面不动」的。
 *
 * 这里走中间路线：
 *
 * 1. [plan] 把插值采样点合并成有界的「按下 → 若干移动 → 抬起」事件序列；
 * 2. [Cursor] 在注入过程中丢弃已经落后计划太久、补发也没有意义的移动事件，保证整段手势有界收尾；
 * 3. [rejected] 判定命令输出里有没有「系统拒绝了这次注入」的字样——退出码为 0 也不能当成功。
 *
 * 本文件只做编排与判定：不碰进程、不碰系统 API、不自己读时钟（时间由调用方传进来），因此可测。
 */
object VirtualScreenMotionPlan {
    /** 动作名；与 [VirtualScreenInjector.PHASES] 同一套取值，注入时转成 `input motionevent` 的大写参数。 */
    const val DOWN = "down"
    const val MOVE = "move"
    const val UP = "up"
    const val CANCEL = "cancel"

    /** 一次离散手势注入的事件数上限（含按下与抬起）：每个事件一次进程启动，必须封顶。 */
    const val MAX_EVENTS = 16

    /** 两个移动事件之间的最小时间间隔；比这更密的采样点先合并掉。 */
    const val MIN_MOVE_INTERVAL_MILLIS = 40L

    /** 相邻移动事件的最小位移（像素）；位移不足的采样点合并成一个（与预览层的滑动判定同量级）。 */
    const val MIN_MOVE_DISTANCE = 8

    /** 允许落后计划多少毫秒仍照常注入；超出即判为过期移动事件，直接丢弃。 */
    const val STALE_TOLERANCE_MILLIS = 250L

    /**
     * 人为拖动（`touch` 的按下 / 移动 / 抬起）在离散通道上的回放时间轴长度。
     *
     * 手指已经抬起了，再按原速把两秒的轨迹慢放一遍只会让人更觉得卡；这里统一压到 240 毫秒以内：
     * 位移仍按真实首尾点走，只是中间位置按 [MIN_MOVE_INTERVAL_MILLIS] 合并、按 [STALE_TOLERANCE_MILLIS]
     * 丢弃过期点。AI 的 `gesture` 不受此限，它本来就按调用方给的时长跑。
     */
    const val TOUCH_TIMELINE_MILLIS = 240

    /** 单个待注入事件：动作、坐标、相对本段手势开始的计划时刻。 */
    data class Step(val action: String, val x: Int, val y: Int, val atMillis: Long)

    /** 编排结果：[samples] 是原始采样点数，[merged] 是被合并掉的移动采样点数。 */
    data class Plan(val steps: List<Step>, val samples: Int, val merged: Int)

    /** 动作名 → `input motionevent` 的参数（大写）。 */
    fun argument(action: String): String = action.uppercase()

    /** 动作名的中文说明，只用于失败消息。 */
    fun label(action: String): String = when (action) {
        DOWN -> "按下"
        MOVE -> "移动"
        UP -> "抬起"
        CANCEL -> "取消"
        else -> action
    }

    /**
     * 把插值采样点编排成离散事件序列（首尾点原样保留：抬起位置就是轨迹终点，跳过它整段位移会缩水）。
     *
     * 合并规则：中间的移动事件只保留「距上一个保留点至少 [MIN_MOVE_INTERVAL_MILLIS] 且至少
     * [MIN_MOVE_DISTANCE] 像素」的采样点；保留数超过 [MAX_EVENTS] 上限时再等距抽样一次
     * （首尾仍然保留）。[Plan.merged] 如实记下被合并掉的移动采样点数，供诊断说明这不是逐点直传。
     *
     * @param timelineMillis 计划时间轴长度：采样点按它均匀铺开，末点正好落在末端。
     */
    fun plan(samples: List<VirtualScreenPolicy.GesturePoint>, timelineMillis: Int): Plan {
        require(samples.isNotEmpty()) { "副屏手势至少需要一个坐标点" }
        val first = samples.first()
        val last = samples.last()
        val steps = ArrayList<Step>(MAX_EVENTS)
        steps.add(Step(DOWN, first.x, first.y, 0L))
        if (samples.size > 1) {
            val kept = ArrayList<Int>(samples.size - 1)
            var anchor = 0
            for (index in 1 until samples.size) {
                val sample = samples[index]
                val previous = samples[anchor]
                val endpoint = index == samples.size - 1
                val far = kotlin.math.hypot(
                    (sample.x - previous.x).toDouble(),
                    (sample.y - previous.y).toDouble(),
                ) >= MIN_MOVE_DISTANCE
                val late = atMillis(index, samples.size, timelineMillis) -
                    atMillis(anchor, samples.size, timelineMillis) >= MIN_MOVE_INTERVAL_MILLIS
                // 末点永远保留：它是滑动终点，合并掉会让整段位移停在半路。
                if (!endpoint && !(far && late)) continue
                kept.add(index)
                anchor = index
            }
            for (index in subsample(kept, MAX_EVENTS - 2)) {
                steps.add(Step(MOVE, samples[index].x, samples[index].y, atMillis(index, samples.size, timelineMillis)))
            }
        }
        steps.add(Step(UP, last.x, last.y, timelineMillis.toLong()))
        return Plan(steps, samples.size, (samples.size - 1) - (steps.size - 2))
    }

    /** 采样点下标对应的计划时刻：在时间轴内均匀铺开，末点正好落在时间轴末端。 */
    private fun atMillis(index: Int, count: Int, timelineMillis: Int): Long =
        if (count <= 1) 0L else timelineMillis.toLong() * index / (count - 1)

    /** 等距抽样保留 [limit] 个下标，首尾优先；[limit] 不小于 1。 */
    private fun subsample(indices: List<Int>, limit: Int): List<Int> {
        if (limit <= 0) return emptyList()
        if (indices.size <= limit) return indices
        if (limit == 1) return listOf(indices.last())
        val out = ArrayList<Int>(limit)
        for (slot in 0 until limit) {
            val at = (slot.toLong() * (indices.size - 1) / (limit - 1)).toInt()
            if (out.isEmpty() || out.last() != indices[at]) out.add(indices[at])
        }
        return out
    }

    /**
     * 按计划顺序推进的游标。调用方每注入完一个事件就问一次「下一个注入什么」，传入自本段手势开始
     * 起的实际毫秒数；落后计划超过 [toleranceMillis] 的移动事件直接丢弃并计入 [dropped]。
     *
     * 按下与抬起不参与过期判定：丢了按下等于整段手势没开始，丢了抬起会把副屏留在「被按住」状态。
     */
    class Cursor(private val steps: List<Step>, private val toleranceMillis: Long = STALE_TOLERANCE_MILLIS) {
        private var index = 0

        /** 已交给调用方注入的事件数（含按下与抬起）。 */
        var injected = 0
            private set

        /** 因过期被丢弃的移动事件数。 */
        var dropped = 0
            private set

        /** 下一个要注入的事件；全部事件都已处理完（包括只剩过期事件被丢弃）时返回 null。 */
        fun next(elapsedMillis: Long): Step? {
            while (index < steps.size) {
                val step = steps[index]
                index++
                if (step.action == MOVE && elapsedMillis - step.atMillis > toleranceMillis) {
                    dropped++
                    continue
                }
                injected++
                return step
            }
            return null
        }
    }

    /**
     * 命令输出里出现这些字样就认为事件没落下去。`input` 在注入被系统拒绝时会打印原因，但仍可能以 0
     * 退出：只看退出码会把「没落地」当成成功（每个事件的进程退出码由调用方在 [rejected] 之前先查）。
     */
    fun rejected(output: String): Boolean = REJECT_MARKERS.any { output.contains(it) }

    private val REJECT_MARKERS = listOf(
        "Error:", "Exception:", "SecurityException",
        "Permission Denial", "Permission denial",
        "Failed to inject", "injectInputEvent failed", "Injection failed",
        "Unknown command", "Unknown action", "Unknown option",
        "Usage: input", "requires an argument",
    )
}
