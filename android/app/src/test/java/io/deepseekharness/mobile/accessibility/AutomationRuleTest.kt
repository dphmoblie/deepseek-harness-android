package io.deepseekharness.mobile.accessibility

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutomationRule] 的严格解析与校验。
 *
 * 这里的断言口径围绕一条原则：**规则是「一次解析、长期自动执行」的**。写错一个字段如果被静默
 * 忽略或截断，用户看不到任何提示，只会在某天发现「手机自己在乱点」或者「规则从来没生效过」。
 * 所以非法输入一律要在解析期被拒，错误信息还要能指出是哪个字段——下面大量断言 `.code`
 * 而不只是「抛了异常」，就是在锁住这个口径。
 */
class AutomationRuleTest {

    // ---- 合法解析 ----

    @Test
    fun `最小规则只给必要字段时按文档默认值补齐`() {
        val rule = AutomationRule.fromJson(minimalJson())
        assertEquals("r1", rule.id)
        assertEquals("com.example.app", rule.packageName)
        assertTrue("enabled 缺省必须是 true", rule.enabled)
        assertEquals(emptyList<String>(), rule.allowActivities)
        assertEquals(0, rule.matchDelayMs)
        assertEquals(1, rule.maxActions)
        assertEquals(3_000, rule.actionCoolDownMs)
        assertEquals(AutomationRule.RESET_ACTIVITY, rule.resetOn)
        assertEquals(1, rule.selectors.size)
        assertEquals("click", rule.action.type)
    }

    @Test
    fun `完整规则往返序列化后逐字段相等`() {
        val rule = AutomationRule.fromJson(JSONObject(fullJson())).validated()
        val again = AutomationRule.fromJson(rule.toJson())
        assertEquals(rule, again)
        // 再序列化一次也应当稳定（避免 put 的顺序/类型漂移导致契约漂移）。
        assertEquals(rule.toJson().toString(), again.toJson().toString())
    }

    @Test
    fun `八种动作类型都能解析并校验`() {
        val actions = listOf(
            """{"type":"click"}""",
            """{"type":"clickCenter"}""",
            """{"type":"longClick"}""",
            """{"type":"back"}""",
            """{"type":"swipe","durationMs":300,"direction":"left"}""",
            """{"type":"key","keyCode":4}""",
            """{"type":"wait","delayMs":1500}""",
            """{"type":"launch","component":"com.example.app/.MainActivity"}""",
            """{"type":"launch","uri":"https://example.com/x"}""",
        )
        actions.forEach { action ->
            val rule = AutomationRule.fromJson(minimalJson().put("action", JSONObject(action)))
            assertNotNull("动作 $action 应当可用", rule.action.validated())
        }
    }

    @Test
    fun `参数化取值在边界上应当被接受`() {
        val accepted = listOf(
            minimalJson().put("matchDelayMs", 0),
            minimalJson().put("matchDelayMs", 5_000),
            minimalJson().put("maxActions", 1),
            minimalJson().put("maxActions", 99),
            minimalJson().put("actionCoolDownMs", 0),
            minimalJson().put("actionCoolDownMs", 600_000),
            minimalJson().put("resetOn", "screen"),
            minimalJson().put("allowActivities", JSONArray(listOf("^com\\.example\\..*Activity$"))),
            minimalJson().put("selectors", JSONArray((1..16).map { JSONObject("""{"text":"t$it"}""") })),
            minimalJson().put("selectors", JSONArray(listOf(JSONObject("""{"minWidth":0,"minHeight":0}""")))),
            minimalJson().put("selectors", JSONArray(listOf(JSONObject("""{"text":"${"字".repeat(200)}"}""")))),
        )
        accepted.forEach { json ->
            assertNotNull("应当接受：$json", AutomationRule.fromJson(json).validated())
        }
    }

    // ---- 非法输入一律带受控错误码 ----

