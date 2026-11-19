package io.deepseekharness.mobile.runtime

/**
 * 通知投递去重与分组边界策略（纯 JVM 逻辑，不依赖 Android 框架）。
 *
 * 背景：Android 16/17（含荣耀 MagicOS）上，`SystemUI` 的 `ShadeViewDiffer.registerNodes`
 * 在同一通知节点既被提升为独立实况/常驻节点、又被绑定到 `BackgroundNotificationGroup` 摘要组时，
 * 或在高频更新期间重复触发树 diff 时，会直接抛出 `DuplicateNodeException` 导致 SystemUI 崩溃并连带重启桌面。
 * 因此这里用确定性决策函数消除无意义的重发 `notify` / `cancel`，并把单条子通知排除在摘要折叠之外。
 */
internal object BackgroundNotificationGroupPolicy {
    const val MIN_CHILDREN_FOR_SUMMARY = 2

    data class SummaryState(
        val childCount: Int,
        val localeTag: String,
    )

    sealed interface SummaryDecision {
        data object NoOp : SummaryDecision
        data object Cancel : SummaryDecision
        data class Post(val state: SummaryState) : SummaryDecision
    }

    fun decide(
        childCount: Int,
        localeTag: String,
        summaryPresentInSystem: Boolean?,
        lastPosted: SummaryState?,
    ): SummaryDecision {
        if (childCount < MIN_CHILDREN_FOR_SUMMARY) {
            return if (summaryPresentInSystem == true || (summaryPresentInSystem == null && lastPosted != null)) {
                SummaryDecision.Cancel
            } else {
                SummaryDecision.NoOp
            }
        }
        val desired = SummaryState(childCount = childCount, localeTag = localeTag)
        return if (summaryPresentInSystem == true && lastPosted == desired) {
            SummaryDecision.NoOp
        } else {
            SummaryDecision.Post(desired)
        }
    }
}

internal object ActiveTaskNotificationSyncPolicy {
    data class ActiveTaskState(
        val runId: String?,
        val activeCount: Int,
        val startedAtMs: Long,
        val localeTag: String,
    )

    sealed interface ActiveTaskDecision {
        data object NoOp : ActiveTaskDecision
        data object Cancel : ActiveTaskDecision
        data class Post(val state: ActiveTaskState) : ActiveTaskDecision
    }

    fun decide(
        shouldShow: Boolean,
        candidate: ActiveTaskState?,
        postedInSystem: Boolean?,
        lastPosted: ActiveTaskState?,
    ): ActiveTaskDecision {
        if (!shouldShow || candidate == null || candidate.activeCount <= 0) {
            return if (postedInSystem == true || (postedInSystem == null && lastPosted != null)) {
                ActiveTaskDecision.Cancel
            } else {
                ActiveTaskDecision.NoOp
            }
        }
        return if (postedInSystem == true && lastPosted == candidate) {
            ActiveTaskDecision.NoOp
        } else {
            ActiveTaskDecision.Post(candidate)
        }
    }
}
