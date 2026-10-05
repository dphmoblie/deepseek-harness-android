package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.virtualscreen.VirtualScreenTextPolicy.FailureCode
import io.deepseekharness.mobile.virtualscreen.VirtualScreenTextPolicy.TextAttempt
import io.deepseekharness.mobile.virtualscreen.VirtualScreenTextPolicy.TextBlock
import io.deepseekharness.mobile.virtualscreen.VirtualScreenTextPolicy.TextConditions
import io.deepseekharness.mobile.virtualscreen.VirtualScreenTextPolicy.TextMethod
import io.deepseekharness.mobile.virtualscreen.VirtualScreenTextPolicy.TextOutcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `VirtualScreenTextPolicy` 是纯函数决策模型：不碰 Android 类，所以能在 JVM 单测里逐支覆盖
 * （本模块的 Gradle 没有开 `unitTests.returnDefaultValues`，真实 Android 类在单测里会抛
 * `Method ... not mocked`，因此这里**只测纯函数**，真机行为一律待真机验证）。
 *
 * 覆盖三件事：
 * 1. [VirtualScreenTextPolicy.classify] 对 ASCII / 中文 / emoji 代理对 / 混合串的分类；
 * 2. [VirtualScreenTextPolicy.plan] 在各种条件组合下算出的**回退链**与短路原因（条件 → 方式）；
 * 3. [VirtualScreenTextPolicy.resolve] / [VirtualScreenTextPolicy.encode] 的收口语义
 *    （成功报生效的那一级、失败报最后一级的失败码、绝不把「写不进去」说成「副屏不可用」）。
 */
class VirtualScreenTextPolicyTest {

    private fun conditions(
        asciiOnly: Boolean,
        accessibilityAvailable: Boolean = true,
        hasFocusedEditable: Boolean = true,
        hasFocusTarget: Boolean = true,
        clipboardAvailable: Boolean = true,
    ) = TextConditions(asciiOnly, accessibilityAvailable, hasFocusedEditable, hasFocusTarget, clipboardAvailable)

    private fun attempted(method: TextMethod, succeeded: Boolean, code: String? = null, detail: String = "") =
        TextAttempt(method, succeeded, code, detail)

    // ---- 文本分类 ----

    @Test
    fun `分类纯 ASCII`() {
        val kind = VirtualScreenTextPolicy.classify("hello world 123")
        assertTrue(kind.asciiOnly)
        assertFalse(kind.hasNonAscii)
        assertFalse(kind.hasEmojiSurrogate)
        assertEquals(15, kind.length)
        assertTrue(VirtualScreenTextPolicy.isAsciiOnly("hello world 123"))
    }

    @Test
    fun `分类中文`() {
        val kind = VirtualScreenTextPolicy.classify("你好，微信")
        assertFalse(kind.asciiOnly)
        assertTrue(kind.hasNonAscii)
        assertFalse(kind.hasEmojiSurrogate)
        assertEquals(5, kind.length)
        assertFalse(VirtualScreenTextPolicy.isAsciiOnly("你好，微信"))
    }

    @Test
    fun `分类 emoji 代理对`() {
        // emoji 是非 BMP 码点：UTF-16 里占两个代码单元，长度按代码单元计（与 1..512 的上限同口径）。
        val kind = VirtualScreenTextPolicy.classify("\uD83D\uDE00")
        assertTrue(kind.hasEmojiSurrogate)
        assertTrue(kind.hasNonAscii)
        assertFalse(kind.asciiOnly)
        assertEquals(2, kind.length)
    }

    @Test
    fun `分类混合文本`() {
        val kind = VirtualScreenTextPolicy.classify("ok 好 \uD83D\uDC4D")
        assertTrue(kind.hasNonAscii)
        assertTrue(kind.hasEmojiSurrogate)
        assertFalse(kind.asciiOnly)
    }

    @Test
    fun `分类空串`() {
        val kind = VirtualScreenTextPolicy.classify("")
        assertFalse(kind.asciiOnly)
        assertFalse(kind.hasNonAscii)
        assertEquals(0, kind.length)
        assertFalse(VirtualScreenTextPolicy.isAsciiOnly(""))
    }

