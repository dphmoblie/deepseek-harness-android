package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutomationRuleGate] 的四层节流与策略闸门语义。
 *
 * 每个用例只锁一层，叠加的情形单独一个用例：四层节流要能排障，前提是"哪一层先拒绝"是确定的，
 * 如果混在一个用例里断言，以后改了判定顺序也测不出来。时间全部是注入的毫秒数，所以"差 1 毫秒"
 * 这种边界可以精确构造，不需要 `Thread.sleep`（真机上加 sleep 的用例既慢又不稳定）。
 *
 * 这里只测**纯函数闸门**：它不依赖 Android 框架，也不直接执行动作，执行语义在
 * [AutomationRuleExecutorTest] 里测。
 */
class AutomationRuleGateTest {

    // ---- 第 1 层：事件准入 ----

    @Test
    fun `第 1 层事件节流：不足 100 毫秒的事件被丢弃且不排队`() {
        val state = AutomationGateState()
        assertAllowed(AutomationRuleGate.admitEvent(state, 1_000L))
        assertEquals(
            AutomationGateCodes.EVENT_THROTTLED,
            rejectCode(AutomationRuleGate.admitEvent(state, 1_099L)),
        )
        // 被丢弃的事件**不刷新**基准：否则高频事件流会把窗口一直往后推，规则再也没有机会被判定。
        assertEquals(
            AutomationGateCodes.EVENT_THROTTLED,
            rejectCode(AutomationRuleGate.admitEvent(state, 1_050L)),
        )
        assertAllowed(AutomationRuleGate.admitEvent(state, 1_100L))
        assertEquals(1_100L, state.lastEventAt ?: -1L)
    }

    @Test
    fun `第 1 层时钟回退：按放行处理并重置基准`() {
        val state = AutomationGateState()
        assertAllowed(AutomationRuleGate.admitEvent(state, 5_000L))
        // 用户把系统时间改小之后，"间隔不足"会让服务再也不判定任何规则，而且没有任何可观察的原因。
        assertAllowed(AutomationRuleGate.admitEvent(state, 1_000L))
        assertEquals(1_000L, state.lastEventAt ?: -1L)
    }

    // ---- 第 2 层：动作绝对间隔（所有规则共用） ----

    @Test
    fun `第 2 层动作间隔：350 毫秒内拒绝，到点放行`() {
        val state = AutomationGateState()
        val rule = rule(actionCoolDownMs = 0, maxActions = 5)
        assertAllowed(check(state, rule, nowMs = 1_000L))
        AutomationRuleGate.recordAttempt(state, 1_000L)
        assertEquals(
            AutomationGateCodes.ACTION_INTERVAL_THROTTLED,
            rejectCode(check(state, rule, nowMs = 1_349L)),
        )
        assertAllowed(check(state, rule, nowMs = 1_350L))
    }

    @Test
    fun `第 2 层是所有规则共用的：另一条规则也要等够间隔`() {
        val state = AutomationGateState()
        val first = rule(id = "r-a", actionCoolDownMs = 0, maxActions = 5)
        val second = rule(id = "r-b", actionCoolDownMs = 0, maxActions = 5)
        assertAllowed(check(state, first, nowMs = 0L))
        AutomationRuleGate.recordAttempt(state, 0L)
        assertEquals(
            AutomationGateCodes.ACTION_INTERVAL_THROTTLED,
            rejectCode(check(state, second, nowMs = 100L)),
        )
    }

    // ---- 第 3 层：规则自身冷却 ----

    @Test
    fun `第 3 层规则冷却：冷却中拒绝并报剩余毫秒，到点放行`() {
        val state = AutomationGateState()
        val rule = rule(actionCoolDownMs = 5_000, maxActions = 5)
        assertAllowed(check(state, rule, nowMs = 0L))
        AutomationRuleGate.recordAttempt(state, 0L)
        AutomationRuleGate.recordSuccess(state, rule.stateKey, "fp-1")

        val cooling = check(state, rule, nowMs = 1_000L, fingerprint = "fp-2")
        assertEquals(AutomationGateCodes.COOLDOWN_ACTIVE, rejectCode(cooling))
        assertTrue("冷却原因里要写清还剩多久：${rejectReason(cooling)}", rejectReason(cooling).contains("还剩 4000 毫秒"))
        assertAllowed(check(state, rule, nowMs = 5_000L, fingerprint = "fp-3"))
    }

