package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `target` 动作的参数面：确认预算、预热、回滚，以及失败原因（reason）与错误码的分工。
 *
 * 真机现象：目标切换只有一句「超时」，调用方无法区分「系统拒绝了这次启动」「应用起来了但没到副屏前台」
 * 与「会话已经失效」——三者该做的事完全不同（换入口 / 再等 / 重开会话）。
 */
class VirtualScreenTargetRequestTest {
    @Test fun `确认预算默认三秒并夹取到半秒到十五秒`() {
        assertEquals(3000L, VirtualScreenPolicy.CONFIRM_BUDGET_DEFAULT_MILLIS)
        assertEquals(500L, VirtualScreenPolicy.CONFIRM_BUDGET_MIN_MILLIS)
        assertEquals(15000L, VirtualScreenPolicy.CONFIRM_BUDGET_MAX_MILLIS)
        assertEquals("confirm_budget_ms", VirtualScreenPolicy.CONFIRM_BUDGET_FIELD)

        assertEquals(3000L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject()))
        assertEquals(3000L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, JSONObject.NULL)))
        assertEquals(8000L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, 8000)))
        // 越界是夹取而不是报错：预算是性能旋钮，给超范围的值仍然能跑，直接拒会让调用方白改一轮参数。
        assertEquals(500L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, 100)))
        assertEquals(500L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, 0)))
        assertEquals(500L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, -3000)))
        assertEquals(15000L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, 60_000)))
        assertEquals(500L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, 500)))
        assertEquals(15000L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, 15000)))
        // 小数取整到毫秒；非数字类型是真正的调用错误。
        assertEquals(2500L, VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, 2500.9)))
        for (value in listOf<Any>("3000", true, JSONObject())) {
            assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenPolicy.confirmBudgetMillis(JSONObject().put(VirtualScreenPolicy.CONFIRM_BUDGET_FIELD, value))
            }
        }
    }

    @Test fun `预热与回滚是布尔量 原因字段固定为 reason`() {
        assertEquals("prewarm", VirtualScreenPolicy.PREWARM_FIELD)
        assertEquals("rollback", VirtualScreenPolicy.ROLLBACK_FIELD)
        assertEquals("reason", VirtualScreenPolicy.SWITCH_REASON_FIELD)
    }

    @Test fun `失败原因三类互斥且不是顶层错误码`() {
        assertEquals("NOT_ACCEPTED", VirtualScreenPolicy.REASON_NOT_ACCEPTED)
        assertEquals("NOT_FOREGROUND", VirtualScreenPolicy.REASON_NOT_FOREGROUND)
        assertEquals("SESSION_DEAD", VirtualScreenPolicy.REASON_SESSION_DEAD)

        // 会话类：错误码本身就说明会话不再可用。
        assertEquals(VirtualScreenPolicy.REASON_SESSION_DEAD, VirtualScreenPolicy.switchFailureReason(RuntimeFailure(VirtualScreenPolicy.SESSION_DEAD_CODE, "副屏会话已失效")))
        assertEquals(VirtualScreenPolicy.REASON_SESSION_DEAD, VirtualScreenPolicy.switchFailureReason(RuntimeFailure("VIRTUAL_SCREEN_STOPPED", "副屏会话已切换")))
        assertEquals(VirtualScreenPolicy.REASON_SESSION_DEAD, VirtualScreenPolicy.switchFailureReason(RuntimeFailure("VIRTUAL_SCREEN_BUSY", "尚未取得副屏显示编号")))

        // 入口不成立：解析不到启动入口，或 am start 被系统拒绝。
        assertEquals(VirtualScreenPolicy.REASON_NOT_ACCEPTED, VirtualScreenPolicy.switchFailureReason(RuntimeFailure(VirtualScreenPolicy.LAUNCH_UNRESOLVED_CODE, "未能在设备上解析到 com.x 的启动入口")))
        assertEquals(VirtualScreenPolicy.REASON_NOT_ACCEPTED, VirtualScreenPolicy.switchFailureReason(RuntimeFailure(VirtualScreenPolicy.LAUNCH_FAILED_CODE, "启动命令未成功执行")))
        assertEquals(VirtualScreenPolicy.REASON_NOT_ACCEPTED, VirtualScreenPolicy.switchFailureReason(IllegalStateException("系统命令未成功执行")))
        assertEquals(VirtualScreenPolicy.REASON_NOT_ACCEPTED, VirtualScreenPolicy.switchFailureReason(IllegalStateException("系统拒绝副屏命令")))

        // 其余（确认超时、轮询里冒出来的异常）都属于「还没到前台」。
        assertEquals(VirtualScreenPolicy.REASON_NOT_FOREGROUND, VirtualScreenPolicy.switchFailureReason(RuntimeFailure(VirtualScreenPolicy.TARGET_SWITCH_TIMEOUT_CODE, VirtualScreenPolicy.TARGET_SWITCH_TIMEOUT_MESSAGE)))
        assertEquals(VirtualScreenPolicy.REASON_NOT_FOREGROUND, VirtualScreenPolicy.switchFailureReason(IllegalStateException("目标应用已离开副屏或系统无法确认其状态")))
        assertEquals(VirtualScreenPolicy.REASON_NOT_FOREGROUND, VirtualScreenPolicy.switchFailureReason(IllegalArgumentException("副屏参数过长")))

        // reason 是**载荷字段**而不是新的顶层错误码：带 VIRTUAL_SCREEN_ 前缀会被调用方当错误码用。
        for (reason in listOf(
            VirtualScreenPolicy.REASON_NOT_ACCEPTED,
            VirtualScreenPolicy.REASON_NOT_FOREGROUND,
            VirtualScreenPolicy.REASON_SESSION_DEAD,
        )) {
            assertFalse(reason.startsWith("VIRTUAL_SCREEN_"))
        }
    }

    @Test fun `目标入口接受组件或包名两种写法且必须二选一`() {
        assertEquals("component", VirtualScreenPolicy.TARGET_FIELD)
        assertEquals("packageName", VirtualScreenPolicy.TARGET_PACKAGE_FIELD)

        // 组件写法原样返回（设备侧不需要再解析）。
        assertEquals(
            "com.tencent.mm/.ui.LauncherUI",
            VirtualScreenPolicy.targetRequestField(JSONObject().put("component", "com.tencent.mm/.ui.LauncherUI")),
        )
        // 包名写法（AI 工具面用的就是这种）返回包名，由执行层解析启动入口。
        assertEquals(
            "com.tencent.mm",
            VirtualScreenPolicy.targetRequestField(JSONObject().put("packageName", "com.tencent.mm")),
        )
        // 两种都给、都不给都算意图不明；非法写法沿用各自的既有校验。
        for (request in listOf(
            JSONObject(),
            JSONObject().put("component", "com.a/.Main").put("packageName", "com.b"),
            JSONObject().put("packageName", "bad-name"),
            JSONObject().put("packageName", ""),
            JSONObject().put("packageName", "com.example/.Main"),
            JSONObject().put("component", "com.example"),
        )) {
            assertThrows("$request 必须被拒绝", IllegalArgumentException::class.java) {
                VirtualScreenPolicy.targetRequestField(request)
            }
        }
    }

    @Test fun `单字段写法与既有组件解析保持同一套规则`() {
        // 老契约（component 字段 + targetRequest）不能被改动：宿主设置页仍在用它。
        assertEquals("com.example/.Main", VirtualScreenPolicy.targetRequest(JSONObject().put("component", "com.example/.Main")))
        assertEquals(
            VirtualScreenPolicy.targetRequest(JSONObject().put("component", "com.example.target/.MainActivity")),
            VirtualScreenPolicy.targetRequestField(JSONObject().put("component", "com.example.target/.MainActivity")),
        )
        for (value in listOf("a/b", "com.example/", "com.example/.Main;id", "com.example/.Main\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenPolicy.targetRequestField(JSONObject().put("component", value))
            }
        }
        assertTrue(VirtualScreenPolicy.validPackageName("com.tencent.mm"))
        assertFalse(VirtualScreenPolicy.validPackageName("com.tencent.mm/.ui.LauncherUI"))
    }
}
