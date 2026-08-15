package io.deepseekharness.mobile.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

object RuntimeFiles {
    fun existsNoFollow(file: File): Boolean = try {
        Os.lstat(file.absolutePath)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw RuntimeFailure("FILESYSTEM_ERROR", "无法检查运行时文件", error)
    }

    fun isDirectoryNoFollow(file: File): Boolean = try {
        OsConstants.S_ISDIR(Os.lstat(file.absolutePath).st_mode)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw RuntimeFailure("FILESYSTEM_ERROR", "无法检查运行时目录", error)
    }

    fun deleteTreeNoFollow(root: File, allowedParent: File) {
        if (!isDirectoryNoFollow(allowedParent)) {
            throw RuntimeFailure("RESET_SCOPE_INVALID", "运行时父路径不是可信目录")
        }
        val normalizedParent = allowedParent.toPath().toAbsolutePath().normalize()
        val normalizedRoot = root.toPath().toAbsolutePath().normalize()
        if (normalizedRoot == normalizedParent || !normalizedRoot.startsWith(normalizedParent)) {
            throw RuntimeFailure("RESET_SCOPE_INVALID", "拒绝删除运行时目录之外的路径")
        }
        deleteEntry(root)
    }

    private fun deleteEntry(file: File) {
        val stat = try {
            Os.lstat(file.absolutePath)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法检查运行时文件", error)
        }
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            val children = file.listFiles()
                ?: throw RuntimeFailure("FILESYSTEM_ERROR", "无法读取运行时目录")
            for (child in children) deleteEntry(child)
        }
        if (!file.delete()) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法删除运行时文件")
        }
    }
}
