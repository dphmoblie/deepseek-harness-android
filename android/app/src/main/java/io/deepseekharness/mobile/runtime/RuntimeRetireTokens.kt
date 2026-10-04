package io.deepseekharness.mobile.runtime

import android.system.ErrnoException
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileSystemException

/**
 * 把「清理运行时残留失败」的原因压缩成诊断日志能容纳的状态码。
 *
 * 诊断日志契约（见 `diagnostics/DiagnosticPolicy.kt`）只允许 `[A-Z][A-Z0-9_]{0,47}` 形式的状态码，
 * 不允许中文文案、路径或 errno 数字，所以这里把异常种类与常见 errno 映射成固定的 token，
 * 便于在真机上区分权限问题（EACCES/EPERM）、占用（EBUSY）、IO 故障与删除预算上限等。
 */
internal object RuntimeRetireTokens {
    /** 未知错误类别的兜底 token。 */
    const val UNKNOWN: String = "UNKNOWN"

    fun of(error: Throwable): String {
        // RuntimeFailure 只是把底层异常包了一层。
        val root = (error as? RuntimeFailure)?.cause ?: error
        // errno 可能在根异常上，也可能在它再包一层的 cause 上（例如包装后的 ErrnoException）。
        errnoOf(root)?.let { return errnoToken(it) }
        errnoOf(root.cause)?.let { return errnoToken(it) }
        return classify(root)
    }

    private fun errnoOf(error: Throwable?): Int? = (error as? ErrnoException)?.errno

    private fun classify(error: Throwable): String = when (error) {
        is SecurityException -> "SECURITY"
        // DirectoryIteratorException 自带 IOException cause，必须先于 IOException 判断。
        is DirectoryIteratorException -> "DIR_ITER"
        is FileSystemException -> "FILESYSTEM"
        is IOException -> "IO"
        is UnsupportedOperationException -> "UNSUPPORTED"
        else -> UNKNOWN
    }

    /**
     * errno 到 token 的映射；只覆盖清理路径上真正可能出现的取值。
     *
     * 这里写 Linux 通用 errno 数值而不是 `OsConstants.*`：单元测试跑在 JVM 上，
     * `android.system.OsConstants` 的字段是桩值（多个常量相等），用符号常量既测不出映射，
     * 也会让 `when` 出现重复分支。数值在 arm64/arm64-v8a 上与 bionic 一致。
     */
    fun errnoToken(errno: Int): String = when (errno) {
        EPERM -> "EPERM"
        ENOENT -> "ENOENT"
        EIO -> "EIO"
        EACCES -> "EACCES"
        EBUSY -> "EBUSY"
        ENOTDIR -> "ENOTDIR"
        EISDIR -> "EISDIR"
        EINVAL -> "EINVAL"
        ENOSPC -> "ENOSPC"
        EROFS -> "EROFS"
        ENAMETOOLONG -> "ENAMETOOLONG"
        ENOTEMPTY -> "ENOTEMPTY"
        else -> "ERRNO_OTHER"
    }

    // Linux 通用 errno 取值（asm-generic），与设备上的 bionic 一致。
    private const val EPERM = 1
    private const val ENOENT = 2
    private const val EIO = 5
    private const val EACCES = 13
    private const val EBUSY = 16
    private const val ENOTDIR = 20
    private const val EISDIR = 21
    private const val EINVAL = 22
    private const val ENOSPC = 28
    private const val EROFS = 30
    private const val ENAMETOOLONG = 36
    private const val ENOTEMPTY = 39
}
