package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 远程清理只允许一次在途；调用方超时后，仍等待旧清理完成才允许重新绑定。 */
internal class ShizukuStopGate(private val stop: () -> Unit) {
    companion object {
        // The UserServiceArgs are process-wide (same component/tag/version).  Keep the
        // in-flight stop process-wide too: a timed-out old runtime must not be followed by
        // a new runtime binding that the old unbind(..., remove=true) could tear down.
        private val processLock = Any()
        private var processPending: FutureTask<Unit>? = null
    }

    fun isStopping(): Boolean = synchronized(processLock) { processPending?.isDone == false }

    fun await(timeoutMillis: Long = 10_000) {
        val task = synchronized(processLock) {
            processPending?.takeUnless { it.isDone } ?: FutureTask { stop() }.also { created ->
                processPending = created
                try {
                    Thread(created, "dsh-shizuku-stop").apply { isDaemon = true }.start()
                } catch (error: Throwable) {
                    // A failed thread start never runs the task/finally block. Do not leave a
                    // phantom process-wide stop forever preventing every future Shizuku bind.
                    if (processPending === created) processPending = null
                    throw error
                }
            }
        }
        try {
            task.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // 不取消旧任务：Binder 不保证响应中断，必须保留在途标记防止误清新连接。
            throw RuntimeFailure("SHIZUKU_UNBIND_TIMEOUT", "等待 Shizuku 用户服务退出超时，请在 Shizuku 中重启服务")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeFailure("SHIZUKU_UNBIND_INTERRUPTED", "等待 Shizuku 用户服务退出被中断", error)
        } catch (error: ExecutionException) {
            val cause = error.cause
            throw cause as? RuntimeFailure
                ?: RuntimeFailure("SHIZUKU_UNBIND_FAILED", "无法停止 Shizuku 用户服务", cause ?: error)
        }
    }
}
