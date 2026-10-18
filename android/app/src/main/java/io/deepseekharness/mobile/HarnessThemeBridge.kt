package io.deepseekharness.mobile

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 把外壳主题同步给 DSH 页面（访客 WebView）。
 *
 * 外壳和 DSH 是两个独立 WebView，`localStorage` 不共享，所以主题必须由原生转一手：
 * Web 侧只传一组**固定字段**，这里逐字段校验后落盘，页面加载完再写成 CSS 变量。
 *
 * 刻意不接受 CSS / JS / URL：那等于把「主题」变成任意代码执行入口，而这里唯一需要的
 * 只是几个颜色和一个明暗模式。
 *
 * 背景走**同源路径**而不是 data URL：访客页面从 `/__shell/background`（由
 * `RestrictedWebViewClient.shouldInterceptRequest` 映射到应用私有目录）加载图片或视频。
 * 对页面来说它是同源资源，CSP 的 `media-src 'self'` 直接通过，也不用放开文件权限；
 * 视频因此能流式播放，不必先变成几十 MB 的 base64 字符串。
 */
internal object HarnessThemeBridge {
    /** 访客页面加载背景的路径；必须与 [HarnessThemeBridge] 的拦截逻辑保持一致。 */
    const val BACKGROUND_PATH = "/__shell/background"

    private const val PREFS = "harness_theme"
    private const val KEY_MODE = "mode"
    private const val KEY_ACCENT = "accent"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_SURFACE = "surface"
    private const val KEY_OPACITY = "surfaceOpacity"
    private const val KEY_SYNCED_AT = "syncedAt"
    private const val KEY_KIND = "backgroundKind"
    private const val BACKGROUND_FILE = "harness-background.bin"
    private const val UPLOAD_FILE = "harness-background.upload"

    /** 视频上限：够 10–20 秒的背景循环，再大只是在手机上烧内存。 */
    const val MAX_BACKGROUND_BYTES = 32L * 1024 * 1024
    /** 单块 base64 上限，与 Web 侧 `MAX_CHUNK_BASE64` 保持一致。 */
    private const val MAX_CHUNK_BASE64 = 512 * 1024

    private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    private val MODES = setOf("light", "dark", "system")
    private val BASE64_CHARS = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val KIND_VIDEO = "video"
    private val KIND_IMAGE = "image"

    /**
     * 白名单校验：明暗模式、三个 #RRGGBB、0..100 的透明度。
     *
     * 单独抽出来是为了能被 JVM 单测直接驱动——这段判断是拼进 `evaluateJavascript` 的
     * 内容唯一的防线，不该只在设备上才验证得到。
     */
    internal fun accepts(mode: String, accent: String, background: String, surface: String, opacity: Int): Boolean =
        mode in MODES && COLOR.matches(accent) && COLOR.matches(background) && COLOR.matches(surface) && opacity in 0..100

