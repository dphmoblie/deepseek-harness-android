package io.deepseekharness.mobile

import android.webkit.WebView

/**
 * 往 Harness 页面注入「文件面板路径面包屑」脚本。
 *
 * 官方文件面板的顶栏是一整段不可点的静态路径，想退回上一层只能再点一次目录树。
 * 脚本把路径切成逐个目录喂给用户点击，且只能在**会话工作目录及其子目录**里活动
 * （Host 对工作区外的路径一律返回 `workspace-file/outside-workspace`，根之上的路径
 * 只显示、不提供跳转）。放在原生侧注入的原因与 [HarnessWorkspaceShare] 相同：
 * 1. 这是应用内 WebView 的交互补丁，外置浏览器不需要跟着改；
 * 2. 上游版本切换时不依赖 dist 里是否换了适配文件。
 *
 * 与 [HarnessPolyfill] 相同，只在 `onPageFinished` 之后注入；脚本本身幂等，
 * 重复执行只补扫一遍，不会叠加观察器。
 */
internal object HarnessFilesPath {
    private const val SCRIPT_ASSET = "support/harness-files-path.js"

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
