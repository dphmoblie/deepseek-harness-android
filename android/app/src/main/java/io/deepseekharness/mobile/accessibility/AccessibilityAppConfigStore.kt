package io.deepseekharness.mobile.accessibility

import android.content.Context
import org.json.JSONObject

/**
 * 一个应用的自动化参数：**「暂停自动规则」开关 + 界面新建规则时的默认值**。
 *
 * 与规则正文分开存（规则在 `accessibility_automation_rules`）：规则是「看到什么点什么」，
 * 参数是「这个应用还允不允许自动动、新规则的初值是多少」。把参数塞进规则 JSON 会让
 * 「改一个默认值」变成改规则数据——40 条规则里每条都要跟着重写一遍。
 *
 * 四条固定语义：
 * 1. **损坏一律按「已暂停」处理**（fail closed）：读不出来时宁可不动，也不在用户按下暂停之后
 *    因为文件损坏继续点击。方向与 `AutomationRuleStore`「坏数据删除自愈」相反，这是刻意的——
 *    那边丢的是规则内容，这边丢的是「用户按过暂停」这个事实；
 * 2. **坏数据不自动删除**：设置页的读取会如实报告损坏（[AccessibilityAppConfigRead.Corrupt]），
 *    由用户保存一次覆盖掉。自动删掉等于把「已暂停」静默改回「继续动」；
 * 3. 热路径（每个无障碍事件）要的是「是否暂停」这一个结论，但它走的是与设置页**同一个解析器**
 *    （见 [isPaused]）：另写一份只看 `paused` 字段的简化判定会与损坏判定分叉，于是第 1 条约定
 *    在真正生效的那条路径上被绕过；
 * 4. 默认值只影响界面新建规则时的初值，**不改动已有规则**，也不作为上限强制——
 *    否则改一次默认值就等于批量改写了用户已经确认过的规则。
 */
internal data class AccessibilityAppConfig(
    val paused: Boolean = false,
    val defaultMaxActions: Int = DEFAULT_MAX_ACTIONS,
    val defaultActionCoolDownMs: Int = DEFAULT_ACTION_COOLDOWN_MS,
    val defaultMatchDelayMs: Int = DEFAULT_MATCH_DELAY_MS,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_PAUSED, paused)
        .put(KEY_MAX_ACTIONS, defaultMaxActions)
        .put(KEY_COOLDOWN_MS, defaultActionCoolDownMs)
        .put(KEY_MATCH_DELAY_MS, defaultMatchDelayMs)

    companion object {
        const val KEY_PAUSED = "paused"
        const val KEY_MAX_ACTIONS = "defaultMaxActions"
        const val KEY_COOLDOWN_MS = "defaultActionCoolDownMs"
        const val KEY_MATCH_DELAY_MS = "defaultMatchDelayMs"

        /** 默认值 = 规则本身的建库默认值，用户不打开这一页时行为与从前一致。 */
        const val DEFAULT_MAX_ACTIONS = 1
        const val DEFAULT_ACTION_COOLDOWN_MS = 3_000
        const val DEFAULT_MATCH_DELAY_MS = 0
    }
}

/** 读取结果：要么可用（可能是默认值），要么损坏——损坏时给的是按 [AccessibilityAppConfig] 的 fail-closed 视图。 */
internal sealed interface AccessibilityAppConfigRead {
    data class Ok(val config: AccessibilityAppConfig) : AccessibilityAppConfigRead

    /** 界面据此提示「读不出来，已按已暂停处理，保存一次即可覆盖」。 */
    data class Corrupt(val message: String, val config: AccessibilityAppConfig) : AccessibilityAppConfigRead
}

/**
 * 每应用参数的持久化。
 *
 * 一个应用一个键、键值是一份完整 JSON，**单键单写**：暂停与默认值必须一起生效或一起不生效，
 * 拆成两个键就会出现「暂停写进去了、默认值还停在上一版」的中间态。
 */
