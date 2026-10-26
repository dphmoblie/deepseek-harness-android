package io.deepseekharness.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「用外部浏览器打开 Harness」时可选的浏览器清单。
 *
 * 只向系统查询能处理 http(s) 浏览的 Activity，按包名去重并排除本应用；
 * 不读取浏览历史、不申请额外权限，也不保存用户在其他应用里的任何数据。
 * 用户的选择只保存包名，打开前仍会按当前已安装清单复核一次。
 */
internal object ExternalBrowsers {
    /**
     * 与前端校验同一口径：包名只允许字母、数字、下划线和点。
     * 这个值会进入显式 Intent 的 `setPackage`，不能接受任意字符串。
     */
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+){1,15}$")
    private const val MAX_BROWSERS = 32
    private const val MAX_LABEL = 120

    data class Browser(val packageName: String, val label: String)

    fun list(context: Context): List<Browser> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://127.0.0.1/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = try {
            context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        } catch (_: Exception) {
            emptyList()
        }
        val candidates = resolved.mapNotNull { entry ->
            val info = entry.activityInfo ?: return@mapNotNull null
            val label = runCatching {
                context.packageManager.getApplicationLabel(info.applicationInfo).toString()
            }.getOrNull().orEmpty()
            Browser(info.packageName, label)
        }
        return normalize(context.packageName, candidates)
    }

    /**
     * 去重、排除自身、把换行与控制字符压平，再按名称排序。
     * 纯函数，便于 JVM 单测；系统返回的列表可能同一包名出现多个 Activity。
     */
    internal fun normalize(selfPackage: String, candidates: List<Browser>): List<Browser> {
        val seen = HashSet<String>()
        val result = ArrayList<Browser>()
        for (candidate in candidates) {
            val packageName = candidate.packageName
            if (packageName == selfPackage || !PACKAGE.matches(packageName) || !seen.add(packageName)) continue
            val label = candidate.label
                .map { if (it.isISOControl()) ' ' else it }
                .joinToString("")
                .trim()
                .take(MAX_LABEL)
            result += Browser(packageName, label.ifEmpty { packageName })
            if (result.size >= MAX_BROWSERS) break
        }
        return result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** 已保存的包名必须在当前清单里；卸载或禁用后返回 null，打开时退回系统选择器。 */
    fun select(context: Context, packageName: String?): Browser? {
        if (packageName == null || !PACKAGE.matches(packageName)) return null
        return list(context).firstOrNull { it.packageName == packageName }
    }

    fun toJson(browsers: List<Browser>): JSONArray = JSONArray().apply {
        browsers.forEach { browser ->
            put(JSONObject().put("packageName", browser.packageName).put("label", browser.label))
        }
    }
}
