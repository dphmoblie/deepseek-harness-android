package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 共享浏览核心的用例。
 *
 * 为什么断言落在这里而不是插件层：投递区（inbox/outbox）与共享目录（`/mnt/user/<序号>`）
 * 现在共用同一段列举/排序/截断/建目录代码，链路之间只差三样东西——错误码族、
 * 错误文案、以及「哪些名字可见」。所以下面钉住的正是**共性**（排序口径、256 截断、
 * 符号链接不进列表、`..` 拒绝、目录不存在时逐级判定）与**两处有意的分叉**
 * （投递区隐藏自己的探针与导出暂存文件，共享目录不隐藏任何用户文件）。
 *
 * 这些用例用真实的临时目录跑：`RuntimeDirectoryBrowser` 是纯 `java.nio` 代码，
 * 在 JVM 上能真读真写，因此「截断是不是真的截在 256」「建目录是不是真的建出来了」
 * 是被实测的，不是走查出来的。
 */
class RuntimeDirectoryBrowserTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val mailbox = RuntimeDirectoryBrowser.MAILBOX
    private val shared = RuntimeDirectoryBrowser.SHARED_STORAGE

    /** 造一个已知大小的普通文件：条目里的 `bytes` 必须来自真实文件长度。 */
    private fun file(name: String, bytes: Int = 1): File {
        val target = File(temporaryFolder.root, name)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(bytes))
        return target
    }

    @Test
    fun `lists directories before files and sorts each group by name`() {
        file("b.txt", 2)
        file("a.txt", 1)
        File(temporaryFolder.root, "zz").mkdirs()
        File(temporaryFolder.root, "aa").mkdirs()

        val snapshot = RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, null, mailbox)

        assertEquals(
            listOf("aa", "zz", "a.txt", "b.txt"),
            snapshot.entries.map { it.name },
        )
        assertEquals(listOf("directory", "directory", "file", "file"), snapshot.entries.map { it.kind })
        assertEquals(listOf(0L, 0L, 1L, 2L), snapshot.entries.map { it.bytes })
        // 目录大小没有意义，投递区既有实现固定写 0；这条断言防的是「顺手改成真实长度」。
        assertTrue(snapshot.entries.take(2).all { it.bytes == 0L })
        assertNull(snapshot.path)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun `keeps the requested relative path in the snapshot`() {
        File(temporaryFolder.root, "sub/inner").mkdirs()

        val snapshot = RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, "sub", mailbox)

        assertEquals("sub", snapshot.path)
        assertEquals(listOf("inner"), snapshot.entries.map { it.name })
    }

    @Test
    fun `truncates at the shared entry limit and flags it`() {
        val limit = RuntimeMailboxLimits.MAX_DIRECTORY_ENTRIES
        repeat(limit + 1) { index -> file("f%04d.txt".format(index)) }

        val snapshot = RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, null, shared)

        // 截断必须同时体现在两条线上：列表长度封顶、并且 truncated 为真，
        // 否则前端会以为「就这么多」而不再提示还有内容。
        assertEquals(limit, snapshot.entries.size)
        assertTrue(snapshot.truncated)
        // 多读一条（limit + 1）才发现溢出：正好 limit 条时不应报截断。
        val exact = TemporaryFolder()
        exact.create()
        try {
            repeat(limit) { index -> File(exact.root, "g%04d.txt".format(index)).writeBytes(ByteArray(1)) }
            val complete = RuntimeDirectoryBrowser.snapshot(exact.root, null, shared)
            assertEquals(limit, complete.entries.size)
            assertFalse(complete.truncated)
        } finally {
            exact.delete()
        }
    }

    @Test
    fun `walks down real directories and rejects a missing level`() {
        File(temporaryFolder.root, "keep/here").mkdirs()

        // 逐级判定：中间级缺失时报「目录不存在」，而不是笼统的「读不了」。
        val viaMailbox = assertThrows(RuntimeFailure::class.java) {
            RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, "keep/absent", mailbox)
        }
        assertEquals(MailboxCodes.DIRECTORY_NOT_FOUND, viaMailbox.code)
        assertEquals("投递区目录不存在", viaMailbox.message)

        val viaShared = assertThrows(RuntimeFailure::class.java) {
            RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, "keep/absent", shared)
        }
        // 同一条规则，两条链路各自报自己的码族与文案——这正是「不另写一套校验」的可见结果。
        assertEquals(StorageDirCodes.NOT_A_DIRECTORY, viaShared.code)
        assertEquals("存储目录不存在或已不是真实目录", viaShared.message)
    }

    @Test
    fun `treats an unusable root as its own failure per chain`() {
        val missing = File(temporaryFolder.root, "not-there")

        val viaMailbox = assertThrows(RuntimeFailure::class.java) {
            RuntimeDirectoryBrowser.snapshot(missing, null, mailbox)
        }
        assertEquals(MailboxCodes.DIRECTORY_NOT_FOUND, viaMailbox.code)
        assertEquals("投递区根目录不可用", viaMailbox.message)

        val viaShared = assertThrows(RuntimeFailure::class.java) {
            RuntimeDirectoryBrowser.snapshot(missing, null, shared)
        }
        assertEquals(StorageDirCodes.NOT_A_DIRECTORY, viaShared.code)
        assertEquals("存储目录当前不可用", viaShared.message)
    }

    @Test
    fun `hides mailbox bookkeeping entries but never hides user dotfiles`() {
        file(".dsh-export-1234")
        file(MailboxInputSelection.PROBE_PREFIX + "probe")
        file(".user-note")

        val viaMailbox = RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, null, mailbox)
        // 投递区保持既有可见性口径：自己的导出暂存与探针文件不该出现在用户看到的列表里。
        assertEquals(listOf(".user-note"), viaMailbox.entries.map { it.name })

        val viaShared = RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, null, shared)
        // 共享目录是用户自己的目录，同名文件属于用户，必须原样列出。
        assertEquals(
            listOf(".dsh-export-1234", MailboxInputSelection.PROBE_PREFIX + "probe", ".user-note"),
            viaShared.entries.map { it.name },
        )
    }

    @Test
    fun `keeps symbolic links out of the listing and out of directory resolution`() {
        File(temporaryFolder.root, "real-dir").mkdirs()
        val realFile = file("real.txt", 3)
        createSymbolicLinkOrSkip(File(temporaryFolder.root, "link-dir").toPath(), File(temporaryFolder.root, "real-dir").toPath())
        createSymbolicLinkOrSkip(File(temporaryFolder.root, "link-file").toPath(), realFile.toPath())

        val snapshot = RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, null, shared)

        // 符号链接既不按目录也不按文件列出：口径与投递区快照的 isRegularFileNoFollow 一致，
        // 否则一条指向外部的链接就会变成「能点进去的目录」。
        assertEquals(listOf("real-dir", "real.txt"), snapshot.entries.map { it.name })

        val refusal = assertThrows(RuntimeFailure::class.java) {
            RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, "link-dir", shared)
        }
        assertEquals(StorageDirCodes.NOT_A_DIRECTORY, refusal.code)
    }

    @Test
    fun `creates nested folders so that the next snapshot can see them`() {
        RuntimeDirectoryBrowser.createFolder(temporaryFolder.root, "a/b", shared)

        assertTrue(File(temporaryFolder.root, "a/b").isDirectory)
        // 中间层不存在时自动补建（与投递区既有 createDirectoriesNoFollow 行为一致），
        // 然后才轮到调用方取「建完即回」的新列表——那条链路在门面层（见 RuntimeStorageDirectoryTest）。
        assertTrue(RuntimeDirectoryBrowser.snapshot(temporaryFolder.root, "a", shared).entries.any { it.name == "b" })
    }

    @Test
    fun `refuses to create a folder that already exists, per chain`() {
        File(temporaryFolder.root, "dup").mkdirs()

        val viaShared = assertThrows(RuntimeFailure::class.java) {
            RuntimeDirectoryBrowser.createFolder(temporaryFolder.root, "dup", shared)
        }
        assertEquals(StorageDirCodes.FOLDER_EXISTS, viaShared.code)
        assertEquals("存储目录下已存在同名条目", viaShared.message)

        val viaMailbox = assertThrows(RuntimeFailure::class.java) {
            RuntimeDirectoryBrowser.createFolder(temporaryFolder.root, "dup", mailbox)
        }
        assertEquals(MailboxCodes.FOLDER_EXISTS, viaMailbox.code)
        assertEquals("投递区目录已存在", viaMailbox.message)
    }

    @Test
    fun `refuses to create a folder over an existing file`() {
        file("occupied.txt")

        val failure = assertThrows(RuntimeFailure::class.java) {
            RuntimeDirectoryBrowser.createFolder(temporaryFolder.root, "occupied.txt", shared)
        }

        // 同名文件与同名目录都是「已存在」，不能把文件悄悄换成目录。
        assertEquals(StorageDirCodes.FOLDER_EXISTS, failure.code)
        assertTrue(File(temporaryFolder.root, "occupied.txt").isFile)
    }

    @Test
    fun `rejects escaping names through the shared mailbox rules, per chain`() {
        // 校验规则只有一份（RuntimeMailboxPolicy.validateRelative），两条链路只换码与文案：
        // 所以「同一个非法输入」在两个链路上都拒绝，但报的是各自的码。
        val viaShared = assertThrows(RuntimeFailure::class.java) {
            RuntimeMailboxPolicy.normalizeSubdirectory("..", StorageDirCodes.PATH_INVALID, "存储目录路径无效")
        }
        assertEquals(StorageDirCodes.PATH_INVALID, viaShared.code)
        // 文案前缀来自链路，后缀（「：路径分段无效」）来自共享校验器——这正说明规则确实只有一份，
        // 共享目录侧没有另写一套平行的判定与措辞。
        val text = viaShared.message.orEmpty()
        assertTrue(text.startsWith("存储目录路径无效"))
        assertTrue(text.endsWith("路径分段无效"))

        val viaMailbox = assertThrows(RuntimeFailure::class.java) {
            RuntimeMailboxPolicy.normalizeSubdirectory("..")
        }
        assertEquals(MailboxCodes.PATH_INVALID, viaMailbox.code)

        listOf("a/../../b", "/etc/passwd", "\\windows", "a//b", "a/./b").forEach { candidate ->
            val sharedFailure = assertThrows("应当拒绝：$candidate", RuntimeFailure::class.java) {
                RuntimeMailboxPolicy.normalizeSubdirectory(candidate, StorageDirCodes.PATH_INVALID, "存储目录路径无效")
            }
            assertEquals(StorageDirCodes.PATH_INVALID, sharedFailure.code)
            val mailboxFailure = assertThrows("应当拒绝：$candidate", RuntimeFailure::class.java) {
                RuntimeMailboxPolicy.normalizeSubdirectory(candidate)
            }
            assertEquals(MailboxCodes.PATH_INVALID, mailboxFailure.code)
        }

        // 空白不是「非法」而是「没给」：两条链路都返回 null（根目录），不能报错。
        assertNull(RuntimeMailboxPolicy.normalizeSubdirectory("   ", StorageDirCodes.PATH_INVALID, "存储目录路径无效"))
        assertNull(RuntimeMailboxPolicy.normalizeSubdirectory("   "))
    }

    /** 与既有测试同款：Windows 上建符号链接可能没权限，那就跳过而不是让用例变红。 */
    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (error: Throwable) {
            if (error !is IOException && error !is UnsupportedOperationException && error !is SecurityException) {
                throw error
            }
            assumeNoException("Symbolic links are unavailable on this host", error)
        }
    }
}
