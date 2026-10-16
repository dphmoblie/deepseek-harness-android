package io.deepseekharness.mobile.shizuku

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** 合并前台回调；Binder 换代后允许重试，卡住的旧任务不能清除新任务。 */
internal class ShizukuAutoConnect(
    private val executor: Executor,
    private val active: () -> Boolean,
    private val connect: (current: () -> Boolean) -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var scheduled = false
    private var requested = false

    fun request(binderChanged: Boolean = false) {
        val current = synchronized(lock) {
            if (!active()) return
            if (binderChanged) {
                generation += 1
                scheduled = false
            }
            requested = true
            if (scheduled) return
            scheduled = true
            generation
        }
        try {
            executor.execute { run(current) }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) {
                if (generation == current) {
                    scheduled = false
                    requested = false
                }
            }
        }
    }

    private fun run(current: Long) {
        while (true) {
            synchronized(lock) {
                if (generation != current) return
                if (!requested || !active()) {
                    scheduled = false
                    requested = false
                    return
                }
                requested = false
            }
            try {
                connect { active() && synchronized(lock) { generation == current } }
            } catch (_: Throwable) {
                // 自动连接是可选能力；失败后允许下次前台或 Binder 回调重试。
            }
        }
    }
}
