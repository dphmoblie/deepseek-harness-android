package io.deepseekharness.mobile

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.getcapacitor.BridgeActivity

/**
 * Capacitor 外壳 Activity，承载管理界面（`src/` 的 React 页面）。
 *
 * 返回键语义与外壳的浏览器历史严格配合：
 *  - WebView 还有可回退的历史 → 先回退历史。外壳每次切换视图都会 `pushState`，
 *    回退后由页面的 `popstate` 把视图恢复成上一级（设置二级页 → 设置一级 → 主视图）；
 *  - 历史已经见底（外壳主视图）→ 把任务退到后台，而不是 `finish()` 掉 Activity：
 *    用户按返回只是要离开当前界面，不希望应用被结束掉，更不希望正在跑的本机运行时被拆掉。
 *
 * 关于「双重处理」：Capacitor 7.4.3 的 `BridgeActivity` 没有实现 `onBackPressed`，
 * 也不注册任何 `OnBackPressedCallback`（已核对上游源码），因此这里注册的回调是返回键的
 * 唯一处理路径。回调被消费后不会再落到 AppCompat 默认的 `finish()`；本类也刻意不重写
 * `onBackPressed()`，避免「既 goBack 又 finish」的两条路径同时生效。
 */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(MobileRuntimePlugin::class.java)
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = bridge?.webView
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                // 历史见底：整个任务退到后台。不调用 finish()，也不回调 super（那会走默认的 finish），
                // 这样 Activity、WebView 历史与本机运行时原样保留，用户从最近任务回来还是原来的界面。
                moveTaskToBack(true)
            }
        })
    }
}
