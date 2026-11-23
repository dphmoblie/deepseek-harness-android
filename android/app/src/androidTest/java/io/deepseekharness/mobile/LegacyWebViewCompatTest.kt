package io.deepseekharness.mobile

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在**真实系统 WebView** 里把 dsh 官方前端产物跑一遍，专抓「旧内核缺函数 / 解析失败」。
 *
 * 为什么必须上设备：`scripts/webview-compat-scan.mjs` 只能静态数调用点，`node:vm` 里的假内核
 * 只能证明补丁语义。产品上真正发生的故障是「安卓 10 出厂 WebView（Chromium 74）把
 * `?.` 当 SyntaxError」和「点开面板报 `X is not a function`」——只有真的把产物塞进系统 WebView
 * 才看得见。CI 里这个用例挂在 API 29 模拟器上（见 `.github/workflows/android-build.yml`
 * 的 `webview-android10` 作业，默认只在 workflow_dispatch 里跑）。
 *
 * 断言三件事：
 * 1. 页面**没有**解析期/API 缺失类错误（`SyntaxError`、`is not a function`、`is not defined`）；
 * 2. `#root` 真的被渲染出来（产物确实执行了，而不是白屏停在空壳）；
 * 3. 注入脚本补的那些 API 在页面里都是函数（缺哪个，失败信息里直接点名）。
 *
 * 明确不管的错误：没有 dsh 后端时前端必然报网络/鉴权失败（`Failed to fetch`、401/500），
 * 那是用例环境造成的，不是兼容性问题，不能算失败。
 */
@RunWith(AndroidJUnit4::class)
class LegacyWebViewCompatTest {
    @Test
    fun harnessBundleBootsInSystemWebViewWithoutMissingFunctions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // 产物由 build.gradle 的 syncHarnessWebFixture 同步到 androidTest 资源；没构建前端时跳过，
        // 而不是把「没夹具」说成「兼容性通过」。
        val assets = context.assets.list("harness")?.toList().orEmpty()
        assumeTrue("androidTest 资源里没有 harness 产物（先构建 harness-web/dist）", assets.isNotEmpty())

        val origin = "https://appassets.androidplatform.net"
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val console = Collections.synchronizedList(mutableListOf<String>())
        val finished = CountDownLatch(1)
        val webView = AtomicReference<WebView?>()

        instrumentation.runOnMainSync {
            val view = WebView(context)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.databaseEnabled = true
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    console.add("${message.messageLevel()}: ${message.message()}")
                    return true
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit

                override fun onPageFinished(view: WebView, url: String?) {
                    finished.countDown()
                }
            }
            // 与 HarnessActivity 同一条注入路径：document-start 可用时最先执行，不支持时这里
            // 也不会报错（provider 返回 false 后由本用例的 evaluate 兜底断言兜住缺失项）。
            HarnessPolyfill.installDocumentStart(view, origin)
            webView.set(view)
            view.loadUrl("$origin/assets/harness/index.html")
        }

        try {
            assertTrue("页面 20 秒内没有加载完成", finished.await(20, TimeUnit.SECONDS))
            val userAgent = evaluate(webView.get()) { "navigator.userAgent" }
            /*
             * 与生产同一条兜底路径：Chromium 74 没有 DOCUMENT_START_SCRIPT（那是 WebView 83+
             * 的能力），`installDocumentStart` 在它上面是空操作，HarnessActivity 靠
             * `onMainFrameLoaded` 里的 `HarnessPolyfill.apply` 补交互期才调的 API。
             * 用例必须复现这条路径，否则在 74 上会因为「装不上 document-start」而误判失败。
             */
            HarnessPolyfill.apply(webView.get() ?: error("WebView 已被销毁"))
            val rootChildren = waitForRootChildren(webView.get())
            val probes = evaluate(webView.get()) {
                """
                (function () {
                  var names = ['Object.hasOwn', 'Array.prototype.at', 'String.prototype.replaceAll',
                    'Promise.allSettled', 'AbortSignal.any', 'AbortSignal.timeout',
                    'Promise.withResolvers', 'URL.canParse'];
                  var missing = [];
                  for (var i = 0; i < names.length; i += 1) {
                    var path = names[i].split('.');
                    var target = window;
                    for (var j = 0; j < path.length - 1; j += 1) { target = target[path[j]]; }
                    if (!target || typeof target[path[path.length - 1]] !== 'function') { missing.push(names[i]); }
                  }
                  return missing.join(',');
                })()
                """
            }
            val failures = console.filter { line ->
                val text = line.lowercase()
                text.contains("syntaxerror") ||
                    text.contains("is not a function") ||
                    text.contains("is not defined")
            }
            val message = buildString {
                append("UA: ").append(userAgent).append('\n')
                append("SDK: ").append(Build.VERSION.SDK_INT).append('\n')
                append("#root 子节点: ").append(rootChildren).append('\n')
                append("缺失 API: ").append(probes.ifEmpty { "无" }).append('\n')
                append("控制台: ").append(console.joinToString(" | "))
            }
            assertEquals("页面缺少注入脚本承诺的 API：$probes\n$message", "", probes)
            assertEquals("控制台出现兼容类错误：$failures\n$message", emptyList<String>(), failures)
            assertTrue("#root 没有被渲染（产物没执行或白屏）\n$message", rootChildren > 0)
        } finally {
            instrumentation.runOnMainSync {
                webView.get()?.destroy()
            }
        }
    }

    /** 轮询 `#root` 的子节点数；白屏时返回 0，不抛异常。 */
    private fun waitForRootChildren(webView: WebView?): Int {
        val deadline = System.nanoTime() + 20_000_000_000L
        var observed = 0
        while (System.nanoTime() < deadline) {
            observed = evaluate(webView) {
                "(document.getElementById('root') ? document.getElementById('root').childElementCount : 0)"
            }.toIntOrNull() ?: 0
            if (observed > 0) return observed
            Thread.sleep(100)
        }
        return observed
    }

    /** 在主线程上 `evaluateJavascript` 并把结果（JSON 字符串）解码成纯文本。 */
    private fun evaluate(webView: WebView?, script: () -> String): String {
        val view = webView ?: return ""
        val result = AtomicReference("")
        val latch = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script()) { value ->
                result.set(value ?: "")
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return ""
        return result.get().trim().removeSurrounding("\"").replace("\\u003c", "<")
    }
}
