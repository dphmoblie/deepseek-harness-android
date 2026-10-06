package io.deepseekharness.mobile.accessibility

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 应用选择器的检索规则单测（对应 [ApplicationSearch] 的匹配与排序口径）。
 *
 * 拼音转写在单测环境里拿不到系统 ICU，所以用一个**可控的桩**：桩的输出形态
 * （音节之间空格、带声调符号、ü 写成 `u:`）刻意与 ICU 的 `Han-Latin` 一致，
 * 于是 [ApplicationSearch.normalize] 与音节切分走的是和真机同一条路径，
 * 只有「汉字 → 拉丁」这一步被替换掉。
 */
class ApplicationSearchTest {

    /** 与 ICU `Han-Latin` 同形态的桩：`微信` → `wēi xìn`，`吕` → `lu:ˇ`。 */
    private val pinyin = mapOf(
        '微' to "wēi", '信' to "xìn", '支' to "zhī", '付' to "fù", '宝' to "bǎo",
        '计' to "jì", '算' to "suàn", '器' to "qì", '吕' to "lu:ˇ",
    )

    private fun stubTransliterate(text: String): String =
        text.map { char -> pinyin[char] ?: char.toString() }.joinToString(" ")

    private val wechat = ApplicationSearch.InstalledApplication("com.tencent.mm", "微信", false, true)
    private val wechatPay = ApplicationSearch.InstalledApplication("com.tencent.mm.pay", "微信支付", false, true)
    private val alipay = ApplicationSearch.InstalledApplication("com.eg.android.AlipayGphone", "支付宝", false, true)
    private val calculator = ApplicationSearch.InstalledApplication("com.android.calculator2", "计算器", true, false)
    private val gmail = ApplicationSearch.InstalledApplication("com.google.android.gm", "Gmail", false, true)
    private val lv = ApplicationSearch.InstalledApplication("com.example.lv", "吕", false, true)
    // 包名刻意与标签无关：`wechat`、`we-chat` 只能靠关键词命中，不能靠包名子串凑巧命中。
    private val weChat = ApplicationSearch.InstalledApplication("com.example.im", "We Chat", false, true)

    private val applications = listOf(wechat, wechatPay, alipay, calculator, gmail, lv, weChat)

    @Before
    fun clearIndexCache() = ApplicationSearch.clearCache()

    private fun entries() = ApplicationSearch.installed(applications, ::stubTransliterate)

    private fun search(query: String, offset: Int = 0): Pair<List<String>, JSONObject> {
        val json = ApplicationSearch.page(entries(), query, offset)
        val apps = json.getJSONArray("apps")
        return (0 until apps.length()).map { apps.getJSONObject(it).getString("packageName") } to json
    }

    private fun packageNames(query: String, offset: Int = 0): List<String> = search(query, offset).first

    /** 在给定的条目清单上查询（降级路径的用例要喂自己那份条目）。 */
    private fun packageNamesOf(entries: List<ApplicationSearch.AppEntry>, query: String): List<String> {
        val apps = ApplicationSearch.page(entries, query, 0).getJSONArray("apps")
        return (0 until apps.length()).map { apps.getJSONObject(it).getString("packageName") }
    }

    /** 改前：筛选只做 `packageName.contains || label.contains`，中文名用拼音一个都搜不到。 */
    @Test
    fun `中文应用名能用整串拼音搜到`() {
        // 拼音是关键词前缀，所以「微信支付」（`weixinzhifu`）同样命中——子串匹配是有意的，
        // 谁排前面由排序键决定（`weixin` < `weixinzhifu`）。
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.pay"), packageNames("weixin"))
        assertEquals(listOf("com.eg.android.AlipayGphone"), packageNames("zhifubao"))
        // 大小写不敏感。
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.pay"), packageNames("WeiXin"))
    }

