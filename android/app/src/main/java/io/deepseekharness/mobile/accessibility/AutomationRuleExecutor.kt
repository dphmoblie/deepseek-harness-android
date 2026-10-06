package io.deepseekharness.mobile.accessibility

/**
 * 事件驱动的自动化**闸门**与**执行器**：把 [AutomationRuleMatcher] 的判定真正落到节点上，
 * 并在执行前后补齐四层节流、节点指纹去重与「连续失败自动停用」。
 *
 * 本文件**不 import 任何 `android.*`**，这是刻意的：四层节流的每一个边界（差 1 毫秒、叠加时谁先拒绝、
 * 同一个指纹第二次事件、连续失败第 3 次、配额按冷却重开）都必须在 JVM 单测里能精确构造，
 * 而真机上复现一次这些边界要先装应用、开无障碍、走到目标界面。真正碰 `AccessibilityNodeInfo` /
 * `dispatchGesture` 的部分收敛到 [AutomationNodeBackend]，由服务侧实现（`DeepSeekAccessibilityService`）。
 *
 * 三个刻意的取舍：
 * 1. **节流语义的唯一出处是这里的闸门**。`AutomationRuleMatcher` 自带一套抑制状态（次数/时间戳），
 *    但执行器给它传的是一个一次性的空 [AutomationMatchState]，让它只做匹配。否则同一次执行会被
 *    两套计数各记一次，「本界面已执行 1 次」到底算 1 还是 2 就说不清了。
 * 2. **动作成功之后才提交状态**：配额与指纹只在 [AutomationRuleGate.recordSuccess] 里提交，
 *    失败只累计连续失败次数。这样「判定通过但点击失败」不会白吃掉一次配额。
 * 3. **指纹的存活范围到本轮配额结束**（冷却到点重开配额时清空，Activity 变化时也清空）。
 *    永久记住指纹会让「同一个弹窗第二次出现」永远点不掉，而防自触发循环真正需要的是
 *    「同一次动作自己触发的事件不要被当成新事件再执行一次」。
 *
 * 四层节流的判定次序（**先拒绝者胜**，这就是「叠加时取最严」的可判定口径）：
 * 第 0 层策略（自动停用/停用/白名单/自身包名/锁屏/敏感窗口/launch 目标）→ 结算窗口与冷却 →
 * 第 2 层 `ACTION_INTERVAL_MS`（所有规则共用）→ 第 3 层规则冷却 → 第 4 层 `actionMaximum`
 * （唯一闸门）→ 指纹去重 → 放行。
 * 指纹放在最后是因为前面几层能给出对用户更有用的原因（「配额已用尽且不会自动重开」比「指纹重复」有用）；
 * 指纹真正起作用的是**配额还有余量**的场景（`maxActions > 1` 或冷却为 0）。
 */

/**
 * 闸门与执行结果的受控原因码。
 *
 * 取值必须匹配 `AuditPolicy.detailPattern`（`^[A-Z][A-Z0-9_]{0,63}$`）：这些码会原样作为
 * `ACCESSIBILITY_ACTION` 审计的 detail 落盘，因此**不允许**带包名、viewId 或界面文本。
 */
internal object AutomationGateCodes {
    /** 放行（只用于测试与日志，不作为拒绝原因上报）。 */
    const val ALLOWED = "AUTOMATION_GATE_ALLOWED"

    // 第 0 层：策略。这些拒绝不消耗配额，也不计连续失败次数——它们拦的是「场景」，不是「规则做错了」。
    const val RULE_DISABLED = "AUTOMATION_RULE_DISABLED"
    const val RULE_AUTO_DISABLED = "AUTOMATION_RULE_AUTO_DISABLED"
    const val PACKAGE_NOT_ALLOWED = "AUTOMATION_PACKAGE_NOT_ALLOWED"
    const val SELF_PACKAGE_BLOCKED = "AUTOMATION_SELF_PACKAGE_BLOCKED"
    const val DEVICE_LOCKED = "AUTOMATION_DEVICE_LOCKED"
    const val SENSITIVE_WINDOW = "AUTOMATION_SENSITIVE_WINDOW"

    // 第 1–4 层节流与指纹。
    const val EVENT_THROTTLED = "AUTOMATION_EVENT_THROTTLED"
    const val ACTION_INTERVAL_THROTTLED = "AUTOMATION_ACTION_INTERVAL_THROTTLED"
    const val COOLDOWN_ACTIVE = "AUTOMATION_COOLDOWN_ACTIVE"
    const val ACTION_MAX_REACHED = "AUTOMATION_ACTION_MAX_REACHED"
    const val DUPLICATE_FINGERPRINT = "AUTOMATION_DUPLICATE_FINGERPRINT"

    // 执行侧。后端（[AutomationNodeBackend]）可以返回自己的 `AUTOMATION_*` 码，执行器原样放进结果里。
    const val DRY_RUN = "AUTOMATION_DRY_RUN"
    const val BACKEND_UNAVAILABLE = "AUTOMATION_BACKEND_UNAVAILABLE"
    const val NODE_UNRESOLVED = "AUTOMATION_NODE_UNRESOLVED"
    const val NODE_STALE = "AUTOMATION_NODE_STALE"
    const val ACTION_UNSUPPORTED = "AUTOMATION_ACTION_UNSUPPORTED"
    const val ACTION_PERFORMED = "AUTOMATION_ACTION_PERFORMED"
    const val CLICK_ANCESTOR_FALLBACK = "AUTOMATION_CLICK_ANCESTOR_FALLBACK"
    const val CLICK_FAILED = "AUTOMATION_CLICK_FAILED"
    const val LONG_CLICK_PERFORMED = "AUTOMATION_LONG_CLICK_PERFORMED"
    const val LONG_CLICK_FAILED = "AUTOMATION_LONG_CLICK_FAILED"
    const val GESTURE_PERFORMED = "AUTOMATION_GESTURE_PERFORMED"
    const val SWIPE_PERFORMED = "AUTOMATION_SWIPE_PERFORMED"
    const val SWIPE_FAILED = "AUTOMATION_SWIPE_FAILED"
    const val BACK_PERFORMED = "AUTOMATION_BACK_PERFORMED"
    const val BACK_FAILED = "AUTOMATION_BACK_FAILED"
    const val KEY_PERFORMED = "AUTOMATION_KEY_PERFORMED"
    const val KEY_FAILED = "AUTOMATION_KEY_FAILED"
    const val LAUNCH_PERFORMED = "AUTOMATION_LAUNCH_PERFORMED"
    const val LAUNCH_FAILED = "AUTOMATION_LAUNCH_FAILED"
    const val WAIT_PERFORMED = "AUTOMATION_WAIT_PERFORMED"
}