    @Test
    fun `非法输入被拒且错误码与字段一一对应`() {
        data class Case(val name: String, val json: JSONObject, val field: String, val code: String)

        val cases = listOf(
            Case("标识为空", minimalJson().put("id", ""), "id", AutomationRuleCodes.FIELD_INVALID),
            Case("标识超长", minimalJson().put("id", "a".repeat(65)), "id", AutomationRuleCodes.FIELD_INVALID),
            Case("标识含非法字符", minimalJson().put("id", "规则1"), "id", AutomationRuleCodes.FIELD_INVALID),
            Case("包名为空", minimalJson().put("packageName", ""), "packageName", AutomationRuleCodes.PACKAGE_INVALID),
            Case("包名是系统保留包", minimalJson().put("packageName", "com.android.systemui"), "packageName", AutomationRuleCodes.PACKAGE_INVALID),
            Case("包名单段", minimalJson().put("packageName", "app"), "packageName", AutomationRuleCodes.PACKAGE_INVALID),
            Case("匹配延迟为负", minimalJson().put("matchDelayMs", -1), "matchDelayMs", AutomationRuleCodes.FIELD_INVALID),
            Case("匹配延迟超上限", minimalJson().put("matchDelayMs", 5_001), "matchDelayMs", AutomationRuleCodes.FIELD_INVALID),
            Case("执行次数为 0", minimalJson().put("maxActions", 0), "maxActions", AutomationRuleCodes.FIELD_INVALID),
            Case("执行次数超上限", minimalJson().put("maxActions", 100), "maxActions", AutomationRuleCodes.FIELD_INVALID),
            Case("冷却为负", minimalJson().put("actionCoolDownMs", -1), "actionCoolDownMs", AutomationRuleCodes.FIELD_INVALID),
            Case("冷却超上限", minimalJson().put("actionCoolDownMs", 600_001), "actionCoolDownMs", AutomationRuleCodes.FIELD_INVALID),
            Case("重置方式未知", minimalJson().put("resetOn", "window"), "resetOn", AutomationRuleCodes.FIELD_INVALID),
            Case("选择器为空数组", minimalJson().put("selectors", JSONArray()), "selectors", AutomationRuleCodes.FIELD_INVALID),
            Case("选择器超过 16 个", minimalJson().put("selectors", JSONArray((1..17).map { JSONObject("""{"text":"t$it"}""") })), "selectors", AutomationRuleCodes.FIELD_INVALID),
            Case("选择器没有任何条件", minimalJson().put("selectors", JSONArray(listOf(JSONObject("{}")))), "selectors", AutomationRuleCodes.FIELD_INVALID),
            Case("选择器文本超长", minimalJson().put("selectors", JSONArray(listOf(JSONObject("""{"text":"${"字".repeat(201)}"}""")))), "text", AutomationRuleCodes.FIELD_INVALID),
            Case("选择器 id 写成裸名字", minimalJson().put("selectors", JSONArray(listOf(JSONObject("""{"id":"login"}""")))), "id", AutomationRuleCodes.FIELD_INVALID),
            Case("选择器 id 缺名称", minimalJson().put("selectors", JSONArray(listOf(JSONObject("""{"id":"com.example.app:id/"}""")))), "id", AutomationRuleCodes.FIELD_INVALID),
            Case("选择器尺寸为负", minimalJson().put("selectors", JSONArray(listOf(JSONObject("""{"minWidth":-1}""")))), "minWidth", AutomationRuleCodes.FIELD_INVALID),
            Case("动作缺类型", minimalJson().put("action", JSONObject("""{"delayMs":100}""")), "type", AutomationRuleCodes.FIELD_INVALID),
            Case("动作类型未知", minimalJson().put("action", JSONObject("""{"type":"doubleTap"}""")), "type", AutomationRuleCodes.FIELD_INVALID),
            Case("滑动缺时长", minimalJson().put("action", JSONObject("""{"type":"swipe","direction":"up"}""")), "durationMs", AutomationRuleCodes.FIELD_INVALID),
            Case("滑动时长越界", minimalJson().put("action", JSONObject("""{"type":"swipe","durationMs":2_001,"direction":"up"}""")), "durationMs", AutomationRuleCodes.FIELD_INVALID),
            Case("滑动方向未知", minimalJson().put("action", JSONObject("""{"type":"swipe","durationMs":300,"direction":"upLeft"}""")), "direction", AutomationRuleCodes.FIELD_INVALID),
            Case("按键不在白名单", minimalJson().put("action", JSONObject("""{"type":"key","keyCode":26}""")), "keyCode", AutomationRuleCodes.FIELD_INVALID),
            Case("等待缺时长", minimalJson().put("action", JSONObject("""{"type":"wait"}""")), "delayMs", AutomationRuleCodes.FIELD_INVALID),
            Case("等待时长为 0", minimalJson().put("action", JSONObject("""{"type":"wait","delayMs":0}""")), "delayMs", AutomationRuleCodes.FIELD_INVALID),
            Case("启动既无组件也无地址", minimalJson().put("action", JSONObject("""{"type":"launch"}""")), "component", AutomationRuleCodes.FIELD_INVALID),
            Case("启动地址协议不支持", minimalJson().put("action", JSONObject("""{"type":"launch","uri":"file:///sdcard/x"}""")), "uri", AutomationRuleCodes.FIELD_INVALID),
            Case("启动组件不是 包名/类名", minimalJson().put("action", JSONObject("""{"type":"launch","component":"com.example.app"}""")), "component", AutomationRuleCodes.FIELD_INVALID),
        )

        cases.forEach { case ->
            val error = assertThrows(case.name, AutomationRuleException::class.java) {
                AutomationRule.fromJson(case.json)
            }
            assertEquals("${case.name} 的错误码", case.code, error.code)
            // 错误信息是面向用户的中文文案，不保证把 JSON 字段名原样写进去（例如 <code>id</code> 那条
            // 写的是「规则标识」）。所以这里断言的是「提到了这个字段」——JSON 名或它的中文称呼命中
            // 一个即可；两者都不提说明调用方拿到的信息定位不到出错字段。
            val keywords = fieldKeywords[case.field] ?: listOf(case.field)
            assertTrue(
                "${case.name} 的错误信息应当提到 ${case.field}（或它的中文称呼）：${error.message}",
                keywords.any { error.message!!.contains(it) },
            )
        }
    }

