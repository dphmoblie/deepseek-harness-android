package io.deepseekharness.mobile

/** 入口页的 WebView 加载策略，与 WebSettings 的 LOAD_* 取值一一对应。 */
internal enum class HarnessCacheMode {
    /** 按正常 HTTP 语义使用缓存（WebSettings.LOAD_DEFAULT）。 */
    NORMAL,

    /** 完全绕开缓存（WebSettings.LOAD_NO_CACHE）。 */
    BYPASS,
}

/**
 * 入口页缓存策略：只有用真实的两维版本键拼出来的 URL 才允许走正常缓存。
 *
 * 为什么把这条判断做成可测的策略、而不是在 Activity 里写死 LOAD_DEFAULT：入口 index.html
 * 没有内容哈希，它能否被安全复用完全取决于 URL 是否唯一标识「哪个 APK 的哪个运行时前端」。
 * 把前提写成代码后，将来若出现漏传版本键的调用点，代价是「退回不缓存」（慢一次），
 * 而不是「命中旧入口页」（用户看到旧前端且无从察觉）。
 */
internal object HarnessPageCache {
    fun modeFor(entryUrl: String): HarnessCacheMode {
        val key = HarnessPageUrl.versionCacheKey(entryUrl) ?: return HarnessCacheMode.BYPASS
        // 占位值只说明「运行时版本读不到」，它并不指向任何一份真实的前端产物：若允许缓存，
        // 所有未知态就会共享同一条入口页，而且清单版本恰好写成 none 时还会与未知态撞键。
        // 因此未知态一律绕开缓存——这正是没有这次改动之前的既有行为，不会把问题放大。
        return if (key.second == HarnessPageUrl.UNKNOWN_RUNTIME_VERSION) {
            HarnessCacheMode.BYPASS
        } else {
            HarnessCacheMode.NORMAL
        }
    }
}
