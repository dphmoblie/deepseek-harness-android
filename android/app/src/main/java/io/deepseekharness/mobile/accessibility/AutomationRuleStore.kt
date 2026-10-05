package io.deepseekharness.mobile.accessibility

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动化规则的**按包名**持久化。
 *
 * 三处固定口径：
 * 1. **坏数据自愈，不崩**。读到一个包解析失败时，该包的条目被**删除**并把成因放进
 *    [AutomationRuleStore.PackageRead.Corrupt] 让调用方能看到，而不是抛异常、也不是留着让每次窗口
 *    变化都再炸一次。破坏面刻意收在一个包之内：一个包坏了不影响其它包（对照
 *    `AccessibilityAutomationStore` 的「白名单任一非法就整份失效」——那份是单值集合，
 *    这里是 40 条规则 × N 个包的多值集合，整份失效等于让一次磁盘损坏抹掉用户几千条调好的规则）。
 * 2. **原子写**。落盘只有**一次** [AutomationRulePackageStorage.write]，把整份槽位索引（每个包的
 *    包名 + 规则 JSON）一次写完；不存在「先删后写」的中间态，因此断电/被杀进程不会留下
 *    「包还在、规则没了」。这份索引本身就是原子替换的单位。
 * 3. **限额是拒绝，不是截断**。每应用 ≤ 40 条、单包 JSON ≤ 64 KiB、全局 ≤ 256 KiB，超限直接抛
 *    [AutomationRuleException]，绝不静默丢规则——用户无法从行为上分辨「被丢了」和「没匹配上」。
 *
 * 存储抽象可注入（生产实现包 SharedPreferences，JVM 单测用内存替身），与
 * `AccessibilityPasswordPreferences` / `RuntimeStorageDirPreferences` 同一个模式：判定逻辑里
 * 不出现 `getSharedPreferences`，否则「坏数据自愈 / 各种上限 / 删除」这条链只能挂在设备上手工点。
 *
 * 读写都带锁：写路径是「读全部 → 算总量 → 写索引」的复合操作，而无障碍服务（读）与
 * 设置页/工具面（写）本来就在不同线程上。
 */
internal object AutomationRuleStore {
    /** 独立偏好文件；与白名单（`accessibility_automation`）和密码（`..._guard`）各自隔离。 */
    const val FILE_NAME = "accessibility_automation_rules"

    /** 槽位索引唯一的键：整份规则数据都以它的值的形式存在。 */
    internal const val KEY_INDEX = "index"

    /** 槽位键：仅用于兼容性占位，实际数据全部在 [KEY_INDEX] 里。 */
    internal fun slotKey(slot: Int): String = "p$slot"

    /** 固定槽位数：远大于任何真实使用量，同时给全局 256 KiB 上限留足余量。 */
    internal const val MAX_PACKAGE_SLOTS = 128

    /**
     * 索引里的两个分隔字符（都用 [Char] 常量，不用字符串字面量）。
     *
     * 这些常量**曾经**写成 `private const val PACKAGE_SEPARATOR = "\\n"`（两个字符：反斜杠 + n），
     * 而 [encodeEntry] 是把它当字符串拼接的，于是落盘内容里多出一个 `\n` **两个字符**的分隔位，
     * 而读取侧按真正的换行 `split`，永远找不到它——表现为「写进去了却读不回来，且被当成损坏数据清掉」。
     * 用 [Char] 常量后不存在这类歧义：索引里的换行是真的换行，JSON 串里的换行才是"反斜杠 + n"。
     */
    private const val SLOT_BODY_SEPARATOR = '\t'

    /**
     * 条目内部的分隔符也是制表符，**不能**用换行。
     *
     * 曾经用换行同时兼作「条目内分隔」和「条目间分隔」，结果 [rawEntries] 先把整份索引按换行切开，
     * [parseEntry] 拿到的那一行里已经不存在换行了，`包名 \n JSON` 永远找不着 → 所有条目被当成坏行丢掉，
     * 于是每次保存都落成同一个槽位、`packages()` 恒为空、`readPackage` 恒返回空列表。
     * 现在两个分隔位都用制表符，换行只承担「条目之间」这一件事。
     */
    private const val PACKAGE_SEPARATOR = '\t'

