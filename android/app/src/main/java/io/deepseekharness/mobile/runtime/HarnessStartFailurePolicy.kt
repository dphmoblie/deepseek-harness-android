package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticPolicy

/**
 * Harness 启动失败的诊断记录内容。
 *
 * 为什么需要它：启动失败以前只落在界面的一行错误码上。用户导出诊断日志后，
 * 时间线里只有成功时的 `HARNESS_START|result=ok|phase=running`，失败路径没有任何痕迹 ——
 * 「dsh 到底为什么没起来」在设备上无法复盘。
 *
 * 记录只有两个字段（都在 [DiagnosticPolicy] 的受控白名单内）：
 *  - `result=failed`：本次启动以失败结束；
 *  - `code=<错误码>`：来自 [RuntimeFailure.code]、状态快照 errorCode 这类内部枚举的受控错误码。
 *
 * 刻意不记录任何其他内容：访客输出、路径、端口、命令与凭据一律不写进诊断日志
 * （边界见 docs/诊断日志.md）。错误码取值不可控时回落成 [FALLBACK_CODE]，
 * 保证写进去的永远是受控枚举，而不是自由文本形态的字符串。
 *
 * 本对象不依赖 Android API，可在 JVM 单测里直接驱动；写入交给
 * RuntimeSupervisor 的启动失败统一出口，那里是「把本次启动判定为失败」的唯一出口。
 */
internal object HarnessStartFailurePolicy {
    /** 记录级别：启动失败是错误级事件（成功时用的是 [DiagnosticLevel.INFO]）。 */
    val LEVEL: DiagnosticLevel = DiagnosticLevel.ERROR

    /** 记录事件：沿用既有的 [DiagnosticEvent.HARNESS_START]，不新增事件。 */
    val EVENT: DiagnosticEvent = DiagnosticEvent.HARNESS_START

    /** `result` 的取值，必须落在 DiagnosticPolicy 的受控集合内。 */
    const val RESULT: String = "failed"

    /** 错误码不可控时的回落值：启动确实失败了，只是原因无法归类。 */
    const val FALLBACK_CODE: String = "HARNESS_START_FAILED"

    /**
     * 一条启动失败记录的字段。
     *
     * 只有 `result` 与 `code` 两个键：字段名白名单之外的键会被策略丢弃，
     * 而每多一个键就多一处泄露面，所以这里刻意不携带阶段、计数或任何上下文。
     */
    fun fields(code: String): Map<String, String> = mapOf(
        "result" to RESULT,
        "code" to controlledCode(code),
    )

    /** 受控错误码：不是大写错误码形态的一律回落，字段因此永远不会被策略丢弃。 */
    fun controlledCode(code: String): String =
        code.takeIf { DiagnosticPolicy.isAllowedField("code", it) } ?: FALLBACK_CODE
}