internal object AccessibilityAppConfigStore {
    internal const val FILE_NAME = "accessibility_automation_app_config"

    internal fun key(packageName: String): String = "app:$packageName"

    /**
     * 热路径：只判断这个应用有没有被暂停。
     *
     * 走的是同一个解析器（[read]），**不是**单独去 `opt` 一下 `paused`：两份判定会分叉——
     * 比如盘上 `paused=false` 但数字越界时，[read] 认为整份读数不可信（按已暂停），
     * 而单独看 `paused` 会得出「没暂停」，于是「读不出来就当已暂停」这条约定在真正生效的
     * 热路径上被绕过。多解析四个字段的代价，比重建一次「查杀式静默失效」小得多。
     */
    fun isPaused(context: Context, packageName: String): Boolean =
        isPaused(AccessibilityAppConfigPreferences.from(context), packageName)

    internal fun isPaused(storage: AccessibilityAppConfigStorage, packageName: String): Boolean =
        when (val read = read(storage, packageName)) {
            is AccessibilityAppConfigRead.Ok -> read.config.paused
            is AccessibilityAppConfigRead.Corrupt -> true
        }

    fun read(context: Context, packageName: String): AccessibilityAppConfigRead =
        read(AccessibilityAppConfigPreferences.from(context), packageName)

