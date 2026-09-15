package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 白名单门面的用例：增删、上限、重复、逐条跳过与绑定结果。
 *
 * 访客挂载点用真实的临时目录（`MailboxTree.createDirectoriesNoFollow` 是纯 `java.nio` 代码，
 * 在 JVM 上能真跑），因此「`/mnt/user/<序号>` 是否真的被创建」是被实测的，不是走查出来的。
 */
class RuntimeStorageDirsTest {
    private val root = RuntimeStorageDirsLayout.PUBLIC_STORAGE
    private val diagnostics = mutableListOf<Pair<DiagnosticLevel, Map<String, String>>>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun facade(
        fileSystem: StorageDirFileSystem,
        stored: StoredStorageDirs = StoredStorageDirs(emptyList()),
        guestRoot: File = temporaryFolder.root,
        storage: RuntimeStorageDirPreferences.Storage = SeededStorage(stored),
    ): RuntimeStorageDirs = RuntimeStorageDirs(
        preferences = RuntimeStorageDirPreferences(storage),
        fileSystem = fileSystem,
        guestRoot = { guestRoot },
        record = { level, fields -> diagnostics += level to fields },
    )

    private fun downloadable(): FakeStorageDirFileSystem = FakeStorageDirFileSystem(
        directories = setOf("$root/Download", "$root/Documents/Project"),
    )

    @Test
    fun `adds a directory and reports its guest mount point`() {
        val fileSystem = downloadable()
        val state = facade(fileSystem).add("primary:Download")

        assertEquals(1, state.entries.size)
        val entry = state.entries.single()
        assertEquals(1, entry.index)
        assertEquals("$root/Download", entry.entry.path)
        assertEquals("Download", entry.entry.displayName)
        assertEquals("/mnt/user/1", entry.guestPath)
        assertEquals(StorageDirAvailability.AVAILABLE, entry.availability)
        assertEquals("T2", entry.availability.level)
    }

    @Test
    fun `rejects a non primary volume and persists nothing`() {
        val fileSystem = downloadable()
        val storage = SeededStorage(StoredStorageDirs(emptyList()))
        val dirs = facade(fileSystem, storage = storage)

        val failure = assertThrows(RuntimeFailure::class.java) { dirs.add("1234-5678:Download") }

        assertEquals(StorageDirCodes.VOLUME_UNSUPPORTED, failure.code)
        assertTrue(storage.current().isEmpty())
    }

    @Test
    fun `rejects a duplicate without stacking a second entry`() {
        val fileSystem = downloadable()
        val dirs = facade(fileSystem)
        dirs.add("primary:Download")

        val failure = assertThrows(RuntimeFailure::class.java) { dirs.add("primary:Download") }

        assertEquals(StorageDirCodes.DUPLICATE, failure.code)
        assertEquals(1, dirs.state().entries.size)
    }

    @Test
    fun `stops at eight directories`() {
        val paths = (1..RuntimeStorageDirsLimits.MAX_DIRECTORIES).map { "$root/dir$it" }
        val fileSystem = FakeStorageDirFileSystem(directories = (paths + "$root/ninth").toSet())
        val storage = SeededStorage(StoredStorageDirs(emptyList()))
        val dirs = facade(fileSystem, storage = storage)
        paths.forEach { path -> dirs.add("primary:${path.removePrefix("$root/")}") }

        val state = dirs.state()
        assertEquals(8, state.entries.size)
        assertEquals(8, state.maxDirectories)
        assertTrue(state.active)

        // 达到上限时**一次写入都不能发生**：既不能多存第 9 条，也不能重写一遍旧的 8 条。
        val writesBefore = storage.writeCount()
        val failure = assertThrows(RuntimeFailure::class.java) { dirs.add("primary:ninth") }
        assertEquals(StorageDirCodes.LIMIT_REACHED, failure.code)
        assertEquals(writesBefore, storage.writeCount())
        assertEquals(8, dirs.state().entries.size)
    }

