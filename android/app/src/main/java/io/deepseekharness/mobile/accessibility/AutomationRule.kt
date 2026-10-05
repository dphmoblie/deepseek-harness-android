package io.deepseekharness.mobile.accessibility

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动化规则的参数模型与**严格** JSON 编解码。
 *
 * 设计口径（与 `AccessibilityAutomationPolicy` 的「白名单写入」一脉相承）：
 * 1. **只接收参数，不执行动作**。本文件是三块纯逻辑的第一块：模型 + 校验 + 编解码；
 *    盘面持久化在 `AutomationRuleStore`，匹配判定在 `AutomationRuleMatcher`。三者都不碰
 *    `Context`、无障碍服务与 AI 工具面，因此可以在 JVM 单测里跑完全部语义。
 * 2. **严格解析，不静默忽略**。任何未知字段、类型不符、越界取值、重复键一律**整体拒绝**，
 *    不截断、不取默认值、不丢字段。理由和 `AccessibilityAutomationPolicy.parseAction` 一致：
 *    静默容忍会让「用户以为生效的规则」与「实际落盘的规则」长期分叉，而自动化的副作用是
 *    真人手机上的真实点击，分叉的代价不是一次报错。
 * 3. **错误可桥接**。[AutomationRuleException] 带受控 [AutomationRuleException.code]
 *    （大写字母/数字/下划线，直接可作为桥接失败码过桥，也符合 `AuditPolicy.detailPattern`），
 *    文案是中文，且**不含**规则内容之外的敏感信息——规则参数本身就是用户自己填的。
 *
 * 字段名与取值范围是权威模型，不允许自创（见任务单）；`Selector` 的每个字段都可空，
 * 但**至少要有一个非空**，否则这条选择器永远为真，等于把整条规则变成「见谁点谁」。
 */

/** 规则的稳定标识：1..64 字符，只允许 `[A-Za-z0-9._-]`。 */
private val RULE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")

/**
 * `Selector.id` 的两种写法。
 *
 * 全写与 `AccessibilityAutomationPolicy.viewIdPattern` **同口径**（该常量是私有的，故在此复刻一份，
 * 并有一条单测把两者钉在一起：任何一侧改动都会让测试变红）；短写只认 `:id/name`。
 */
private val FULL_VIEW_ID_PATTERN =
    Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){1,12}:id/[A-Za-z_][A-Za-z0-9_]{0,79}$")

/** 短写：`:id/name`，匹配任意包的资源名后缀。 */
private val SUFFIX_VIEW_ID_PATTERN = Regex("^:id/[A-Za-z_][A-Za-z0-9_]{0,79}$")

/** `launch.component` 的写法：`包名/类名`，类名允许用 `.` 开头的相对写法。 */
private val COMPONENT_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.]{0,159}/[A-Za-z_$.][A-Za-z0-9_$.]{0,199}$")

/**
 * 所有字符串字段的统一上限（单位：UTF-16 字符，与 `String.length` 一致）。
 *
 * 唯一出处是 [AutomationRuleLimits.MAX_STRING_CHARS]，这里只是文件内的短别名。
 * 不写成 `const val = 200`：对象体内与文件级同名常量互相引用会让 Kotlin 报递归类型检查。
 */
private val MAX_STRING_CHARS = AutomationRuleLimits.MAX_STRING_CHARS

/**
 * 规则参数不合法。继承 [IllegalArgumentException] 而不是自创第二套失败通道，
 * 与 `AccessibilityGuardException` 同一个取舍：既有的「`IllegalArgumentException` → 配置格式错误」
 * 映射能直接复用，同时 [code] 又让桥接层可以单独识别规则族的失败。
 */
internal class AutomationRuleException(val code: String, message: String) : IllegalArgumentException(message)

/** [AutomationRuleException.code] 的受控取值；前端与桥接层据此区分失败原因。 */
internal object AutomationRuleCodes {
    const val MALFORMED = "AUTOMATION_RULE_MALFORMED"
    const val FIELD_INVALID = "AUTOMATION_RULE_FIELD_INVALID"
    const val PATTERN_INVALID = "AUTOMATION_RULE_PATTERN_INVALID"
    const val PACKAGE_INVALID = "AUTOMATION_RULE_PACKAGE_INVALID"
    const val UNKNOWN_FIELD = "AUTOMATION_RULE_UNKNOWN_FIELD"
    const val DIGEST_INVALID = "AUTOMATION_RULE_DIGEST_INVALID"
}