    /**
     * 真机反馈（0.2.11）：无障碍白名单里输入 `gkd` 或 `GKD` 都搜不到「GKD」，用户最后是滚动列表找到的。
     *
     * 规则侧必须命中，所以把这条钉成用例：包名 `li.songe.gkd` 含子串 `gkd`、标签「GKD」大小写不敏感。
     * 这条过了就意味着「搜不到」不是匹配规则的问题，而要往设备侧的应用列表查。
     */
    @Test
    fun `包名子串与英文标签都能搜到 GKD`() {
        val gkd = ApplicationSearch.installed(
            listOf(ApplicationSearch.InstalledApplication("li.songe.gkd", "GKD", false, true)),
            ::stubTransliterate,
        )
        assertEquals(listOf("li.songe.gkd"), packageNamesOf(gkd, "gkd"))
        assertEquals(listOf("li.songe.gkd"), packageNamesOf(gkd, "GKD"))
        assertEquals(listOf("li.songe.gkd"), packageNamesOf(gkd, "songe"))
    }

    /** 改前：首字母不是标签子串，`wx` 搜不到「微信」。 */
    @Test
    fun `中文应用名能用首字母搜到`() {
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.pay"), packageNames("wx"))
        assertEquals(listOf("com.eg.android.AlipayGphone"), packageNames("zfb"))
        // 单音节标签不生成首字母：`lv` 靠拼音的 v 写法命中，`l` 那种单字母噪声不参与。
        assertEquals(listOf("com.example.lv"), packageNames("lv"))
    }

    /**
     * API 29 以下的降级路径：`android.icu.text.Transliterator` 是 API 29 才有的类，
     * 低版本上 [ApplicationSearch.systemTransliterate] 返回空串，搜索退化成字面量匹配。
     *
     * 这里**不**去碰真机的 ICU：直接喂一个恒返回空串的转写器，等价于低版本设备上的行为，
     * 钉住的是「没有拼音时，中文标签与包名仍然搜得到，且不会抛异常」。
     */
    @Test
    fun `低于 API 29 时降级为字面量匹配但不影响中文标签与包名`() {
        val degraded = ApplicationSearch.installed(applications) { "" }
        // 中文标签按字面量命中，包名照旧。
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.pay"), packageNamesOf(degraded, "微信"))
        assertEquals(listOf("com.google.android.gm"), packageNamesOf(degraded, "gmail"))
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.pay"), packageNamesOf(degraded, "com.tencent"))
        // 只少了拼音这一层：拼音查询与首字母查询在低版本上确实命中不了（这是有意的降级）。
        assertEquals(emptyList<String>(), packageNamesOf(degraded, "weixin"))
        assertEquals(emptyList<String>(), packageNamesOf(degraded, "wx"))
        // 空查询仍然给出完整字典序列表，说明降级只影响匹配、不影响排序与分页。
        assertEquals(7, ApplicationSearch.page(degraded, "", 0).getInt("total"))
    }

    /** 桩的 `u:ˇ` 与真机 ICU 的 `lü` 都该收口成 `lu`，并补一份键盘打法 `lv`。 */
    @Test
    fun `转写里的声调符号与 ü 的两种写法都会收口`() {
        assertTrue(ApplicationSearch.keywords("吕", ::stubTransliterate).contains("lu"))
        assertTrue(ApplicationSearch.keywords("吕") { "lü" }.contains("lu"))
        // 键盘上没有 `ü`，用户会打 `lv`；两种写法都必须能搜到。
        assertTrue(ApplicationSearch.keywords("吕", ::stubTransliterate).contains("lv"))
        assertTrue(ApplicationSearch.keywords("吕") { "lü" }.contains("lv"))
        assertEquals("lu:ˇ", ApplicationSearch.normalize(stubTransliterate("吕")))
    }

    /** 表驱动：语序无关、每个关键词都必须命中、空白形态无关。 */
    @Test
    fun `多关键词与语序无关且必须全部命中`() {
        val cases = listOf(
            // 空查询按字典序（拉丁转写）：gmail < jisuanqi < lu < wechat < weixin < weixinzhifu < zhifubao。
            // 改前按标签码位排，中文顺序在用户看来毫无规律（「吕」会排到「计算器」后面）。
            "" to listOf(
                "com.google.android.gm", "com.android.calculator2", "com.example.lv",
                "com.example.im", "com.tencent.mm", "com.tencent.mm.pay", "com.eg.android.AlipayGphone",
            ),
            "微信 支付" to listOf("com.tencent.mm.pay"),
            "支付 微信" to listOf("com.tencent.mm.pay"),
            "wx 支付" to listOf("com.tencent.mm.pay"),
            "wm 微信" to emptyList(),          // 两个词都得命中；`wm` 谁都不命中
            "微信 不存在" to emptyList(),
            "  微信   支付  " to listOf("com.tencent.mm.pay"),
        )
        cases.forEach { (query, expected) -> assertEquals("查询「$query」", expected, packageNames(query)) }
    }