    @Test
    fun `非法 Activity 正则在解析期就被拒`() {
        val error = assertThrows(AutomationRuleException::class.java) {
            AutomationRule.fromJson(minimalJson().put("allowActivities", JSONArray(listOf("com.example.["))))
        }
        assertEquals(AutomationRuleCodes.PATTERN_INVALID, error.code)
        assertTrue(error.message!!.contains("Activity 白名单"))
    }

    @Test
    fun `不是 JSON 对象时给出可诊断的格式错误`() {
        listOf("", "[1,2,3]", "not json", "{}").forEach { text ->
            val error = assertThrows("输入 $text", AutomationRuleException::class.java) {
                AutomationRule.fromJson(text)
            }
            assertTrue(
                "输入 $text 应当报格式或字段错误：${error.code}",
                error.code == AutomationRuleCodes.MALFORMED || error.code == AutomationRuleCodes.FIELD_INVALID,
            )
        }
    }

    // ---- 未知字段一律拒绝（严格解析） ----

    @Test
    fun `规则与选择器与动作的未知字段都被拒绝`() {
        val cases = listOf(
            minimalJson().put("matchDelay", 100) to "matchDelay",
            minimalJson().put("selectors", JSONArray(listOf(JSONObject("""{"txt":"确定"}""")))) to "txt",
            minimalJson().put("action", JSONObject("""{"type":"click","x":1}""")) to "x",
        )
        cases.forEach { (json, field) ->
            val error = assertThrows("未知字段 $field", AutomationRuleException::class.java) {
                AutomationRule.fromJson(json)
            }
            assertEquals(AutomationRuleCodes.UNKNOWN_FIELD, error.code)
            assertTrue("错误信息应指出未知字段 $field：${error.message}", error.message!!.contains(field))
        }
    }

    @Test
    fun `类型错误的字段被拒而不是被强转`() {
        val cases = listOf(
            minimalJson().put("enabled", "true"),
            minimalJson().put("matchDelayMs", "100"),
            minimalJson().put("maxActions", 1.5),
            minimalJson().put("selectors", JSONObject("{}")),
            minimalJson().put("allowActivities", "com.example.MainActivity"),
            minimalJson().put("selectors", JSONArray(listOf(JSONObject("""{"clickable":"true"}""")))),
        )
        cases.forEach { json ->
            val error = assertThrows("$json", AutomationRuleException::class.java) { AutomationRule.fromJson(json) }
            assertEquals(AutomationRuleCodes.FIELD_INVALID, error.code)
        }
    }

    // ---- 上限：拒绝而不是截断 ----

    @Test
    fun `每应用规则数上限是 40 条`() {
        assertEquals(40, AutomationRuleLimits.MAX_RULES_PER_PACKAGE)
        val rules = (1..40).map { index ->
            AutomationRule.fromJson(minimalJson().put("id", "r$index"))
        }
        assertEquals(40, rules.size)
        // 第 41 条本身是合法规则（解析不报错），超限由存储层在整包写入时拒绝。
        assertNotNull(AutomationRule.fromJson(minimalJson().put("id", "r41")))
    }

    @Test
    fun `限额常量与文档口径一致`() {
        assertEquals(64 * 1024, AutomationRuleLimits.MAX_PACKAGE_JSON_BYTES)
        assertEquals(256 * 1024, AutomationRuleLimits.MAX_TOTAL_JSON_BYTES)
        assertEquals(200, AutomationRuleLimits.MAX_STRING_CHARS)
        assertEquals(16, AutomationRuleLimits.MAX_SELECTORS)
        assertEquals(1, AutomationRuleLimits.SCHEMA)
    }

