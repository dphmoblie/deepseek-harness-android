package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 投递区路径策略的纯逻辑用例：不接触文件系统，也不依赖 `/sdcard`。
 *
 * 这些断言对应 `docs/mobile-acceptance-checklist.md` 第 10 节 R2 的判定口径：
 * `..` 条目、绝对路径条目、绝对符号链接、越出落点的相对符号链接**必须被拒绝**。
 */
class RuntimeMailboxPolicyTest {
    private val root: Path = Paths.get(System.getProperty("java.io.tmpdir"), "mailbox-staging")
        .toAbsolutePath()
        .normalize()

    @Test
    fun acceptsOrdinaryEntryNames() {
        assertEquals("a/b.txt", RuntimeMailboxPolicy.normalizeEntryName("a/b.txt"))
        assertEquals("a/b.txt", RuntimeMailboxPolicy.normalizeEntryName("./a/b.txt"))
        assertEquals("a/b.txt", RuntimeMailboxPolicy.normalizeEntryName("././a/b.txt"))
        assertEquals("dir", RuntimeMailboxPolicy.normalizeEntryName("dir/"))
        assertEquals("dir/sub", RuntimeMailboxPolicy.normalizeEntryName("./dir/sub/"))
    }

    @Test
    fun rejectsTraversalAbsoluteAndMalformedEntryNames() {
        listOf(
            "../outside",
            "a/../../outside",
            "/etc/passwd",
            "a\\b",
            "a//b",
            "a/./b",
            ".",
            "./",
            "",
            "a/\u0000b",
            "a\nb",
            "x".repeat(RuntimeMailboxLimits.MAX_PATH_CHARS + 1),
            (1..(RuntimeMailboxLimits.MAX_PATH_DEPTH + 1)).joinToString("/") { "d$it" },
            "y".repeat(RuntimeMailboxLimits.MAX_COMPONENT_CHARS + 1),
        ).forEach { value ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                RuntimeMailboxPolicy.normalizeEntryName(value)
            }
            assertEquals("输入=" + value.take(24), MailboxCodes.PATH_INVALID, failure.code)
        }
    }

    @Test
    fun rejectsAbsoluteSymlinkTargets() {
        listOf("/root/.dsh", "/root/1/secret", "/etc/passwd").forEach { target ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                RuntimeMailboxPolicy.normalizeLinkTarget(root, "link", target)
            }
            assertEquals(MailboxCodes.LINK_ABSOLUTE, failure.code)
        }
    }

    @Test
    fun rejectsSymlinkTargetsEscapingTheDestination() {
        // 链接落在 root/a/b/link：需要三个 `..` 才能越出落点。
        listOf("../../../root/.dsh", "../../../../etc/passwd", "../../../../outside").forEach { target ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                RuntimeMailboxPolicy.normalizeLinkTarget(root, "a/b/link", target)
            }
            assertEquals(MailboxCodes.LINK_ESCAPE, failure.code)
        }
    }

    @Test
    fun keepsRelativeSymlinkTargetsInsideTheDestination() {
        assertEquals("b.txt", RuntimeMailboxPolicy.normalizeLinkTarget(root, "a/link", "b.txt"))
        assertEquals("../b.txt", RuntimeMailboxPolicy.normalizeLinkTarget(root, "a/sub/link", "../b.txt"))
        assertEquals(".", RuntimeMailboxPolicy.normalizeLinkTarget(root, "a/link", "."))
    }

    @Test
    fun rejectsMalformedSymlinkTargets() {
        listOf("", "a\\b", "x".repeat(RuntimeMailboxLimits.MAX_PATH_CHARS + 1), "a\nb").forEach { target ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                RuntimeMailboxPolicy.normalizeLinkTarget(root, "link", target)
            }
            assertEquals(MailboxCodes.LINK_INVALID, failure.code)
        }
    }

    @Test
    fun normalizesHardlinkSourcesFromTheArchiveRoot() {
        assertEquals("usr/bin/tool", RuntimeMailboxPolicy.normalizeHardlinkSource("/usr/bin/tool"))
        assertEquals("usr/bin/tool", RuntimeMailboxPolicy.normalizeHardlinkSource("./usr/bin/tool"))
        listOf("", "/").forEach { value ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                RuntimeMailboxPolicy.normalizeHardlinkSource(value)
            }
            assertEquals(MailboxCodes.LINK_INVALID, failure.code)
        }
        // 带 `..` 的硬链接源走与条目名同一套校验，因此报的是路径码。
        listOf("..", "/../outside").forEach { value ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                RuntimeMailboxPolicy.normalizeHardlinkSource(value)
            }
            assertEquals(MailboxCodes.PATH_INVALID, failure.code)
        }
    }

    @Test
    fun treatsBlankExportSubdirectoryAsTheWholeWorkspace() {
        assertNull(RuntimeMailboxPolicy.normalizeSubdirectory(null))
        assertNull(RuntimeMailboxPolicy.normalizeSubdirectory(""))
        assertNull(RuntimeMailboxPolicy.normalizeSubdirectory("   "))
        assertEquals("proj/src", RuntimeMailboxPolicy.normalizeSubdirectory(" proj/src "))
    }

    @Test
    fun rejectsExportSubdirectoryTraversal() {
        listOf("../outside", "/abs", "proj/../../outside", "proj/./src").forEach { value ->
            assertThrows(RuntimeFailure::class.java) {
                RuntimeMailboxPolicy.normalizeSubdirectory(value)
            }
        }
    }

    @Test
    fun resolvesEntriesInsideTheDestinationOnly() {
        assertEquals(root.resolve("a/b.txt"), RuntimeMailboxPolicy.resolveEntry(root, "a/b.txt"))
        assertThrows(RuntimeFailure::class.java) {
            RuntimeMailboxPolicy.resolveEntry(root, "..")
        }
    }

    @Test
    fun pinsTheDocumentedLayout() {
        assertEquals("/storage/emulated/0/Documents/DSH/inbox", layoutPath(RuntimeMailboxLayout.INBOX_DIRECTORY))
        assertEquals("/storage/emulated/0/Documents/DSH/outbox", layoutPath(RuntimeMailboxLayout.OUTBOX_DIRECTORY))
        // 绑定源一律使用真实路径：`/sdcard` 是符号链接，不能出现在布局常量里。
        assertFalse(RuntimeMailboxLayout.PUBLIC_DOCUMENTS.startsWith("/sdcard"))
        assertEquals("/mnt/inbox", RuntimeMailboxLayout.GUEST_INBOX)
        assertEquals("/mnt/outbox", RuntimeMailboxLayout.GUEST_OUTBOX)
        assertEquals("mailbox-import", RuntimeMailboxLayout.IMPORT_DIRECTORY)
        assertEquals("dsh-workspace.tar", RuntimeMailboxLayout.EXPORT_TAR_NAME)
        assertEquals("dsh-workspace.manifest.json", RuntimeMailboxLayout.EXPORT_MANIFEST_NAME)
        assertEquals("dsh-workspace.tar.sha256", RuntimeMailboxLayout.EXPORT_SHA256_NAME)
    }

    private fun layoutPath(name: String): String =
        "${RuntimeMailboxLayout.PUBLIC_DOCUMENTS}/${RuntimeMailboxLayout.APP_DIRECTORY}/$name"
}