    /** 索引里条目之间的分隔：真换行，一个包一行。 */
    private const val ENTRY_SEPARATOR = '\n'

    private val lock = Any()

    /**
     * 读一个包的规则。
     *
     * @return [PackageRead.Ok]（含「没保存过」与「保存过但为空」两种空包）；
     *         或 [PackageRead.Corrupt]，表示数据不可用且**已被清掉**。
     *         两种情况都不抛异常：读取路径挂在每个窗口变化上，抛异常等于把一次磁盘损坏升级成
     *         「服务每次响应都失败」。
     */
    fun readPackage(store: AutomationRulePackageStorage, packageName: String): PackageRead {
        requirePackageName(packageName)
        synchronized(lock) {
            val entry = decodeEntries(store).firstOrNull {
                it.decoded?.packageName == packageName || it.header == packageName || it.mismatchedFor(packageName)
            } ?: return PackageRead.Ok(emptyList())
            entry.decoded?.let { decoded ->
                if (decoded.packageName != packageName) {
                    // 表头写着这个包，行内的规则却自称属于另一个包：同属损坏，走同一个出口。
                    // 这一支就是为了「表头被别人改过」而留的——此时按表头找得到的正是这条自相矛盾的行。
                    removeLocked(store, packageName)
                    return PackageRead.Corrupt(
                        AutomationRuleCodes.PACKAGE_INVALID,
                        "规则数据里的包名与索引不一致：索引声明 $packageName，规则里是 ${decoded.packageName}",
                    )
                }
                return PackageRead.Ok(decoded.rules)
            }
            if (entry.header == packageName) {
                val error = entry.error ?: AutomationRuleException(AutomationRuleCodes.MALFORMED, "规则数据不可用")
                removeLocked(store, packageName)
                return PackageRead.Corrupt(error.code, error.message ?: "规则数据不可用")
            }
            // 表头写着别的包，而这一行的规则却自称属于我们正在找的包：表头被改过。此时若只按
            // 表头查找就会返回一个空包，用户看到规则凭空消失、拿不到任何「数据坏了」的线索。
            val declared = entry.declaredPackages.firstOrNull { it == packageName } ?: packageName
            removeLocked(store, entry.header)
            return PackageRead.Corrupt(
                AutomationRuleCodes.PACKAGE_INVALID,
                "规则数据里的包名与索引不一致：索引声明 ${entry.header}，规则里是 $declared",
            )
        }
    }

    /** 列出已保存规则的包名，按字典序（与白名单 `state()` 的排序口径一致）。 */
    fun packages(store: AutomationRulePackageStorage): List<String> = synchronized(lock) {
        // 索引里的坏行由 rawEntries 整条丢掉：宁可少列一个包，也不返回一个查不到规则的乱码包名。
        rawEntries(store).map { (_, packageName, _) -> packageName }.sorted()
    }

