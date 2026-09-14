package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticPolicy
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harness 启动失败诊断记录的纯逻辑测试。
 *
 * RuntimeSupervisor 的启动流程要真的拉起 PRoot→node 进程、等待端口与认证挑战，
 * 无法在 JVM 单测里驱动；这里直接覆盖它写出的**记录内容**：
 * 事件固定为 HARNESS_START、result=failed、code 为对应错误码，
 * 并且整行能原样通过诊断策略的受控校验（不会被丢弃任何字段）。
 *
 * 真机上的失败路径（自检不过、端口被占、启动超时等）由 RuntimeSupervisor 的
 * 统一出口写入，覆盖范围见 docs/诊断日志.md。
 */
class HarnessStartFailurePolicyTest {
    private val instant: Instant = Instant.parse("2026-09-15T10:20:30Z")

    private fun line(code: String): String = DiagnosticPolicy.recordLine(
        instant,
        HarnessStartFailurePolicy.LEVEL,
        HarnessStartFailurePolicy.EVENT,
        HarnessStartFailurePolicy.fields(code),
    )

    @Test
    fun recordsOneFailedStartLineWithTheExactErrorCode() {
        // 启动流程里实际会走到的失败码：拉进程失败、等待就绪超时、启动等待被中断、
        // 访客进程自行退出、缺少认证入口，以及自检与端口两处前置检查。
        val codes = listOf(
            "HARNESS_START_FAILED",
            "HARNESS_START_TIMEOUT",
            "HARNESS_START_INTERRUPTED",
            "HARNESS_EXITED",
            "HARNESS_AUTH_UNAVAILABLE",
            "NODE_RUNTIME_FAILED",
            "HARNESS_PREFLIGHT_FAILED",
            "HARNESS_PORT_IN_USE",
            "RUNTIME_NOT_INSTALLED",
            "RUNTIME_CORRUPTED",
        )
        codes.forEach { code ->
            assertEquals(
                "2026-09-15T10:20:30Z|ERROR|HARNESS_START|result=failed|code=$code\n",
                line(code),
            )
        }
    }

    @Test
    fun recordUsesOnlyControlledEventLevelAndFields() {
        assertEquals(DiagnosticLevel.ERROR, HarnessStartFailurePolicy.LEVEL)
        assertEquals(DiagnosticEvent.HARNESS_START, HarnessStartFailurePolicy.EVENT)

        val fields = HarnessStartFailurePolicy.fields("HARNESS_START_TIMEOUT")
        // 多一个键就多一处泄露面：这里锁定启动失败记录只有 result 与 code。
        assertEquals(setOf("result", "code"), fields.keys)
        assertEquals("failed", fields["result"])
        assertTrue(fields.all { (key, value) -> DiagnosticPolicy.isAllowedField(key, value) })
    }

    @Test
    fun uncontrolledCodesFallBackToAControlledOne() {
        // 错误码只接受内部枚举。带路径、空格、小写或异常消息形态的取值绝不能原样落盘，
        // 否则诊断日志的「不含路径与凭据」承诺就被一个字段破坏掉了。
        val uncontrolled = listOf(
            "",
            "harness_start_failed",
            "/data/user/0/io.deepseekharness.mobile/files",
            "Cannot read properties of undefined",
            "sk-live-abcdef",
            "HARNESS START",
        )
        uncontrolled.forEach { code ->
            assertEquals(HarnessStartFailurePolicy.FALLBACK_CODE, HarnessStartFailurePolicy.controlledCode(code))
            assertEquals(
                "2026-09-15T10:20:30Z|ERROR|HARNESS_START|result=failed|code=HARNESS_START_FAILED\n",
                line(code),
            )
        }
        // 受控错误码原样保留，不被回落改写。
        assertEquals("HARNESS_START_INTERRUPTED", HarnessStartFailurePolicy.controlledCode("HARNESS_START_INTERRUPTED"))
    }
}
