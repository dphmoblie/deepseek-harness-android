package io.deepseekharness.mobile

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ClipData
import android.content.Intent
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.deepseekharness.mobile.runtime.HarnessAccess
import io.deepseekharness.mobile.runtime.RuntimeStore
import io.deepseekharness.mobile.runtime.RuntimeWorkspaceFiles
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onResume() {
        super.onResume()
        // 控制台也是一个窗口：主题在 Web 侧改过、或系统深色模式在后台切换过之后，
        // 回到前台都要按已保存的模式重算状态栏，否则它会与页面配色不一致。
        AppThemePreference.apply(this)
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
        val runtimeStore = RuntimeStore(this)
        if (runtimeStore.keepScreenAwake()) {
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
            when (item.itemId) {
                R.id.action_harness_files -> {
                    showWorkspaceFiles()
                    true
                }
                R.id.action_harness_management -> {
                    returnToMainActivity()
                    true
                }
                else -> false
            }
        }

        // 入口 URL：index.html 是唯一没有内容哈希的产物，它的缓存键必须同时带上 APK 版本与
        // 已安装运行时版本，否则在线更新运行之后 WebView 会继续复用旧前端。
        // 运行时未安装 / 清单不可读时由 withVersions 落到固定占位值，不会让 URL 抖动。
        val entryUrl = HarnessPageUrl.withVersions(
            allowedOrigin.initialUrl,
            BuildConfig.VERSION_NAME,
            runtimeStore.installedManifest()?.version,
        )

        webView = findViewById(R.id.harness_web_view)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 入口 index.html 没有内容哈希，只有 URL 带全两维「真实」版本键时才允许走正常缓存；
            // 缺任一维、或运行时版本读不到（占位值）都退回完全绕开缓存——宁可多下载一次，
            // 也不能拿旧前端。带哈希的 /assets/* 能否真正长缓存取决于运行时的响应头，
            // 单靠 Android 侧改不了（见 HarnessPageCache 与本次改动报告）。
            cacheMode = when (HarnessPageCache.modeFor(entryUrl)) {
                HarnessCacheMode.NORMAL -> WebSettings.LOAD_DEFAULT
                HarnessCacheMode.BYPASS -> WebSettings.LOAD_NO_CACHE
            }
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
            // 控制台界面的字号跟随系统字号（登记册 5.6-I）：外壳 CSS 已全部 rem 化，
            // 但 WebView 不会因为系统字号变化就改变 rem 基准，只有 textZoom 能带上这件事。
            // fontScale 不在 configChanges 里，系统改字号会重建本 Activity，因此读一次即可。
            textZoom = AppTextScale.percentOf(resources.configuration.fontScale)
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
                    webView.loadUrl(entryUrl)
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

    /** Native file controls remain available even when the Harness page is busy or failed. */
    private fun showWorkspaceFiles() {
        lifecycleScope.launch {
            val files = try {
                val manager = RuntimeWorkspaceFiles(RuntimeStore(this@HarnessActivity), cacheDir)
                withContext(Dispatchers.IO) { manager.list() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(this@HarnessActivity, R.string.harness_file_action_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (files.isEmpty()) {
                Toast.makeText(this@HarnessActivity, R.string.harness_workspace_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@HarnessActivity)
                .setTitle(R.string.harness_workspace_files)
                .setItems(files.toTypedArray()) { _, index -> showWorkspaceFileActions(files[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showWorkspaceFileActions(path: String) {
        val actions = arrayOf(
            getString(R.string.harness_file_open),
            getString(R.string.harness_file_share),
            getString(R.string.harness_file_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(path)
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> openOrShareWorkspaceFile(path, open = true)
                    1 -> openOrShareWorkspaceFile(path, open = false)
                    2 -> confirmDeleteWorkspaceFile(path)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openOrShareWorkspaceFile(path: String, open: Boolean) {
        lifecycleScope.launch {
            try {
                val manager = RuntimeWorkspaceFiles(RuntimeStore(this@HarnessActivity), cacheDir)
                val shared = withContext(Dispatchers.IO) { manager.copyForSharing(path) }
                val uri = FileProvider.getUriForFile(
                    this@HarnessActivity,
                    "${packageName}.diagnostics",
                    shared,
                )
                val intent = Intent(if (open) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
                    val mime = manager.mimeType(path)
                    if (open) setDataAndType(uri, mime) else {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                    }
                    clipData = ClipData.newRawUri(shared.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(if (open) R.string.harness_file_open else R.string.harness_file_share)))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(this@HarnessActivity, R.string.harness_file_action_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteWorkspaceFile(path: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.harness_file_delete)
            .setMessage(getString(R.string.harness_file_delete_confirm, path))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.harness_file_delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            RuntimeWorkspaceFiles(RuntimeStore(this@HarnessActivity), cacheDir).delete(path)
                        }
                        Toast.makeText(this@HarnessActivity, R.string.harness_file_deleted, Toast.LENGTH_SHORT).show()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Toast.makeText(this@HarnessActivity, R.string.harness_file_action_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
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
