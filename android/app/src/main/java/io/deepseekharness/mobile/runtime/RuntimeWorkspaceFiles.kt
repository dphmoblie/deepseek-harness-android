package io.deepseekharness.mobile.runtime

import android.webkit.MimeTypeMap
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Restricts all host-side access to ordinary files below the DSH workspace. */
class RuntimeWorkspaceFiles(private val store: RuntimeStore, private val cacheDir: File) {
    private val workspace get() = File(store.currentRoot, "root/1")

    fun list(maxFiles: Int = 100, maxDepth: Int = 6): List<String> {
        val root = workspace
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
        val limit = maxFiles.coerceIn(1, 100)
        val result = ArrayList<String>(limit)
        Files.walkFileTree(
            root.toPath(),
            emptySet(),
            maxDepth.coerceIn(1, 6),
            object : SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (result.size >= limit) return FileVisitResult.TERMINATE
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
        return result.sorted()
    }

    fun resolve(relative: String): File {
        if (relative.length !in 1..MAX_RELATIVE_PATH_CHARS || relative.startsWith('/') || relative.contains('\\')) {
            throw RuntimeFailure("WORKSPACE_PATH_INVALID", "工作区文件路径无效")
        }
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw RuntimeFailure("WORKSPACE_PATH_INVALID", "工作区文件路径无效")
        }
        if (!workspace.isDirectory || Files.isSymbolicLink(workspace.toPath())) {
            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用")
        }
        val root = workspace.canonicalFile
        var cursor = root
        for (segment in segments) {
            cursor = File(cursor, segment)
            if (Files.isSymbolicLink(cursor.toPath())) {
                throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用")
            }
        }
        val file = cursor.canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile || file.length() > MAX_FILE_BYTES) {
            throw RuntimeFailure("WORKSPACE_FILE_UNAVAILABLE", "工作区文件不可用")
        }
        return file
    }

    fun copyForSharing(relative: String): File {
        val source = resolve(relative)
        val directory = File(cacheDir, "share")
        if ((!directory.isDirectory && !directory.mkdirs()) || Files.isSymbolicLink(directory.toPath())) {
            throw RuntimeFailure("WORKSPACE_EXPORT_FAILED", "无法准备文件分享目录")
        }
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifEmpty { "file" }
        val target = File.createTempFile("dsh-", "-$safeName", directory)
        try {
            Files.newInputStream(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
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
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun delete(relative: String) {
        val file = resolve(relative)
        if (!file.delete()) throw RuntimeFailure("WORKSPACE_DELETE_FAILED", "无法删除工作区文件")
    }

    fun mimeType(relative: String): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(relative.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

    companion object {
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        private const val MAX_RELATIVE_PATH_CHARS = 240
    }
}