    @Test
    fun `四层叠加时上报更严的第 2 层，而不是规则冷却`() {
        val state = AutomationGateState()
        val rule = rule(actionCoolDownMs = 10_000, maxActions = 5)
        assertAllowed(check(state, rule, nowMs = 0L))
        AutomationRuleGate.recordAttempt(state, 0L)
        AutomationRuleGate.recordSuccess(state, rule.stateKey, "fp-1")

        // 100 毫秒时"绝对间隔"与"规则冷却"同时成立。先判的绝对间隔是更严的那一层（还要等 250 毫秒），
        // 上报它用户拿到的是可操作的等待时间，而不是"还要等 9900 毫秒"。
        assertEquals(
            AutomationGateCodes.ACTION_INTERVAL_THROTTLED,
            rejectCode(check(state, rule, nowMs = 100L, fingerprint = "fp-2")),
        )
        assertEquals(
            AutomationGateCodes.COOLDOWN_ACTIVE,
            rejectCode(check(state, rule, nowMs = 400L, fingerprint = "fp-2")),
        )
    }

    // ---- 第 4 层：actionMaximum（唯一闸门） ----

    @Test
    fun `第 4 层次数上限：用满即拒，冷却到点后配额重开`() {
        val state = AutomationGateState()
        val rule = rule(actionCoolDownMs = 1_000, maxActions = 1)
        assertAllowed(check(state, rule, nowMs = 0L, fingerprint = "fp-1"))
        AutomationRuleGate.recordAttempt(state, 0L)
        assertEquals(1, AutomationRuleGate.recordSuccess(state, rule.stateKey, "fp-1"))
        assertEquals(1, state.used(rule.stateKey))

        // 冷却还没到点：拒绝的是冷却层。
        assertEquals(
            AutomationGateCodes.COOLDOWN_ACTIVE,
            rejectCode(check(state, rule, nowMs = 900L, fingerprint = "fp-2")),
        )
        // 冷却到点：配额重开（次数清零），换一个节点就能再执行一次。
        assertAllowed(check(state, rule, nowMs = 1_000L, fingerprint = "fp-2"))
        assertEquals(0, state.used(rule.stateKey))
    }

    @Test
    fun `冷却为 0 的规则配额不会自动重开，只能等界面变化`() {
        val state = AutomationGateState()
        val rule = rule(actionCoolDownMs = 0, maxActions = 1)
        assertAllowed(check(state, rule, nowMs = 0L, fingerprint = "fp-1"))
        AutomationRuleGate.recordAttempt(state, 0L)
        AutomationRuleGate.recordSuccess(state, rule.stateKey, "fp-1")

        val exhausted = check(state, rule, nowMs = 60_000L, fingerprint = "fp-2")
        assertEquals(AutomationGateCodes.ACTION_MAX_REACHED, rejectCode(exhausted))
        assertTrue(
            "冷却为 0 时要说明配额不会自动重开：${rejectReason(exhausted)}",
            rejectReason(exhausted).contains("只在界面变化时重开"),
        )

        // 界面变化（新的配额窗口）：次数清零，于是可以再执行一次。
        assertAllowed(check(state, rule, nowMs = 60_000L, fingerprint = "fp-2", quotaWindowKey = "q-2"))
        // 但指纹不会被窗口变化清掉：同一个节点在同一界面上依旧只执行一次（防自触发循环）。
        assertEquals(
            AutomationGateCodes.DUPLICATE_FINGERPRINT,
            rejectCode(check(state, rule, nowMs = 61_000L, fingerprint = "fp-1", quotaWindowKey = "q-3")),
        )
    }

    // ---- 指纹去重 ----