    /** 覆盖一个包的规则（整包替换）。空列表等价于删除该包，不留下空条目。 */
    fun savePackage(store: AutomationRulePackageStorage, packageName: String, rules: List<AutomationRule>) {
        requirePackageName(packageName)
        synchronized(lock) {
            if (rules.isEmpty()) {
                removeLocked(store, packageName)
                return
            }
            if (rules.size > AutomationRuleLimits.MAX_RULES_PER_PACKAGE) {
                throw AutomationRuleException(
                    AutomationRuleCodes.FIELD_INVALID,
                    "每个应用最多 ${AutomationRuleLimits.MAX_RULES_PER_PACKAGE} 条规则，当前 ${rules.size} 条",
                )
            }
            rules.forEach { rule ->
                if (rule.packageName != packageName) {
                    throw AutomationRuleException(
                        AutomationRuleCodes.PACKAGE_INVALID,
                        "规则的包名是 ${rule.packageName}，与目标应用 $packageName 不一致",
                    )
                }
                rule.validated()
            }
            // 重复 id 会让「这条规则执行了几次」的计数互相串台，必须在写入边界拒绝。
            val repeated = rules.groupingBy(AutomationRule::id).eachCount().filterValues { it > 1 }.keys
            if (repeated.isNotEmpty()) {
                throw AutomationRuleException(
                    AutomationRuleCodes.FIELD_INVALID,
                    "规则标识重复：${repeated.sorted().joinToString("、")}",
                )
            }
            val json = renderRules(rules)
            val bytes = jsonUtf8Bytes(json)
            if (bytes > AutomationRuleLimits.MAX_PACKAGE_JSON_BYTES) {
                throw AutomationRuleException(
                    AutomationRuleCodes.FIELD_INVALID,
                    "单个应用的规则数据超过 ${AutomationRuleLimits.MAX_PACKAGE_JSON_BYTES} 字节（当前 $bytes 字节）",
                )
            }
            val entries = rawEntries(store)
            val existing = entries.firstOrNull { (_, name, _) -> name == packageName }
            // 全局上限用「写入后的总量」判定：先排除这个包的旧条目，避免反复保存同一份时把自己顶爆。
            val others = entries.filterNot { it === existing || it.third == packageName }
            val slot = existing?.first ?: allocateSlot(entries)
            val total = others.sumOf { (_, _, body) -> jsonUtf8Bytes(body) } + bytes
            if (total > AutomationRuleLimits.MAX_TOTAL_JSON_BYTES) {
                throw AutomationRuleException(
                    AutomationRuleCodes.FIELD_INVALID,
                    "全部应用的规则数据超过 ${AutomationRuleLimits.MAX_TOTAL_JSON_BYTES} 字节（写入后为 $total 字节）",
                )
            }
            // 唯一一次落盘：包名与规则在同一个字符串里，不存在「删了旧的、新的没写成」的窗口。
            store.write(KEY_INDEX, renderIndex(others + Triple(slot, packageName, json)))
        }
    }

    /** 删除一个包的规则。返回是否真的删掉了内容（幂等：删不存在的包不算失败）。 */
    fun removePackage(store: AutomationRulePackageStorage, packageName: String): Boolean {
        requirePackageName(packageName)
        return synchronized(lock) { removeLocked(store, packageName) }
    }

    /** 清空全部规则；返回被删除的包名（用于把「清掉了什么」如实报给调用方）。 */
    fun clear(store: AutomationRulePackageStorage): List<String> = synchronized(lock) {
        val removed = packages(store)
        store.clear()
        removed
    }

    /** 统计口径：包数、规则总数、已落盘字节、坏包数。设置页与桥接层读这一个方法就够。 */
    fun stats(store: AutomationRulePackageStorage): Stats = synchronized(lock) {
        val entries = rawEntries(store)
        var ruleCount = 0
        var bytes = 0
        var corrupt = 0
        entries.forEach { entry ->
            bytes += jsonUtf8Bytes(entry.third)
            val decoded = runCatching { decodeEntry(entry) }.getOrNull()
            if (decoded == null) corrupt++ else ruleCount += decoded.rules.size
        }
        Stats(
            packageCount = entries.size,
            ruleCount = ruleCount,
            storedBytes = bytes,
            maxRulesPerPackage = AutomationRuleLimits.MAX_RULES_PER_PACKAGE,
            maxPackageBytes = AutomationRuleLimits.MAX_PACKAGE_JSON_BYTES,
            maxTotalBytes = AutomationRuleLimits.MAX_TOTAL_JSON_BYTES,
            corruptPackages = corrupt,
        )
    }

    /** 单个包已占用的 JSON 字节数（未保存时是 0）；用于设置页展示占用。 */
    fun packageBytes(store: AutomationRulePackageStorage, packageName: String): Int {
        requirePackageName(packageName)
        return synchronized(lock) {
            val entry = rawEntries(store).firstOrNull { (_, name, _) -> name == packageName } ?: return 0
            jsonUtf8Bytes(entry.third)
        }
    }

