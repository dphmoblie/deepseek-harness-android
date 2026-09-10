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

    fun current(context: Context): String = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.takeIf { it == "en" || it == "zh-CN" }
        ?: "zh-CN"

    fun localizedContext(context: Context): Context {
        val language = current(context)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration)
    }
}
