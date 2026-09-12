package io.deepseekharness.mobile

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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

    /** 等待用户从系统选择器返回的 WebView 回调；同一时刻只允许一个，页面靠它继续上传。 */
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    /**
     * 单选：用 SAF 的 OpenDocument，保证返回 content:// 且能按 MIME 过滤（页面请求图片类型时
     * 会直接进相册）。刻意不用 ACTION_GET_CONTENT：部分 provider 会返回 file://，而 WebView 的
     * allowFileAccess 保持关闭。
     */
    private val singleFileChooser = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> deliverFileChooserResult(listOfNotNull(uri)) }

    private val multipleFileChooser = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> deliverFileChooserResult(uris) }

    companion object {
        const val AUTH_TOKEN_COOKIE = "dsh_mobile_token"

        /**
         * 任意 MIME 类型。
         *
         * 单独放成常量有两个原因：SAF 既不接受扩展名过滤也不接受空数组，必须回退到它；
         * 同时避免这个字面量散落在注释密集的代码里（Kotlin 块注释可嵌套，注释中一旦出现
         * 斜杠加星号就会把后续代码吞进注释）。
         */
        private const val ANY_MIME_TYPE = "*/*"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
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
        if (RuntimeStore(this).keepScreenAwake()) {
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
            // 必须允许 content:// 访问，否则 <input type="file"> 选择结果（SAF 返回的都是
            // content:// URI）无法被 WebView 读取，系统文件选择器等于白弹。
            // 页面自身发起的 content:// 加载仍然被 RestrictedWebViewClient 拦成 403，
            // 因此这里放开的是"读取用户在系统选择器里明确选中的文件"，而不是任意 provider 读取。
            allowContentAccess = true
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
        // 没有 WebChromeClient 时 <input type="file"> 点了毫无反应：这是插件"从相册导入"
        // 这类入口在手机上完全不可用的根因（皮肤中心只能靠手填容器路径绕过）。
        webView.webChromeClient = HarnessWebChromeClient()
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
        // 页面可能仍在等待选择结果：必须显式回传 null，否则该 input 会永久处于"等待选择文件"。
        deliverFileChooserResult(emptyList())
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

    /**
     * 把系统选择器的结果交回 WebView。
     *
     * 无论用户选中、取消还是选择被系统中断，都必须回调一次：
     *  - 有结果 → 只保留 content:// URI，其余一律丢弃（WebView 只允许读 content://）；
     *  - 无结果 → 回传 null，让页面恢复可交互。
     */
    private fun deliverFileChooserResult(uris: List<Uri>) {
        val callback = pendingFileChooser ?: return
        pendingFileChooser = null
        val accepted = uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        try {
            callback.onReceiveValue(accepted.takeIf { it.isNotEmpty() }?.toTypedArray())
        } catch (_: Throwable) {
            // 回调属于 WebView 内部状态：失败也不得把 Activity 带崩。
        }
    }

    /**
     * 只为 `<input type="file">` 服务：没有它，页面上任何"选择文件"入口在手机上都是死按钮。
     *
     * 其余 WebChromeClient 能力（JS 弹窗、控制台、地理位置等）一概不重写，保持系统默认行为。
     */
    private inner class HarnessWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?,
        ): Boolean {
            if (filePathCallback == null) return false
            // 上一次选择还没回来就又触发一次：先取消旧的，避免页面拿到错位的结果。
            deliverFileChooserResult(emptyList())
            pendingFileChooser = filePathCallback
            // 只接受标准 MIME（含 "/"）；页面给的是扩展名（如 ".png"）时退化为任意类型，
            // 因为 SAF 不接受扩展名过滤。
            val mimeTypes = fileChooserParams?.acceptTypes
                ?.filter { it.contains('/') }
                ?.toTypedArray()
                ?.takeIf { it.isNotEmpty() }
                ?: arrayOf(ANY_MIME_TYPE)
            val multiple = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            return try {
                if (multiple) multipleFileChooser.launch(mimeTypes) else singleFileChooser.launch(mimeTypes)
                true
            } catch (_: Throwable) {
                // 无法拉起选择器时立即回传 null，页面保持可交互；返回 false 让 WebView 走默认
                //（即什么都不做），两者都不会让页面卡住。
                deliverFileChooserResult(emptyList())
                false
            }
        }
    }

    private fun handleMainFrameFailure() {
        if (pageFailureHandled || isFinishing || isDestroyed) return
        pageFailureHandled = true
        Toast.makeText(this, R.string.harness_page_failed, Toast.LENGTH_SHORT).show()
        returnToMainActivity()
    }

    private class Origin(val scheme: String, val host: String, val port: Int, val initialUrl: String) {
        fun allows(uri: Uri): Boolean =
            uri.scheme == scheme && uri.host == host && uri.port == port && uri.userInfo == null

        companion object {
            fun parse(raw: String?): Origin? {
                val uri = HarnessPageUrl.parseEntryUrl(raw) ?: return null
                return Origin(uri.scheme, uri.host, uri.port, uri.toASCIIString())
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