/**
 * 硬限制的唯一出处。所有数值都是**拒绝阈值**（超过直接拒，绝不截断成合法值）：
 * 截断会让「用户写了 41 条」变成「静默丢了 1 条」，而自动化的用户无法从行为上分辨这两者。
 *
 * 单应用与全局的字节上限都按 **UTF-8 字节**计算：SharedPreferences 落盘的是 UTF-8，
 * 中文规则名/中文文案占 3 字节，若按字符数算，40 条中文规则的「64 KiB 字符」会变成 192 KiB 字节。
 */
internal object AutomationRuleLimits {
    const val MAX_RULES_PER_PACKAGE = 40
    const val MAX_PACKAGE_JSON_BYTES = 64 * 1024
    const val MAX_TOTAL_JSON_BYTES = 256 * 1024
    const val MAX_SELECTORS = 16

    // 直接写字面量而不是引用文件级的 MAX_STRING_CHARS：常量名在对象体内会遮蔽外层同名声明，
    // `const val MAX_STRING_CHARS = MAX_STRING_CHARS` 会变成自引用（Kotlin 报递归类型检查）。
    // 两处数值由 `AutomationRuleTest` 的限额用例锁死一致。
    const val MAX_STRING_CHARS = 200
    const val MIN_MATCH_DELAY_MS = 0
    const val MAX_MATCH_DELAY_MS = 5_000
    const val MIN_ACTION_MAXIMUM = 1
    const val MAX_ACTION_MAXIMUM = 99
    const val MIN_ACTION_COOLDOWN_MS = 0
    const val MAX_ACTION_COOLDOWN_MS = 600_000
    const val MIN_SWIPE_DURATION_MS = 100
    const val MAX_SWIPE_DURATION_MS = 2_000
    const val MIN_WAIT_DELAY_MS = 1
    const val MAX_WAIT_DELAY_MS = 10_000

    /** 序列化外观版本；容器（store）读盘时据此判断未来格式。 */
    const val SCHEMA = 1
}

/** 一个选择器的全部字段；全部可空，但至少一个非空（由 [Selector.isUsable] 判定）。 */
internal data class Selector(
    /** `text` 为**精确相等**（不是包含）。 */
    val text: String? = null,
    val textContains: String? = null,
    val textStartsWith: String? = null,
    val textEndsWith: String? = null,
    /** `viewIdResourceName`；允许 `pkg:id/name` 全写或 `:id/name` 结尾匹配。 */
    val id: String? = null,
    val desc: String? = null,
    val descContains: String? = null,
    val className: String? = null,
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val editable: Boolean? = null,
    val minWidth: Int? = null,
    val minHeight: Int? = null,
) {
    /** 至少一个字段非空；空选择器永远是「谁都能过」，必须拒绝。 */
    fun isUsable(): Boolean = text != null || textContains != null || textStartsWith != null ||
        textEndsWith != null || id != null || desc != null || descContains != null ||
        className != null || clickable != null || enabled != null || editable != null ||
        minWidth != null || minHeight != null

    fun toJson(): JSONObject = JSONObject().also { json ->
        text?.let { json.put("text", it) }
        textContains?.let { json.put("textContains", it) }
        textStartsWith?.let { json.put("textStartsWith", it) }
        textEndsWith?.let { json.put("textEndsWith", it) }
        id?.let { json.put("id", it) }
        desc?.let { json.put("desc", it) }
        descContains?.let { json.put("descContains", it) }
        className?.let { json.put("className", it) }
        clickable?.let { json.put("clickable", it) }
        enabled?.let { json.put("enabled", it) }
        editable?.let { json.put("editable", it) }
        minWidth?.let { json.put("minWidth", it) }
        minHeight?.let { json.put("minHeight", it) }
    }

    companion object {
        private val FIELDS = setOf(
            "text", "textContains", "textStartsWith", "textEndsWith", "id", "desc", "descContains",
            "className", "clickable", "enabled", "editable", "minWidth", "minHeight",
        )

        fun fromJson(value: Any?): Selector = fromJson(requireObject(value, "选择器"))

        fun fromJson(json: JSONObject): Selector {
            rejectUnknownFields(json, FIELDS, "选择器")
            val selector = Selector(
                text = optionalString(json, "text", MAX_STRING_CHARS),
                textContains = optionalString(json, "textContains", MAX_STRING_CHARS),
                textStartsWith = optionalString(json, "textStartsWith", MAX_STRING_CHARS),
                textEndsWith = optionalString(json, "textEndsWith", MAX_STRING_CHARS),
                id = optionalViewId(json),
                desc = optionalString(json, "desc", MAX_STRING_CHARS),
                descContains = optionalString(json, "descContains", MAX_STRING_CHARS),
                className = optionalString(json, "className", MAX_STRING_CHARS),
                clickable = optionalBoolean(json, "clickable"),
                enabled = optionalBoolean(json, "enabled"),
                editable = optionalBoolean(json, "editable"),
                minWidth = optionalSize(json, "minWidth"),
                minHeight = optionalSize(json, "minHeight"),
            )
            if (!selector.isUsable()) {
                fail(AutomationRuleCodes.FIELD_INVALID, "选择器至少要有一个条件（text/id/desc/className/状态/尺寸）")
            }
            return selector
        }

        private fun optionalViewId(json: JSONObject): String? {
            val id = optionalString(json, "id", MAX_STRING_CHARS) ?: return null
            // 两种写法二选一：全写 `包名:id/名称`，短写 `:id/名称`。
            // 其它形态（缺 id/、缺包名、乱写）一律拒绝，不做「宽松包含」——宽松包含会让
            // `id: "login"` 这种写法悄悄匹配到任意包含 login 的资源名。
            if (!FULL_VIEW_ID_PATTERN.matches(id) && !SUFFIX_VIEW_ID_PATTERN.matches(id)) {
                fail(AutomationRuleCodes.FIELD_INVALID, "选择器 id 必须写成 包名:id/名称 或 :id/名称")
            }
            return id
        }

        private fun optionalSize(json: JSONObject, key: String): Int? {
            val value = optionalInt(json, key) ?: return null
            if (value < 0) fail(AutomationRuleCodes.FIELD_INVALID, "选择器 $key 不能为负数")
            return value
        }
    }
}

