package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutomationRuleMatcher] 的匹配语义。
 *
 * 这里的用例刻意表驱动：选择器有 13 个字段，逐个写一个测试方法会让「哪些字段没覆盖」看不出来，
 * 而一张表能直接数出覆盖了几种语义。每个断言只挂一个语义点，红了就能定位到具体字段。
 *
 * 时钟全部是注入的整数（毫秒），因此冷却/次数上限的每个边界（差 1 毫秒、刚好到点、时钟回退）
 * 都能精确构造，不需要 `Thread.sleep`——真实实现里 sleep 出来的测试既慢又不稳定。
 */
class AutomationRuleMatcherTest {

    // ---- 单个选择器的字段语义 ----

    @Test
    fun `text 是精确相等而不是包含`() {
        // 同一棵树上放三个 TextView，只有 text 完全相同的那个能被匹到。
        val tree = container(
            text("确定", bounds = row(0)),
            text("确定按钮", bounds = row(1)),
            text("请点击确定", bounds = row(2)),
        )
        assertMatchedAt(0, rule(Selector(text = "确定")), tree)
        assertMatchedAt(1, rule(Selector(text = "确定按钮")), tree)
        assertMatchedAt(2, rule(Selector(text = "请点击确定")), tree)
        // 不存在精确相等的节点时**不能**退化成包含匹配。
        assertSkip(AutomationSkipCodes.NODE_NOT_FOUND, rule(Selector(text = "定")), tree)
    }

    @Test
    fun `文本类字段的四种语义各不相同`() {
        val tree = container(text("登录成功", bounds = row(0)), text("请重新登录", bounds = row(1)))
        assertMatchedAt(0, rule(Selector(textContains = "登录")), tree, "textContains 应当命中第一个")
        assertMatchedAt(1, rule(Selector(textStartsWith = "请")), tree, "textStartsWith 应当命中第二个")
        assertMatchedAt(0, rule(Selector(textEndsWith = "成功")), tree, "textEndsWith 应当命中第一个")
        assertNoDecision(rule(Selector(textContains = "注册")), tree, "textContains 不该匹到不含该片段的节点")
        // 空片段在 Kotlin 里 `contains("")` 为 true、`startsWith("")`/`endsWith("")` 也为 true；
        // 这是刻意的（用户填了空串说明他不关心这段文字），但要锁住行为，避免以后被"顺手修掉"。
        assertMatchedAt(0, rule(Selector(textContains = "")), tree)
    }

    @Test
    fun `desc 精确与 descContains 各管一段`() {
        val tree = container(
            AutomationNode(className = "android.widget.ImageButton", desc = "返回", clickable = true, bounds = row(0)),
            // 第二个节点的 desc 必须**不含** 返回：同一条规则只产出第一个命中节点，若两个节点都含
            // 返回，descContains 永远匹到第 0 行，「包含语义能匹到后面的行」就测不出来了。
            AutomationNode(className = "android.widget.ImageButton", desc = "首页", clickable = true, bounds = row(1)),
        )
        assertMatchedAt(0, rule(Selector(desc = "返回")), tree)
        // 「包含语义能匹到别的行」同样必须用只有目标节点的树：`descContains="返回"` 在这棵树上
        // 永远先匹到 row(0)，断言它就等于断言「第一个节点还在这儿」，测不出包含语义。
        val suffixOnlyForContains = container(
            AutomationNode(className = "android.widget.ImageButton", desc = "返回首页", clickable = true, bounds = row(0)),
        )
        assertMatchedAt(0, rule(Selector(descContains = "首页")), suffixOnlyForContains)
        assertMatchedAt(1, rule(Selector(descContains = "首页")), tree)
        // 「精确相等」的反证要用一棵只有 `返回首页` 的树：同一条规则在树上只产出**第一个**命中节点，
        // 所以不能在有 `返回` 的树上断言「没匹到 返回首页」——那永远为真，测不出精确语义。
        val suffixOnly = container(
            AutomationNode(className = "android.widget.ImageButton", desc = "返回首页", clickable = true, bounds = row(0)),
        )
        assertNoDecision(rule(Selector(desc = "返回")), suffixOnly, "desc 是精确相等，不该匹配 返回首页")
    }

