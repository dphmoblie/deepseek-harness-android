package io.deepseekharness.mobile

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONObject
import java.io.ByteArrayInputStream

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
    private const val KEY_SYNCED_AT = "syncedAt"
    private const val KEY_BACKGROUND_URI = "backgroundUri"
    private const val KEY_BACKGROUND_MIME = "backgroundMime"
    private const val KEY_BACKGROUND_KIND = "backgroundKind"

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
    internal fun accepts(mode: String, accent: String, background: String, surface: String, opacity: Int): Boolean =
        mode in MODES && COLOR.matches(accent) && COLOR.matches(background) && COLOR.matches(surface) && opacity in 0..100

    /** 背景地址白名单：只认 `content://`，长度受限，不含引号与反斜杠。 */
    internal fun acceptsBackgroundUri(uri: String): Boolean = CONTENT_URI.matches(uri)

    internal fun kindOf(mime: String): String? = when {
        mime.startsWith("video/") -> KIND_VIDEO
        mime.startsWith("image/") -> KIND_IMAGE
        else -> null
    }

    /** 校验并保存；任何一项不合格都整体拒绝，避免半套主题落盘。 */
    fun save(context: Context, payload: JSONObject): Boolean {
        val mode = payload.optString(KEY_MODE, "")
        val accent = payload.optString(KEY_ACCENT, "")
        val background = payload.optString(KEY_BACKGROUND, "")
        val surface = payload.optString(KEY_SURFACE, "")
        val opacity = payload.optInt(KEY_OPACITY, -1)
        if (!accepts(mode, accent, background, surface, opacity)) return false
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, mode)
                .putString(KEY_ACCENT, accent)
                .putString(KEY_BACKGROUND, background)
                .putString(KEY_SURFACE, surface)
                .putInt(KEY_OPACITY, opacity)
                .putLong(KEY_SYNCED_AT, System.currentTimeMillis())
                .commit()
        } catch (_: Exception) {
            false
        }
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
     * 访客页面侧消费这些变量的最小样式。
     *
     * 只做两件低风险的事：把背景铺在内容下面、让表单控件跟随明暗与强调色。
     * 不重写官方调色板——访客页面自己的主题仍然生效，外壳只是叠一层。
     */
    internal val STYLE_RULES = """
        html[data-dsh-theme-source='shell'] body::before {
          content: '';
          position: fixed;
          inset: 0;
          z-index: -1;
          pointer-events: none;
          background-image: var(--dsh-theme-background-image, none);
          background-position: center;
          background-size: cover;
          opacity: var(--dsh-theme-surface-opacity, 1);
        }
        html[data-dsh-theme-source='shell'] body {
          background-color: var(--dsh-theme-background, transparent);
        }
        html[data-dsh-theme-source='shell'][data-dsh-theme='dark'] { color-scheme: dark; }
        html[data-dsh-theme-source='shell'][data-dsh-theme='light'] { color-scheme: light; }
        html[data-dsh-theme-source='shell'] { accent-color: var(--dsh-theme-accent, auto); }
        video[data-dsh-shell-background] {
          position: fixed;
          inset: 0;
          z-index: -1;
          width: 100%;
          height: 100%;
          object-fit: cover;
          pointer-events: none;
          opacity: var(--dsh-theme-surface-opacity, 1);
        }
    """.trimIndent()

    /**
     * 生成注入脚本：只写 CSS 变量、`data-dsh-theme`、一段固定样式，必要时加一个背景视频层。
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
        if (!accepts(mode, accent, background, surface, opacity)) return null
        val kind = backgroundKind(context)
        val hasBackground = backgroundUri(context) != null
        val css = JSONObject.quote(STYLE_RULES)
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
              if ('$mode' !== 'system') { root.style.colorScheme = '$mode'; }
              var style = document.getElementById('dsh-shell-theme');
              if (!style) {
                style = document.createElement('style');
                style.id = 'dsh-shell-theme';
                (document.head || root).appendChild(style);
              }
              style.textContent = $css;
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

    /** 页面加载完成或回到前台时调用；没有同步过就什么都不做。 */
    fun apply(webView: WebView) {
        val script = script(webView.context) ?: return
        try {
            webView.evaluateJavascript(script, null)
        } catch (_: Exception) {
            // 页面正在销毁或 WebView 已 detach：下一次加载会重新补上，不需要打断用户。
        }
    }
}
