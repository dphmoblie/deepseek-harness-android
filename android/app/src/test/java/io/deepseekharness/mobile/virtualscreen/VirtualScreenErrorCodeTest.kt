package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 副屏错误码语义：会话切换与“正在启动”属于可重试情形，不能被归为「副屏不可用」。 */
class VirtualScreenErrorCodeTest {
    private val current = "3f1a5c2e-9d4b-4f7a-8e11-2b6c0d9a7e34"
    private val previous = "8b7c1d40-2e59-4a3f-9c88-5d1e6f0a2b47"

    @Test fun `会话已切换时返回可重试的停止码而不是不可用`() {
        val failure = VirtualScreenPolicy.sessionFailure(current, previous)
        assertEquals("VIRTUAL_SCREEN_STOPPED", failure?.code)
        assertEquals("副屏会话已切换，请重新读取状态", failure?.message)
        val code = VirtualScreenPolicy.errorCode(failure!!)
        assertEquals("VIRTUAL_SCREEN_STOPPED", code)
        assertNotEquals("VIRTUAL_SCREEN_UNAVAILABLE", code)
    }

    @Test fun `尚未建立会话时返回瞬时的忙碌码`() {
        val failure = VirtualScreenPolicy.sessionFailure("", current)
        assertEquals("VIRTUAL_SCREEN_BUSY", failure?.code)
        assertEquals("VIRTUAL_SCREEN_BUSY", VirtualScreenPolicy.errorCode(failure!!))
    }

    @Test fun `会话一致时不产生失败`() {
        assertNull(VirtualScreenPolicy.sessionFailure(current, current))
    }

    @Test fun `错误码映射保留运行失败码并区分输入错误`() {
        assertEquals("VIRTUAL_SCREEN_STOPPED", VirtualScreenPolicy.errorCode(RuntimeFailure("VIRTUAL_SCREEN_STOPPED", "副屏会话已切换，请重新读取状态")))
        assertEquals("VIRTUAL_SCREEN_BUSY", VirtualScreenPolicy.errorCode(RuntimeFailure("VIRTUAL_SCREEN_BUSY", "上一步副屏操作尚未完成")))
        assertEquals("DEVICE_SHELL_DISABLED", VirtualScreenPolicy.errorCode(RuntimeFailure("DEVICE_SHELL_DISABLED", "请先开启 AI Shell")))
        assertEquals("VIRTUAL_SCREEN_INVALID", VirtualScreenPolicy.errorCode(IllegalArgumentException("副屏参数过长")))
        val jsonError = runCatching { JSONObject("{}").getString("sessionId") }.exceptionOrNull()
        assertTrue(jsonError is JSONException)
        assertEquals("VIRTUAL_SCREEN_INVALID", VirtualScreenPolicy.errorCode(jsonError!!))
        // 只有无法归类的异常才退化成「不可用」，避免把可重试情形伪装成不可用。
        assertEquals("VIRTUAL_SCREEN_UNAVAILABLE", VirtualScreenPolicy.errorCode(IllegalStateException("副屏预览组件异常")))
    }
}