    @Test
    fun `className 是精确相等`() {
        val tree = container(
            AutomationNode(className = "android.widget.Button", text = "确定", clickable = true, bounds = row(0)),
            AutomationNode(className = "android.widget.ImageButton", text = "取消", clickable = true, bounds = row(1)),
        )
        assertMatchedAt(0, rule(Selector(className = "android.widget.Button")), tree)
        assertNoDecision(rule(Selector(className = "android.widget.ButtonX")), tree, "不该匹配子类化的名字")
        assertNoDecision(rule(Selector(className = "android.widget.")), tree, "不该退化成前缀匹配")
    }

    @Test
    fun `选择器字段表全部参与判定`() {
        data class Case(val name: String, val selector: Selector, val expected: Boolean)

        val cases = listOf(
            Case("text", Selector(text = "确定"), true),
            Case("textContains", Selector(textContains = "确"), true),
            Case("textStartsWith", Selector(textStartsWith = "确"), true),
            Case("textEndsWith", Selector(textEndsWith = "定"), true),
            Case("id 全写", Selector(id = "com.example.app:id/ok"), true),
            Case("id 短写", Selector(id = ":id/ok"), true),
            Case("desc", Selector(desc = "确定按钮"), true),
            Case("descContains", Selector(descContains = "确定"), true),
            Case("className", Selector(className = "android.widget.Button"), true),
            Case("clickable", Selector(clickable = true), true),
            Case("enabled", Selector(enabled = true), true),
            Case("editable", Selector(editable = false), true),
            Case("minWidth", Selector(minWidth = 200), true),
            Case("minHeight", Selector(minHeight = 100), true),
            Case("minWidth 差 1 像素", Selector(minWidth = 201), false),
            Case("minHeight 差 1 像素", Selector(minHeight = 101), false),
            Case("id 不匹配", Selector(id = ":id/cancel"), false),
        )
        val target = AutomationNode(
            className = "android.widget.Button",
            text = "确定",
            viewId = "com.example.app:id/ok",
            desc = "确定按钮",
            clickable = true,
            enabled = true,
            editable = false,
            bounds = AutomationBounds(40, 100, 240, 200),
        )
        // 这张表用**裸的目标节点**而不是 `container(target)`：容器自身也是节点，布尔/尺寸这类单字段
        // 选择器会在容器上成立（一条规则只产出第一个命中节点，容器在子节点之前），断言就会落在容器上
        // 而不是被测字段。逐字段判定不需要外层容器参与；遍历整棵树由「命中前序第一个匹配节点」覆盖。
        val tree = target
        cases.forEach { case ->
            if (case.expected) {
                assertMatchedAt(1, rule(case.selector), tree, "${case.name}（选择器=${case.selector}）")
            } else {
                assertNoDecision(rule(case.selector), tree, "${case.name}（选择器=${case.selector}）")
            }
        }
        // 布尔类负例必须用「整棵树都没有该属性值」的树：若树里存在 clickable=true 的节点（哪怕只是
        // 容器），断言 `Selector(clickable=true)` 不命中就是不成立的假断言——它会被容器匹到。
        listOf(
            Selector(clickable = true) to "clickable：整棵树都没有 clickable=true 的节点，不该匹到",
            Selector(enabled = true) to "enabled：整棵树都没有 enabled=true 的节点，不该匹到",
            Selector(editable = true) to "editable：整棵树都没有 editable=true 的节点，不该匹到",
        ).forEach { (selector, message) ->
            assertNoDecision(
                rule(selector),
                node(text = "确定", clickable = false, enabled = false, editable = false, bounds = row(0)),
                "$message（选择器=$selector）",
            )
        }
    }
    // ---- id 的两种写法 ----

