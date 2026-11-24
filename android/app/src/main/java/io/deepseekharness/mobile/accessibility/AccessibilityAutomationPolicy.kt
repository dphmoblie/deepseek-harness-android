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
    private val androidViewIdPattern = Regex("^android:id/[A-Za-z_][A-Za-z0-9_]{0,79}$")

    /**
     * 敏感系统组件、权限/安装器及特权管理器（KernelSU/Magisk/APatch/Shizuku/国产 ROM 安全中心）：
     * 这些包名默认不能进白名单或被 AI 副屏拉起，仅在显式开启 `ACCESSIBILITY_SYSTEM_APPS` 后才放行。
     * 本应用不在其中（见 [SELF_PACKAGE]），本应用高危权限页与 Root 确认页另由不可绕过的自我提权护栏保护。
     */
    private val reservedPackages = setOf(
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        // Root / Shizuku 提权管理器
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "me.bmax.apatch",
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "moe.shizuku.privileged.api",
        // 主流 OEM 安全中心、权限管理与安装器
        "com.miui.securitycenter",
        "com.miui.packageinstaller",
        "com.oplus.securitypermission",
        "com.coloros.securitypermission",
        "com.oplus.appdetail",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
        "com.samsung.android.packageinstaller",
        "com.samsung.android.lool",
    )

    private val selfPrivilegeGuardText = Regex(
        "高危文件与命令权限|高危檔案與命令權限|high-risk\\s*file\\s*&\\s*command\\s*permissions|" +
            "高リスクファイル・コマンド権限|고위험\\s*파일\\s*및\\s*명령\\s*권한|" +
            "允许ai申请高危文件与命令权限|允許ai申請高危檔案與命令權限|" +
            "allow\\s*ai\\s*to\\s*request\\s*high-risk|" +
            "根通道高危命令|极危\\s*root\\s*命令|高风险\\s*root\\s*命令|critical\\s*root\\s*command",
        RegexOption.IGNORE_CASE,
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

    fun isReservedPackage(packageName: String): Boolean = packageName in reservedPackages

    fun validPackage(packageName: String, allowReserved: Boolean = false): Boolean {
        if (allowReserved && packageName in reservedPackages) return true
        return packageName.length <= 160 && packagePattern.matches(packageName) &&
            packageName !in reservedPackages
    }

    fun validPackages(packages: List<String>): Boolean =
        packages.distinct().size == packages.size &&
            packages.all { validPackage(it, allowReserved = false) }

    /**
     * 关闭名单限制只放行普通应用，包名格式与受保护系统组件校验仍然生效；
     * 仅当用户在启动器显式开启「无障碍操作受保护系统组件与权限弹窗」（[allowReserved]）时才放行系统组件。
     */
    fun packageAllowed(
        packageName: String,
        allowedPackages: Set<String>,
        whitelistEnabled: Boolean = true,
        allowReserved: Boolean = false,
    ): Boolean {
        if (!validPackage(packageName, allowReserved)) return false
        if (allowReserved && packageName in reservedPackages) return true
        return !whitelistEnabled || packageName in allowedPackages
    }

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

    /**
     * 本应用高危权限开关页与 Root 警告确认页防 AI 自我提权文本识别（覆盖五种语言）。
     * 该拦截不受 `accessibility_sensitive_windows` 开关放宽，防止 AI 自行打开高危开关或点击确认页。
     */
    fun containsSelfPrivilegeGuardText(value: CharSequence?): Boolean {
        if (value == null) return false
        val normalized = allWhitespace.replace(zeroWidthChars.replace(value, ""), "")
        return selfPrivilegeGuardText.containsMatchIn(value) || selfPrivilegeGuardText.containsMatchIn(normalized)
    }

    fun parseAction(
        param: String,
        allowReserved: Boolean = false,
        allowSensitiveText: Boolean = false,
    ): ActionRequest? {
        if (param.length !in 2..2048) return null
        val json = try { JSONObject(param) } catch (_: Throwable) { return null }
        val packageName = json.opt("packageName") as? String ?: return null
        val action = json.opt("action") as? String ?: return null
        val selector = json.opt("selector") as? JSONObject ?: return null
        val viewId = selector.opt("viewId") as? String ?: return null
        if (!validPackage(packageName, allowReserved) || selector.length() != 1 || json.length() !in 3..4) return null
        val viewIdMatches = if (allowReserved && packageName in reservedPackages && androidViewIdPattern.matches(viewId)) {
            true
        } else {
            viewIdPattern.matchEntire(viewId)?.groupValues?.get(1) == packageName
        }
        if (viewId.length > 240 || !viewIdMatches) return null
        val text = json.opt("text") as? String
        val direction = json.opt("direction") as? String
        return when (action) {
            "click" -> if (json.length() == 3) ActionRequest(packageName, action, viewId, null, null) else null
            "setText" -> if (json.length() == 4 && json.has("text") && text != null &&
                text.length <= MAX_INPUT_CHARS && text.none { it.code < 32 || it.code == 127 }
                && (allowSensitiveText || !containsSensitiveText(text))
            ) ActionRequest(packageName, action, viewId, text, null) else null
            "scroll" -> if (json.length() == 4 && json.has("direction") && direction in setOf("forward", "backward"))
                ActionRequest(packageName, action, viewId, null, direction) else null
            else -> null
        }
    }
}
