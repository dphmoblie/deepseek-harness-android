package io.deepseekharness.mobile

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.deepseekharness.mobile.runtime.HarnessAccess
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.ByteArrayInputStream

class HarnessActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var allowedOrigin: Origin

    companion object {
        const val AUTH_TOKEN_COOKIE = "dsh_mobile_token"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppAuthenticationState.isHarnessAuthenticated()) {
            finish()
            return
        }
        val access = AppAuthenticationState.harnessAccess() ?: run {
            finish()
            return
        }
        allowedOrigin = Origin.parse(access.url) ?: run {
            finish()
            return
        }
        if (RuntimeStore(this).settings().keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
            displayZoomControls = false
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
            // WebView 的 WS 握手 401 不触发 onReceivedHttpAuthRequest，Basic 挑战
            // 对 WebSocket 无效；注入 HttpOnly Cookie 供 preload 的 upgrade 路径
            // 鉴权（JS 不可读，仅随同源请求与 WS 握手自动携带）。
            setCookie(
                "http://127.0.0.1:${allowedOrigin.port}",
                "$AUTH_TOKEN_COOKIE=${access.password}; Path=/; HttpOnly",
            )
        }
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        webView.webViewClient = RestrictedWebViewClient(allowedOrigin, access.username, access.password)
        setContentView(webView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(applicationContext, null)
        }
        webView.loadUrl(allowedOrigin.initialUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
        if (::allowedOrigin.isInitialized) {
            // 清除注入的鉴权 Cookie：token 每次启动重新生成，旧值无意义
            CookieManager.getInstance().setCookie(
                "http://127.0.0.1:${allowedOrigin.port}",
                "$AUTH_TOKEN_COOKIE=; Max-Age=0",
            )
        }
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            AppAuthenticationState.revokeHarness()
            finish()
        }
    }

    private data class Origin(val scheme: String, val host: String, val port: Int, val initialUrl: String) {
        fun allows(uri: Uri): Boolean =
            uri.scheme == scheme && uri.host == host && uri.port == port && uri.userInfo == null

        companion object {
            fun parse(raw: String?): Origin? {
                if (raw.isNullOrEmpty() || raw.length > 256) return null
                val uri = Uri.parse(raw)
                if (
                    uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port !in 1024..65535 ||
                    uri.userInfo != null || uri.query != null || uri.fragment != null ||
                    (uri.path != null && uri.path != "" && uri.path != "/")
                ) return null
                val normalized = "http://127.0.0.1:${uri.port}/"
                return Origin("http", "127.0.0.1", uri.port, normalized)
            }
        }
    }

    private class RestrictedWebViewClient(
        private val origin: Origin,
        private val username: String,
        private val password: String,
    ) : WebViewClient() {
        override fun onReceivedHttpAuthRequest(
            view: WebView?,
            handler: HttpAuthHandler?,
            host: String?,
            realm: String?,
        ) {
            if (host == origin.host && realm == HarnessAccess.REALM) {
                handler?.proceed(username, password)
            } else {
                handler?.cancel()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return true
            return !origin.allows(uri)
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val uri = request?.url ?: return blockedResponse()
            return if (origin.allows(uri)) null else blockedResponse()
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
            handler?.cancel()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            view?.destroy()
            (view?.context as? HarnessActivity)?.finish()
            return true
        }

        private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}
