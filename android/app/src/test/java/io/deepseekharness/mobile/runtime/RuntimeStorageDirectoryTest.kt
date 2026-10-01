package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 共享目录门面的用例：`/mnt/user/<序号>` → 白名单条目的解析、未就绪/不可用条目的错误码、
 * 以及子目录校验是否真的走投递区那一套规则。
 *
 * 覆盖边界说明（不夸大）：白名单里的宿主路径在真机上是 `/storage/emulated/0/...`，
 * 而 `RuntimeStorageDirsPolicy.requireCanonicalPath` 要求「以 `/` 开头的安全绝对路径」，
 * Windows 的临时目录过不了这一关。所以这里**不**假装能跑通「真实列举共享目录」，
 * 只测解析与失败码；成功列举由 `RuntimeDirectoryBrowserTest` 用真实临时目录直测核心，
 * 并由真机验收点补上端到端那一段（见报告）。
 */
class RuntimeStorageDirectoryTest {
    private val root = RuntimeStorageDirsLayout.PUBLIC_STORAGE

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 内存偏好替身：编解码仍走生产实现，只是不落盘。 */
    private class MemoryStorage(private var text: String? = null) : RuntimeStorageDirPreferences.Storage {
        override fun readString(key: String): String? = text

        override fun writeString(key: String, value: String) {
            text = value
        }
    }

    /** 用生产编解码器预置白名单，避免测试里手写 JSON 而与真实存储格式脱节。 */
    private fun seeded(vararg entries: StorageDirEntry): RuntimeStorageDirPreferences.Storage {
        val storage = MemoryStorage()
        RuntimeStorageDirPreferences(storage).write(entries.toList())
        return storage
    }

    private fun facade(
        fileSystem: StorageDirFileSystem,
        storage: RuntimeStorageDirPreferences.Storage,
    ): RuntimeStorageDirs = RuntimeStorageDirs(
        preferences = RuntimeStorageDirPreferences(storage),
        fileSystem = fileSystem,
        guestRoot = { temporaryFolder.root },
        record = { _, _ -> },
    )

    private fun downloadable(): FakeStorageDirFileSystem =
        FakeStorageDirFileSystem(directories = setOf("$root/Download", "$root/Documents/Project"))

    @Test
    fun `maps the guest number to the persisted position without reordering`() {
        val storage = seeded(
            StorageDirEntry("$root/Download", "Download"),
            StorageDirEntry("$root/Documents/Project", "Project"),
        )
        val facade = facade(downloadable(), storage)

        val first = facade.requireGuestDirectory("/mnt/user/1")
        val second = facade.requireGuestDirectory("/mnt/user/2")

        // 序号就是「持久化顺序」（与 StorageDirStatus.index 同一个数），不是排序结果：
        // 这条断言防的是「顺手按路径/名称重排」——那会让用户的挂载点凭空换位置。
        assertEquals(1, first.index)
        assertEquals("$root/Download", first.entry.path)
        assertEquals("Download", first.entry.displayName)
        assertEquals("/mnt/user/1", first.guestPath)
        assertEquals(2, second.index)
        assertEquals("$root/Documents/Project", second.entry.path)
        assertEquals(StorageDirAvailability.AVAILABLE, first.availability)
    }

    @Test
    fun `accepts exactly one spelling of a guest path`() {
        val storage = seeded(StorageDirEntry("$root/Download", "Download"))
        val facade = facade(downloadable(), storage)

        // 只认 guestPath(n) 这一个写法：多一位前导零、空白、正号、尾斜杠、带子路径都不行，
        // 否则「/mnt/user/01」会被解释成不同条目，挂载点与实际目录就对不上了。
        listOf(
            "",
            "   ",
            "/mnt/user",
            "/mnt/user/",
            "/mnt/user/0",
            "/mnt/user/01",
            "/mnt/user/+1",
            "/mnt/user/ 1",
            "/mnt/user/1 ",
            "/mnt/user/1/",
            "/mnt/user/1/Download",
            "/mnt/user/abc",
            "/mnt/user/1.0",
            "/mnt/inbox",
            "mnt/user/1",
            "/mnt/user/-1",
        ).forEach { candidate ->
            val failure = assertThrows("应当拒绝：$candidate", RuntimeFailure::class.java) {
                facade.requireGuestDirectory(candidate)
            }
            assertEquals("应当拒绝：$candidate", StorageDirCodes.PATH_INVALID, failure.code)
        }
    }

