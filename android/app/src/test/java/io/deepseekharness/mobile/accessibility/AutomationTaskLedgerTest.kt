package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单应用任务台账的边界用例。
 *
 * 这里锁的是**分类口径**而不是实现细节：什么情况算「可重试」、什么情况必须记成「不确定」、
 * 目标变了之后还能不能发。这些边界在真机上要靠「制造超时 / 换应用 / 断服务」才能碰一次，
 * 所以它们必须在 JVM 上逐个钉住。
 */
class AutomationTaskLedgerTest {

    private fun spec(
        id: String,
        action: String = AutomationTaskActions.CLICK,
        target: String = "com.example.app:id/ok",
        verify: String = AutomationTaskActions.defaultVerify(action),
        timeoutMs: Long = 5_000L,
        maxAttempts: Int = 1,
    ) = AutomationTaskStepSpec(id, action, target, verify, timeoutMs, maxAttempts)

    private fun facts(
        packageName: String = "com.example.app",
        digest: String = "d1",
        locked: Boolean = false,
        sensitive: Boolean = false,
        allowed: Boolean = true,
        connected: Boolean = true,
    ) = AutomationTaskFacts(packageName, digest, locked, sensitive, allowed, connected)

    private fun ledger(vararg steps: AutomationTaskStepSpec, digest: String? = "d1"): AutomationTaskLedger {
        val ledger = AutomationTaskLedger()
        val gate = ledger.plan("task-1", "com.example.app", digest, steps.toList())
        assertTrue("计划应当被接受：$gate", gate is AutomationTaskGate.Proceed)
        return ledger
    }

    private fun AutomationTaskGate.refusedCode(): String {
        assertTrue("这一步本应被拒：$this", this is AutomationTaskGate.Refused)
        return (this as AutomationTaskGate.Refused).code
    }

    private fun AutomationTaskGate.attempt(): Int {
        assertTrue("这一步本应放行：$this", this is AutomationTaskGate.Proceed)
        return (this as AutomationTaskGate.Proceed).attempt
    }

    @Test
    fun `计划校验把不合法的步骤逐条挡住`() {
        val seen = mutableSetOf<String>()
        assertNull(AutomationTaskPlan.validate(spec("s1"), seen))
        assertTrue(AutomationTaskPlan.validate(spec("S1"), seen)!!.contains("标识不合法"))
        assertTrue(AutomationTaskPlan.validate(spec("s2", action = "swipe"), seen)!!.contains("动作不受支持"))
        assertTrue(AutomationTaskPlan.validate(spec("s3", verify = "targetGone", action = AutomationTaskActions.SCROLL), seen)!!.contains("校验模式"))
        assertTrue(AutomationTaskPlan.validate(spec("s4", target = ""), seen)!!.contains("缺少目标"))
        assertTrue(AutomationTaskPlan.validate(spec("s5", timeoutMs = 50L), seen)!!.contains("超时"))
        assertTrue(AutomationTaskPlan.validate(spec("s6", maxAttempts = 4), seen)!!.contains("重试上限"))
        assertTrue(AutomationTaskPlan.validate(spec("s7", action = AutomationTaskActions.WAIT, target = "x"), seen)!!.contains("不接受目标"))
        assertTrue(AutomationTaskPlan.validate(spec("s1"), mutableSetOf("s1"))!!.contains("重复"))
    }

    @Test
    fun `两步都通过校验时任务收口为完成`() {
        val ledger = ledger(spec("s1"), spec("s2", action = AutomationTaskActions.WAIT, target = ""))
        assertEquals(1, ledger.gate("s1", facts(), 1_000L).attempt())
        ledger.record("s1", AutomationTaskStepOutcome(ok = true, verified = true), 1_200L)
        assertTrue(ledger.gate("s2", facts(), 1_300L) is AutomationTaskGate.Proceed)
        val snapshot = ledger.record("s2", AutomationTaskStepOutcome(ok = true), 1_500L)
        assertEquals(AutomationTaskStatus.DONE, snapshot.status)
        assertEquals(listOf(AutomationTaskStepStatus.OK, AutomationTaskStepStatus.OK), snapshot.steps.map { it.status })
        assertNull(snapshot.nextStepId)
        assertEquals(2, snapshot.settledSteps)
        assertEquals(0, snapshot.pendingSteps)
    }

