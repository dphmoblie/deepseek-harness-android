package io.deepseekharness.mobile.runtime

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.ByteArrayInputStream

/**
 * `$DSH_HOME/.credentials.yaml`（访客内 `/root/.dsh/.credentials.yaml`）的**保序**读取与并写。
 *
 * ## 为什么需要它
 *
 * pinned dsh 的凭据分层（`@deepseek-ai/dsh-credentials-local` 的模块注释与 `resolve()`）是：
 *
 * ```text
 * 继承的进程环境（只读，优先）
 * > $DSH_HOME/.credentials.yaml（provider 管理的可写文档）
 * > <调用目录>/.env
 * > $DSH_HOME/.env
 * ```
 *
 * 也就是说：**不设同名环境变量时，dsh 就从它自己的凭据文档读取**。
 * 于是模型凭据的正确投递方式是「并写这份文档 + argv 里一个赋值都不出现」，
 * 而不是「把取值塞进环境/命令行」。这份文档本来就是官方前端「模型页」写入的那一份，
 * 也已经在 [RuntimePreservePolicy] 的运行时保留白名单里。
 *
 * ## 安全约束（与 dsh 侧一致，任何一条不满足就拒绝写）
 *
 *  - 文档必须是 `version: 1` 的映射，顶层只允许 `version` / `refs` / `records`；
 *  - `refs` 的键必须是合法凭据引用名（与环境变量名同文法），取值必须是**非空**字符串；
 *  - 文档里**已存在**的 `records` 段（OAuth 授权记录等）与所有注释、空行、格式一律**逐行原样保留**：
 *    本对象只替换/追加 `refs` 段里的行，绝不重建整份文档 —— 手写 YAML 往返是丢数据的最短路径；
 *  - 任何「本对象不敢改」的形态（未知顶层键、块标量、流式集合、锚点、重复键、CRLF、超限）
 *    一律返回 `null`，由调用方回落到 0600 环境文件路径，而不是猜 dsh 会怎么解析它。
 *
 * 本对象是**纯文本**处理（不接触文件系统、不依赖 Android API），便于在 JVM 单测里穷举。
 */
internal object HarnessCredentialsDocument {
    /** 文档上限；[HarnessCredentialStatusReader] 的读入上限与之同源。 */
    const val MAX_DOCUMENT_BYTES = 256 * 1024

    /** `refs` 条目数上限：凭据文档不该长成一份大表，超限即视为不受控输入。 */
    private const val MAX_REFS = 256

    /** 新建或追加 `refs` 条目时的默认缩进；dsh 自己写出来的就是这个缩进。 */
    private const val DEFAULT_REF_INDENT = 2

    /** 顶层条目：行首无缩进、键为 POSIX 标识符。 */
    private val TOP_LEVEL_ENTRY = Regex("^([A-Za-z_][A-Za-z0-9_]*):(.*)$")

    /** `refs` 段里的条目：必须有缩进，且冒号后必须是空格/制表符或行尾（否则整行是单个纯量）。 */
    private val REF_ENTRY = Regex("^ +([A-Za-z_][A-Za-z0-9_]*):(?:[ \\t]+(.*))?$")

    /**
     * 不能出现在纯量开头的指示符。
     *
     * `|` / `>` 是块标量，`[` / `{` 是流式集合，`&` / `*` / `!` / `%` / `@` / `` ` `` 是锚点、标签与保留符，
     * `?` / `:` / `,` / `#` 在行首有结构含义。它们都意味着「这一行不是一个能直接替换掉的键值对」。
     */
    private const val PLAIN_SCALAR_INDICATORS = "|>[&*!%@`?,:#"

    private val TOP_LEVEL_KEYS = setOf("version", "refs", "records")

    private val LOAD_SETTINGS = LoadSettings.builder()
        .setLabel("Harness credentials document")
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(0)
        .setCodePointLimit(MAX_DOCUMENT_BYTES)
        .build()