/** 规则要执行的动作。字段与 `type` 严格配套：不属于该动作的字段一律拒绝。 */
internal data class AutomationAction(
    val type: String,
    /** swipe 用，100..2000。 */
    val durationMs: Int? = null,
    /** swipe 用："up" | "down" | "left" | "right"。 */
    val direction: String? = null,
    /** key 用，取值见 [AutomationKeyCodes]。 */
    val keyCode: Int? = null,
    /** wait 用，1..10000。 */
    val delayMs: Int? = null,
    /** launch 用："包名/类名"。 */
    val component: String? = null,
    /** launch 用：仅 http/https/market。 */
    val uri: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().also { json ->
        json.put("type", type)
        durationMs?.let { json.put("durationMs", it) }
        direction?.let { json.put("direction", it) }
        keyCode?.let { json.put("keyCode", it) }
        delayMs?.let { json.put("delayMs", it) }
        component?.let { json.put("component", it) }
        uri?.let { json.put("uri", it) }
    }

    companion object {
        val TYPES = setOf("click", "clickCenter", "longClick", "back", "swipe", "key", "wait", "launch")
        val DIRECTIONS = setOf("up", "down", "left", "right")

        /** `launch.uri` 允许的协议；与 `DeviceCommandRunner` 的「不给任意 Activity/Intent」口径一致。 */
        val URI_SCHEMES = listOf("http://", "https://", "market://")

        private val FIELDS =
            setOf("type", "durationMs", "direction", "keyCode", "delayMs", "component", "uri")

        fun fromJson(value: Any?): AutomationAction = fromJson(requireObject(value, "动作"))

        fun fromJson(json: JSONObject): AutomationAction {
            val type = requiredString(json, "type", MAX_STRING_CHARS)
            if (type !in TYPES) {
                fail(AutomationRuleCodes.FIELD_INVALID, "动作类型不支持：$type（可选 ${TYPES.joinToString("/")}）")
            }
            // 先按动作类型收紧允许出现的字段，再逐个解析：这样「click 带了个 keyCode」当场报错，
            // 而不是带着可疑参数一路走到执行器。
            val allowed = when (type) {
                "swipe" -> setOf("type", "durationMs", "direction")
                "key" -> setOf("type", "keyCode")
                "wait" -> setOf("type", "delayMs")
                "launch" -> setOf("type", "component", "uri")
                else -> setOf("type")
            }
            rejectUnknownFields(json, allowed, "动作 $type")
            val action = AutomationAction(
                type = type,
                durationMs = optionalInt(json, "durationMs"),
                direction = optionalString(json, "direction", MAX_STRING_CHARS),
                keyCode = optionalInt(json, "keyCode"),
                delayMs = optionalInt(json, "delayMs"),
                component = optionalString(json, "component", MAX_STRING_CHARS),
                uri = optionalString(json, "uri", MAX_STRING_CHARS),
            )
            return action.validated()
        }
    }

    /** 逐动作校验；不合法直接抛，调用方拿不到「差不多能用」的动作。 */
    fun validated(): AutomationAction {
        if (type !in TYPES) {
            fail(AutomationRuleCodes.FIELD_INVALID, "动作类型不支持：$type（可选 ${TYPES.joinToString("/")}）")
        }
        when (type) {
            "swipe" -> {
                val duration = durationMs
                if (duration == null) {
                    fail(
                        AutomationRuleCodes.FIELD_INVALID,
                        "滑动动作需要 durationMs（${AutomationRuleLimits.MIN_SWIPE_DURATION_MS}~${AutomationRuleLimits.MAX_SWIPE_DURATION_MS} 毫秒）",
                    )
                }
                if (duration !in AutomationRuleLimits.MIN_SWIPE_DURATION_MS..AutomationRuleLimits.MAX_SWIPE_DURATION_MS) {
                    fail(
                        AutomationRuleCodes.FIELD_INVALID,
                        "滑动时间必须在 ${AutomationRuleLimits.MIN_SWIPE_DURATION_MS}~${AutomationRuleLimits.MAX_SWIPE_DURATION_MS} 毫秒之间",
                    )
                }
                if (direction !in DIRECTIONS) {
                    fail(AutomationRuleCodes.FIELD_INVALID, "滑动方向必须是 ${DIRECTIONS.joinToString("/")}")
                }
            }

            "key" -> {
                val key = keyCode
                if (key == null) fail(AutomationRuleCodes.FIELD_INVALID, "按键动作需要 keyCode")
                if (key !in AutomationKeyCodes.ALLOWED) {
                    fail(
                        AutomationRuleCodes.FIELD_INVALID,
                        "按键不在允许列表（可用：${AutomationKeyCodes.names().joinToString("/")}）",
                    )
                }
            }

            "wait" -> {
                val delay = delayMs
                if (delay == null) {
                    fail(
                        AutomationRuleCodes.FIELD_INVALID,
                        "等待动作需要 delayMs（${AutomationRuleLimits.MIN_WAIT_DELAY_MS}~${AutomationRuleLimits.MAX_WAIT_DELAY_MS} 毫秒）",
                    )
                }
                if (delay !in AutomationRuleLimits.MIN_WAIT_DELAY_MS..AutomationRuleLimits.MAX_WAIT_DELAY_MS) {
                    fail(
                        AutomationRuleCodes.FIELD_INVALID,
                        "等待时间必须在 ${AutomationRuleLimits.MIN_WAIT_DELAY_MS}~${AutomationRuleLimits.MAX_WAIT_DELAY_MS} 毫秒之间",
                    )
                }
            }

            "launch" -> {
                if (component == null && uri == null) {
                    fail(AutomationRuleCodes.FIELD_INVALID, "启动动作至少需要 component 或 uri 之一")
                }
                component?.let {
                    if (!COMPONENT_PATTERN.matches(it)) {
                        fail(AutomationRuleCodes.FIELD_INVALID, "启动组件必须写成 包名/类名")
                    }
                    val packageName = it.substringBefore('/')
                    if (!AccessibilityAutomationPolicy.validPackage(packageName)) {
                        fail(AutomationRuleCodes.PACKAGE_INVALID, "启动组件里的包名不在可自动化范围：$packageName")
                    }
                }
                uri?.let {
                    if (URI_SCHEMES.none(it::startsWith)) {
                        fail(AutomationRuleCodes.FIELD_INVALID, "启动地址只支持 http/https/market 三种协议")
                    }
                }
            }
        }
        return this
    }
}

