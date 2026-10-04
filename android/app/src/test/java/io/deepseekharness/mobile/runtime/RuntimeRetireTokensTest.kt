package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.NoSuchFileException

/**
 * 清理失败的状态码映射：真机上只能靠这些 token 区分权限、占用与 IO 故障，所以固定住它们的取值。
 */
class RuntimeRetireTokensTest {
    @Test
    fun `errno 映射成固定状态码`() {
        assertEquals("EACCES", RuntimeRetireTokens.errnoToken(13))
        assertEquals("EPERM", RuntimeRetireTokens.errnoToken(1))
        assertEquals("EBUSY", RuntimeRetireTokens.errnoToken(16))
        assertEquals("ENOTEMPTY", RuntimeRetireTokens.errnoToken(39))
        assertEquals("EIO", RuntimeRetireTokens.errnoToken(5))
        assertEquals("EROFS", RuntimeRetireTokens.errnoToken(30))
        assertEquals("ENAMETOOLONG", RuntimeRetireTokens.errnoToken(36))
        assertEquals("ERRNO_OTHER", RuntimeRetireTokens.errnoToken(12345))
    }

    @Test
    fun `非 errno 的异常按类别映射`() {
        assertEquals("SECURITY", RuntimeRetireTokens.of(SecurityException("没有权限")))
        assertEquals("IO", RuntimeRetireTokens.of(IOException("io")))
        assertEquals("FILESYSTEM", RuntimeRetireTokens.of(NoSuchFileException("missing")))
        assertEquals(
            "DIR_ITER",
            RuntimeRetireTokens.of(DirectoryIteratorException(IOException("iterate"))),
        )
        assertEquals("UNSUPPORTED", RuntimeRetireTokens.of(UnsupportedOperationException("no")))
        assertEquals(RuntimeRetireTokens.UNKNOWN, RuntimeRetireTokens.of(IllegalStateException("?")))
    }

    @Test
    fun `RuntimeFailure 里的 cause 参与映射`() {
        assertEquals(RuntimeRetireTokens.UNKNOWN, RuntimeRetireTokens.of(RuntimeFailure("FILESYSTEM_ERROR", "无法检查运行时文件")))
        assertEquals(
            "IO",
            RuntimeRetireTokens.of(RuntimeFailure("FILESYSTEM_ERROR", "无法安全清理运行时文件", IOException("io"))),
        )
    }

    @Test
    fun `状态码符合诊断日志契约`() {
        val pattern = Regex("^[A-Z][A-Z0-9_]{0,47}$")
        val samples = listOf(
            RuntimeRetireTokens.errnoToken(13),
            RuntimeRetireTokens.errnoToken(16),
            RuntimeRetireTokens.errnoToken(99999),
            RuntimeRetireTokens.of(SecurityException("x")),
            RuntimeRetireTokens.of(IllegalStateException("x")),
        )
        for (token in samples) {
            assertEquals("状态码 $token 必须匹配诊断日志契约", true, pattern.matches(token))
        }
    }
}
