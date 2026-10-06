package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副屏 Shell 直通通道的策略边界：脚本校验、会话与 displayId 校验、前缀的确定性。
 *
 * 全部是 JVM 单测：被测对象不依赖 Android，只依赖纯 Kotlin 的 `RuntimeFailure`。
 */
class VirtualScreenShellPolicyTest {
    private val sessionDisplayId = 38
    private val tap = "input -d \"\$DISPLAY_ID\" tap 622 880"

    private fun failureCode(block: () -> Unit): String {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("应当抛出 RuntimeFailure，实际为 $failure", failure is RuntimeFailure)
        return (failure as RuntimeFailure).code
    }

    @Test fun `缺省 displayId 用会话副屏并把编号导出到脚本环境`() {
        val plan = VirtualScreenShellPolicy.plan(tap, null, active = true, sessionDisplayId = sessionDisplayId)
        assertEquals(38, plan.displayId)
        assertTrue(plan.command.startsWith(VirtualScreenShellPolicy.prefix(38)))
        assertTrue(plan.command.endsWith(tap))
        assertTrue(plan.command.contains("export DISPLAY_ID=38;"))
    }

    @Test fun `显式传入会话副屏 id 被接受`() {
        val plan = VirtualScreenShellPolicy.plan(tap, sessionDisplayId, active = true, sessionDisplayId = sessionDisplayId)
        assertEquals(38, plan.displayId)
        assertEquals(VirtualScreenShellPolicy.prefix(38) + tap, plan.command)
    }

    @Test fun `显式传入不是本会话副屏的 id 被拒绝且错误码可区分`() {
        val code = failureCode {
            VirtualScreenShellPolicy.plan(tap, 49, active = true, sessionDisplayId = sessionDisplayId)
        }
        assertEquals("VIRTUAL_SCREEN_DISPLAY_INVALID", code)
        assertEquals(VirtualScreenShellPolicy.DISPLAY_INVALID_CODE, code)
        assertNotEquals(VirtualScreenShellPolicy.UNAVAILABLE_CODE, code)
        assertNotEquals(VirtualScreenShellPolicy.SCRIPT_INVALID_CODE, code)
    }

    @Test fun `显式传入主屏 0 一律拒绝并说明理由`() {
        val failure = runCatching {
            VirtualScreenShellPolicy.plan(tap, 0, active = true, sessionDisplayId = sessionDisplayId)
        }.exceptionOrNull() as RuntimeFailure
        assertEquals(VirtualScreenShellPolicy.DISPLAY_INVALID_CODE, failure.code)
        assertTrue("消息要写清「0 是主屏」的理由", failure.message!!.contains("主屏"))
    }

    @Test fun `负数 displayId 被拒绝`() {
        assertEquals(
            VirtualScreenShellPolicy.DISPLAY_INVALID_CODE,
            failureCode { VirtualScreenShellPolicy.resolveDisplayId(sessionDisplayId, -1) },
        )
    }

    @Test fun `副屏会话未运行或没有显示编号时回不可用码`() {
        assertEquals(
            VirtualScreenShellPolicy.UNAVAILABLE_CODE,
            failureCode { VirtualScreenShellPolicy.plan(tap, null, active = false, sessionDisplayId = sessionDisplayId) },
        )
        assertEquals(
            VirtualScreenShellPolicy.UNAVAILABLE_CODE,
            failureCode { VirtualScreenShellPolicy.plan(tap, null, active = true, sessionDisplayId = 0) },
        )
    }

    @Test fun `脚本缺失或只有空白被拒绝`() {
        for (script in listOf(null, "", " ", "\n\t")) {
            assertEquals(
                "脚本：${script?.let { JSON_ESCAPED_NAMES[it] ?: "空白" } ?: "null"}",
                VirtualScreenShellPolicy.SCRIPT_INVALID_CODE,
                failureCode { VirtualScreenShellPolicy.plan(script, null, active = true, sessionDisplayId = sessionDisplayId) },
            )
        }
    }

