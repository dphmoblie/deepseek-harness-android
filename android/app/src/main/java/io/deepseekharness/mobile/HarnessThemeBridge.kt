package io.deepseekharness.mobile

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference

/**
 * 把外壳主题同步给 DSH 页面（访客 WebView）。
 *
 * 外壳和 DSH 是两个独立 WebView，`localStorage` 不共享，所以主题必须由原生转一手：
 * Web 侧只传一组**固定字段**，这里逐字段校验后落盘，页面加载完再写成 CSS 变量。
 *
 * 刻意不接受 CSS / JS / URL：那等于把「主题」变成任意代码执行入口，而这里唯一需要的
 * 只是几个颜色和一个明暗模式。
 *
 * 背景只存**地址**（`content://` 的持久授权），不复制文件：视频多大都不进内存、
 * 不过桥、不设上限。访客页面从 `/__shell/background` 取，由
 * `RestrictedWebViewClient.shouldInterceptRequest` 映射到同一个 URI 上——
 * 对页面来说它是同源资源，CSP 的 `media-src 'self'` 直接通过。
 */
internal object HarnessThemeBridge {
    /** 访客页面加载背景的路径；必须与拦截逻辑保持一致。 */
    const val BACKGROUND_PATH = "/__shell/background"

    private const val PREFS = "harness_theme"
    private const val KEY_MODE = "mode"
    private const val KEY_ACCENT = "accent"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_SURFACE = "surface"
    private const val KEY_OPACITY = "surfaceOpacity"
    /**
     * DSH 页面里按钮与输入框的底色透明度（百分比）。
     *
     * 与面板透明度分开：面板要够不透正文才读得清，控件透明度是单独一项外观设置。
     * 老版本载荷没有这个键，按 100（不透明）迁移。
     */
    private const val KEY_CONTROL_OPACITY = "controlOpacity"
    private const val KEY_SYNCED_AT = "syncedAt"
    private const val KEY_BACKGROUND_URI = "backgroundUri"
    private const val KEY_BACKGROUND_MIME = "backgroundMime"
    private const val KEY_BACKGROUND_KIND = "backgroundKind"
    /** 「连背景一起同步」开关：落在这里而不是外壳的 localStorage，页面重启后才不会自己跑回来。 */
    private const val KEY_SYNC_BACKGROUND = "syncBackground"

    private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    private val MODES = setOf("light", "dark", "system")
    /** 只接受 SAF 的内容地址：不留 `file://`、不留 `http(s)://`，也就不需要额外一道网络策略。 */
    private val CONTENT_URI = Regex("^content://[A-Za-z0-9._~:/?#@!$&'()*+,;=%\\-]{1,2048}$")
    private const val KIND_VIDEO = "video"
    private const val KIND_IMAGE = "image"

    /**
     * 白名单校验：明暗模式、三个 #RRGGBB、0..100 的透明度。
     *
     * 单独抽出来是为了能被 JVM 单测直接驱动——这段判断是拼进 `evaluateJavascript` 的
     * 内容唯一的防线，不该只在设备上才验证得到。
     */
    internal fun accepts(mode: String, accent: String, background: String, surface: String, opacity: Int, controlOpacity: Int = 100): Boolean =
        mode in MODES && COLOR.matches(accent) && COLOR.matches(background) && COLOR.matches(surface) &&
            opacity in 0..100 && controlOpacity in 40..100

    /** 背景地址白名单：只认 `content://`，长度受限，不含引号与反斜杠。 */
    internal fun acceptsBackgroundUri(uri: String): Boolean = CONTENT_URI.matches(uri)

    internal fun kindOf(mime: String): String? = when {
        mime.startsWith("video/") -> KIND_VIDEO
        mime.startsWith("image/") -> KIND_IMAGE
        else -> null
    }

    /** 「连背景一起同步」只认布尔：字符串 "false" 之类的取值一律当非法，不猜用户的意思。 */
    internal fun acceptsSyncBackground(value: Any?): Boolean = value is Boolean

    /**
     * 页面到底该不该显示背景：地址存在**并且**开关打开。
     *
     * 拆成纯函数是因为「关掉开关」这条路最容易做成只改界面：地址还在，每次加载又把
     * 旧背景画出来。这里把判定收在一处，`script()`、`serveBackground()` 共用同一个结论。
     */
    internal fun backgroundEnabled(uri: String?, syncBackground: Boolean): Boolean = uri != null && syncBackground

