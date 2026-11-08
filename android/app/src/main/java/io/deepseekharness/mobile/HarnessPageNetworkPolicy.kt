package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.PageNetworkAccess
import java.net.URI
import java.util.Locale

/**
 * 「页面能连哪些地址」的放行规则。
 *
 * 与 [HarnessPluginResources] 的位置关系：本机源、插件市场、外壳背景是**始终放行**的固定来源，
 * 由调用方先判断；这里只回答「此外的跨源请求是否因为用户选的档位而放行」。
 *
 * 三条硬约束，任何档位都不放开：
 *  - 主框架请求一律不放行外部地址（页面换站由 `shouldOverrideUrlLoading` 拦，导航能力属于另一件事）；
 *  - 只认 `http` / `https`：`file:`、`content:`、`intent:` 这些协议不因为放开网络而可用；
 *  - 拒绝带 userinfo 的地址，避免把凭据写进页面可见的请求 URL。
 *
 * 纯函数、只依赖字符串：WebView 的请求对象不好构造，规则必须能脱离真机单测。
 */
internal object HarnessPageNetworkPolicy {
    /** 与插件市场规则同一个上限：超长 URL 属于畸形请求，不解析也不放行。 */
    const val MAX_URL_CHARS = 8192

    fun allows(mode: PageNetworkAccess, raw: String, mainFrame: Boolean): Boolean {
        if (mainFrame) return false
        if (raw.isEmpty() || raw.length > MAX_URL_CHARS) return false
        // `\` 在部分解析器里会被当成 `/`：拒绝它，避免同一地址出现两种解释。
        if (raw.any { it.isISOControl() || it == '\\' }) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrEmpty()) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port !in 1..65535) return false
        return when (mode) {
            PageNetworkAccess.APP -> false
            PageNetworkAccess.HTTP -> scheme == "http"
            PageNetworkAccess.ALL -> true
        }
    }
}