/**
 * `key` 动作允许的按键码：与 `VirtualScreenPolicy` 里副屏 `keyevent` 用的是**同一张表**，
 * 不在这张表里的按键一律拒绝。刻意不放开整段 `KeyEvent`：`KEYCODE_*` 里有电源、恢复出厂、
 * 拨号、快捷键等破坏性/越权按键，规则是「一次解析、长期自动执行」的，放开整段等于把
 * 那些按键变成一条 JSON 就能触发。
 */
internal object AutomationKeyCodes {
    private val BY_NAME = linkedMapOf(
        "BACK" to 4,
        "DPAD_UP" to 19,
        "DPAD_DOWN" to 20,
        "DPAD_LEFT" to 21,
        "DPAD_RIGHT" to 22,
        "DPAD_CENTER" to 23,
        "TAB" to 61,
        "SPACE" to 62,
        "ENTER" to 66,
        "DEL" to 67,
        "ESC" to 111,
    )

    val ALLOWED: Set<Int> = BY_NAME.values.toSet()

    fun names(): List<String> = BY_NAME.keys.toList()

    /** 按键名 → 键值；不在允许列表返回 null（调用方自己映射成受控失败）。 */
    fun codeOf(name: String): Int? = BY_NAME[name]
}

/** 一条规则：包名 + 可选 Activity 白黑名单 + 选择器（AND）+ 动作 + 抑制参数。 */
internal data class AutomationRule(
    val id: String,
    val packageName: String,
    val enabled: Boolean = true,
    val allowActivities: List<String> = emptyList(),
    val denyActivities: List<String> = emptyList(),
    val matchDelayMs: Int = 0,
    val maxActions: Int = 1,
    val actionCoolDownMs: Int = 3_000,
    /** `"activity"`（Activity 变化即重置）或 `"screen"`（窗口更新即重置）。 */
    val resetOn: String = RESET_ACTIVITY,
    val selectors: List<Selector>,
    val action: AutomationAction,
) {
    fun toJson(): JSONObject = JSONObject().also { json ->
        json.put("id", id)
        json.put("packageName", packageName)
        json.put("enabled", enabled)
        json.put("allowActivities", JSONArray(allowActivities))
        json.put("denyActivities", JSONArray(denyActivities))
        json.put("matchDelayMs", matchDelayMs)
        json.put("maxActions", maxActions)
        json.put("actionCoolDownMs", actionCoolDownMs)
        json.put("resetOn", resetOn)
        json.put("selectors", JSONArray().also { array -> selectors.forEach { array.put(it.toJson()) } })
        json.put("action", action.toJson())
    }

    /** 逐字段校验（含包名复用 [AccessibilityAutomationPolicy.validPackage]），不合法直接抛。 */
    fun validated(): AutomationRule {
        if (!RULE_ID_PATTERN.matches(id)) {
            fail(AutomationRuleCodes.FIELD_INVALID, "规则标识必须是 1~64 个字母、数字、点、下划线或连字符")
        }
        if (!AccessibilityAutomationPolicy.validPackage(packageName)) {
            fail(AutomationRuleCodes.PACKAGE_INVALID, "规则包名不在可自动化范围：$packageName")
        }
        if (matchDelayMs !in AutomationRuleLimits.MIN_MATCH_DELAY_MS..AutomationRuleLimits.MAX_MATCH_DELAY_MS) {
            fail(
                AutomationRuleCodes.FIELD_INVALID,
                "匹配延迟必须在 ${AutomationRuleLimits.MIN_MATCH_DELAY_MS}~${AutomationRuleLimits.MAX_MATCH_DELAY_MS} 毫秒之间",
            )
        }
        if (maxActions !in AutomationRuleLimits.MIN_ACTION_MAXIMUM..AutomationRuleLimits.MAX_ACTION_MAXIMUM) {
            fail(
                AutomationRuleCodes.FIELD_INVALID,
                "执行次数上限必须在 ${AutomationRuleLimits.MIN_ACTION_MAXIMUM}~${AutomationRuleLimits.MAX_ACTION_MAXIMUM} 之间",
            )
        }
        if (actionCoolDownMs !in AutomationRuleLimits.MIN_ACTION_COOLDOWN_MS..AutomationRuleLimits.MAX_ACTION_COOLDOWN_MS) {
            fail(
                AutomationRuleCodes.FIELD_INVALID,
                "冷却时间必须在 ${AutomationRuleLimits.MIN_ACTION_COOLDOWN_MS}~${AutomationRuleLimits.MAX_ACTION_COOLDOWN_MS} 毫秒之间",
            )
        }
        if (resetOn != RESET_ACTIVITY && resetOn != RESET_SCREEN) {
            fail(AutomationRuleCodes.FIELD_INVALID, "重置方式必须是 $RESET_ACTIVITY 或 $RESET_SCREEN")
        }
        if (selectors.isEmpty() || selectors.size > AutomationRuleLimits.MAX_SELECTORS) {
            fail(
                AutomationRuleCodes.FIELD_INVALID,
                "选择器数量必须在 1~${AutomationRuleLimits.MAX_SELECTORS} 之间",
            )
        }
        selectors.forEach { if (!it.isUsable()) fail(AutomationRuleCodes.FIELD_INVALID, "选择器至少要有一个条件") }
        // 正则在这里就编译一次：语法错误必须在解析/写入期暴露，而不是等到用户站在目标界面时
        // 匹配器抛异常（那时服务只能把它当一次未知故障，用户看不到任何可改的提示）。
        allowActivities.forEach { compileRulePattern(it, "Activity 白名单") }
        denyActivities.forEach { compileRulePattern(it, "Activity 黑名单") }
        action.validated()
        return this
    }

    companion object {
        const val RESET_ACTIVITY = "activity"
        const val RESET_SCREEN = "screen"

        private val FIELDS = setOf(
            "id", "packageName", "enabled", "allowActivities", "denyActivities", "matchDelayMs",
            "maxActions", "actionCoolDownMs", "resetOn", "selectors", "action",
        )

        /**
         * 严格的整条解析：JSON 字符串 → [AutomationRule]。
         *
         * @throws AutomationRuleException 不是对象、缺字段、类型不符、取值越界、有未知字段
         */
        fun fromJson(text: String): AutomationRule {
            val json = try {
                JSONObject(text)
            } catch (_: Throwable) {
                fail(AutomationRuleCodes.MALFORMED, "规则不是合法的 JSON 对象")
            }
            return fromJson(json)
        }

        fun fromJson(json: JSONObject): AutomationRule {
            rejectUnknownFields(json, FIELDS, "规则")
            return AutomationRule(
                id = requiredString(json, "id", MAX_STRING_CHARS),
                packageName = requiredString(json, "packageName", MAX_STRING_CHARS, AutomationRuleCodes.PACKAGE_INVALID),
                enabled = optionalBoolean(json, "enabled") ?: true,
                allowActivities = stringList(json, "allowActivities"),
                denyActivities = stringList(json, "denyActivities"),
                matchDelayMs = optionalInt(json, "matchDelayMs") ?: 0,
                maxActions = optionalInt(json, "maxActions") ?: 1,
                actionCoolDownMs = optionalInt(json, "actionCoolDownMs") ?: 3_000,
                resetOn = optionalString(json, "resetOn", MAX_STRING_CHARS) ?: RESET_ACTIVITY,
                selectors = selectorList(json),
                action = AutomationAction.fromJson(requirePresent(json, "action", "动作")),
            ).validated()
        }

        private fun stringList(json: JSONObject, key: String): List<String> {
            val value = optional(json, key) ?: return emptyList()
            val array = value as? JSONArray
            if (array == null) fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 必须是字符串数组")
            return (0 until array.length()).map { index ->
                val element = array.opt(index) as? String
                if (element == null) {
                    fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 的第 ${index + 1} 项必须是字符串")
                }
                if (element.length > MAX_STRING_CHARS) {
                    fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 的第 ${index + 1} 项超过 $MAX_STRING_CHARS 字符")
                }
                element
            }
        }

        private fun selectorList(json: JSONObject): List<Selector> {
            val value = requirePresent(json, "selectors", "选择器列表")
            val array = value as? JSONArray
            if (array == null) fail(AutomationRuleCodes.FIELD_INVALID, "选择器列表必须是数组")
            val size = array.length()
            if (size == 0) fail(AutomationRuleCodes.FIELD_INVALID, "选择器至少要有 1 个")
            if (size > AutomationRuleLimits.MAX_SELECTORS) {
                fail(AutomationRuleCodes.FIELD_INVALID, "选择器最多 ${AutomationRuleLimits.MAX_SELECTORS} 个")
            }
            return (0 until size).map { Selector.fromJson(array.opt(it)) }
        }
    }
}

