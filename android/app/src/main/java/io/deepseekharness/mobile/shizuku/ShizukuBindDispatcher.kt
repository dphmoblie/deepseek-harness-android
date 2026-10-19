package io.deepseekharness.mobile.shizuku

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 调度一次 UserService 绑定，并在真正发起 Binder RPC 前再次确认连接仍有效。
 *
 * 绑定请求可能在停止流程中排队；这里的二次检查避免旧代次在停止后误绑定，
 * 同时让 [ShizukuRuntime] 的同步 Binder 调用始终落在后台执行器上。
 */
internal class ShizukuBindDispatcher(private val executor: Executor) {
    /** UserServiceArgs 是进程级的：旧的绑定卡住时，绝不与新的绑定重叠。 */
    private companion object {
        val processBindGate = Semaphore(1, true)
    }
    private val closed = AtomicBoolean(false)

    fun submit(
        stillCurrent: () -> Boolean,
        bind: () -> Unit,
        fail: (Throwable) -> Unit,
    ): Boolean {
        if (closed.get()) return false
        return try {
            executor.execute {
                if (closed.get() || !stillCurrent()) {
                    fail(IllegalStateException("Shizuku binding was cancelled"))
                    return@execute
                }
                try {
                    processBindGate.acquire()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    fail(error)
                    return@execute
                }
                try {
                    if (closed.get() || !stillCurrent()) {
                        fail(IllegalStateException("Shizuku binding was cancelled"))
                        return@execute
                    }
                    bind()
                } catch (error: Throwable) {
                    fail(error)
                } finally {
                    processBindGate.release()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    fun shutdown() {
        closed.set(true)
    }
}