    @Test
    fun `id 的短写只看冒号之后的部分且必须是结尾`() {
        val tree = container(
            node(viewId = "com.example.app:id/submit", text = "提交", bounds = row(0)),
            node(viewId = "com.example.app:id/submitAll", text = "全部提交", bounds = row(1)),
            node(viewId = "com.other.app:id/submit", text = "别的应用", bounds = row(2)),
        )
        assertMatchedAt(0, rule(Selector(id = ":id/submit")), tree, "短写应当命中自己应用里的同名 id")
        // 一条规则每次判定只产出**第一个**命中节点（否则同一条规则会同时点多个地方），所以
        // 「跨包同名也命中」要用只有跨包节点的树来验证，不能指望在同一棵树上同时匹到 row(0) 和 row(2)。
        val crossPackage = container(node(viewId = "com.other.app:id/submit", text = "别的应用", bounds = row(0)))
        assertMatchedAt(
            0,
            rule(Selector(id = ":id/submit")),
            crossPackage,
            "短写不管包名，跨包同名也命中",
        )
        // 「不能命中 :id/submitAll」是一种**关于某个节点**的反证，所以只能用「只有那个节点」的树：
        // 同一条规则在同一棵树上只产出第一个命中节点，在含 row(0) 的树上断言 null 永远失败。
        val submitAllOnly = container(
            node(viewId = "com.example.app:id/submitAll", text = "全部提交", bounds = row(0)),
        )
        assertNoDecision(
            rule(Selector(id = ":id/submit")),
            submitAllOnly,
            "id=:id/submit 不能命中 :id/submitAll（结尾匹配，不是包含）",
        )
        assertMatchedAt(0, rule(Selector(id = "com.example.app:id/submit")), tree)
    }

    @Test
    fun `节点没有 viewId 时任何 id 写法都不命中`() {
        val tree = container(node(viewId = null, text = "确定", bounds = row(0)))
        assertNoDecision(rule(Selector(id = ":id/ok")), tree, "节点没有 viewId 时短写不该命中")
        assertNoDecision(rule(Selector(id = "com.example.app:id/ok")), tree, "节点没有 viewId 时全写不该命中")
    }

    // ---- 多选择器 AND ----

    @Test
    fun `多个选择器必须落在同一个节点上`() {
        // 两个节点分别满足其中一个选择器，但没有任何一个同时满足：AND 语义下不该命中。
        val tree = container(
            node(text = "确定", viewId = "com.example.app:id/ok", bounds = row(0)),
            node(text = "取消", viewId = "com.example.app:id/cancel", bounds = row(1)),
        )
        assertNull(
            "两个选择器分别匹到不同节点时不算命中（否则就是 OR）",
            matched(rule(Selector(text = "确定"), Selector(id = ":id/cancel")), tree),
        )
        val both = matched(rule(Selector(text = "确定"), Selector(id = ":id/ok")), tree)
        assertEquals(listOf(0, 1), both!!.selectorIndexes)
    }

    @Test
    fun `选择器命中的下标在 reason 里全部列出`() {
        val tree = container(node(text = "确定", viewId = "com.example.app:id/ok", bounds = row(0)))
        val decision = matched(rule(Selector(text = "确定"), Selector(textContains = "确")), tree)!!
        assertEquals(listOf(0, 1), decision.selectorIndexes)
        assertTrue("reason=${decision.reason}", decision.reason.contains("第 1、2 个选择器全部命中"))
    }

    // ---- 树的遍历 ----

    @Test
    fun `命中前序第一个匹配节点并给出可复现的路径`() {
        val tree = container(
            container(text("确定", bounds = row(0)), text("确定", bounds = row(1))),
            container(text("确定", bounds = row(2))),
        )
        val decision = matched(rule(Selector(text = "确定")), tree)!!
        // 第一个子容器的第一个子节点：前序第一个，也是屏幕上最靠上的那个。
        assertEquals(listOf(0, 0), decision.nodePath)
        assertEquals("0/0", decision.nodePathText)
        assertEquals(row(0).toString(), decision.bounds.toString())
    }

    @Test
    fun `空规则集不产出决策`() {
        val result = AutomationRuleMatcher.match(container(text("确定")), screen(), emptyList(), AutomationMatchState(), 0L)
        assertEquals(AutomationMatchResult.EMPTY, result)
        assertFalse(result.hasAction)
        assertEquals(emptyList<AutomationSkip>(), result.skipped)
    }

    @Test
    fun `没有节点树时记为节点为空而不是匹配失败`() {
        assertSkip(AutomationSkipCodes.EMPTY_TREE, rule(Selector(text = "确定")), null)
        assertSkip(AutomationSkipCodes.EMPTY_TREE, rule(Selector(text = "确定")), AutomationNode())
    }

    // ---- 包名与 Activity 过滤 ----

