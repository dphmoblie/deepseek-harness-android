package io.deepseekharness.mobile.accessibility

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AutomationTaskWire] 的用例：入参形状、缺省值、回执字段。 */
class AutomationTaskWireTest {
    @Test
    fun `空参数按 status 处理`() {
        assertEquals(AutomationTaskRequest.Status, AutomationTaskWire.parse(""))
        assertEquals(AutomationTaskRequest.Status, AutomationTaskWire.parse("   "))
        assertEquals(AutomationTaskRequest.Status, AutomationTaskWire.parse("""{"op":"status"}"""))
        assertEquals(AutomationTaskRequest.Cancel, AutomationTaskWire.parse("""{"op":"cancel"}"""))
    }

    @Test
    fun `参数不是 JSON 或 op 不受支持时拒绝并列全允许的 op`() {
        val broken = AutomationTaskWire.parse("不是 JSON") as AutomationTaskRequest.Invalid
        assertEquals(AutomationTaskCodes.PARAM_INVALID, broken.code)

        val unknown = AutomationTaskWire.parse("""{"op":"run"}""") as AutomationTaskRequest.Invalid
        assertEquals(AutomationTaskCodes.PARAM_INVALID, unknown.code)
        AutomationTaskWire.OPS.forEach { assertTrue("消息里应列出 $it：${unknown.message}", unknown.message.contains(it)) }

        val missing = AutomationTaskWire.parse("{}") as AutomationTaskRequest.Invalid
        assertEquals(AutomationTaskCodes.PARAM_INVALID, missing.code)
    }

    @Test
    fun `plan 的缺省值按动作给`() {
        val request = plan(
            """{"id":"s1","action":"click","target":"com.example:id/ok"}""",
            """{"id":"s2","action":"wait"}""",
        )
        assertEquals("t1", request.taskId)
        assertEquals("com.example.app", request.packageName)
        assertEquals(listOf("s1", "s2"), request.steps.map { it.id })
        assertEquals(AutomationTaskActions.VERIFY_WINDOW_CHANGED, request.steps[0].verify)
        assertEquals(AutomationTaskWire.DEFAULT_TIMEOUT_MS, request.steps[0].timeoutMs)
        assertEquals(AutomationTaskWire.DEFAULT_MAX_ATTEMPTS, request.steps[0].maxAttempts)
        // wait 步骤可以不写目标：默认校验是 none，且不接受目标。
        assertEquals(AutomationTaskActions.VERIFY_NONE, request.steps[1].verify)
        assertEquals("", request.steps[1].target)
    }

    @Test
    fun `plan 接受显式校验模式 超时与重试上限`() {
        val request = plan(
            """{"id":"s1","action":"setText","target":"com.example:id/input","verify":"windowChanged","timeoutMs":9000,"maxAttempts":3}""",
        )
        val step = request.steps.single()
        assertEquals(AutomationTaskActions.VERIFY_WINDOW_CHANGED, step.verify)
        assertEquals(9_000L, step.timeoutMs)
        assertEquals(3, step.maxAttempts)
    }

    @Test
    fun `plan 拒绝小数的整数字段而不是静默截断`() {
        val fractionalTimeout = AutomationTaskWire.parse(
            """{"op":"plan","taskId":"t1","packageName":"com.example.app","steps":[{"id":"s1","action":"click","target":"x","timeoutMs":5000.9}]}""",
        ) as AutomationTaskRequest.Invalid
        assertEquals(AutomationTaskCodes.PARAM_INVALID, fractionalTimeout.code)

        val fractionalAttempts = AutomationTaskWire.parse(
            """{"op":"plan","taskId":"t1","packageName":"com.example.app","steps":[{"id":"s1","action":"click","target":"x","maxAttempts":1.9}]}""",
        ) as AutomationTaskRequest.Invalid
        assertEquals(AutomationTaskCodes.PARAM_INVALID, fractionalAttempts.code)
    }

