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
    private const val KEY_WHITELIST_ENABLED = "enforce_whitelist"

    // 旧安装与异常配置一律保留限制，只有明确保存 false 才关闭。
    fun whitelistEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all[KEY_WHITELIST_ENABLED] as? Boolean ?: true

    fun packageAllowed(context: Context, packageName: String): Boolean = AccessibilityAutomationPolicy.packageAllowed(
        packageName, allowedPackages(context), whitelistEnabled(context),
    )

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
    fun setAllowedPackages(context: Context, packages: List<String>, password: String?, whitelistEnabled: Boolean? = null): JSONObject {
        val normalized = AccessibilityWhitelistWritePolicy.authorize(guard(context), packages, password)
        // 开关与名单共用密码验证并一次写入；省略开关的旧调用保持现值。
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PACKAGES, normalized.toSet())
            .also { editor -> whitelistEnabled?.let { editor.putBoolean(KEY_WHITELIST_ENABLED, it) } }.apply()
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
            // 总开关与三态服务状态：前者是用户在设置页按下的「开始/暂停/紧急停止」，
            // 后者区分「系统里没开」与「开着但服务当前没连上」（见 serviceStatus 注释）。
            .put("automationRuntime", AutomationControlPolicy.name(AutomationControlStore.runtime(context)))
            .put("serviceStatus", serviceStatus(context))
    }

    /** 同时检查服务引用与系统安全设置，避免进程重建或服务掉线后继续显示已连接。 */
    private fun isServiceEnabled(context: Context): Boolean = isServiceConfigured(context) && isServiceConnected()

    /**
     * 服务状态三态：
     * - `connected`：服务进程已连接（可读界面、可执行动作）；
     * - `enabled_disconnected`：系统设置里开着，但本进程还没连上——刚打开开关、或服务被系统回收后等待重新绑定；
     * - `off`：系统设置里没开。
     *
     * 一个布尔不够用：系统开着但服务被系统回收时，布尔会显示「未开启」，用户照提示去开关只会看到它本来就是开的。
     * 这里只报当前读到的两个事实，**不承诺自动重开**，也不落盘「曾经连上过」的记忆。
     */
    fun serviceStatus(context: Context): String = when {
        isServiceConnected() -> "connected"
        isServiceConfigured(context) -> "enabled_disconnected"
        else -> "off"
    }

    /** 系统安全设置里是否登记了本服务（只看系统这一侧，不看进程有没有连上）。 */
    private fun isServiceConfigured(context: Context): Boolean {
        val expected = ComponentName(context, DeepSeekAccessibilityService::class.java).flattenToString()
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** 无障碍服务实例是否还在本进程里。 */
    private fun isServiceConnected(): Boolean = DeepSeekAccessibilityService.current() != null

    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun guard(context: Context): AccessibilityPasswordGuard =
        AccessibilityPasswordGuard(AccessibilityPasswordPreferences.from(context))
}