    /**
     * 第 9 条必须被拒，且原因必须是「已满」而不是「保存失败」。
     *
     * 这里用一个「写入必定失败」的存储来钉住错误口径：即使存储本身不可写，用户看到的原因也
     * 只能是 `STORAGE_DIR_LIMIT_REACHED`——报成保存失败会把用户引向排查存储故障，而真实原因
     * 是白名单已经满 8 个。
     *
     * **已知的测试盲区（实测）**：上限在两处独立实现（门面与持久化层），因此把门面里的
     * `>=` 改成 `>` 时本条与「stops at eight directories」都仍然通过——持久化层会先抛出同一个码。
     * 「上限是 8」这条规则由常量变异（把 `MAX_DIRECTORIES` 改成 9）与前端校验器的
     * `maxDirectories === 8` 共同钉住，而不是由这一条。
     */
    @Test
    fun `rejects the ninth directory before writing anything`() {
        val paths = (1..RuntimeStorageDirsLimits.MAX_DIRECTORIES).map { "$root/dir$it" }
        val seeded = paths.map { StorageDirEntry(it, it.substringAfterLast('/')) }
        val fileSystem = FakeStorageDirFileSystem(directories = (paths + "$root/ninth").toSet())
        val dirs = facade(
            fileSystem,
            stored = StoredStorageDirs(seeded),
            storage = SeededStorage(StoredStorageDirs(seeded), failWrites = true),
        )

        val failure = assertThrows(RuntimeFailure::class.java) { dirs.add("primary:ninth") }

        assertEquals(StorageDirCodes.LIMIT_REACHED, failure.code)
    }

    @Test
    fun `removes by path and reports an unknown path`() {
        val fileSystem = downloadable()
        val dirs = facade(fileSystem)
        dirs.add("primary:Download")
        dirs.add("primary:Documents/Project")

        val state = dirs.remove("$root/Download")
        assertEquals(listOf("$root/Documents/Project"), state.entries.map { it.entry.path })

        val failure = assertThrows(RuntimeFailure::class.java) { dirs.remove("$root/Download") }
        assertEquals(StorageDirCodes.NOT_FOUND, failure.code)
    }

    /**
     * 移除是纯账本操作：权限被撤销、系统不支持、目录早就不存在时**都必须能清理条目**。
     *
     * 否则用户会卡在一份删不掉的列表上，而那份列表正是他下次启动时唯一能自救的地方。
     */
    @Test
    fun `removes an entry even when the whitelist is not usable`() {
        val fileSystem = downloadable().apply {
            supported = false
            accessible = false
        }
        val dirs = facade(
            fileSystem,
            StoredStorageDirs(listOf(StorageDirEntry("$root/Download", "Download"))),
        )

        assertTrue(dirs.remove("$root/Download").entries.isEmpty())
    }

    @Test
    fun `binds every available directory to a stable numbered mount point`() {
        val fileSystem = downloadable()
        val dirs = facade(fileSystem)
        dirs.add("primary:Download")
        dirs.add("primary:Documents/Project")

        val mounts = dirs.bindMounts()

        assertEquals(
            listOf(
                ProotBindMount("$root/Download", "/mnt/user/1"),
                ProotBindMount("$root/Documents/Project", "/mnt/user/2"),
            ),
            mounts,
        )
        // 访客挂载点必须真的被创建出来（逐级 NoFollow），否则 PRoot 会挂到不存在的目录上。
        assertTrue(File(temporaryFolder.root, "mnt/user/1").isDirectory)
        assertTrue(File(temporaryFolder.root, "mnt/user/2").isDirectory)
        // 绑定源必须是真实路径而不是 /sdcard 这类符号链接：这是投递区踩过的坑。
        assertTrue(mounts.all { it.source.startsWith("$root/") })
    }

    @Test
    fun `skips only the vanished directory and keeps the numbering of the rest`() {
        val fileSystem = downloadable()
        val dirs = facade(fileSystem)
        dirs.add("primary:Download")
        dirs.add("primary:Documents/Project")

        // 第 1 条目录被用户删掉/改名：只跳过它，第 2 条仍挂在 /mnt/user/2（不重排）。
        fileSystem.removeDirectory("$root/Download")
        val mounts = dirs.bindMounts()

        assertEquals(listOf(ProotBindMount("$root/Documents/Project", "/mnt/user/2")), mounts)
        val statuses = dirs.state().entries
        assertEquals(StorageDirAvailability.UNAVAILABLE, statuses[0].availability)
        assertEquals(StorageDirCodes.NOT_A_DIRECTORY, statuses[0].reasonCode)
        assertEquals(StorageDirAvailability.AVAILABLE, statuses[1].availability)
        assertTrue(diagnostics.any { it.second["code"] == StorageDirCodes.NOT_A_DIRECTORY })
    }

