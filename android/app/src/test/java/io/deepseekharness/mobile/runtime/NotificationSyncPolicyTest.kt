package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.ActiveTaskNotificationSyncPolicy.ActiveTaskDecision
import io.deepseekharness.mobile.runtime.ActiveTaskNotificationSyncPolicy.ActiveTaskState
import io.deepseekharness.mobile.runtime.BackgroundNotificationGroupPolicy.SummaryDecision
import io.deepseekharness.mobile.runtime.BackgroundNotificationGroupPolicy.SummaryState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知组摘要与进行中实况通知的投递去重/边界策略测试。
 *
 * 钉住两条真机回归边界：
 * 1. `0x44534806`（实况进行中通知）绝不能挂进 `BackgroundNotificationGroup`（`0x44534800`），
 *    否则 Honor/Android 17 SystemUI 在 `ShadeViewDiffer.registerNodes` 会因同一节点既被实况提升
 *    又留在组内而抛 `DuplicateNodeException` 并引发连环崩溃；
 * 2. 组摘要仅在子通知 >= 2 时投递，且状态未变时跳过重复 `notify`，避免高频无效树 diff。
 */
class NotificationSyncPolicyTest {
    @Test
    fun `组内不足两条子通知时不发布摘要并在存在旧摘要时撤销`() {
        for (count in listOf(0, 1)) {
            assertEquals(
                SummaryDecision.NoOp,
                BackgroundNotificationGroupPolicy.decide(
                    childCount = count,
                    localeTag = "zh-CN",
                    summaryPresentInSystem = false,
                    lastPosted = null,
                ),
            )
            assertEquals(
                SummaryDecision.Cancel,
                BackgroundNotificationGroupPolicy.decide(
                    childCount = count,
                    localeTag = "zh-CN",
                    summaryPresentInSystem = true,
                    lastPosted = SummaryState(2, "zh-CN"),
                ),
            )
        }
    }

    @Test
    fun `组内至少两条子通知时去重并在条数语言或系统丢失后重发`() {
        val stateTwoZh = SummaryState(2, "zh-CN")
        assertEquals(
            SummaryDecision.Post(stateTwoZh),
            BackgroundNotificationGroupPolicy.decide(2, "zh-CN", summaryPresentInSystem = false, lastPosted = null),
        )
        assertEquals(
            SummaryDecision.NoOp,
            BackgroundNotificationGroupPolicy.decide(2, "zh-CN", summaryPresentInSystem = true, lastPosted = stateTwoZh),
        )
        assertEquals(
            SummaryDecision.Post(stateTwoZh),
            BackgroundNotificationGroupPolicy.decide(2, "zh-CN", summaryPresentInSystem = false, lastPosted = stateTwoZh),
        )
        assertEquals(
            SummaryDecision.Post(SummaryState(3, "zh-CN")),
            BackgroundNotificationGroupPolicy.decide(3, "zh-CN", summaryPresentInSystem = true, lastPosted = stateTwoZh),
        )
        assertEquals(
            SummaryDecision.Post(SummaryState(2, "en")),
            BackgroundNotificationGroupPolicy.decide(2, "en", summaryPresentInSystem = true, lastPosted = stateTwoZh),
        )
    }

    @Test
    fun `进行中任务通知在系统状态一致时跳过重复投递并在失活时精确撤销`() {
        val state = ActiveTaskState(runId = "0123456789abcdef", activeCount = 1, startedAtMs = 10_000L, localeTag = "zh-CN")

        assertEquals(
            ActiveTaskDecision.Post(state),
            ActiveTaskNotificationSyncPolicy.decide(shouldShow = true, candidate = state, postedInSystem = false, lastPosted = null),
        )
        assertEquals(
            ActiveTaskDecision.NoOp,
            ActiveTaskNotificationSyncPolicy.decide(shouldShow = true, candidate = state, postedInSystem = true, lastPosted = state),
        )
        assertEquals(
            ActiveTaskDecision.Post(state),
            ActiveTaskNotificationSyncPolicy.decide(shouldShow = true, candidate = state, postedInSystem = false, lastPosted = state),
        )
        assertEquals(
            ActiveTaskDecision.Post(state.copy(activeCount = 2)),
            ActiveTaskNotificationSyncPolicy.decide(
                shouldShow = true,
                candidate = state.copy(activeCount = 2),
                postedInSystem = true,
                lastPosted = state,
            ),
        )
        assertEquals(
            ActiveTaskDecision.NoOp,
            ActiveTaskNotificationSyncPolicy.decide(shouldShow = false, candidate = null, postedInSystem = false, lastPosted = null),
        )
        assertEquals(
            ActiveTaskDecision.Cancel,
            ActiveTaskNotificationSyncPolicy.decide(shouldShow = false, candidate = null, postedInSystem = true, lastPosted = state),
        )
    }

    @Test
    fun `进行中实况通知不得加入后台通知分组以免触发 SystemUI DuplicateNodeException`() {
        val source = source("TaskLiveNotificationCompat.kt")
        assertFalse(source.contains("BackgroundNotificationGroup.GROUP_KEY"))
        assertFalse(source.contains(".setGroup("))

        val taskNotification = source("TaskNotification.kt")
        val updateStart = taskNotification.indexOf("private fun updateTaskStatus(")
        val updateEnd = taskNotification.indexOf("data class TurnCompletionReceipt(", updateStart)
        assertTrue(updateStart >= 0 && updateEnd > updateStart)
        val updateBody = taskNotification.substring(updateStart, updateEnd)
        assertFalse(
            "更新或撤下进行中通知时不应同步后台组摘要",
            updateBody.contains("BackgroundNotificationGroup.sync("),
        )
    }

    private fun source(name: String): String {
        val relative = "src/main/java/io/deepseekharness/mobile/runtime/$name"
        return listOf(File(relative), File("app/$relative"), File("android/app/$relative"))
            .first { it.isFile }.readText()
    }
}
