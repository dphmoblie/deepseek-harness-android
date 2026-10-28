package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

/**
 * 分享候选路径的取舍规则。
 *
 * 预览面板给的相对地址可能是「相对工作区」也可能是「相对会话工作目录 `/root`」，
 * 两种口径都会给出候选。这里钉住：只有「工作区里没有这个文件」才换下一个候选，
 * 路径非法、安全目录流不可用等真实故障必须原样抛出，不能被误报成「文件不在工作区」。
 *
 * 取舍逻辑用假的复制函数测（开发机是 Windows，没有 `SecureDirectoryStream`）；
 * 真实复制链路由最后一个用例在支持安全目录流的平台上覆盖。
 */
class WorkspaceFileSharingTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val unavailable = RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用")

    private fun copier(
        calls: MutableList<String>,
        files: Map<String, File>,
    ): (String) -> File = { candidate ->
        calls += candidate
        files[candidate] ?: throw unavailable
    }

    @Test
    fun returnsTheFirstSuccessfulCandidate() {
        val first = File("first")
        val calls = mutableListOf<String>()
        val result = WorkspaceFileSharing.firstAvailable(
            listOf("1/notes.md", "notes.md"),
            copier(calls, mapOf("1/notes.md" to first, "notes.md" to File("second"))),
        )
        assertEquals(first, result)
        assertEquals(listOf("1/notes.md"), calls)
    }

    /** 会话工作目录就是工作区时，地址不带 `1/`：第一个候选不存在就落到第二个。 */
    @Test
    fun fallsBackWhenTheFileIsNotInTheWorkspace() {
        val second = File("second")
        val calls = mutableListOf<String>()
        val result = WorkspaceFileSharing.firstAvailable(
            listOf("1/docs/notes.md", "docs/notes.md"),
            copier(calls, mapOf("docs/notes.md" to second)),
        )
        assertEquals(second, result)
        assertEquals(listOf("1/docs/notes.md", "docs/notes.md"), calls)
    }

    @Test
    fun reportsNotInWorkspaceWhenNoCandidateExists() {
        val failure = assertThrows(RuntimeFailure::class.java) {
            WorkspaceFileSharing.firstAvailable(listOf("1/notes.md", "notes.md")) { throw unavailable }
        }
        assertEquals(WorkspaceFileSharing.NOT_IN_WORKSPACE, failure.code)
    }

    /** 路径非法属于调用方或被篡改的请求，不能靠换候选掩盖。 */
    @Test
    fun propagatesInvalidPathInsteadOfTryingTheNextCandidate() {
        val calls = mutableListOf<String>()
        val failure = assertThrows(RuntimeFailure::class.java) {
            WorkspaceFileSharing.firstAvailable(listOf("../secret.txt", "notes.md")) { candidate ->
                calls += candidate
                throw RuntimeFailure("WORKSPACE_PATH_INVALID", "工作区文件路径无效")
            }
        }
        assertEquals("WORKSPACE_PATH_INVALID", failure.code)
        assertEquals(listOf("../secret.txt"), calls)
    }

    /** 安全目录流不可用时是真故障，同样不能退化成「文件不在工作区」。 */
    @Test
    fun propagatesUnavailableSecureAccess() {
        val failure = assertThrows(RuntimeFailure::class.java) {
            WorkspaceFileSharing.firstAvailable(listOf("1/notes.md", "notes.md")) {
                throw RuntimeFailure("WORKSPACE_SECURE_ACCESS_UNAVAILABLE", "系统不支持安全访问工作区文件")
            }
        }
        assertEquals("WORKSPACE_SECURE_ACCESS_UNAVAILABLE", failure.code)
    }

    /** 真实链路：候选换成工作区里实际存在的那个文件，内容原样复制。 */
    @Test
    fun copiesTheRealWorkspaceFileMatchedByTheSecondCandidate() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        Files.write(workspace(root).resolve("notes.md"), "root-level".toByteArray())
        val copy = WorkspaceFileSharing.copyFirstAvailable(
            manager(root),
            listOf("1/notes.md", "notes.md"),
        )
        assertTrue(copy.isFile)
        assertEquals("root-level", copy.readText())
    }

    private fun manager(root: Path) = RuntimeWorkspaceFiles(
        root.resolve("runtime/current/root/1").toFile(),
        root.resolve("cache").toFile(),
        root.toFile(),
    )

    private fun supportsSecureStreams(root: Path): Boolean =
        Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> }

    private fun workspace(root: Path): Path = Files.createDirectories(root.resolve("runtime/current/root/1"))
}
