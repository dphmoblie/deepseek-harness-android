package io.deepseekharness.mobile.runtime

import org.json.JSONObject
import java.io.File

/**
 * 「已安装插件版本账本」在原生侧的**只读**投影与核对。
 *
 * 账本由访客脚本 `plugin-manager.cjs` 维护在**跨运行时升级保留区**：
 * `root/.dsh-mobile/plugin-manager/installed-lock.json`。它与 `state-snapshot.json` 的分工是
 * 硬边界，不能混用：
 *  - 状态快照记**启停状态**（用户的选择），由 `restoreState()` 回填清单，只补缺失项；
 *  - 版本账本记**版本事实**（每个已装插件解析到的版本、包本体所在的事务目录名、来源、
 *    该条事实最后变化的时间），只被本对象读取，用来回答「包目录还在不在」。
 *
 * 为什么要一份账本：插件包本体在 `versions/<事务>/node_modules/<包名>`，而让运行时解析到它的
 * 链接落在 `profiles/`（**不在**保留名单里）。运行时或应用升级后链接会被抹掉，plugin-manager
 * 需要重装或接回；账本让原生侧在**不启动节点、不遍历插件目录**的前提下，就能逐条核对
 * 「这个包的目录还在不在」，并把缺口如实计进 missing。
 *
 * 校验口径与访客脚本 `lockEntries()` 逐条对应（包名/版本/事务目录/来源/时间五条）；**任何一条
 * 不合法都按「账本不可用」处理**（返回 null），绝不部分采信：一份被篡改的账本比没有账本更危险，
 * 它会让我们把「缺失」算成「在」。读不出来只是一次核对做不成，不改动任何插件目录。
 */
object PluginInstallLock {
    /** 与访客脚本里的文件名逐字一致。 */
    const val FILE_NAME = "installed-lock.json"

    /** 与访客脚本 `LOCK_SCHEMA` 一致；不认识的版本一律按不可用处理（降级后由访客重写）。 */
    const val SCHEMA_VERSION = 1

    /** 条目上限；与访客脚本 `LOCK_LIMIT` 一致，超过即视为不可信。 */
    const val MAX_ENTRIES = 256

    /** 文件字节上限；与访客脚本读侧一致（防止一次读取撑爆内存）。 */
    const val MAX_BYTES = 65536L

    private val PACKAGE = Regex("^(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")
    private val VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?$")
    private val TRANSACTION = Regex("^[a-f0-9-]{36}$")
    private val INSTANT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")

    /**
     * 来源取值：`bundle`（随运行时清单自带的官方包）/ `npm`（npm 装/更新）/ `url`（https 直链
     * 压缩包）/ `git`（git+https 仓库）/ `unknown`（首次登记时无从判定，如实不猜）。
     */
    val SOURCES = setOf("bundle", "npm", "url", "git", "unknown")

    /** 一条版本事实；[version] 与 [transaction] 为 `null` 表示访客侧当时就解析不到。 */
    data class Entry(val id: String, val version: String?, val transaction: String?, val source: String)

    data class Snapshot(val entries: List<Entry>)

    /** 核对结论：[checked] 是账本条目数，[missing] 是其中「包目录已不在」的条数。 */
    data class Verification(val checked: Int, val missing: Int)

