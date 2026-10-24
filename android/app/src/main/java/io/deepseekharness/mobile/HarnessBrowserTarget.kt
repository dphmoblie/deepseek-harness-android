package io.deepseekharness.mobile

/**
 * 外部浏览器接管时的目标包选择。
 *
 * 为什么需要白名单：接管 URL 的**片段里带着一次性令牌**，把它交给任意能处理
 * `http://` 的 Activity 等于把令牌交给一个未知应用。系统里能声明 VIEW+BROWSABLE
 * 的不只浏览器，所以这里只认已知浏览器，按偏好顺序取第一个真正装着的。
 *
 * 只做纯判断、不碰 PackageManager，方便 JVM 单测直接覆盖；实际枚举在调用方完成。
 */
internal object HarnessBrowserTarget {
    /**
     * 受信任的浏览器包名，顺序即偏好：先用主流浏览器，再退到各厂商自带浏览器。
     *
     * 故意**不**包含 `org.chromium.webview_shell` 这类调试壳：它们能打开页面，
     * 但不该成为用户日常接管的落点。
     */
    private val PREFERRED = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.firefox_nightly",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.gx",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android",
        "com.android.browser",
        "com.miui.browser",
        "com.vivo.browser",
        "com.coloros.browser",
        "com.heytap.browser",
        "com.huawei.browser",
        "com.oneplus.browser",
    )

    /** 从可处理该 URL 的包里挑一个受信任的浏览器；一个都没有时返回 null（调用方报错，不降级给任意应用）。 */
    fun pick(candidates: Collection<String>): String? = PREFERRED.firstOrNull { it in candidates }

    /** 供单测断言白名单不重复、不含调试壳。 */
    internal fun preferredPackages(): List<String> = PREFERRED
}
