package io.deepseekharness.mobile.accessibility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** 仅供壳内选择器使用；分页传输，不把完整应用清单交给 AI 或写入日志。 */
object InstalledApplications {
    private const val PAGE_SIZE = 100

    fun list(context: Context, query: String, offset: Int): JSONObject {
        require(query.length <= 160 && query.none { it.isISOControl() } && offset >= 0) { "应用筛选参数无效" }
        val manager = context.packageManager
        val applications = if (Build.VERSION.SDK_INT >= 33) {
            manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getInstalledApplications(0)
        }
        val entries = applications.map { app ->
            val label = runCatching { manager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }.take(160)
            Triple(app, label, AccessibilityAutomationPolicy.validPackage(app.packageName))
        }.filter { (app, label, _) -> query.isEmpty() || app.packageName.contains(query, true) || label.contains(query, true) }
            .sortedWith(compareBy({ it.second.lowercase() }, { it.first.packageName }))
        val page = JSONArray()
        entries.drop(offset).take(PAGE_SIZE).forEach { (app, label, selectable) ->
            page.put(JSONObject().put("packageName", app.packageName).put("label", label)
                .put("system", app.flags and ApplicationInfo.FLAG_SYSTEM != 0).put("selectable", selectable))
        }
        val end = (offset.toLong() + PAGE_SIZE).coerceAtMost(entries.size.toLong()).toInt()
        return JSONObject().put("apps", page).put("total", entries.size)
            .put("nextOffset", if (end < entries.size) end else JSONObject.NULL)
    }
}
