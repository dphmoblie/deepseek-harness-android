package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行记录与拦截原因聚合的**受控搬运**。
 *
 * 这里验的是两件事：①从执行结果里只搬受控码与布尔（界面文本、节点路径、坐标、指纹一个都不带），
 * ②环形缓冲的取舍与排序稳定。两者都是纯逻辑，挂在设备上每次都要真跑一条规则才看得见。
 */
class AutomationRunLogTest {

    private fun result(
        ruleId: String = "r1",
        action: String = "click",
        code: String = AutomationGateCodes.ACTION_PERFORMED,
        ok: Boolean = true,
        performed: Boolean = true,
        degraded: Boolean = false,
    ) = AutomationActionResult(
        ruleId = ruleId,
        action = action,
        code = code,
        ok = ok,
        performed = performed,
        degraded = degraded,
        reason = "示例界面里的中文说明，不该被搬进记录",
        fingerprint = "fp-123456",
        nodePathText = "0.1.3",
        bounds = AutomationBounds(10, 20, 30, 40),
        quotaUsed = if (ok) 1 else null,
        failureStreak = if (ok) null else 2,
    )

    private fun skip(ruleId: String, code: String) = AutomationSkip(ruleId = ruleId, code = code, reason = "示例界面里的中文说明")

    @Test
    fun `记录只搬运受控字段`() {
        val report = AutomationExecutionReport(
            results = listOf(result(ruleId = "r1", action = "click", code = AutomationGateCodes.ACTION_PERFORMED)),
            skipped = emptyList(),
        )

        val records = AutomationRunLogPolicy.records(report, nowMs = 1_700_000_000_000L)
        assertEquals(1, records.size)
        // 逐字段相等：多带任何一个界面相关字段都会让这条断言失败（数据类比较不看字段名以外的约定）。
        assertEquals(
            AutomationRunRecord(
                atMs = 1_700_000_000_000L,
                ruleId = "r1",
                action = "click",
                code = AutomationGateCodes.ACTION_PERFORMED,
                ok = true,
                performed = true,
                degraded = false,
            ),
            records.single(),
        )
    }

    @Test
    fun `试运行也留下记录但标记为未执行`() {
        val report = AutomationExecutionReport(
            results = listOf(result(code = AutomationGateCodes.DRY_RUN, ok = true, performed = false)),
        )

        val record = AutomationRunLogPolicy.records(report, nowMs = 1L).single()
        assertFalse("试运行没有真的动过界面，界面不能显示成已执行", record.performed)
        assertEquals(AutomationGateCodes.DRY_RUN, record.code)
    }

    @Test
    fun `记录条数受上限约束且零表示不记`() {
        val report = AutomationExecutionReport(
            results = listOf(result(ruleId = "a"), result(ruleId = "b"), result(ruleId = "c")),
        )

        assertEquals(2, AutomationRunLogPolicy.records(report, nowMs = 1L, limit = 2).size)
        assertEquals(listOf("a", "b"), AutomationRunLogPolicy.records(report, nowMs = 1L, limit = 2).map { it.ruleId })
        assertTrue(AutomationRunLogPolicy.records(report, nowMs = 1L, limit = 0).isEmpty())
        // 负数按 0 处理，不能抛异常（桥接参数由前端给，可能传 -1）。
        assertTrue(AutomationRunLogPolicy.records(report, nowMs = 1L, limit = -1).isEmpty())
    }

    @Test
    fun `拦截原因按次数降序同数按码升序`() {
        val report = AutomationExecutionReport(
            skipped = listOf(
                skip("r1", AutomationGateCodes.SENSITIVE_WINDOW),
                skip("r2", AutomationGateCodes.EVENT_THROTTLED),
                skip("r3", AutomationGateCodes.EVENT_THROTTLED),
                skip("r4", AutomationGateCodes.EVENT_THROTTLED),
                skip("r5", AutomationGateCodes.DEVICE_LOCKED),
                skip("r6", AutomationGateCodes.DEVICE_LOCKED),
            ),
        )

        assertEquals(
            listOf(
                AutomationBlockedReason(AutomationGateCodes.EVENT_THROTTLED, 3),
                AutomationBlockedReason(AutomationGateCodes.DEVICE_LOCKED, 2),
                AutomationBlockedReason(AutomationGateCodes.SENSITIVE_WINDOW, 1),
            ),
            AutomationRunLogPolicy.blocked(report),
        )
        // 只取前 N 条时仍按同一顺序截断（界面显示「最多的几种原因」）。
        assertEquals(2, AutomationRunLogPolicy.blocked(report, limit = 2).size)
        assertTrue(AutomationRunLogPolicy.blocked(report, limit = 0).isEmpty())
        assertTrue(AutomationRunLogPolicy.blocked(AutomationExecutionReport.EMPTY).isEmpty())
    }

    @Test
    fun `按包名分桶且最新在前`() {
        val history = AutomationRunHistory()
        history.record("com.example.a", AutomationExecutionReport(results = listOf(result(ruleId = "a1"))), nowMs = 100L)
        history.record("com.example.b", AutomationExecutionReport(results = listOf(result(ruleId = "b1"))), nowMs = 200L)
        history.record("com.example.a", AutomationExecutionReport(results = listOf(result(ruleId = "a2"))), nowMs = 300L)

        assertEquals(listOf("com.example.a", "com.example.b"), history.packages())
        assertEquals(listOf("a2", "a1"), history.runs("com.example.a").map { it.ruleId })
        assertEquals(listOf("b1"), history.runs("com.example.b").map { it.ruleId })
        // 不带包名即跨包合并，仍然最新在前。
        assertEquals(listOf("a2", "b1", "a1"), history.runs().map { it.ruleId })
        assertTrue(history.runs("com.example.missing").isEmpty())
        assertEquals(listOf(300L, 200L, 100L), history.runs(limit = 3).map { it.atMs })
    }