    @Test
    fun `指纹去重：同一节点同一轮配额内不重复执行，换节点才放行`() {
        val state = AutomationGateState()
        val rule = rule(actionCoolDownMs = 0, maxActions = 9)
        assertAllowed(check(state, rule, nowMs = 0L, fingerprint = "fp-a"))
        AutomationRuleGate.recordAttempt(state, 0L)
        AutomationRuleGate.recordSuccess(state, rule.stateKey, "fp-a")

        assertEquals(
            AutomationGateCodes.DUPLICATE_FINGERPRINT,
            rejectCode(check(state, rule, nowMs = 400L, fingerprint = "fp-a")),
        )
        // 命中另一个节点（另一个指纹）时，同一轮配额内仍然可以执行。
        assertAllowed(check(state, rule, nowMs = 400L, fingerprint = "fp-b"))
    }

    @Test
    fun `指纹表按规则设定上限，不会无界增长`() {
        val state = AutomationGateState()
        val rule = rule(actionCoolDownMs = 0, maxActions = 99)
        repeat(AutomationRuleGate.MAX_FINGERPRINTS_PER_RULE + 5) { index ->
            AutomationRuleGate.recordSuccess(state, rule.stateKey, "fp-$index")
        }
        assertEquals(AutomationRuleGate.MAX_FINGERPRINTS_PER_RULE, state.fingerprintCount(rule.stateKey))
    }

    // ---- 策略层 ----

    @Test
    fun `白名单外一律不执行，空白名单也不执行`() {
        val state = AutomationGateState()
        val rule = rule()
        assertEquals(
            AutomationGateCodes.PACKAGE_NOT_ALLOWED,
            rejectCode(check(state, rule, nowMs = 0L, packageName = "com.other.app")),
        )
        assertEquals(
            AutomationGateCodes.PACKAGE_NOT_ALLOWED,
            rejectCode(check(state, rule, nowMs = 0L, allowedPackages = emptySet())),
        )
    }

    @Test
    fun `事件包名不是可自动化的应用包名时不执行`() {
        val state = AutomationGateState()
        // 状态栏、输入法这类系统包名根本不该进入规则判定。
        assertEquals(
            AutomationGateCodes.PACKAGE_NOT_ALLOWED,
            rejectCode(check(state, rule(), nowMs = 0L, packageName = "com.android.systemui", allowedPackages = setOf("com.android.systemui"))),
        )
        // 保留包名（系统设置、权限控制器等）就算被写进白名单也必须失效。
        assertEquals(
            AutomationGateCodes.PACKAGE_NOT_ALLOWED,
            rejectCode(check(state, rule(), nowMs = 0L, packageName = "com.android.settings", allowedPackages = setOf("com.android.settings"))),
        )
    }

    @Test
    fun `默认不对应用自身包名执行动作，显式放行后才允许`() {
        val state = AutomationGateState()
        val self = AccessibilityAutomationPolicy.SELF_PACKAGE
        val rule = rule(packageName = self)
        assertEquals(
            AutomationGateCodes.SELF_PACKAGE_BLOCKED,
            rejectCode(check(state, rule, nowMs = 0L, packageName = self, allowedPackages = setOf(self))),
        )
        state.allowSelfPackage()
        assertAllowed(check(state, rule, nowMs = 0L, packageName = self, allowedPackages = setOf(self)))
    }

    @Test
    fun `锁屏与敏感窗口在动作之前就被拒绝`() {
        val state = AutomationGateState()
        val rule = rule()
        assertEquals(
            AutomationGateCodes.DEVICE_LOCKED,
            rejectCode(check(state, rule, nowMs = 0L, deviceLocked = true)),
        )
        assertEquals(
            AutomationGateCodes.SENSITIVE_WINDOW,
            rejectCode(check(state, rule, nowMs = 0L, sensitiveWindow = true)),
        )
        // 策略层拒绝发生在结算之前：没有动作尝试，也没有占用配额。
        assertNull(state.lastActionAt)
        assertEquals(0, state.used(rule.stateKey))
    }

