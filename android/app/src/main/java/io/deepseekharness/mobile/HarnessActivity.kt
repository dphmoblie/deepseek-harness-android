package io.deepseekharness.mobile

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import io.deepseekharness.mobile.runtime.HarnessAccess
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class HarnessActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var allowedOrigin: Origin
    private var pageLoadGate = HarnessPageLoadGate()
    private val authenticationOwner = Any()
    private var boundAccess: HarnessAccess? = null
    private var pageFailureHandled = false
    private var pageRetryCount = 0
    private var rendererRecovery = false
    private var entryUrlForRetry: String? = null
    private val pageRetryHandler = Handler(Looper.getMainLooper())

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
        /**
         * HTTP Auth 数据库清理专用线程：进程级单线程、守护线程。
         * 单次调用只做一次毫秒级 SQLite 删除，串行即可，无需随 Activity 关闭。
         */
        private val authDatabaseExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dsh-webview-auth-db").apply { isDaemon = true }
        }

        const val AUTH_TOKEN_COOKIE = "dsh_mobile_token"
        const val EXTRA_SESSION_ID = "io.deepseekharness.mobile.extra.SESSION_ID"
        const val EXTRA_RESET_TO_HOME = "io.deepseekharness.mobile.extra.RESET_TO_HOME"

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

    override fun onResume() {
        super.onResume()
        // 运行时停止或自动重启会撤销上一周期的授权。此 Activity 可能仍在任务栈里，
        // 不能让它继续展示或使用旧的 WebView 凭据；管理页重新打开 Harness 时会重新绑定。
        if (::webView.isInitialized && !AppAuthenticationState.ownsHarnessAccess(authenticationOwner)) {
            finish()
            return
        }
        // 控制台也是一个窗口：主题在 Web 侧改过、或系统深色模式在后台切换过之后，
        // 回到前台都要按已保存的模式重算状态栏，否则它会与页面配色不一致。
        applyNativeChrome()
        // 用户可能刚在外壳里点过「让 DSH 使用当前主题」：从后台回来时补一次，
        // 不要求他手动刷新页面。
        if (::webView.isInitialized) HarnessThemeBridge.apply(webView)
    }

    override fun onStart() {
        super.onStart()
        // 控制台与外壳共用同一个「是否在前台」计数器：控制台叠在外壳之上，
        // 只有两者都停了才算切到后台（用布尔标志会在关闭控制台时误判，见 AppForeground 的说明）。
        AppForeground.onActivityStarted()
    }

    override fun onStop() {
        AppForeground.onActivityStopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val resetToHome = intent.getBooleanExtra(EXTRA_RESET_TO_HOME, false)
        val nextSessionId = sessionIdExtra(intent)
        if (!::webView.isInitialized) return
        val access = AppAuthenticationState.harnessAccess() ?: run {
            finish()
            return
        }
        // 通知回到同一运行周期时保留页面；新运行周期即使没有跳转 extra 也必须重绑。
        if (resetToHome || nextSessionId != null || access != boundAccess ||
            !AppAuthenticationState.ownsHarnessAccess(authenticationOwner)
        ) {
            bindHarnessAccess(access, nextSessionId.takeUnless { resetToHome }, clearHistory = resetToHome)
        }
    }

    /**
     * 读取会话直达的 extra，并把格式不合规的值挡在入口 URL 之外。
     *
     * Activity 不导出（`android:exported="false"`），这个 extra 只可能来自本应用的桥接，
     * 但仍然在边界上收口：非法值按「没有会话」处理——宁可不跳转，也不要让一次畸形参数
     * 把 HarnessActivity 直接崩掉。真正的契约错误（桥接侧传了非法会话键）由桥接层先拦。
     */
    private fun sessionIdExtra(intent: Intent?): String? = try {
        HarnessPageUrl.requireValidSessionId(intent?.getStringExtra(EXTRA_SESSION_ID))
    } catch (_: IllegalArgumentException) {
        null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val access = AppAuthenticationState.harnessAccess() ?: run {
            finish()
            return
        }
        val runtimeStore = RuntimeStore(this)
        if (runtimeStore.keepScreenAwake()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setContentView(R.layout.activity_harness)
        AppWindowInsets.apply(this)
        // 会话页让出整条工具栏空间，返回交给系统手势与返回键。
        applyNativeChrome()

        webView = findViewById(R.id.harness_web_view)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            // 必须允许 content:// 访问，否则 <input type="file"> 选择结果（SAF 返回的都是
            // content:// URI）无法被 WebView 读取，系统文件选择器等于白弹。
            // 页面自身发起的 content:// 加载仍然被 RestrictedWebViewClient 拦成 403，
            // 因此这里放开的是"读取用户在系统选择器里明确选中的文件"，而不是任意 provider 读取。
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            // 默认不放行媒体自播；唯一的例外是「同步中的静音背景视频」，
            // 由 HarnessThemeBridge.syncAutoplay 按背景状态逐次对齐，关掉背景就收回。
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
            displayZoomControls = false
            safeBrowsingEnabled = true
            // 控制台界面的字号跟随系统字号（登记册 5.6-I）：外壳 CSS 已全部 rem 化，
            // 但 WebView 不会因为系统字号变化就改变 rem 基准，只有 textZoom 能带上这件事。
            // fontScale 不在 configChanges 里，系统改字号会重建本 Activity，因此读一次即可。
            textZoom = AppTextScale.percentOf(resources.configuration.fontScale)
        }
        // 没有 WebChromeClient 时 <input type="file"> 点了毫无反应：这是插件"从相册导入"
        // 这类入口在手机上完全不可用的根因（皮肤中心只能靠手填容器路径绕过）。
        webView.webChromeClient = HarnessWebChromeClient()
        // 设置页改「连背景一起同步」时插件在本进程内改档，需要一块还没销毁的 WebView 来重放。
        HarnessThemeBridge.attach(webView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(applicationContext, null)
        }
        bindHarnessAccess(access, sessionIdExtra(intent))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else returnToMainActivity()
            }
        })
    }

    private fun bindHarnessAccess(access: HarnessAccess, sessionId: String?, clearHistory: Boolean = false) {
        val origin = Origin.parse(access.url) ?: run {
            finish()
            return
        }
        val cookie = try {
            HarnessSessionCookie.authenticated(access.password)
        } catch (_: IllegalArgumentException) {
            finish()
            return
        }
        if (!AppAuthenticationState.claimHarnessAccess(authenticationOwner, access)) {
            finish()
            return
        }
        var discardHistory = clearHistory || boundAccess != access
        pageLoadGate.cancel()
        val gate = HarnessPageLoadGate()
        pageLoadGate = gate
        pageRetryHandler.removeCallbacksAndMessages(null)
        pageFailureHandled = false
        pageRetryCount = 0
        deliverFileChooserResult(emptyList())
        webView.stopLoading()
        if (discardHistory) webView.clearHistory()
        boundAccess = access
        allowedOrigin = origin
        val entryUrl = HarnessPageUrl.withVersions(
            origin.initialUrl,
            BuildConfig.VERSION_NAME,
            RuntimeStore(this).installedManifest()?.version,
            sessionId,
        )
        entryUrlForRetry = entryUrl
        webView.settings.cacheMode = when (HarnessPageCache.modeFor(entryUrl)) {
            HarnessCacheMode.NORMAL -> WebSettings.LOAD_DEFAULT
            HarnessCacheMode.BYPASS -> WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = RestrictedWebViewClient(
            origin, access.username, access.password,
            onMainFrameFailure = {
                if (pageLoadGate === gate && AppAuthenticationState.ownsHarnessAccess(authenticationOwner)) {
                    handleMainFrameFailure()
                }
            },
            onMainFrameLoaded = { view ->
                if (discardHistory && pageLoadGate === gate && view.url == entryUrl) {
                    discardHistory = false
                    view.clearHistory()
                }
                // 每次主框架加载完都重放一次已同步的主题；没有同步过时它什么都不做。
                HarnessThemeBridge.apply(view)
            },
        )
        clearHttpAuthCredentialsAsync()
        val cookies = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        // 每轮绑定单独持有 gate；上一轮迟到的 Cookie 回调不能触发新一轮导航。
        cookies.setCookie(HarnessSessionCookie.origin(origin.port), cookie) { accepted ->
            pageRetryHandler.post {
                if (isFinishing || isDestroyed || pageLoadGate !== gate ||
                    !AppAuthenticationState.ownsHarnessAccess(authenticationOwner)
                ) return@post
                when (gate.onCookieStored(accepted)) {
                    CookieLoadDecision.LOAD -> {
                        cookies.flush()
                        webView.loadUrl(entryUrl)
                    }
                    CookieLoadDecision.REJECT -> {
                        Toast.makeText(this, R.string.harness_session_failed, Toast.LENGTH_SHORT).show()
                        returnToMainActivity()
                    }
                    CookieLoadDecision.IGNORE -> Unit
                }
            }
        }
    }

    override fun onDestroy() {
        pageLoadGate.cancel()
        if (!isChangingConfigurations) OverlayBallService.closeConversation()
        // 页面可能仍在等待选择结果：必须显式回传 null，否则该 input 会永久处于"等待选择文件"。
        deliverFileChooserResult(emptyList())
        if (::webView.isInitialized) {
            HarnessThemeBridge.detach(webView)
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
        pageRetryHandler.removeCallbacksAndMessages(null)
        val revoked = !isChangingConfigurations && !rendererRecovery &&
            AppAuthenticationState.revokeHarness(authenticationOwner)
        val authWasInvalidated = !AppAuthenticationState.isHarnessAuthenticated()
        if (::allowedOrigin.isInitialized && !isChangingConfigurations && !rendererRecovery &&
            (revoked || authWasInvalidated)) {
            // 清除注入的鉴权 Cookie：token 每次启动重新生成，旧值无意义。
            val cookieManager = CookieManager.getInstance()
            cookieManager.setCookie(
                HarnessSessionCookie.origin(allowedOrigin.port),
                HarnessSessionCookie.expired(),
            ) { cookieManager.flush() }
        }
        // 与 onCreate 相同：磁盘清理放后台，不占用销毁链路的主线程时间。
        clearHttpAuthCredentialsAsync()
        super.onDestroy()
    }

    private fun returnToMainActivity() {
        if (isFinishing) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    /**
     * 在后台线程清空 WebView 的 HTTP Auth 凭据缓存。
     *
     * WebViewDatabase 的获取与清理都是磁盘操作，官方同样建议在后台线程调用；
     * 失败静默：清理只是跨会话的卫生兜底，绝不允许它影响会话页生命周期。
     */
    private fun clearHttpAuthCredentialsAsync() {
        authDatabaseExecutor.execute {
            runCatching {
                WebViewDatabase.getInstance(applicationContext).clearHttpAuthUsernamePassword()
            }
        }
    }

    /** 系统栏和网页背景跟随应用主题，不额外占用会话高度。 */
    private fun applyNativeChrome() {
        if (isFinishing || isDestroyed) return
        val dark = AppThemePreference.isDark(
            AppThemePreference.current(this),
            AppThemePreference.systemNight(this),
        )
        // 按保存的主题解析颜色，允许应用主题与系统不同。
        val configuration = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val palette = createConfigurationContext(configuration)
        val background = palette.getColor(R.color.harness_toolbar_background)
        AppThemePreference.applySafely(this, backgroundColor = background)
        window.setBackgroundDrawable(ColorDrawable(background))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        findViewById<WebView>(R.id.harness_web_view)
            .setBackgroundColor(palette.getColor(R.color.harness_page_background))
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
        if (isFinishing || isDestroyed) return
        if (pageRetryCount < 2) {
            pageRetryCount++
            val delay = 300L * pageRetryCount
            pageRetryHandler.postDelayed({
                if (isFinishing || isDestroyed || !::webView.isInitialized) return@postDelayed
                webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webView.loadUrl(entryUrlForRetry ?: return@postDelayed)
            }, delay)
            Toast.makeText(this, "页面连接中断，正在重试（${pageRetryCount}/2）", Toast.LENGTH_SHORT).show()
            return
        }
        if (pageFailureHandled) return
        pageFailureHandled = true
        Toast.makeText(this, R.string.harness_page_failed, Toast.LENGTH_SHORT).show()
    }

    internal class Origin(val scheme: String, val host: String, val port: Int, val initialUrl: String) {
        fun allows(uri: Uri): Boolean =
            uri.scheme == scheme && uri.host == host && uri.port == port && uri.userInfo == null

        companion object {
            fun parse(raw: String?): Origin? {
                val uri = HarnessPageUrl.parseEntryUrl(raw) ?: return null
                return Origin(uri.scheme, uri.host, uri.port, uri.toASCIIString())
            }
        }
    }

    internal class RestrictedWebViewClient(
        private val origin: Origin,
        private val username: String,
        private val password: String,
        private val onMainFrameFailure: () -> Unit,
        private val onRendererGone: (WebView?) -> Unit = { view ->
            view?.destroy()
            (view?.context as? HarnessActivity)?.finish()
        },
        private val onMainFrameLoaded: (WebView) -> Unit = {},
    ) : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            if (view != null) onMainFrameLoaded(view)
        }

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
            // 外壳同步的背景：由原生按同源路径供给，支持 Range，也不放开文件权限。
            if (uri.path == HarnessThemeBridge.BACKGROUND_PATH) {
                val context = view?.context ?: return blockedResponse()
                return HarnessThemeBridge.serveBackground(context, request)
            }
            // 市场目录和缩略图由插件直接请求 HTTPS 资源站；交给 WebView 执行正常的 TLS/CORS 校验。
            // 不添加本机 Authorization 或 Cookie，也不允许资源站替换会话主页面。
            return if (origin.allows(uri) || HarnessPluginResources.allows(
                    uri.toString(), request.method, request.isForMainFrame,
                )) null else blockedResponse()
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
            val activity = view?.context as? HarnessActivity
            if (activity != null && !activity.isFinishing) {
                activity.rendererRecovery = true
                activity.recreate()
            } else onRendererGone(view)
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