    @Test
    fun `超出容量丢弃最旧的并计数`() {
        val history = AutomationRunHistory(capacity = 2)
        for (index in 1..4) {
            history.record("com.example.a", AutomationExecutionReport(results = listOf(result(ruleId = "r$index"))), nowMs = index.toLong())
        }

        assertEquals(2, history.limit)
        assertEquals(listOf("r4", "r3"), history.runs("com.example.a").map { it.ruleId })
        assertEquals("界面要能如实说明丢了多少条", 2, history.droppedCount)
    }

    @Test
    fun `空包名与零容量都不产生记录`() {
        val history = AutomationRunHistory()
        history.record("", AutomationExecutionReport(results = listOf(result())), nowMs = 1L)
        assertTrue(history.packages().isEmpty())
        assertTrue(history.runs().isEmpty())

        val zero = AutomationRunHistory(capacity = 0)
        zero.record("com.example.a", AutomationExecutionReport(results = listOf(result())), nowMs = 1L)
        assertTrue(zero.runs().isEmpty())
        assertEquals(0, zero.droppedCount)
    }

    @Test
    fun `拦截原因跨包聚合且按同样顺序`() {
        val history = AutomationRunHistory()
        history.record(
            "com.example.a",
            AutomationExecutionReport(skipped = listOf(skip("r1", AutomationGateCodes.SENSITIVE_WINDOW))),
            nowMs = 1L,
        )
        history.record(
            "com.example.b",
            AutomationExecutionReport(
                skipped = listOf(
                    skip("r2", AutomationGateCodes.SENSITIVE_WINDOW),
                    skip("r3", AutomationGateCodes.SENSITIVE_WINDOW),
                    skip("r4", AutomationGateCodes.DEVICE_LOCKED),
                ),
            ),
            nowMs = 2L,
        )

        assertEquals(
            listOf(
                AutomationBlockedReason(AutomationGateCodes.SENSITIVE_WINDOW, 3),
                AutomationBlockedReason(AutomationGateCodes.DEVICE_LOCKED, 1),
            ),
            history.blocked(),
        )
        assertEquals(listOf(AutomationBlockedReason(AutomationGateCodes.SENSITIVE_WINDOW, 1)), history.blocked("com.example.a"))
        assertTrue(history.blocked("com.example.missing").isEmpty())
        // 记录与拦截原因是一起清空的：清空后界面不该还显示上一轮的拦截统计。
        history.clear()
        assertTrue(history.packages().isEmpty())
        assertTrue(history.blocked().isEmpty())
        assertTrue(history.runs().isEmpty())
        assertEquals(0, history.droppedCount)
    }

    @Test
    fun `只累计原因不写执行记录`() {
        val history = AutomationRunHistory()
        repeat(3) { history.noteBlocked("com.example.a", AutomationGateCodes.APP_PAUSED) }

        // 按应用暂停拦在规则求值之前：一次执行记录都不该有，也不该因为刷屏把环形缓冲挤满。
        // 这两种错法的表现完全不同——前者会让「最近执行」里冒出没有规则的空行，
        // 后者会把真正的执行历史冲掉、`dropped` 一路涨，而用户会以为是自己看漏了。
        assertTrue(history.runs().isEmpty())
        assertTrue(history.runs("com.example.a").isEmpty())
        assertEquals(0, history.droppedCount)
        assertEquals(
            listOf(AutomationBlockedReason(AutomationGateCodes.APP_PAUSED, 3)),
            history.blocked("com.example.a"),
        )
        // 包名要留下来：界面按当前查看的应用过滤拦截原因，没有桶就等于「这个应用什么都没发生」。
        assertEquals(listOf("com.example.a"), history.packages())
    }

    @Test
    fun `只累计原因的计数与执行记录里的原因合并`() {
        val history = AutomationRunHistory()
        history.record(
            "com.example.a",
            AutomationExecutionReport(skipped = listOf(skip("r1", AutomationGateCodes.SENSITIVE_WINDOW))),
            nowMs = 1L,
        )
        history.noteBlocked("com.example.a", AutomationGateCodes.APP_PAUSED, count = 2)

        // 两条路径写进同一个桶：计数降序（暂停 2 次在前），同数才按码升序。
        assertEquals(
            listOf(
                AutomationBlockedReason(AutomationGateCodes.APP_PAUSED, 2),
                AutomationBlockedReason(AutomationGateCodes.SENSITIVE_WINDOW, 1),
            ),
            history.blocked("com.example.a"),
        )
    }

    @Test
    fun `空包名与非正计数都不记账`() {
        val history = AutomationRunHistory()
        history.noteBlocked("", AutomationGateCodes.APP_PAUSED)
        history.noteBlocked("com.example.a", AutomationGateCodes.APP_PAUSED, count = 0)
        history.noteBlocked("com.example.b", AutomationGateCodes.APP_PAUSED, count = -1)

        // 空包名会造出一条无主记录（服务侧拿不到前台包时不该记）；0 与负数会让卡片显示负次数。
        assertTrue(history.packages().isEmpty())
        assertTrue(history.blocked().isEmpty())
    }
}
