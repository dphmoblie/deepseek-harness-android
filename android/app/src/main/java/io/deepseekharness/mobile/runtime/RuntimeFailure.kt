package io.deepseekharness.mobile.runtime

/**
 * 运行时安装 / 校验失败。
 *
 * [details] 只承载**可以写进诊断日志的受控字段**：目前只有 transfer 阶段的字节数与摘要结论
 * （全部由 `io.deepseekharness.mobile.runtime.diagnostics.TransferFields` 产出），
 * 一律是数字与布尔。原始异常文本、绝对路径与凭据**永远不进**这里，
 * 它们只留在进程内的 [cause] 上，既不进日志也不进审计。
 */
class RuntimeFailure(
    val code: String,
    message: String,
    cause: Throwable? = null,
    val details: Map<String, String> = emptyMap(),
) : Exception(message, cause)