    @Test
    fun `停用的规则与自动停用的规则都不判定`() {
        val state = AutomationGateState()
        assertEquals(
            AutomationGateCodes.RULE_DISABLED,
            rejectCode(check(state, rule(enabled = false), nowMs = 0L)),
        )

        val auto = rule(id = "r-auto")
        repeat(AutomationRuleGate.FAILURE_LIMIT) { AutomationRuleGate.recordFailure(state, auto.stateKey) }
        assertTrue(state.isAutoDisabled(auto.stateKey))
        assertEquals(
            AutomationGateCodes.RULE_AUTO_DISABLED,
            rejectCode(check(state, auto, nowMs = 0L)),
        )
        // 人工重新启用（规则表里重新启用时会清掉自动停用标记）之后恢复判定。
        assertTrue(state.clearAutoDisabled(auto.stateKey))
        assertAllowed(check(state, auto, nowMs = 0L))
    }

    // ---- 连续失败 ----

    @Test
    fun `连续失败第 3 次把规则标成自动停用并回报`() {
        val state = AutomationGateState()
        val rule = rule(actionCoolDownMs = 0, maxActions = 9)
        var nowMs = 0L
        repeat(AutomationRuleGate.FAILURE_LIMIT) { index ->
            assertAllowed(check(state, rule, nowMs = nowMs, fingerprint = "fp-$index"))
            AutomationRuleGate.recordAttempt(state, nowMs)
            assertEquals(index + 1, AutomationRuleGate.recordFailure(state, rule.stateKey))
            nowMs += AutomationRuleGate.ACTION_INTERVAL_MS
        }
        assertTrue(state.isAutoDisabled(rule.stateKey))
        assertEquals(listOf(rule.stateKey), state.autoDisabledRuleIds())
        assertEquals(
            AutomationGateCodes.RULE_AUTO_DISABLED,
            rejectCode(check(state, rule, nowMs = nowMs, fingerprint = "fp-x")),
        )
    }

    @Test
    fun `成功一次会把连续失败清零：计数的是连续失败而不是累计失败`() {
        val state = AutomationGateState()
        val rule = rule(actionCoolDownMs = 0, maxActions = 9)
        var nowMs = 0L
        repeat(2) { index ->
            assertAllowed(check(state, rule, nowMs = nowMs, fingerprint = "fp-f$index"))
            AutomationRuleGate.recordAttempt(state, nowMs)
            AutomationRuleGate.recordFailure(state, rule.stateKey)
            nowMs += AutomationRuleGate.ACTION_INTERVAL_MS
        }
        assertEquals(2, state.failureStreak(rule.stateKey))
        assertFalse(state.isAutoDisabled(rule.stateKey))

        assertAllowed(check(state, rule, nowMs = nowMs, fingerprint = "fp-ok"))
        AutomationRuleGate.recordAttempt(state, nowMs)
        AutomationRuleGate.recordSuccess(state, rule.stateKey, "fp-ok")
        assertEquals(0, state.failureStreak(rule.stateKey))

        // 清零之后再连续失败两次，仍然不该被停用。
        nowMs += AutomationRuleGate.ACTION_INTERVAL_MS
        repeat(2) { index ->
            assertAllowed(check(state, rule, nowMs = nowMs, fingerprint = "fp-g$index"))
            AutomationRuleGate.recordAttempt(state, nowMs)
            AutomationRuleGate.recordFailure(state, rule.stateKey)
            nowMs += AutomationRuleGate.ACTION_INTERVAL_MS
        }
        assertEquals(2, state.failureStreak(rule.stateKey))
        assertFalse(state.isAutoDisabled(rule.stateKey))
    }

    // ---- launch 的目标包 ----

    @Test
    fun `launch 的目标应用同样受白名单约束`() {
        val state = AutomationGateState()
        val outside = rule(action = AutomationAction(type = "launch", component = "com.other.app/.MainActivity"))
        val rejected = check(state, outside, nowMs = 0L)
        assertEquals(AutomationGateCodes.PACKAGE_NOT_ALLOWED, rejectCode(rejected))
        assertTrue(
            "拒绝原因要指出是 launch 的目标：${rejectReason(rejected)}",
            rejectReason(rejected).contains("launch 目标应用 com.other.app"),
        )

        val allowed = rule(action = AutomationAction(type = "launch", component = "$APP/.MainActivity"))
        assertAllowed(check(state, allowed, nowMs = 0L))
    }