    /**
     * 严格解析出的 `refs` 引用与取值。
     *
     * @param text 文档原文。
     * @return 引用名 → 取值；空白文档返回空映射；**不是本对象认识的形态时返回 `null`**。
     *   返回值含取值，只允许用于比对与写盘，绝不允许写进日志或诊断。
     */
    fun refs(text: String): Map<String, String>? {
        if (text.isBlank()) return emptyMap()
        val document = try {
            Load(LOAD_SETTINGS).loadFromInputStream(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            return null
        }
        val root = document as? Map<*, *> ?: return null
        if (root.keys.any { it !is String || it !in TOP_LEVEL_KEYS }) return null
        if (root["version"] != 1) return null
        val records = root["records"]
        if (records != null && records !is Map<*, *>) return null
        val refs = root["refs"] ?: return emptyMap()
        val mapping = refs as? Map<*, *> ?: return null
        if (mapping.size > MAX_REFS) return null
        val parsed = linkedMapOf<String, String>()
        for ((rawName, rawValue) in mapping) {
            val name = rawName as? String ?: return null
            val value = rawValue as? String ?: return null
            if (!RuntimeSecretPolicy.isCredentialReferenceName(name) || value.isEmpty()) return null
            parsed[name] = value
        }
        return parsed
    }

    /**
     * 把 [updates] 并进 [text] 的 `refs` 段，返回新的文档原文。
     *
     * 语义要点：
     *  - **只增不删**：`refs` 里已有、但 [updates] 没提到的条目原样保留（可能是模型页写的另一把 key）；
     *  - `records` 段与所有注释、空行逐行保留；
     *  - 文档已经满足 [updates] 时返回原文本（调用方据此跳过写盘，避免无谓改动）；
     *  - 返回前用 [refs] 重新解析结果并比对，**解析不出预期内容就返回 `null`**（自校验）。
     *
     * @param text 现有文档原文；`null` / 空白表示新建。
     * @param updates 需要写入或覆盖的引用。
     * @return 新的文档原文；无法安全编辑时返回 `null`。
     */
    fun upsert(text: String?, updates: Map<String, String>): String? {
        if (updates.isEmpty()) return text
        if (updates.any { !RuntimeSecretPolicy.isCredentialReferenceName(it.key) || !isStorableValue(it.value) }) {
            return null
        }
        val existing = refs(text.orEmpty()) ?: return null
        val desired = linkedMapOf<String, String>().apply {
            putAll(existing)
            putAll(updates)
        }
        if (desired == existing) return text
        val rendered = if (text.isNullOrBlank()) {
            freshDocument(updates)
        } else {
            val lines = text.split('\n')
            val structure = scan(lines) ?: return null
            render(lines, structure, updates)
        }
        // 自校验：写盘前的最后一关。解析不出预期内容就当作「不能安全编辑」。
        return rendered.takeIf { refs(it) == desired }
    }

    /** 新建一份最小文档；`refs` 段的缩进与 dsh 自己写出来的一致。 */
    private fun freshDocument(updates: Map<String, String>): String = buildString {        append("version: 1\n")
        append("refs:\n")
        updates.forEach { (name, value) ->
            append(" ".repeat(DEFAULT_REF_INDENT))
            append(entry(name, value))
            append('\n')
        }
    }

    /**
     * 纯文本结构扫描：确认这是「本编辑器认识的」文档，并记下 `refs` 段的位置。
     *
     * 只做**结构**判定，不解释取值：`records` 段内部完全不看，因此无论它多复杂都会被逐行保住。
     */
    private fun scan(lines: List<String>): Structure? {
        var versionLine: Int? = null
        var refsHeaderLine: Int? = null
        val refEntries = mutableListOf<Pair<Int, String>>()
        var refIndent = DEFAULT_REF_INDENT
        // 新条目插入点：refs 段最后一个条目之后；段内没有条目时是表头之后。
        var insertLine = -1
        var section: String? = null
        var sawContent = false
        lines.forEachIndexed { index, raw ->
            // CRLF 文档不在本编辑器的处理范围内：原样保留会与行下标假设冲突，一律放弃编辑。
            if (raw.indexOf('\r') >= 0) return null
            if (raw.isBlank()) return@forEachIndexed
            if (raw.trimStart().startsWith("#")) return@forEachIndexed
            // 文档标记（`---` / `...`）会改变解析语义，本编辑器不处理。
            if (raw.startsWith("---") || raw.startsWith("...")) return null
            sawContent = true
            val indent = raw.takeWhile { it == ' ' }.length
            if (indent < raw.length && raw[indent] == '\t') return null
            if (indent == 0) {
                val match = TOP_LEVEL_ENTRY.matchEntire(raw) ?: return null
                val key = match.groupValues[1]
                val rest = match.groupValues[2].trim()
                section = key
                when (key) {
                    "version" -> {
                        // 与 dsh 一致：只认字面量 `version: 1`，且不允许重复。
                        if (versionLine != null || rest != "1") return null
                        versionLine = index
                    }
                    "refs" -> {
                        // 只认块映射形态（`refs:` 后为空）。`refs: {}` 一类的流式形态一律拒绝。
                        if (refsHeaderLine != null || rest.isNotEmpty()) return null
                        refsHeaderLine = index
                        insertLine = index + 1
                    }
                    "records" -> Unit
                    else -> return null
                }
                return@forEachIndexed
            }
            if (section == REFS_SECTION) {
                val match = REF_ENTRY.matchEntire(raw) ?: return null
                if (!isPlainSingleLineScalar(match.groupValues[2])) return null
                refEntries += index to match.groupValues[1]
                refIndent = indent
                insertLine = index + 1
            }
        }
        // 非空文档必须带 `version: 1`：没有它 dsh 自己也会拒绝读取（pre-release 扁平布局）。
        val versionIndex = versionLine ?: return null
        return Structure(versionIndex, refsHeaderLine, refEntries, insertLine, refIndent)
    }

    /** 逐行重建：只替换/追加 `refs` 段里的行，其它行原样透传。 */
    private fun render(lines: List<String>, structure: Structure, updates: Map<String, String>): String {
        val indent = " ".repeat(structure.refIndent)
        val replacements = structure.refEntries.mapNotNull { (index, name) ->
            updates[name]?.let { index to (indent + entry(name, it)) }
        }.toMap()
        val existingNames = structure.refEntries.mapTo(linkedSetOf()) { it.second }
        // 追加的行必须带上与同段其它条目一致的缩进：少了它这些键会落到顶层，
        // 自校验会（正确地）判定文档不再是本编辑器认识的那一份并拒绝写入。
        val additions = updates.filterKeys { it !in existingNames }.map { (name, value) -> indent + entry(name, value) }
        val output = mutableListOf<String>()
        lines.forEachIndexed { index, raw ->
            output += replacements[index] ?: raw
            when {
                structure.refsHeaderLine != null && index == structure.insertLine - 1 -> output += additions
                structure.refsHeaderLine == null && index == structure.versionLine -> {
                    output += "refs:"
                    output += additions
                }
            }
        }
        return output.joinToString("\n")
    }

    /** `NAME: 'value'`。 */
    private fun entry(name: String, value: String): String = "$name: ${yamlScalar(value)}"

    /**
     * YAML 单引号标量：除把 `'` 写成 `''` 之外没有任何转义处理，因此不存在「转义与解析不一致」的空间。
     * 取值由 [isStorableValue] 保证是可打印 ASCII 且无空格，单引号形态总是安全。
     */
    private fun yamlScalar(value: String): String = "'" + value.replace("'", "''") + "'"

    /** 可写进文档的取值：与 [RuntimeSecretPolicy] 对环境取值的文法同源（可打印 ASCII，非空）。 */
    private fun isStorableValue(value: String): Boolean =
        value.isNotEmpty() && value.none { it.code < 0x21 || it.code > 0x7e }

    /**
     * 是否是本编辑器敢原样保留的**单行纯量**。
     *
     * 空值（`key:`）、块标量（`|` / `>`）、流式集合（`[` / `{`）、锚点与标签（`&` / `*` / `!`）、
     * 显式空值（`~` / `null`）都与「一个可以直接替换掉的取值」不同 —— 一律拒绝并回落到环境文件，
     * 绝不去猜 dsh 会怎么解析它。带引号的写法（`'x'` / `"x"`）是单行纯量，原样保留。
     */
    private fun isPlainSingleLineScalar(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        if (value.first() in PLAIN_SCALAR_INDICATORS) return false
        if (value.endsWith(":")) return false
        if (value.contains(": ") || value.contains(" #")) return false
        if (value.equals("null", ignoreCase = true) || value == "~") return false
        return true
    }

    private class Structure(
        val versionLine: Int,
        val refsHeaderLine: Int?,
        /** `refs` 段里的条目行：行下标 → 引用名，顺序与原文档一致。 */
        val refEntries: List<Pair<Int, String>>,
        /** 新条目的插入位置（行下标）。 */
        val insertLine: Int,
        /** `refs` 条目的缩进；追加时沿用，避免同一映射出现两种缩进。 */
        val refIndent: Int,
    )

    private const val REFS_SECTION = "refs"
}