    @Test
    fun `字符串长度按字符数判定而不是按 UTF-8 字节数`() {
        // 200 个中文字符 = 600 字节，仍然合法：限额口径是字符数，否则用户填中文界面文字会被莫名卡住。
        val rule = AutomationRule.fromJson(minimalJson().put("selectors", JSONArray(listOf(JSONObject().put("text", "字".repeat(200))))))
        assertEquals(200, rule.selectors.single().text!!.length)
        assertEquals(600, jsonUtf8Bytes(rule.selectors.single().text!!))
    }

    // ---- 按键白名单 ----

    @Test
    fun `按键白名单与 VirtualScreenPolicy 的 keyevent 是同一张表`() {
        assertEquals(
            mapOf(
                "BACK" to 4, "ENTER" to 66, "DEL" to 67, "TAB" to 61,
                "DPAD_UP" to 19, "DPAD_DOWN" to 20, "DPAD_LEFT" to 21, "DPAD_RIGHT" to 22,
                "DPAD_CENTER" to 23, "SPACE" to 62, "ESC" to 111,
            ),
            AutomationKeyCodes.names().associateWith { AutomationKeyCodes.codeOf(it)!! },
        )
        // 破坏性/系统级按键不在表内：规则是一次解析长期执行，放开整段 KeyEvent 等于把它们变成一行 JSON 可触发。
        listOf(26 /* POWER */, 27 /* CAMERA */, 5 /* CALL */, 6 /* ENDCALL */).forEach { code ->
            assertFalse("keyCode $code 不应被允许", code in AutomationKeyCodes.ALLOWED)
        }
    }

    @Test
    fun `id 的两种合法写法都被接受`() {
        val accepted = listOf(
            "com.example.app:id/login_button",
            ":id/login_button",
            "com.example.app:id/a1",
            ":id/a_1",
        )
        accepted.forEach { id ->
            val rule = AutomationRule.fromJson(minimalJson().put("selectors", JSONArray(listOf(JSONObject().put("id", id)))))
            assertEquals(id, rule.selectors.single().id)
        }
    }

    /**
     * JSON 字段名 → 该字段在校验文案里可能出现的中文称呼。
     *
     * 校验文案是给用户看的中文（「规则标识必须是…」而不是「id must be…」），所以「错误信息必须提到
     * 出错字段」这条断言只能按「JSON 名或中文称呼命中一个」来判；缺了中文称呼就说明用户看到报错
     * 也不知道该改哪个字段。
     */
    private val fieldKeywords: Map<String, List<String>> = mapOf(
        "id" to listOf("id", "标识"),
        "packageName" to listOf("packageName", "包名"),
        "matchDelayMs" to listOf("matchDelayMs", "匹配延迟", "延迟"),
        "maxActions" to listOf("maxActions", "执行次数"),
        "actionCoolDownMs" to listOf("actionCoolDownMs", "冷却"),
        "resetOn" to listOf("resetOn", "重置方式"),
        "selectors" to listOf("selectors", "选择器"),
        "durationMs" to listOf("durationMs", "滑动时间"),
        "direction" to listOf("direction", "滑动方向"),
        "keyCode" to listOf("keyCode", "按键"),
        "delayMs" to listOf("delayMs", "等待时间", "延迟"),
        // 动作类型是唯一用中文「动作」称呼的字段：文案是「动作类型不支持：doubleTap（可选 …）」，
        // 用户从这句就能定位到 action.type。
        "type" to listOf("type", "动作类型", "动作"),
        // launch 的两个字段同理：文案是「启动组件必须写成 包名/类名」与「启动地址只支持 …」。
        "component" to listOf("component", "启动组件", "组件"),
        "uri" to listOf("uri", "启动地址", "地址"),
    )

