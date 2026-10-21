package io.deepseekharness.mobile.runtime

import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** Restricts all host-side access to ordinary files below the DSH workspace. */
class RuntimeWorkspaceFiles internal constructor(
    private val workspace: File,
    private val cacheDir: File,
    private val trustedParent: File,
) {
    constructor(store: RuntimeStore, cacheDir: File) : this(
        File(store.currentRoot, "root/1"), cacheDir, store.runtimeParent.parentFile!!,
    )

    data class Snapshot(val files: List<String>, val truncated: Boolean)

    fun list(maxFiles: Int = 100, maxDepth: Int = 6): Snapshot {
        val root = workspace
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return Snapshot(emptyList(), false)
        val limit = maxFiles.coerceIn(1, 100)
        val result = ArrayList<String>(limit + 1)
        Files.walkFileTree(
            root.toPath(),
            emptySet(),
            maxDepth.coerceIn(1, 6),
            object : SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (result.size > limit) return FileVisitResult.TERMINATE
                    if (attrs.isRegularFile && !attrs.isSymbolicLink) {
                        val relative = root.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                        if (relative.length in 1..MAX_RELATIVE_PATH_CHARS) result += relative
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: java.nio.file.Path, error: java.io.IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            }
        )
        val sorted = result.sorted()
        return Snapshot(sorted.take(limit), sorted.size > limit)
    }

    internal fun <T> withFile(relative: String, action: (SecureDirectoryStream<Path>, Path) -> T): T {
        if (relative.length !in 1..MAX_RELATIVE_PATH_CHARS || relative.startsWith('/') || relative.contains('\\') ||
            relative.any { it == '\u0000' || it == '\r' || it == '\n' }
        ) {
            throw RuntimeFailure("WORKSPACE_PATH_INVALID", "工作区文件路径无效")
        }
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw RuntimeFailure("WORKSPACE_PATH_INVALID", "工作区文件路径无效")
        }
        val trusted = trustedParent.toPath().toAbsolutePath().normalize()
        val root = workspace.toPath().toAbsolutePath().normalize()
        if (root == trusted || !root.startsWith(trusted)) {
            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用")
        }
        val names = trusted.relativize(root).toList() + segments.map { root.fileSystem.getPath(it) }
        fun visit(parent: SecureDirectoryStream<Path>, index: Int): T {
            val name = names[index]
            if (index < names.lastIndex) {
                return parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { visit(it, index + 1) }
            }
            val attributes = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                ?.readAttributes()
            if (attributes == null || !attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > MAX_FILE_BYTES) {
                throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用")
            }
            return action(parent, name)
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
        } catch (error: IOException) {
            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用", error)
        } catch (error: UnsupportedOperationException) {
            throw RuntimeFailure("WORKSPACE_SECURE_ACCESS_UNAVAILABLE", "系统不支持安全访问工作区文件", error)
        } catch (error: SecurityException) {
            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用", error)
        }
    }

    fun copyForSharing(relative: String): File = withFile(relative) { parent, name ->
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
                        if (total > MAX_FILE_BYTES) {
                            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件超过大小限制")
                        }
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

    fun delete(relative: String) {
        withFile(relative) { parent, name ->
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
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        private const val MAX_RELATIVE_PATH_CHARS = 240
    }
}