/**
 * 动作模型里的按键码 → 无障碍可达的全局动作名。
 *
 * 抽成纯函数是为了让「哪些按键真的发得出去」能被 JVM 单测锁住：无障碍服务注入按键只能走
 * `performGlobalAction`，可达的只有返回键与五个方向键（方向键需要 Android 12 及以上），
 * `TAB/SPACE/ENTER/DEL/ESC` 没有对应的全局动作（注入它们需要系统签名权限）。执行器在**发出动作之前**
 * 就用它把不可能的按键拦下来并如实回报，后端只负责把名字翻成系统常量、按版本判断可用性。
 */
internal object AutomationKeyRouting {
    const val BACK = "BACK"
    const val DPAD_UP = "DPAD_UP"
    const val DPAD_DOWN = "DPAD_DOWN"
    const val DPAD_LEFT = "DPAD_LEFT"
    const val DPAD_RIGHT = "DPAD_RIGHT"
    const val DPAD_CENTER = "DPAD_CENTER"

    private val ROUTED = listOf(BACK, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER)

    /** 返回 null 表示这个按键码在当前无障碍服务里不可注入（原因由调用方负责解释给用户）。 */
    fun globalActionName(keyCode: Int): String? =
        ROUTED.firstOrNull { AutomationKeyCodes.codeOf(it) == keyCode }

    /** 界面/日志里用的按键名；查不到就退回数字（`names()` 与 `codeOf` 是模型自己的名称表，不在这里抄一份）。 */
    fun keyName(keyCode: Int): String =
        AutomationKeyCodes.names().firstOrNull { AutomationKeyCodes.codeOf(it) == keyCode } ?: keyCode.toString()
}

/**
 * 闸门的既往状态：四层节流的时间基准、每条规则的配额/指纹/连续失败，以及内存里的自动停用标记。
 *
 * 可变且由服务侧持有（与 `AutomationMatchState` 同一个取舍）：服务连接重建时必须调用 [reset]，
 * 否则会把上一次连接的动作时间戳与配额带过来。
 *
 * 时间一律由调用方以毫秒注入，这里**不取** `System.currentTimeMillis()` / `SystemClock`：
 * 一个是可被用户改的墙上时钟，一个是开机以来的时长，混用会让冷却判定直接错乱。
 */
internal class AutomationGateState {
    private var lastEventAtMs: Long? = null
    private var lastAttemptAtMs: Long? = null
    private var selfPackageBlocked = true
    private val attemptAtMs = mutableMapOf<String, Long>()
    private val usedCount = mutableMapOf<String, Int>()
    private val quotaWindowOf = mutableMapOf<String, String>()
    private val identityWindowOf = mutableMapOf<String, String>()
    private val fingerprints = mutableMapOf<String, MutableSet<String>>()
    private val failureStreak = mutableMapOf<String, Int>()
    private val autoDisabled = linkedSetOf<String>()

    /** 上一次**通过第 1 层**的事件时间；第 1 层就是拿它做 `EVENT_THROTTLE_MS` 判定。 */
    val lastEventAt: Long? get() = lastEventAtMs

    /** 上一次**动作尝试**的时间（成功与失败都记账）：第 2 层拿它做绝对间隔。 */
    val lastActionAt: Long? get() = lastAttemptAtMs

    fun isSelfPackageBlocked(): Boolean = selfPackageBlocked

    /** 打开「允许对自身包名执行动作」。默认关闭：自己触发的事件又去点自己，是自触发循环的最短路径。 */
    fun allowSelfPackage() {
        selfPackageBlocked = false
    }

    fun isAutoDisabled(ruleId: String): Boolean = ruleId in autoDisabled

    fun autoDisabledRuleIds(): List<String> = autoDisabled.toList()

    /**
     * 清掉内存里的自动停用标记。
     *
     * 调用点是服务侧读到该规则的 `enabled = true`：内存标记在自动停用时已经写盘成 `enabled = false`，
     * 所以「读到 true」只可能是用户刚重新启用了它——此时必须放行，否则用户会发现规则被停用后再也开不起来。
     */
    fun clearAutoDisabled(ruleId: String): Boolean = autoDisabled.remove(ruleId)

    fun failureStreak(ruleId: String): Int = failureStreak[ruleId] ?: 0

    /** 本轮配额已用次数（每次动作**成功**加一）。 */
    fun used(ruleId: String): Int = usedCount[ruleId] ?: 0

    fun lastRuleAttemptAt(ruleId: String): Long? = attemptAtMs[ruleId]

    fun hasFingerprint(ruleId: String, fingerprint: String): Boolean = fingerprints[ruleId]?.contains(fingerprint) == true

    fun fingerprintCount(ruleId: String): Int = fingerprints[ruleId]?.size ?: 0

    fun reset() {
        lastEventAtMs = null
        lastAttemptAtMs = null
        attemptAtMs.clear()
        usedCount.clear()
        quotaWindowOf.clear()
        identityWindowOf.clear()
        fingerprints.clear()
        failureStreak.clear()
        autoDisabled.clear()
    }

    internal fun markEventAdmitted(nowMs: Long) {
        lastEventAtMs = nowMs
    }

    internal fun markAttempt(nowMs: Long) {
        lastAttemptAtMs = nowMs
    }

    /**
     * 记录某条规则最近一次**动作尝试**的时间：第 3 层规则冷却与「冷却到点配额重开」都以它为基准。
     *
     * 写入点是 [AutomationRuleGate.check] 判定放行的那一刻（动作即将执行，成败都算一次尝试）。
     * 与 [markAttempt] 分开：后者是跨规则共用的第 2 层绝对间隔基准，不带规则维度。
     */
    internal fun markRuleAttempt(ruleId: String, nowMs: Long) {
        attemptAtMs[ruleId] = nowMs
    }

