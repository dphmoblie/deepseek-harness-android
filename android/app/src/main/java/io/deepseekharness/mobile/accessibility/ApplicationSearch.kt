package io.deepseekharness.mobile.accessibility

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用选择器的检索与排序规则：**纯逻辑 + 可注入的转写器**，不碰 PackageManager。
 *
 * 之所以单独拆出来（而不是留在 [InstalledApplications] 里）：
 *  1. 真机上的「搜不到」几乎都出在匹配规则上，规则必须能在 JVM 单测里逐条钉死，
 *     而 `Context`/`PackageManager` 在单测里是拿不到的；
 *  2. 拼音转写（[Transliterator]）在单测环境里不可用，所以转写函数由外部注入，
 *     单测传入一个可预期的桩，真机传入系统 ICU。
 *
 * 匹配规则（对应界面上那个搜索框的行为约定）：
 *  - **拼音与首字母**：「微信」→ `weixin` / `wx`；「支付宝」→ `zhifubao` / `zfb`；
 *    （`ü` 音节另补键盘打法：`吕` → `lu` / `lv`。需要 API 29+ 的系统 ICU，
 *    低版本自动退化成字面量匹配，见 [systemTransliterate]）
 *  - **多关键词、语序无关**：`wx 支付` 命中「微信支付」，`支付 wx` 同样命中；
 *    空格与连字符都算分隔符，`we chat` 与 `we-chat` 等价；
 *  - **大小写与空白**：大小写不敏感；全角空格、多余空白、连字符差异都不影响命中；
 *  - 全部关键词都命中才算命中；一个都没命中的条目由界面显示为「没有匹配的应用」。
 *
 * 排序规则：命中权重 → 拉丁转写（中文按拼音、英文按字母的字典序）→ 包名。
 * 「已勾选的应用排最前」不在这一层：原生不知道用户勾了什么（见 [page]）。
 */
internal object ApplicationSearch {

    /**
     * 选择器列表里的一行。`keywords` 与 `sortKey` 由 [installed] 一次性算好，
     * 分页时每页只做匹配与比较，不重复做拼音转写。
     *
     * `sortKey` 是**拉丁转写**（有拼音就用拼音，没有就用规范化标签，标签为空则用包名）：
     * 直接按标签码位排序的话，中文应用的顺序在用户看来毫无规律（「吕」会排到「计算器」后面）。
     */
    internal class AppEntry(
        val packageName: String,
        val label: String,
        val system: Boolean,
        val selectable: Boolean,
        val keywords: List<String>,
        val sortKey: String,
    )

    /** 匹配成败 + 排序权重；`score` 只决定顺序，不影响分页口径。 */
    internal class Hit(val entry: AppEntry, val score: Int)

    /**
     * 把整份应用清单编成可检索的条目：标签做规范化 + 转写，每台设备只做一次。
     *
     * @param transliterate 汉字转拉丁的函数；输出按空格切音节即可（见 [syllablesOf]）。
     */
    internal fun installed(
        applications: List<InstalledApplication>,
        transliterate: (String) -> String = ::systemTransliterate,
    ): List<AppEntry> = applications.map { application ->
        val label = application.label.take(MAX_LABEL_CHARS)
        val index = indexed(label, transliterate)
        AppEntry(
            packageName = application.packageName,
            label = label,
            system = application.system,
            selectable = application.selectable,
            keywords = index.keywords,
            // 标签为空（少数系统组件）时按包名排序，免得它们全挤在列表最前面。
            sortKey = index.sortKey.ifEmpty { normalize(application.packageName) },
        )
    }

    /** 桥接层交给 [installed] 的原始一行；只在这里定义一次，避免到处传四元组。 */
    internal class InstalledApplication(
        val packageName: String,
        val label: String,
        val system: Boolean,
        val selectable: Boolean,
    )

    /**
     * 检索 + 排序 + 分页，产出桥接层直接回给前端的 JSON。
     *
     * 排序：先按匹配权重（包名前缀 > 标签前缀 > 拼音/首字母前缀 > 子串），
     * 同权重按 [AppEntry.sortKey]（中文按拼音、英文按字母，即字典序）、再按包名，
     * **顺序与分页无关**（`offset` 只在这份完整顺序上切片）。
     * 已勾选的应用前置由界面侧做：原生不知道用户勾了什么，硬塞会让分页顺序不可预测。
     */
    internal fun page(entries: List<AppEntry>, query: String, offset: Int): JSONObject {
        val request = Request.of(query)
        val hits = entries.mapNotNull { entry -> match(entry, request) }
            .sortedWith(compareByDescending<Hit> { it.score }
                .thenBy { it.entry.sortKey }
                .thenBy { it.entry.packageName })
        val page = JSONArray()
        hits.drop(offset).take(InstalledApplications.PAGE_SIZE).forEach { hit ->
            val entry = hit.entry
            page.put(
                JSONObject().put("packageName", entry.packageName).put("label", entry.label)
                    .put("system", entry.system).put("selectable", entry.selectable),
            )
        }
        val end = (offset.toLong() + InstalledApplications.PAGE_SIZE).coerceAtMost(hits.size.toLong()).toInt()
        return JSONObject().put("apps", page).put("total", hits.size)
            .put("nextOffset", if (end < hits.size) end else JSONObject.NULL)
    }

