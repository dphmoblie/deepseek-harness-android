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
    private fun request(address: String, suffix: String = ""): String =
        "dsh-share://file?address=" +
            URLEncoder.encode(address, "UTF-8").replace("+", "%20") +
            suffix

    @Test
    fun relativeSessionPathIsAccepted() {
        assertEquals(
            "src/main/App.kt",
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc/src/main/App.kt"),
            ),
        )
    }

    @Test
    fun absolutePathInsideWorkspaceIsStripped() {
        assertEquals(
            "中文 文件.md",
            WorkspaceShareAddress.relativePath(
                request(
                    "dsh-resource://file/session/session-abc//root/1/%E4%B8%AD%E6%96%87%20%E6%96%87%E4%BB%B6.md",
                ),
            ),
        )
    }

    @Test
    fun absolutePathOutsideWorkspaceIsRejected() {
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc//etc/passwd"),
            ),
        )
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc//root/10/secret.txt"),
            ),
        )
    }

    @Test
    fun traversalSegmentsAreRejected() {
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc/../secret.txt"),
            ),
        )
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc/%2E%2E/secret.txt"),
            ),
        )
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc//root/1/../secret.txt"),
            ),
        )
    }

    @Test
    fun encodedSeparatorCannotCreateNewSegments() {
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc/a%2Fb.txt"),
            ),
        )
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc/a%5Cb.txt"),
            ),
        )
    }

    @Test
    fun plusSignIsKeptLiteral() {
        assertEquals(
            "C++ notes.md",
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc/C++%20notes.md"),
            ),
        )
    }

    @Test
    fun onlyTheAddressParameterIsAccepted() {
        val address = "dsh-resource://file/session/session-abc/a.txt"
        assertNull(WorkspaceShareAddress.relativePath(request(address, "&open=true")))
        assertNull(WorkspaceShareAddress.relativePath("dsh-share://file?path=$address"))
        assertNull(WorkspaceShareAddress.relativePath("dsh-share://file?address="))
    }

    @Test
    fun wrongSchemeHostOrShapeIsRejected() {
        val address = "dsh-resource://file/session/session-abc/a.txt"
        assertNull(WorkspaceShareAddress.relativePath("https://file?address=$address"))
        assertNull(WorkspaceShareAddress.relativePath("dsh-share://other?address=$address"))
        assertNull(WorkspaceShareAddress.relativePath("dsh-share://file/path?address=$address"))
        assertNull(WorkspaceShareAddress.relativePath("dsh-share://user@file?address=$address"))
    }

    @Test
    fun nonSessionAddressesAreRejected() {
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/absolute//root/1/a.txt"),
            ),
        )
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc"),
            ),
        )
    }

    @Test
    fun malformedEncodingAndControlsAreRejected() {
        assertNull(WorkspaceShareAddress.relativePath("dsh-share://file?address=%zz"))
        assertNull(
            WorkspaceShareAddress.relativePath(
                request("dsh-resource://file/session/session-abc/%00file.txt"),
            ),
        )
        assertNull(
            WorkspaceShareAddress.relativePath(
                "dsh-share://file?address=dsh-resource%3A%2F%2Ffile%2Fsession%2Fsession-abc%2Fa%0Ab.txt",
            ),
        )
    }
}
