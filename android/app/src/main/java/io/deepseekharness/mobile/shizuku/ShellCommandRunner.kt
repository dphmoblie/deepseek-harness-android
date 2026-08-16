package io.deepseekharness.mobile.shizuku

/**
 * 单次命令执行结果（反射特权 exec 专用，无交互语义）。
 * 环境/机制失败（Shizuku 不可用、反射失败）一律抛 RuntimeFailure，
 * 命令本身的退出码通过 [exitCode] 表达。
 */
data class ShellExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val base64: String? = null,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

/**
 * 单次命令执行语义：argv 数组直传（无 shell 元字符解释，杜绝注入），
 * 与 [ShizukuRuntime] 的 UserService PTY 交互终端互补。
 */
interface ShellCommandRunner {
    fun exec(
        cmd: Array<String>,
        timeoutMs: Long = 30_000L,
        wantBinary: Boolean = false,
    ): ShellExecResult
}
