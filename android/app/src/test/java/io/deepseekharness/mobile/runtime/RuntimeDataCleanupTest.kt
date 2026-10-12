package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.diagnostics.DIAGNOSTIC_DIRECTORY_NAME
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 分级清理（缓存档 / 插件数据档）的行为契约。
 *
 * 覆盖三件真机上必须成立的事：**没登记的东西一律不动**（缓存档只认白名单、插件数据档只认账本）、
 * 单条目失败绝不静默也绝不中断其余条目、以及越界路径仍被拒绝。逐包清理另有一条契约：
 * 给了包名就**只动那一个包**，核对不到就拒绝且零改动（绝不拿请求里的字符串去拼路径）。
 *
 * 删除器与存在性判定都是注入的：真机上的严格删除器走 `SecureDirectoryStream`、存在性判定走
 * `Os.lstat`，两者在 JVM 单测里都不可用（Android 桩件会抛「not mocked」）；注入之后「不存在就跳过」
 * 与「严格失败→走兜底」这两条分支才能被真的走到 —— 它们正是「别把不存在的东西算成已删」和
 * 「删掉了就说删掉了」的依据。
 */
class RuntimeDataCleanupTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `缓存档只删白名单三项_陌生条目与待安装更新原样保留`() {
        val cacheDir = temporaryFolder.newFolder("cache")
        val diagnostics = directory(cacheDir, RuntimeCacheEntries.DIAGNOSTIC_EXPORTS, bytes = 1024)
        val share = directory(cacheDir, RuntimeCacheEntries.WORKSPACE_SHARE, bytes = 64)
        val prootTmp = directory(cacheDir, RuntimeCacheEntries.PROOT_TMP, bytes = 32)
        // 没登记的条目（系统、WebView 或第三方库自己的缓存目录）必须原样保留。
        val stranger = directory(cacheDir, "webview-cache", bytes = 4096)
        // 已下载待安装的更新包是产物、不是缓存：清掉它等于让一次更新白跑。
        val pending = directory(cacheDir, RuntimeCacheEntries.PENDING_UPDATE, bytes = 8192)

        val outcome = RuntimeCacheCleanup(cacheDir, deletingRunner()).clear()