    @Test fun `脚本按 UTF-8 字节计限并保留边界值`() {
        val max = "a".repeat(VirtualScreenShellPolicy.MAX_SCRIPT_BYTES)
        assertEquals(max, VirtualScreenShellPolicy.requireScript(max))
        assertEquals(
            VirtualScreenShellPolicy.SCRIPT_INVALID_CODE,
            failureCode {
                VirtualScreenShellPolicy.plan(
                    "a".repeat(VirtualScreenShellPolicy.MAX_SCRIPT_BYTES + 1),
                    null,
                    active = true,
                    sessionDisplayId = sessionDisplayId,
                )
            },
        )
        // 中文按 UTF-8 三字节计：5462 个字已经超过 16 KiB，不能按字符数放行。
        assertEquals(
            VirtualScreenShellPolicy.SCRIPT_INVALID_CODE,
            failureCode {
                VirtualScreenShellPolicy.plan("中".repeat(5462), null, active = true, sessionDisplayId = sessionDisplayId)
            },
        )
    }

    @Test fun `脚本含 NUL 或回车被拒绝`() {
        for (script in listOf("a\u0000b", "a\rb", "echo hi\r")) {
            assertEquals(
                VirtualScreenShellPolicy.SCRIPT_INVALID_CODE,
                failureCode { VirtualScreenShellPolicy.plan(script, null, active = true, sessionDisplayId = sessionDisplayId) },
            )
        }
        // 换行与制表符是正常脚本的一部分，不能被误伤。
        assertEquals("a\nb\tc", VirtualScreenShellPolicy.requireScript("a\nb\tc"))
    }

    @Test fun `脚本校验先于会话校验，坏脚本不会伪装成副屏不可用`() {
        assertEquals(
            VirtualScreenShellPolicy.SCRIPT_INVALID_CODE,
            failureCode { VirtualScreenShellPolicy.plan("", null, active = false, sessionDisplayId = 0) },
        )
    }

    @Test fun `脚本加上前缀后超过上限被提前拒绝`() {
        val prefixBytes = VirtualScreenShellPolicy.prefix(sessionDisplayId).toByteArray(Charsets.UTF_8).size
        val fits = "a".repeat(VirtualScreenShellPolicy.MAX_SCRIPT_BYTES - prefixBytes)
        val plan = VirtualScreenShellPolicy.plan(fits, null, active = true, sessionDisplayId = sessionDisplayId)
        assertEquals(VirtualScreenShellPolicy.MAX_SCRIPT_BYTES, plan.command.toByteArray(Charsets.UTF_8).size)
        assertEquals(
            VirtualScreenShellPolicy.SCRIPT_INVALID_CODE,
            failureCode {
                VirtualScreenShellPolicy.plan(fits + "a", null, active = true, sessionDisplayId = sessionDisplayId)
            },
        )
    }

    @Test fun `前缀是确定性的并且说明 input 与 am start 的用法`() {
        assertEquals(VirtualScreenShellPolicy.prefix(38), VirtualScreenShellPolicy.prefix(38))
        assertNotEquals(VirtualScreenShellPolicy.prefix(38), VirtualScreenShellPolicy.prefix(49))
        val prefix = VirtualScreenShellPolicy.prefix(49)
        assertTrue(prefix.contains("input -d \"\$DISPLAY_ID\" tap 622 880"))
        assertTrue(prefix.contains("am start --display \"\$DISPLAY_ID\""))
        assertTrue(prefix.contains("displayId=49"))
    }

    @Test fun `前缀重复执行幂等，导出的编号始终是同一个`() {
        val once = VirtualScreenShellPolicy.prefix(38)
        val twice = once + once
        val exported = twice.lines().filter { it.startsWith("export DISPLAY_ID=") }
        assertEquals(2, exported.size)
        assertEquals(listOf("export DISPLAY_ID=38;", "export DISPLAY_ID=38;"), exported)
        assertEquals(once.toByteArray(Charsets.UTF_8).toList(), VirtualScreenShellPolicy.prefix(38).toByteArray(Charsets.UTF_8).toList())
    }

    companion object {
        private val JSON_ESCAPED_NAMES = mapOf(" " to "单个空格", "\n\t" to "换行与制表符")
    }
}