    internal fun read(storage: AccessibilityAppConfigStorage, packageName: String): AccessibilityAppConfigRead {
        val raw = storage.read(key(packageName)) ?: return AccessibilityAppConfigRead.Ok(AccessibilityAppConfig())
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return corrupt("每应用参数不是合法 JSON")
        }
        val paused = json.opt(AccessibilityAppConfig.KEY_PAUSED) as? Boolean ?: return corrupt("每应用参数里的暂停开关不是布尔值")
        val maxActions = intField(json, AccessibilityAppConfig.KEY_MAX_ACTIONS) ?: return corrupt("每应用参数里的默认动作上限不是整数")
        val cooldown = intField(json, AccessibilityAppConfig.KEY_COOLDOWN_MS) ?: return corrupt("每应用参数里的默认冷却时间不是整数")
        val matchDelay = intField(json, AccessibilityAppConfig.KEY_MATCH_DELAY_MS) ?: return corrupt("每应用参数里的默认匹配延迟不是整数")
        if (maxActions !in AutomationRuleLimits.MIN_ACTION_MAXIMUM..AutomationRuleLimits.MAX_ACTION_MAXIMUM) {
            return corrupt("每应用参数里的默认动作上限超出 ${maxActionsRangeText()}")
        }
        if (cooldown !in AutomationRuleLimits.MIN_ACTION_COOLDOWN_MS..AutomationRuleLimits.MAX_ACTION_COOLDOWN_MS) {
            return corrupt("每应用参数里的默认冷却时间超出 ${cooldownRangeText()} 毫秒")
        }
        if (matchDelay !in AutomationRuleLimits.MIN_MATCH_DELAY_MS..AutomationRuleLimits.MAX_MATCH_DELAY_MS) {
            return corrupt("每应用参数里的默认匹配延迟超出 ${matchDelayRangeText()} 毫秒")
        }
        return AccessibilityAppConfigRead.Ok(
            AccessibilityAppConfig(
                paused = paused,
                defaultMaxActions = maxActions,
                defaultActionCoolDownMs = cooldown,
                defaultMatchDelayMs = matchDelay,
            ),
        )
    }

    /**
     * 覆盖这个应用的参数。整条替换，不合并。
     *
     * @throws IllegalArgumentException 包名非法或任一数值越界
     */
    fun write(context: Context, packageName: String, config: AccessibilityAppConfig) {
        write(AccessibilityAppConfigPreferences.from(context), packageName, config)
    }

    internal fun write(storage: AccessibilityAppConfigStorage, packageName: String, config: AccessibilityAppConfig) {
        require(AccessibilityAutomationPolicy.validPackage(packageName)) { "包名不在可自动化范围：$packageName" }
        require(config.defaultMaxActions in AutomationRuleLimits.MIN_ACTION_MAXIMUM..AutomationRuleLimits.MAX_ACTION_MAXIMUM) {
            "每应用默认动作上限必须是 ${maxActionsRangeText()}"
        }
        require(
            config.defaultActionCoolDownMs in AutomationRuleLimits.MIN_ACTION_COOLDOWN_MS..AutomationRuleLimits.MAX_ACTION_COOLDOWN_MS,
        ) { "每应用默认冷却时间必须是 ${cooldownRangeText()} 毫秒" }
        require(config.defaultMatchDelayMs in AutomationRuleLimits.MIN_MATCH_DELAY_MS..AutomationRuleLimits.MAX_MATCH_DELAY_MS) {
            "每应用默认匹配延迟必须是 ${matchDelayRangeText()} 毫秒"
        }
        storage.write(key(packageName), config.toJson().toString())
    }

    /** 删除这个应用的参数（回到「未暂停 + 默认值」）。删除规则时不顺带调用：参数是独立的用户决定。 */
    fun clear(context: Context, packageName: String) {
        clear(AccessibilityAppConfigPreferences.from(context), packageName)
    }

    internal fun clear(storage: AccessibilityAppConfigStorage, packageName: String) {
        storage.remove(key(packageName))
    }

    private fun corrupt(message: String): AccessibilityAppConfigRead =
        AccessibilityAppConfigRead.Corrupt("$message；已按「已暂停」处理", AccessibilityAppConfig(paused = true))

    private fun intField(json: JSONObject, name: String): Int? {
        val value = json.opt(name) ?: return null
        if (value !is Number || value.toDouble() != value.toInt().toDouble()) return null
        return value.toInt()
    }

    // 越界说明里的数字直接取自限额对象，改限额时文案跟着变，不需要两处同步。
    private fun maxActionsRangeText(): String =
        "${AutomationRuleLimits.MIN_ACTION_MAXIMUM}..${AutomationRuleLimits.MAX_ACTION_MAXIMUM}"

    private fun cooldownRangeText(): String =
        "${AutomationRuleLimits.MIN_ACTION_COOLDOWN_MS}..${AutomationRuleLimits.MAX_ACTION_COOLDOWN_MS}"

    private fun matchDelayRangeText(): String =
        "${AutomationRuleLimits.MIN_MATCH_DELAY_MS}..${AutomationRuleLimits.MAX_MATCH_DELAY_MS}"
}

/**
 * 参数的存储抽象：生产实现包 SharedPreferences，JVM 单测用内存替身。
 *
 * [write] 与 [remove] 都必须是**单键**操作，不能拆成多步（见 [AccessibilityAppConfigStore] 注释）。
 */
internal interface AccessibilityAppConfigStorage {
    fun read(key: String): String?

    fun write(key: String, value: String)

    fun remove(key: String)
}

/** [AccessibilityAppConfigStore] 的生产存储：`commit()` 同步落盘，按下去就生效。 */
internal object AccessibilityAppConfigPreferences {
    fun from(context: Context): AccessibilityAppConfigStorage {
        val preferences = context.applicationContext
            .getSharedPreferences(AccessibilityAppConfigStore.FILE_NAME, Context.MODE_PRIVATE)
        return object : AccessibilityAppConfigStorage {
            override fun read(key: String): String? =
                if (preferences.contains(key)) preferences.getString(key, null) else null

            override fun write(key: String, value: String) {
                // commit() 而不是 apply()：这是「按下去就必须生效」的开关（暂停）。
                preferences.edit().putString(key, value).commit()
            }

            override fun remove(key: String) {
                preferences.edit().remove(key).commit()
            }
        }
    }
}