    /** 标签关键词的上限：防止畸形标签把索引撑大（标签本身还有一道控制符过滤）。 */
    private const val MAX_LABEL_CHARS = 160

    /**
     * 拼音索引缓存。
     *
     * 真机上最贵的一步是转写，而**同一个标签会在每次按键时被重新请求**；
     * 这里按标签缓存。用 [ConcurrentHashMap] 而不是同步块：查询来自桥线程，将来并行调用也不互相阻塞。
     * 上限 [KEYWORD_CACHE_LIMIT] 条：装几千个应用的设备上也不会无限增长。
     */
    internal const val KEYWORD_CACHE_LIMIT = 2048
    private val indexCache = ConcurrentHashMap<String, Indexed>()

    /** 一个标签的检索形态：关键词 + 拉丁排序键。两者同源（一次转写），所以缓存一份。 */
    private class Indexed(val keywords: List<String>, val sortKey: String)

    internal fun keywords(label: String, transliterate: (String) -> String): List<String> =
        indexed(label, transliterate).keywords

    private fun indexed(label: String, transliterate: (String) -> String): Indexed {
        indexCache[label]?.let { return it }
        val computed = compute(label, transliterate)
        if (indexCache.size >= KEYWORD_CACHE_LIMIT) indexCache.clear()
        indexCache[label] = computed
        return computed
    }

    /** 单测用：缓存是进程级的，用例之间要能清干净。 */
    internal fun clearCache() = indexCache.clear()

    private fun compute(label: String, transliterate: (String) -> String): Indexed {
        val text = normalize(label)
        val keywords = mutableListOf<String>()
        if (text.isNotEmpty()) keywords += text
        val transliterated = transliterate(text)
        val syllables = syllablesOf(transliterated)
        if (syllables.isNotEmpty()) {
            keywords += pinyinForms(syllables)
            // 键盘上打不出 `ü`：`吕` 的拼音 `lü` 再补一份 `v` 写法（`lv`），否则用户按
            // 习惯打法（`lv`、`lvzhou`）搜不到「吕」「绿洲」这类应用。
            val typed = syllablesOf(transliterated.replace("u:", "v").replace('ü', 'v').replace('Ü', 'V'))
            if (typed != syllables) keywords += pinyinForms(typed)
        }
        // 每个关键词的紧凑形态（丢空格）。
        //
        // **为什么必须有**：查询侧 `Request.of` 按分隔符切词，标签侧的关键词一直**带着原始空格**：
        // 带空格的标签（`微信 Lite`、`We Chat`）就永远匹配不到连写查询（`wechat`），
        // 用户输对了名字还是空列表。与拼音音节同源的重复形态会由 `distinct()` 去重。
        //
        // 必须先 `map` 出副本再追加：`mapTo(keywords)` 会把结果写回被遍历的同一个
        // `ArrayList`，迭代器立刻抛 `ConcurrentModificationException`（单测里就是这么炸的）。
        val compacted = keywords.map { it.replace(" ", "") }
        keywords += compacted
        // 排序键：有拉丁转写就用转写（中文按拼音、英文按字母），否则退回标签本身。
        return Indexed(keywords.distinct(), syllables.joinToString("").ifEmpty { text })
    }

    /** 整串拼音（`weixin`）与首字母（`wx`）；只有一个音节时不生成首字母（「你」→ `n` 只是噪声）。 */
    private fun pinyinForms(syllables: List<String>): List<String> = buildList {
        add(syllables.joinToString(""))
        if (syllables.size > 1) add(syllables.joinToString("") { it.take(1) })
    }

    /**
     * 一次查询切出来的检索词。**空格与连字符都是分隔符**：`we-chat`、`we chat`、`we－chat`
     * 三种写法切出同一组词，因此写法差异不会让人搜不到。
     *
     * 为什么不把「去掉分隔符的紧凑形态」也当成一个词：那样 `we-chat` 必须同时命中
     * `wechat` 这一个词，而带空格的标签（`We Chat`）的关键词只有 `we chat`——
     * 结果就是「名字里带空格的应用，用带连字符的名字搜不到」。紧凑形态该由
     * 索引侧的关键词承担（见 [compute]），查询侧只负责**切词**。
     */
    private class Request private constructor(val tokens: List<String>) {
        companion object {
            fun of(raw: String): Request = Request(
                normalize(raw).replace('-', ' ').split(' ').filter { it.isNotEmpty() },
            )
        }
    }