        assertEquals(RuntimeCacheEntries.CLEARABLE.size, outcome.scope)
        assertEquals(3, outcome.targets)
        assertEquals(3, outcome.removed)
        assertEquals(0, outcome.fallback)
        assertEquals(0, outcome.failed)
        assertEquals(0, outcome.skipped)
        assertEquals(3, outcome.files)
        assertEquals(1120L, outcome.bytes)
        assertTrue(outcome.ok)
        assertFalse(diagnostics.exists())
        assertFalse(share.exists())
        assertFalse(prootTmp.exists())
        assertTrue(stranger.isDirectory)
        assertTrue(pending.isDirectory)
    }

    @Test
    fun `条目不存在时记跳过_不算失败也不算已删`() {
        val cacheDir = temporaryFolder.newFolder("cache")
        // 删除器一被调用就失败：这个用例要证明的是「压根没去删」。
        val runner = RuntimeCleanupRunner(
            cleanup = RuntimeResidueCleanup(
                strictDelete = { _, _ -> throw AssertionError("条目不存在时不应调用删除器") },
                fallbackDelete = { _, _ -> throw AssertionError("条目不存在时不应调用删除器") },
            ),
            existsNoFollow = { false },
        )

        val outcome = RuntimeCacheCleanup(cacheDir, runner).clear()

        assertEquals(3, outcome.skipped)
        assertEquals(0, outcome.targets)
        assertEquals(0, outcome.removed)
        assertEquals(0, outcome.failed)
        assertEquals(0L, outcome.bytes)
        assertTrue(outcome.ok)
    }

    @Test
    fun `存在性判定读不出来时按存在处理_交给删除器如实报失败`() {
        val cacheDir = temporaryFolder.newFolder("cache")
        val runner = RuntimeCleanupRunner(
            cleanup = RuntimeResidueCleanup(
                strictDelete = { _, _ -> throw RuntimeFailure("FILESYSTEM_ERROR", "严格删除失败") },
                fallbackDelete = { _, _ -> throw RuntimeFailure("FILESYSTEM_ERROR", "兜底删除失败") },
            ),
            // 权限问题会让 lstat 抛错；这时把条目当存在处理，失败会被记上，而不是从账上悄悄消失。
            existsNoFollow = { throw IllegalStateException("lstat not mocked") },
        )

        val outcome = RuntimeCacheCleanup(cacheDir, runner).clear()

        assertEquals(3, outcome.targets)
        assertEquals(3, outcome.failed)
        assertEquals(0, outcome.removed)
        assertFalse(outcome.ok)
    }

    @Test
    fun `严格删除器失败但兜底成功时单独记兜底条数`() {
        val cacheDir = temporaryFolder.newFolder("cache")
        val diagnostics = directory(cacheDir, RuntimeCacheEntries.DIAGNOSTIC_EXPORTS, bytes = 16)
        val runner = RuntimeCleanupRunner(
            cleanup = RuntimeResidueCleanup(
                strictDelete = { _, _ -> throw RuntimeFailure("FILESYSTEM_ERROR", "严格删除失败") },
                fallbackDelete = { target, _ -> target.deleteRecursively() },
            ),
            existsNoFollow = { it.exists() },
        )

        val outcome = RuntimeCacheCleanup(cacheDir, runner).clear()

        assertEquals(1, outcome.removed)
        assertEquals(1, outcome.fallback)
        assertEquals(0, outcome.failed)
        assertEquals(1, outcome.files)
        assertEquals(16L, outcome.bytes)
        assertTrue(outcome.ok)
        assertFalse(diagnostics.exists())
    }

    @Test
    fun `两种删除器都失败时记失败且目标原样保留`() {
        val cacheDir = temporaryFolder.newFolder("cache")
        val diagnostics = directory(cacheDir, RuntimeCacheEntries.DIAGNOSTIC_EXPORTS, bytes = 16)
        val runner = RuntimeCleanupRunner(
            cleanup = RuntimeResidueCleanup(
                strictDelete = { _, _ -> throw RuntimeFailure("FILESYSTEM_ERROR", "严格删除失败") },
                fallbackDelete = { _, _ -> throw RuntimeFailure("FILESYSTEM_ERROR", "兜底删除失败") },
            ),
            existsNoFollow = { it.exists() },
        )

        val outcome = RuntimeCacheCleanup(cacheDir, runner).clear()

        assertEquals(1, outcome.failed)
        assertEquals(0, outcome.removed)
        assertEquals(0L, outcome.bytes)
        assertFalse(outcome.ok)
        assertTrue(diagnostics.isDirectory)
    }

    @Test
    fun `越界目标被拒绝并如实记失败`() {
        val cacheDir = temporaryFolder.newFolder("cache")
        // 孙目录：两个删除器都会以 RESET_SCOPE_INVALID 拒绝，且拒绝发生在任何文件系统操作之前。
        val nested = File(cacheDir, "sub/child").apply { mkdirs() }
        val tally = RuntimeCleanupTally()

        // 这里刻意用默认删除器（真机上的严格删除器），证明作用域校验没有被注入点放宽。
        RuntimeCleanupRunner(existsNoFollow = { true }).attempt(nested, cacheDir, tally)
        val outcome = tally.outcome(scope = 1)

        assertEquals(1, outcome.targets)
        assertEquals(1, outcome.failed)
        assertEquals(0, outcome.removed)
        assertFalse(outcome.ok)
        assertTrue(nested.isDirectory)
    }

    @Test
    fun `常量与各自的属主保持一致`() {
        assertEquals("diagnostics", DIAGNOSTIC_DIRECTORY_NAME)
        assertEquals(DIAGNOSTIC_DIRECTORY_NAME, RuntimeCacheEntries.DIAGNOSTIC_EXPORTS)
        assertEquals("share", RuntimeCacheEntries.WORKSPACE_SHARE)
        assertEquals("proot-tmp", RuntimeCacheEntries.PROOT_TMP)
        assertEquals("app-updates", AppUpdateStorage.DIRECTORY_NAME)
        assertEquals(AppUpdateStorage.DIRECTORY_NAME, RuntimeCacheEntries.PENDING_UPDATE)
        assertEquals(
            listOf("diagnostics", "share", "proot-tmp"),
            RuntimeCacheEntries.CLEARABLE,
        )
        // 待安装的更新包绝不能被列进可清理项。
        assertFalse(RuntimeCacheEntries.CLEARABLE.contains(RuntimeCacheEntries.PENDING_UPDATE))
    }

    @Test
    fun `插件数据目录清单与访客脚本同形`() {
        assertEquals(listOf("data", "config", "storage", ".config"), PluginDataDirectories.NAMES)
    }

    @Test
    fun `账本缺失或损坏时插件数据档返回 null 且磁盘零改动`() {
        val home = temporaryFolder.newFolder("plugin-manager")
        val transaction = transaction("a")
        val packageDirectory = packageDirectory(home, transaction, "left-pad")
        val data = dataDirectories(packageDirectory)
        val ledger = File(home, PluginInstallLock.FILE_NAME)

        // ① 账本文件压根不存在。
        assertNull(PluginDataPurge(home, runner = deletingRunner()).purge())

        // ② 账本存在但不是合法 JSON。
        ledger.writeText("{ 这不是 JSON")
        assertNull(PluginDataPurge(home, runner = deletingRunner()).purge())

        // ③ 账本合法 JSON、但缺一个必需字段：整份账本判 null，绝不部分采信。
        ledger.writeText(
            JSONObject()
                .put("schemaVersion", PluginInstallLock.SCHEMA_VERSION)
                .put("plugins", JSONArray().put(JSONObject().put("id", "left-pad")))
                .toString(),
        )
        assertNull(PluginDataPurge(home, runner = deletingRunner()).purge())

        data.forEach { assertTrue(it.isDirectory) }
        assertTrue(File(packageDirectory, "package.json").isFile)
    }

    @Test
    fun `账本可用时只删四个数据目录并保留包本体`() {
        val home = temporaryFolder.newFolder("plugin-manager")
        val transaction = transaction("a")
        val packageDirectory = packageDirectory(home, transaction, "left-pad")
        val data = dataDirectories(packageDirectory)
        writeLedger(home, ledgerEntry("left-pad", "1.2.3", transaction))

        val outcome = PluginDataPurge(home, runner = deletingRunner()).purge()

        assertNotNull(outcome)
        assertEquals(1, outcome!!.scope)
        assertEquals(4, outcome.targets)
        assertEquals(4, outcome.removed)
        assertEquals(0, outcome.failed)
        assertEquals(PluginDataDirectories.NAMES.size, outcome.files)
        assertTrue(outcome.ok)
        data.forEach { assertFalse(it.exists()) }
        // 包本体（依赖、清单）一律不动：清理数据不等于卸载插件。
        assertTrue(File(packageDirectory, "package.json").isFile)
        assertTrue(packageDirectory.isDirectory)
    }

    @Test
    fun `账本核对不到的包与未登记的版本目录一律不动`() {
        val home = temporaryFolder.newFolder("plugin-manager")
        val registered = transaction("a")
        val orphan = transaction("b")
        // ① 账本记着版本、但包目录已经不在（真实路径核对不过）⇒ 不参与清理，也不谎报缺失以外的事。
        val missing = File(File(File(home, "versions"), registered), "node_modules/ghost")
        // ② 账本自己记着「当时就解析不到版本」⇒ 直接排除。
        val unresolved = packageDirectory(home, registered, "unresolved-pkg")
        val unresolvedData = dataDirectories(unresolved)
        // ③ 源码版本目录里存在、但账本里没有登记的包：绝不遍历 `versions/` 去删。
        val unregistered = packageDirectory(home, orphan, "unregistered-pkg")
        val unregisteredData = dataDirectories(unregistered)

        writeLedger(
            home,
            ledgerEntry("ghost", "1.0.0", registered),
            ledgerEntry("unresolved-pkg", null, registered),
        )

        val outcome = PluginDataPurge(home, runner = deletingRunner()).purge()

        assertNotNull(outcome)
        assertEquals(0, outcome!!.scope)
        assertEquals(0, outcome.targets)
        assertEquals(0, outcome.removed)
        assertEquals(0, outcome.failed)
        assertTrue(outcome.ok)
        assertFalse(missing.exists())
        unresolvedData.forEach { assertTrue(it.isDirectory) }
        unregisteredData.forEach { assertTrue(it.isDirectory) }
    }

    @Test
    fun `按包清理只动指定的那一个包`() {
        val home = temporaryFolder.newFolder("plugin-manager")
        val transaction = transaction("a")
        val target = packageDirectory(home, transaction, "left-pad")
        val targetData = dataDirectories(target)
        val other = packageDirectory(home, transaction, "right-pad")
        val otherData = dataDirectories(other)
        writeLedger(
            home,
            ledgerEntry("left-pad", "1.2.3", transaction),
            ledgerEntry("right-pad", "4.5.6", transaction),
        )

        val outcome = PluginDataPurge(home, runner = deletingRunner()).purge("left-pad")

        assertNotNull(outcome)
        // scope 是「这次涉及几个包」：按包清理时报 1，界面才能说清「只清了这一个」。
        assertEquals(1, outcome!!.scope)
        assertEquals(4, outcome.targets)
        assertEquals(4, outcome.removed)
        assertEquals(0, outcome.failed)
        assertTrue(outcome.ok)
        targetData.forEach { assertFalse(it.exists()) }
        // 另一个插件的数据目录必须原样留着 —— 按包清理的全部意义就在这里，
        // 少了这条断言，「传了包名但代码里漏了过滤」会安静地变成「清了所有插件」。
        otherData.forEach { assertTrue(it.isDirectory) }
        assertTrue(File(other, "package.json").isFile)
    }

    @Test
    fun `账本里核对不到的包名被拒绝且磁盘零改动`() {
        val home = temporaryFolder.newFolder("plugin-manager")
        val transaction = transaction("a")
        val registered = packageDirectory(home, transaction, "left-pad")
        val data = dataDirectories(registered)
        writeLedger(home, ledgerEntry("left-pad", "1.2.3", transaction))

        val failure = assertThrows(RuntimeFailure::class.java) {
            PluginDataPurge(home, runner = deletingRunner()).purge("ghost-pkg")
        }

        // 「账本里没这个包」与「请求的串格式不对」是两个受控码（后者在桥接层挡下）：
        // 合成一个码，用户就分不清该改输入还是该刷新列表。
        assertEquals("CLEANUP_PLUGIN_UNKNOWN", failure.code)
        // 核对不到就一个字节都不动：绝不拿请求里的字符串去拼一个目录试试看。
        data.forEach { assertTrue(it.isDirectory) }
        assertTrue(File(registered, "package.json").isFile)
    }

    @Test
    fun `逐包现状按账本列出，占用取自四个数据目录`() {
        val home = temporaryFolder.newFolder("plugin-manager")
        val transaction = transaction("a")
        val registered = packageDirectory(home, transaction, "left-pad")
        File(registered, "data").apply {
            mkdirs()
            File(this, "a.bin").writeBytes(ByteArray(16))
        }
        File(registered, "config").apply {
            mkdirs()
            File(this, "b.bin").writeBytes(ByteArray(4))
        }
        // 装了但一个数据目录都没有的插件：仍然要出现（present=false、占用 0），
        // 列都不列的话用户会以为这个插件没装上。
        val empty = packageDirectory(home, transaction, "empty-pkg")
        // 账本没登记、但磁盘上有的包：绝不因为「它在 versions/ 里」就列出来。
        val unregistered = packageDirectory(home, transaction, "unregistered-pkg")
        dataDirectories(unregistered)
        writeLedger(
            home,
            ledgerEntry("left-pad", "1.2.3", transaction),
            ledgerEntry("empty-pkg", "1.0.0", transaction),
        )

        val entries = PluginDataInventory(home, existsNoFollow = { it.exists() }).snapshot()

        assertNotNull(entries)
        assertEquals(listOf("left-pad", "empty-pkg"), entries!!.map { it.id })
        assertTrue(entries[0].present)
        assertEquals(2, entries[0].files)
        assertEquals(20L, entries[0].bytes)
        assertFalse(entries[1].present)
        assertEquals(0, entries[1].files)
        assertEquals(0L, entries[1].bytes)
        assertTrue(File(registered, "package.json").isFile)
    }

    @Test
    fun `逐包现状在账本不可用时返回 null 而不是空列表`() {
        val home = temporaryFolder.newFolder("plugin-manager")
        packageDirectory(home, transaction("a"), "left-pad")

        // null 与「空列表」在界面上是两句话：前者是「账本读不出来，我不知道有什么」，
        // 后者是「读出来了，一个可清理的插件都没有」。混成一个，用户会以为插件都没装。
        assertNull(PluginDataInventory(home, existsNoFollow = { it.exists() }).snapshot())
    }

    /** 真机删除器走 `SecureDirectoryStream`、lstat 走 `Os.lstat`，两者在 JVM 单测里都不可用。 */
    private fun deletingRunner(): RuntimeCleanupRunner = RuntimeCleanupRunner(
        cleanup = RuntimeResidueCleanup(strictDelete = { target, _ -> target.deleteRecursively() }),
        existsNoFollow = { it.exists() },
    )

    private fun directory(parent: File, name: String, bytes: Int): File = File(parent, name).apply {
        mkdirs()
        File(this, "entry.bin").writeBytes(ByteArray(bytes))
    }

    private fun dataDirectories(directory: File): List<File> =
        PluginDataDirectories.NAMES.map { name ->
            File(directory, name).apply {
                mkdirs()
                File(this, "store.bin").writeBytes(ByteArray(8))
            }
        }

    private fun packageDirectory(home: File, transaction: String, id: String): File =
        File(File(File(home, "versions"), transaction), "node_modules/$id").apply {
            mkdirs()
            File(this, "package.json").writeText("""{"name":"$id"}""")
        }

    private fun writeLedger(home: File, vararg rows: JSONObject) {
        home.mkdirs()
        val payload = JSONObject()
            .put("schemaVersion", PluginInstallLock.SCHEMA_VERSION)
            .put("plugins", JSONArray().apply { rows.forEach { put(it) } })
        File(home, PluginInstallLock.FILE_NAME).writeText(payload.toString())
    }

    private fun ledgerEntry(id: String, version: Any?, transaction: Any?): JSONObject = JSONObject()
        .put("id", id)
        .put("version", version ?: JSONObject.NULL)
        .put("transaction", transaction ?: JSONObject.NULL)
        .put("source", "npm")
        .put("updatedAt", "2026-09-12T10:20:30.000Z")

    /** 36 位 `[a-f0-9-]`（账本的 `TRANSACTION` 正则）。 */
    private fun transaction(seed: String): String = listOf(8, 4, 4, 4, 12)
        .joinToString("-") { seed.repeat(it) }
}
