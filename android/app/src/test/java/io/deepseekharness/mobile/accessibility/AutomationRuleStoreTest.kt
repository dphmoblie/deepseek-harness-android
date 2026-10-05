package io.deepseekharness.mobile.accessibility

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutomationRuleStore] 的持久化、限额与**坏数据自愈**。
 *
 * 用内存替身而不是真 SharedPreferences：这里要验证的每一条（超限拒绝、坏数据自愈、删除后不残留）
 * 挂在设备上都得先装应用、开权限、手工把偏好文件改坏才能复现一次。替身还让我可以精确构造
 * 「索引被人手工改过」「记录了一个不在索引里的槽位」这类真实世界会出现的脏数据。
 *
 * 每个用例都新建一份 [FakeStorage]：规则存储是全局单例 object，状态泄漏会让用例之间互相影响
 * （顺序一变就红）。
 */
class AutomationRuleStoreTest {

    @Test
    fun `保存后能读回且包名列表按字典序`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.b", listOf(rule("b1", "com.example.b")))
        AutomationRuleStore.savePackage(storage, "com.example.a", listOf(rule("a1", "com.example.a"), rule("a2", "com.example.a")))

        assertEquals(listOf("com.example.a", "com.example.b"), AutomationRuleStore.packages(storage))
        val read = AutomationRuleStore.readPackage(storage, "com.example.a")
        assertTrue(read is AutomationRuleStore.PackageRead.Ok)
        assertEquals(listOf("a1", "a2"), (read as AutomationRuleStore.PackageRead.Ok).rules.map { it.id })
        // 读回来的规则要能逐字段相等（不是只比 id）。
        assertEquals(rule("a1", "com.example.a"), read.rules.first())
    }

    @Test
    fun `没保存过的包读出空包而不是错误`() {
        val storage = FakeStorage()
        val read = AutomationRuleStore.readPackage(storage, "com.example.app")
        assertEquals(AutomationRuleStore.PackageRead.Ok(emptyList()), read)
        assertEquals(0, AutomationRuleStore.packageBytes(storage, "com.example.app"))
    }

    @Test
    fun `整包替换不会残留旧规则`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.app", listOf(rule("old1", "com.example.app"), rule("old2", "com.example.app")))
        AutomationRuleStore.savePackage(storage, "com.example.app", listOf(rule("new1", "com.example.app")))

        val read = AutomationRuleStore.readPackage(storage, "com.example.app") as AutomationRuleStore.PackageRead.Ok
        assertEquals(listOf("new1"), read.rules.map { it.id })
        // 覆盖写不能留下两个槽位（否则全局字节数会虚高、删除也删不干净）。
        assertEquals(1, AutomationRuleStore.stats(storage).packageCount)
    }

    @Test
    fun `保存空列表等价于删除该包`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.app", listOf(rule("r1", "com.example.app")))
        assertTrue(AutomationRuleStore.removePackage(storage, "com.example.app"))

        assertEquals(emptyList<String>(), AutomationRuleStore.packages(storage))
        assertEquals(AutomationRuleStore.PackageRead.Ok(emptyList()), AutomationRuleStore.readPackage(storage, "com.example.app"))
        AutomationRuleStore.savePackage(storage, "com.example.app", emptyList())
        assertEquals(emptyList<String>(), AutomationRuleStore.packages(storage))
    }

    @Test
    fun `删除不存在的包是幂等的`() {
        val storage = FakeStorage()
        assertFalse(AutomationRuleStore.removePackage(storage, "com.example.app"))
        AutomationRuleStore.savePackage(storage, "com.example.other", listOf(rule("o1", "com.example.other")))
        assertFalse(AutomationRuleStore.removePackage(storage, "com.example.app"))
        assertEquals(listOf("com.example.other"), AutomationRuleStore.packages(storage))
    }

    @Test
    fun `clear 返回被删掉的包名并清空全部数据`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.a", listOf(rule("a1", "com.example.a")))
        AutomationRuleStore.savePackage(storage, "com.example.b", listOf(rule("b1", "com.example.b")))

        assertEquals(listOf("com.example.a", "com.example.b"), AutomationRuleStore.clear(storage))
        assertEquals(emptyList<String>(), AutomationRuleStore.packages(storage))
        assertEquals(AutomationRuleStore.Stats(0, 0, 0, 40, 64 * 1024, 256 * 1024, 0), AutomationRuleStore.stats(storage))
    }

    // ---- 坏数据自愈 ----

    @Test
    fun `损坏的单个包被清除并给出可诊断原因且不影响其它包`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.bad", listOf(rule("bad1", "com.example.bad")))
        AutomationRuleStore.savePackage(storage, "com.example.good", listOf(rule("good1", "com.example.good")))
        storage.corruptPackageOf("com.example.bad")

        val read = AutomationRuleStore.readPackage(storage, "com.example.bad")
        assertTrue("应当是 Corrupt 结果", read is AutomationRuleStore.PackageRead.Corrupt)
        val corrupt = read as AutomationRuleStore.PackageRead.Corrupt
        assertEquals(AutomationRuleCodes.MALFORMED, corrupt.code)
        assertTrue("自愈标记必须为 true：${corrupt.repaired}", corrupt.repaired)
        assertTrue("应当给出中文原因：${corrupt.message}", corrupt.message.contains("JSON"))
        // 自愈后该包只剩「空包」，不会每次窗口变化都再炸一次。
        assertEquals(emptyList<String>(), AutomationRuleStore.packages(storage).filter { it == "com.example.bad" })
        // 另一个包不受影响——这是「破坏面收在一个包之内」的核心断言。
        val good = AutomationRuleStore.readPackage(storage, "com.example.good") as AutomationRuleStore.PackageRead.Ok
        assertEquals(listOf("good1"), good.rules.map { it.id })
    }

    @Test
    fun `包名与索引不一致时按损坏处理并清除`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.app", listOf(rule("r1", "com.example.app")))
        // 手工把索引里的包名改成另一个（模拟备份还原/手工编辑偏好文件）。
        storage.renameInIndex("com.example.app", "com.example.other")

        val read = AutomationRuleStore.readPackage(storage, "com.example.app")
        assertTrue(read is AutomationRuleStore.PackageRead.Corrupt)
        assertTrue((read as AutomationRuleStore.PackageRead.Corrupt).message.contains("不一致"))
    }

    @Test
    fun `索引里的坏行被忽略而不是让读取崩掉`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.app", listOf(rule("r1", "com.example.app")))
        // 混入没有分隔符 / 缺包名 / 缺 json 的垃圾行。
        storage.taintIndex { raw -> "$raw\nnot-a-valid-line\np9\t\np8\tcom.example.x\n" }

        // 好的那条仍然可读，垃圾行不产生幻影包名。
        val read = AutomationRuleStore.readPackage(storage, "com.example.app") as AutomationRuleStore.PackageRead.Ok
        assertEquals(listOf("r1"), read.rules.map { it.id })
        assertEquals(listOf("com.example.app"), AutomationRuleStore.packages(storage))
    }

    @Test
    fun `整份索引不是空串但没有任何合法行时读出空包`() {
        val storage = FakeStorage()
        storage.taintIndex { "garbage" }
        assertEquals(AutomationRuleStore.PackageRead.Ok(emptyList()), AutomationRuleStore.readPackage(storage, "com.example.app"))
        assertEquals(emptyList<String>(), AutomationRuleStore.packages(storage))
    }

    @Test
    fun `规则内容超长时按损坏自愈而不是抛异常`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.app", listOf(rule("r1", "com.example.app")))
        // 记录数超过每应用上限：读取时按损坏处理（写入侧已经拦过，这里是防手工改/降级安装）。
        storage.replaceRulesInIndex("com.example.app") { json ->
            val array = JSONArray()
            (1..41).forEach { index -> array.put(JSONObject(json.getJSONObject(0).toString().replace("\"r1\"", "\"r$index\""))) }
            array
        }
        val read = AutomationRuleStore.readPackage(storage, "com.example.app")
        assertTrue(read is AutomationRuleStore.PackageRead.Corrupt)
        assertEquals(AutomationRuleCodes.MALFORMED, (read as AutomationRuleStore.PackageRead.Corrupt).code)
    }

    // ---- 限额：拒绝而不是截断 ----

    @Test
    fun `超过 40 条规则时拒绝写入且原数据不被破坏`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.app", listOf(rule("keep", "com.example.app")))
        val tooMany = (1..41).map { rule("r$it", "com.example.app") }

        val error = assertThrows(AutomationRuleException::class.java) {
            AutomationRuleStore.savePackage(storage, "com.example.app", tooMany)
        }
        assertEquals(AutomationRuleCodes.FIELD_INVALID, error.code)
        assertTrue(error.message!!.contains("40"))
        // 关键：被拒的写入不能把已经存好的规则弄丢。
        val read = AutomationRuleStore.readPackage(storage, "com.example.app") as AutomationRuleStore.PackageRead.Ok
        assertEquals(listOf("keep"), read.rules.map { it.id })
    }

    @Test
    fun `重复的规则标识被拒绝`() {
        val storage = FakeStorage()
        val error = assertThrows(AutomationRuleException::class.java) {
            AutomationRuleStore.savePackage(
                storage,
                "com.example.app",
                listOf(rule("dup", "com.example.app"), rule("dup", "com.example.app")),
            )
        }
        assertEquals(AutomationRuleCodes.FIELD_INVALID, error.code)
        assertTrue(error.message!!.contains("dup"))
        assertEquals(emptyList<String>(), AutomationRuleStore.packages(storage))
    }

    @Test
    fun `规则的包名与目标包不一致时被拒绝`() {
        val storage = FakeStorage()
        val error = assertThrows(AutomationRuleException::class.java) {
            AutomationRuleStore.savePackage(storage, "com.example.app", listOf(rule("r1", "com.example.other")))
        }
        assertEquals(AutomationRuleCodes.PACKAGE_INVALID, error.code)
    }

    @Test
    fun `不可自动化的包名在读写入口都被拒绝`() {
        val storage = FakeStorage()
        listOf("com.android.systemui", "android", "app", "").forEach { packageName ->
            assertThrows("保存 $packageName", AutomationRuleException::class.java) {
                AutomationRuleStore.savePackage(storage, packageName, listOf(rule("r1", "com.example.app")))
            }
            assertThrows("读取 $packageName", AutomationRuleException::class.java) {
                AutomationRuleStore.readPackage(storage, packageName)
            }
        }
    }

    @Test
    fun `单包 JSON 超过 64 KiB 时拒绝写入`() {
        val storage = FakeStorage()
        // 每条规则塞 8 个 200 字的文本选择器，构造出确定超过 64 KiB 的一包。
        val heavy = (1..40).map { index ->
            rule("r$index", "com.example.app").copy(
                selectors = (1..8).map { Selector(text = "文".repeat(200) + index) },
            )
        }
        val error = assertThrows(AutomationRuleException::class.java) {
            AutomationRuleStore.savePackage(storage, "com.example.app", heavy)
        }
        assertEquals(AutomationRuleCodes.FIELD_INVALID, error.code)
        assertTrue("应报出字节数：${error.message}", error.message!!.contains("字节"))
        assertEquals(emptyList<String>(), AutomationRuleStore.packages(storage))
    }

    @Test
    fun `全局超过 256 KiB 时拒绝写入而先前数据保留`() {
        val storage = FakeStorage()
        // 每个包约 60 KiB（30 条 × 3 个 200 字符选择器 = 61792 字节，仍压在单包 64 KiB 以内），
        // 写到第 5 个包时总量必然超过 256 KiB。
        // 注意别用 40 条 × 3 选择器：那是 82392 字节，先被**单包**上限拒掉，测不到全局上限。
        val perPackage = (1..30).map { index ->
            rule("r$index", "com.example.app").copy(
                selectors = (1..3).map { Selector(text = "字".repeat(200)) },
            )
        }
        val saved = ArrayList<String>()
        var rejected = 0
        (1..6).forEach { index ->
            val packageName = "com.example.app$index"
            val rules = perPackage.map { it.copy(id = "${it.id}-$index", packageName = packageName) }
            try {
                AutomationRuleStore.savePackage(storage, packageName, rules)
                saved += packageName
            } catch (_: AutomationRuleException) {
                rejected++
            }
        }
        assertTrue("应当有包被全局上限拒绝", rejected > 0)
        assertTrue("应当先成功写入若干包", saved.size >= 4)
        // 被拒之后总量仍在限额内，且已写入的包完整可读。
        val stats = AutomationRuleStore.stats(storage)
        assertTrue("总量不应超过上限：${stats.storedBytes}", stats.storedBytes <= AutomationRuleLimits.MAX_TOTAL_JSON_BYTES)
        assertEquals(saved.size, stats.packageCount)
        saved.forEach { packageName ->
            val read = AutomationRuleStore.readPackage(storage, packageName) as AutomationRuleStore.PackageRead.Ok
            assertEquals(30, read.rules.size)
        }
    }

    @Test
    fun `重复保存同一个包不会因为旧数据占位而把自己顶爆`() {
        val storage = FakeStorage()
        // 每条规则的文本字段顶到 200 字符上限，40 条刚好压在单包 64 KiB 以内：
        // 这样「反复保存」若把旧条目也算进总量，就会被自己顶爆而失败。
        val rules = (1..40).map { index ->
            rule("r$index", "com.example.app").copy(selectors = listOf(Selector(text = "字".repeat(200))))
        }
        repeat(3) { AutomationRuleStore.savePackage(storage, "com.example.app", rules) }
        val read = AutomationRuleStore.readPackage(storage, "com.example.app") as AutomationRuleStore.PackageRead.Ok
        assertEquals(40, read.rules.size)
        assertEquals(1, AutomationRuleStore.stats(storage).packageCount)
    }

    // ---- 落盘格式（这是曾经踩过的坑，固化成断言，防止再回归） ----

    /**
     * 索引必须是「一个包一行、行内两个字段分隔都是**真正的制表符**」。
     *
     * 曾经把分隔符写成字符串 `"\\n"`（两个字符：反斜杠 + n）而读取侧按真正的换行切分，
     * 结果是写进去了再也读不回来，还会被当成损坏数据清掉——落盘格式一旦靠「看起来一样」来约定
     * 就会重演。这里直接对着**字符码**断言，任何一处退化成转义序列都会立刻红。
     */
    @Test
    fun `索引的落盘格式是一个包一行且分隔符是真控制字符`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.a", listOf(rule("a1", "com.example.a")))
        AutomationRuleStore.savePackage(storage, "com.example.b", listOf(rule("b1", "com.example.b")))

        val raw = storage.values[AutomationRuleStore.KEY_INDEX] ?: error("索引没有落盘")
        val lines = raw.split('\n')
        assertEquals("一个包一行", 2, lines.size)
        lines.forEachIndexed { index, line ->
            val cells = line.split('\t')
            assertEquals("第 ${index + 1} 行应当是「槽位键 / 包名 / 规则 JSON」三段", 3, cells.size)
            assertEquals("第 ${index + 1} 行的槽位键", "p$index", cells[0])
            assertEquals("第 ${index + 1} 行的包名", if (index == 0) "com.example.a" else "com.example.b", cells[1])
            assertTrue("第 ${index + 1} 行正文应当是规则 JSON：${cells[2]}", cells[2].startsWith("["))
            // 正文本身必须是可解析的 JSON：分隔符吞掉半个字段时这里立刻红。
            assertTrue(org.json.JSONArray(cells[2]).length() >= 1)
        }
        assertFalse("分隔符不能是反斜杠转义序列", raw.contains("\\n") || raw.contains("\\t"))

        // 落盘内容必须能被读回（同一份数据的两端约定一致）。
        assertEquals(listOf("com.example.a", "com.example.b"), AutomationRuleStore.packages(storage))
    }

    // ---- 统计 ----
    @Test
    fun `统计给出包数规则数与字节占用`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.a", listOf(rule("a1", "com.example.a"), rule("a2", "com.example.a")))
        AutomationRuleStore.savePackage(storage, "com.example.b", listOf(rule("b1", "com.example.b")))

        val stats = AutomationRuleStore.stats(storage)
        assertEquals(2, stats.packageCount)
        assertEquals(3, stats.ruleCount)
        assertEquals(0, stats.corruptPackages)
        assertTrue("应当统计到实际占用：${stats.storedBytes}", stats.storedBytes > 0)
        assertTrue("占用应当与单包统计吻合", stats.storedBytes >= AutomationRuleStore.packageBytes(storage, "com.example.a"))
    }

    @Test
    fun `统计能数出坏包但不因此崩掉`() {
        val storage = FakeStorage()
        AutomationRuleStore.savePackage(storage, "com.example.a", listOf(rule("a1", "com.example.a")))
        AutomationRuleStore.savePackage(storage, "com.example.b", listOf(rule("b1", "com.example.b")))
        storage.corruptPackageOf("com.example.b")

        val stats = AutomationRuleStore.stats(storage)
        assertEquals(1, stats.ruleCount)
        assertEquals(1, stats.corruptPackages)
    }

    @Test
    fun `单包字节数与实际保存内容一致`() {
        val storage = FakeStorage()
        val rules = listOf(rule("r1", "com.example.app"))
        AutomationRuleStore.savePackage(storage, "com.example.app", rules)
        val expected = jsonUtf8Bytes(AutomationRuleStore.renderRules(rules))
        assertEquals(expected, AutomationRuleStore.packageBytes(storage, "com.example.app"))
    }

    private fun rule(id: String, packageName: String): AutomationRule = AutomationRule(
        id = id,
        packageName = packageName,
        selectors = listOf(Selector(text = "确定")),
        action = AutomationAction(type = "click"),
    )

    /**
     * 内存替身。除了基础的读写，还提供几个「把数据弄坏」的入口——真实世界里的坏数据正是这样来的：
     * 备份还原、手工编辑偏好文件、旧版本写入的格式。
     */
    private class FakeStorage : AutomationRulePackageStorage {
        val values = linkedMapOf<String, String>()

        override fun read(key: String): String? =
            if (key == AutomationRuleStore.KEY_INDEX) values[key] else null

        override fun write(key: String, value: String) {
            values[key] = value
        }

        override fun clear() {
            values.clear()
        }

        /** 让某个包的规则 JSON 变成非法内容（保留包名与分隔符，模拟内容损坏）。 */
        fun corruptPackageOf(packageName: String) {
            values[AutomationRuleStore.KEY_INDEX] = mutateLines { line ->
                if (line.contains(packageName)) {
                    line.substringBeforeLast('\t') + "\t{not json"
                } else {
                    line
                }
            }
        }

        /**
         * 制造「索引声明的包名与规则自带包名不一致」：**只改表头**（槽位键与规则 JSON 之间的那段）。
         *
         * 不能用整串 `replace`：那会把规则 JSON 里的 `packageName` 一起改掉，表头与正文仍然自洽，
         * 测试就变成在验证另一个场景（而且整行替换后连包名都查不到了）。
         */
        fun renameInIndex(from: String, to: String) {
            values[AutomationRuleStore.KEY_INDEX] = mutateLines { line ->
                val fields = line.split('\t')
                if (fields.size < 3 || fields[1] != from) return@mutateLines line
                (fields.toMutableList().also { it[1] = to }).joinToString("\t")
            }
        }

        fun taintIndex(transform: (String) -> String) {
            values[AutomationRuleStore.KEY_INDEX] = transform(values[AutomationRuleStore.KEY_INDEX] ?: "")
        }

        /** 把某个包的规则数组换成构造出来的数组（用于制造「超过每应用上限」的持久化数据）。 */
        fun replaceRulesInIndex(packageName: String, transform: (JSONArray) -> JSONArray) {
            values[AutomationRuleStore.KEY_INDEX] = mutateLines { line ->
                if (!line.contains(packageName)) return@mutateLines line
                val separator = line.lastIndexOf('\t')
                val head = line.substring(0, separator + 1)
                head + transform(JSONArray(line.substring(separator + 1))).toString()
            }
        }

        private fun mutateLines(transform: (String) -> String): String =
            (values[AutomationRuleStore.KEY_INDEX] ?: "").split('\n').joinToString("\n", transform = transform)
    }
}
