package io.deepseekharness.mobile.accessibility

import org.json.JSONObject

/** 无障碍自动化的纯参数规则；业务层与服务层共用，避免只在界面校验。 */
internal object AccessibilityAutomationPolicy {
    const val MAX_INPUT_CHARS = 512

    /**
     * 本应用自己的包名。
     *
     * 它**恒为白名单成员**：桥接层与策略层都会在规范化时补回来，用户无法删掉。原因是壳内界面
     * 本身就是用户要自动化的目标之一（读取层级、点击自己的控件），而把「本应用」排除在白名单外
     * 只会让同一台设备上出现两套规则。注意这**不等于**放宽任何自动化护栏：服务侧的敏感窗口拒绝
     * （密码框、验证码、支付、权限弹窗）与设备端确认一概照旧，本应用进白名单拿到的仍是同样的拒绝。
     */
    const val SELF_PACKAGE = "io.deepseekharness.mobile"

    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){1,12}$")
    private val viewIdPattern = Regex("^([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){1,12}):id/[A-Za-z_][A-Za-z0-9_]{0,79}$")

    /** 敏感系统组件：这些包名永远不能进白名单。本应用不在其中（见 [SELF_PACKAGE]）。 */
    private val reservedPackages = setOf(
        "android", "com.android.settings", "com.android.systemui", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )
    private val sensitiveText = Regex(
        "密码|口令|验证码|验证代码|动态码|支付|付款|收款|转账|转帐|银行卡|银行账户|授权|权限申请|" +
            "生物识别|指纹验证|面容验证|安全验证|password|passcode|verification[ -]?code|" +
            "one[ -]?time[ -]?password|\\botp\\b|payment|checkout|transfer|bank[ -]?account|" +
            "card[ -]?number|permission|biometric|fingerprint|face[ -]?id",
        RegexOption.IGNORE_CASE,
    )

    /** 匹配前把文本归一化：去零宽字符、折叠所有空白。否则把「密码」拆成两个节点、
     *  插入零宽字符或空格，就能让逐词匹配全部失配，对抗性应用借此绕过敏感界面拦截。 */
    private val zeroWidthChars = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")
    // \s 在 Java 默认只覆盖 ASCII 空白，全角空格（U+3000）要单列，否则「支　付」照样绕过。
    private val allWhitespace = Regex("[\\s\\u3000]+")

    data class ActionRequest(
        val packageName: String,
        val action: String,
        val viewId: String,
        val text: String?,
        val direction: String?,
    )

    fun validPackage(packageName: String): Boolean =
        packageName.length <= 160 && packagePattern.matches(packageName) &&
            packageName !in reservedPackages

    fun validPackages(packages: List<String>): Boolean =
        packages.distinct().size == packages.size &&
            packages.all(::validPackage)

    /** 关闭名单限制只放行普通应用，包名格式与受保护系统组件校验仍然生效。 */
    fun packageAllowed(packageName: String, allowedPackages: Set<String>, whitelistEnabled: Boolean = true): Boolean =
        validPackage(packageName) && (!whitelistEnabled || packageName in allowedPackages)

    /**
     * 白名单的唯一规范化口径：`trim` → 去重 → 补上 [SELF_PACKAGE] → 排序。
     *
     * 幂等（再跑一次结果不变），因此写入路径与读取路径可以各调用一次而不必担心漂移；
     * 排序与 [AccessibilityAutomationStore.state] 里 `allowedPackages` 的既有顺序一致，
     * 界面上不会因为「补了本应用」而重排。
     *
     * 这里**不做合法性判定**（非法包名照原样留着）：校验是写入边界的事，
     * 由调用方用 [validPackages] 判定后决定是拒绝还是整份失效；规范化里夹带过滤会让
     * 「用户以为删掉了、其实是被静默丢弃」这类问题变得不可见。
     */
    fun withSelf(packages: List<String>): List<String> =
        (packages.map(String::trim) + SELF_PACKAGE).distinct().sorted()

    fun containsSensitiveText(value: CharSequence?): Boolean {
        if (value == null) return false
        // 先归一化再匹配：把所有空白与零宽字符全部删掉，使「密 码」「密​码」
        // 「p a s s w o r d」这类拆字/插字符的绕过尝试重新拼回原词。代价是
        // 「pass word」这类自然分词也会被拼合，但敏感词命中即拒属安全方向。
        val normalized = allWhitespace.replace(zeroWidthChars.replace(value, ""), "")
        // 保留原文匹配，避免归一化移除单词边界后漏报「Enter OTP」等提示。
        return sensitiveText.containsMatchIn(value) || sensitiveText.containsMatchIn(normalized)
    }

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