    // ---- 回退链：条件 → 方式 ----

    @Test
    fun `有聚焦输入框时先直接写入`() {
        val text = "hello"
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify(text),
            conditions(asciiOnly = true),
        )
        assertEquals(
            listOf(TextMethod.SET_TEXT, TextMethod.FOCUS_THEN_SET_TEXT, TextMethod.PASTE, TextMethod.KEY_EVENTS),
            decision.chain,
        )
        assertNull(decision.blocked)
        assertEquals("无障碍直接写入 → 无障碍聚焦后写入 → 剪贴板粘贴 → 按键事件逐字符输入", decision.plan)
    }

    @Test
    fun `没有聚焦输入框但有候选框时先聚焦再写入`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("hello"),
            conditions(asciiOnly = true, hasFocusedEditable = false),
        )
        assertEquals(
            listOf(TextMethod.FOCUS_THEN_SET_TEXT, TextMethod.PASTE, TextMethod.KEY_EVENTS),
            decision.chain,
        )
    }

    @Test
    fun `剪贴板不可用时跳过粘贴并如实说明`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("hello"),
            conditions(asciiOnly = true, clipboardAvailable = false),
        )
        assertEquals(listOf(TextMethod.SET_TEXT, TextMethod.FOCUS_THEN_SET_TEXT, TextMethod.KEY_EVENTS), decision.chain)
        assertFalse(decision.chain.contains(TextMethod.PASTE))
        assertTrue(decision.reason.contains("剪贴板不可用，已跳过粘贴"))
    }

    @Test
    fun `无障碍不可用时纯 ASCII 只剩按键事件`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("hello"),
            conditions(asciiOnly = true, accessibilityAvailable = false, clipboardAvailable = false),
        )
        assertEquals(listOf(TextMethod.KEY_EVENTS), decision.chain)
        assertNull(decision.blocked)
    }

    @Test
    fun `无障碍不可用时非 ASCII 直接空链并给出短路原因`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("你好"),
            conditions(asciiOnly = false, accessibilityAvailable = false, clipboardAvailable = false),
        )
        assertTrue(decision.chain.isEmpty())
        assertEquals(TextBlock.ACCESSIBILITY_DISABLED, decision.blocked)
        assertEquals(
            "无障碍服务未启用：非 ASCII 文本需要无障碍定向注入；纯 ASCII 之外的字符也无法用按键事件输入",
            decision.reason,
        )
        // 空链时 plan 的摘要直接落回 UNSUPPORTED，不会伪造一个方式。
        assertEquals(TextMethod.UNSUPPORTED.label, decision.plan)
    }

    @Test
    fun `无障碍可用但副屏没有可编辑节点时非 ASCII 空链`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("你好"),
            conditions(asciiOnly = false, hasFocusedEditable = false, hasFocusTarget = false),
        )
        assertTrue(decision.chain.isEmpty())
        assertEquals(TextBlock.NOT_EDITABLE, decision.blocked)
    }

    @Test
    fun `无障碍可用但没有可编辑节点时纯 ASCII 仍走按键事件`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("42"),
            conditions(asciiOnly = true, hasFocusedEditable = false, hasFocusTarget = false),
        )
        assertEquals(listOf(TextMethod.KEY_EVENTS), decision.chain)
        assertNull(decision.blocked)
    }

    @Test
    fun `超长与空文本在策略层就是参数错误`() {
        val empty = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify(""),
            conditions(asciiOnly = false),
        )
        assertTrue(empty.chain.isEmpty())
        assertNull(empty.blocked)
        assertEquals("副屏文本长度无效：只支持 1～512 个字符", empty.reason)

        val tooLong = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("a".repeat(VirtualScreenTextPolicy.MAX_TEXT_CHARS + 1)),
            conditions(asciiOnly = true),
        )
        assertTrue(tooLong.chain.isEmpty())
        assertNull(tooLong.blocked)
    }

    @Test
    fun `逐字符合成按键只对纯 ASCII 成立`() {
        assertTrue(VirtualScreenTextPolicy.keyEventsSupported(VirtualScreenTextPolicy.classify("abc")))
        assertFalse(VirtualScreenTextPolicy.keyEventsSupported(VirtualScreenTextPolicy.classify("好的")))
        assertFalse(VirtualScreenTextPolicy.keyEventsSupported(VirtualScreenTextPolicy.classify("")))
    }

    @Test
    fun `上限与既有校验口径一致`() {
        assertEquals(512, VirtualScreenTextPolicy.MAX_TEXT_CHARS)
        assertEquals(VirtualScreenTree.MAX_TEXT_CHARS, VirtualScreenTextPolicy.MAX_TEXT_CHARS)
        // 上限按 UTF-16 代码单元计：512 个 ASCII 字符合法、512 个 emoji（1024 个代码单元）非法。
        assertTrue(VirtualScreenTree.validText("a".repeat(VirtualScreenTextPolicy.MAX_TEXT_CHARS)))
        assertFalse(VirtualScreenTree.validText("\uD83D\uDE00".repeat(VirtualScreenTextPolicy.MAX_TEXT_CHARS)))
    }

    @Test
    fun `输入法不参与写文字`() {
        // IME_TAP 保留在枚举里是为了说明「为什么不用它」，决不能被排进任何回退链。
        val cases = listOf(
            VirtualScreenTextPolicy.plan(VirtualScreenTextPolicy.classify("hi"), conditions(asciiOnly = true)),
            VirtualScreenTextPolicy.plan(
                VirtualScreenTextPolicy.classify("hi"),
                conditions(asciiOnly = true, hasFocusedEditable = false),
            ),
            VirtualScreenTextPolicy.plan(
                VirtualScreenTextPolicy.classify("hi"),
                conditions(asciiOnly = true, accessibilityAvailable = false, clipboardAvailable = false),
            ),
            VirtualScreenTextPolicy.plan(VirtualScreenTextPolicy.classify("你好"), conditions(asciiOnly = false)),
        )
        cases.forEach { decision -> assertFalse(decision.chain.contains(TextMethod.IME_TAP)) }
        assertTrue(TextMethod.IME_TAP.explanation.contains("不依赖输入法"))
    }

    // ---- 收口：逐级记账 → 最终结果 ----

    @Test
    fun `第一级成功就报第一级`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("你好"),
            conditions(asciiOnly = false),
        )
        val attempts = listOf(attempted(TextMethod.SET_TEXT, true))
        val outcome = VirtualScreenTextPolicy.resolve(decision, attempts)
        assertTrue(outcome is TextOutcome.Success)
        assertEquals(TextMethod.SET_TEXT, outcome.attemptedMethod)
        assertEquals(2, outcome.chars)
        assertNull(VirtualScreenTextPolicy.codeOf(outcome))
        assertEquals("已通过无障碍直接写入写入 2 个字符", VirtualScreenTextPolicy.messageOf(outcome))
    }

    @Test
    fun `逐级失败到剪贴板成功时报剪贴板`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("hello"),
            conditions(asciiOnly = true),
        )
        val attempts = listOf(
            attempted(TextMethod.SET_TEXT, false, FailureCode.TEXT_UNSUPPORTED, "节点拒绝写入"),
            attempted(TextMethod.FOCUS_THEN_SET_TEXT, false, FailureCode.TEXT_UNSUPPORTED, "聚焦后仍被拒"),
            attempted(TextMethod.PASTE, true),
        )
        val outcome = VirtualScreenTextPolicy.resolve(decision, attempts)
        assertTrue(outcome is TextOutcome.Success)
        assertEquals(TextMethod.PASTE, outcome.attemptedMethod)
        assertTrue(VirtualScreenTextPolicy.describe(attempts).contains("剪贴板粘贴成功"))
    }

    @Test
    fun `全部失败时报最后一级的失败码`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("hello"),
            conditions(asciiOnly = true),
        )
        val attempts = listOf(
            attempted(TextMethod.SET_TEXT, false, FailureCode.TEXT_UNSUPPORTED),
            attempted(TextMethod.PASTE, false, FailureCode.TEXT_UNSUPPORTED, "目标输入框不接受粘贴"),
            attempted(TextMethod.KEY_EVENTS, false, FailureCode.TEXT_UNSUPPORTED, "等待调用方的按键通道"),
        )
        val outcome = VirtualScreenTextPolicy.resolve(decision, attempts)
        assertTrue(outcome is TextOutcome.Failure)
        assertEquals(FailureCode.TEXT_UNSUPPORTED, VirtualScreenTextPolicy.codeOf(outcome))
        // 关键语义：写不进去 ≠ 副屏不可用（VIRTUAL_SCREEN_UNAVAILABLE 会让模型直接放弃重试）。
        assertFalse(VirtualScreenTextPolicy.messageOf(outcome).contains("VIRTUAL_SCREEN_UNAVAILABLE"))
        assertEquals(
            "目标输入框不支持程序化写入，请在副屏上手动输入或改用点击操作（等待调用方的按键通道）",
            VirtualScreenTextPolicy.messageOf(outcome),
        )
        assertEquals(TextMethod.KEY_EVENTS, outcome.attemptedMethod)
    }

    @Test
    fun `上层闸门的失败码原样透传`() {
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("你好"),
            conditions(asciiOnly = false),
        )
        listOf(
            FailureCode.DEVICE_LOCKED,
            FailureCode.RATE_LIMITED,
            FailureCode.SENSITIVE_WINDOW,
            FailureCode.WINDOW_UNAVAILABLE,
        ).forEach { code ->
            val outcome = VirtualScreenTextPolicy.resolve(
                decision,
                listOf(attempted(TextMethod.SET_TEXT, false, code, "上游拒绝")),
            )
            assertEquals(code, VirtualScreenTextPolicy.codeOf(outcome))
            assertTrue(VirtualScreenTextPolicy.messageOf(outcome).contains("（上游拒绝）"))
        }
    }

    @Test
    fun `空链的失败码按原因区分`() {
        // 非 ASCII + 无障碍不可用：是「这台设备/这个应用写不进去」，不是参数错误。
        val blocked = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("你好"),
            conditions(asciiOnly = false, accessibilityAvailable = false),
        )
        val unsupported = VirtualScreenTextPolicy.resolve(blocked, emptyList())
        assertEquals(FailureCode.TEXT_UNSUPPORTED, VirtualScreenTextPolicy.codeOf(unsupported))
        assertEquals(blocked.reason, VirtualScreenTextPolicy.messageOf(unsupported))

        // 长度非法：是参数错误。
        val invalid = VirtualScreenTextPolicy.resolve(
            VirtualScreenTextPolicy.plan(VirtualScreenTextPolicy.classify(""), conditions(asciiOnly = false)),
            emptyList(),
        )
        assertEquals(FailureCode.ACTION_INVALID, VirtualScreenTextPolicy.codeOf(invalid))
    }

    @Test
    fun `链路已排但一次都没跑时按最后一级报未支持`() {
        // 执行层还没来得及尝试就返回（例如服务在探测后掉线）：不能凭空报成功，也不能报参数错误。
        val decision = VirtualScreenTextPolicy.plan(
            VirtualScreenTextPolicy.classify("hello"),
            conditions(asciiOnly = true),
        )
        val outcome = VirtualScreenTextPolicy.resolve(decision, emptyList())
        assertEquals(FailureCode.TEXT_UNSUPPORTED, VirtualScreenTextPolicy.codeOf(outcome))
        assertTrue(outcome is TextOutcome.Failure)
        assertTrue(outcome.attempts.isEmpty())
    }

    // ---- 中文说明与结构化输出 ----

    @Test
    fun `每个已知失败码都有中文说明`() {
        val messages = listOf(
            FailureCode.TEXT_UNSUPPORTED,
            FailureCode.DEVICE_LOCKED,
            FailureCode.RATE_LIMITED,
            FailureCode.SENSITIVE_WINDOW,
            FailureCode.WINDOW_UNAVAILABLE,
            FailureCode.ACTION_INVALID,
            FailureCode.INVALID,
        ).map { code -> VirtualScreenTextPolicy.failureMessage(code) }
        messages.forEach { message ->
            assertTrue(message.isNotEmpty())
            assertTrue(message.none { it.code in 0x00..0x1F })
            // 一律不许把失败说成「副屏不可用」。
            assertFalse(message.contains("VIRTUAL_SCREEN_UNAVAILABLE"))
            assertFalse(message.contains("副屏功能"))
        }
        // 未知码不硬猜，给一句通用解释而不是空白。
        assertEquals(
            "副屏文本注入未完成，请重新观察副屏后再试",
            VirtualScreenTextPolicy.failureMessage("ACCESSIBILITY_WHAT_IS_THIS"),
        )
    }

    @Test
    fun `成功与失败的 JSON 形状稳定`() {
        val success = VirtualScreenTextPolicy.encode(
            TextOutcome.Success(5, TextMethod.PASTE, listOf(attempted(TextMethod.PASTE, true))),
            submit = true,
        )
        assertEquals(5, success.getInt("chars"))
        assertTrue(success.getBoolean("submit"))
        assertEquals("PASTE", success.getString("method"))
        assertEquals("剪贴板粘贴", success.getString("label"))
        assertEquals("剪贴板粘贴成功", success.getString("steps"))
        assertFalse(success.has("code"))

        val failure = VirtualScreenTextPolicy.encode(
            TextOutcome.Failure(
                0,
                FailureCode.TEXT_UNSUPPORTED,
                VirtualScreenTextPolicy.failureMessage(FailureCode.TEXT_UNSUPPORTED),
                listOf(attempted(TextMethod.SET_TEXT, false, FailureCode.TEXT_UNSUPPORTED)),
            ),
            submit = false,
        )
        assertEquals(0, failure.getInt("chars"))
        assertFalse(failure.getBoolean("submit"))
        assertEquals(FailureCode.TEXT_UNSUPPORTED, failure.getString("code"))
        assertTrue(failure.getString("reason").contains("请在副屏上手动输入"))
        assertFalse(failure.has("method"))
    }

    @Test
    fun `逐级尝试摘要同时含成功与失败细节`() {
        val attempts = listOf(
            attempted(TextMethod.SET_TEXT, false, FailureCode.TEXT_UNSUPPORTED, "节点拒绝写入"),
            attempted(TextMethod.SET_TEXT, false, FailureCode.RATE_LIMITED),
            attempted(TextMethod.KEY_EVENTS, true),
        )
        assertEquals(
            "无障碍直接写入失败（${FailureCode.TEXT_UNSUPPORTED}）：节点拒绝写入；" +
                "无障碍直接写入失败（${FailureCode.RATE_LIMITED}）；按键事件逐字符输入成功",
            VirtualScreenTextPolicy.describe(attempts),
        )
        assertEquals("", VirtualScreenTextPolicy.describe(emptyList()))
    }

    @Test
    fun `参数校验与既有 validText 同口径`() {
        VirtualScreenTextPolicy.requireInjectable("你好")
        VirtualScreenTextPolicy.requireInjectable("a".repeat(VirtualScreenTextPolicy.MAX_TEXT_CHARS))
        listOf("", "a".repeat(VirtualScreenTextPolicy.MAX_TEXT_CHARS + 1), "a\nb", "a\u0000b", "a\u200Bb").forEach { bad ->
            val error = runCatching { VirtualScreenTextPolicy.requireInjectable(bad) }.exceptionOrNull()
            assertTrue("应拒绝：<$bad>", error is IllegalArgumentException)
        }
    }

    @Test
    fun `结构化输出可直接放进 JSON 响应`() {
        val json = JSONObject(
            VirtualScreenTextPolicy.encode(
                TextOutcome.Success(2, TextMethod.SET_TEXT, listOf(attempted(TextMethod.SET_TEXT, true))),
                submit = false,
            ).toString(),
        )
        assertEquals(2, json.getInt("chars"))
        assertEquals("SET_TEXT", json.getString("method"))
    }

    @Test
    fun `提交状态尾注把没按成的回车讲清楚`() {
        assertEquals("；已按一次回车提交", VirtualScreenTextPolicy.submitNote(true, true, true))
        assertEquals("；文字没写进去，没有按回车", VirtualScreenTextPolicy.submitNote(false, false, true))
        assertEquals("；文字写进去了，但回车提交未生效", VirtualScreenTextPolicy.submitNote(true, false, true))
        assertEquals("", VirtualScreenTextPolicy.submitNote(true, false, false))
        assertEquals("", VirtualScreenTextPolicy.submitNote(false, false, false))
    }

    @Test
    fun `设备 Shell 按键通道的信封在成功时给出真实字符数`() {
        val attempts = listOf(attempted(TextMethod.KEY_EVENTS, true, detail = "设备 Shell input text"))
        val json = VirtualScreenTree.encodeKeyEvents(attempts, 11, succeeded = true, submitted = true, submitRequested = true)
        assertEquals("KEY_EVENTS", json.getString("method"))
        assertEquals(11, json.getInt("chars"))
        assertTrue(json.getBoolean("submit"))
        assertFalse(json.has("code"))
        assertTrue(json.getString("steps").contains("已按一次回车提交"))
    }

    @Test
    fun `设备 Shell 按键通道的信封在失败时如实记账并带失败码`() {
        val attempts = listOf(
            attempted(TextMethod.KEY_EVENTS, false, FailureCode.TEXT_UNSUPPORTED, "input text 未成功"),
        )
        val json = VirtualScreenTree.encodeKeyEvents(attempts, 0, succeeded = false, submitRequested = true)
        assertEquals(0, json.getInt("chars"))
        assertFalse(json.getBoolean("submit"))
        assertEquals(FailureCode.TEXT_UNSUPPORTED, json.getString("code"))
        assertTrue(json.getString("reason").contains("设备 Shell"))
        assertTrue(json.getString("steps").contains("文字没写进去，没有按回车"))
        assertFalse(json.getString("code").contains("UNAVAILABLE"))
    }

    @Test
    fun `写了一半的按键兜底不会被说成整段成功`() {
        // 逐字符按键在第 3 个字符断了：chars 只能是已写进去的 2 个，且必须带失败码。
        val attempts = listOf(
            attempted(TextMethod.KEY_EVENTS, false, FailureCode.TEXT_UNSUPPORTED, "逐字符按键在第 3 个字符失败"),
        )
        val json = VirtualScreenTree.encodeKeyEvents(attempts, 2, succeeded = false, submitRequested = false)
        assertEquals(2, json.getInt("chars"))
        assertEquals(FailureCode.TEXT_UNSUPPORTED, json.getString("code"))
        assertFalse(json.getBoolean("submit"))
    }

    @Test
    fun `写进去但回车没生效时信封不宣称已发送`() {
        val attempts = listOf(attempted(TextMethod.KEY_EVENTS, true, detail = "设备 Shell input text"))
        val json = VirtualScreenTree.encodeKeyEvents(attempts, 5, succeeded = true, submitted = false, submitRequested = true)
        assertFalse(json.getBoolean("submit"))
        assertTrue(json.getString("steps").contains("回车提交未生效"))
        assertFalse(json.has("code"))
    }

    @Test
    fun `必须回主进程注入的信封与副屏不可用区分开`() {
        assertEquals(
            "VIRTUAL_SCREEN_TEXT_INJECTION_REQUIRED",
            VirtualScreenTree.TEXT_INJECTION_REQUIRED,
        )
        assertNotEquals("VIRTUAL_SCREEN_UNAVAILABLE", VirtualScreenTree.TEXT_INJECTION_REQUIRED)
        val json = VirtualScreenTree.injectionRequired(7, "含非 ASCII 字符")
        assertEquals(VirtualScreenTree.TEXT_INJECTION_REQUIRED, json.getString("code"))
        assertEquals("含非 ASCII 字符", json.getString("reason"))
        assertEquals(0, json.getInt("chars"))
        assertEquals(7, json.getInt("displayId"))
    }
}