    /**
     * 推进「配额窗口」。窗口变化时**只清次数，保留冷却基准**。
     *
     * 与 `AutomationMatchState.beginWindow` 刻意不同（那边把时间戳一起清）：动作自己把界面切走是常见情形
     * （点「跳过」→ 进下一个 Activity），连时间戳一起清等于每次切屏都能绕开冷却，规则冷却就形同虚设。
     */
    internal fun syncQuotaWindow(ruleId: String, windowKey: String): Boolean {
        if (quotaWindowOf[ruleId] == windowKey) return false
        quotaWindowOf[ruleId] = windowKey
        usedCount.remove(ruleId)
        return true
    }

    /** 推进「身份窗口」（只认包名/Activity）：窗口变化时清空指纹，指纹不随 `resetOn="screen"` 的事件序号清空。 */
    internal fun syncIdentityWindow(ruleId: String, windowKey: String): Boolean {
        if (identityWindowOf[ruleId] == windowKey) return false
        identityWindowOf[ruleId] = windowKey
        fingerprints.remove(ruleId)
        return true
    }

    /** 配额重开（冷却到点或时钟回退）：次数清零 + 指纹清空。 */
    internal fun reopenQuota(ruleId: String): Boolean {
        val had = usedCount.remove(ruleId) != null || fingerprints.remove(ruleId) != null
        return had
    }

    internal fun countSuccess(ruleId: String, fingerprint: String): Int {
        val used = (usedCount[ruleId] ?: 0) + 1
        usedCount[ruleId] = used
        failureStreak.remove(ruleId)
        val set = fingerprints.getOrPut(ruleId) { linkedSetOf() }
        set.add(fingerprint)
        // 上限按插入顺序淘汰最旧的：指纹是"别重复点同一个节点"的短期记忆，不是永久黑名单。
        while (set.size > AutomationRuleGate.MAX_FINGERPRINTS_PER_RULE) {
            val oldest = set.firstOrNull() ?: break
            set.remove(oldest)
        }
        return used
    }

    internal fun countFailure(ruleId: String): Int {
        val streak = (failureStreak[ruleId] ?: 0) + 1
        failureStreak[ruleId] = streak
        if (streak >= AutomationRuleGate.FAILURE_LIMIT) autoDisabled.add(ruleId)
        return streak
    }
}

/** 一次规则判定的全部输入。不含任何可变状态，方便表驱动测试逐条构造。 */
internal data class AutomationGateContext(
    val rule: AutomationRule,
    /** 节点指纹：[AutomationRuleExecutor.fingerprintOf] 的产物。 */
    val fingerprint: String,
    /** 事件所属包名（前台应用），不是规则里写的包名。 */
    val packageName: String,
    /** 服务侧当前的白名单（`AccessibilityAutomationStore.allowedPackages`）。空集合 = 一律拒绝。 */
    val allowedPackages: Set<String>,
    /** 身份窗口：包名/Activity 维度，只用它决定指纹何时失效。 */
    val identityWindowKey: String,
    /** 配额窗口：`resetOn="screen"` 时随每个事件推进，决定次数何时清零。 */
    val quotaWindowKey: String,
    val nowMs: Long,
    val deviceLocked: Boolean = false,
    val sensitiveWindow: Boolean = false,
)

/** 闸门判定：放行，或者拒绝并带上受控原因码与给用户看的中文原因。 */
internal sealed interface AutomationGateDecision {
    data object Allowed : AutomationGateDecision

    data class Rejected(val code: String, val reason: String) : AutomationGateDecision

    val allowed: Boolean get() = this is Allowed
}

/**
 * 四层节流 + 策略闸门。所有判定都是「读 [AutomationGateState] + 注入的毫秒时间 → 决策」。
 *
 * 只有 [check] 会推进窗口、"配额重开"与"本规则最近一次尝试时间"这类**结算性**状态；配额与指纹的提交全部在 [recordSuccess]，
 * 时间基准在 [recordAttempt]，失败计数在 [recordFailure]——执行器按「先记录尝试、再执行、最后按结果提交」
 * 的顺序调用，语义与「动作成功之后才提交状态」一致。
 */
internal object AutomationRuleGate {
    /**
     * 第 1 层：相邻两次**事件评估**的最小间隔（毫秒）。
     *
     * 与 `accessibility_service_config.xml` 的 `notificationTimeout` 同值同源（服务侧用
     * `notificationTimeout = EVENT_THROTTLE_MS` 设置）：系统侧那道是按包生效的提示性节流，
     * 跨包/跨事件类型仍可能密集到达，所以这里再兜一次，并且**丢弃而不是排队**。
     */
    const val EVENT_THROTTLE_MS = 100L

    /** 第 2 层：所有自动化动作共用的绝对最小间隔（毫秒），成功与失败都算一次尝试。 */
    const val ACTION_INTERVAL_MS = 350L

    /** 连续失败达到该次数就自动停用规则（拍板口径：连续失败 3 次自动停用并回报）。 */
    const val FAILURE_LIMIT = 3

    /** 每条规则最多记住多少个已执行节点指纹。 */
    const val MAX_FINGERPRINTS_PER_RULE = 32

    /**
     * 第 1 层：事件准入。**丢弃**被节流的事件（不排队、不补做），并刷新动作预算的起点。
     *
     * 时钟回退（`nowMs < lastEventAt`）时按放行处理并重置基准：把回退当成"间隔不足"会让服务在
     * 用户改过系统时间之后再也不执行任何规则，而这个失败没有任何可观察的原因。
     */
    fun admitEvent(state: AutomationGateState, nowMs: Long): AutomationGateDecision {
        val previous = state.lastEventAt
        if (previous != null && nowMs >= previous && nowMs - previous < EVENT_THROTTLE_MS) {
            return AutomationGateDecision.Rejected(
                AutomationGateCodes.EVENT_THROTTLED,
                "距上次事件评估仅 ${nowMs - previous} 毫秒（下限 $EVENT_THROTTLE_MS 毫秒），本次事件已丢弃",
            )
        }
        state.markEventAdmitted(nowMs)
        return AutomationGateDecision.Allowed
    }