    @Test
    fun `跳过前一步直接做后一步会被顺序闸门挡住`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        val gate = ledger.gate("s2", facts(), 1_000L)
        assertEquals(AutomationTaskCodes.STEP_OUT_OF_ORDER, gate.refusedCode())
        assertTrue((gate as AutomationTaskGate.Refused).message.contains("s1"))
    }

    @Test
    fun `已经成功的步骤再提交不会执行第二次`() {
        val ledger = ledger(spec("s1", maxAttempts = 3))
        ledger.gate("s1", facts(), 1_000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = true), 1_100L)
        assertEquals(AutomationTaskCodes.STEP_ALREADY_SETTLED, ledger.gate("s1", facts(), 1_200L).refusedCode())
        // 幂等：重复提交不消耗重试次数，也不把状态改回去。
        val snapshot = ledger.snapshot()
        assertEquals(1, snapshot.steps.single().attemptsUsed)
        assertEquals(AutomationTaskStepStatus.OK, snapshot.steps.single().status)
    }

    @Test
    fun `前台换了应用时任务被阻塞并给出恢复路径`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        val gate = ledger.gate("s1", facts(packageName = "com.other.app"), 1_000L)
        assertEquals(AutomationTaskCodes.TARGET_LEFT, gate.refusedCode())
        assertEquals(AutomationTaskStatus.BLOCKED, ledger.snapshot().status)
        // 回到目标应用之后，必须用新的指纹走一次 resume 才能继续（不是默默放行）。
        assertTrue(ledger.resume(listOf("s1", "s2"), "d2") is AutomationTaskGate.Proceed)
        assertEquals(AutomationTaskStatus.RUNNING, ledger.snapshot().status)
        assertTrue(ledger.gate("s1", facts(digest = "d2"), 2_000L) is AutomationTaskGate.Proceed)
    }

    @Test
    fun `计划之后界面就变了时第一步不发出`() {
        val ledger = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.TARGET_STALE, ledger.gate("s1", facts(digest = "d9"), 1_000L).refusedCode())
        assertTrue(ledger.resume(listOf("s1"), "d9") is AutomationTaskGate.Proceed)
        assertTrue(ledger.gate("s1", facts(digest = "d9"), 2_000L) is AutomationTaskGate.Proceed)
    }

    @Test
    fun `恢复时的步骤列表必须与原计划一致`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        val gate = ledger.resume(listOf("s2", "s1"), "d2")
        assertEquals(AutomationTaskCodes.PLAN_INVALID, gate.refusedCode())
    }

    @Test
    fun `明确没生效的失败可以重试一次`() {
        val ledger = ledger(spec("s1", maxAttempts = 2))
        assertEquals(1, ledger.gate("s1", facts(), 1_000L).attempt())
        ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_NODE_UNRESOLVED"), 1_100L)
        val snapshot = ledger.snapshot().steps.single()
        assertEquals(AutomationTaskStepStatus.FAILED, snapshot.status)
        assertEquals("AUTOMATION_NODE_UNRESOLVED", snapshot.code)
        // 现场没变：允许再试，次数记到 2。
        assertEquals(2, ledger.gate("s1", facts(), 1_200L).attempt())
        ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_CLICK_FAILED"), 1_300L)
        // 次数用尽：再提交就是「次数用完」，不是继续试。
        assertEquals(AutomationTaskCodes.ATTEMPTS_EXHAUSTED, ledger.gate("s1", facts(), 1_400L).refusedCode())
    }

    @Test
    fun `失败之后界面又变了时不允许直接重发`() {
        val ledger = ledger(spec("s1", maxAttempts = 3))
        ledger.gate("s1", facts(digest = "d1"), 1_000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_NODE_UNRESOLVED"), 1_100L)
        val gate = ledger.gate("s1", facts(digest = "d2"), 1_200L)
        assertEquals(AutomationTaskCodes.TARGET_STALE, gate.refusedCode())
        assertEquals(AutomationTaskStatus.BLOCKED, ledger.snapshot().status)
    }

    @Test
    fun `认不出的失败码按不安全处理且不许重试`() {
        val ledger = ledger(spec("s1", maxAttempts = 3))
        ledger.gate("s1", facts(), 1_000L)
        val snapshot = ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_SOMETHING_NEW"), 1_100L)
        val step = snapshot.steps.single()
        assertEquals(AutomationTaskStepStatus.UNKNOWN, step.status)
        assertEquals("AUTOMATION_SOMETHING_NEW", step.code)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s1", facts(), 1_200L).refusedCode())
    }

    @Test
    fun `超时记成不确定并且永不自动重试`() {
        val ledger = ledger(spec("s1", maxAttempts = 3))
        ledger.gate("s1", facts(), 1_000L)
        val snapshot = ledger.record("s1", AutomationTaskStepOutcome(ok = false, timedOut = true), 6_500L)
        val step = snapshot.steps.single()
        assertEquals(AutomationTaskStepStatus.UNKNOWN, step.status)
        assertEquals(AutomationTaskCodes.TIMEOUT, step.code)
        assertEquals(5_500L, step.elapsedMs)
        assertFalse(AutomationTaskRetry.mayRetry(step.code, step.attemptsUsed, step.maxAttempts))
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s1", facts(), 7_000L).refusedCode())
    }

    @Test
    fun `发出去了但没有观察到变化时记成不确定`() {
        val ledger = ledger(spec("s1"))
        ledger.gate("s1", facts(), 1_000L)
        val snapshot = ledger.record("s1", AutomationTaskStepOutcome(ok = true, verified = false), 1_400L)
        val step = snapshot.steps.single()
        assertEquals(AutomationTaskStepStatus.UNKNOWN, step.status)
        assertEquals(AutomationTaskCodes.NOT_VERIFIED, step.code)
        assertEquals(AutomationTaskStatus.FAILED, snapshot.status)
    }

    @Test
    fun `不需要校验的等待步骤成功即算完成`() {
        val ledger = ledger(spec("s1", action = AutomationTaskActions.WAIT, target = "", maxAttempts = 1))
        ledger.gate("s1", facts(), 1_000L)
        val snapshot = ledger.record("s1", AutomationTaskStepOutcome(ok = true, verified = false), 1_100L)
        assertEquals(AutomationTaskStepStatus.OK, snapshot.steps.single().status)
        assertEquals(AutomationTaskStatus.DONE, snapshot.status)
    }

    @Test
    fun `取消之后未收口的步骤记为跳过且不再放行`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        ledger.gate("s1", facts(), 1_000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = true), 1_100L)
        val snapshot = ledger.cancel()
        assertEquals(AutomationTaskStatus.CANCELLED, snapshot.status)
        assertEquals(listOf(AutomationTaskStepStatus.OK, AutomationTaskStepStatus.SKIPPED), snapshot.steps.map { it.status })
        assertEquals(AutomationTaskCodes.CANCELLED, snapshot.steps.last().code)
        assertEquals(AutomationTaskCodes.CANCELLED, ledger.gate("s2", facts(), 1_200L).refusedCode())
    }

    @Test
    fun `同一时刻只允许一个未收口的任务`() {
        val ledger = ledger(spec("s1"))
        val gate = ledger.plan("task-2", "com.example.app", "d1", listOf(spec("t1")))
        assertEquals(AutomationTaskCodes.BUSY, gate.refusedCode())
        // 收口之后同一个 id 可以被新计划覆盖。
        ledger.gate("s1", facts(), 1_000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = true), 1_100L)
        assertTrue(ledger.plan("task-1", "com.example.app", "d1", listOf(spec("s9"))) is AutomationTaskGate.Proceed)
    }

    @Test
    fun `锁屏 敏感窗口与白名单拒绝都算场景类并阻塞任务`() {
        val locked = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.DEVICE_LOCKED, locked.gate("s1", facts(locked = true), 1_000L).refusedCode())
        assertEquals(AutomationTaskStatus.BLOCKED, locked.snapshot().status)

        val sensitive = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.SENSITIVE_WINDOW, sensitive.gate("s1", facts(sensitive = true), 1_000L).refusedCode())

        val denied = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.PACKAGE_NOT_ALLOWED, denied.gate("s1", facts(allowed = false), 1_000L).refusedCode())

        val offline = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.SERVICE_DISCONNECTED, offline.gate("s1", facts(connected = false), 1_000L).refusedCode())
    }

    @Test
    fun `步骤过多与计划非法都在计划期被拒`() {
        val ledger = AutomationTaskLedger()
        val many = (1..AutomationTaskPlan.MAX_STEPS + 1).map { spec("s$it") }
        assertEquals(
            AutomationTaskCodes.TOO_MANY_STEPS,
            ledger.plan("task-1", "com.example.app", "d1", many).refusedCode(),
        )
        assertEquals(
            AutomationTaskCodes.PLAN_INVALID,
            ledger.plan("bad id", "com.example.app", "d1", listOf(spec("s1"))).refusedCode(),
        )
        assertEquals(
            AutomationTaskCodes.PLAN_INVALID,
            ledger.plan("task-1", "com.example.app", "d1", emptyList()).refusedCode(),
        )
        assertEquals(
            AutomationTaskCodes.PLAN_INVALID,
            ledger.plan("task-1", "Com.Example.App", "d1", listOf(spec("s1"))).refusedCode(),
        )
    }
}
