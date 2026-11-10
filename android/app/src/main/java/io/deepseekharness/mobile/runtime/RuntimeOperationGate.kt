package io.deepseekharness.mobile.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 长操作闸门：安装、修复、版本切换这类操作会在**下载/解压/访客进程**上停留数分钟。
 *
 * 它们持有生命周期锁是必要的（同一时间只能有一个写 rootfs 的人），但起停请求不能跟着排队：
 * 一次 npm 安装最长 20 分钟，排队中的 `startHarness` 只会让界面停在「正在进入对话」，
 * 等安装结束再**悄悄把 Harness 拉起来**——用户既不知道自己在等什么，也失去了对启动的决定权。
 *
 * 所以这里提供的是两道短回答：
 *  - [withStartLock]：起停入口在拿锁前后检查，锁竞争最多等待 100ms（受控码 [BUSY_CODE]）；
 *  - [run]：长操作之间也互斥，重复提交同样立刻失败，而不是排在第一个后面继续等。
 *
 * 读状态、写终端、插件列表不登记长操作；启动等待它们释放生命周期锁时同样有上限。
 */
internal class RuntimeOperationGate {
    private val active = AtomicBoolean(false)

    /** 是否存在正在进行的长操作；给起停入口在排队前做判断。 */
    fun isActive(): Boolean = active.get()

    /** 起停入口用：有长操作时不进入生命周期锁队列。 */
    fun requireIdle() {
        if (active.get()) throw busy()
    }

    /** 长操作可能在前置检查后抢先拿锁，因此等待有上限，拿到锁后也必须重新检查。 */
    fun <T> withStartLock(lock: ReentrantLock, block: () -> T): T {
        requireIdle()
        val acquired = try {
            lock.tryLock(100, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw busy(error)
        }
        if (!acquired) throw busy()
        return try {
            requireIdle()
            block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * 登记一次长操作；已经有一个在跑时立刻失败，而不是排队等待。
     *
     * 必须与 [end] 成对使用，调用方用 `try/finally` 保证异常路径也会释放。
     */
    fun begin() {
        if (!active.compareAndSet(false, true)) throw busy()
    }

    fun end() {
        active.set(false)
    }

    /**
     * 执行一次长操作，并把它标记为「正在进行」。
     *
     * 已经有一个长操作时立刻失败而不是等待：两个安装/修复同时排队没有任何收益，
     * 用户看到的只会是第二次点击迟迟没有回应。
     */
    fun <T> run(block: () -> T): T {
        begin()
        return try {
            block()
        } finally {
            end()
        }
    }

    private fun busy(cause: Throwable? = null) = RuntimeFailure(BUSY_CODE, "运行环境或 Harness 版本操作正在进行，请完成后再试", cause)

    companion object {
        /**
         * 受控错误码：界面据此说明「先等长操作完成」，而不是误报成要求停止 Harness。
         */
        const val BUSY_CODE = "RUNTIME_OPERATION_BUSY"
    }
}
