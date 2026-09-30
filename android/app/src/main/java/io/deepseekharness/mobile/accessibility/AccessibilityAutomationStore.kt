package io.deepseekharness.mobile.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** 无障碍自动化配置。白名单只由用户在应用内设置，服务本身不能静默开启。 */
object AccessibilityAutomationStore {
    private const val PREFERENCES = "accessibility_automation"
    private const val KEY_PACKAGES = "allowed_packages"

    fun allowedPackages(context: Context): Set<String> {
        val values = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, emptySet())
            .orEmpty()
            .toList()
        // 偏好文件是应用私有的，但仍按整体校验处理；一旦发现异常条目就全部失效，
        // 防止损坏或迁移数据把非法包名带入自动化边界。
        return if (AccessibilityAutomationPolicy.validPackages(values)) values.toSet() else emptySet()
    }

    fun setAllowedPackages(context: Context, packages: List<String>): JSONObject {
        val normalized = packages.map(String::trim).distinct()
        require(AccessibilityAutomationPolicy.validPackages(normalized)) { "无障碍应用白名单格式无效" }
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PACKAGES, normalized.toSet()).apply()
        return state(context)
    }

    fun state(context: Context): JSONObject {
        val values = JSONArray()
        allowedPackages(context).sorted().forEach(values::put)
        return JSONObject()
            .put("enabled", DeepSeekAccessibilityService.current() != null)
            .put("allowedPackages", values)
    }

    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