/** 序列化一条规则（含未知字段校验则由解析侧负责）。 */
internal fun AutomationRule.toRuleJson(): JSONObject = validated().toJson()

/**
 * 整份 JSON 的 UTF-8 字节数。
 *
 * 用 UTF-8 而不是 [String.length]：SharedPreferences 落盘是 UTF-8，中文规则名占 3 字节，
 * 按字符数算会把 64 KiB 的限额实际放大到近 3 倍。
 */
internal fun jsonUtf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

/** 编译规则里的正则（Activity 白黑名单）；语法错误在解析期就暴露，不拖到匹配时。 */
internal fun compileRulePattern(pattern: String, field: String): Regex = try {
    Regex(pattern)
} catch (_: Throwable) {
    throw AutomationRuleException(AutomationRuleCodes.PATTERN_INVALID, "$field 不是合法的正则表达式：$pattern")
}

// ---- 严格解析的公共小工具：所有错误都带受控 code，异常类型统一是 AutomationRuleException ----

private fun fail(code: String, message: String): Nothing = throw AutomationRuleException(code, message)

/** 读取可空字段；键不存在与显式 `null` 一律按「未提供」处理（JSON 两种写法等价）。 */
private fun optional(json: JSONObject, key: String): Any? = if (json.has(key)) json.opt(key) else null

