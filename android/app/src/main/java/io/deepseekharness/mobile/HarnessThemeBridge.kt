package io.deepseekharness.mobile

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
    /**
     * 侧边栏底色透明度（百分比）。
     *
     * 与正文面板分开：侧栏面积大、结构简单，可以更透；正文面板要保证正文可读。
     * 老版本载荷没有这个键，按 100（不透明）迁移，外观与升级前一致。
     */
    private const val KEY_SIDEBAR_OPACITY = "sidebarOpacity"
    /**
     * 右侧面板底色透明度（百分比）。
     *
     * 右侧面板指右上角按钮打开的那一栏（「开始 / 工作区文件」），与左侧侧栏是两套
     * 不同的界面：左栏是导航，右栏要承载正文，两者必须能分开调。
     * 老版本载荷没有这个键，按 40 迁移——此前右栏在有背景时整层透明、等于没有底，
     * 40% 是补上底色、又仍能透出背景的默认值。
     */
    private const val KEY_RIGHT_PANEL_OPACITY = "rightPanelOpacity"
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
     * 页面的文字调色板：一套三档，与官方 `body` / `body[data-ds-dark-theme]` 里的
     * `--dsw-alias-label-primary|secondary|tertiary` 取值逐字一致。
     *
     * 取值跟着官方调色板走，不在这里另创一套：android.css 只负责把这三个值接到官方令牌上，
     * 换成别的色值会让「同步了外壳主题的页面」与「外置浏览器里的页面」看起来是两套设计。
     */
    internal data class PagePalette(
        val dark: Boolean,
        val primary: String,
        val secondary: String,
        val tertiary: String,
    ) {
        /** 写进 `data-dsh-theme-resolved` 的取值，CSS 据此选色。 */
        val css: String get() = if (dark) "dark" else "light"
    }

    internal val DARK_PAGE = PagePalette(
        dark = true,
        primary = "#f9fafb",
        secondary = "#cfd3d6",
        tertiary = "#adb2b8",
    )

    internal val LIGHT_PAGE = PagePalette(
        dark = false,
        primary = "#0f1115",
        secondary = "#61666b",
        tertiary = "#81858c",
    )

    /**
     * WCAG 相对亮度的分界：0.179 之上用深色文字，之下用浅色文字。
     *
     * 与 `src/theme.ts` 里 `--on-blue` 的判定同一口径（那个数字来自「黑字与白字在这一点上
     * 对比度相等」的推导）。拿 #808080 验算：黑字 5.3、白字 3.95，所以极值中点也应当偏黑字。
     */
    private const val CONTRAST_PIVOT = 0.179

    /**
     * 页面该用哪一套文字：只看**外壳实际铺在页面上的 surface**。
     *
     * 三个候选来源里只有 surface 与「文字压在什么颜色上」直接相关——底色、面板、侧栏全由它
     * 派生。另外两个都不可用作判据：手机的深色模式决定的是 SystemUI 的通知卡片；WebView 的
     * `prefers-color-scheme` 跟的是宿主 Activity 的 `uiMode`，而本应用允许外观与系统不同
     * （`app_theme` 的显式 light/dark）。拿它们判深浅，就是用户看到的
     * 「应用白字 / 通知黑字」与「黑字压深底」。
     *
     * 因此 `system` 模式在这里也不查系统：同步过来的 surface 是外壳在它自己解析出的明暗下
     * 算好的，页面跟随它才不会与底色分家；系统之后切换深色也不该让页面文字单方面翻面。
     */
    internal fun pagePalette(surface: String): PagePalette? {
        val luminance = relativeLuminance(surface) ?: return null
        return if (luminance > CONTRAST_PIVOT) LIGHT_PAGE else DARK_PAGE
    }

    /** `#RRGGBB` 的 WCAG 相对亮度；格式不合法时返回 null（不猜一个值出来）。 */
    internal fun relativeLuminance(color: String): Double? {
        if (!COLOR.matches(color)) return null
        fun channel(offset: Int): Double {
            val value = color.substring(offset, offset + 2).toInt(16) / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5)
    }

    /** `#RRGGBB` → 不透明 ARGB；格式不合法时返回 null。 */
    internal fun toArgb(color: String?): Int? =
        color?.takeIf { COLOR.matches(it) }?.let { 0xFF000000.toInt() or it.substring(1).toInt(16) }

    /**
     * 白名单校验：明暗模式、三个 #RRGGBB、0..100 的透明度。
     *
     * 单独抽出来是为了能被 JVM 单测直接驱动——这段判断是拼进 `evaluateJavascript` 的
     * 内容唯一的防线，不该只在设备上才验证得到。
     */
    internal fun accepts(
        mode: String,
        accent: String,
        background: String,
        surface: String,
        opacity: Int,
        controlOpacity: Int = 100,
        sidebarOpacity: Int = 100,
        rightPanelOpacity: Int = 40,
    ): Boolean =
        mode in MODES && COLOR.matches(accent) && COLOR.matches(background) && COLOR.matches(surface) &&
            opacity in 0..100 && controlOpacity in 40..100 &&
            sidebarOpacity in 0..100 && rightPanelOpacity in 0..100

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
        val sidebarOpacity = payload.optInt(KEY_SIDEBAR_OPACITY, 100)
        val rightPanelOpacity = payload.optInt(KEY_RIGHT_PANEL_OPACITY, 40)
        if (!accepts(mode, accent, background, surface, opacity, controlOpacity, sidebarOpacity, rightPanelOpacity)) return false
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
                .putInt(KEY_SIDEBAR_OPACITY, sidebarOpacity)
                .putInt(KEY_RIGHT_PANEL_OPACITY, rightPanelOpacity)
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
        result.put(KEY_SIDEBAR_OPACITY, sidebarOpacity(context))
        result.put(KEY_RIGHT_PANEL_OPACITY, rightPanelOpacity(context))
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

    fun clear(context: Context): Boolean {
        return try {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previousUri = preferences.getString(KEY_BACKGROUND_URI, null)
            // Remove the reference first. If disk I/O fails, keep the old reference and
            // its permission together so the existing background remains usable.
            val cleared = preferences.edit().clear().commit()
            if (!cleared) {
                false
            } else {
                releaseUriPermission(context, previousUri)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun syncedAt(context: Context): Long = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SYNCED_AT, 0L)
    } catch (_: Exception) {
        0L
    }

    /**
     * 通知的强调色（ARGB）：用户同步过来的强调色；没同步过或格式不合法时为 null。
     *
     * 通知的**文字**颜色由 SystemUI 按它自己的主题绘制，应用无权干预，也不该为它写死
     * #FFF / #000（那必然在另一半系统主题下不可读）。能对齐的只有应用名与图标底色这一处
     * 强调色；这里用强调色而不是面板底色：面板常是近黑色，深色系统主题下那块底色等于看不见。
     */
    fun accentColor(context: Context): Int? = toArgb(
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCENT, null)
            } catch (_: Exception) {
                null
            },
        )

    /** 控件透明度：老版本没有这个键，读默认 100，外观与升级前一致。 */
    fun controlOpacity(context: Context): Int = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CONTROL_OPACITY, 100).coerceIn(40, 100)
    } catch (_: Exception) {
        100
    }

    /** 侧栏透明度：老版本没有这个键，读默认 100（不透明），外观与升级前一致。 */
    fun sidebarOpacity(context: Context): Int = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SIDEBAR_OPACITY, 100).coerceIn(0, 100)
    } catch (_: Exception) {
        100
    }

    /** 右侧面板透明度：老版本没有这个键，读默认 40（补上底色，仍透出背景）。 */
    fun rightPanelOpacity(context: Context): Int = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_RIGHT_PANEL_OPACITY, 40).coerceIn(0, 100)
    } catch (_: Exception) {
        40
    }

    /**
     * 记下背景地址（同时记 mime 与图片/视频类型）。
     *
     * 只存地址不复制文件：视频大小因此没有上限，换手机内置相机拍的整段素材也能直接用。
     */
    fun setBackgroundRef(context: Context, uri: String, mime: String): Boolean {
        if (!acceptsBackgroundUri(uri)) return false
        val kind = kindOf(mime) ?: return false
        var previousUri: String? = null
        return try {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            previousUri = preferences.getString(KEY_BACKGROUND_URI, null)
            val saved = preferences.edit()
                .putString(KEY_BACKGROUND_URI, uri)
                .putString(KEY_BACKGROUND_MIME, mime)
                .putString(KEY_BACKGROUND_KIND, kind)
                .commit()
            if (!saved) {
                // The picker has already granted this URI. Do not leak that grant when
                // the preferences write fails.
                if (previousUri != uri) releaseUriPermission(context, uri)
                false
            } else {
                // Keep the new grant before dropping the old one, so a failed picker or
                // provider cannot leave the app without a usable background.
                if (previousUri != null && previousUri != uri) releaseUriPermission(context, previousUri)
                true
            }
        } catch (_: Exception) {
            if (previousUri != uri) releaseUriPermission(context, uri)
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
    fun clearBackground(context: Context): Boolean {
        return try {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previousUri = preferences.getString(KEY_BACKGROUND_URI, null)
            val cleared = preferences.edit()
            .remove(KEY_BACKGROUND_URI)
            .remove(KEY_BACKGROUND_MIME)
            .remove(KEY_BACKGROUND_KIND)
            // 背景被清掉后开关也要回到未勾：留着 true 会让下一次选文件立刻显示，
            // 与界面上的复选框状态不一致。
            .remove(KEY_SYNC_BACKGROUND)
            .commit()
            if (!cleared) {
                false
            } else {
                releaseUriPermission(context, previousUri)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun releaseUriPermission(context: Context, rawUri: String?) {
        val raw = rawUri ?: return
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(raw),
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
     * 生成注入脚本：写 CSS 变量与 `data-dsh-theme-*`，必要时加一个背景视频层。
     *
     * 消费这些变量的规则在子模块 `android.css`（`dsh-android.css`）里：访客页面加载它，
     * 原生这边不再注入样式，免得同一套规则存在两份、改一处漏一处。
     *
     * 深浅的结论由 [pagePalette] 按 surface 亮度算出并写进 `data-dsh-theme-resolved`，
     * 不再把 `system` 原样交给页面的 `prefers-color-scheme`：那个媒体查询跟的是宿主
     * Activity 的 uiMode，与页面底下真正铺着的 surface 没有关系（用户报的「应用白字 /
     * 通知黑字」正是拿它当判据的结果）。
     *
     * 脚本可重入且自带补偿：body 还没解析出来时短轮询，配合 DOMContentLoaded / load 与
     * documentElement 上的 MutationObserver，body 被重建也能补上；同一文档再注入一次会先
     * 停掉上一份实例，不会留下两套 observer 互相对写。
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
        val sidebarOpacity = prefs.getInt(KEY_SIDEBAR_OPACITY, 100).coerceIn(0, 100)
        val rightPanelOpacity = prefs.getInt(KEY_RIGHT_PANEL_OPACITY, 40).coerceIn(0, 100)
        if (!accepts(mode, accent, background, surface, opacity, controlOpacity, sidebarOpacity, rightPanelOpacity)) return null
        val page = pagePalette(surface) ?: return null
        // 只有「开关打开 + 地址存在」才渲染背景层；kind 也只在真的显示时才带出去。
        val hasBackground = hasBackgroundForPage(context)
        val kind = if (hasBackground) backgroundKind(context) else null
        // 值都已通过白名单校验，不含引号或反斜杠，拼进脚本是安全的。
        return """
            (function () {
              var KEY = '__dshShellTheme';
              var previous = window[KEY];
              if (previous && typeof previous.stop === 'function') { previous.stop(); }

              var mode = '$mode';
              var resolved = '${page.css}';
              var accent = '$accent';
              var background = '$background';
              var surface = '$surface';
              var surfaceOpacity = '${opacity / 100.0}';
              var controlOpacity = '${controlOpacity / 100.0}';
              var sidebarOpacity = '${sidebarOpacity / 100.0}';
              var rightPanelOpacity = '${rightPanelOpacity / 100.0}';
              var labels = {
                primary: '${page.primary}',
                secondary: '${page.secondary}',
                tertiary: '${page.tertiary}'
              };
              var dark = ${page.dark};
              var path = '${BACKGROUND_PATH}';
              var hasBackground = $hasBackground;
              var kind = '$kind';

              var stopped = false;
              var timer = 0;
              var attempts = 0;
              var observedRoot = null;
              var observedBody = null;
              var rootObserver = null;
              var bodyObserver = null;

              function writeRoot() {
                var root = document.documentElement;
                if (!root) { return false; }
                root.dataset.dshTheme = mode;
                root.dataset.dshThemeSource = 'shell';
                root.dataset.dshThemeResolved = resolved;
                root.style.setProperty('--dsh-theme-accent', accent);
                root.style.setProperty('--dsh-theme-background', background);
                root.style.setProperty('--dsh-theme-surface', surface);
                root.style.setProperty('--dsh-theme-surface-opacity', surfaceOpacity);
                root.style.setProperty('--dsh-theme-control-opacity', controlOpacity);
                root.style.setProperty('--dsh-theme-sidebar-opacity', sidebarOpacity);
                root.style.setProperty('--dsh-theme-right-panel-opacity', rightPanelOpacity);
                // 文字令牌由这里给出，android.css 只负责把它们接到官方 --dsw-alias-label-*。
                root.style.setProperty('--dsh-theme-label-primary', labels.primary);
                root.style.setProperty('--dsh-theme-label-secondary', labels.secondary);
                root.style.setProperty('--dsh-theme-label-tertiary', labels.tertiary);
                // 表单控件、滚动条跟随同一结论；页面之后再改内联值，由 syncColorScheme 拉回来。
                root.style.colorScheme = resolved;
                return true;
              }

              /*
               * 官方深浅由 body[data-ds-dark-theme] 驱动，而**页面自己的主题插件也写同一个
               * 属性**：它按自己保存的偏好（默认跟随 WebView 的 prefers-color-scheme）在
               * 设置变更、跨页同步、系统 media change 时重写。两处写同一开关、谁最后写谁算数，
               * 就是「底色跟壳、文字跟页面」的成因。
               *
               * 这里不做第二个「谁后写谁赢」的写入者，而是把它对齐到壳算出的结论：
               * 值已经一致就不再回写，因此不会与页面的 apply 互相触发成环。
               */
              function syncBody() {
                var body = document.body;
                if (!body) { return false; }
                if (body !== observedBody) {
                  observedBody = body;
                  if (bodyObserver) { bodyObserver.disconnect(); }
                  bodyObserver = null;
                  if (typeof MutationObserver === 'function') {
                    bodyObserver = new MutationObserver(syncBody);
                    bodyObserver.observe(body, { attributes: true, attributeFilter: ['data-ds-dark-theme'] });
                  }
                }
                if (body.hasAttribute('data-ds-dark-theme') !== dark) {
                  if (dark) { body.setAttribute('data-ds-dark-theme', ''); }
                  else { body.removeAttribute('data-ds-dark-theme'); }
                }
                return true;
              }

              /*
               * 页面 ThemePresenter 写的是**内联** `color-scheme`，样式表规则压不过它
               * （android.css 里也不允许用 !important：那里的强制声明必须留在窄屏/粗指针查询内）。
               * 所以内联值这边自己对齐：页面没写过时不碰（此时 android.css 的规则已经给出同一结论）。
               */
              function syncColorScheme() {
                var root = document.documentElement;
                if (!root) { return false; }
                if (root.style.colorScheme && root.style.colorScheme !== resolved) {
                  root.style.colorScheme = resolved;
                }
                return true;
              }

              // body 被重建（React 重挂、重载中途）时只有 documentElement 的 childList 能看到。
              // 同一个 observer 还看 html 的 style 属性：页面改内联 color-scheme 时只做一次便宜的对齐，
              // 不为一次样式变更重跑整段 apply（里面要扫一遍按钮找侧栏开关）。
              function bindRootObserver() {
                var root = document.documentElement;
                if (!root) { return false; }
                if (root !== observedRoot) {
                  observedRoot = root;
                  if (rootObserver) { rootObserver.disconnect(); }
                  rootObserver = null;
                  if (typeof MutationObserver === 'function') {
                    rootObserver = new MutationObserver(function (records) {
                      for (var index = 0; index < records.length; index += 1) {
                        if (records[index].type === 'attributes') { syncColorScheme(); return; }
                      }
                      apply();
                    });
                    rootObserver.observe(root, { childList: true, attributes: true, attributeFilter: ['style'] });
                  }
                }
                return true;
              }

              function applyBody() {
                var body = document.body;
                if (!body) { return false; }
                syncBody();
                body.setAttribute('data-dsh-theme-applied', '');
                /*
                 * 手机端左栏入口：**只有官方界面自己没有侧栏开关时才自绘一个**。
                 *
                 * 之前无条件自绘，结果它正好压在官方 toggle 上：点击被自绘按钮吃掉，
                 * 自绘按钮再转手点官方 toggle，视觉上两个按钮叠在一起、谁都点不动。
                 * 官方前端本来就有开关，这里退化成「没有才补」；官方后来加上开关时，
                 * 下一轮注入会把我们的按钮摘掉。
                 */
                var sidebarButton = document.getElementById('dsh-mobile-sidebar-button');
                var findSidebarToggle = function () {
                  var candidates = Array.prototype.slice.call(document.querySelectorAll('button,[role="button"]'));
                  return candidates.find(function (item) {
                    if (item === sidebarButton) { return false; }
                    var label = ((item.getAttribute('aria-label') || '') + ' ' + (item.getAttribute('title') || '')).toLowerCase();
                    return /sidebar|side bar|menu|history|会话|侧栏/.test(label);
                  });
                };
                if (findSidebarToggle()) {
                  if (sidebarButton && sidebarButton.parentNode) { sidebarButton.parentNode.removeChild(sidebarButton); }
                } else if (!sidebarButton) {
                  sidebarButton = document.createElement('button');
                  sidebarButton.id = 'dsh-mobile-sidebar-button';
                  sidebarButton.type = 'button';
                  sidebarButton.setAttribute('aria-label', '打开左侧边栏');
                  sidebarButton.title = '打开左侧边栏';
                  // 兜底按钮放在左下角：官方开关几乎都在左上，放同一点就会互相压住（实测两者都点不动）。
                  sidebarButton.style.cssText = 'position:fixed;left:8px;bottom:calc(env(safe-area-inset-bottom, 0px) + 88px);z-index:30;width:40px;height:40px;padding:8px;border:0;border-radius:12px;background:var(--dsh-theme-surface, rgba(20,24,32,0.9));color:var(--dsw-alias-label-primary);display:grid;place-items:center;';
                  var sidebarIcon = document.createElement('img');
                  sidebarIcon.alt = '';
                  sidebarIcon.width = 24;
                  sidebarIcon.height = 24;
                  sidebarIcon.src = '/favicon.svg';
                  sidebarButton.appendChild(sidebarIcon);
                  sidebarButton.addEventListener('click', function () {
                    var target = findSidebarToggle();
                    if (target) { target.click(); }
                  });
                  body.appendChild(sidebarButton);
                }
                var video = document.getElementById('dsh-shell-background');
                if (!hasBackground) {
                  if (video && video.parentNode) { video.parentNode.removeChild(video); }
                  document.documentElement.style.removeProperty('--dsh-theme-background-image');
                  delete document.documentElement.dataset.dshThemeMedia;
                  return true;
                }
                document.documentElement.dataset.dshThemeMedia = kind;
                if (kind === 'video') {
                  document.documentElement.style.removeProperty('--dsh-theme-background-image');
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
                    body.insertBefore(video, body.firstChild);
                  }
                  if (video.getAttribute('src') !== path) { video.setAttribute('src', path); }
                  var playing = video.play();
                  if (playing && typeof playing.catch === 'function') { playing.catch(function () {}); }
                } else {
                  if (video && video.parentNode) { video.parentNode.removeChild(video); }
                  document.documentElement.style.setProperty('--dsh-theme-background-image', 'url("' + path + '")');
                }
                return true;
              }

              function schedule() {
                if (stopped || timer || attempts >= 200) { return; }
                attempts += 1;
                timer = window.setTimeout(function () { timer = 0; apply(); }, 50);
              }

              function apply() {
                if (stopped) { return false; }
                if (!writeRoot() || !bindRootObserver()) { schedule(); return false; }
                if (!applyBody()) { schedule(); return false; }
                return true;
              }

              function onReady() { apply(); }

              function stop() {
                stopped = true;
                if (timer) { window.clearTimeout(timer); timer = 0; }
                if (rootObserver) { rootObserver.disconnect(); rootObserver = null; }
                if (bodyObserver) { bodyObserver.disconnect(); bodyObserver = null; }
                document.removeEventListener('DOMContentLoaded', onReady);
                window.removeEventListener('load', onReady);
                try { delete window[KEY]; } catch (error) { window[KEY] = undefined; }
              }

              window[KEY] = { apply: apply, stop: stop };
              document.addEventListener('DOMContentLoaded', onReady);
              window.addEventListener('load', onReady);
              apply();
              return true;
            })();
        """.trimIndent()
    }

    /**
     * 用户点了「恢复默认主题」：把外壳写过的东西全撤掉，页面重新对自己的调色板负责。
     *
     * 只撤外壳写上去的那几样：属性、变量、背景层、幂等标记与两处 observer。
     * body 上的 `data-ds-dark-theme` 刻意不猜：撤成哪一种都可能与页面自己的偏好相反，
     * 交给它的 ThemePresenter 在下一个事件里重新落地。
     */
    private val RESET_SCRIPT = """
        (function () {
          var root = document.documentElement;
          var shell = window.__dshShellTheme;
          /*
           * 从没同步过（页面既没有外壳源标记、也没有上次的注入实例）时立刻收手：
           * 页面自己的 ThemePresenter 写过 html 的内联 color-scheme，抹掉它会让滚动条
           * 与表单控件退回默认配色。每次主框架加载完成都会走一遍这段脚本，必须无副作用。
           */
          if (!root || (!root.hasAttribute('data-dsh-theme-source') && !shell)) { return true; }
          if (shell && typeof shell.stop === 'function') { shell.stop(); }
          root.removeAttribute('data-dsh-theme-source');
          root.removeAttribute('data-dsh-theme');
          root.removeAttribute('data-dsh-theme-resolved');
          root.removeAttribute('data-dsh-theme-media');
          [
            '--dsh-theme-accent', '--dsh-theme-background', '--dsh-theme-surface',
            '--dsh-theme-surface-opacity', '--dsh-theme-control-opacity',
            '--dsh-theme-sidebar-opacity', '--dsh-theme-right-panel-opacity',
            '--dsh-theme-label-primary', '--dsh-theme-label-secondary',
            '--dsh-theme-label-tertiary', '--dsh-theme-background-image'
          ].forEach(function (name) { root.style.removeProperty(name); });
          root.style.removeProperty('color-scheme');
          if (document.body) { document.body.removeAttribute('data-dsh-theme-applied'); }
          var video = document.getElementById('dsh-shell-background');
          if (video && video.parentNode) { video.parentNode.removeChild(video); }
          return true;
        })();
    """.trimIndent()

    /** 当前正在显示页面的 WebView；插件线程和 Activity 之间只共享这一个弱引用。 */
    @Volatile
    private var attached: WeakReference<WebView>? = null

    /** document-start 注册：脚本里带的是**这一次**的主题常量，值变了必须换一份。 */
    private var documentStartOrigin: String? = null
    private var documentStartHandler: ScriptHandler? = null
    private var documentStartPayload: String? = null
    private var documentStartOwner: WeakReference<WebView>? = null

    /** Activity 挂上自己的 WebView：之后的开关变更才有地方重放。 */
    fun attach(webView: WebView) {
        attached = WeakReference(webView)
    }

    fun detach(webView: WebView) {
        if (attached?.get() === webView) attached = null
        if (documentStartOwner?.get() === webView) removeDocumentStart()
    }

    /**
     * 在页面任何脚本之前把主题落下去。
     *
     * 只在 onPageFinished 注入时，页面的 boot 脚本已经按它自己的偏好画过一帧——那正是
     * 「刷新前后方向相反」的观感来源之一。provider 不支持 document-start 时静默跳过，
     * 仍由 [apply] 在加载完成、回到前台与设置变更时兜底。
     */
    fun installDocumentStart(webView: WebView, originRule: String) {
        documentStartOrigin = originRule
        // Activity 绑定的这块页面就是主题的唯一 owner：旧注册（可能属于上一块 WebView）不复用。
        removeDocumentStart()
        replaceDocumentStart(webView, script(webView.context))
    }

    /**
     * 换一份 document-start 注册（撤掉旧的再装新的）。
     *
     * 从主题值变化到下一次加载之间，旧注册仍会在新文档里执行旧常量，所以这里必须同步替换，
     * 不能等下一次 [installDocumentStart]。主题被清掉（payload 为 null）时撤掉注册：
     * 否则下一次加载还会被旧脚本按旧主题重写一遍。
     */
    private fun replaceDocumentStart(webView: WebView, payload: String?) {
        // 注册已经属于另一块页面（浮层小窗也走 apply()）：不要抢，否则主屏下一次加载就退回
        // 「先按页面自己的偏好画一帧」的旧时序。
        val owner = documentStartOwner?.get()
        if (owner !== null && owner !== webView) return
        // 当前挂载的是别的页面时同样不替它注册；没有挂载者（例如只有一个后台小窗）则按需装。
        val mounted = attached?.get()
        if (mounted !== null && mounted !== webView) return
        // 同一份脚本、同一个 WebView：不必拆了重装（每次主框架加载完成都会走到这里）。
        if (payload == documentStartPayload && owner === webView) return
        val origin = documentStartOrigin ?: return
        removeDocumentStart()
        if (payload == null) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        documentStartHandler = try {
            WebViewCompat.addDocumentStartJavaScript(webView, payload, setOf(origin))
        } catch (_: Exception) {
            // provider 声称支持却注入失败：保留 onPageFinished 兜底，不打断页面加载。
            null
        }
        documentStartPayload = payload
        documentStartOwner = WeakReference(webView)
    }

    private fun removeDocumentStart() {
        val handler = documentStartHandler
        documentStartHandler = null
        documentStartPayload = null
        documentStartOwner = null
        try {
            handler?.remove()
        } catch (_: Exception) {
            // WebView 已销毁：注册随它一起消失，没有需要回收的东西。
        }
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
        val script = script(webView.context)
        // 值变了就换一份 document-start 注册；清掉了就撤掉注册并让页面恢复自己的外观。
        replaceDocumentStart(webView, script)
        try {
            webView.evaluateJavascript(script ?: RESET_SCRIPT, null)
        } catch (_: Exception) {
            // 页面正在销毁或 WebView 已 detach：下一次加载会重新补上，不需要打断用户。
        }
    }
}