    fun check(state: AutomationGateState, context: AutomationGateContext): AutomationGateDecision {
        val rule = context.rule
        val nowMs = context.nowMs

        // ---- 第 0 层：策略 ----
        if (state.isAutoDisabled(rule.id)) {
            return reject(
                AutomationGateCodes.RULE_AUTO_DISABLED,
                "规则「${rule.id}」连续失败 $FAILURE_LIMIT 次后已被自动停用，需人工重新启用",
            )
        }
        if (!rule.enabled) {
            return reject(AutomationGateCodes.RULE_DISABLED, "规则「${rule.id}」处于停用状态")
        }
        if (!AccessibilityAutomationPolicy.validPackage(context.packageName)) {
            return reject(
                AutomationGateCodes.PACKAGE_NOT_ALLOWED,
                "事件包名不是可自动化的应用包名（${context.packageName}），已跳过",
            )
        }
        if (context.packageName !in context.allowedPackages) {
            return reject(
                AutomationGateCodes.PACKAGE_NOT_ALLOWED,
                "应用 ${context.packageName} 不在无障碍自动化白名单内",
            )
        }
        if (state.isSelfPackageBlocked() && context.packageName == AccessibilityAutomationPolicy.SELF_PACKAGE) {
            return reject(
                AutomationGateCodes.SELF_PACKAGE_BLOCKED,
                "拒绝对本应用自身（${AccessibilityAutomationPolicy.SELF_PACKAGE}）执行动作，避免自触发循环",
            )
        }
        if (context.deviceLocked) {
            return reject(AutomationGateCodes.DEVICE_LOCKED, "设备已锁定或屏幕未交互")
        }
        if (context.sensitiveWindow) {
            return reject(AutomationGateCodes.SENSITIVE_WINDOW, "检测到密码、验证码、支付或权限窗口")
        }
        if (rule.action.type == AutomationRuleMatcher.ACTION_LAUNCH) {
            // launch 的目标应用同样受白名单约束：白名单之外的应用，连"打开"都不做。
            val target = rule.action.component?.substringBefore('/')?.trim().orEmpty()
            if (target.isNotEmpty() && (!AccessibilityAutomationPolicy.validPackage(target) || target !in context.allowedPackages)) {
                return reject(AutomationGateCodes.PACKAGE_NOT_ALLOWED, "launch 目标应用 $target 不在无障碍自动化白名单内")
            }
        }

        // ---- 结算：窗口推进 + 配额重开 ----
        state.syncQuotaWindow(rule.id, context.quotaWindowKey)
        state.syncIdentityWindow(rule.id, context.identityWindowKey)
        val lastAttempt = state.lastRuleAttemptAt(rule.id)
        val elapsed = lastAttempt?.let { nowMs - it }
        val rolledBack = elapsed != null && elapsed < 0
        val cooldown = rule.actionCoolDownMs
        when {
            rolledBack -> state.reopenQuota(rule.id)
            elapsed != null && cooldown > 0 && elapsed >= cooldown -> state.reopenQuota(rule.id)
        }

        // ---- 第 2 层：绝对间隔（所有规则共用；先于规则冷却判定，因此叠加时上报它） ----
        val lastAction = state.lastActionAt
        if (lastAction != null && nowMs >= lastAction && nowMs - lastAction < ACTION_INTERVAL_MS) {
            return reject(
                AutomationGateCodes.ACTION_INTERVAL_THROTTLED,
                "距上一次动作仅 ${nowMs - lastAction} 毫秒（下限 $ACTION_INTERVAL_MS 毫秒），本次跳过",
            )
        }

        // ---- 第 3 层：规则自身冷却 ----
        if (elapsed != null && !rolledBack && cooldown > 0 && elapsed < cooldown) {
            return reject(
                AutomationGateCodes.COOLDOWN_ACTIVE,
                "规则「${rule.id}」冷却中：距上次动作 $elapsed 毫秒，还剩 ${cooldown - elapsed} 毫秒",
            )
        }

        // ---- 第 4 层：actionMaximum（唯一闸门；冷却只决定配额何时重开） ----
        val used = state.used(rule.id)
        if (used >= rule.maxActions) {
            val reopen = if (cooldown > 0) "冷却到点后重开" else "冷却为 0 毫秒，只在界面变化时重开"
            return reject(
                AutomationGateCodes.ACTION_MAX_REACHED,
                "本界面已执行 $used 次（上限 ${rule.maxActions} 次），$reopen",
            )
        }

        // ---- 指纹去重 ----
        if (state.hasFingerprint(rule.id, context.fingerprint)) {
            return reject(
                AutomationGateCodes.DUPLICATE_FINGERPRINT,
                "同一节点指纹在本轮配额内已执行过（防自触发循环），本次跳过",
            )
        }
        // 判定放行 = 动作即将执行：在这里登记本规则的尝试时间，冷却与配额重开都从这一刻起算。
        // 缺了这一步，lastRuleAttemptAt 永远是 null，第 3 层冷却与「冷却到点重开配额」就整层失效。
        state.markRuleAttempt(rule.id, nowMs)
        return AutomationGateDecision.Allowed
    }

    /** 动作**尝试**记账（无论成败）：第 2 层的间隔基准。 */
    fun recordAttempt(state: AutomationGateState, nowMs: Long) {
        state.markAttempt(nowMs)
    }

    /** 动作**成功**之后提交：本轮配额 +1、清空连续失败、记住指纹。返回提交后的配额计数。 */
    fun recordSuccess(state: AutomationGateState, ruleId: String, fingerprint: String): Int =
        state.countSuccess(ruleId, fingerprint)

    /** 动作失败之后提交：连续失败 +1；达到 [FAILURE_LIMIT] 时把规则标成自动停用。返回新的连续失败次数。 */
    fun recordFailure(state: AutomationGateState, ruleId: String): Int = state.countFailure(ruleId)

    private fun reject(code: String, reason: String): AutomationGateDecision =
        AutomationGateDecision.Rejected(code, reason)
}

/** 后端自述的结果。 */
internal sealed interface BackendOutcome {
    data object Done : BackendOutcome

    /** 后端或系统拒绝，带受控原因码与中文原因。 */
    data class Refused(val code: String, val reason: String) : BackendOutcome
}

