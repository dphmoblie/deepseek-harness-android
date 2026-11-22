package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
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
    ) {
        /**
         * 这次执行是否用上了完整的沙箱。
         *
         * 与 [ok] 正交：命令可以成功而沙箱是降级的（ROM 没有 `unshare`），也可以失败而沙箱完好
         * （载荷自己报错）。调用方要展示「降级执行」时看这个字段，别拿 [ok] 去猜。
         */
        val degraded: Boolean
            get() = diagnostics.any { it.substringBefore(':') in RootSecurityWrapper.DEGRADATION_CODES }
    }

    /**
     * 执行一段根脚本。
     *
     * @param hardened 裁减 `CAP_SYS_ADMIN`/`CAP_MKNOD`；需要挂载类操作（OverlayFS 等）时传 `false`，
     *                 此时硬约束只剩 KernelSU 管理器的 App Profile。
     * @param guardProfile 静态审查档位。默认 [RootCommandGuard.Profile.STRICT]；
     *                     用户显式选择最大档时传 [RootCommandGuard.Profile.MAXIMUM]。
     */
    fun run(
        script: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        hardened: Boolean = true,
        guardProfile: RootCommandGuard.Profile = RootCommandGuard.Profile.STRICT,
    ): RootCommandResult {
        RootCommandGuard.verifySafe(script, guardProfile)
        val wrapped = RootSecurityWrapper.wrapWithProtection(script, hardened)
        return executeWrapped(wrapped, timeoutMs)
    }

    /**
     * 执行已取得宿主签发凭证 [grant] 的根脚本。
     *
     * 二次核验 `sha256(script)` 与凭证一致：
     *  - `LOW` / `ELEVATED`（经 dsh 审批或最大档放行）：继续套用 [RootSecurityWrapper]，
     *    仅当本次已确认的命令本身需要挂载/内核模块/设备节点能力时关闭 `setpriv` 裁剪；
     *  - `HIGH` / `CRITICAL`（已通过 Android 宿主 App 确认/极危警告页）：走无外层命名空间与
     *    块设备遮蔽的 Base64 安全直连，确保用户明确确认后的操作（如重启、关 SELinux、写核心分区或
     *    维护操作）不会被沙箱或 `-sys_boot` 静默拦成失败。
     */
    internal fun runApproved(
        script: String,
        grant: RootExecutionGrant,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RootCommandResult {
        if (RootApprovalRegistry.sha256(script) != grant.scriptSha256) {
            throw RuntimeFailure("ROOT_APPROVAL_INVALID", "脚本内容与已批准凭证不一致，拒绝执行")
        }
        val wrapped = if (grant.assessment.bypassSandboxWrapper) {
            wrapDirectBase64(script)
        } else {
            RootSecurityWrapper.wrapWithProtection(script, hardened = !grant.assessment.needsUnconfinedCaps)
        }
        return executeWrapped(wrapped, timeoutMs)
    }

    private fun wrapDirectBase64(rawScript: String): String {
        val b64 = Base64.getEncoder().encodeToString(rawScript.toByteArray(StandardCharsets.UTF_8))
        return buildString {
            append("dsh_dec=\"base64\"; command -v toybox >/dev/null 2>&1 && dsh_dec=\"toybox base64 -d\"; ")
            append("dsh_p=\$(printf '%s' '$b64' | \$dsh_dec 2>/dev/null) || ")
            append("{ echo \"${RootSecurityWrapper.DIAG_PREFIX}${RootSecurityWrapper.DIAG_DECODE_FAILED}\" >&2; exit ${RootSecurityWrapper.EXIT_DECODE_FAILED}; }; ")
            append("[ -n \"\$dsh_p\" ] || { echo \"${RootSecurityWrapper.DIAG_PREFIX}${RootSecurityWrapper.DIAG_DECODE_FAILED}\" >&2; exit ${RootSecurityWrapper.EXIT_DECODE_FAILED}; }; ")
            append("printf '%s' \"\$dsh_p\" | /system/bin/sh -s; exit \$?")
        }
    }

    private fun executeWrapped(wrapped: String, timeoutMs: Long): RootCommandResult {
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

        // 退出码不能单独当作沙箱诊断：沙箱只在**自己打印了诊断行**时才算数。
        // 反过来说，退出码 124 也可能是载荷自己的选择，所以这里同样先看诊断行。
        val errorCode = when {
            exitCode == 0 -> null
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

    /**
     * 原生电源动作专用：执行一段**编译期常量**脚本，不过护栏、不包沙箱。
     *
     * 只有 `MobileRuntimePlugin.requestDevicePowerAction` 用它，且脚本只能是 `reboot` /
     * `reboot -p`。这不是给 AI 的新通道：设备桥里没有电源命令，AI 工具也不暴露它。
     * 超时按「设备正在重启/关机」处理为已受理，不再 `destroyForcibly`。
     */
    internal fun runSystemAction(script: String, timeoutMs: Long = 5_000L): RootCommandResult {
        val process = try {
            processLauncher(listOf("su", "-c", script))
        } catch (_: Exception) {
            return RootCommandResult(false, -1, "", false, ERROR_SU_UNAVAILABLE)
        }
        val (text, truncated, completed) = readWithTimeout(process, timeoutMs)
        if (!completed) {
            // reboot/poweroff 可能在命令返回前就把进程带走；这不是失败。
            return RootCommandResult(true, 0, text, truncated, null)
        }
        val exitCode = try {
            process.exitValue()
        } catch (_: Exception) {
            -1
        }
        return RootCommandResult(
            ok = exitCode == 0,
            exitCode = exitCode,
            output = text,
            truncated = truncated,
            errorCode = if (exitCode == 0) null else ERROR_COMMAND_FAILED,
        )
    }

    /**
     * 探测专用：直接执行一段**内部常量**脚本，不包沙箱、不过护栏。
     *
     * 只有 [RootSandboxProbe] 用它——它测量的正是「沙箱能不能用」，所以不能再过一遍沙箱；
     * 脚本是编译期常量，不含任何外部输入，也没有可被注入的拼接点。
     */
    internal fun runProbe(script: String, timeoutMs: Long): RootCommandResult {
        val process = try {
            processLauncher(listOf("su", "-c", script))
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
        val output = text.lineSequence()
            .filterNot { it.contains(RootSecurityWrapper.DIAG_PREFIX) }
            .joinToString("\n")
            .trim()
        return RootCommandResult(
            ok = exitCode == 0,
            exitCode = exitCode,
            output = output,
            truncated = truncated,
            errorCode = if (exitCode == 0) null else ERROR_COMMAND_FAILED,
        )
    }

    /** 读流与超时等待：读在独立线程，避免输出撑满管道把子进程卡住。 */    private fun readWithTimeout(
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
                        // 只截断保留内容，继续排空管道，避免长输出使合法脚本收到写入错误。
                        if (read > remain) truncated = true
                        if (remain > 0) buffer.appendRange(chunk, 0, minOf(read, remain))
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
    const val ERROR_SANDBOX_DECODE_FAILED = "ROOT_SANDBOX_DECODE_FAILED"
    const val ERROR_PERMISSION_DENIED = "ROOT_PERMISSION_DENIED"
    const val ERROR_COMMAND_FAILED = "ROOT_COMMAND_FAILED"

    private val DENIED_MARKERS = listOf("not allowed to", "permission denied", "operation not permitted")
}