    @Test
    fun `reports a guest path beyond the whitelist as not found`() {
        val storage = seeded(StorageDirEntry("$root/Download", "Download"))
        val facade = facade(downloadable(), storage)

        val failure = assertThrows(RuntimeFailure::class.java) {
            facade.requireGuestDirectory("/mnt/user/2")
        }

        // 序号合法但条目不在白名单里 → NOT_FOUND（与 removeStorageDirectory 的「找不到」语义一致），
        // 不能复用 PATH_INVALID：前端要能区分「参数写错了」和「目录已被移除」。
        assertEquals(StorageDirCodes.NOT_FOUND, failure.code)
        assertNotEquals(StorageDirCodes.PATH_INVALID, failure.code)
    }

    @Test
    fun `surfaces the entry's own reason code instead of inventing one`() {
        val storage = seeded(
            StorageDirEntry("$root/Android/data", "data"),
            StorageDirEntry("$root/Gone", "Gone"),
        )
        val facade = facade(FakeStorageDirFileSystem(directories = setOf("$root/Download")), storage)

        // 条目本身违规（Android/data 是私有目录）→ 报那一刻的既有判定码，不新造一层包装码。
        val private = assertThrows(RuntimeFailure::class.java) {
            facade.requireGuestDirectory("/mnt/user/1")
        }
        assertEquals(StorageDirCodes.PRIVATE_REJECTED, private.code)

        // 条目合规但目录已消失/改名 → NOT_A_DIRECTORY。
        val vanished = assertThrows(RuntimeFailure::class.java) {
            facade.requireGuestDirectory("/mnt/user/2")
        }
        assertEquals(StorageDirCodes.NOT_A_DIRECTORY, vanished.code)
    }

    @Test
    fun `refuses to resolve before the tier is ready`() {
        val storage = seeded(StorageDirEntry("$root/Download", "Download"))

        val unsupported = assertThrows(RuntimeFailure::class.java) {
            facade(FakeStorageDirFileSystem(supported = false, directories = setOf("$root/Download")), storage)
                .requireGuestDirectory("/mnt/user/1")
        }
        assertEquals(StorageDirCodes.UNSUPPORTED, unsupported.code)

        val needsPermission = assertThrows(RuntimeFailure::class.java) {
            facade(FakeStorageDirFileSystem(accessible = false, directories = setOf("$root/Download")), storage)
                .requireGuestDirectory("/mnt/user/1")
        }
        // 未授权时必须先报「要授权」，而不是先报「目录不存在」：否则用户会去删白名单。
        assertEquals(StorageDirCodes.NEEDS_PERMISSION, needsPermission.code)
    }

    @Test
    fun `validates the subdirectory with the shared mailbox rules`() {
        val storage = seeded(StorageDirEntry("$root/Download", "Download"))
        val facade = facade(downloadable(), storage)

        // 校验发生在真正碰文件系统之前，所以这些断言不依赖宿主上是否真有该目录。
        listOf("..", "a/../b", "a/../../b", "/Download", "\\Download", "a//b", "a/./b").forEach { candidate ->
            val failure = assertThrows("应当拒绝：$candidate", RuntimeFailure::class.java) {
                facade.directory("/mnt/user/1", candidate)
            }
            // 码是共享目录自己的族，规则来自 RuntimeMailboxPolicy（两者不是平行实现）；
            // 具体原因后缀（「：路径分段无效」等）由共享校验器追加。
            assertEquals("应当拒绝：$candidate", StorageDirCodes.PATH_INVALID, failure.code)
            assertTrue("应当拒绝：$candidate", failure.message.orEmpty().startsWith("存储目录路径无效"))
        }
    }

    @Test
    fun `createFolder rejects an empty path before touching the file system`() {
        val storage = seeded(StorageDirEntry("$root/Download", "Download"))
        val facade = facade(downloadable(), storage)

        val failure = assertThrows(RuntimeFailure::class.java) {
            facade.createFolder("/mnt/user/1", "   ")
        }

        assertEquals(StorageDirCodes.PATH_INVALID, failure.code)
        assertEquals("存储目录新目录路径不能为空", failure.message)
    }

    @Test
    fun `keeps the shared storage code family when listing reaches the host`() {
        val storage = seeded(StorageDirEntry("$root/Download", "Download"))
        val facade = facade(downloadable(), storage)

        val failure = assertThrows(RuntimeFailure::class.java) {
            facade.directory("/mnt/user/1", null)
        }

        // 这一条只证明「接线正确」：列举确实进了共享浏览核心、并带着共享目录的码族，
        // 而不是投递区的码。JVM 上宿主没有 /storage/emulated/0/Download，所以必然失败；
        // 成功列举要在真机上验收（报告里列了验收点）。
        assertEquals(StorageDirCodes.NOT_A_DIRECTORY, failure.code)
        assertNotEquals(MailboxCodes.DIRECTORY_NOT_FOUND, failure.code)
    }
}
