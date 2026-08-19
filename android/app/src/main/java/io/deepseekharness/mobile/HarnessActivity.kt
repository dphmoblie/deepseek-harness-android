package io.deepseekharness.mobile

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import io.deepseekharness.mobile.runtime.HarnessAccess
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.ByteArrayInputStream

class HarnessActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var allowedOrigin: Origin
    private val pageLoadGate = HarnessPageLoadGate()
    private var pageFailureHandled = false

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

        val cookieOrigin = HarnessSessionCookie.origin(allowedOrigin.port)
        val authenticationCookie = try {
            HarnessSessionCookie.authenticated(access.password)
        } catch (_: IllegalArgumentException) {
            finish()
            return
        }

        setContentView(R.layout.activity_harness)
        val toolbar = findViewById<Toolbar>(R.id.harness_toolbar)
        toolbar.inflateMenu(R.menu.harness_toolbar)
        toolbar.setNavigationOnClickListener { returnToMainActivity() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_harness_management) {
                returnToMainActivity()
                true
            } else {
                false
            }
        }

        webView = findViewById(R.id.harness_web_view)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
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
        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        webView.webViewClient = RestrictedWebViewClient(
            allowedOrigin,
            access.username,
            access.password,
            ::handleMainFrameFailure,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(applicationContext, null)
        }

        // WebSocket 的 Basic challenge 不会触发 onReceivedHttpAuthRequest，因此使用
        // JS 不可读的同源 Cookie。必须等异步写入确认并落盘后再发起首个页面请求。
        cookieManager.setCookie(cookieOrigin, authenticationCookie) { accepted ->
            when (pageLoadGate.onCookieStored(accepted)) {
                CookieLoadDecision.LOAD -> {
                    cookieManager.flush()
                    webView.loadUrl(HarnessPageUrl.withAppVersion(allowedOrigin.initialUrl, BuildConfig.VERSION_NAME))
                }
                CookieLoadDecision.REJECT -> {
                    Toast.makeText(this, R.string.harness_session_failed, Toast.LENGTH_SHORT).show()
                    returnToMainActivity()
                }
                CookieLoadDecision.IGNORE -> Unit
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else returnToMainActivity()
            }
        })
    }

    override fun onDestroy() {
        pageLoadGate.cancel()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
        if (!isChangingConfigurations) {
            AppAuthenticationState.revokeHarness()
        }
        if (::allowedOrigin.isInitialized && !isChangingConfigurations) {
            // 清除注入的鉴权 Cookie：token 每次启动重新生成，旧值无意义。
            val cookieManager = CookieManager.getInstance()
            cookieManager.setCookie(
                HarnessSessionCookie.origin(allowedOrigin.port),
                HarnessSessionCookie.expired(),
            ) { cookieManager.flush() }
        }
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        super.onDestroy()
    }

    private fun returnToMainActivity() {
        if (!isFinishing) finish()
    }

    private fun handleMainFrameFailure() {
        if (pageFailureHandled || isFinishing || isDestroyed) return
        pageFailureHandled = true
        Toast.makeText(this, R.string.harness_page_failed, Toast.LENGTH_SHORT).show()
        returnToMainActivity()
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
        private val onMainFrameFailure: () -> Unit,
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

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) onMainFrameFailure()
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
