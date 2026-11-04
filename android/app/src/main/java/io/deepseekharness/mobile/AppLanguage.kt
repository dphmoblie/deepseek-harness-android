package io.deepseekharness.mobile

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** 应用语言独立于系统语言保存；仅支持明确列出的语言。 */
internal object AppLanguage {
    private const val PREFERENCES = "app_language"
    private const val KEY = "language"

    fun save(context: Context, language: String): Boolean {
        require(language == "zh-CN" || language == "en") { "不支持的应用语言" }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY, language).commit()
    }

    /** 读取当前应用语言；未设置或为非法值时回落到简体中文。 */
    fun current(context: Context): String = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.takeIf { it == "en" || it == "zh-CN" }
        ?: "zh-CN"

    /**
     * 用户**显式保存过**的语言；从没选过时返回 null。
     *
     * [current] 永远有值（未设置就回落简体中文），单看它分不清「用户选了中文」和
     * 「用户还没选」。首次启动的语言引导只能靠这个区别决定要不要弹。
     */
    fun saved(context: Context): String? = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.takeIf { it == "en" || it == "zh-CN" }

    fun localizedContext(context: Context): Context {
        val language = current(context)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration)
    }
}