    /**
     * 读取并校验账本。
     *
     * @return 可用时返回快照；文件缺失、超限、JSON 损坏或**任何一条**字段不合法时返回 `null`
     *   （调用方据此区分「还没有账本」与「账本损坏」，见 `RuntimePluginManager` 的 `lock` 状态）。
     */
    fun read(file: File): Snapshot? = try {
        if (!file.isFile || file.length() > MAX_BYTES) null
        else {
            val value = JSONObject(file.readText())
            if (value.optInt("schemaVersion", -1) != SCHEMA_VERSION) null
            else {
                val rows = value.optJSONArray("plugins")
                if (rows == null || rows.length() > MAX_ENTRIES) null
                else {
                    val entries = mutableListOf<Entry>()
                    val seen = mutableSetOf<String>()
                    var valid = true
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index)
                        val entry = row?.let { parseEntry(it) }
                        if (entry == null || !seen.add(entry.id)) { valid = false; break }
                        entries += entry
                    }
                    if (valid) Snapshot(entries) else null
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 包名是否合法：长度、`..` 与字符集三条判据，与账本条目用的是同一套。
     *
     * 抽出来是因为清理侧也要用同一把尺子：按单个插件清理时，请求里的包名先过这里，再去账本核对
     * 目录（判据不是「像不像包名」，而是「账本里有没有这一条」）。两处各写一份正则会慢慢分叉，
     * 而分叉的两侧必然有一边是错的 —— 要么放进不该放的字符串，要么把合法包名判成非法。
     */
    fun isPackageName(value: String): Boolean = value.length in 1..214 && ".." !in value && PACKAGE.matches(value)

    private fun parseEntry(row: JSONObject): Entry? {
        if (!row.has("id") || !row.has("version") || !row.has("transaction") || !row.has("source") || !row.has("updatedAt")) return null
        val id = row.optString("id", "")
        if (!isPackageName(id)) return null
        val version = if (row.isNull("version")) null else row.optString("version", "")
        if (version != null && !VERSION.matches(version)) return null
        val transaction = if (row.isNull("transaction")) null else row.optString("transaction", "")
        if (transaction != null && !TRANSACTION.matches(transaction)) return null
        val source = row.optString("source", "")
        if (source !in SOURCES) return null
        if (!INSTANT.matches(row.optString("updatedAt", ""))) return null
        return Entry(id, version, transaction, source)
    }

    /**
     * 按账本逐条核对包目录是否还在（**只读**：只 stat 目录，不创建、不删除、不跟随越界链接）。
     *
     * 判据（顺序即优先级）：
     *  - `version == null`：账本自己记着「当时就解析不到」——这是最直接的一条缺失证据；
     *  - 记了事务目录：核对 `versions/<事务>/node_modules/<包名>` 是不是**保留区内**的真实目录；
     *    不在、不是目录、或真实路径越出保留区（畸形链接）都计缺失；
     *  - 只有版本、没有事务目录：该包落在运行时自己的目录里（例如官方包的 `opt/dsh/node_modules`），
     *    账本不记那条路径 —— 如实**不计**缺失，不猜。
     *
     * @param home 插件管理区（`root/.dsh-mobile/plugin-manager`）。
     */
    fun verify(home: File, snapshot: Snapshot): Verification {
        // 连保留区的真实路径都求不出来时不做任何断言：计「核对 0 条」，而不是谎报「0 条缺失」。
        val root = canonical(home) ?: return Verification(0, 0)
        var missing = 0
        for (entry in snapshot.entries) if (!present(root, home, entry)) missing += 1
        return Verification(snapshot.entries.size, missing)
    }

    /** `versions/<事务>/node_modules/<包名>`；没有事务目录时为 `null`。 */
    private fun directoryOf(home: File, entry: Entry): File? {
        val transaction = entry.transaction ?: return null
        return File(File(File(home, "versions"), transaction), "node_modules/${entry.id}")
    }

    /** 一条账本事实对应的包目录；[id] 是包名，用来生成诊断与界面文案（**不进日志的路径字段**）。 */
    data class PackageDirectory(val id: String, val directory: File)

    /**
     * 账本里「包目录确实还在保留区内」的那些目录（**只读**：只 stat、只求真实路径）。
     *
     * 判据与 [verify] 完全同一套（复用 [present]）：没有事务目录、目录不在、不是目录、或真实路径
     * 越出保留区（畸形链接）的一律**不返回** —— 清理路径上不能删一个我们核不准的目标。
     *
     * 存在的意义：插件数据清理（`PluginDataPurge`）必须由账本驱动。遍历 `versions/` 去找
     * 「看起来像插件目录」的东西会删到不该删的数据，而这条路径只认账本登记过的事实。
     */
    fun directories(home: File, snapshot: Snapshot): List<PackageDirectory> {
        val root = canonical(home) ?: return emptyList()
        return snapshot.entries.mapNotNull { entry ->
            if (!present(root, home, entry)) return@mapNotNull null
            val directory = directoryOf(home, entry) ?: return@mapNotNull null
            PackageDirectory(entry.id, directory)
        }
    }

    private fun present(root: File, home: File, entry: Entry): Boolean {
        if (entry.version == null) return false
        val directory = directoryOf(home, entry) ?: return true
        if (!directory.isDirectory) return false
        val resolved = canonical(directory) ?: return false
        return resolved == root || resolved.path.startsWith(root.path + File.separator)
    }

    private fun canonical(file: File): File? = try { file.canonicalFile } catch (_: Exception) { null }
}