    @Test
    fun `plan 缺字段或形状不对时逐条拒绝`() {
        val cases = mapOf(
            """{"op":"plan","packageName":"com.example.app","steps":[{"id":"s1","action":"click","target":"x"}]}""" to "taskId",
            """{"op":"plan","taskId":"t1","steps":[{"id":"s1","action":"click","target":"x"}]}""" to "packageName",
            """{"op":"plan","taskId":"t1","packageName":"com.example.app","steps":[]}""" to "steps",
            """{"op":"plan","taskId":"t1","packageName":"com.example.app","steps":"s1"}""" to "steps",
            """{"op":"plan","taskId":"t1","packageName":"com.example.app","steps":["s1"]}""" to "步骤",
            """{"op":"plan","taskId":"t1","packageName":"com.example.app","steps":[{"action":"click","target":"x"}]}""" to "id",
            """{"op":"plan","taskId":"t1","packageName":"com.example.app","steps":[{"id":"s1"}]}""" to "action",
        )
        cases.forEach { (param, expected) ->
            val invalid = AutomationTaskWire.parse(param) as AutomationTaskRequest.Invalid
            assertEquals(AutomationTaskCodes.PARAM_INVALID, invalid.code)
            assertTrue("「$param」的消息应提到 $expected：${invalid.message}", invalid.message.contains(expected))
        }
    }

    @Test
    fun `gate 与 resume 的步骤参数`() {
        assertEquals(
            "s2",
            (AutomationTaskWire.parse("""{"op":"gate","step":"s2"}""") as AutomationTaskRequest.Gate).stepId,
        )
        assertEquals(
            AutomationTaskCodes.PARAM_INVALID,
            (AutomationTaskWire.parse("""{"op":"gate"}""") as AutomationTaskRequest.Invalid).code,
        )
        assertEquals(
            listOf("s1", "s2"),
            (AutomationTaskWire.parse("""{"op":"resume","steps":["s1","s2"]}""") as AutomationTaskRequest.Resume).stepIds,
        )
        assertEquals(
            AutomationTaskCodes.PARAM_INVALID,
            (AutomationTaskWire.parse("""{"op":"resume","steps":[]}""") as AutomationTaskRequest.Invalid).code,
        )
        assertEquals(
            AutomationTaskCodes.PARAM_INVALID,
            (AutomationTaskWire.parse("""{"op":"resume","steps":["s1",3]}""") as AutomationTaskRequest.Invalid).code,
        )
    }

    @Test
    fun `record 只接受三种结果 skipped 不许调用方自己报`() {
        val ok = AutomationTaskWire.parse(
            """{"op":"record","step":"s1","status":"ok","code":"AUTOMATION_ACTION_PERFORMED"}""",
        ) as AutomationTaskRequest.Record
        assertEquals("s1", ok.stepId)
        assertTrue(ok.outcome.ok)
        assertEquals("AUTOMATION_ACTION_PERFORMED", ok.outcome.code)
        // 没写 verified 时：报成功按「通过校验」算，报失败按「没通过」算——宁可保守。
        assertTrue(ok.outcome.verified)
        assertFalse(ok.outcome.timedOut)

        val failed = AutomationTaskWire.parse(
            """{"op":"record","step":"s1","status":"failed","code":"AUTOMATION_CLICK_FAILED"}""",
        ) as AutomationTaskRequest.Record
        assertFalse(failed.outcome.ok)
        assertFalse(failed.outcome.verified)

        val unknown = AutomationTaskWire.parse(
            """{"op":"record","step":"s1","status":"unknown","verified":false,"timedOut":true}""",
        ) as AutomationTaskRequest.Record
        assertFalse(unknown.outcome.ok)
        assertTrue(unknown.outcome.timedOut)

        listOf(
            """{"op":"record","step":"s1"}""",
            """{"op":"record","step":"s1","status":"skipped"}""",
        ).forEach { param ->
            val invalid = AutomationTaskWire.parse(param) as AutomationTaskRequest.Invalid
            assertEquals(AutomationTaskCodes.RECORD_INVALID, invalid.code)
        }
        // 缺 step 是参数形状问题，与 status 取值无关：两者刻意用不同的码。
        assertEquals(
            AutomationTaskCodes.PARAM_INVALID,
            (AutomationTaskWire.parse("""{"op":"record","status":"ok"}""") as AutomationTaskRequest.Invalid).code,
        )
    }

