package io.deepseekharness.mobile.virtualscreen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 副屏输入后「等画面稳定」与「截图等待新帧」的纯函数契约。
 */
class VirtualScreenSettleTest {

    @Test
    fun `动作预算只作用于会产生画面反馈的动作且校验整数区间`() {
        assertEquals(3_000, VirtualScreenSettle.AGENT_DEFAULT_BUDGET_MILLIS)
        assertEquals(10_000, VirtualScreenSettle.MAX_BUDGET_MILLIS)
        for (action in VirtualScreenSettle.ACTIONS) {
            assertEquals(0, VirtualScreenSettle.budget(JSONObject().put("action", action)))
            assertEquals(4_500, VirtualScreenSettle.budget(JSONObject().put("action", action).put("settleMs", 4_500)))
            assertEquals(10_000, VirtualScreenSettle.budget(JSONObject().put("action", action).put("settleMs", 10_000L)))
        }
        // 非视觉反馈类动作即使带了字段也按 0 处理，不阻塞释放/配置/逐事件直传。
        for (action in listOf("text", "touch", "stop", "config", "tree")) {
            assertEquals(0, VirtualScreenSettle.budget(JSONObject().put("action", action).put("settleMs", 3_000)))
        }
        for (bad in listOf<Any>(-1, 10_001, 250.5, "3000", true)) {
            assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenSettle.budget(JSONObject().put("action", "tap").put("settleMs", bad))
            }
        }
    }

    @Test
    fun `截图等待预算缺省为零并限制在零到十秒`() {
        assertEquals(0, VirtualScreenSettle.captureWaitBudget(JSONObject()))
        assertEquals(0, VirtualScreenSettle.captureWaitBudget(JSONObject().put("waitMs", JSONObject.NULL)))
        assertEquals(7_500, VirtualScreenSettle.captureWaitBudget(JSONObject().put("waitMs", 7_500)))
        for (bad in listOf<Any>(-1, 10_001, 12.5, "5000", false)) {
            assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenSettle.captureWaitBudget(JSONObject().put("waitMs", bad))
            }
        }
    }

    @Test
    fun `低帧档位放宽静止判定阈值以免把采集空档误判为稳定`() {
        assertEquals(300L, VirtualScreenSettle.quietMillis(16))
        assertEquals(300L, VirtualScreenSettle.quietMillis(66))
        assertEquals(540L, VirtualScreenSettle.quietMillis(180))
    }

    @Test
    fun `等待步进正确区分未出帧-仍在变化-已稳定与超时`() {
        val inputAt = 1_000L
        val deadline = 4_000L
        val quiet = 300L

        // 1. 还没出过输入后的新帧：deadline 前继续等，到 deadline 报 frameAfterInput=false。
        assertEquals(
            VirtualScreenSettle.Step.Wait(50L),
            VirtualScreenSettle.step(now = 1_500L, inputAt = inputAt, frameAt = 900L, deadline = deadline, quiet = quiet),
        )
        assertEquals(
            VirtualScreenSettle.Step.Done(frameAfterInput = false, quiet = false),
            VirtualScreenSettle.step(now = 4_000L, inputAt = inputAt, frameAt = 1_000L, deadline = deadline, quiet = quiet),
        )

        // 2. 输入后刚出新帧但静止时间不够：继续等（受 quiet-idle 约束）。
        assertEquals(
            VirtualScreenSettle.Step.Wait(50L),
            VirtualScreenSettle.step(now = 1_250L, inputAt = inputAt, frameAt = 1_100L, deadline = deadline, quiet = quiet),
        )
        assertEquals(
            VirtualScreenSettle.Step.Wait(20L),
            VirtualScreenSettle.step(now = 1_380L, inputAt = inputAt, frameAt = 1_100L, deadline = deadline, quiet = quiet),
        )

        // 3. 静止时长已满足：提前结束并返回 quiet=true。
        assertEquals(
            VirtualScreenSettle.Step.Done(frameAfterInput = true, quiet = true),
            VirtualScreenSettle.step(now = 1_400L, inputAt = inputAt, frameAt = 1_100L, deadline = deadline, quiet = quiet),
        )

        // 4. 出了新帧但直到超时画面仍在刷新：报 frameAfterInput=true, quiet=false。
        assertEquals(
            VirtualScreenSettle.Step.Done(frameAfterInput = true, quiet = false),
            VirtualScreenSettle.step(now = 4_000L, inputAt = inputAt, frameAt = 3_900L, deadline = deadline, quiet = quiet),
        )
    }

    @Test
    fun `等待回执只在确实观察到新帧时写首帧延迟`() {
        val settled = VirtualScreenSettle.outcome(
            budget = 3_000,
            inputAt = 1_000L,
            firstFrameAt = 1_220L,
            frameAt = 1_350L,
            finishedAt = 1_650L,
            done = VirtualScreenSettle.Step.Done(frameAfterInput = true, quiet = true),
        )
        assertEquals(3_000, settled.getInt("budgetMs"))
        assertEquals(650L, settled.getLong("waitedMs"))
        assertTrue(settled.getBoolean("frameAfterInput"))
        assertTrue(settled.getBoolean("quiet"))
        assertEquals(220L, settled.getLong("firstFrameDelayMs"))
        assertFalse(settled.has("aborted"))

        val missed = VirtualScreenSettle.outcome(
            budget = 3_000,
            inputAt = 1_000L,
            firstFrameAt = 0L,
            frameAt = 800L,
            finishedAt = 4_000L,
            done = VirtualScreenSettle.Step.Done(frameAfterInput = false, quiet = false),
            aborted = true,
        )
        assertFalse(missed.getBoolean("frameAfterInput"))
        assertFalse(missed.has("firstFrameDelayMs"))
        assertTrue(missed.getBoolean("aborted"))
    }
}
