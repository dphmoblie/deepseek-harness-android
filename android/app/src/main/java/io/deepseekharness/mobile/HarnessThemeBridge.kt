package io.deepseekharness.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebView
import org.json.JSONObject
import java.io.File

/**
 * 把外壳主题同步给 DSH 页面（访客 WebView）。
 *
 * 外壳和 DSH 是两个独立 WebView，`localStorage` 不共享，所以主题必须由原生转一手：
 * Web 侧只传一组**固定字段**，这里逐字段校验后落盘，页面加载完再写成 CSS 变量。
 *
 * 刻意不接受 CSS / JS / URL：那等于把「主题」变成任意代码执行入口，而这里唯一需要的
 * 只是几个颜色和一个明暗模式。
 */
internal object HarnessThemeBridge {
    /** 只接受 JPEG：它没有透明通道，老 WebView 也一定解码得了。 */
    const val SYNC_MIME = "image/jpeg"

    private const val PREFS = "harness_theme"
    private const val KEY_MODE = "mode"
    private const val KEY_ACCENT = "accent"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_SURFACE = "surface"
    private const val KEY_OPACITY = "surfaceOpacity"
    private const val KEY_SYNCED_AT = "syncedAt"
    private const val KEY_HAS_BACKGROUND = "hasBackground"
    private const val BACKGROUND_FILE = "harness-background.jpg"
    /** 同步背景的载荷上限（base64 字符数），与 Web 侧 MAX_SYNC_BASE64 保持一致。 */
    private const val MAX_BACKGROUND_BASE64 = 2 * 1024 * 1024
    /** 解码后的像素上限：足够铺满长屏，再大只是白占内存。 */
    private const val MAX_BACKGROUND_PIXELS = 4096L * 4096L

    private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    private val MODES = setOf("light", "dark", "system")
    private val BASE64_CHARS = Regex("^[A-Za-z0-9+/]+={0,2}$")

    /** (文件修改时间, 文件长度, base64)：文件一换就自动失效。 */
    @Volatile
    private var cachedBackground: Triple<Long, Long, String>? = null

    /**
     * 白名单校验：明暗模式、三个 #RRGGBB、0..100 的透明度。
     *
     * 单独抽出来是为了能被 JVM 单测直接驱动——这段判断是拼进 `evaluateJavascript` 的
     * 内容唯一的防线，不该只在设备上才验证得到。
     */
    internal fun accepts(mode: String, accent: String, background: String, surface: String, opacity: Int): Boolean =
        mode in MODES && COLOR.matches(accent) && COLOR.matches(background) && COLOR.matches(surface) && opacity in 0..100

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

    /**
     * 背景载荷的白名单：只允许 base64 字符并限长。
     *
     * 这段字符串会被拼进 `url("data:image/jpeg;base64,…")`，所以字符集必须逐字受控。
     */
    internal fun acceptsBackground(base64: String): Boolean =
        base64.isNotEmpty() && base64.length <= MAX_BACKGROUND_BASE64 && BASE64_CHARS.matches(base64)

    /** 校验、解码、按最长边 2048 重新编码成 JPEG 落盘；任何一步失败都不留半张图。 */
    fun saveBackground(context: Context, base64: String): Boolean {
        if (!acceptsBackground(base64)) return false
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_BACKGROUND_PIXELS) return false
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            scaled = scaleDown(bitmap, 2048)
            val temporary = File(context.filesDir, "$BACKGROUND_FILE.tmp")
            temporary.outputStream().use { stream -> scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream) }
            if (!temporary.renameTo(File(context.filesDir, BACKGROUND_FILE))) {
                temporary.delete()
                return false
            }
            cachedBackground = null
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_HAS_BACKGROUND, true)
                .commit()
        } catch (_: Exception) {
            false
        } finally {
            if (scaled != null && scaled !== bitmap) scaled.recycle()
            bitmap?.recycle()
        }
    }

    fun clearBackground(context: Context): Boolean {
        cachedBackground = null
        return try {
            File(context.filesDir, BACKGROUND_FILE).delete()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_HAS_BACKGROUND)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
            true,
        )
    }

    /** 读背景文件并转成 base64；按文件修改时间做一次内存缓存，避免每次加载都重读。 */
    private fun backgroundData(context: Context): String? = try {
        val file = File(context.filesDir, BACKGROUND_FILE)
        if (!file.isFile) {
            null
        } else {
            val cached = cachedBackground
            if (cached != null && cached.first == file.lastModified() && cached.second == file.length()) {
                cached.third
            } else {
                val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                cachedBackground = Triple(file.lastModified(), file.length(), encoded)
                encoded
            }
        }
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context): Boolean = try {
        cachedBackground = null
        File(context.filesDir, BACKGROUND_FILE).delete()
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
    """.trimIndent()

    /**
     * 生成注入脚本：只写 CSS 变量、`data-dsh-theme` 与一段固定样式，不替换任何页面结构。
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
        // 背景是一段独立的大字符串：没有同步过就是空，注入时整条语句跳过。
        val image = backgroundData(context).orEmpty()
        // 样式表用 JSON 转义后当 JS 字面量传进去：换行与引号都不用手写转义。
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
              var image = '$image';
              if (image) {
                root.style.setProperty('--dsh-theme-background-image', 'url("data:image/jpeg;base64,' + image + '")');
              }
              var style = document.getElementById('dsh-shell-theme');
              if (!style) {
                style = document.createElement('style');
                style.id = 'dsh-shell-theme';
                (document.head || root).appendChild(style);
              }
              style.textContent = $css;
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