    @Test
    fun `配额重开同时清除已有次数与指纹`() {
        val state = AutomationGateState()
        val rule = rule()
        AutomationRuleGate.recordSuccess(state, rule.stateKey, "same-node")
        assertTrue(state.reopenQuota(rule.stateKey))
        assertEquals(0, state.used(rule.stateKey))
        assertFalse(state.hasFingerprint(rule.stateKey, "same-node"))
        assertFalse(state.reopenQuota(rule.stateKey))
    }

    @Test
    fun `同名规则的失败停用与冷却按应用隔离`() {
        val state = AutomationGateState()
        val first = rule(actionCoolDownMs = 10000)
        val other = first.copy(packageName = "com.other.app")
        assertAllowed(check(state, first, nowMs = 0))
        repeat(3) { AutomationRuleGate.recordFailure(state, first.stateKey) }
        assertAllowed(check(state, other, nowMs = 400, packageName = other.packageName, allowedPackages = setOf(other.packageName)))
        assertFalse(state.isAutoDisabled(other.stateKey))
        assertEquals(0, state.failureStreak(other.stateKey))
        assertFalse(state.clearAutoDisabled(other.stateKey))
        assertTrue(state.isAutoDisabled(first.stateKey))
    }

    @Test
    fun `实际启动目标必须在白名单且不是自身或系统包`() {
        assertTrue(AutomationLaunchPolicy.allowed(APP, setOf(APP)))
        assertFalse(AutomationLaunchPolicy.allowed("com.browser.app", setOf(APP)))
        assertFalse(AutomationLaunchPolicy.allowed("android", setOf("android")))
        val self = AccessibilityAutomationPolicy.SELF_PACKAGE
        assertTrue(AutomationLaunchPolicy.allowed(self, setOf(self)))
    }

    // ---- 辅助 ----

    private fun check(
        state: AutomationGateState,
        rule: AutomationRule,
        nowMs: Long,
        fingerprint: String = "fp-${rule.id}",
        packageName: String = APP,
        allowedPackages: Set<String> = setOf(APP),
        identityWindowKey: String = "com.example.app/com.example.app.MainActivity",
        quotaWindowKey: String = "com.example.app/com.example.app.MainActivity",
        deviceLocked: Boolean = false,
        sensitiveWindow: Boolean = false,
    ): AutomationGateDecision = AutomationRuleGate.check(
        state,
        AutomationGateContext(
            rule = rule,
            fingerprint = fingerprint,
            packageName = packageName,
            allowedPackages = allowedPackages,
            identityWindowKey = identityWindowKey,
            quotaWindowKey = quotaWindowKey,
            nowMs = nowMs,
            deviceLocked = deviceLocked,
            sensitiveWindow = sensitiveWindow,
        ),
    )

    private fun rule(
        id: String = "r-1",
        packageName: String = APP,
        enabled: Boolean = true,
        maxActions: Int = 1,
        actionCoolDownMs: Int = 0,
        action: AutomationAction = AutomationAction(type = "click"),
    ): AutomationRule = AutomationRule(
        id = id,
        packageName = packageName,
        enabled = enabled,
        maxActions = maxActions,
        actionCoolDownMs = actionCoolDownMs,
        selectors = listOf(Selector(text = "确定")),
        action = action,
    )

    private fun assertAllowed(decision: AutomationGateDecision, message: String = "") {
        assertTrue("$message 期望放行，实际被拒绝：${describe(decision)}", decision.allowed)
    }

    private fun rejectCode(decision: AutomationGateDecision): String {
        assertFalse("期望被拒绝，实际放行了", decision.allowed)
        return (decision as AutomationGateDecision.Rejected).code
    }

    private fun rejectReason(decision: AutomationGateDecision): String =
        (decision as AutomationGateDecision.Rejected).reason

    private fun describe(decision: AutomationGateDecision): String = when (decision) {
        is AutomationGateDecision.Allowed -> "放行"
        is AutomationGateDecision.Rejected -> "${decision.code}：${decision.reason}"
    }

    private companion object {
        const val APP = "com.example.app"
    }
}
