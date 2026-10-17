package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure

/** 排队和等待生命周期锁都计入有效期；取消与最终页面派发共用短锁，慢读取不持锁。 */
internal class HarnessOpenRequests(private val nowMillis: () -> Long) {
    data class Request(val generation: Long, val acceptedAtMillis: Long)

    private var generation = 0L

    @Synchronized
    fun accept(): Request = Request(++generation, nowMillis())

    @Synchronized
    fun invalidate() {
        generation += 1
    }

    fun <T> launch(request: Request, access: () -> T, dispatch: (T) -> Unit) {
        synchronized(this) { requireCurrent(request) }
        val value = access()
        synchronized(this) {
            requireCurrent(request)
            dispatch(value)
        }
    }

    private fun requireCurrent(request: Request) {
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