    private fun removeLocked(store: AutomationRulePackageStorage, packageName: String): Boolean {
        val entries = rawEntries(store)
        val kept = entries.filterNot { (_, name, _) -> name == packageName }
        if (kept.size == entries.size) return false
        // 与 savePackage 同样的单次落盘：要么整份索引还是旧的，要么已经是新的。
        store.write(KEY_INDEX, renderIndex(kept))
        return true
    }

    private fun allocateSlot(entries: List<Triple<String, String, String>>): String {
        val used = entries.mapTo(mutableSetOf()) { (slot, _, _) -> slot }
        for (index in 0 until MAX_PACKAGE_SLOTS) {
            val candidate = slotKey(index)
            if (candidate !in used) return candidate
        }
        throw AutomationRuleException(
            AutomationRuleCodes.FIELD_INVALID,
            "最多只能为 $MAX_PACKAGE_SLOTS 个应用保存规则",
        )
    }

    /**
     * 索引 → `(槽位键, 包名, 规则 JSON)` 列表；坏行整条丢掉，不让读取路径崩在半个写上。
     *
     * **切分与解析必须成对**：行内 `包名 \n JSON` 的那个换行与「行与行之间」的换行是同一个字符，
     * [parseEntry] 只会看它拿到的那一段。所以这里必须先把整份索引按换行切行、再逐行解析；
     * 若把整份索引当成一行丢进去，`parseEntry` 会把「第一行的包名 + 后面所有行的拼接」当成 JSON，
     * 读出来的内容全错却不报错。
     */
    private fun rawEntries(store: AutomationRulePackageStorage): List<Triple<String, String, String>> =
        rawEntries(store.read(KEY_INDEX))