    /**
     * 把 `Selector.id` 的**全写**口径钉在 `AccessibilityAutomationPolicy.viewIdPattern` 上。
     *
     * 两边是同一条正则的两份拷贝（策略里的那个是私有的，规则引擎无法直接引用），所以只能用
     * **行为**对钉：拿同一批 `viewId` 分别喂给 `parseAction`（旧接口，直接用策略里的正则，
     * 且其 `matchEntire` 等价于全串匹配）与 `AutomationRule.fromJson`（规则引擎，用
     * [FULL_VIEW_ID_PATTERN]），要求「接受/拒绝」完全一致。任何一侧改动都会让这个用例变红，
     * 从而避免出现「旧接口能点到的节点，规则引擎写不出来」这种分叉。
     */
    @Test
    fun `选择器 id 的全写与策略里的 viewIdPattern 同口径`() {
        val accepted = listOf(
            "com.example.app:id/ok",
            "a.b:id/_under",
            "a.b:id/n123",
        )
        val rejected = listOf(
            "com.example.app:id/", // 缺资源名
            "com.example.app:id/9n", // 资源名不能以数字开头
            "login", // 裸名字
            "com.example.app:id/ok/extra", // 多了一段
            "com.example.app:id/${"x".repeat(81)}", // 资源名超长（上限 80）
            "com.id/ok", // 包名只有一段
        )
        // 只拿**全写**对钉：短写 `:id/name` 是规则引擎自己的扩展（策略里没有这个概念，
        // `parseAction` 要求带上包名），所以它必须单独断言，不能混进「两边一致」的循环里。
        (accepted + rejected).forEach { viewId ->
            // 旧接口要求「viewId 里的包名 == 参数里的 packageName」，所以这里的 packageName 必须
            // 与 viewId 的前缀一致，否则测到的是「跨包拒绝」而不是正则本身的口径。
            val packageName = viewId.substringBefore(':')
            val policyAccepts = AccessibilityAutomationPolicy.parseAction(actionParam(packageName, viewId)) != null
            val selectorJson = JSONObject().put("id", viewId)
            val ruleAccepts = runCatching {
                AutomationRule.fromJson(minimalJson().put("selectors", JSONArray(listOf(selectorJson))))
            }.isSuccess
            assertEquals("viewId=$viewId 时两边口径应当一致", policyAccepts, ruleAccepts)
        }
        // 短写：规则引擎接受（匹配时只比 `:id/` 之后的部分），旧接口不接受（它要求带包名）。
        assertTrue(
            runCatching {
                AutomationRule.fromJson(minimalJson().put("selectors", JSONArray(listOf(JSONObject().put("id", ":id/ok")))))
            }.isSuccess,
        )
        assertNull(AccessibilityAutomationPolicy.parseAction(actionParam("com.example.app", ":id/ok")))
        // 反向再钉一次：上面两组不是「全接受」或「全拒绝」的退化情形。
        assertNotNull(AccessibilityAutomationPolicy.parseAction(actionParam("com.example.app", "com.example.app:id/ok")))
        assertNull(AccessibilityAutomationPolicy.parseAction(actionParam("com.example.app", "com.example.app:id/")))
        // 两者都只判「形态」，跨包由调用方自己比对（旧接口就地比 packageName，规则引擎在匹配时比）。
        assertNull(AccessibilityAutomationPolicy.parseAction(actionParam("com.example.app", "com.other.app:id/ok")))
        assertNotNull(
            AutomationRule.fromJson(minimalJson().put("selectors", JSONArray(listOf(JSONObject().put("id", "com.other.app:id/ok"))))),
        )
    }

    /** 旧接口 `parseAction` 的最小参数：`selector` 必须恰好一个键，参数对象只允许 packageName/action/selector。 */
    private fun actionParam(packageName: String, viewId: String): String = JSONObject()
        .put("packageName", packageName)
        .put("action", "click")
        .put("selector", JSONObject().put("viewId", viewId))
        .toString()

    private fun minimalJson(): JSONObject = JSONObject()
        .put("id", "r1")
        .put("packageName", "com.example.app")
        .put("selectors", JSONArray(listOf(JSONObject("""{"text":"确定"}"""))))
        .put("action", JSONObject("""{"type":"click"}"""))

    private fun fullJson(): String = """
        {
          "id": "r-full",
          "packageName": "com.example.app",
          "enabled": false,
          "allowActivities": ["^com\\.example\\.app\\..*Activity$"],
          "denyActivities": ["Splash"],
          "matchDelayMs": 800,
          "maxActions": 3,
          "actionCoolDownMs": 1500,
          "resetOn": "screen",
          "selectors": [
            {"text": "确定", "id": ":id/confirm", "className": "android.widget.Button", "clickable": true, "enabled": true, "editable": false, "minWidth": 100, "minHeight": 40},
            {"textContains": "定", "textStartsWith": "确", "textEndsWith": "定", "desc": "确定按钮", "descContains": "确定"}
          ],
          "action": {"type": "swipe", "durationMs": 500, "direction": "down"}
        }
    """.trimIndent()
}