    /** 全角空格、连字符差异、前后空白都不该让人搜不到。 */
    @Test
    fun `空白与连字符的无关差异不影响命中`() {
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.pay"), packageNames("  微信  "))
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.pay"), packageNames("微信\u3000"))
        // 全角空格切词：`微信　支付` 与半角写法等价。
        assertEquals(listOf("com.tencent.mm.pay"), packageNames("微信\u3000支付"))
        // 连字符与空格等价切词：`we-chat` 与 `we chat` 切出同一组词，都命中标签 `We Chat`。
        assertEquals(listOf("com.example.im"), packageNames("we-chat"))
        assertEquals(listOf("com.example.im"), packageNames("we chat"))
        // 全角连字符同样归一到半角。
        assertEquals(listOf("com.example.im"), packageNames("we－chat"))
    }

    /**
     * 带空格的标签两种形态都能搜到。
     *
     * 这里钉住的是 [ApplicationSearch] 里「关键词的紧凑形态」这一步：查询侧只按分隔符切词，
     * 如果索引里只有带空格的 `we chat`（`We Chat` 的标签形态），那么「名字里本来就带空格」
     * 的应用用连写形态（`wechat`）就搜不到——真机上表现为「明明装了这个应用，输对了名字还是空列表」。
     */
    @Test
    fun `带空格的标签在紧凑与分词两种形态下都能命中`() {
        assertEquals(listOf("com.example.im"), packageNames("we chat"))
        assertEquals(listOf("com.example.im"), packageNames("we-chat"))
        assertEquals(listOf("com.example.im"), packageNames("wechat"))
        assertEquals(listOf("com.example.im"), packageNames("WE CHAT"))
        // 单独的分词也要能命中：`we` 与 `chat` 各自都是这个标签的子串。
        assertEquals(listOf("com.example.im"), packageNames("chat"))
        assertTrue(ApplicationSearch.keywords("We Chat", ::stubTransliterate)
            .containsAll(listOf("we chat", "wechat")))
    }

    @Test
    fun `包名与英文标签按字面量匹配`() {
        assertEquals(listOf("com.google.android.gm"), packageNames("gmail"))
        assertEquals(listOf("com.google.android.gm"), packageNames("GMAIL"))
        assertEquals(listOf("com.google.android.gm"), packageNames("google.android.gm"))
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.pay"), packageNames("com.tencent"))
        assertEquals(emptyList<String>(), packageNames("com.tencent.mm.reader"))
    }

    /** 排序：命中权重 → 拉丁转写排序键（中文按拼音、英文按字母）→ 包名；完整顺序与 offset 无关。 */
    @Test
    fun `排序稳定且分页只是切片`() {
        val ordered = packageNames("")
        val repeats = List(3) { packageNames("") }
        repeats.forEach { assertEquals(ordered, it) }

        // 包名命中权重（5）高于标签命中（3）：「支付」只命中标签，整串拼音「zhifubao」同样只命中标签，
        // 而「微信」是标签前缀——三者在同一页里的先后由权重与标签顺序共同决定，这里只钉住稳定性。
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.pay"), packageNames("微信"))
        // 两个词的紧凑形态也参与命中：`微信支付` 作为整体只出现在第二个应用的关键词里。
        assertEquals(listOf("com.tencent.mm.pay"), packageNames("微信支付 支付"))

        // offset 前进不改变顺序：第二页是第一份顺序的切片。
        assertEquals(ordered.drop(2), packageNames("", 2))
    }

