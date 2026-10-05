package io.deepseekharness.mobile.accessibility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

/** 仅供壳内选择器使用；分页传输，不把完整应用清单交给 AI 或写入日志。 */
object InstalledApplications {
    const val PAGE_SIZE = 100

    /**
     * 读取本机应用并交给 [ApplicationSearch] 检索。
     *
     * 这里只做两件 Android 专属的事：向 PackageManager 要清单（含包可见性规则）、
     * 以及把不干净的标签洗成可显示文本。**匹配与排序规则全部在 [ApplicationSearch] 里**
     * （拼音/首字母、多关键词、大小写与空白），这部分有 JVM 单测盯着，不在这个文件里改。
     */
    fun list(context: Context, query: String, offset: Int): JSONObject {
        require(query.length <= 160 && query.none { it.isISOControl() } && offset >= 0) { "应用筛选参数无效" }
        val manager = context.packageManager
        val applications = if (Build.VERSION.SDK_INT >= 33) {
            manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getInstalledApplications(0)
        }
        val entries = ApplicationSearch.installed(applications.map { app ->
            ApplicationSearch.InstalledApplication(
                packageName = app.packageName,
                label = runCatching { manager.getApplicationLabel(app).toString() }
                    .getOrDefault(app.packageName)
                    .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
                    .take(160),
                system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                selectable = AccessibilityAutomationPolicy.validPackage(app.packageName),
            )
        })
        return ApplicationSearch.page(entries, query, offset)
    }
}
