package io.deepseekharness.mobile.accessibility

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「白名单写入」的完整顺序：**先过验证密码，再规范化落盘**。
 *
 * 特意做成不带 `Context` 的纯函数，是为了让「未配置密码时写入不受限 / 配置后缺密码被拒 /
 * 错密码被拒 / 正确密码放行」这条链路能在 JVM 单测里用内存替身跑完（与 `RuntimeStorageDirPreferences`
 * 同一个思路：判定逻辑里不出现 `getSharedPreferences`，否则这条链只能靠设备上的手工点击确认）。
 *
 * 顺序本身是安全语义的一部分：密码门槛在格式校验**之前**，未通过验证的调用拿不到任何关于
 * 白名单内容或格式的反馈。
 */
internal object AccessibilityWhitelistWritePolicy {
    /** 返回即将落盘的规范化列表（已含 [AccessibilityAutomationPolicy.SELF_PACKAGE]）。 */
    fun authorize(
        guard: AccessibilityPasswordGuard,
        packages: List<String>,
        password: String?,
    ): List<String> {
        guard.verifyOrThrow(password)
        val normalized = AccessibilityAutomationPolicy.withSelf(packages)
        require(AccessibilityAutomationPolicy.validPackages(normalized)) { "无障碍应用白名单格式无效" }
        return normalized
    }
}

/**
 * 无障碍自动化配置。白名单只由用户在应用内设置，服务本身不能静默开启。
 *
 * 两处固定语义：
 * 1. 本应用 [AccessibilityAutomationPolicy.SELF_PACKAGE] 恒在白名单里（读、写都补，用户删不掉）；
 * 2. 已设置验证密码后，写入必须带对密码，否则 [AccessibilityPasswordStore.verifyOrThrow] 抛受控失败。
 * 读取路径（服务每个窗口都要读一次）**不经过密码与哈希**，行为与从前一致。
 */
object AccessibilityAutomationStore {
    private const val PREFERENCES = "accessibility_automation"
    private const val KEY_PACKAGES = "allowed_packages"
    private const val KEY_ENFORCE_WHITELIST = "enforce_whitelist"

    /** 白名单默认开启；关闭只解除包名准入，不绕过锁屏、敏感窗口和动作频率限制。 */
    fun whitelistEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENFORCE_WHITELIST, true)

    fun setWhitelistEnabled(context: Context, enabled: Boolean): JSONObject {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENFORCE_WHITELIST, enabled).apply()
        return state(context)
    }

    fun allowedPackages(context: Context): Set<String> {
        val values = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, emptySet())
            .orEmpty()
            .toList()
        // 偏好文件是应用私有的，但仍按整体校验处理；一旦发现异常条目就全部失效，
        // 防止损坏或迁移数据把非法包名带入自动化边界。
        val valid = if (AccessibilityAutomationPolicy.validPackages(values)) values else emptyList()
        // 本应用只在这里补进白名单：服务侧那道「非法包名 / 不在白名单」的判定仍然是同一份，
        // 白名单里出现本应用 ≠ 能自动化密码框或敏感窗口（那些拒绝在服务里独立生效）。
        return AccessibilityAutomationPolicy.withSelf(valid).toSet()
    }

    /**
     * 覆盖白名单。已设置验证密码时 [password] 必填且必须正确。
     *
     * @throws AccessibilityGuardException 密码缺失 / 错误 / 被锁定 / 记录损坏
     * @throws IllegalArgumentException 白名单格式无效
     */
    fun setAllowedPackages(context: Context, packages: List<String>, password: String?): JSONObject {
        val normalized = AccessibilityWhitelistWritePolicy.authorize(guard(context), packages, password)
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PACKAGES, normalized.toSet()).apply()
        return state(context)
    }

    fun state(context: Context): JSONObject {
        val values = JSONArray()
        allowedPackages(context).sorted().forEach(values::put)
        return JSONObject()
            .put("enabled", isServiceEnabled(context))
            .put("allowedPackages", values)
            // 前端按「固定成员」单独渲染这一项（灰显、不可删除）；它同时也在 allowedPackages 里，
            // 两个字段都出现是刻意的：字段齐全才能过 src/platform/validation.ts 的形状校验。
            .put(
                "alwaysAllowedPackages",
                JSONArray().put(AccessibilityAutomationPolicy.SELF_PACKAGE),
            )
            .put("passwordConfigured", AccessibilityPasswordStore.configured(context))
            .put("whitelistEnabled", whitelistEnabled(context))
    }

    /** 同时检查服务引用与系统安全设置，避免进程重建或服务掉线后继续显示已连接。 */
    private fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, DeepSeekAccessibilityService::class.java).flattenToString()
        val configured = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
        return configured && DeepSeekAccessibilityService.current() != null
    }

    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun guard(context: Context): AccessibilityPasswordGuard =
        AccessibilityPasswordGuard(AccessibilityPasswordPreferences.from(context))
}