    @Test
    fun `分页给出 total 与 nextOffset`() {
        val many = (1..250).map {
            ApplicationSearch.InstalledApplication(
                "com.example.app$it",
                "App ${it.toString().padStart(3, '0')}",
                false,
                true,
            )
        }
        val all = ApplicationSearch.installed(many, ::stubTransliterate)

        val first = ApplicationSearch.page(all, "", 0)
        assertEquals(100, first.getJSONArray("apps").length())
        assertEquals(250, first.getInt("total"))
        assertEquals(100, first.getInt("nextOffset"))

        val second = ApplicationSearch.page(all, "", 100)
        assertEquals(100, second.getJSONArray("apps").length())
        assertEquals(200, second.getInt("nextOffset"))

        val third = ApplicationSearch.page(all, "", 200)
        assertEquals(50, third.getJSONArray("apps").length())
        assertTrue(third.isNull("nextOffset"))

        // 越界 offset 返回空页而不是抛错，「加载更多」不会把列表清空。
        val beyond = ApplicationSearch.page(all, "", 999)
        assertEquals(0, beyond.getJSONArray("apps").length())
        assertEquals(250, beyond.getInt("total"))
        assertTrue(beyond.isNull("nextOffset"))

        // 分页不重复也不丢项：三页拼起来正好是完整顺序。
        // 这些标签的排序键都算成 `app`（桩逐字符转写），同键再按**包名字典序**排，
        // 所以顺序不是数字序（`com.example.app100` 排在 `com.example.app11` 前面）——
        // 这里用「三页拼起来 == 整份字典序」钉住，而不是写死一两个包名。
        val collected = listOf(first, second, third).flatMap { json ->
            val apps = json.getJSONArray("apps")
            (0 until apps.length()).map { apps.getJSONObject(it).getString("packageName") }
        }
        assertEquals(many.map { it.packageName }.sorted(), collected)
    }

    @Test
    fun `空查询返回全部条目并保留系统标记`() {
        val json = ApplicationSearch.page(entries(), "  ", 0)
        assertEquals(7, json.getInt("total"))
        val apps = json.getJSONArray("apps")
        val calculatorJson = (0 until apps.length())
            .map { apps.getJSONObject(it) }
            .single { it.getString("packageName") == "com.android.calculator2" }
        assertTrue(calculatorJson.getBoolean("system"))
        assertFalse(calculatorJson.getBoolean("selectable"))
        assertEquals("计算器", calculatorJson.getString("label"))
    }

    @Test
    fun `拼音索引按标签缓存且不改变结果`() {
        val first = ApplicationSearch.keywords("微信", ::stubTransliterate)
        assertEquals(listOf("微信", "weixin", "wx"), first)
        // 第二次即使换个桩（本该算出别的东西）也走缓存，证明缓存真的在挡。
        assertSame(first, ApplicationSearch.keywords("微信") { "" })

        ApplicationSearch.clearCache()
        // 清空后换成空转写，只剩标签本身——拼音是缓存里带出来的，不是每次都重算。
        assertEquals(listOf("微信"), ApplicationSearch.keywords("微信") { "" })
    }

    @Test
    fun `缓存到上限后清空而不是无限增长`() {
        repeat(ApplicationSearch.KEYWORD_CACHE_LIMIT) { index ->
            ApplicationSearch.keywords("label-$index", ::stubTransliterate)
        }
        val cached = ApplicationSearch.keywords("微信", ::stubTransliterate)
        assertEquals(listOf("微信", "weixin", "wx"), cached)
        // 再塞一条会越过上限触发整体清空：这一条仍在，说明写入没有被上限挡住。
        ApplicationSearch.keywords("再 清", ::stubTransliterate)
        assertNotNull(ApplicationSearch.keywords("再 清", ::stubTransliterate))
    }

    @Test
    fun `规范化抹平大小写与全角形态、抹掉零宽与双向控制符，但保留空格`() {
        assertEquals("wx 支付", ApplicationSearch.normalize("  WX\u3000支付 "))
        assertEquals("we-chat", ApplicationSearch.normalize("we－chat"))
        assertEquals("gmail", ApplicationSearch.normalize("ＧＭＡＩＬ"))
        assertEquals("微信", ApplicationSearch.normalize("\u202a微信\u202c"))
        assertEquals("", ApplicationSearch.normalize("\u200b \t"))
        assertEquals(emptyList<String>(), ApplicationSearch.keywords("", ::stubTransliterate))
    }
}