/**
 * 执行时读到的节点快照。字段与 [AutomationNode] 对齐，但**不含** children：这只是"还是不是同一个节点"的证据。
 *
 * `text` 必须是**原始文本**（不要在服务侧截断）：身份归一化（trim + 截断）只在
 * [AutomationRuleExecutor.nodeIdentity] 里做一次，两端截断长度不同会把同一个节点算成两个身份。
 */
internal data class AutomationNodeSnapshot(
    val className: String? = null,
    val text: String? = null,
    val viewId: String? = null,
    val clickable: Boolean = false,
    val bounds: AutomationBounds = AutomationBounds.EMPTY,
)

/** 解析成功的节点句柄：`node` 是后端自己的类型（Android 侧是 `AccessibilityNodeInfo`），执行器只负责原样传回。 */
internal data class AutomationNodeHandle(val node: Any, val snapshot: AutomationNodeSnapshot)

/**
 * 真实节点与手势的**唯一出口**，只有它的实现需要 Android 类型（服务侧的内部类）。
 *
 * 约定：所有方法都在**主线程**上调用（无障碍节点读取与 `dispatchGesture` 都是主线程 API），
 * 服务侧实现负责把工作切到主线程并同步把结果带回来。执行器在事件回调里同步等结果，
 * 因此「事件量大时按节流丢弃、不排队堆积」自然成立——这条链路上没有队列，只有一次同步尝试。
 *
 * [resolveNode] 返回 null 表示「判定时看到的那个位置已经没有节点了」；
 * 返回的 [AutomationNodeSnapshot] 与判定时的节点身份不一致时，执行器按 `AUTOMATION_NODE_STALE` 拒绝动作。
 */
internal interface AutomationNodeBackend {
    /** 按判定时的下标路径 + bounds 重新取节点。**不读** children（只需要身份与可点击性）。 */
    fun resolveNode(path: List<Int>, bounds: AutomationBounds): AutomationNodeHandle?

    fun clickNode(node: Any): BackendOutcome

    /** 从该节点向上找最近的可点击祖先并点击它（`clickCenter` 在无法派发手势时的第一近似）。 */
    fun clickClickableAncestor(node: Any): BackendOutcome

    /** 在该矩形中心派发一次点击手势（`dispatchGesture`）。 */
    fun clickCenter(bounds: AutomationBounds): BackendOutcome

    fun longClickNode(node: Any): BackendOutcome

    fun pressBack(): BackendOutcome

    fun swipe(direction: String, durationMs: Int): BackendOutcome

    fun sendKey(keyCode: Int): BackendOutcome

    fun launch(component: String?, uri: String?): BackendOutcome
}

/** 单条规则的一次执行结果。 */
internal data class AutomationActionResult(
    val ruleId: String,
    /** 实际执行的动作类型（命中节点不可点击降级后是 `clickCenter`）。 */
    val action: String,
    val code: String,
    val ok: Boolean,
    /** 是否真的向后端发出了动作（试运行、节点消失、后端缺失都是 false——这类结果不落审计）。 */
    val performed: Boolean,
    /** 走了降级分支（不可点击 → 祖先点击 / 手势，或匹配器已经降级过）。 */
    val degraded: Boolean,
    val reason: String,
    val fingerprint: String,
    val nodePathText: String,
    val bounds: AutomationBounds,
    /** 成功时提交后的配额计数。 */
    val quotaUsed: Int? = null,
    /** 失败时累计的连续失败次数。 */
    val failureStreak: Int? = null,
)

/** 一次事件里全部规则的执行汇总。 */
internal data class AutomationExecutionReport(
    val results: List<AutomationActionResult> = emptyList(),
    val skipped: List<AutomationSkip> = emptyList(),
    val autoDisabledRuleIds: List<String> = emptyList(),
    /** 给用户看的一次性告警（连续失败自动停用等），服务侧负责弹提示与写审计。 */
    val notices: List<String> = emptyList(),
    /** 需要服务侧延后重新判定的最早毫秒数（`wait` 动作、或规则的 `matchDelayMs`）。 */
    val nextEvaluationDelayMs: Int? = null,
) {
    val performed: List<AutomationActionResult> get() = results.filter { it.performed }

    val succeeded: List<AutomationActionResult> get() = results.filter { it.ok }

    val hasFailure: Boolean get() = results.any { it.performed && !it.ok }

    companion object {
        val EMPTY = AutomationExecutionReport()
    }
}

/**
 * 执行器：一次事件 = 一次同步评估（匹配 → 闸门 → 执行 → 提交状态），没有队列也没有重试。
 *
 * `apply = false` 是试运行：只走到「判定 + 落点 + 闸门」，不执行动作、不消耗配额、不提交指纹、不计失败，
 * 因此它可以安全地在用户眼前反复跑。
 */
internal object AutomationRuleExecutor {
    /** `launch` 之外的节点身份文本参与比较时的最长字符数（两端一致，见 [nodeIdentity]）。 */
    const val MAX_IDENTITY_TEXT_CHARS = 160

    /** 单条指纹的最长字符数（落盘/日志里不该出现超长串）。 */
    const val MAX_FINGERPRINT_CHARS = 200

    /** `swipe` 动作在模型里必须带 `durationMs`；这里是兜底默认值，只在不合法输入下才会用到。 */
    const val DEFAULT_SWIPE_DURATION_MS = 300

    /** 身份窗口：包名 + Activity（Activity 拿不到时用 `?`，此时只有包名变化才会重置）。 */
    fun baseWindowKey(screen: AutomationScreenInfo): String =
        "${screen.packageName}/${screen.activityName ?: "?"}"

    /**
     * 配额窗口：`resetOn = "activity"` 时与身份窗口同值，`"screen"` 时带上事件序号
     * （每个事件都是新窗口 → 次数清零，但指纹不会被清，见 [AutomationGateState.syncIdentityWindow]）。
     */
    fun quotaWindowKey(rule: AutomationRule, screen: AutomationScreenInfo, eventSequence: Long): String {
        val base = baseWindowKey(screen)
        return if (rule.resetOn == AutomationRule.RESET_SCREEN) "$base#$eventSequence" else base
    }

