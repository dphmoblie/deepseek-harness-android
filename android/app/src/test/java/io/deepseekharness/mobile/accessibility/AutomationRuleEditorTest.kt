package io.deepseekharness.mobile.accessibility

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则编辑（新建 / 编辑 / 删除）的读改写语义。
 *
 * 用内存存储而不是 Android 偏好：这一层要证明的是「读出来 → 改一条 → 原子写回去」的判定，
 * 而不是 `SharedPreferences` 能不能写。所有用例都不依赖 `android.*` 桩件。
 */
class AutomationRuleEditorTest {

    private val packageName = "com.example.target"

    private class MemoryStorage : AutomationRulePackageStorage {
        val values = linkedMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) {
            values[key] = value
        }

        override fun clear() {
            values.clear()
        }
    }

    private fun draft(
        id: String,
        packageName: String = "com.example.target",
        text: String = "确定",
        maxActions: Int? = null,
        extra: JSONObject.() -> Unit = {},
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("packageName", packageName)
        .put("selectors", JSONArray().put(JSONObject().put("text", text)))
        .put("action", JSONObject().put("type", "click"))
        .also { json ->
            maxActions?.let { json.put("maxActions", it) }
            json.extra()
        }

    private fun rules(store: MemoryStorage, packageName: String = "com.example.target"): List<AutomationRule> =
        when (val read = AutomationRuleStore.readPackage(store, packageName)) {
            is AutomationRuleStore.PackageRead.Ok -> read.rules
            is AutomationRuleStore.PackageRead.Corrupt -> throw AssertionError("不期望这里读出损坏：${read.message}")
        }

    private fun rejected(outcome: AutomationRuleEditor.Outcome): AutomationRuleEditor.Outcome.Rejected {
        assertTrue("期望被拒绝，实际是 $outcome", outcome is AutomationRuleEditor.Outcome.Rejected)
        return outcome as AutomationRuleEditor.Outcome.Rejected
    }

    private fun applied(outcome: AutomationRuleEditor.Outcome): List<AutomationRule> {
        assertTrue("期望写入成功，实际是 $outcome", outcome is AutomationRuleEditor.Outcome.Applied)
        return (outcome as AutomationRuleEditor.Outcome.Applied).rules
    }

    private fun readRejected(outcome: AutomationRuleEditor.ReadOutcome): AutomationRuleEditor.ReadOutcome.Rejected {
        assertTrue("期望读取被拒绝，实际是 $outcome", outcome is AutomationRuleEditor.ReadOutcome.Rejected)
        return outcome as AutomationRuleEditor.ReadOutcome.Rejected
    }

    private fun found(outcome: AutomationRuleEditor.ReadOutcome): AutomationRule {
        assertTrue("期望读到规则，实际是 $outcome", outcome is AutomationRuleEditor.ReadOutcome.Found)
        return (outcome as AutomationRuleEditor.ReadOutcome.Found).rule
    }

    @Test
    fun `新建的一条规则写进存储并如实回读`() {
        val store = MemoryStorage()
        val outcome = AutomationRuleEditor.save(
            store,
            packageName,
            draft("tap-ok", maxActions = 3),
            AutomationRuleEditor.MODE_CREATE,
        )
        val appliedRules = applied(outcome)
        assertEquals(listOf("tap-ok"), appliedRules.map { it.id })
        assertEquals(3, appliedRules.single().maxActions)
        // 回读是另一条路径：写入成功不等于落盘内容能被解回来。
        val stored = rules(store)
        assertEquals(listOf("tap-ok"), stored.map { it.id })
        assertEquals(listOf(packageName), AutomationRuleStore.packages(store))
    }

    @Test
    fun `新建撞了已有标识就拒绝_不覆盖原来的内容`() {
        val store = MemoryStorage()
        AutomationRuleEditor.save(store, packageName, draft("tap-ok", text = "确定"), AutomationRuleEditor.MODE_CREATE)
        val rejected = rejected(
            AutomationRuleEditor.save(
                store,
                packageName,
                draft("tap-ok", text = "取消"),
                AutomationRuleEditor.MODE_CREATE,
            ),
        )
        assertEquals(AutomationRuleEditor.Codes.ID_CONFLICT, rejected.code)
        assertEquals("确定", rules(store).single().selectors.single().text)
    }

    @Test
    fun `编辑找不到标识就拒绝_不顺手新建一条`() {
        val store = MemoryStorage()
        val rejected = rejected(
            AutomationRuleEditor.save(store, packageName, draft("tap-ok"), AutomationRuleEditor.MODE_UPDATE),
        )
        assertEquals(AutomationRuleEditor.Codes.NOT_FOUND, rejected.code)
        assertEquals(emptyList<String>(), AutomationRuleStore.packages(store))
    }

    @Test
    fun `编辑是整条替换而不是按字段合并`() {
        val store = MemoryStorage()
        AutomationRuleEditor.save(store, packageName, draft("tap-ok", maxActions = 5), AutomationRuleEditor.MODE_CREATE)
        val appliedRules = applied(
            AutomationRuleEditor.save(store, packageName, draft("tap-ok"), AutomationRuleEditor.MODE_UPDATE),
        )
        // 草稿里没写的字段回到默认值——「只改一个字段」的路径是单条开关，不是这里。
        assertEquals(1, appliedRules.single().maxActions)
        assertEquals(1, rules(store).single().maxActions)
    }

    @Test
    fun `删除最后一条会把整个包清掉`() {
        val store = MemoryStorage()
        AutomationRuleEditor.save(store, packageName, draft("tap-ok"), AutomationRuleEditor.MODE_CREATE)
        val appliedRules = applied(AutomationRuleEditor.delete(store, packageName, "tap-ok"))
        assertEquals(emptyList<AutomationRule>(), appliedRules)
        assertEquals(emptyList<String>(), AutomationRuleStore.packages(store))
        assertEquals(emptyList<AutomationRule>(), rules(store))
    }

    @Test
    fun `删除别的包或者不存在的标识都拒绝`() {
        val store = MemoryStorage()
        AutomationRuleEditor.save(store, packageName, draft("tap-ok"), AutomationRuleEditor.MODE_CREATE)
        val rejected = rejected(AutomationRuleEditor.delete(store, packageName, "tap-cancel"))
        assertEquals(AutomationRuleEditor.Codes.NOT_FOUND, rejected.code)
        assertEquals(listOf("tap-ok"), rules(store).map { it.id })
    }

    @Test
    fun `草稿里的包名与请求的包名不一致就拒绝_不写到请求的那个包里`() {
        val store = MemoryStorage()
        val rejected = rejected(
            AutomationRuleEditor.save(
                store,
                packageName,
                draft("tap-ok", packageName = "com.example.other"),
                AutomationRuleEditor.MODE_CREATE,
            ),
        )
        assertEquals(AutomationRuleEditor.Codes.PACKAGE_MISMATCH, rejected.code)
        assertEquals(emptyList<String>(), AutomationRuleStore.packages(store))
        assertEquals(emptyList<AutomationRule>(), rules(store))
    }

    @Test
    fun `写入方式不认识就拒绝_一个字节都不写`() {
        val store = MemoryStorage()
        val rejected = rejected(
            AutomationRuleEditor.save(store, packageName, draft("tap-ok"), "delete"),
        )
        assertEquals(AutomationRuleEditor.Codes.MODE_INVALID, rejected.code)
        assertEquals(emptyMap<String, String>(), store.values)
    }

    @Test
    fun `规则内容不合法时透出严格解析的受控码`() {
        val store = MemoryStorage()
        val rejected = rejected(
            AutomationRuleEditor.save(
                store,
                packageName,
                draft("tap-ok") { put("unexpected", 1) },
                AutomationRuleEditor.MODE_CREATE,
            ),
        )
        // 不吞成笼统的「参数错误」：界面要能说清是哪个字段、为什么不行。
        assertEquals(AutomationRuleCodes.UNKNOWN_FIELD, rejected.code)
        assertEquals(emptyMap<String, String>(), store.values)
    }

    @Test
    fun `选择器一个条件都没有时拒绝`() {
        val store = MemoryStorage()
        // 空对象：`clickable`/`enabled` 这类状态字段也算条件，所以这里要的是「真的一个字段都没有」。
        val noConditions = JSONObject()
            .put("id", "tap-ok")
            .put("packageName", packageName)
            .put("selectors", JSONArray().put(JSONObject()))
            .put("action", JSONObject().put("type", "click"))
        val rejected = rejected(
            AutomationRuleEditor.save(store, packageName, noConditions, AutomationRuleEditor.MODE_CREATE),
        )
        assertEquals(AutomationRuleCodes.FIELD_INVALID, rejected.code)
        assertEquals(emptyMap<String, String>(), store.values)
    }

    @Test
    fun `超出每包条数上限时按限额拒绝_已存的规则原样保留`() {
        val store = MemoryStorage()
        val full = (1..AutomationRuleLimits.MAX_RULES_PER_PACKAGE).map { index ->
            AutomationRule(
                id = "rule-$index",
                packageName = packageName,
                selectors = listOf(Selector(text = "第$index")),
                action = AutomationAction(type = "click"),
            )
        }
        AutomationRuleStore.savePackage(store, packageName, full)
        val rejected = rejected(
            AutomationRuleEditor.save(
                store,
                packageName,
                draft("rule-extra"),
                AutomationRuleEditor.MODE_CREATE,
            ),
        )
        assertEquals(AutomationRuleEditor.Codes.LIMIT_REACHED, rejected.code)
        assertEquals(AutomationRuleLimits.MAX_RULES_PER_PACKAGE, rules(store).size)
    }

    @Test
    fun `包数据损坏时拒绝写入_也不把新的规则混进去`() {
        val store = MemoryStorage()
        AutomationRuleEditor.save(store, packageName, draft("tap-ok"), AutomationRuleEditor.MODE_CREATE)
        // 把索引里那一行的正文换成一段读不出来的东西：表头还在，正文坏了——这正是备份还原 / 手工改坏的形状。
        val raw = store.values.getValue(AutomationRuleStore.KEY_INDEX)
        val bodySeparator = raw.lastIndexOf('\t')
        store.values[AutomationRuleStore.KEY_INDEX] = raw.substring(0, bodySeparator + 1) + "{坏掉的正文"

        val rejected = rejected(
            AutomationRuleEditor.save(store, packageName, draft("tap-new"), AutomationRuleEditor.MODE_CREATE),
        )
        assertEquals(AutomationRuleEditor.Codes.CORRUPT, rejected.code)
        // 存储层的既有口径是「损坏的那个包会被清掉」，但绝不该出现一条我们刚写进去的规则。
        assertTrue(
            "损坏之后不该多出任何规则",
            rules(store).none { it.id == "tap-new" },
        )
    }

    @Test
    fun `读一条规则要带回选择器正文与动作参数_缺一项编辑就等于把它抹掉`() {
        val store = MemoryStorage()
        val rich = JSONObject()
            .put("id", "swipe-on-card")
            .put("packageName", packageName)
            .put("enabled", false)
            .put("allowActivities", JSONArray().put("com.example.target.MainActivity"))
            .put("denyActivities", JSONArray().put(".*Dialog.*"))
            .put("matchDelayMs", 1_200)
            .put("maxActions", 4)
            .put("actionCoolDownMs", 8_000)
            .put("resetOn", AutomationRule.RESET_SCREEN)
            .put(
                "selectors",
                JSONArray()
                    .put(JSONObject().put("text", "确认").put("id", ":id/confirm").put("clickable", true))
                    .put(JSONObject().put("descContains", "卡片")),
            )
            .put(
                "action",
                JSONObject().put("type", "swipe").put("durationMs", 400).put("direction", "left"),
            )
        assertEquals(1, applied(AutomationRuleEditor.save(store, packageName, rich, AutomationRuleEditor.MODE_CREATE)).size)

        val rule = found(AutomationRuleEditor.find(store, packageName, "swipe-on-card"))
        // 选择器正文：少一个字，界面预填出来就是另一条规则。
        assertEquals(2, rule.selectors.size)
        assertEquals("确认", rule.selectors[0].text)
        assertEquals(":id/confirm", rule.selectors[0].id)
        assertEquals(true, rule.selectors[0].clickable)
        assertEquals("卡片", rule.selectors[1].descContains)
        // 动作参数：type 之外的字段同样是内容的一部分（swipe 丢了方向就变成乱划）。
        assertEquals("swipe", rule.action.type)
        assertEquals(400, rule.action.durationMs)
        assertEquals("left", rule.action.direction)
        // 抑制参数与 allow/deny 名单也一并带回，避免「保存一次把设定退回默认」。
        assertEquals(false, rule.enabled)
        assertEquals(1_200, rule.matchDelayMs)
        assertEquals(4, rule.maxActions)
        assertEquals(8_000, rule.actionCoolDownMs)
        assertEquals(AutomationRule.RESET_SCREEN, rule.resetOn)
        assertEquals(listOf("com.example.target.MainActivity"), rule.allowActivities)
        assertEquals(listOf(".*Dialog.*"), rule.denyActivities)
    }

    @Test
    fun `读一条不存在的规则被拒绝`() {
        val store = MemoryStorage()
        AutomationRuleEditor.save(store, packageName, draft("tap-ok"), AutomationRuleEditor.MODE_CREATE)
        val rejected = readRejected(AutomationRuleEditor.find(store, packageName, "tap-missing"))
        assertEquals(AutomationRuleEditor.Codes.NOT_FOUND, rejected.code)
    }

    @Test
    fun `包数据损坏时读取被拒绝_而不是回一条空规则`() {
        val store = MemoryStorage()
        AutomationRuleEditor.save(store, packageName, draft("tap-ok"), AutomationRuleEditor.MODE_CREATE)
        val raw = store.values.getValue(AutomationRuleStore.KEY_INDEX)
        store.values[AutomationRuleStore.KEY_INDEX] =
            raw.substring(0, raw.lastIndexOf('\t') + 1) + "{坏掉的正文"

        val rejected = readRejected(AutomationRuleEditor.find(store, packageName, "tap-ok"))
        // 关键：不能把「读不出来」变成「读到一条空的」——那会让界面拿一份空表单去覆盖存储。
        assertEquals(AutomationRuleEditor.Codes.CORRUPT, rejected.code)
    }
}