    private fun rawEntries(raw: String?): List<Triple<String, String, String>> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(ENTRY_SEPARATOR).mapNotNull(::parseEntry)
    }

    /**
     * 解析索引里的一行：`槽位键 \t 包名 \t 规则 JSON`。
     *
     * 用 `lastIndexOf` 定位正文前那个制表符：JSON 正文本身不含制表符（`org.json` 序列化时会把
     * 控制字符转义），所以「正文前最后一个制表符」是唯一可靠的定位方式；用 `indexOf` 会把包名
     * 与 JSON 一起吞掉。
     *
     * 判定口径只在**表头**上：槽位键与包名必须非空且不含制表符（否则表头本身已经错位，包名归谁
     * 都不可信）。**正文内容不参与判定**——正文坏掉的条目必须留在列表里，才能让 [readPackage]
     * 认出「这个包有数据但读不出来」并给出可诊断的 [PackageRead.Corrupt]；若在这里就把整行丢掉，
     * 表现会退化成「包突然不见了」，用户反而看不到任何线索。
     */
    private fun parseEntry(line: String): Triple<String, String, String>? {
        val separator = line.indexOf(SLOT_BODY_SEPARATOR)
        if (separator <= 0) return null
        val bodySeparator = line.lastIndexOf(PACKAGE_SEPARATOR)
        if (bodySeparator <= separator) return null
        val slot = line.substring(0, separator)
        val packageName = line.substring(separator + 1, bodySeparator)
        val body = line.substring(bodySeparator + 1)
        if (slot.isEmpty() || packageName.isEmpty() || body.isEmpty()) return null
        if (packageName.indexOf(SLOT_BODY_SEPARATOR) >= 0) return null
        return Triple(slot, packageName, body)
    }

    private fun renderIndex(entries: List<Triple<String, String, String>>): String =
        entries.sortedBy { it.first }.joinToString(ENTRY_SEPARATOR.toString()) { (slot, packageName, body) ->
            encodeEntry(slot, packageName, body)
        }

    /**
     * 索引一行的编码：`槽位键 \t 包名 \t 规则 JSON`。
     *
     * 不做 Base64：规则内容本身就是用户填的应用界面文字，不是凭据；而 `org.json` 会把 JSON 串里的
     * 控制字符转义掉，所以 `\t` 与 `\n` 只可能出现在我们放的分隔位上。这样落盘内容在
     * `adb` / 备份文件里直接可读，排查「规则为什么没生效」不必先解码。
     */
    private fun encodeEntry(slot: String, packageName: String, json: String): String =
        "$slot$SLOT_BODY_SEPARATOR$packageName$PACKAGE_SEPARATOR$json"

    private fun decodeEntry(entry: Triple<String, String, String>): DecodedPackage =
        decodeBody(entry.second, entry.third)

    /**
     * 逐条解析索引条目，把「读取入口按哪个包名找」这件事与「这一行到底声明了哪些包」分开。
     *
     * 必要性：索引表头的包名是可以被外部改坏的（备份还原、手工编辑偏好文件），改坏之后
     * `readPackage("com.example.app")` 若只按表头查找就会找不到那一行，于是**返回一个空包**——
     * 用户看到的是「规则凭空消失」，而不是「数据坏了」。这里额外把规则自带的包名收集出来，
     * 让读取入口在「按表头没找到、但有别行的规则自称属于这个包」时也能报出损坏。
     */
    private fun decodeEntries(store: AutomationRulePackageStorage): List<IndexEntry> =
        rawEntries(store).map { (slot, header, body) ->
            try {
                IndexEntry(slot, header, decodeEntry(Triple(slot, header, body)), emptyList(), null)
            } catch (error: AutomationRuleException) {
                IndexEntry(slot, header, null, declaredPackages(body), error)
            } catch (error: RuntimeException) {
                // 读取路径挂在每个窗口变化上，任何未预料到的解析异常都必须收敛成可诊断结果。
                IndexEntry(
                    slot,
                    header,
                    null,
                    declaredPackages(body),
                    AutomationRuleException(
                        AutomationRuleCodes.MALFORMED,
                        "规则数据不可解析：${error.message ?: error::class.java.simpleName}",
                    ),
                )
            }
        }

    /**
     * 从原始正文里尽可能读出规则自称的包名，不做任何校验。
     *
     * 存在的理由：表头被人改坏时，`decodeBody` 会以「表头与规则不一致」失败，此时必须还能知道
     * 这行数据**原本属于哪个包**，[readPackage] 才能把「按名字找不到」与「数据坏了」区分开——
     * 否则用户看到的是规则凭空消失，而不是数据损坏。
     */
    private fun declaredPackages(body: String): List<String> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val names = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val element = array.opt(index) as? JSONObject ?: continue
            val name = element.opt("packageName") as? String ?: continue
            if (name.isNotEmpty() && name !in names) names += name
        }
        return names
    }

    /** 索引里一行的解析结果；[decoded] 为 null 表示这一行坏了，[error] 给出受控原因。 */
    private data class IndexEntry(
        val slot: String,
        val header: String,
        val decoded: DecodedPackage?,
        val declaredPackages: List<String>,
        val error: AutomationRuleException?,
    ) {
        /** 这一行是否「说法自相矛盾」：规则正文自称属于 [packageName]，而表头写的是别的包。 */
        fun mismatchedFor(packageName: String): Boolean =
            header != packageName && packageName in declaredPackages
    }

    /**
     * 解出一行索引的包名与规则。
     *
     * 这里**不**校验包名是否为合法 Android 包名：索引里的包名合法与否由写入入口
     * （[savePackage] → `requirePackageName`）把关，读取侧关心的是「这一行的声明包名与规则自带的
     * 包名是否一致」。若在这里先抛 `PACKAGE_INVALID`，手工改坏的索引就只会得到「包名不合法」，
     * 掩盖掉真正的原因（声明与实际不符），[readPackage] 后面那条更准确的「不一致」判定也就永远
     * 走不到。包名只要求非空（`parseEntry` 已经拦过空包名）。
     */
    private fun decodeBody(packageName: String, body: String): DecodedPackage {
        val array = try {
            JSONArray(body)
        } catch (_: Throwable) {
            throw AutomationRuleException(AutomationRuleCodes.MALFORMED, "规则数据不是合法的 JSON 数组")
        }
        if (array.length() > AutomationRuleLimits.MAX_RULES_PER_PACKAGE) {
            throw AutomationRuleException(
                AutomationRuleCodes.MALFORMED,
                "规则数据超过每应用上限：${array.length()} 条",
            )
        }
        // 显式取成 JSONObject 再交给解析器：`JSONArray.opt` 返回 `Any?`，直接传进去会让
        // `fromJson` 的重载解析在 `String` / `JSONObject` 之间失去依据。
        val rules = (0 until array.length()).map { index ->
            val element = array.opt(index)
            if (element !is JSONObject) {
                throw AutomationRuleException(
                    AutomationRuleCodes.MALFORMED,
                    "规则数据的第 ${index + 1} 项不是 JSON 对象",
                )
            }
            AutomationRule.fromJson(element)
        }
        // 声明包名与规则自带包名不符：备份还原/手工编辑偏好文件都会造成这种数据，必须报「不一致」
        // 而不是让调用方以为这批规则属于当前应用（否则规则会被套用到别的包上）。
        rules.firstOrNull { it.packageName != packageName }?.let { mismatched ->
            throw AutomationRuleException(
                AutomationRuleCodes.PACKAGE_INVALID,
                "规则数据里的包名与索引不一致：索引声明 $packageName，规则里是 ${mismatched.packageName}",
            )
        }
        return DecodedPackage(packageName, rules)
    }

    /** 规则的序列化外观：一个 JSON 数组，字段与 `AutomationRule` 一一对应。 */
    internal fun renderRules(rules: List<AutomationRule>): String =
        JSONArray().also { array -> rules.forEach { array.put(it.toJson()) } }.toString()

    /** 供诊断使用：把一条规则写成等价 JSON（不含 `selectors`/`action` 之外的容器）。 */
    internal fun renderRule(rule: AutomationRule): JSONObject = rule.validated().toJson()

    private fun requirePackageName(packageName: String) {
        if (!AccessibilityAutomationPolicy.validPackage(packageName)) {
            throw AutomationRuleException(AutomationRuleCodes.PACKAGE_INVALID, "包名不在可自动化范围：$packageName")
        }
    }

    private data class DecodedPackage(val packageName: String, val rules: List<AutomationRule>)

    /** 单包读取结果：要么可用（可能是空包），要么损坏且已清除。 */
    internal sealed interface PackageRead {
        data class Ok(val rules: List<AutomationRule>) : PackageRead

        /** [code] 用 [AutomationRuleCodes]；[repaired] 恒为 true（坏数据已被删除）。 */
        data class Corrupt(val code: String, val message: String, val repaired: Boolean = true) : PackageRead
    }

    /** 统计结果；`max*` 字段一并返回，界面不必再抄一遍限额常量。 */
    internal data class Stats(
        val packageCount: Int,
        val ruleCount: Int,
        val storedBytes: Int,
        val maxRulesPerPackage: Int,
        val maxPackageBytes: Int,
        val maxTotalBytes: Int,
        val corruptPackages: Int,
    )
}

