package io.deepseekharness.mobile.accessibility

import org.json.JSONObject

/** 无障碍自动化的纯参数规则；业务层与服务层共用，避免只在界面校验。 */
internal object AccessibilityAutomationPolicy {
    const val MAX_PACKAGES = 16
    const val MAX_INPUT_CHARS = 512
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){1,12}$")
    private val viewIdPattern = Regex("^([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){1,12}):id/[A-Za-z_][A-Za-z0-9_]{0,79}$")
    private val reservedPackages = setOf(
        "android", "com.android.settings", "com.android.systemui", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller", "io.deepseekharness.mobile",
    )
    private val reservedPrefixes = listOf(
        "com.android.", "com.miui.", "com.coloros.", "com.oplus.", "com.huawei.",
        "com.samsung.android.", "com.vivo.",
    )
    private val sensitiveText = Regex(
        "密码|口令|验证码|验证代码|动态码|支付|付款|收款|转账|转帐|银行卡|银行账户|授权|权限申请|" +
            "生物识别|指纹验证|面容验证|安全验证|password|passcode|verification[ -]?code|" +
            "one[ -]?time[ -]?password|\\botp\\b|payment|checkout|transfer|bank[ -]?account|" +
            "card[ -]?number|permission|biometric|fingerprint|face[ -]?id",
        RegexOption.IGNORE_CASE,
    )

    data class ActionRequest(
        val packageName: String,
        val action: String,
        val viewId: String,
        val text: String?,
        val direction: String?,
    )

    fun validPackage(packageName: String): Boolean =
        packageName.length <= 160 && packagePattern.matches(packageName) &&
            packageName !in reservedPackages && reservedPrefixes.none(packageName::startsWith)

    fun validPackages(packages: List<String>): Boolean =
        packages.size <= MAX_PACKAGES && packages.distinct().size == packages.size &&
            packages.all(::validPackage)

    fun containsSensitiveText(value: CharSequence?): Boolean =
        value != null && sensitiveText.containsMatchIn(value)

    fun parseAction(param: String): ActionRequest? {
        if (param.length !in 2..2048) return null
        val json = try { JSONObject(param) } catch (_: Throwable) { return null }
        val packageName = json.opt("packageName") as? String ?: return null
        val action = json.opt("action") as? String ?: return null
        val selector = json.opt("selector") as? JSONObject ?: return null
        val viewId = selector.opt("viewId") as? String ?: return null
        if (!validPackage(packageName) || selector.length() != 1 || json.length() !in 3..4) return null
        if (viewId.length > 240 || viewIdPattern.matchEntire(viewId)?.groupValues?.get(1) != packageName) return null
        val text = json.opt("text") as? String
        val direction = json.opt("direction") as? String
        return when (action) {
            "click" -> if (json.length() == 3) ActionRequest(packageName, action, viewId, null, null) else null
            "setText" -> if (json.length() == 4 && json.has("text") && text != null &&
                text.length <= MAX_INPUT_CHARS && text.none { it.code < 32 || it.code == 127 }
                && !containsSensitiveText(text)
            ) ActionRequest(packageName, action, viewId, text, null) else null
            "scroll" -> if (json.length() == 4 && json.has("direction") && direction in setOf("forward", "backward"))
                ActionRequest(packageName, action, viewId, null, direction) else null
            else -> null
        }
    }
}