    /**
     * 自动播放是否放开：只有「同步背景 + 背景是视频」这一种组合。
     *
     * WebView 默认 `mediaPlaybackRequiresUserGesture = true`，注入脚本里的 `video.play()`
     * 会被直接拒掉（静音也不放行）。放开它等于允许访客页面里任何媒体自播，所以必须由
     * 背景视频这个明确状态来派生，并且在关掉背景后立刻收回。
     */
    internal fun allowsBackgroundAutoplay(kind: String?, syncBackground: Boolean): Boolean =
        syncBackground && kind == KIND_VIDEO

    /** 校验并保存；任何一项不合格都整体拒绝，避免半套主题落盘。 */
    fun save(context: Context, payload: JSONObject): Boolean {
        val mode = payload.optString(KEY_MODE, "")
        val accent = payload.optString(KEY_ACCENT, "")
        val background = payload.optString(KEY_BACKGROUND, "")
        val surface = payload.optString(KEY_SURFACE, "")
        val opacity = payload.optInt(KEY_OPACITY, -1)
        val controlOpacity = payload.optInt(KEY_CONTROL_OPACITY, 100)
        if (!accepts(mode, accent, background, surface, opacity, controlOpacity)) return false
        val hasFlag = payload.has(KEY_SYNC_BACKGROUND)
        val flag = payload.opt(KEY_SYNC_BACKGROUND)
        if (hasFlag && !acceptsSyncBackground(flag)) return false
        return try {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, mode)
                .putString(KEY_ACCENT, accent)
                .putString(KEY_BACKGROUND, background)
                .putString(KEY_SURFACE, surface)
                .putInt(KEY_OPACITY, opacity)
                .putInt(KEY_CONTROL_OPACITY, controlOpacity)
                .putLong(KEY_SYNCED_AT, System.currentTimeMillis())
            // 没带这个字段就保留原值：老版本外壳只同步颜色，不该顺手把背景开关关掉。
            if (hasFlag) editor.putBoolean(KEY_SYNC_BACKGROUND, flag as Boolean)
            editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 「连背景一起同步」开关落盘。
     *
     * 与地址分开存：关掉开关只表示「页面别再显示」，用户选过的文件与持久授权都留着，
     * 重新打开时不用再选一遍；真正放弃文件才走 [clearBackground]。
     */
    fun setSyncBackground(context: Context, enabled: Boolean): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SYNC_BACKGROUND, enabled)
            .commit()
    } catch (_: Exception) {
        false
    }

    fun syncBackground(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SYNC_BACKGROUND, false)
    } catch (_: Exception) {
        false
    }

    /** 页面是否该显示背景：地址 + 开关，两处判定只在这里合一次。 */
    fun hasBackgroundForPage(context: Context): Boolean =
        backgroundEnabled(backgroundUri(context), syncBackground(context))

    /**
     * 外壳重开时读回同步状态：开关、已选背景、上次同步时间。
     *
     * 没有这条，重启后复选框回到未勾、已选文件也显示不出来——看起来就像背景被丢了。
     */
    fun state(context: Context): JSONObject {
        val result = JSONObject()
        result.put(KEY_SYNC_BACKGROUND, syncBackground(context))
        result.put(KEY_SYNCED_AT, syncedAt(context))
        result.put(KEY_CONTROL_OPACITY, controlOpacity(context))
        val uri = backgroundUri(context)
        val mime = backgroundMime(context)
        val kind = backgroundKind(context)
        val valid = uri != null && mime != null && kind != null &&
            acceptsBackgroundUri(uri) && kindOf(mime!!) == kind
        result.put(
            "background",
            if (valid) {
                JSONObject().put("uri", uri).put("mime", mime).put("kind", kind)
            } else {
                JSONObject.NULL
            },
        )
        return result
    }

    fun clear(context: Context): Boolean = try {
        releaseBackground(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    } catch (_: Exception) {
        false
    }

    fun syncedAt(context: Context): Long = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SYNCED_AT, 0L)
    } catch (_: Exception) {
        0L
    }

    /** 控件透明度：老版本没有这个键，读默认 100，外观与升级前一致。 */
    fun controlOpacity(context: Context): Int = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CONTROL_OPACITY, 100).coerceIn(40, 100)
    } catch (_: Exception) {
        100
    }

    /**
     * 记下背景地址（同时记 mime 与图片/视频类型）。
     *
     * 只存地址不复制文件：视频大小因此没有上限，换手机内置相机拍的整段素材也能直接用。
     */
    fun setBackgroundRef(context: Context, uri: String, mime: String): Boolean {
        if (!acceptsBackgroundUri(uri)) return false
        val kind = kindOf(mime) ?: return false
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_BACKGROUND_URI, uri)
                .putString(KEY_BACKGROUND_MIME, mime)
                .putString(KEY_BACKGROUND_KIND, kind)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    fun backgroundUri(context: Context): String? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKGROUND_URI, null)
    } catch (_: Exception) {
        null
    }

    fun backgroundKind(context: Context): String? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKGROUND_KIND, null)
    } catch (_: Exception) {
        null
    }

    fun backgroundMime(context: Context): String? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKGROUND_MIME, null)
    } catch (_: Exception) {
        null
    }

    /** 清掉背景地址，并把持久授权一起还回去——否则用户删了背景，授权还挂在应用上。 */
    fun clearBackground(context: Context): Boolean = try {
        releaseBackground(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_BACKGROUND_URI)
            .remove(KEY_BACKGROUND_MIME)
            .remove(KEY_BACKGROUND_KIND)
            // 背景被清掉后开关也要回到未勾：留着 true 会让下一次选文件立刻显示，
            // 与界面上的复选框状态不一致。
            .remove(KEY_SYNC_BACKGROUND)
            .commit()
    } catch (_: Exception) {
        false
    }

    private fun releaseBackground(context: Context) {
        val uri = backgroundUri(context) ?: return
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // 不是可持久化的授权（例如用户从临时入口选的）：没有可释放的东西。
        }
    }

    /**
     * 供 `shouldInterceptRequest` 调用：把背景地址当同源资源发给访客页面。
     *
     * 走 `ContentResolver` 流式转发，不缓存、不复制。**不支持 Range**：内容提供者的
     * 输入流通常不可随机定位，硬做只会读到错位的字节；`<video>` 在无 Range 时会顺序
     * 读完整个文件再播，循环背景够用，代价是不能拖动。
     */
    fun serveBackground(context: Context, request: WebResourceRequest): WebResourceResponse {
        // 开关关掉之后这条同源路径也要立刻失效：页面里残留的 <video> 或 CSS 变量
        // 不该还能把文件读出去。
        if (!hasBackgroundForPage(context)) return notFound()
        val uri = backgroundUri(context)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val mime = backgroundMime(context) ?: "application/octet-stream"
        if (uri == null) return notFound()
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: return notFound()
        val length = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        } catch (_: Exception) {
            null
        }
        val headers = mutableMapOf(
            "Cache-Control" to "no-store",
            "Accept-Ranges" to "none",
        )
        if (length != null && length > 0) headers["Content-Length"] = length.toString()
        return WebResourceResponse(mime, null, 200, "OK", headers, stream)
    }

    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)),
    )

    /**
     * 生成注入脚本：只写 CSS 变量、`data-dsh-theme`，必要时加一个背景视频层。
     *
     * 消费这些变量的规则在子模块 `android.css`（`dsh-android.css`）里：访客页面加载它，
     * 原生这边不再注入样式，免得同一套规则存在两份、改一处漏一处。
     *
     * `system` 原样交给访客页面自己判断——外壳里解析出的深浅是外壳那一刻的结论，
     * 把它固化成 light/dark 会让访客页面在系统切换后不再跟随。
     */
    fun script(context: Context): String? {
        val prefs = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            return null
        }
        val mode = prefs.getString(KEY_MODE, null) ?: return null
        val accent = prefs.getString(KEY_ACCENT, null) ?: return null
        val background = prefs.getString(KEY_BACKGROUND, null) ?: return null
        val surface = prefs.getString(KEY_SURFACE, null) ?: return null
        val opacity = prefs.getInt(KEY_OPACITY, 100)
        val controlOpacity = prefs.getInt(KEY_CONTROL_OPACITY, 100).coerceIn(40, 100)
        if (!accepts(mode, accent, background, surface, opacity, controlOpacity)) return null
        // 只有「开关打开 + 地址存在」才渲染背景层；kind 也只在真的显示时才带出去。
        val hasBackground = hasBackgroundForPage(context)
        val kind = if (hasBackground) backgroundKind(context) else null
        // 值都已通过白名单校验，不含引号或反斜杠，拼进脚本是安全的。
        return """
            (function () {
              var root = document.documentElement;
              if (!root) { return false; }
              root.dataset.dshTheme = '$mode';
              root.dataset.dshThemeSource = 'shell';
              root.style.setProperty('--dsh-theme-accent', '$accent');
              root.style.setProperty('--dsh-theme-background', '$background');
              root.style.setProperty('--dsh-theme-surface', '$surface');
              root.style.setProperty('--dsh-theme-surface-opacity', '${opacity / 100.0}');
              root.style.setProperty('--dsh-theme-control-opacity', '${controlOpacity / 100.0}');
              if ('$mode' !== 'system') { root.style.colorScheme = '$mode'; }
              var path = '${BACKGROUND_PATH}';
              var hasBackground = $hasBackground;
              var kind = '$kind';
              var video = document.getElementById('dsh-shell-background');
              if (!hasBackground) {
                if (video && video.parentNode) { video.parentNode.removeChild(video); }
                root.style.removeProperty('--dsh-theme-background-image');
                delete root.dataset.dshThemeMedia;
                return true;
              }
              root.dataset.dshThemeMedia = kind;
              if (kind === 'video') {
                root.style.removeProperty('--dsh-theme-background-image');
                if (!video) {
                  video = document.createElement('video');
                  video.id = 'dsh-shell-background';
                  video.setAttribute('data-dsh-shell-background', '');
                  video.muted = true;
                  video.loop = true;
                  video.autoplay = true;
                  video.playsInline = true;
                  video.setAttribute('muted', '');
                  video.setAttribute('playsinline', '');
                  video.setAttribute('preload', 'auto');
                  (document.body || root).insertBefore(video, (document.body || root).firstChild);
                }
                if (video.getAttribute('src') !== path) { video.setAttribute('src', path); }
                var playing = video.play();
                if (playing && typeof playing.catch === 'function') { playing.catch(function () {}); }
              } else {
                if (video && video.parentNode) { video.parentNode.removeChild(video); }
                root.style.setProperty('--dsh-theme-background-image', 'url("' + path + '")');
              }
              return true;
            })();
        """.trimIndent()
    }

    /** 当前正在显示页面的 WebView；插件线程和 Activity 之间只共享这一个弱引用。 */
    @Volatile
    private var attached: WeakReference<WebView>? = null

    /** Activity 挂上自己的 WebView：之后的开关变更才有地方重放。 */
    fun attach(webView: WebView) {
        attached = WeakReference(webView)
    }

    fun detach(webView: WebView) {
        if (attached?.get() === webView) attached = null
    }

    /**
     * 关掉背景时把播放权限收回去。
     *
     * 判据只来自 [allowsBackgroundAutoplay]：背景是视频且正在同步。其余任何状态都恢复到
     * WebView 的默认值，不让「点过一次背景视频」变成页面里所有媒体的永久通行证。
     */
    private fun syncAutoplay(webView: WebView) {
        val context = webView.context
        val allow = allowsBackgroundAutoplay(backgroundKind(context), hasBackgroundForPage(context))
        try {
            val settings = webView.settings ?: return
            if (settings.mediaPlaybackRequiresUserGesture == allow) {
                settings.mediaPlaybackRequiresUserGesture = !allow
            }
        } catch (_: Exception) {
            // WebView 正在销毁：下次 attach 或页面加载时会重新对齐。
        }
    }

    /** 页面加载完成或回到前台时调用；没有同步过就什么都不做。 */
    fun apply(webView: WebView) {
        // 插件方法可能来自 Bridge 的后台线程，而 evaluateJavascript 只能在 view 的线程执行。
        webView.post { applyNow(webView) }
    }

    /** 开关在设置页被改过：把结果重放到当前页面，用户不用等下一次加载。 */
    fun refresh() {
        attached?.get()?.let { apply(it) }
    }

    private fun applyNow(webView: WebView) {
        syncAutoplay(webView)
        val script = script(webView.context) ?: return
        try {
            webView.evaluateJavascript(script, null)
        } catch (_: Exception) {
            // 页面正在销毁或 WebView 已 detach：下一次加载会重新补上，不需要打断用户。
        }
    }
}