    /**
     * 节点身份：回答「判定时看到的节点与执行时读到的节点是不是同一个」。
     *
     * 刻意**不含** `clickable`：界面从"按钮还没激活"变成可点击是常见情形（异步加载完成后按钮才可用），
     * 把它算进身份会让这种最值得执行的动作被判成 `NODE_STALE` 而拒绝。
     */
    fun nodeIdentity(
        pathText: String,
        className: String?,
        text: String?,
        viewId: String?,
        bounds: AutomationBounds,
    ): String = buildString {
        append(pathText)
        append(SEPARATOR)
        append(normalize(className))
        append(SEPARATOR)
        append(normalize(text))
        append(SEPARATOR)
        append(normalize(viewId))
        append(SEPARATOR)
        append(bounds.toString())
    }

    /** 去重指纹 = 窗口 + 规则 + 节点身份：同一条规则在同一界面上对同一个节点只执行一次。 */
    fun fingerprintOf(identityWindowKey: String, ruleId: String, identity: String): String =
        "$identityWindowKey$SEPARATOR$ruleId$SEPARATOR$identity".take(MAX_FINGERPRINT_CHARS)

    fun execute(
        backend: AutomationNodeBackend?,
        root: AutomationNode?,
        screen: AutomationScreenInfo,
        rules: List<AutomationRule>,
        gate: AutomationGateState,
        nowMs: Long,
        allowedPackages: Set<String>,
        deviceLocked: Boolean = false,
        sensitiveWindow: Boolean = false,
        apply: Boolean = true,
        eventSequence: Long = 0L,
    ): AutomationExecutionReport {
        if (rules.isEmpty()) return AutomationExecutionReport.EMPTY
        // 一次性的空抑制状态：匹配器只做匹配，节流判定全在闸门里（见文件头第 1 条取舍）。
        val matched = AutomationRuleMatcher.match(root, screen, rules, AutomationMatchState(), nowMs)
        val rulesById = rules.associateBy { it.id }
        val refsByPath = root?.flatten().orEmpty().associateBy { it.path }
        val identityWindow = baseWindowKey(screen)
        val results = ArrayList<AutomationActionResult>()
        val skipped = ArrayList(matched.skipped)
        val notices = ArrayList<String>()
        val autoDisabledIds = ArrayList<String>()
        var nextDelayMs: Int? = null

        // matchDelayMs > 0 的规则不会产出决策（匹配器报 MATCH_DELAY_PENDING），这里把它翻译成
        // 「稍后重新判定一次」交给服务侧排期——延迟后重新匹配是服务侧的责任，不是匹配器的。
        for (skip in matched.skipped) {
            if (skip.code != AutomationSkipCodes.MATCH_DELAY_PENDING) continue
            val rule = rulesById[skip.ruleId] ?: continue
            if (rule.matchDelayMs > 0) nextDelayMs = earliest(nextDelayMs, rule.matchDelayMs)
        }

        for (decision in matched.decisions) {
            val rule = rulesById[decision.ruleId] ?: continue
            val ref = refsByPath[decision.nodePath]
            val bounds = decision.resolvedBounds
            val identity = nodeIdentity(
                pathText = decision.nodePathText,
                className = ref?.node?.className,
                text = ref?.node?.text,
                viewId = ref?.node?.viewId,
                // 身份用命中节点**自己的矩形**，不是降级后的中心点矩形（[AutomationDecision.resolvedBounds] 在
                // 降级时退化成中心点）：执行时后端读回来的是节点矩形，两边必须同一个基准，否则每次
                // 降级点击都会被判成 NODE_STALE，降级链等于永远走不通。
                bounds = decision.bounds,
            )
            val fingerprint = fingerprintOf(identityWindow, rule.id, identity)
            val context = AutomationGateContext(
                rule = rule,
                fingerprint = fingerprint,
                packageName = screen.packageName,
                allowedPackages = allowedPackages,
                identityWindowKey = identityWindow,
                quotaWindowKey = quotaWindowKey(rule, screen, eventSequence),
                nowMs = nowMs,
                deviceLocked = deviceLocked,
                sensitiveWindow = sensitiveWindow,
            )
            when (val verdict = AutomationRuleGate.check(gate, context)) {
                is AutomationGateDecision.Rejected -> skipped += AutomationSkip(rule.id, verdict.code, verdict.reason)
                AutomationGateDecision.Allowed -> {
                    if (apply) AutomationRuleGate.recordAttempt(gate, nowMs)
                    val outcome = attempt(backend, decision, rule, identity, apply)
                    if (outcome.delayMs != null) nextDelayMs = earliest(nextDelayMs, outcome.delayMs)
                    var quotaUsed: Int? = null
                    var streak: Int? = null
                    if (apply) {
                        if (outcome.ok) {
                            quotaUsed = AutomationRuleGate.recordSuccess(gate, rule.id, fingerprint)
                        } else if (outcome.performed) {
                            // 只有「动作真的发给了后端、并被平台拒绝」的失败才计入连续失败。节点在判定与执行之间
                            // 被替换/消失、服务没接后端、按键不可注入这类结果根本没发出动作，属于瞬时或环境问题：
                            // 把它们算进失败，会在界面刷新频繁时误停用一条本来能用的规则。
                            streak = AutomationRuleGate.recordFailure(gate, rule.id)
                            if (streak >= AutomationRuleGate.FAILURE_LIMIT) {
                                autoDisabledIds += rule.id
                                notices += "规则「${rule.id}」（应用 ${screen.packageName}）连续失败 $streak 次，" +
                                    "已自动停用；修好规则并在自动化设置里重新启用之前不会再执行"
                            }
                        }
                    }
                    results += AutomationActionResult(
                        ruleId = rule.id,
                        action = decision.kind,
                        code = outcome.code,
                        ok = outcome.ok,
                        performed = outcome.performed,
                        degraded = decision.degradedToCenter || outcome.degraded,
                        reason = outcome.reason,
                        fingerprint = fingerprint,
                        nodePathText = decision.nodePathText,
                        bounds = bounds,
                        quotaUsed = quotaUsed,
                        failureStreak = streak,
                    )
                }
            }
        }
        return AutomationExecutionReport(
            results = results,
            skipped = skipped,
            autoDisabledRuleIds = autoDisabledIds,
            notices = notices,
            nextEvaluationDelayMs = nextDelayMs,
        )
    }

    private class AttemptOutcome(
        val performed: Boolean,
        val ok: Boolean,
        val code: String,
        val reason: String,
        val degraded: Boolean = false,
        val delayMs: Int? = null,
    )

