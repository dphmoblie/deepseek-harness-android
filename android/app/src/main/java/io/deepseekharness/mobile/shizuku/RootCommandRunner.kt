package io.deepseekharness.mobile.shizuku

import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 根命令执行器：**由应用自己（而不是 Shizuku 的 shell）去调 `su -c`**。
 *
 * 这是设计规范里「分区 2 的指令只能落在拥有 UID 0 的那个进程」这一条的实现：
 * 先前实现把 `su -c` 拼进 Shizuku 用户服务的 PTY 会话里执行，那条会话的父进程是 UID 2000 的 shell，
 * 于是隔离住的只是「被 shell 包含的那一段文本」，而 namespace 生命周期、`/dev/block` 遮蔽、
 * KernelSU 的 App Profile 都失去了确定的归属。本类把调用权收回应用进程：
 *
 * 1. [RootCommandGuard] 静态安检 → 2. [RootSecurityWrapper] 包一层失败即停的命名空间沙箱 →
 * 3. 单参数交给 `su -c`（不做任何 shell 拼接，也就不存在转义逃逸）。
 *
 * 返回对象里的 [RootCommandResult.diagnostics] 是沙箱自己报的降级说明
 * （例如 `PID_ISOLATION_UNAVAILABLE` 表示内核没开 PID namespace），已经从 `output` 里剥掉，
 * 不会干扰调用方的结果协议解析。
 */
object RootCommandRunner {

    const val MAX_OUTPUT_CHARS = 256 * 1024
    const val DEFAULT_TIMEOUT_MS = 60_000L

    /** 测试注入点：默认真的起进程。 */
    var processLauncher: (List<String>) -> Process = { argv ->
        ProcessBuilder(argv).redirectErrorStream(true).start()
    }

    data class RootCommandResult(
        val ok: Boolean,
        val exitCode: Int,
        /** 已剥离诊断行的标准输出（stderr 已合并进来）。 */
        val output: String,
        val truncated: Boolean,
        val errorCode: String?,
        /** 沙箱降级说明，例如 `PID_ISOLATION_UNAVAILABLE`。 */
        val diagnostics: List<String> = emptyList(),
    )

    /**
     * 执行一段根脚本。
     *
     * @param hardened 裁减 `CAP_SYS_ADMIN`/`CAP_MKNOD`；需要挂载类操作（OverlayFS 等）时传 `false`，
     *                 此时硬约束只剩 KernelSU 管理器的 App Profile。
     */
    fun run(script: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, hardened: Boolean = true): RootCommandResult {
        RootCommandGuard.verifySafe(script)
        val wrapped = RootSecurityWrapper.wrapWithProtection(script, hardened)

        val process = try {
            processLauncher(listOf("su", "-c", wrapped))
        } catch (_: Exception) {
            return RootCommandResult(false, -1, "", false, ERROR_SU_UNAVAILABLE)
        }

        val (text, truncated, completed) = readWithTimeout(process, timeoutMs)
        if (!completed) {
            process.destroyForcibly()
            return RootCommandResult(false, -1, text, truncated, ERROR_TIMEOUT)
        }

        val exitCode = try {
            process.exitValue()
        } catch (_: Exception) {
            -1
        }
        val diagnostics = text.lineSequence()
            .filter { it.contains(RootSecurityWrapper.DIAG_PREFIX) }
            .map { it.substringAfter(RootSecurityWrapper.DIAG_PREFIX).trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val output = text.lineSequence()
            .filterNot { it.contains(RootSecurityWrapper.DIAG_PREFIX) }
            .joinToString("\n")
            .trim()

        // 退出码不能单独当作沙箱诊断：125/126/127 同样是常见的载荷退出码（127 = command not found，
        // 126 = not executable），只看数字会把「命令没找到」报成「沙箱遮蔽失败」。
        // 沙箱只在**同时打印了对应诊断行**时才认领这个退出码，否则一律按载荷自己的结果归类。
        val errorCode = when {
            exitCode == 0 -> null
            diagnostics.contains(RootSecurityWrapper.DIAG_NAMESPACE_UNAVAILABLE) -> ERROR_SANDBOX_UNAVAILABLE
            diagnostics.contains(RootSecurityWrapper.DIAG_PROPAGATION_FAILED) -> ERROR_SANDBOX_PROPAGATION
            diagnostics.contains(RootSecurityWrapper.DIAG_BLOCK_MASK_FAILED) -> ERROR_BLOCK_MASK_FAILED
            diagnostics.contains(RootSecurityWrapper.DIAG_DECODE_FAILED) -> ERROR_SANDBOX_DECODE_FAILED
            DENIED_MARKERS.any { output.contains(it, ignoreCase = true) } -> ERROR_PERMISSION_DENIED
            else -> ERROR_COMMAND_FAILED
        }

        return RootCommandResult(
            ok = exitCode == 0,
            exitCode = exitCode,
            output = output,
            truncated = truncated,
            errorCode = errorCode,
            diagnostics = diagnostics,
        )
    }

    /** 读流与超时等待：读在独立线程，避免输出撑满管道把子进程卡住。 */
    private fun readWithTimeout(
        process: Process,
        timeoutMs: Long,
    ): Triple<String, Boolean, Boolean> {
        val buffer = StringBuilder()
        var truncated = false
        val reader = Thread {
            try {
                val stream: InputStream = process.inputStream
                stream.bufferedReader().use { source ->
                    val chunk = CharArray(4_096)
                    while (true) {
                        val read = source.read(chunk)
                        if (read <= 0) break
                        val remain = MAX_OUTPUT_CHARS - buffer.length
                        if (remain <= 0) {
                            truncated = true
                            break
                        }
                        buffer.appendRange(chunk, 0, minOf(read, remain))
                    }
                }
            } catch (_: Exception) {
                // 读流异常按「已读到的部分」处理。
            }
        }
        reader.isDaemon = true
        reader.start()

        val completed = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }
        if (completed) reader.join(200)
        return Triple(buffer.toString().trim(), truncated, completed)
    }

    const val ERROR_SU_UNAVAILABLE = "ROOT_SU_UNAVAILABLE"
    const val ERROR_TIMEOUT = "ROOT_COMMAND_TIMEOUT"
    const val ERROR_SANDBOX_UNAVAILABLE = "ROOT_SANDBOX_UNAVAILABLE"
    const val ERROR_SANDBOX_PROPAGATION = "ROOT_SANDBOX_PROPAGATION_FAILED"
    const val ERROR_BLOCK_MASK_FAILED = "ROOT_BLOCK_MASK_FAILED"
    const val ERROR_SANDBOX_DECODE_FAILED = "ROOT_SANDBOX_DECODE_FAILED"
    const val ERROR_PERMISSION_DENIED = "ROOT_PERMISSION_DENIED"
    const val ERROR_COMMAND_FAILED = "ROOT_COMMAND_FAILED"

    private val DENIED_MARKERS = listOf("not allowed to", "permission denied", "operation not permitted")
}
