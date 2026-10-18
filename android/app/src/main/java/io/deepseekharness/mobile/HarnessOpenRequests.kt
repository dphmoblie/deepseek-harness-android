package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure

/**
 * 排队和等待生命周期锁都计入有效期；慢读取不持锁。
 *
 * 最终派发先在短锁内登记为已提交，再在锁外执行授权/启动。取消不会等待生命周期锁；
 * 已提交的派发会在生命周期复核点感知取消，未进入最终门的请求则立即失效。
 */
internal class HarnessOpenRequests(private val nowMillis: () -> Long) {
    data class Request(val generation: Long, val acceptedAtMillis: Long) {
        var committed: Boolean = false
    }

    private var generation = 0L

    @Synchronized
    fun accept(): Request = Request(++generation, nowMillis())

    /** 短暂复核请求是否仍有效；不等待任何生命周期锁。 */
    fun isCurrent(request: Request): Boolean = synchronized(this) {
        nowMillis() - request.acceptedAtMillis in 0 until TIMEOUT_MS &&
            request.generation == generation
    }

    /** 在外层生命周期锁内原子提交请求；取消要么先赢，要么等待提交后的动作完成。 */
    fun commit(request: Request): Boolean = synchronized(this) {
        if (request.committed) return@synchronized false
        if (runCatching { requireCurrent(request) }.isFailure) return@synchronized false
        request.committed = true
        true
    }

    @Synchronized
    fun invalidate() { generation += 1 }

    fun <T> launch(request: Request, access: () -> T, dispatch: (T) -> Unit) {
        synchronized(this) { requireCurrent(request) }
        val value = access()
        synchronized(this) {
            requireCurrent(request)
        }
        // 授权等生命周期操作在 request 锁外执行；invalidate 不会被长生命周期操作阻塞。
        dispatch(value)
    }

    private fun requireCurrent(request: Request) {
        if (request.committed) {
            throw RuntimeFailure("HARNESS_OPEN_CANCELLED", "打开请求已取消")
        }
        if (request.generation != generation) {
            throw RuntimeFailure("HARNESS_OPEN_CANCELLED", "打开请求已取消")
        }
        if (nowMillis() - request.acceptedAtMillis !in 0 until TIMEOUT_MS) {
            throw RuntimeFailure("BRIDGE_TIMEOUT", "打开请求已超时")
        }
    }

    companion object {
        // 与前端 ACTION_WATCHDOG_MS 保持一致，只限制打开页面，不限制冷启动。
        const val TIMEOUT_MS = 45_000L
    }
}
