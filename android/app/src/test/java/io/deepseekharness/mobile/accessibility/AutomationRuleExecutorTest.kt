package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutomationRuleExecutor] 的端到端语义：匹配 → 闸门 → 落到节点上执行 → 按结果提交状态。
 *
 * 执行后端是这份文件里的假实现 [FakeBackend]：真后端要 `AccessibilityNodeInfo`、`dispatchGesture`
 * 和已连接的无障碍服务，在 JVM 单测里既起不来也测不了；而执行器的全部决策逻辑都只依赖
 * [AutomationNodeBackend] 这个接口，所以假后端能精确构造"后端拒绝""节点在执行前被替换""节点消失"
 * 这些真机上极难复现的分支——而那几条分支恰好是"绝不误点"的最后一道防线。
 */
class AutomationRuleExecutorTest {

    // ---- 点击 ----

    @Test
    fun `命中可点击节点：执行点击并在成功之后提交配额与指纹`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "click"))
        val backend = FakeBackend(tree)
        val state = AutomationGateState()

        val report = execute(backend, tree, rule, state, nowMs = 0L)

        val result = report.performed.single()
        assertTrue(result.ok)
        assertEquals(AutomationGateCodes.ACTION_PERFORMED, result.code)
        assertEquals(AutomationRuleMatcher.ACTION_CLICK, result.action)
        assertFalse(result.degraded)
        assertEquals(1, result.quotaUsed)
        assertEquals(listOf("click"), backend.calls)
        // 状态只在动作成功之后提交。
        assertEquals(1, state.used(rule.stateKey))
        assertEquals(0, state.failureStreak(rule.stateKey))
        assertTrue(state.hasFingerprint(rule.stateKey, result.fingerprint))
        assertEquals(0L, state.lastActionAt ?: -1L)
    }

    @Test
    fun `节点不可点击时降级：先点最近的可点击祖先，并如实标记降级`() {
        val tree = container(node(text = "确定", clickable = false))
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "click"))
        val backend = FakeBackend(tree).apply { ancestorOutcome = BackendOutcome.Done }

        val report = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L)

        val result = report.performed.single()
        assertTrue(result.ok)
        assertEquals(AutomationGateCodes.CLICK_ANCESTOR_FALLBACK, result.code)
        // 降级后的动作类型是 clickCenter（匹配器已经知道节点不可点击）。
        assertEquals(AutomationRuleMatcher.ACTION_CLICK_CENTER, result.action)
        assertTrue(result.degraded)
        assertEquals(listOf("ancestor"), backend.calls)
    }

    @Test
    fun `祖先也点不动时退到手势点击`() {
        val tree = container(node(text = "确定", clickable = false))
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "click"))
        val backend = FakeBackend(tree).apply { centerOutcome = BackendOutcome.Done }

        val report = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L)

        val result = report.performed.single()
        assertTrue(result.ok)
        assertEquals(AutomationGateCodes.GESTURE_PERFORMED, result.code)
        assertTrue(result.degraded)
        assertEquals(listOf("ancestor", "center"), backend.calls)
    }

    @Test
    fun `降级链全部失败：如实报失败并累计连续失败`() {
        val tree = container(node(text = "确定", clickable = false))
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "click"))
        val backend = FakeBackend(tree)
        val state = AutomationGateState()

        val report = execute(backend, tree, rule, state, nowMs = 0L)

        val result = report.performed.single()
        assertFalse(result.ok)
        assertEquals(AutomationGateCodes.CLICK_FAILED, result.code)
        assertEquals(listOf("ancestor", "center"), backend.calls)
        // 失败不占用配额，但计入连续失败。
        assertEquals(0, state.used(rule.stateKey))
        assertEquals(1, state.failureStreak(rule.stateKey))
        assertFalse(state.hasFingerprint(rule.stateKey, result.fingerprint))
    }

    @Test
    fun `同一节点第二轮判定被指纹拦下，不会重复点击`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "click"), maxActions = 9, actionCoolDownMs = 0)
        val backend = FakeBackend(tree)
        val state = AutomationGateState()

        assertTrue(execute(backend, tree, rule, state, nowMs = 0L).performed.single().ok)
        val second = execute(backend, tree, rule, state, nowMs = 400L)

        assertTrue("第二轮不该再产出动作", second.results.isEmpty())
        assertEquals(AutomationGateCodes.DUPLICATE_FINGERPRINT, second.skipped.single().code)
        assertEquals(listOf("click"), backend.calls)
    }

    @Test
    fun `screen 口径靠事件序号重开配额，activity 口径不受事件序号影响`() {
        val first = container(text("确定"))
        val second = container(text("确定", row(1)))
        val third = container(text("确定", row(2)))
        // 三棵树的节点位置不同 → 指纹不同，于是"能不能执行"只由配额窗口决定。
        val screenRule = rule(Selector(text = "确定"), maxActions = 1).copy(resetOn = AutomationRule.RESET_SCREEN)
        val state = AutomationGateState()
        assertTrue(execute(FakeBackend(first), first, screenRule, state, nowMs = 0L, eventSequence = 0L).performed.single().ok)
        val sameWindow = execute(FakeBackend(second), second, screenRule, state, nowMs = 400L, eventSequence = 0L)
        assertEquals(AutomationGateCodes.ACTION_MAX_REACHED, sameWindow.skipped.single().code)
        assertTrue("新的窗口更新重开配额", execute(FakeBackend(third), third, screenRule, state, nowMs = 800L, eventSequence = 1L).performed.single().ok)

        // activity 口径只认包名/Activity：序号变了也不重开，否则 screen 的序号会顺手废掉它。
        val activityState = AutomationGateState()
        val activityRule = rule(Selector(text = "确定"), maxActions = 1)
        assertTrue(execute(FakeBackend(first), first, activityRule, activityState, nowMs = 0L, eventSequence = 0L).performed.single().ok)
        assertEquals(
            AutomationGateCodes.ACTION_MAX_REACHED,
            execute(FakeBackend(second), second, activityRule, activityState, nowMs = 400L, eventSequence = 1L).skipped.single().code,
        )
    }

    // ---- 连续失败与自动停用 ----

    @Test
    fun `连续失败 3 次：自动停用该规则并给出回报，之后一次都不再执行`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "click"), maxActions = 9, actionCoolDownMs = 0)
        val backend = FakeBackend(tree).apply {
            clickOutcome = refused("TEST_CLICK_REFUSED", "测试：节点拒绝 ACTION_CLICK")
        }
        val state = AutomationGateState()
        var nowMs = 0L

        val reports = (1..AutomationRuleGate.FAILURE_LIMIT).map { expected ->
            val report = execute(backend, tree, rule, state, nowMs = nowMs)
            nowMs += AutomationRuleGate.ACTION_INTERVAL_MS
            val result = report.performed.single()
            assertFalse(result.ok)
            assertEquals(expected, result.failureStreak)
            report
        }

        val last = reports.last()
        assertEquals(listOf(rule.id), last.autoDisabledRuleIds)
        assertEquals(1, last.notices.size)
        assertTrue("回报要写清失败次数与后果：${last.notices.single()}", last.notices.single().contains("连续失败 3 次"))
        assertTrue(state.isAutoDisabled(rule.stateKey))
        assertEquals(0, state.used(rule.stateKey))

        // 自动停用之后闸门直接拒绝：一次后端调用都不该再发生（被停用的规则不能继续"试")。
        val callsBefore = backend.calls.size
        val after = execute(backend, tree, rule, state, nowMs = nowMs)
        assertTrue(after.results.isEmpty())
        assertEquals(AutomationGateCodes.RULE_AUTO_DISABLED, after.skipped.single().code)
        assertEquals(callsBefore, backend.calls.size)
    }

    // ---- 试运行 ----

    @Test
    fun `试运行只判定不执行：不消耗配额、不计失败、不调用后端`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "click"))
        val backend = FakeBackend(tree)
        val state = AutomationGateState()

        val report = execute(backend, tree, rule, state, nowMs = 0L, apply = false)

        val result = report.results.single()
        assertEquals(AutomationGateCodes.DRY_RUN, result.code)
        assertFalse(result.performed)
        assertFalse(result.ok)
        assertTrue(backend.calls.isEmpty())
        assertEquals(0, state.used(rule.stateKey))
        assertEquals(0, state.failureStreak(rule.stateKey))
        assertNull(state.lastActionAt)
    }

    // ---- 策略：白名单与自身包名 ----

    @Test
    fun `限制关闭后名单外应用可以执行再开启立即拒绝`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))
        val backend = FakeBackend(tree)
        val state = AutomationGateState()
        val allowed = execute(backend, tree, rule, state, 0, allowedPackages = emptySet(), whitelistEnabled = false)
        assertTrue(allowed.performed.single().ok)
        assertEquals(listOf("click"), backend.calls)
        val blocked = execute(backend, tree, rule, state, 1000, allowedPackages = emptySet(), whitelistEnabled = true)
        assertTrue(blocked.results.isEmpty())
        assertEquals(AutomationGateCodes.PACKAGE_NOT_ALLOWED, blocked.skipped.single().code)
        assertEquals(listOf("click"), backend.calls)
    }

    @Test
    fun `白名单外的应用一次动作都不执行`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))
        val backend = FakeBackend(tree)

        val report = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L, allowedPackages = setOf("com.other.app"))

        assertTrue(report.results.isEmpty())
        assertEquals(AutomationGateCodes.PACKAGE_NOT_ALLOWED, report.skipped.single().code)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `默认不对应用自身包名执行动作，显式放行后才执行`() {
        val self = AccessibilityAutomationPolicy.SELF_PACKAGE
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), packageName = self)
        val screen = screen(packageName = self, activity = "$self.MainActivity")
        val backend = FakeBackend(tree)
        val state = AutomationGateState()

        val blocked = execute(backend, tree, rule, state, nowMs = 0L, screen = screen, allowedPackages = setOf(self))
        assertTrue(blocked.results.isEmpty())
        assertEquals(AutomationGateCodes.SELF_PACKAGE_BLOCKED, blocked.skipped.single().code)
        assertTrue(backend.calls.isEmpty())

        // 只有设置页里的人工开关会打开这个标记；打开之后才允许。
        state.allowSelfPackage()
        val allowed = execute(backend, tree, rule, state, nowMs = 0L, screen = screen, allowedPackages = setOf(self))
        assertTrue(allowed.performed.single().ok)
        assertEquals(listOf("click"), backend.calls)
    }

    // ---- 执行前的最后一道防线 ----

    @Test
    fun `节点在判定与执行之间被替换：不动作并如实报 NODE_STALE`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))
        val backend = FakeBackend(tree).apply { snapshotTextOverride = "已被替换" }
        val state = AutomationGateState()

        val report = execute(backend, tree, rule, state, nowMs = 0L)

        val result = report.results.single()
        assertEquals(AutomationGateCodes.NODE_STALE, result.code)
        assertFalse(result.ok)
        assertFalse(result.performed)
        assertTrue(backend.calls.isEmpty())
        // 没有发出动作的失败不计入连续失败：界面刷新导致的偶发"节点被替换"不该把一条能用的规则自动停用。
        assertEquals(0, state.failureStreak(rule.stateKey))
    }

    @Test
    fun `命中节点已经消失：不动作并报 NODE_UNRESOLVED`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))
        val backend = FakeBackend(tree).apply { resolveMissing = true }

        val result = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L).results.single()

        assertEquals(AutomationGateCodes.NODE_UNRESOLVED, result.code)
        assertFalse(result.performed)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `服务没有提供执行后端时如实报后端缺失`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))

        val report = AutomationRuleExecutor.execute(
            backend = null,
            root = tree,
            screen = screen(),
            rules = listOf(rule),
            gate = AutomationGateState(),
            nowMs = 0L,
            allowedPackages = setOf(APP),
        )

        val result = report.results.single()
        assertEquals(AutomationGateCodes.BACKEND_UNAVAILABLE, result.code)
        assertFalse(result.performed)
    }

    @Test
    fun `未命中节点时不产出动作，跳过原因来自匹配器`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "不存在的按钮"))
        val backend = FakeBackend(tree)

        val report = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L)

        assertTrue(report.results.isEmpty())
        assertEquals(AutomationSkipCodes.NODE_NOT_FOUND, report.skipped.single().code)
        assertTrue(backend.calls.isEmpty())
    }

    // ---- 其它动作类型 ----

    @Test
    fun `不可注入的按键在发出动作之前就被拒绝，且不计入失败`() {
        val tree = container(text("确定"))
        val enter = AutomationKeyCodes.codeOf("ENTER") ?: error("测试依赖 ENTER 在允许列表里")
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "key", keyCode = enter))
        val backend = FakeBackend(tree)
        val state = AutomationGateState()

        val result = execute(backend, tree, rule, state, nowMs = 0L).results.single()

        assertEquals(AutomationGateCodes.ACTION_UNSUPPORTED, result.code)
        assertEquals("没有发出动作就不该算作已执行", false, result.performed)
        assertTrue("原因要说清可达的按键范围：${result.reason}", result.reason.contains("无法通过无障碍注入"))
        assertTrue(backend.calls.isEmpty())
        // 连一次后端调用都没有，因此不该被记成失败（否则规则会因为"不支持"而被自动停用）。
        assertEquals(0, state.failureStreak(rule.stateKey))
    }

    @Test
    fun `返回键通过无障碍全局动作发送`() {
        val tree = container(text("确定"))
        val back = AutomationKeyCodes.codeOf("BACK") ?: error("测试依赖 BACK 在允许列表里")
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "key", keyCode = back))
        val backend = FakeBackend(tree)

        val result = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L).performed.single()

        assertTrue(result.ok)
        assertEquals(AutomationGateCodes.KEY_PERFORMED, result.code)
        assertEquals(listOf("key:BACK"), backend.calls)
    }

    @Test
    fun `滑动按规则里的方向与时长下发`() {
        val tree = container(text("确定"))
        val rule = rule(
            Selector(text = "确定"),
            action = AutomationAction(type = "swipe", direction = "up", durationMs = 400),
        )
        val backend = FakeBackend(tree)

        val result = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L).performed.single()

        assertTrue(result.ok)
        assertEquals(AutomationGateCodes.SWIPE_PERFORMED, result.code)
        assertEquals(listOf("swipe:up:400"), backend.calls)
    }

    @Test
    fun `wait 不碰界面，只请求稍后重新判定`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), action = AutomationAction(type = "wait", delayMs = 500))
        val backend = FakeBackend(tree)

        val report = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L)

        assertEquals(AutomationGateCodes.WAIT_PERFORMED, report.performed.single().code)
        assertEquals(500, report.nextEvaluationDelayMs)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `matchDelayMs 的规则不产出动作，只请求延迟后重新判定`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), matchDelayMs = 200)
        val backend = FakeBackend(tree)

        val report = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L)

        assertTrue(report.results.isEmpty())
        assertEquals(AutomationSkipCodes.MATCH_DELAY_PENDING, report.skipped.single().code)
        assertEquals(200, report.nextEvaluationDelayMs)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `launch 目标不在白名单时不下发启动`() {
        val tree = container(text("确定"))
        val rule = rule(
            Selector(text = "确定"),
            action = AutomationAction(type = "launch", component = "com.other.app/.MainActivity"),
        )
        val backend = FakeBackend(tree)

        val report = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L)

        assertTrue(report.results.isEmpty())
        assertEquals(AutomationGateCodes.PACKAGE_NOT_ALLOWED, report.skipped.single().code)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `launch 目标在白名单内时下发启动`() {
        val tree = container(text("确定"))
        val rule = rule(
            Selector(text = "确定"),
            action = AutomationAction(type = "launch", component = "$APP/.MainActivity"),
        )
        val backend = FakeBackend(tree)

        val result = execute(backend, tree, rule, AutomationGateState(), nowMs = 0L).performed.single()

        assertTrue(result.ok)
        assertEquals(AutomationGateCodes.LAUNCH_PERFORMED, result.code)
        assertEquals(listOf("launch:$APP/.MainActivity"), backend.calls)
    }

    @Test
    fun `同 ID 的其他应用规则不会覆盖当前应用的动作或配额`() {
        val tree = container(text("确定"))
        val first = rule(Selector(text = "确定"), action = AutomationAction(type = "wait", delayMs = 100))
        val other = first.copy(packageName = "com.other.app", action = AutomationAction(type = "wait", delayMs = 900))
        val state = AutomationGateState()
        val backend = FakeBackend(tree)
        fun run(current: AutomationRule, now: Long) = AutomationRuleExecutor.execute(
            backend, tree, screen(packageName = current.packageName), listOf(first, other), state, now,
            allowedPackages = setOf(APP, other.packageName),
        )
        assertEquals(100, run(first, 0).nextEvaluationDelayMs)
        assertEquals(900, run(other, 400).nextEvaluationDelayMs)
        assertEquals(1, state.used(first.stateKey))
        assertEquals(1, state.used(other.stateKey))
        assertTrue(run(first, 800).results.isEmpty())
    }

    @Test
    fun `延迟到期执行且中间事件只等待剩余时间`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), matchDelayMs = 500)
        val backend = FakeBackend(tree)
        val state = AutomationGateState()
        assertEquals(500, execute(backend, tree, rule, state, 0).nextEvaluationDelayMs)
        assertEquals(100, execute(backend, tree, rule, state, 400).nextEvaluationDelayMs)
        val ready = execute(backend, tree, rule, state, 500)
        assertTrue(ready.performed.single().ok)
        assertNull(ready.nextEvaluationDelayMs)
        assertEquals(listOf("click"), backend.calls)
    }

    @Test
    fun `窗口切换规则修改与时钟回退都重新等待`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), matchDelayMs = 500)
        val state = AutomationGateState()
        val backend = FakeBackend(tree)
        execute(backend, tree, rule, state, 100)
        assertEquals(500, execute(backend, tree, rule, state, 400, screen = screen(activity = "Other")).nextEvaluationDelayMs)
        assertEquals(500, execute(backend, tree, rule, state, 450).nextEvaluationDelayMs)
        val edited = rule.copy(action = AutomationAction(type = "back"))
        assertEquals(500, execute(backend, tree, edited, state, 600).nextEvaluationDelayMs)
        assertEquals(500, execute(backend, tree, edited, state, 50).nextEvaluationDelayMs)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `延迟到期必须用新树重新匹配`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"), matchDelayMs = 200)
        val backend = FakeBackend(tree)
        val state = AutomationGateState()
        execute(backend, tree, rule, state, 0)
        val gone = execute(backend, container(text("取消")), rule, state, 200)
        assertTrue(gone.results.isEmpty())
        assertEquals(AutomationSkipCodes.NODE_NOT_FOUND, gone.skipped.single().code)
        assertTrue(backend.calls.isEmpty())
    }

    // ---- 辅助 ----

    private fun execute(
        backend: AutomationNodeBackend?,
        tree: AutomationNode?,
        rule: AutomationRule,
        state: AutomationGateState,
        nowMs: Long,
        allowedPackages: Set<String> = setOf(APP),
        screen: AutomationScreenInfo = screen(),
        apply: Boolean = true,
        eventSequence: Long = 0L,
        whitelistEnabled: Boolean = true,
    ): AutomationExecutionReport = AutomationRuleExecutor.execute(
        backend = backend,
        root = tree,
        screen = screen,
        rules = listOf(rule),
        gate = state,
        nowMs = nowMs,
        allowedPackages = allowedPackages,
        apply = apply,
        eventSequence = eventSequence,
        whitelistEnabled = whitelistEnabled,
    )

    private fun rule(
        vararg selectors: Selector,
        packageName: String = APP,
        maxActions: Int = 1,
        actionCoolDownMs: Int = 0,
        matchDelayMs: Int = 0,
        action: AutomationAction = AutomationAction(type = "click"),
    ): AutomationRule = AutomationRule(
        id = "r-1",
        packageName = packageName,
        matchDelayMs = matchDelayMs,
        maxActions = maxActions,
        actionCoolDownMs = actionCoolDownMs,
        selectors = selectors.toList(),
        action = action,
    )

    private fun screen(
        packageName: String = APP,
        activity: String? = "$APP.MainActivity",
    ): AutomationScreenInfo = AutomationScreenInfo(packageName, activity)

    private fun container(vararg children: AutomationNode): AutomationNode = AutomationNode(
        className = "android.widget.FrameLayout",
        enabled = true,
        editable = false,
        clickable = true,
        children = children.toList(),
        bounds = AutomationBounds(0, 0, 60, 1),
    )

    private fun text(value: String, bounds: AutomationBounds = row(0)): AutomationNode =
        node(text = value, bounds = bounds)

    private fun node(
        text: String? = null,
        viewId: String? = null,
        desc: String? = null,
        clickable: Boolean = true,
        enabled: Boolean = true,
        editable: Boolean = false,
        bounds: AutomationBounds = row(0),
    ): AutomationNode = AutomationNode(
        className = "android.widget.Button",
        text = text,
        viewId = viewId,
        desc = desc,
        clickable = clickable,
        enabled = enabled,
        editable = editable,
        bounds = bounds,
    )

    /** 第 [index] 行：每行高 100 像素，`top / 100` 就是行号。 */
    private fun row(index: Int): AutomationBounds = AutomationBounds(0, index * ROW_HEIGHT, 1080, (index + 1) * ROW_HEIGHT)

    private fun refused(code: String, reason: String): BackendOutcome = BackendOutcome.Refused(code, reason)

    /**
     * 假后端：只把"调用了哪个动作、返回什么结果"记下来。
     *
     * `resolveNode` 用判定时的树回放节点——这正是真后端"重新读一次界面树"的等价物，用它才能构造
     * "节点被替换"（[snapshotTextOverride]）与"节点消失"（[resolveMissing]）两条分支。
     */
    private class FakeBackend(private val root: AutomationNode) : AutomationNodeBackend {
        val calls = mutableListOf<String>()
        var clickOutcome: BackendOutcome = BackendOutcome.Done
        var ancestorOutcome: BackendOutcome = BackendOutcome.Refused("TEST_ANCESTOR_REFUSED", "测试：没有可点击的祖先节点")
        var centerOutcome: BackendOutcome =
            BackendOutcome.Refused("TEST_GESTURE_UNAVAILABLE", "测试：服务没有 canPerformGestures 能力")
        var longClickOutcome: BackendOutcome = BackendOutcome.Done
        var backOutcome: BackendOutcome = BackendOutcome.Done
        var swipeOutcome: BackendOutcome = BackendOutcome.Done
        var keyOutcome: BackendOutcome = BackendOutcome.Done
        var launchOutcome: BackendOutcome = BackendOutcome.Done
        var resolveMissing = false
        var snapshotTextOverride: String? = null

        override fun resolveNode(path: List<Int>, bounds: AutomationBounds): AutomationNodeHandle? {
            if (resolveMissing) return null
            val ref = root.flatten().firstOrNull { it.path == path } ?: return null
            return AutomationNodeHandle(
                ref.node,
                AutomationNodeSnapshot(
                    className = ref.node.className,
                    text = snapshotTextOverride ?: ref.node.text,
                    viewId = ref.node.viewId,
                    clickable = ref.node.clickable,
                    // 真后端读的是执行这一刻的屏幕坐标；这里用判定时的坐标（正常情形两者相同）。
                    bounds = bounds,
                ),
            )
        }

        override fun clickNode(node: Any): BackendOutcome {
            calls += "click"
            return clickOutcome
        }

        override fun clickClickableAncestor(node: Any): BackendOutcome {
            calls += "ancestor"
            return ancestorOutcome
        }

        override fun clickCenter(bounds: AutomationBounds): BackendOutcome {
            calls += "center"
            return centerOutcome
        }

        override fun longClickNode(node: Any): BackendOutcome {
            calls += "longClick"
            return longClickOutcome
        }

        override fun pressBack(): BackendOutcome {
            calls += "back"
            return backOutcome
        }

        override fun swipe(direction: String, durationMs: Int): BackendOutcome {
            calls += "swipe:$direction:$durationMs"
            return swipeOutcome
        }

        override fun sendKey(keyCode: Int): BackendOutcome {
            calls += "key:${AutomationKeyRouting.keyName(keyCode)}"
            return keyOutcome
        }

        override fun launch(component: String?, uri: String?): BackendOutcome {
            calls += "launch:${component ?: uri}"
            return launchOutcome
        }
    }

    private companion object {
        const val APP = "com.example.app"
        const val ROW_HEIGHT = 100
    }
}