    private fun attempt(
        backend: AutomationNodeBackend?,
        decision: AutomationDecision,
        rule: AutomationRule,
        identity: String,
        apply: Boolean,
    ): AttemptOutcome {
        val degraded = decision.degradedToCenter
        if (!apply) {
            return AttemptOutcome(
                performed = false,
                ok = false,
                code = AutomationGateCodes.DRY_RUN,
                reason = "试运行：只判定落点与动作，不执行（不消耗配额、不提交指纹、不计失败）",
                degraded = degraded,
                delayMs = if (decision.kind == AutomationRuleMatcher.ACTION_WAIT) rule.action.delayMs else null,
            )
        }
        if (backend == null) {
            return AttemptOutcome(
                performed = false,
                ok = false,
                code = AutomationGateCodes.BACKEND_UNAVAILABLE,
                reason = "服务没有提供节点执行后端，规则动作未执行",
                degraded = degraded,
            )
        }
        val handle = backend.resolveNode(decision.nodePath, decision.bounds)
            ?: return AttemptOutcome(
                performed = false,
                ok = false,
                code = AutomationGateCodes.NODE_UNRESOLVED,
                reason = "命中节点在当前窗口里已不存在（路径 ${decision.nodePathText}），本次不动作",
                degraded = degraded,
            )
        if (nodeDependent(decision.kind)) {
            val fresh = nodeIdentity(
                pathText = decision.nodePathText,
                className = handle.snapshot.className,
                text = handle.snapshot.text,
                viewId = handle.snapshot.viewId,
                bounds = handle.snapshot.bounds,
            )
            if (fresh != identity) {
                return AttemptOutcome(
                    performed = false,
                    ok = false,
                    code = AutomationGateCodes.NODE_STALE,
                    reason = "命中节点在判定与执行之间已被替换（路径 ${decision.nodePathText}），本次不动作",
                    degraded = degraded,
                )
            }
        }
        return when (decision.kind) {
            AutomationRuleMatcher.ACTION_CLICK -> clickNodePath(backend, handle, null)
            AutomationRuleMatcher.ACTION_CLICK_CENTER ->
                clickCenterPath(backend, handle, if (degraded) "匹配器已按「不可点击」降级为点击中心点" else null)
            AutomationRuleMatcher.ACTION_LONG_CLICK -> longClickPath(backend, handle)
            AutomationRuleMatcher.ACTION_BACK -> backPath(backend)
            AutomationRuleMatcher.ACTION_SWIPE -> swipePath(backend, rule)
            AutomationRuleMatcher.ACTION_KEY -> keyPath(backend, rule)
            AutomationRuleMatcher.ACTION_WAIT -> waitPath(rule)
            AutomationRuleMatcher.ACTION_LAUNCH -> launchPath(backend, rule)
            else -> AttemptOutcome(
                performed = false,
                ok = false,
                code = AutomationGateCodes.ACTION_UNSUPPORTED,
                reason = "动作类型 ${decision.kind} 不受执行器支持",
                degraded = degraded,
            )
        }
    }

    /** `click`：命中节点应当可点击；执行时发现不可点击（或后端拒绝）就退到中心点链路并如实记录。 */
    private fun clickNodePath(backend: AutomationNodeBackend, handle: AutomationNodeHandle, firstReason: String?): AttemptOutcome {
        if (!handle.snapshot.clickable) return clickCenterPath(backend, handle, "执行时读到该节点 clickable=false")
        val outcome = backend.clickNode(handle.node)
        if (outcome is BackendOutcome.Done) {
            return performed(
                AutomationGateCodes.ACTION_PERFORMED,
                "已对命中节点执行 ACTION_CLICK（${handle.snapshot.bounds}）",
            )
        }
        val refused = outcome as BackendOutcome.Refused
        return clickCenterPath(backend, handle, "节点拒绝了 ACTION_CLICK：${refused.reason}")
    }

    /**
     * `clickCenter` 的降级链：节点自身可点击 → 最近的可点击祖先（近似，不需要手势能力）→ `dispatchGesture`。
     *
     * 三级的取舍：触摸落在某个坐标上时，真正接收点击的通常是该点上最外层的可点击容器，所以
     * 「祖先 ACTION_CLICK」是对"点中心"最接近的无障碍等价物；只有它不行时才去要手势能力
     * （本服务当前未声明 `android:canPerformGestures`，那一级在真机上大概率会如实失败）。
     * 三级都失败时把每级的原因拼在一起返回——用户要能一眼看出该改选择器还是该开手势能力。
     */
    private fun clickCenterPath(backend: AutomationNodeBackend, handle: AutomationNodeHandle, firstReason: String?): AttemptOutcome {
        val reasons = ArrayList<String>()
        firstReason?.let(reasons::add)
        val center = "${handle.snapshot.bounds.centerX},${handle.snapshot.bounds.centerY}"
        if (handle.snapshot.clickable) {
            val outcome = backend.clickNode(handle.node)
            if (outcome is BackendOutcome.Done) {
                return performed(
                    AutomationGateCodes.ACTION_PERFORMED,
                    "命中节点可点击，已用 ACTION_CLICK 完成点击中心（中心点 $center）",
                )
            }
            reasons += "节点 ACTION_CLICK 被拒绝：${(outcome as BackendOutcome.Refused).reason}"
        }
        val ancestor = backend.clickClickableAncestor(handle.node)
        if (ancestor is BackendOutcome.Done) {
            return performed(
                AutomationGateCodes.CLICK_ANCESTOR_FALLBACK,
                "命中节点不可点击，已退化为点击最近的可点击祖先节点（对「点中心 $center」的近似，未走手势通道）",
                degraded = true,
            )
        }
        reasons += "祖先节点点击被拒绝：${(ancestor as BackendOutcome.Refused).reason}"
        val gesture = backend.clickCenter(handle.snapshot.bounds)
        if (gesture is BackendOutcome.Done) {
            return performed(
                AutomationGateCodes.GESTURE_PERFORMED,
                "已通过 dispatchGesture 在中心点 $center 派发点击",
                degraded = true,
            )
        }
        reasons += "手势点击被拒绝：${(gesture as BackendOutcome.Refused).reason}"
        return failed(AutomationGateCodes.CLICK_FAILED, "点击中心失败：${reasons.joinToString("；")}", degraded = true)
    }

