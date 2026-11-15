package io.deepseekharness.mobile.runtime

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.AppForeground
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 走真实收单入口；保持前台以免投递通知，不申请权限或启动 Harness。 */
@RunWith(AndroidJUnit4::class)
class TaskNotificationLifecycleTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        AppForeground.onActivityStarted()
        TaskNotification.setHarnessRun(context, RUN_ONE)
    }

    @After
    fun cleanup() {
        try {
            TaskNotification.setHarnessRun(context, null)
        } finally {
            AppForeground.onActivityStopped()
        }
    }

    @Test
    fun duplicateAndOutOfOrderSignalsDoNotReviveOrCloseAnotherTask() {
        assertTrue(start(SESSION_ONE, 2).accepted)
        assertIgnored(start(SESSION_ONE, 2))
        assertIgnored(start(SESSION_ONE, 1))
        assertIgnored(end(SESSION_ONE, 1))
        assertEquals(1, activeCount())

        assertTrue(start(SESSION_TWO, 1).accepted)
        assertEquals(2, activeCount())
        assertTrue(end(SESSION_ONE, 2).ok)
        assertEquals(1, activeCount())
        assertIgnored(end(SESSION_ONE, 2))
        assertIgnored(start(SESSION_ONE, 2))
        assertEquals(1, activeCount())

        assertTrue(end(SESSION_TWO, 1, reason = "turn-error").ok)
        assertEquals(0, activeCount())
        // 完成先于开始到达：同轮开始即使随后到达也不能重新点亮。
        assertTrue(end(SESSION_ONE, 3).ok)
        assertIgnored(start(SESSION_ONE, 3))
        assertEquals(0, activeCount())
        assertTrue(start(SESSION_ONE, 4).accepted)
        assertIgnored(end(SESSION_ONE, 3))
        assertEquals(1, activeCount())
        assertTrue(end(SESSION_ONE, 4, reason = "turn-aborted").ok)
        assertEquals(0, activeCount())
    }

    @Test
    fun replacingOrStoppingRunRejectsLateSignals() {
        assertTrue(start(SESSION_ONE, 1).accepted)
        TaskNotification.setHarnessRun(context, RUN_TWO)
        assertEquals(0, activeCount())
        assertIgnored(start(SESSION_ONE, 2, run = RUN_ONE))
        assertIgnored(end(SESSION_ONE, 1, run = RUN_ONE))
        assertEquals(0, activeCount())

        assertTrue(start(SESSION_ONE, 1, run = RUN_TWO).accepted)
        // 旧进程的完成信号不能替新进程收口，即使会话和轮次相同。
        assertIgnored(end(SESSION_ONE, 1, run = RUN_ONE))
        assertEquals(1, activeCount())
        TaskNotification.setHarnessRun(context, null)
        assertEquals(0, activeCount())
        assertIgnored(start(SESSION_TWO, 1, run = RUN_TWO))
        assertIgnored(end(SESSION_ONE, 1, run = RUN_TWO))
        assertIgnored(TaskNotification.recordTurnCompleted(context, ""))
        assertEquals(0, activeCount())
    }

    @Test
    fun throttledCompletionStillClosesRunningTask() {
        // 收单限流是进程级状态，等待上一条测试留下的最小间隔。
        SystemClock.sleep(TurnCompletionPolicy.MIN_INTERVAL_MS + 50L)
        assertTrue(start(SESSION_ONE, 1).accepted)
        assertTrue(start(SESSION_TWO, 1).accepted)
        assertEquals(2, activeCount())
        val first = end(SESSION_ONE, 1)
        assertTrue(first.accepted)
        assertFalse(first.queued) // 前台只更新状态。
        val sequence = TaskNotification.turnCompletionSequence

        val throttled = end(SESSION_TWO, 1, reason = "turn-blocked")
        assertTrue(throttled.ok)
        assertFalse(throttled.accepted)
        assertNotNull(throttled.dropped)
        assertFalse(throttled.queued)
        assertEquals(sequence, TaskNotification.turnCompletionSequence)
        assertEquals(0, activeCount())
        assertIgnored(start(SESSION_TWO, 1))
        assertEquals(0, activeCount())
    }

    private fun activeCount(): Int = TaskNotification.activeTurnSnapshot().activeCount

    private fun start(session: String, turn: Long, run: String = RUN_ONE) =
        TaskNotification.recordTurnStarted(context, signal(session, turn, "turn-start", run))

    private fun end(session: String, turn: Long, run: String = RUN_ONE, reason: String = "turn-end") =
        TaskNotification.recordTurnCompleted(context, signal(session, turn, reason, run))

    private fun signal(session: String, turn: Long, reason: String, run: String): String =
        """{"session":"$session","turn":$turn,"reason":"$reason","run":"$run"}"""

    private fun assertIgnored(receipt: TaskNotification.TurnCompletionReceipt) {
        assertTrue(receipt.ok)
        assertFalse(receipt.accepted)
        assertNull(receipt.errorCode)
        assertNull(receipt.dropped)
        assertFalse(receipt.queued)
    }

    companion object {
        private const val RUN_ONE = "0123456789abcdef"
        private const val RUN_TWO = "fedcba9876543210"
        private const val SESSION_ONE = "1111111111111111"
        private const val SESSION_TWO = "2222222222222222"
    }
}