/**
 * 规则存储的抽象。
 *
 * 生产实现包 SharedPreferences（[AutomationRulePreferences.from]），JVM 单测用内存替身。
 * [write] 是**唯一**的落盘入口，实现必须是「整键覆盖」的一次写入，不能拆成多步。
 */
internal interface AutomationRulePackageStorage {
    fun read(key: String): String?

    fun write(key: String, value: String)

    fun clear()
}

/** [AutomationRuleStore] 的生产存储：单键单写，`commit()` 同步落盘。 */
internal object AutomationRulePreferences {
    fun from(context: Context): AutomationRulePackageStorage {
        val preferences = context.applicationContext
            .getSharedPreferences(AutomationRuleStore.FILE_NAME, Context.MODE_PRIVATE)
        return object : AutomationRulePackageStorage {
            override fun read(key: String): String? =
                if (preferences.contains(key)) preferences.getString(key, null) else null

            override fun write(key: String, value: String) {
                // commit() 而不是 apply()：写路径是「读全部 → 算总量 → 写索引」的复合操作，
                // 异步落盘会让紧接着的读（例如保存后回读 state）看到旧值。
                preferences.edit().putString(key, value).commit()
            }

            override fun clear() {
                preferences.edit().clear().commit()
            }
        }
    }
}
