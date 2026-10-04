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

    /**
     * 不跟随符号链接的兜底删除：只用来回收已经不再需要的运行时目录（`stale-*` 残留等）。
     *
     * 与 [deleteTreeNoFollow] 的差别是**不使用 [SecureDirectoryStream]**：真机上严格删除器在某些树形上
     * 会直接抛 `java.nio.file.FileSystemException`，于是每次更新只能把上一份 rootfs 改名挪开，
     * 约 960 MB 永远收不回来。这里改用 java.nio 的 `NOFOLLOW_LINKS` 属性判断与 [Files.delete]：
     * 目录先递归清空再删，符号链接只删链接本身、绝不下降；作用域校验、深度与条目预算与严格删除器一致。
     *
     * 因为不再持有目录描述符，这条路径存在 TOCTOU 窗口，所以只对运行时目录下、由本应用自己生成的
     * 路径使用；根目录本身仍要求直接位于 [allowedParent] 之下，越界一律 `RESET_SCOPE_INVALID`。
     */
    fun deleteTreeNoFollowFallback(root: File, allowedParent: File) {
        val normalizedParent = allowedParent.toPath().toAbsolutePath().normalize()
        val normalizedRoot = root.toPath().toAbsolutePath().normalize()
        if (normalizedRoot.parent != normalizedParent) {
            throw RuntimeFailure("RESET_SCOPE_INVALID", "拒绝删除运行时目录之外的路径")
        }
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) return
        deleteEntryFallback(normalizedRoot, 0, DeleteBudget())
    }

    private fun deleteEntryFallback(path: Path, depth: Int, budget: DeleteBudget) {
        if (depth > MAX_DELETE_DEPTH || ++budget.entries > MAX_DELETE_ENTRIES) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "运行时文件树超过安全清理限制")
        }
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return
        } catch (error: IOException) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法检查运行时文件", error)
        }
        if (attributes.isDirectory && !attributes.isSymbolicLink) {
            try {
                Files.newDirectoryStream(path).use { directory ->
                    for (entry in directory) deleteEntryFallback(entry, depth + 1, budget)
                }
            } catch (_: NoSuchFileException) {
                return
            } catch (error: DirectoryIteratorException) {
                throw RuntimeFailure("FILESYSTEM_ERROR", "无法遍历运行时目录", error.cause ?: error)
            } catch (error: IOException) {
                throw RuntimeFailure("FILESYSTEM_ERROR", "无法遍历运行时目录", error)
            }
        }
        try {
            Files.delete(path)
        } catch (_: NoSuchFileException) {
            return
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

    /**
     * 两个文件是否字节一致（长度不同直接判否；任一文件读不到也算不一致）。
     *
     * 用途只有一个：判断运行时根里那份 loader 拷贝是否还需要重建。它同时兜住「APK 升级换了
     * loader」与「根内文件被替换/截断」两种情况，所以调用方拿到 false 时的动作是重建、不是报错。
     * 读失败一律按「不一致」处理：真正读不到源文件时，随后的复制会抛出带原因的失败。
     */
    fun sameContent(first: File, second: File): Boolean = try {
        if (first.length() != second.length()) {
            false
        } else {
            first.inputStream().use { a ->
                second.inputStream().use { b ->
                    val left = ByteArray(CONTENT_COMPARE_BUFFER)
                    val right = ByteArray(CONTENT_COMPARE_BUFFER)
                    var equal = true
                    while (true) {
                        val readLeft = a.read(left)
                        val readRight = b.read(right)
                        if (readLeft != readRight) {
                            equal = false
                            break
                        }
                        if (readLeft <= 0) break
                        if (!left.copyOf(readLeft).contentEquals(right.copyOf(readRight))) {
                            equal = false
                            break
                        }
                    }
                    equal
                }
            }
        }
    } catch (_: IOException) {
        false
    }

    private const val CONTENT_COMPARE_BUFFER = 64 * 1024
    private const val MAX_DELETE_DEPTH = 256
    private const val MAX_DELETE_ENTRIES = 250_000
}
