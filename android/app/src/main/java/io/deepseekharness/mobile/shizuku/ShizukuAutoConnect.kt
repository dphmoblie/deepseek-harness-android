package io.deepseekharness.mobile.shizuku

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 合并前台回调；Binder 换代后允许重试，卡住的旧任务不能清除新任务。
 *
 * 「这一代还算数吗」在本类里出现三次：[request] 排队失败时、[run] 醒来时、以及交给
 * [connect] 的那个判据。三处都读同一个 [generation]，靠 [stillCurrentLocked] 统一判法——
 * 少判一处就会出现「上一代的任务把新一代的排队标记清掉」这类缺陷。
 */
internal class ShizukuAutoConnect(
    private val executor: Executor,
    private val active: () -> Boolean,
    private val connect: (stillCurrent: () -> Boolean) -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var scheduled = false
    private var requested = false

    /** 本代仍有效吗？调用方必须已持有 [lock]。 */
    private fun stillCurrentLocked(at: Long): Boolean = generation == at

    /** 本代仍有效吗？（自己取锁） */
    private fun stillCurrent(at: Long): Boolean = synchronized(lock) { stillCurrentLocked(at) }

    fun request(binderChanged: Boolean = false) {
        val generationAtSchedule = synchronized(lock) {
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
            executor.execute { run(generationAtSchedule) }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) {
                if (stillCurrentLocked(generationAtSchedule)) {
                    scheduled = false
                    requested = false
                }
            }
        }
    }

    private fun run(generationAtSchedule: Long) {
        while (true) {
            synchronized(lock) {
                if (!stillCurrentLocked(generationAtSchedule)) return
                if (!requested || !active()) {
                    scheduled = false
                    requested = false
                    return
                }
                requested = false
            }
            try {
                connect { active() && stillCurrent(generationAtSchedule) }
            } catch (_: Throwable) {
                // 自动连接是可选能力；失败后允许下次前台或 Binder 回调重试。
            }
        }
    }
}
