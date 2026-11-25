package io.deepseekharness.mobile.runtime

import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** Restricts host-side access to ordinary files below the selected guest directory. */
class RuntimeWorkspaceFiles internal constructor(
    private val workspace: File,
    private val cacheDir: File,
    private val trustedParent: File,
) {
    constructor(store: RuntimeStore, cacheDir: File) : this(
        File(store.currentRoot, "root"), cacheDir, store.runtimeParent.parentFile!!,
    )

    data class Snapshot(val files: List<String>, val truncated: Boolean)

    fun list(maxFiles: Int = 100, maxDepth: Int = 6): Snapshot = withWorkspace({ Snapshot(emptyList(), false) }) { root ->
        val limit = maxFiles.coerceIn(1, 100)
        val result = ArrayList<String>(limit + 1)
        fun visit(directory: SecureDirectoryStream<Path>, prefix: String, depth: Int) {
            for (entry in directory) {
                if (result.size > limit) return
                val name = entry.fileName
                val relative = prefix + name
                if (!isValidRelativePath(relative)) continue
                try {
                    val attributes = attributesNoFollow(directory, name) ?: continue
                    if (attributes.isRegularFile) {
                        result += relative
                    } else if (attributes.isDirectory && depth > 1) {
                        directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use {
                            visit(it, "$relative/", depth - 1)
                        }
                    }
                } catch (_: IOException) {
                    // An individual guest entry may become unavailable during the walk.
                }
            }
        }
        visit(root, "", maxDepth.coerceIn(1, 6))
        val sorted = result.sorted()
        Snapshot(sorted.take(limit), sorted.size > limit)
    }

    internal fun <T> withFile(relative: String, action: (SecureDirectoryStream<Path>, Path, BasicFileAttributes) -> T): T {
        if (!isValidRelativePath(relative)) {
            throw RuntimeFailure("WORKSPACE_PATH_INVALID", "工作区文件路径无效")
        }
        val names = relative.split('/').map { workspace.toPath().fileSystem.getPath(it) }
        fun visit(parent: SecureDirectoryStream<Path>, index: Int): T {
            val name = names[index]
            if (index < names.lastIndex) {
                return parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { visit(it, index + 1) }
            }
            val attributes = attributesNoFollow(parent, name)
            if (attributes == null || !attributes.isRegularFile) {
                throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用")
            }
            return action(parent, name, attributes)
        }
        return withWorkspace({ throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用") }) {
            visit(it, 0)
        }
    }

    private fun <T> withWorkspace(missing: () -> T, action: (SecureDirectoryStream<Path>) -> T): T {
        val trusted = trustedParent.toPath().toAbsolutePath().normalize()
        val root = workspace.toPath().toAbsolutePath().normalize()
        if (root == trusted || !root.startsWith(trusted)) {
            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用")
        }
        val names = trusted.relativize(root).toList()
        fun visit(parent: SecureDirectoryStream<Path>, index: Int): T {
            if (index == names.size) return action(parent)
            return parent.newDirectoryStream(names[index], LinkOption.NOFOLLOW_LINKS).use { visit(it, index + 1) }
        }
        try {
            // Keep every guest-controlled parent pinned until the operation has finished.
            return Files.newDirectoryStream(trusted).use { stream ->
                if (stream !is SecureDirectoryStream<*>) {
                    throw RuntimeFailure("WORKSPACE_SECURE_ACCESS_UNAVAILABLE", "系统不支持安全访问工作区文件")
                }
                @Suppress("UNCHECKED_CAST")
                visit(stream as SecureDirectoryStream<Path>, 0)
            }
        } catch (_: NoSuchFileException) {
            return missing()
        } catch (error: DirectoryIteratorException) {
            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用", error)
        } catch (error: IOException) {
            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用", error)
        } catch (error: UnsupportedOperationException) {
            throw RuntimeFailure("WORKSPACE_SECURE_ACCESS_UNAVAILABLE", "系统不支持安全访问工作区文件", error)
        } catch (error: SecurityException) {
            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用", error)
        }
    }

    private fun attributesNoFollow(parent: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes? =
        parent.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.readAttributes()

    private fun isValidRelativePath(path: String): Boolean =
        path.length in 1..MAX_RELATIVE_PATH_CHARS && !path.contains('\\') &&
            path.none { it.code <= 31 || it.code == 127 } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun copyForSharing(relative: String): File = withFile(relative) { parent, name, attributes ->
        checkShareSize(attributes.size())
        val directory = RuntimeShareCache.prepare(cacheDir, RuntimeCacheEntries.WORKSPACE_SHARE)
        if (directory == null) {
            throw RuntimeFailure("WORKSPACE_EXPORT_FAILED", "无法准备文件分享目录")
        }
        val safeName = name.toString().replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifEmpty { "file" }
        val target = File.createTempFile("dsh-", "-$safeName", directory)
        try {
            Channels.newInputStream(parent.newByteChannel(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))).use { input ->
                Files.newOutputStream(
                    target.toPath(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        checkShareSize(total)
                        output.write(buffer, 0, read)
                    }
                }
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun checkShareSize(bytes: Long) {
        if (bytes > MAX_SHARE_FILE_BYTES) {
            throw RuntimeFailure(SHARE_FILE_TOO_LARGE, "工作区文件超过 64 MiB，无法打开或分享")
        }
    }

    fun delete(relative: String) {
        withFile(relative) { parent, name, _ ->
            try {
                parent.deleteFile(name)
            } catch (error: IOException) {
                throw RuntimeFailure("WORKSPACE_DELETE_FAILED", "无法删除工作区文件", error)
            }
        }
    }

    fun mimeType(relative: String): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(relative.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

    companion object {
        internal fun forDefaultWorkspace(currentRoot: File, cacheDir: File, trustedParent: File) =
            RuntimeWorkspaceFiles(File(currentRoot, RuntimePreservePolicy.DEFAULT_WORKSPACE_ROOT), cacheDir, trustedParent)

        internal const val SHARE_FILE_TOO_LARGE = "WORKSPACE_SHARE_FILE_TOO_LARGE"
        private const val MAX_SHARE_FILE_BYTES = 64L * 1024 * 1024
        private const val MAX_RELATIVE_PATH_CHARS = 240
    }
}