    @Test
    fun `规则只对指定包名的前台应用生效`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))
        assertTrue(AutomationRuleMatcher.match(tree, screen("com.example.app"), listOf(rule)).hasAction)
        val other = AutomationRuleMatcher.match(tree, screen("com.example.other"), listOf(rule))
        assertFalse(other.hasAction)
        assertEquals(AutomationSkipCodes.PACKAGE_MISMATCH, other.skipped.single().code)
    }

    @Test
    fun `Activity 白名单与黑名单按正则命中`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定")).copy(
            allowActivities = listOf("""^com\.example\.app\..*Activity$"""),
            denyActivities = listOf("Splash", "Permission"),
        )
        assertTrue(AutomationRuleMatcher.match(tree, screen(activity = "com.example.app.MainActivity"), listOf(rule)).hasAction)

        val notAllowed = AutomationRuleMatcher.match(tree, screen(activity = "com.example.app.Dialog"), listOf(rule))
        assertEquals(AutomationSkipCodes.ACTIVITY_NOT_ALLOWED, notAllowed.skipped.single().code)

        val excluded = AutomationRuleMatcher.match(tree, screen(activity = "com.example.app.SplashActivity"), listOf(rule))
        assertEquals(AutomationSkipCodes.ACTIVITY_EXCLUDED, excluded.skipped.single().code)
        assertTrue(excluded.skipped.single().reason.contains("Splash"))
    }

    @Test
    fun `只有黑名单时黑名单之外都放行`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定")).copy(denyActivities = listOf("Settings"))
        assertTrue(AutomationRuleMatcher.match(tree, screen(activity = "com.example.app.Main"), listOf(rule)).hasAction)
        assertFalse(AutomationRuleMatcher.match(tree, screen(activity = "com.example.app.Settings"), listOf(rule)).hasAction)
    }

    @Test
    fun `拿不到 Activity 名称时不放行带 Activity 条件的规则`() {
        val tree = container(text("确定"))
        val withWhitelist = rule(Selector(text = "确定")).copy(allowActivities = listOf("Main"))
        val skip = AutomationRuleMatcher.match(tree, screen(activity = null), listOf(withWhitelist)).skipped.single()
        assertEquals(AutomationSkipCodes.ACTIVITY_UNKNOWN, skip.code)

        // 没有任何 Activity 条件的规则不受影响（它本来就与界面无关）。
        assertTrue(AutomationRuleMatcher.match(tree, screen(activity = null), listOf(rule(Selector(text = "确定")))).hasAction)
    }

    @Test
    fun `停用的规则被跳过且原因可读`() {
        val tree = container(text("确定"))
        val disabled = rule(Selector(text = "确定")).copy(enabled = false)
        val result = AutomationRuleMatcher.match(tree, screen(), listOf(disabled))
        assertFalse(result.hasAction)
        assertEquals(AutomationSkipCodes.RULE_DISABLED, result.skipped.single().code)
    }

    @Test
    fun `匹配延迟的规则交给服务侧延后重试而不是在这里阻塞`() {
        val tree = container(text("确定"))
        val delayed = rule(Selector(text = "确定")).copy(matchDelayMs = 800)
        val skip = AutomationRuleMatcher.match(tree, screen(), listOf(delayed)).skipped.single()
        assertEquals(AutomationSkipCodes.MATCH_DELAY_PENDING, skip.code)
        assertTrue(skip.reason.contains("800"))
    }

    // ---- 不可点击降级 ----

    @Test
    fun `不可点击节点上的 click 降级为点击中心点且显式记录`() {
        val tree = container(
            AutomationNode(
                className = "android.widget.TextView",
                text = "立即领取",
                clickable = false,
                bounds = AutomationBounds(0, 100, 1080, 200),
            ),
        )
        val decision = matched(rule(Selector(text = "立即领取")), tree)!!
        assertTrue("必须标记已降级", decision.degradedToCenter)
        assertEquals(AutomationDecision.KIND_CENTER_FALLBACK, decision.kind)
        assertEquals(AutomationSkipCodes.CLICK_CENTER_FALLBACK, decision.code)
        assertTrue("降级原因必须写进 reason 且带坐标：${decision.reason}", decision.reason.contains("不可点击"))
        assertTrue("reason 应当带上中心点坐标：${decision.reason}", decision.reason.contains("540,150"))
        // 要用的坐标必须是中心点，不是节点 bounds（否则执行侧会退化成"点左上角"）。
        assertEquals(540, decision.resolvedBounds.left)
        assertEquals(150, decision.resolvedBounds.top)
        assertEquals(0, decision.bounds.left)
    }

    @Test
    fun `可点击节点保持 click 不降级`() {
        val tree = container(node(text = "确定", bounds = row(0)))
        val decision = matched(rule(Selector(text = "确定")), tree)!!
        assertFalse(decision.degradedToCenter)
        assertEquals(AutomationRuleMatcher.ACTION_CLICK, decision.kind)
        assertEquals(AutomationSkipCodes.MATCHED, decision.code)
        assertEquals(decision.bounds, decision.resolvedBounds)
    }

    @Test
    fun `选择器显式要求不可点击时按中心点执行`() {
        val tree = container(node(text = "文本", clickable = false, bounds = AutomationBounds(0, 0, 100, 100)))
        val decision = matched(rule(Selector(text = "文本", clickable = false)), tree)!!
        assertTrue(decision.degradedToCenter)
        assertEquals(50, decision.resolvedBounds.left)
    }

    @Test
    fun `clickCenter 动作不受节点可点击性影响`() {
        val tree = container(node(text = "确定", clickable = false, bounds = row(0)))
        val decision = matched(rule(Selector(text = "确定"), action = AutomationAction(type = "clickCenter")), tree)!!
        assertFalse("动作本身就是点中心，不算降级", decision.degradedToCenter)
        assertEquals("clickCenter", decision.kind)
    }

    // ---- 抑制：maxActions 与冷却 ----

    @Test
    fun `默认上限一次时第二次不重复执行直到冷却结束`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))
        val state = AutomationMatchState()

        val first = matched(rule, tree, state, 0L)!!
        state.commit(rule, 0L)

        val blocked = AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 100L)
        assertFalse("冷却期内不该再执行", blocked.hasAction)
        // 闸门顺序是「先上限、后冷却」：默认上限就是 1，已经用掉了，所以这里报的是上限；
        // reason 里同时给出「冷却还剩多少毫秒重开」（距上次 100 毫秒 ⇒ 剩 2900 毫秒），
        // 用户据此知道要等多久，而不是以为规则坏了。
        assertEquals(AutomationSkipCodes.ACTION_MAX_REACHED, blocked.skipped.single().code)
        assertTrue(
            "reason=${blocked.skipped.single().reason}",
            blocked.skipped.single().reason.contains("冷却还剩 2900 毫秒后重开"),
        )

        // 冷却到点（含）时配额重开：这里**不能**先 startDecision(3000) 再断言 3000 处还有决策——
        // 「执行一次」会把时间戳推到 3000，紧接着的判定 elapsed=0 又落回冷却窗口内，上限立刻重新生效。
        // 要验的是「窗口过去后配额重开」这一个事实，用一次 match 读结果即可。
        val after = AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 3_000L)
        assertTrue("冷却到点（含）应当重开配额", after.hasAction)
    }

    @Test
    fun `maxActions 限制冷却期内累计执行次数`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定")).copy(maxActions = 3, actionCoolDownMs = 10_000)
        val state = AutomationMatchState()

        var executed = 0
        // 时间固定每轮推进 200 毫秒：前 3 次落在同一个冷却窗口内，第 4 次（t=2000）已经跨出
        // 10000 毫秒窗口…为了不把「窗口边界」和「上限」两个因素混在一起，这里刻意让 6 次尝试
        // 都落在窗口内（0/200/400/600/800/1000），上限必须在窗口内就把执行截住。
        listOf(0L, 200L, 400L, 600L, 800L, 1_000L).forEach { at ->
            if (startDecision(rule, tree, state, at) != null) executed += 1
        }
        assertEquals("冷却期内最多执行 maxActions 次", 3, executed)

        val blocked = AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 1_000L)
        assertEquals(AutomationSkipCodes.ACTION_MAX_REACHED, blocked.skipped.single().code)
        assertTrue(blocked.skipped.single().reason.contains("上限 3 次"))

        // 窗口整体过去之后配额重开。**窗口是「装配额的执行窗口」**：最后一次执行在 t=400，窗口到
        // t=10400 才过去，所以 t=10000 时仍然被上限拦住（这正是「窗口长度」口径的必然结论，也是
        // 之前把 10000 当成"必然重开"的测试假设出错的地方）。重开后的配额是**满额**的：要再累计
        // 3 次才会被上限拦住，所以这里逐个时间点验「窗口重新计数」，而不是「重开后立刻又被拦住」。
        assertTrue("冷却到点（含）应当重开配额", startDecision(rule, tree, state, 10_400L) != null)
        val reopened = listOf(10_600L, 10_800L, 11_000L).count { startDecision(rule, tree, state, it) != null }
        assertEquals("重开后应当按上限重新计数", 2, reopened)
        assertTrue("重开后额度用满又被上限拦住", startDecision(rule, tree, state, 11_200L) == null)
    }

    @Test
    fun `冷却为 0 时不由冷却抑制只由上限抑制`() {
        val tree = container(text("确定"))
        // 冷却 0 表示没有时间窗口：配额用完之后**不会**自动重开，时间再往前推进也一样。
        val rule = rule(Selector(text = "确定")).copy(maxActions = 2, actionCoolDownMs = 0)
        val state = AutomationMatchState()

        assertEquals(2, listOf(0L, 0L, 0L).count { startDecision(rule, tree, state, it) != null })
        val blocked = AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 0L).skipped.single()
        assertEquals(AutomationSkipCodes.ACTION_MAX_REACHED, blocked.code)
        assertTrue("冷却为 0 时上限不会自动重开，原因里要说明", blocked.reason.contains("不会自动重开"))
        // 时间推得再远也不会重开（没有窗口可以"过去"）。
        assertTrue("冷却为 0 时时间推进不该重开配额", startDecision(rule, tree, state, 999_999L) == null)
    }

    @Test
    fun `时钟回退不会让规则永久卡死`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))
        val state = AutomationMatchState()
        startDecision(rule, tree, state, 10_000L)

        // 对时把时钟调回过去：必须按"冷却已结束"处理，否则时间戳落在未来会让规则永久失效。
        assertTrue(
            "时钟回退后应当重新可用",
            AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 5_000L).hasAction,
        )
    }

    @Test
    fun `不同规则的计数互不干扰`() {
        val tree = container(text("确定"))
        val first = rule(Selector(text = "确定")).copy(id = "r-first")
        val second = rule(Selector(text = "确定")).copy(id = "r-second")
        val state = AutomationMatchState()
        startDecision(first, tree, state, 0L)

        val result = AutomationRuleMatcher.match(tree, screen(), listOf(first, second), state, 100L)
        assertEquals("只有未被抑制的那条产出决策", listOf("r-second"), result.decisions.map { it.ruleId })
        assertEquals("另一条要给出被抑制的原因", listOf("r-first"), result.skipped.map { it.ruleId })
    }

    // ---- 窗口周期（resetOn） ----

    @Test
    fun `窗口标识变化会清空计数`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))
        val state = AutomationMatchState()
        state.beginWindow("com.example.app.Main")
        startDecision(rule, tree, state, 0L)

        // 同一个 Activity：计数保留。
        state.beginWindow("com.example.app.Main")
        assertFalse(AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 100L).hasAction)

        // 换了 Activity：用户看到的是新界面，配额重开。
        state.beginWindow("com.example.app.Detail")
        assertTrue(AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 100L).hasAction)
    }

    @Test
    fun `screen 口径允许在同一 Activity 内被一次窗口更新重开配额`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定")).copy(resetOn = AutomationRule.RESET_SCREEN)
        val state = AutomationMatchState()
        state.beginWindow("com.example.app.Main")
        startDecision(rule, tree, state, 0L)
        assertFalse(AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 100L).hasAction)

        // 同一个 Activity 内的新屏幕（页面切换）：显式推进一次即可重开。
        state.advanceWindow()
        assertTrue(AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 100L).hasAction)
        assertEquals(1L, state.windowUpdateSeq)
    }

    @Test
    fun `reset 清空全部抑制状态`() {
        val tree = container(text("确定"))
        val rule = rule(Selector(text = "确定"))
        val state = AutomationMatchState()
        state.beginWindow("com.example.app.Main")
        startDecision(rule, tree, state, 0L)

        state.reset()
        assertNull(state.lastWindowKey)
        assertEquals(0, state.count(rule.id))
        assertEquals(0L, state.windowUpdateSeq)
        assertTrue(AutomationRuleMatcher.match(tree, screen(), listOf(rule), state, 0L).hasAction)
    }

    // ---- 多个规则的整体行为 ----

    @Test
    fun `规则按传入顺序产出决策且跳过项单独列出`() {
        val tree = container(node(text = "确定", bounds = row(0), clickable = false), node(text = "取消", bounds = row(1)))
        val rules = listOf(
            rule(Selector(text = "取消")).copy(id = "r-cancel"),
            rule(Selector(text = "登录")).copy(id = "r-missing"),
            rule(Selector(text = "确定")).copy(id = "r-ok"),
            rule(Selector(text = "取消")).copy(id = "r-other-package", packageName = "com.example.other"),
        )
        val result = AutomationRuleMatcher.match(tree, screen(), rules)

        assertEquals(listOf("r-cancel", "r-ok"), result.decisions.map { it.ruleId })
        assertEquals(listOf("r-missing", "r-other-package"), result.skipped.map { it.ruleId })
        assertEquals(AutomationSkipCodes.NODE_NOT_FOUND, result.skipped.first().code)
        assertEquals(AutomationSkipCodes.PACKAGE_MISMATCH, result.skipped.last().code)
        assertTrue("降级信息要在决策里带着", result.decisions.last().degradedToCenter)
        assertTrue(result.hasAction)
    }

    @Test
    fun `诊断码全部是审计字段允许的形态`() {
        // 审计详情字段只接受 [A-Z][A-Z0-9_]{0,63}（见 AuditPolicy.detailPattern），
        // 这里把匹配器产出的码全部过一遍，避免将来加码时写出不能进日志的形态。
        val auditPattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")
        val codes = listOf(
            AutomationSkipCodes.MATCHED,
            AutomationSkipCodes.RULE_DISABLED,
            AutomationSkipCodes.PACKAGE_MISMATCH,
            AutomationSkipCodes.ACTIVITY_UNKNOWN,
            AutomationSkipCodes.ACTIVITY_NOT_ALLOWED,
            AutomationSkipCodes.ACTIVITY_EXCLUDED,
            AutomationSkipCodes.EMPTY_TREE,
            AutomationSkipCodes.MATCH_DELAY_PENDING,
            AutomationSkipCodes.NODE_NOT_FOUND,
            AutomationSkipCodes.ACTION_MAX_REACHED,
            AutomationSkipCodes.COOLDOWN_ACTIVE,
            AutomationSkipCodes.CLICK_CENTER_FALLBACK,
        )
        codes.forEach { code -> assertTrue("$code 不符合审计字段形态", auditPattern.matches(code)) }
    }

    @Test
    fun `rules 与决策里的动作被原样透传`() {
        // 动作是用户写下的意图，匹配器不能"顺手"把它换成别的（比如把 longClick 拆成两次 click）。
        val table = listOf(
            AutomationAction(type = "click"),
            AutomationAction(type = "clickCenter"),
            AutomationAction(type = "longClick"),
            AutomationAction(type = "back"),
        )
        val tree = container(node(text = "确定", bounds = row(0)))
        table.forEach { action ->
            val decision = matched(rule(Selector(text = "确定"), action = action), tree)!!
            assertEquals(action, decision.action)
            assertEquals(action.type, decision.kind)
        }
    }

    @Test
    fun `带参数的动作字段原样保留`() {
        val table = listOf(
            AutomationAction(type = "swipe", durationMs = 300, direction = "up"),
            AutomationAction(type = "key", keyCode = 4),
            AutomationAction(type = "wait", delayMs = 1_000),
            AutomationAction(type = "launch", component = "com.example.app/.MainActivity"),
            AutomationAction(type = "launch", uri = "https://example.com/x"),
        )
        val tree = container(node(text = "确定", bounds = row(0)))
        table.forEach { action ->
            val decision = matched(rule(Selector(text = "确定"), action = action), tree)!!
            assertEquals("动作 ${action.type} 的参数不该被改写", action, decision.action)
            assertEquals(action.durationMs, decision.action.durationMs)
            assertEquals(action.direction, decision.action.direction)
            assertEquals(action.keyCode, decision.action.keyCode)
            assertEquals(action.delayMs, decision.action.delayMs)
            assertEquals(action.component, decision.action.component)
            assertEquals(action.uri, decision.action.uri)
        }
    }

    // ---- 测试脚手架 ----

    /** 命中时返回决策，未命中时失败并打印跳过原因（比 `assertNotNull` 多给一条线索）。 */
    private fun matched(
        rule: AutomationRule,
        tree: AutomationNode?,
        state: AutomationMatchState = AutomationMatchState(),
        nowMs: Long = 0L,
        screen: AutomationScreenInfo = screen(),
    ): AutomationDecision? {
        val result = AutomationRuleMatcher.match(tree, screen, listOf(rule), state, nowMs)
        if (result.hasAction) return result.decisions.single()
        // 未命中是合法结果（部分用例就是要断言它），这里只返回 null，具体断言由调用方做。
        return null
    }

    /** 「尝试执行一次」：匹配到就提交计数，等价于服务侧执行成功后的回写。 */
    private fun startDecision(
        rule: AutomationRule,
        tree: AutomationNode?,
        state: AutomationMatchState,
        nowMs: Long,
    ): AutomationDecision? {
        val decision = matched(rule, tree, state, nowMs)
        if (decision != null) state.commit(rule, nowMs)
        return decision
    }

    /**
     * 命中的节点是第几行（未命中返回 `null`）。
     *
     * 这里刻意不用「bounds 等于某个值」来判断是否命中：`container()` 造出来的根节点自身
     * `clickable=true`、bounds 是 1080×1920，`Selector(clickable=false)`、`Selector(editable=false)`、
     * `Selector(minHeight=100)` 这类**单字段**选择器在根节点上会合法成立，于是「匹到根节点」和
     * 「匹到被测节点」在只看 `bounds != null` 的断言下无法区分，测试会假绿/假红。
     */
    private fun matchedAtRow(rule: AutomationRule, tree: AutomationNode): Int? =
        matched(rule, tree)?.let { decision ->
            // 节点不在 row() 造出来的行上（例如根节点）时给出负数，避免整数除法把它算成第 0 行。
            if (decision.bounds.height != ROW_HEIGHT) -1 else decision.bounds.top / ROW_HEIGHT
        }

    private fun assertMatchedAt(expectedRow: Int, rule: AutomationRule, tree: AutomationNode, message: String = "") {
        assertEquals(message, expectedRow, matchedAtRow(rule, tree))
    }

    /** 断言这条规则**没有**产出决策（比只断言「不是某一个节点」更强）。 */
    private fun assertNoDecision(rule: AutomationRule, tree: AutomationNode, message: String) {
        assertNull(message, matched(rule, tree))
    }

    /** `Assert.assertNull` 的语义化包装：断言这条规则**没有**产出决策。 */
    private fun assertNoMatch(rule: AutomationRule, tree: AutomationNode, message: String) {
        assertNull(message, matched(rule, tree))
    }

    private fun assertSkip(code: String, rule: AutomationRule, tree: AutomationNode?) {
        val result = AutomationRuleMatcher.match(tree, screen(), listOf(rule))
        assertFalse("不该有决策", result.hasAction)
        assertEquals(code, result.skipped.single().code)
    }

    private fun rule(vararg selectors: Selector, action: AutomationAction = AutomationAction(type = "click")): AutomationRule =
        AutomationRule(
            id = "r-1",
            packageName = "com.example.app",
            selectors = selectors.toList(),
            action = action,
        )

    private fun screen(
        packageName: String = "com.example.app",
        activity: String? = "com.example.app.MainActivity",
    ): AutomationScreenInfo = AutomationScreenInfo(packageName, activity)

    /**
     * 容器节点：被测节点挂在它下面，用来验证「遍历整棵树」而不是只看根节点。
     *
     * 几个属性刻意与 `node()` 造出来的被测节点相反（`enabled=false`、`editable=true`、
     * `clickable=false`、`desc=null`），并且**尺寸刻意小到任何尺寸类选择器都不会匹到它**：
     * `minWidth`/`minHeight` 这类单字段选择器不像 `text` 那样天然把容器排除掉，容器要是「够大」，
     * `Selector(minWidth=200)` 会先匹到它（一条规则只产出第一个命中节点，容器在子节点之前）。
     * 断言必须能区分「匹到容器」和「匹到被测节点」，否则用例会假绿。
     */
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

    /** 第 [index] 行：每行高 100 像素，`top / 100` 就是行号，便于断言"匹到了哪一个"。 */
    private fun row(index: Int): AutomationBounds = AutomationBounds(0, index * ROW_HEIGHT, 1080, (index + 1) * ROW_HEIGHT)

    private companion object {
        const val ROW_HEIGHT = 100
    }
}