    private fun match(entry: AppEntry, request: Request): Hit? {
        if (request.tokens.isEmpty()) return Hit(entry, 0)
        val pack = normalize(entry.packageName)
        var score = 0
        for (token in request.tokens) {
            val tokenScore = when {
                pack.startsWith(token) -> 5
                pack.contains(token) -> 4
                entry.keywords.any { it.isNotEmpty() && it.startsWith(token) } -> 3
                entry.keywords.any { it.contains(token) } -> 2
                else -> return null
            }
            score = score.coerceAtLeast(tokenScore)
        }
        return Hit(entry, score)
    }

    /**
     * 规范化：这一步决定「大小写/空白/连字符这类无关差异搜不到」是否成立。
     *
     * 顺序有意为之：先 NFD 拆出组合音符（**在转写之前**，这样 ICU 拿到的是无声调的汉字，
     * 输出里也不会再带声调符号），再统一全角空格与各种连字符，最后转小写。
     * 注意**不能**在这里丢掉空格或连字符：`wx 支付` 要靠空格切词，各种写法的连字符先收口成
     * 半角 `-`，再由 [Request.of] 统一当分隔符切开（切词只在那一个地方决策，这里只做归一）。
     */
    internal fun normalize(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val builder = StringBuilder(decomposed.length)
        decomposed.forEach { char ->
            when {
                // 控制符与双向控制符：来自畸形标签，不该进索引。
                char.isISOControl() && char != '\t' -> Unit
                char.code in 0x202A..0x202E || char.code in 0x2066..0x2069 -> Unit
                // 零宽字符（BOM、零宽空格/连接符）看不见却会挡住命中，一律抹掉。
                char.code == 0x200B || char.code == 0x200C || char.code == 0x200D || char.code == 0xFEFF -> Unit
                isCombiningMark(char) -> Unit
                char == '\u3000' || char == '\t' || char == '\n' || char == '\r' -> builder.append(' ')
                char == '－' || char == '–' || char == '—' || char == 'ー' || char == '‐' -> builder.append('-')
                else -> builder.append(char.lowercaseChar())
            }
        }
        return builder.toString().trim().replace(WHITESPACE, " ")
    }

    private val WHITESPACE = Regex(" +")

    private fun isCombiningMark(char: Char): Boolean {
        val type = Character.getType(char).toInt()
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * 汉字 → 拉丁音节。系统 ICU 的 `Han-Latin` 把「微信」转成 `wēi xìn`、把「吕」转成 `lü`，
     * 这里只保留干净的 a-z 音节；声调符号在 [normalize] 里已随 NFD 去掉，
     * 带分音符的 `ü` 走 `u:` 写法，两种形态都收口成 `lu`。
     * 非汉字（英文、数字）ICU 原样透传，正好让「微信 Lite」这类混排标签也能用拼音搜到。
     */
    private fun syllablesOf(transliterated: String): List<String> =
        normalize(transliterated).replace("u:", "u").split(' ')
            .map { syllable -> syllable.takeWhile { it in 'a'..'z' } }
            .filter { it.isNotEmpty() }

    /**
     * 系统 ICU 转写器。
     *
     * `android.icu.text.Transliterator` 是 **API 29（Android 10）** 才有的类，而 minSdk 是 26，
     * 所以这里必须做版本守卫，并且**守卫与调用要写在同一个表达式里**（lint 的 NewApi 看不到
     * 跨方法/跨作用域的守卫，本仓库在 RuntimeStorageDirs.kt 上吃过一次亏）。
     *
     * 守卫之外返回空串：搜索退化成标签与包名的字面量匹配（也就是改造前的行为），
     * 只是没有拼音/首字母——**不能**因此让整个列表读取失败。
     */
    internal fun systemTransliterate(text: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) IcuTransliterator.transliterate(text) else ""

    /**
     * ICU 转写的**唯一**落脚点：整块用 `@RequiresApi(29)` 标注，低版本设备永远不会加载它
     * （否则光是引用这个类就会 `NoClassDefFoundError`）。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private object IcuTransliterator {
        private val transliterator: Transliterator by lazy { Transliterator.getInstance("Han-Latin") }

        fun transliterate(text: String): String =
            runCatching { transliterator.transliterate(text) }.getOrDefault("")
    }
}
