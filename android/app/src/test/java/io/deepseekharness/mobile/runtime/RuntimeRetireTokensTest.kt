package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

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
        assertEquals("FILESYSTEM", RuntimeRetireTokens.of(FileSystemException("generic")))
        assertEquals(
            "DIR_ITER",
            RuntimeRetireTokens.of(DirectoryIteratorException(IOException("iterate"))),
        )
        assertEquals("UNSUPPORTED", RuntimeRetireTokens.of(UnsupportedOperationException("no")))
        assertEquals(RuntimeRetireTokens.UNKNOWN, RuntimeRetireTokens.of(IllegalStateException("?")))
    }

    /**
     * 真机上抛的是 `java.nio.file.FileSystemException` 这一族，子类必须各有自己的状态码：
     * 全都塌成 `FILESYSTEM` 就看不出是权限被拒、目录非空还是条目已经消失。
     */
    @Test
    fun `FileSystemException 的子类先于父类判定`() {
        assertEquals("ACCESS_DENIED", RuntimeRetireTokens.of(AccessDeniedException("文件", "denied", "权限被拒")))
        assertEquals("NOT_EMPTY", RuntimeRetireTokens.of(DirectoryNotEmptyException("目录非空")))
        assertEquals("NO_SUCH_FILE", RuntimeRetireTokens.of(NoSuchFileException("missing")))
        assertEquals("NOT_A_DIR", RuntimeRetireTokens.of(NotDirectoryException("not-a-dir")))
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
