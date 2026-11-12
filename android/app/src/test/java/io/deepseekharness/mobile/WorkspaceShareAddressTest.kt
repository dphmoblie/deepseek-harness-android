package io.deepseekharness.mobile

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预览面板分享请求的解析契约。
 *
 * 页面传入的是完整资源地址，解析结果会直接交给工作区文件校验，因此这里要钉住：
 * 工作区外的绝对路径、目录跳转、编码斜杠与任何多余参数都不得变成可分享路径。
 */
class WorkspaceShareAddressTest {
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun request(address: String, suffix: String = ""): String =
        "dsh-share://file?address=" + encode(address) + suffix

    /** 页面从预览标题栏 `[data-textpreview-path]` 的 `title` 取到的绝对路径。 */
    private fun pathRequest(path: String, address: String? = null): String =
        "dsh-share://file?path=" + encode(path) +
            (address?.let { "&address=" + encode(it) } ?: "")

    /** 只关心「解析出哪个路径」的用例：取候选列表第一项（优先级最高、未剥层的那一个）。 */
    private fun relative(raw: String): String? = WorkspaceShareAddress.relativePaths(raw)?.first()

    @Test
    fun relativeSessionPathIsAccepted() {
        assertEquals(
            "src/main/App.kt",
            relative(
                request("dsh-resource://file/session/session-abc/src/main/App.kt"),
            ),
        )
    }

    @Test
    fun absolutePathInsideWorkspaceIsStripped() {
        assertEquals(
            "1/中文 文件.md",
            relative(
                request(
                    "dsh-resource://file/session/session-abc//root/1/%E4%B8%AD%E6%96%87%20%E6%96%87%E4%BB%B6.md",
                ),
            ),
        )
    }

    @Test
    fun sharesAnyFileInTheGuestHomeButNothingOutsideIt() {
        // 分享根是访客用户空间 `/root`：用户自己建的目录（`/root/dsh`）也要能分享，
        // 不再只认 `root/1`。
        assertEquals(
            "dsh/audit.md",
            relative(request("dsh-resource://file/session/session-abc//root/dsh/audit.md")),
        )
        assertNull(
            relative(
                request("dsh-resource://file/session/session-abc//etc/passwd"),
            ),
        )
        assertNull(
            relative(
                request("dsh-resource://file/session/session-abc//sdcard/notes.md"),
            ),
        )
    }

    @Test
    fun traversalSegmentsAreRejected() {
        assertNull(
            relative(
                request("dsh-resource://file/session/session-abc/../secret.txt"),
            ),
        )
        assertNull(
            relative(
                request("dsh-resource://file/session/session-abc/%2E%2E/secret.txt"),
            ),
        )
        assertNull(
            relative(
                request("dsh-resource://file/session/session-abc//root/1/../secret.txt"),
            ),
        )
    }

    @Test
    fun encodedSeparatorCannotCreateNewSegments() {
        assertNull(
            relative(
                request("dsh-resource://file/session/session-abc/a%2Fb.txt"),
            ),
        )
        assertNull(
            relative(
                request("dsh-resource://file/session/session-abc/a%5Cb.txt"),
            ),
        )
    }

    @Test
    fun plusSignIsKeptLiteral() {
        assertEquals(
            "C++ notes.md",
            relative(
                request("dsh-resource://file/session/session-abc/C++%20notes.md"),
            ),
        )
    }

    @Test
    fun onlyKnownParametersAreAccepted() {
        val address = "dsh-resource://file/session/session-abc/a.txt"
        assertNull(relative(request(address, "&open=true")))
        assertNull(relative("dsh-share://file?address="))
        assertNull(relative("dsh-share://file?unknown=$address"))
    }

    @Test
    fun wrongSchemeHostOrShapeIsRejected() {
        val address = "dsh-resource://file/session/session-abc/a.txt"
        assertNull(relative("https://file?address=$address"))
        assertNull(relative("dsh-share://other?address=$address"))
        assertNull(relative("dsh-share://file/path?address=$address"))
        assertNull(relative("dsh-share://user@file?address=$address"))
    }

    @Test
    fun nonSessionAddressesAreRejected() {
        assertNull(
            relative(
                request("dsh-resource://file/absolute//root/1/a.txt"),
            ),
        )
        assertNull(
            relative(
                request("dsh-resource://file/session/session-abc"),
            ),
        )
    }

    @Test
    fun malformedEncodingAndControlsAreRejected() {
        assertNull(relative("dsh-share://file?address=%zz"))
        assertNull(
            relative(
                request("dsh-resource://file/session/session-abc/%00file.txt"),
            ),
        )
        assertNull(
            relative(
                "dsh-share://file?address=dsh-resource%3A%2F%2Ffile%2Fsession%2Fsession-abc%2Fa%0Ab.txt",
            ),
        )
    }

