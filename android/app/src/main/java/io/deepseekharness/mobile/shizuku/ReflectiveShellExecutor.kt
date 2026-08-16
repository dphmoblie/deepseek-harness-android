package io.deepseekharness.mobile.shizuku

import android.content.pm.PackageManager
import io.deepseekharness.mobile.runtime.RuntimeFailure
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 通过反射调用 shizuku-api 13.1.5 私有方法 [Shizuku.newProcess] 获得 shell uid 2000
 * 特权进程，用于执行一次性设备自动化命令（screencap/uiautomator/input 等），
 * 与 UserService PTY 交互终端互补。
 *
 * 依据（docs/legacy/v2-bridge.md §4）：`Shizuku.newProcess` 在 13.1.5 中存在但为
 * 私有，且 13.2.0 不存在于 Maven Central；依赖固定 13.1.5 以冻结反射签名。
 * 返回的 `ShizukuRemoteProcess` 继承 java.lang.Process，按普通 Process 消费；
 * 若上游未来公开 newProcess，改为直接调用即可，签名不变。
 *
 * 失效即关（fail-closed）：Shizuku 不可用、未授权、反射失败一律抛 RuntimeFailure，
 * 绝不静默空转，绝不崩溃。
 */
object ReflectiveShellExecutor : ShellCommandRunner {

    /** 单次执行文本输出上限（stdout 或 stderr） */
    private const val MAX_TEXT_BYTES = 256 * 1024

    /** 单次执行二进制输出上限（如 screencap PNG） */
    private const val MAX_BINARY_BYTES = 8 * 1024 * 1024

    /** 缓存私有 Shizuku.newProcess 的反射句柄，避免重复 getDeclaredMethod */
    @Volatile
    private var newProcessMethod: Method? = null

    override fun exec(cmd: Array<String>, timeoutMs: Long, wantBinary: Boolean): ShellExecResult {
        requireAvailable()
        val env = arrayOf("PATH=/system/bin:/system/xbin:/sbin:/vendor/bin")
        val process = try {
            newProcessViaReflection(cmd, env, null)
        } catch (error: Throwable) {
            throw RuntimeFailure("SHIZUKU_EXEC_FAILED", "无法创建 Shizuku 特权进程", error)
        }
        // 双线程并发排空 stdout/stderr，避免子进程因管道写满而阻塞（死锁防护）
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val outCapped = booleanArrayOf(false)
        val outThread = Thread {
            outCapped[0] = drain(process.inputStream, outBuf, if (wantBinary) MAX_BINARY_BYTES else MAX_TEXT_BYTES)
        }
        val errThread = Thread { drain(process.errorStream, errBuf, MAX_TEXT_BYTES) }
        outThread.start()
        errThread.start()
        val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) {
            process.destroy()
            throw RuntimeFailure("SHIZUKU_EXEC_TIMEOUT", "特权命令执行超时")
        }
        outThread.join(2_000)
        errThread.join(2_000)
        val code = process.exitValue()
        return if (wantBinary) {
            ShellExecResult(
                exitCode = code,
                stdout = "",
                stderr = errBuf.toString("UTF-8"),
                base64 = Base64.getEncoder().encodeToString(outBuf.toByteArray()),
                truncated = outCapped[0],
            )
        } else {
            ShellExecResult(
                exitCode = code,
                stdout = outBuf.toString("UTF-8"),
                stderr = errBuf.toString("UTF-8"),
                truncated = outCapped[0],
            )
        }
    }

    private fun requireAvailable() {
        val available = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (!available) {
            throw RuntimeFailure("SHIZUKU_UNAVAILABLE", "Shizuku 不可用或未授权")
        }
    }

    /** 反射调用私有 Shizuku.newProcess(String[], String[], String)，签名由 13.1.5 冻结 */
    private fun newProcessViaReflection(cmd: Array<String>, env: Array<String>, dir: String?): java.lang.Process {
        val method = newProcessMethod ?: Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            .also {
                it.isAccessible = true
                newProcessMethod = it
            }
        return method.invoke(null, cmd, env, dir) as java.lang.Process
    }

    /**
     * 读取至 EOF 或达到上限；返回是否截断。
     * 截断后继续消费剩余输出并丢弃，防止子进程因管道写满而阻塞。
     */
    private fun drain(input: InputStream, out: ByteArrayOutputStream, cap: Int): Boolean {
        val buf = ByteArray(16 * 1024)
        var total = 0
        var truncated = false
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (total + n > cap) {
                    out.write(buf, 0, cap - total)
                    truncated = true
                    while (input.read(buf) >= 0) { /* 丢弃剩余输出，保持管道畅通 */ }
                    break
                }
                out.write(buf, 0, n)
                total += n
            }
        } catch (_: Exception) {
            // 流关闭或中断——仍返回已读取的部分输出
        } finally {
            try {
                input.close()
            } catch (_: Exception) {
                // 流可能已被对端关闭
            }
        }
        return truncated
    }
}