    @Test
    fun `binds nothing without all files access`() {
        val fileSystem = downloadable().apply { accessible = false }
        val dirs = facade(
            fileSystem,
            StoredStorageDirs(listOf(StorageDirEntry("$root/Download", "Download"))),
        )

        assertTrue(dirs.bindMounts().isEmpty())
        assertEquals(StorageDirAvailability.NEEDS_PERMISSION, dirs.state().entries.single().availability)
        assertEquals("T0", dirs.state().level)

        val failure = assertThrows(RuntimeFailure::class.java) { dirs.add("primary:Download") }
        assertEquals(StorageDirCodes.NEEDS_PERMISSION, failure.code)
    }

    @Test
    fun `reports unsupported on systems without all files access`() {
        val fileSystem = downloadable().apply { supported = false }
        val dirs = facade(
            fileSystem,
            StoredStorageDirs(listOf(StorageDirEntry("$root/Download", "Download"))),
        )

        assertFalse(dirs.state().supported)
        assertEquals(StorageDirAvailability.UNSUPPORTED, dirs.state().entries.single().availability)
        assertTrue(dirs.bindMounts().isEmpty())
        val failure = assertThrows(RuntimeFailure::class.java) { dirs.add("primary:Download") }
        assertEquals(StorageDirCodes.UNSUPPORTED, failure.code)
    }

    @Test
    fun `skips a stored entry that no longer passes the rules`() {
        // 手改过的偏好（或将来规则收紧）里可能留下不合规路径：读取时逐条跳过，不清空整份白名单。
        val fileSystem = downloadable()
        val dirs = facade(
            fileSystem,
            StoredStorageDirs(
                listOf(
                    StorageDirEntry("$root/Android/data", "data"),
                    StorageDirEntry("$root/Download", "Download"),
                ),
            ),
        )

        val statuses = dirs.state().entries
        assertEquals(StorageDirCodes.PRIVATE_REJECTED, statuses[0].reasonCode)
        assertEquals(StorageDirAvailability.UNAVAILABLE, statuses[0].availability)
        assertEquals(listOf(ProotBindMount("$root/Download", "/mnt/user/2")), dirs.bindMounts())
    }

    @Test
    fun `cache token follows the whitelist content`() {
        val fileSystem = downloadable()
        val dirs = facade(fileSystem)
        val empty = dirs.cacheToken()

        dirs.add("primary:Download")
        val single = dirs.cacheToken()
        dirs.add("primary:Documents/Project")
        val doubled = dirs.cacheToken()
        dirs.remove("$root/Download")
        val reordered = dirs.cacheToken()

        assertNotEquals(empty, single)
        assertNotEquals(single, doubled)
        // 内容不同但条数相同也必须不同：摘要覆盖路径本身，而不是只数条数。
        assertNotEquals(doubled, reordered)
        assertFalse(single.contains(root))
    }

    @Test
    fun `records diagnostics without leaking paths`() {
        val fileSystem = downloadable()
        val dirs = facade(fileSystem)
        dirs.add("primary:Download")

        val addRecord = diagnostics.last()
        assertEquals(DiagnosticLevel.INFO, addRecord.first)
        assertEquals("add", addRecord.second["reason"])
        assertTrue(addRecord.second.values.none { it.contains(root) })
    }

    /** 只读替身：写入走内存（可令其失败），读取返回预置内容。 */
    private class SeededStorage(
        private var stored: StoredStorageDirs,
        private val failWrites: Boolean = false,
    ) : RuntimeStorageDirPreferences.Storage {
        private var text: String? = null
        private var writes = 0

        init {
            if (stored.entries.isNotEmpty()) {
                text = stored.entries.joinToString(",", "[", "]") { entry ->
                    """{"path":"${entry.path}","name":"${entry.displayName}"}"""
                }
            }
        }

        override fun readString(key: String): String? = text

        override fun writeString(key: String, value: String) {
            writes += 1
            if (failWrites) throw IllegalStateException("存储不可写（夹具）")
            text = value
        }

        fun writeCount(): Int = writes

        fun current(): List<StorageDirEntry> = RuntimeStorageDirPreferences(this).read().entries
    }
}