    /** 单块校验：只允许 base64 字符并限长。 */
    internal fun acceptsUploadChunk(data: String): Boolean =
        data.isNotEmpty() && data.length <= MAX_CHUNK_BASE64 && BASE64_CHARS.matches(data)

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
        val file = File(context.filesDir, BACKGROUND_FILE)
        file.delete()
        File(context.filesDir, UPLOAD_FILE).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    } catch (_: Exception) {
        false
    }

    fun syncedAt(context: Context): Long = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SYNCED_AT, 0L)
    } catch (_: Exception) {
        0L
    }

    // ── 背景上传：按块追加，最后一次收尾 ────────────────────────────────────────

    @Volatile
    private var uploadMime: String? = null
    @Volatile
    private var uploadIndex = 0
    @Volatile
    private var uploadBytes = 0L

    internal fun acceptsUploadMime(mime: String): Boolean = mime == "video/mp4" || mime == "image/jpeg"

    /** 开始一次上传：清掉上一次的临时文件与序号。 */
    fun beginUpload(context: Context, mime: String): Boolean {
        if (!acceptsUploadMime(mime)) return false
        return try {
            File(context.filesDir, UPLOAD_FILE).outputStream().use { }
            uploadMime = mime
            uploadIndex = 0
            uploadBytes = 0L
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 追加一块。
     *
     * 序号必须严格递增——乱序说明桥两侧的状态已经不同步，继续拼下去只会得到坏文件。
     */
    fun appendUpload(context: Context, index: Int, data: String): Boolean {
        if (uploadMime == null || index != uploadIndex || !acceptsUploadChunk(data)) return false
        return try {
            val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            if (uploadBytes + bytes.size > MAX_BACKGROUND_BYTES) return false
            File(context.filesDir, UPLOAD_FILE).appendBytes(bytes)
            uploadIndex += 1
            uploadBytes += bytes.size
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 收尾：临时文件转正，按 mime 记下类型，供注入脚本与拦截器使用。 */
    fun commitUpload(context: Context): Boolean {
        val mime = uploadMime ?: return false
        return try {
            val temporary = File(context.filesDir, UPLOAD_FILE)
            if (temporary.length() <= 0L) return false
            val target = File(context.filesDir, BACKGROUND_FILE)
            if (target.exists() && !target.delete()) return false
            if (!temporary.renameTo(target)) return false
            // 先删除再改用视频/图片：旧图片没清掉，注入层会同时挂两个背景。
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_KIND, if (mime == "video/mp4") KIND_VIDEO else KIND_IMAGE)
                .commit()
            uploadMime = null
            true
        } catch (_: Exception) {
            false
        }
    }

    fun backgroundKind(context: Context): String? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_KIND, null)
    } catch (_: Exception) {
        null
    }

    /** 供拦截器读：没有同步过背景时返回 null。 */
    fun backgroundFile(context: Context): File? = try {
        val file = File(context.filesDir, BACKGROUND_FILE)
        if (file.isFile && file.length() > 0L) file else null
    } catch (_: Exception) {
        null
    }

    /**
     * 供 `shouldInterceptRequest` 调用：把背景文件当同源资源发给访客页面。
     *
     * 支持 Range——`<video>` 会分段请求；不支持 Range 时浏览器仍可能播完，但拖动与
     * 循环末尾会重新下载整个文件，32 MB 的视频在手机上体感很差。
     */
    fun serveBackground(context: Context, request: WebResourceRequest): WebResourceResponse {
        val file = backgroundFile(context)
        if (file == null) {
            return WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found",
                mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)),
            )
        }
        val mime = if (backgroundKind(context) == KIND_VIDEO) "video/mp4" else "image/jpeg"
        val length = file.length()
        val range = request.requestHeaders["Range"] ?: request.requestHeaders["range"]
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        if (match != null) {
            val start = match.groupValues[1].toLongOrNull() ?: 0L
            val requestedEnd = match.groupValues[2].toLongOrNull() ?: (length - 1)
            if (start in 0 until length) {
                val end = minOf(requestedEnd, length - 1)
                if (start <= end) {
                    val count = end - start + 1
                    val stream = FileInputStream(file).apply { skip(start) }
                    return WebResourceResponse(
                        mime, null, 206, "Partial Content",
                        mapOf(
                            "Content-Range" to "bytes $start-$end/$length",
                            "Accept-Ranges" to "bytes",
                            "Content-Length" to count.toString(),
                            "Cache-Control" to "no-store",
                        ),
                        LimitedInputStream(stream, count),
                    )
                }
            }
        }
        return WebResourceResponse(
            mime, null, 200, "OK",
            mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Length" to length.toString(),
                "Cache-Control" to "no-store",
            ),
            FileInputStream(file),
        )
    }

    private class LimitedInputStream(input: InputStream, private var remaining: Long) :
        FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value >= 0) remaining -= 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val read = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }
    }

    fun clearBackground(context: Context): Boolean = try {
        uploadMime = null
        File(context.filesDir, BACKGROUND_FILE).delete()
        File(context.filesDir, UPLOAD_FILE).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_KIND).commit()
    } catch (_: Exception) {
        false
    }

    /**
     * 访客页面侧消费这些变量的最小样式。
     *
     * 只做两件低风险的事：把背景铺在内容下面、让表单控件跟随明暗与强调色。
     * 不重写官方调色板——访客页面自己的主题仍然生效，外壳只是叠一层。
     *
     * 放在原生脚本里而不是子模块 CSS：这样换任何 `dsh-web-frontend` 版本都成立，
     * 不需要为了一个背景去提升前端指针。
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
        val hasBackground = backgroundFile(context) != null
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
                  video.style.zIndex = '-1';
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
