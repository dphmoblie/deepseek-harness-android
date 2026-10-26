package io.deepseekharness.mobile

import android.webkit.WebView

/**
 * 往 Harness 页面注入「预览顶栏分享」脚本。
 *
 * 官方前端是上游包，不能直接改它的源码；适配脚本只补一个按钮，并把点击转成
 * `dsh-share://` 导航，交给 [HarnessActivity] 的 WebViewClient 拦截。放在原生侧注入
 * 而不是写进前端适配层，有两个原因：
 * 1. 分享能力只存在于应用内 WebView，外置浏览器里按钮没有意义；
 * 2. 上游版本切换时不依赖 dist 里的适配文件是否被替换，按钮始终随应用版本走。
 *
 * 与 [HarnessPolyfill] 相同，只在 `onPageFinished` 之后注入；脚本本身幂等，
 * 重复执行只补扫一遍，不会叠加观察器。
 */
internal object HarnessWorkspaceShare {
    private const val SCRIPT_ASSET = "support/harness-workspace-share.js"

    fun apply(webView: WebView) {
        webView.post {
            val script = try {
                webView.context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                // 资源缺失说明打包不完整；静默跳过，不影响页面本身使用。
                return@post
            }
            try {
                webView.evaluateJavascript(script, null)
            } catch (_: Exception) {
                // 页面正在销毁：下一次加载会重新注入，不需要打断用户。
            }
        }
    }
}
