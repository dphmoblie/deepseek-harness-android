package io.deepseekharness.mobile.runtime

/**
 * Harness 对话页面的网络访问档位。
 *
 * 页面只从本机回环地址加载；默认除本机源与插件市场外一律拦截。三档**递进**：
 *
 *  - [APP]：只允许本机 Harness 源与插件市场资源站（默认，等同引入该设置前的行为）；
 *  - [HTTP]：额外允许任意主机的明文 `http`，用于局域网里的自建网关、本地 MCP；
 *  - [ALL]：任意主机、任意协议（含公网 `https`）。
 *
 * 它只影响**页面自己发起**的请求，不改变外部主框架导航的拦截（见
 * `HarnessActivity.RestrictedWebViewClient.shouldOverrideUrlLoading`），也不改变插件市场
 * 那条固定来源的规则。缺键或旧值损坏时按 [APP] 处理：读不出来就放开是最坏的默认值。
 */
enum class PageNetworkAccess(val wireValue: String) {
    APP("app"),
    HTTP("http"),
    ALL("all"),
    ;

    companion object {
        fun parse(value: String): PageNetworkAccess? = entries.firstOrNull { it.wireValue == value }

        fun fromStored(value: String?): PageNetworkAccess = value?.let(::parse) ?: APP
    }
}
