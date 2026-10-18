package io.deepseekharness.mobile

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject

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
    private const val PREFS = "harness_theme"
    private const val KEY_MODE = "mode"
    private const val KEY_ACCENT = "accent"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_SURFACE = "surface"
    private const val KEY_OPACITY = "surfaceOpacity"
    private const val KEY_SYNCED_AT = "syncedAt"

    private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    private val MODES = setOf("light", "dark", "system")

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

    fun clear(context: Context): Boolean = try {
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
     * 生成注入脚本：只写 CSS 变量与 `data-dsh-theme`，不替换任何页面结构。
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