    @Test
    fun `放行回执带步骤 尝试次数与台账快照`() {
        val json = JSONObject(AutomationTaskWire.accepted(AutomationTaskGate.Proceed("s2", 1), snapshot()))
        assertTrue(json.getBoolean("ok"))
        assertTrue(json.isNull("code"))
        assertEquals("s2", json.getString("step"))
        assertEquals(1, json.getInt("attempt"))
        assertFalse(json.getBoolean("retryable"))
        val task = json.getJSONObject("task")
        assertEquals("t1", task.getString("taskId"))
        assertEquals(AutomationTaskStatus.RUNNING, task.getString("status"))
        assertEquals("s2", task.getString("nextStep"))
        assertEquals(1, task.getInt("settledSteps"))
        assertEquals(1, task.getInt("pendingSteps"))
        val steps = task.getJSONArray("steps")
        assertEquals(2, steps.length())
        assertEquals("s1", steps.getJSONObject(0).getString("id"))
        assertEquals(AutomationTaskStepStatus.OK, steps.getJSONObject(0).getString("status"))
        assertTrue(steps.getJSONObject(1).isNull("code"))
        assertEquals("这一步没看出效果", task.getString("lastMessage"))
    }

    @Test
    fun `拒绝回执带码 可重试标记与台账快照 没有台账时不带 task 键`() {
        val json = JSONObject(
            AutomationTaskWire.refused(AutomationTaskCodes.TARGET_STALE, "界面已经变了", false, snapshot()),
        )
        assertFalse(json.getBoolean("ok"))
        assertEquals(AutomationTaskCodes.TARGET_STALE, json.getString("code"))
        assertFalse(json.getBoolean("retryable"))
        assertTrue(json.has("task"))

        val without = JSONObject(
            AutomationTaskWire.refused(AutomationTaskCodes.PARAM_INVALID, "参数不对", false, null),
        )
        assertFalse(without.has("task"))

        val retryable = JSONObject(
            AutomationTaskWire.refused("ACCESSIBILITY_RATE_LIMITED", "太频繁", true, null),
        )
        assertTrue(retryable.getBoolean("retryable"))
    }

    @Test
    fun `记账回执与形状错误回执`() {
        val done = JSONObject(AutomationTaskWire.done(snapshot()))
        // 命令受理成功，但「这一步成不成」看 steps 与 lastCode：这里如实带出来。
        assertTrue(done.getBoolean("ok"))
        assertEquals(AutomationTaskCodes.NOT_VERIFIED, done.getString("code"))
        assertEquals("这一步没看出效果", done.getString("message"))
        assertEquals(2, done.getJSONObject("task").getJSONArray("steps").length())

        val invalid = JSONObject(AutomationTaskWire.invalid(AutomationTaskCodes.PARAM_INVALID, "缺少 op"))
        assertFalse(invalid.getBoolean("ok"))
        assertEquals(AutomationTaskCodes.PARAM_INVALID, invalid.getString("code"))
        assertFalse(invalid.has("task"))
    }

    private fun plan(vararg steps: String): AutomationTaskRequest.Plan {
        val param = """{"op":"plan","taskId":"t1","packageName":"com.example.app","steps":[${steps.joinToString(",")}]}"""
        return AutomationTaskWire.parse(param) as AutomationTaskRequest.Plan
    }

    private fun snapshot(): AutomationTaskSnapshot = AutomationTaskSnapshot(
        taskId = "t1",
        status = AutomationTaskStatus.RUNNING,
        packageName = "com.example.app",
        steps = listOf(
            AutomationTaskStepState(
                id = "s1",
                action = AutomationTaskActions.CLICK,
                verify = AutomationTaskActions.VERIFY_WINDOW_CHANGED,
                timeoutMs = 5_000L,
                maxAttempts = 1,
                status = AutomationTaskStepStatus.OK,
                attemptsUsed = 1,
                code = "AUTOMATION_ACTION_PERFORMED",
            ),
            AutomationTaskStepState(
                id = "s2",
                action = AutomationTaskActions.SET_TEXT,
                verify = AutomationTaskActions.VERIFY_TEXT_APPLIED,
                timeoutMs = 5_000L,
                maxAttempts = 2,
            ),
        ),
        nextStepId = "s2",
        lastCode = AutomationTaskCodes.NOT_VERIFIED,
        lastMessage = "这一步没看出效果",
    )
}