    private fun longClickPath(backend: AutomationNodeBackend, handle: AutomationNodeHandle): AttemptOutcome {
        val outcome = backend.longClickNode(handle.node)
        return if (outcome is BackendOutcome.Done) {
            // 长按没有 `ACTION_LONG_CLICK` 这类 performAction 常量，只能派发手势，因此这一级天然依赖手势能力。
            performed(AutomationGateCodes.LONG_CLICK_PERFORMED, "已对命中节点派发长按（${handle.snapshot.bounds}）")
        } else {
            failed(AutomationGateCodes.LONG_CLICK_FAILED, "长按失败：${(outcome as BackendOutcome.Refused).reason}")
        }
    }

    private fun backPath(backend: AutomationNodeBackend): AttemptOutcome {
        val outcome = backend.pressBack()
        return if (outcome is BackendOutcome.Done) {
            performed(AutomationGateCodes.BACK_PERFORMED, "已执行 GLOBAL_ACTION_BACK")
        } else {
            failed(AutomationGateCodes.BACK_FAILED, "回退失败：${(outcome as BackendOutcome.Refused).reason}")
        }
    }

    private fun swipePath(backend: AutomationNodeBackend, rule: AutomationRule): AttemptOutcome {
        val direction = rule.action.direction
        if (direction.isNullOrEmpty()) {
            return failed(AutomationGateCodes.ACTION_UNSUPPORTED, "swipe 动作缺少 direction", attempted = false)
        }
        val duration = rule.action.durationMs ?: DEFAULT_SWIPE_DURATION_MS
        val outcome = backend.swipe(direction, duration)
        return if (outcome is BackendOutcome.Done) {
            performed(AutomationGateCodes.SWIPE_PERFORMED, "已派发 $direction 方向滑动（手指移动方向，${duration} 毫秒）")
        } else {
            failed(AutomationGateCodes.SWIPE_FAILED, "滑动失败：${(outcome as BackendOutcome.Refused).reason}")
        }
    }

    private fun keyPath(backend: AutomationNodeBackend, rule: AutomationRule): AttemptOutcome {
        val keyCode = rule.action.keyCode
        if (keyCode == null) {
            return failed(AutomationGateCodes.ACTION_UNSUPPORTED, "key 动作缺少 keyCode", attempted = false)
        }
        if (keyCode !in AutomationKeyCodes.ALLOWED) {
            return failed(AutomationGateCodes.ACTION_UNSUPPORTED, "按键码 $keyCode 不在允许列表内", attempted = false)
        }
        val name = AutomationKeyRouting.keyName(keyCode)
        // 无障碍注入按键只能走 performGlobalAction，因此在**发出动作之前**就把不可能的按键拦下来，
        // 而不是让后端去尝试一次注定失败的调用（失败原因仍然是受控码，用户看得出该改规则）。
        if (AutomationKeyRouting.globalActionName(keyCode) == null) {
            return failed(
                AutomationGateCodes.ACTION_UNSUPPORTED,
                "按键 $name 无法通过无障碍注入：可达的只有返回键与方向键（其余按键需要系统签名权限）",
                attempted = false,
            )
        }
        val outcome = backend.sendKey(keyCode)
        return if (outcome is BackendOutcome.Done) {
            performed(AutomationGateCodes.KEY_PERFORMED, "已通过无障碍全局动作发送按键 $name（$keyCode）")
        } else {
            failed(AutomationGateCodes.KEY_FAILED, "按键 $name 失败：${(outcome as BackendOutcome.Refused).reason}")
        }
    }

    /** `wait`：动作本身就是「延后重新判定」，由服务侧用 [AutomationExecutionReport.nextEvaluationDelayMs] 排期。 */
    private fun waitPath(rule: AutomationRule): AttemptOutcome {
        val delay = rule.action.delayMs
            ?: return failed(AutomationGateCodes.ACTION_UNSUPPORTED, "wait 动作缺少 delayMs", attempted = false)
        return performed(
            AutomationGateCodes.WAIT_PERFORMED,
            "已请求在 $delay 毫秒后重新判定（本动作不碰界面，但仍占一次配额）",
            delayMs = delay,
        )
    }

    private fun launchPath(backend: AutomationNodeBackend, rule: AutomationRule): AttemptOutcome {
        val component = rule.action.component
        val uri = rule.action.uri
        if (component.isNullOrEmpty() && uri.isNullOrEmpty()) {
            return failed(AutomationGateCodes.ACTION_UNSUPPORTED, "launch 动作缺少 component 与 uri", attempted = false)
        }
        val outcome = backend.launch(component, uri)
        return if (outcome is BackendOutcome.Done) {
            performed(AutomationGateCodes.LAUNCH_PERFORMED, "已请求启动 ${component ?: uri}")
        } else {
            failed(AutomationGateCodes.LAUNCH_FAILED, "启动失败：${(outcome as BackendOutcome.Refused).reason}")
        }
    }

    /** 依赖"命中节点本身"的动作：这类动作在节点被替换之后绝不能继续执行。 */
    private fun nodeDependent(kind: String): Boolean = kind == AutomationRuleMatcher.ACTION_CLICK ||
        kind == AutomationRuleMatcher.ACTION_CLICK_CENTER ||
        kind == AutomationRuleMatcher.ACTION_LONG_CLICK

    private fun performed(code: String, reason: String, degraded: Boolean = false, delayMs: Int? = null): AttemptOutcome =
        AttemptOutcome(true, true, code, reason, degraded, delayMs)

    private fun failed(code: String, reason: String, degraded: Boolean = false, attempted: Boolean = true): AttemptOutcome =
        AttemptOutcome(attempted, false, code, reason, degraded)

    private fun normalize(value: String?): String = value?.trim()?.take(MAX_IDENTITY_TEXT_CHARS).orEmpty()

    private fun earliest(current: Int?, candidate: Int): Int = if (current == null || candidate < current) candidate else current

    /** 节点身份与指纹里的字段分隔符：用不可打印的单元分隔符，避免文本里带 `|` 造成指纹撞车。 */
    private const val SEPARATOR = "\u001f"
}
