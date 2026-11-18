package io.deepseekharness.mobile.virtualscreen

import org.json.JSONObject

/**
 * AI 副屏输入后的「等画面稳定」口径（纯函数，真机与单测共用）。
 *
 * ## 为什么要在设备端等
 * 真机实测（2026-11 答题场景）：负载较高时一次点击到画面出首帧可能要好几秒，模型按「点完 2～3 秒就截图」
 * 的节奏看到的常常是旧帧，于是误判为没点上、再补点，事件堆积甚至被合并/丢弃。让设备端在回执里直接告诉
 * 模型「输入之后有没有出新帧、画面稳没稳」，比让模型猜一个固定等待时间可靠得多。
 *
 * ## 边界（每一条都是刻意的）
 * 1. **只有 AI 路径默认等待**：插件层给出 `settleMs` 缺省值；设备端缺省为 0（不等）。预览页的离散点击/滑动
 *    也走同一个 action，那是用户手指的交互，不能让每次抬手都多卡两秒。
 * 2. `frameAfterInput=true` 只说明**输入之后画面变过**（倒计时、光标闪烁也会触发），不证明点中了目标；
 *    `false` 说明预算内画面一帧没变——要么点在了不可点区域，要么设备太忙还没渲染，模型应先观察再决定，不要立刻补点。
 * 3. 等待在设备端的实例监视器**之外**进行，只等帧锁：等待期间状态读取、自动跟随与会话结束都不被它挡住。
 */
internal object VirtualScreenSettle {
    /** 调用方请求的等待预算字段（毫秒）；缺省 0 = 不等。 */
    const val FIELD = "settleMs"

    /** 截图等待输入后新帧的预算字段（毫秒）；缺省 0 = 立即读取当前保留帧。 */
    const val CAPTURE_WAIT_FIELD = "waitMs"

    /** 预算上限：再长就该让模型自己分段观察，而不是一次调用卡十几秒。 */
    const val MAX_BUDGET_MILLIS = 10_000

    /** 插件层给 AI 动作用的缺省预算，与 dsh-mobile-shizuku 插件里的常量保持一致。 */
    const val AGENT_DEFAULT_BUDGET_MILLIS = 3_000

    /** 「画面已稳定」的最短静止时长；低帧档位按采集间隔的三倍放宽，避免把两次采集之间的空档当成静止。 */
    const val MIN_QUIET_MILLIS = 300L

    /** 单次等待的上限：帧锁上的 wait 会被新帧唤醒，这里只防止错过通知后睡过头。 */
    const val POLL_MILLIS = 50L

    /** 会产生画面反馈、值得等的动作；触摸直传是逐事件高频路径，不在其中。 */
    val ACTIONS = setOf("tap", "swipe", "long_press", "keyevent", "back", "gesture")

    /** 读取并校验等待预算：不在 [ACTIONS] 里或没带字段时为 0；带了就必须是 0..[MAX_BUDGET_MILLIS] 的整数。 */
    fun budget(p: JSONObject): Int {
        if (p.optString("action") !in ACTIONS || !p.has(FIELD)) return 0
        return parseMillis(p, FIELD, "副屏等待时长")
    }

    /** 读取并校验截图等待预算：没带或为 null 时为 0；带了就必须是 0..[MAX_BUDGET_MILLIS] 的整数。 */
    fun captureWaitBudget(p: JSONObject): Int {
        if (!p.has(CAPTURE_WAIT_FIELD) || p.isNull(CAPTURE_WAIT_FIELD)) return 0
        return parseMillis(p, CAPTURE_WAIT_FIELD, "副屏截图等待时长")
    }

    private fun parseMillis(p: JSONObject, key: String, label: String): Int {
        val value = p.get(key)
        require(value is Int || value is Long) { "${label}必须为整数" }
        val number = (value as Number).toLong()
        require(number in 0L..MAX_BUDGET_MILLIS.toLong()) { "${label}必须为 0～$MAX_BUDGET_MILLIS 毫秒" }
        return number.toInt()
    }

    fun quietMillis(frameIntervalMillis: Int): Long = maxOf(MIN_QUIET_MILLIS, 3L * frameIntervalMillis)

    /** 一步判定：要么继续等 [Wait.millis]，要么给出结论 [Done]。 */
    sealed class Step {
        data class Wait(val millis: Long) : Step()
        data class Done(val frameAfterInput: Boolean, val quiet: Boolean) : Step()
    }

    /**
     * @param now 当前时刻（elapsedRealtime 毫秒）
     * @param inputAt 输入落地时刻
     * @param frameAt 最近一帧的采集时刻
     * @param deadline 等待截止时刻
     * @param quiet 判定稳定所需的静止时长
     */
    fun step(now: Long, inputAt: Long, frameAt: Long, deadline: Long, quiet: Long): Step {
        val remaining = deadline - now
        if (frameAt <= inputAt) {
            return if (remaining <= 0L) Step.Done(frameAfterInput = false, quiet = false)
            else Step.Wait(minOf(POLL_MILLIS, remaining))
        }
        val idle = now - frameAt
        if (idle >= quiet) return Step.Done(frameAfterInput = true, quiet = true)
        if (remaining <= 0L) return Step.Done(frameAfterInput = true, quiet = false)
        return Step.Wait(minOf(POLL_MILLIS, quiet - idle, remaining))
    }

    /**
     * 回执里的 `settle` 对象：字段全是数字/布尔，不含画面内容。
     * [firstFrameAt] 是等待中观察到的第一帧输入后画面（0 = 没等到），`firstFrameDelayMs` 只在等到时输出。
     */
    fun outcome(
        budget: Int,
        inputAt: Long,
        firstFrameAt: Long,
        frameAt: Long,
        finishedAt: Long,
        done: Step.Done,
        aborted: Boolean = false,
    ): JSONObject {
        val json = JSONObject()
            .put("budgetMs", budget)
            .put("waitedMs", (finishedAt - inputAt).coerceAtLeast(0L))
            .put("frameAfterInput", done.frameAfterInput)
            .put("quiet", done.quiet)
            .put("inputAtElapsedMs", inputAt)
            .put("frameAtElapsedMs", frameAt)
        if (done.frameAfterInput && firstFrameAt > inputAt) json.put("firstFrameDelayMs", firstFrameAt - inputAt)
        if (aborted) json.put("aborted", true)
        return json
    }
}