    /**
     * 相对地址的两种口径：会话工作目录就是工作区（`notes.md`），
     * 或会话工作目录是 PRoot 的 `/root`（`1/notes.md`）。后者要额外给出剥层后的候选，
     * 由调用方按「工作区里确实存在这个文件」决定用哪一个。
     */
    @Test
    fun relativePathsAddsSessionCwdCandidateOnlyForOneSegmentPrefix() {
        assertEquals(
            listOf("1/notes.md", "notes.md"),
            WorkspaceShareAddress.relativePaths(
                request("dsh-resource://file/session/session-abc/1/notes.md"),
            ),
        )
        assertEquals(
            listOf("notes.md"),
            WorkspaceShareAddress.relativePaths(
                request("dsh-resource://file/session/session-abc/notes.md"),
            ),
        )
        // 只有完整的一层 `1/` 才剥：`1` 本身、`10/`、`1x/` 都不是会话工作目录前缀。
        assertEquals(
            listOf("1"),
            WorkspaceShareAddress.relativePaths(
                request("dsh-resource://file/session/session-abc/1"),
            ),
        )
        assertEquals(
            listOf("10/notes.md"),
            WorkspaceShareAddress.relativePaths(
                request("dsh-resource://file/session/session-abc/10/notes.md"),
            ),
        )
        assertEquals(
            listOf("1x/notes.md"),
            WorkspaceShareAddress.relativePaths(
                request("dsh-resource://file/session/session-abc/1x/notes.md"),
            ),
        )
    }

    /** 绝对路径已由前端按会话工作目录处理过，只对应一个确定路径，不应再给候选。 */
    @Test
    fun absoluteAddressHasExactlyOneCandidate() {
        assertEquals(
            listOf("1/中文 文件.md"),
            WorkspaceShareAddress.relativePaths(
                request(
                    "dsh-resource://file/session/session-abc//root/1/%E4%B8%AD%E6%96%87%20%E6%96%87%E4%BB%B6.md",
                ),
            ),
        )
        assertNull(
            WorkspaceShareAddress.relativePaths(
                request("dsh-resource://file/session/session-abc//etc/passwd"),
            ),
        )
    }

    /**
     * 标题栏的绝对路径来自宿主解析，不再依赖会话工作目录：文件在 `root/1` 下就必须命中，
     * 地址的相对基准是 `/root`、工作区本身还是工作区子目录都不影响结果。
     */
    @Test
    fun absoluteTitlePathIsPreferredOverAddress() {
        assertEquals(
            listOf("1/项目/报告 1.md"),
            WorkspaceShareAddress.relativePaths(
                pathRequest("/root/1/项目/报告 1.md", "dsh-resource://file/session/s/报告 1.md"),
            ),
        )
        assertEquals(
            listOf("1/sub/report.md"),
            WorkspaceShareAddress.relativePaths(
                pathRequest("/root/1/sub/report.md", "dsh-resource://file/session/s/report.md"),
            ),
        )
    }

    /** 绝对路径不在工作区时退回地址的旧口径；不能凭页面给的工作区外路径分享任何文件。 */
    @Test
    fun absoluteTitlePathOutsideWorkspaceFallsBackToAddress() {
        assertEquals(
            listOf("1/notes.md", "notes.md"),
            WorkspaceShareAddress.relativePaths(
                pathRequest("/mnt/user/1/notes.md", "dsh-resource://file/session/s/1/notes.md"),
            ),
        )
        assertNull(
            WorkspaceShareAddress.relativePaths(pathRequest("/mnt/user/1/notes.md")),
        )
    }

    @Test
    fun malformedAbsoluteTitlePathsAreRejected() {
        listOf(
            "/root/1/",
            "/etc/passwd",
            "notes.md",
            "/root/1/../secret.txt",
            "/root/1/a//b.txt",
            "/root/1/a\\b.txt",
        ).forEach { path ->
            assertNull(path, WorkspaceShareAddress.relativePaths(pathRequest(path)))
        }
        // `/root/1` 本身是目录：地址层只负责给出相对路径，目录由文件层按「不是普通文件」拒绝。
        assertEquals(listOf("1"), WorkspaceShareAddress.relativePaths(pathRequest("/root/1")))
    }

    @Test
    fun duplicateOrUnknownParametersAreRejected() {
        val path = encode("/root/1/a.txt")
        val address = encode("dsh-resource://file/session/s/a.txt")
        assertNull(WorkspaceShareAddress.relativePaths("dsh-share://file?path=$path&path=$path"))
        assertNull(WorkspaceShareAddress.relativePaths("dsh-share://file?address=$address&address=$address"))
        assertNull(WorkspaceShareAddress.relativePaths("dsh-share://file?path=$path&open=true"))
        assertNull(WorkspaceShareAddress.relativePaths("dsh-share://file?path=$path&address=$address&open=true"))
        assertNull(WorkspaceShareAddress.relativePaths("dsh-share://file?path="))
        assertNull(WorkspaceShareAddress.relativePaths("dsh-share://file?"))
    }
}
