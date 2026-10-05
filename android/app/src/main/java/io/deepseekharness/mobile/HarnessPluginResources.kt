package io.deepseekharness.mobile

import java.net.URI

/** 插件市场的跨域只读资源；页面导航与本机会话鉴权仍由宿主单独处理。 */
internal object HarnessPluginResources {
    fun allows(raw: String, method: String, mainFrame: Boolean): Boolean {
        // 限定已核实的市场来源和读取方法，不为任意网址转发本机凭据。
        if (mainFrame || method !in setOf("GET", "HEAD", "OPTIONS") || raw.length > 8192) return false
        if (raw.any { it.isISOControl() || it == '\\' }) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("dsh-market.com", ignoreCase = true) &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null
    }
}