private fun requirePresent(json: JSONObject, key: String, label: String): Any {
    val value = optional(json, key)
    if (value == null) fail(AutomationRuleCodes.FIELD_INVALID, "缺少字段 $key（$label）")
    return value
}

private fun requireObject(value: Any?, label: String): JSONObject {
    val json = value as? JSONObject
    if (json == null) fail(AutomationRuleCodes.FIELD_INVALID, "$label 必须是 JSON 对象")
    return json
}

private fun requiredString(
    json: JSONObject,
    key: String,
    maxChars: Int,
    emptyCode: String = AutomationRuleCodes.FIELD_INVALID,
): String {
    val value = requirePresent(json, key, key)
    val text = value as? String
    if (text == null) fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 必须是字符串")
    // 空串的错误码可由调用方改写：包名空缺的语义是「这条规则没有归属」（PACKAGE_INVALID），
    // 而不是「某个字段写得不对」。其余必填字段保持字段级错误码。
    if (text.isEmpty()) fail(emptyCode, "字段 $key 不能为空")
    if (text.length > maxChars) {
        fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 超过 $maxChars 字符")
    }
    return text
}

private fun optionalString(json: JSONObject, key: String, maxChars: Int): String? {
    val value = optional(json, key) ?: return null
    val text = value as? String
    if (text == null) fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 必须是字符串")
    // 显式空串按「未提供」处理：`"text": ""` 与省略 text 是同一个意思（匹配任意文本），
    // 但**不**因此放宽长度或类型判定。
    if (text.isEmpty()) return null
    if (text.length > maxChars) fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 超过 $maxChars 字符")
    return text
}

private fun optionalBoolean(json: JSONObject, key: String): Boolean? {
    val value = optional(json, key) ?: return null
    val flag = value as? Boolean
    if (flag == null) fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 必须是布尔值")
    return flag
}

private fun optionalInt(json: JSONObject, key: String): Int? {
    val value = optional(json, key) ?: return null
    // 只接受整数：浮点/字符串/布尔一律拒绝，避免 `1.9` 被静默截断成 1 毫秒或 1 次。
    return when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            value.toInt()
        } else {
            fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 超出整数范围")
        }

        else -> fail(AutomationRuleCodes.FIELD_INVALID, "字段 $key 必须是整数")
    }
}

/** 未知字段一律拒绝：拼错的字段名（如 `textContain`）必须当场报错，而不是静默变成「匹配任意文本」。 */
private fun rejectUnknownFields(json: JSONObject, allowed: Set<String>, label: String) {
    val unknown = json.keys().asSequence().filterNot(allowed::contains).toList()
    if (unknown.isNotEmpty()) {
        fail(AutomationRuleCodes.UNKNOWN_FIELD, "$label 含未知字段：${unknown.sorted().joinToString("、")}")
    }
}
