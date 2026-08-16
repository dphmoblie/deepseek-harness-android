package io.deepseekharness.mobile.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

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
        val normalizedParent = allowedParent.toPath().toAbsolutePath().normalize()
        val normalizedRoot = root.toPath().toAbsolutePath().normalize()
        if (normalizedRoot.parent != normalizedParent) {
            throw RuntimeFailure("RESET_SCOPE_INVALID", "拒绝删除运行时目录之外的路径")
        }
        val parentOfAllowed = normalizedParent.parent
            ?: throw RuntimeFailure("RESET_SCOPE_INVALID", "运行时父路径没有可信上级目录")
        val allowedName = normalizedParent.fileName
            ?: throw RuntimeFailure("RESET_SCOPE_INVALID", "运行时父路径无效")
        val rootName = normalizedRoot.fileName
            ?: throw RuntimeFailure("RESET_SCOPE_INVALID", "运行时删除路径无效")

        try {
            Files.newDirectoryStream(parentOfAllowed).use { outerStream ->
                val outer = requireSecureDirectoryStream(outerStream)
                val allowedAttributes = readAttributesNoFollow(outer, allowedName) ?: return
                if (!allowedAttributes.isDirectory || allowedAttributes.isSymbolicLink) {
                    throw RuntimeFailure("RESET_SCOPE_INVALID", "运行时父路径不是可信目录")
                }
                val allowedStream = openDirectoryNoFollow(outer, allowedName) ?: return
                allowedStream.use {
                    deleteEntry(allowedStream, rootName, 0, DeleteBudget())
                }
            }
        } catch (error: RuntimeFailure) {
            throw error
        } catch (error: DirectoryIteratorException) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法遍历运行时目录", error.cause ?: error)
        } catch (_: NoSuchFileException) {
            // The trusted parent or runtime parent disappeared before its descriptor was opened.
            return
        } catch (error: UnsupportedOperationException) {
            throw RuntimeFailure("FILESYSTEM_SECURE_DELETE_UNAVAILABLE", "系统不支持安全清理运行时文件", error)
        } catch (error: SecurityException) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "没有权限安全清理运行时文件", error)
        } catch (error: IOException) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法安全清理运行时文件", error)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun requireSecureDirectoryStream(stream: DirectoryStream<Path>): SecureDirectoryStream<Path> {
        if (stream !is SecureDirectoryStream<*>) {
            throw RuntimeFailure("FILESYSTEM_SECURE_DELETE_UNAVAILABLE", "系统不支持安全清理运行时文件")
        }
        // DirectoryStream<Path> fixes the SecureDirectoryStream element type to Path.
        return stream as SecureDirectoryStream<Path>
    }

    private fun readAttributesNoFollow(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes? {
        val view = parent.getFileAttributeView(
            name,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: throw RuntimeFailure("FILESYSTEM_SECURE_DELETE_UNAVAILABLE", "系统不支持安全读取运行时文件属性")
        return try {
            view.readAttributes()
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun openDirectoryNoFollow(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ): SecureDirectoryStream<Path>? = try {
        parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun deleteEntry(
        parent: SecureDirectoryStream<Path>,
        name: Path,
        depth: Int,
        budget: DeleteBudget,
    ) {
        if (depth > MAX_DELETE_DEPTH || ++budget.entries > MAX_DELETE_ENTRIES) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "运行时文件树超过安全清理限制")
        }
        val attributes = readAttributesNoFollow(parent, name) ?: return
        if (attributes.isDirectory && !attributes.isSymbolicLink) {
            val directory = openDirectoryNoFollow(parent, name) ?: return
            directory.use {
                for (entry in directory) {
                    val childName = entry.fileName
                        ?: throw RuntimeFailure("FILESYSTEM_ERROR", "运行时目录返回了无效条目")
                    if (childName.nameCount != 1 || childName.toString() == "." || childName.toString() == "..") {
                        throw RuntimeFailure("FILESYSTEM_ERROR", "运行时目录返回了越界条目")
                    }
                    deleteEntry(directory, childName, depth + 1, budget)
                }
            }
            try {
                parent.deleteDirectory(name)
            } catch (_: NoSuchFileException) {
                return
            }
        } else {
            try {
                parent.deleteFile(name)
            } catch (_: NoSuchFileException) {
                return
            }
        }
    }

    private class DeleteBudget(var entries: Int = 0)

    private const val MAX_DELETE_DEPTH = 256
    private const val MAX_DELETE_ENTRIES = 250_000
}
