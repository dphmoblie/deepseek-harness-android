package io.deepseekharness.mobile

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 应用主题的**原生侧**记忆与状态栏落地。
 *
 * **为什么原生也要存一份**：主题选择是 Web 侧的偏好（`src/theme.ts` 存 localStorage），
 * 但状态栏属于窗口，Web 改不了它。`meta[name=theme-color]` 只有 Chrome for Android 认，
 * **Android WebView 不会拿它去染状态栏**——所以不接这一步，真机上选浅色主题时状态栏仍是深色。
 *
 * **为什么存 mode 而不是存「深/浅」**：`system` 模式下系统在用户使用期间切换深色，
 * 应用要跟着变。存「当前是深还是浅」会把那一刻的结论固化成用户的显式选择，
 * 之后系统再切就不跟随了。存 mode 让原生每次自己按 `uiMode` 解析，语义与 Web 侧一致。
 *
 * **两个 API 都要做，缺一不可**：
 *  - `window.statusBarColor` / `navigationBarColor`：Android 11–14 上生效；
 *  - `WindowInsetsControllerCompat.isAppearanceLightStatusBars`（图标明暗）：
 *    **本应用 targetSdk 35，Android 15 起 `statusBarColor` 被废弃且被忽略**（强制 edge-to-edge，
 *    状态栏透明、内容画到它下面）。那种情况下真正决定观感的是「状态栏区域的底色 = 应用自己的背景」
 *    与「图标该用深色还是浅色」，因此图标那一项在 15+ 上反而是主路径。
 */
internal object AppThemePreference {
    private const val PREFERENCES = "app_theme"
    private const val KEY = "mode"

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    private val MODES = setOf(MODE_SYSTEM, MODE_LIGHT, MODE_DARK)

    /** 深色状态栏底色：与 `values/colors.xml` 及 Web 侧深色 `--bg` 同一取值。 */
    const val DARK_BAR_COLOR = 0xFF111315.toInt()

    /** 浅色状态栏底色：与 Web 侧浅色 `--bg` 同一取值，避免状态栏与页面之间出现色带。 */
    const val LIGHT_BAR_COLOR = 0xFFF3F5F8.toInt()

    /** 保存主题模式；非法值直接拒绝（调用方必须给出受控取值，不做「猜一个」）。 */
    fun save(context: Context, mode: String): Boolean {
        require(mode in MODES) { "不支持的主题模式" }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode).commit()
    }

    /** 读取主题模式；未设置或为非法值时回落到 `system`（与 Web 侧默认值一致）。 */
    fun current(context: Context): String = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.takeIf { it in MODES }
        ?: MODE_SYSTEM

    /**
     * 解析出「当前应当用深色外观吗」。纯函数，因此可以穷举测试——
     * 这条判定错一次，用户看到的就是状态栏与界面不匹配。
     */
    fun isDark(mode: String, systemNight: Boolean): Boolean = when (mode) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        // 非法值按 system 处理，与 [current] 的回退保持一致：宁可跟随系统，也不要猜。
        else -> systemNight
    }

    fun systemNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 按已保存的模式给窗口上色与设置图标明暗。需要 Activity，因此不做 JVM 单测。 */
    fun apply(activity: Activity) {
        val dark = isDark(current(activity), systemNight(activity))
        val window = activity.window
        @Suppress("DEPRECATION")
        window.statusBarColor = if (dark) DARK_BAR_COLOR else LIGHT_BAR_COLOR
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (dark) DARK_BAR_COLOR else LIGHT_BAR_COLOR
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // 深色底要配浅色图标，反之亦然——注意这里是「取反」，写反了图标会糊在背景里看不见。
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
