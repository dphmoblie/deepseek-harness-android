package io.deepseekharness.mobile.runtime

import android.system.Os
import android.system.OsConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class SafeRootfsExtractor {
    private data class PendingSymlink(val path: Path, val target: String)
    private data class PendingHardlink(val path: Path, val target: Path)
    private data class PendingDirectoryMode(val path: Path, val mode: Int)

    fun extract(
        archive: File,
        destination: File,
        expectedExtractedBytes: Long,
        compression: RootfsCompression,
        shouldCancel: () -> Boolean = { false },
    ) {
        if (expectedExtractedBytes !in 1..RuntimeLimits.MAX_EXTRACTED_BYTES) {
            throw RuntimeFailure("MANIFEST_SIZE_INVALID", "运行时解压大小无效")
        }
        val destinationParent = destination.parentFile
            ?: throw RuntimeFailure("FILESYSTEM_ERROR", "运行时暂存路径无效")
        if (destination.exists()) {
            throw RuntimeFailure("STAGING_NOT_EMPTY", "运行时暂存目录已存在")
        }
        if (!destination.mkdirs()) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法创建运行时暂存目录")
        }

        val root = destination.toPath().toAbsolutePath().normalize()
        val seen = HashSet<Path>()
        val symlinks = ArrayList<PendingSymlink>()
        val hardlinks = ArrayList<PendingHardlink>()
        val directoryModes = ArrayList<PendingDirectoryMode>()
        var entryCount = 0
        var extractedBytes = 0L

        try {
            FileInputStream(archive).use { fileInput ->
                compressedInput(BufferedInputStream(fileInput, BUFFER_SIZE), compression).use { archiveInput ->
                    TarArchiveInputStream(archiveInput).use { tarInput ->
                        while (true) {
                            if (shouldCancel()) throw RuntimeFailure("INSTALL_CANCELLED", "运行时安装已取消")
                            val entry = tarInput.nextEntry as? TarArchiveEntry ?: break
                            entryCount += 1
                            if (entryCount > RuntimeLimits.MAX_ARCHIVE_ENTRIES) {
                                throw RuntimeFailure("ARCHIVE_ENTRY_LIMIT", "运行时归档条目数量超过限制")
                            }
                            if (!tarInput.canReadEntryData(entry)) {
                                throw RuntimeFailure("ARCHIVE_FEATURE_UNSUPPORTED", "运行时归档包含不支持的条目")
                            }
                            if (entry.name.removeSuffix("/") in setOf(".", "./")) {
                                if (!entry.isDirectory) {
                                    throw RuntimeFailure("ARCHIVE_PATH_INVALID", "归档根条目类型无效")
                                }
                                continue
                            }
                            val path = ArchivePathPolicy.resolveEntry(root, entry.name)
                            if (!seen.add(path)) {
                                throw RuntimeFailure("ARCHIVE_DUPLICATE_ENTRY", "运行时归档包含重复或保留条目")
                            }
                            when {
                                entry.isLink -> {
                                    if (entry.size != 0L) {
                                        throw RuntimeFailure("ARCHIVE_LINK_INVALID", "归档硬链接包含意外数据")
                                    }
                                    ensureDirectory(path.parent, root)
                                    hardlinks.add(
                                        PendingHardlink(
                                            path,
                                            ArchivePathPolicy.resolveHardlinkTarget(root, entry.linkName),
                                        ),
                                    )
                                }
                                entry.isDirectory -> {
                                    ensureDirectory(path, root)
                                    directoryModes.add(PendingDirectoryMode(path, safeMode(entry.mode, directory = true)))
                                }
                                entry.isFile -> {
                                    if (entry.isSparse || entry.size < 0) {
                                        throw RuntimeFailure("ARCHIVE_FEATURE_UNSUPPORTED", "运行时归档包含稀疏或无效文件")
                                    }
                                    if (entry.size > expectedExtractedBytes - extractedBytes) {
                                        throw RuntimeFailure("ARCHIVE_EXPANSION_LIMIT", "运行时归档解压大小超过声明值")
                                    }
                                    ensureDirectory(path.parent, root)
                                    writeRegularFile(tarInput, path, entry.size, shouldCancel)
                                    extractedBytes += entry.size
                                    Os.chmod(path.toString(), safeMode(entry.mode, directory = false))
                                }
                                entry.isSymbolicLink -> {
                                    if (entry.size != 0L) {
                                        throw RuntimeFailure("ARCHIVE_LINK_INVALID", "归档符号链接包含意外数据")
                                    }
                                    ensureDirectory(path.parent, root)
                                    symlinks.add(
                                        PendingSymlink(
                                            path,
                                            ArchivePathPolicy.normalizeSymlinkTarget(root, path, entry.linkName),
                                        ),
                                    )
                                }
                                else -> throw RuntimeFailure(
                                    "ARCHIVE_ENTRY_TYPE_REJECTED",
                                    "运行时归档包含硬链接、设备节点或其他不安全条目",
                                )
                            }
                        }
                    }
                }
            }

            if (extractedBytes != expectedExtractedBytes) {
                throw RuntimeFailure("ARCHIVE_SIZE_MISMATCH", "运行时实际解压大小与清单不一致")
            }
            if (shouldCancel()) throw RuntimeFailure("INSTALL_CANCELLED", "运行时安装已取消")
            createHardlinks(hardlinks)
            for ((path, target) in symlinks) {
                if (path.toFile().exists()) {
                    throw RuntimeFailure("ARCHIVE_DUPLICATE_ENTRY", "符号链接目标路径已存在")
                }
                Os.symlink(target, path.toString())
            }
            for ((path, mode) in directoryModes.asReversed()) {
                Os.chmod(path.toString(), mode)
            }
        } catch (error: Throwable) {
            try {
                RuntimeFiles.deleteTreeNoFollow(destination, destinationParent)
            } catch (_: Throwable) {
                // The original verified failure is returned; the next install performs another scoped cleanup.
            }
            if (error is RuntimeFailure) throw error
            throw RuntimeFailure("ARCHIVE_EXTRACTION_FAILED", "无法解压运行时归档", error)
        }
    }

    private fun compressedInput(input: InputStream, compression: RootfsCompression): InputStream = when (compression) {
        RootfsCompression.GZIP -> GzipCompressorInputStream(input)
    }

    private fun createHardlinks(pending: List<PendingHardlink>) {
        val remaining = pending.toMutableList()
        while (remaining.isNotEmpty()) {
            var progress = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val link = iterator.next()
                val stat = try {
                    Os.lstat(link.target.toString())
                } catch (error: android.system.ErrnoException) {
                    if (error.errno == OsConstants.ENOENT) continue
                    throw RuntimeFailure("ARCHIVE_LINK_INVALID", "无法检查归档硬链接目标", error)
                }
                if (!OsConstants.S_ISREG(stat.st_mode)) {
                    throw RuntimeFailure("ARCHIVE_LINK_INVALID", "归档硬链接目标不是常规文件")
                }
                if (RuntimeFiles.existsNoFollow(link.path.toFile())) {
                    throw RuntimeFailure("ARCHIVE_DUPLICATE_ENTRY", "归档硬链接路径已存在")
                }
                Os.link(link.target.toString(), link.path.toString())
                iterator.remove()
                progress = true
            }
            if (!progress) {
                throw RuntimeFailure("ARCHIVE_LINK_INVALID", "归档硬链接目标不存在或形成循环")
            }
        }
    }

    private fun ensureDirectory(path: Path, root: Path) {
        if (path == root) return
        if (!path.startsWith(root)) throw RuntimeFailure("ARCHIVE_PATH_INVALID", "归档路径越界")
        val relative = root.relativize(path)
        var cursor = root
        for (component in relative) {
            cursor = cursor.resolve(component)
            val file = cursor.toFile()
            if (file.exists()) {
                if (!file.isDirectory) throw RuntimeFailure("ARCHIVE_PATH_CONFLICT", "归档路径与文件冲突")
            } else if (!file.mkdir()) {
                throw RuntimeFailure("FILESYSTEM_ERROR", "无法创建运行时目录")
            }
        }
    }

    private fun writeRegularFile(
        input: TarArchiveInputStream,
        path: Path,
        expectedBytes: Long,
        shouldCancel: () -> Boolean,
    ) {
        var written = 0L
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            BufferedOutputStream(Channels.newOutputStream(channel), BUFFER_SIZE).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (written < expectedBytes) {
                    if (shouldCancel()) throw RuntimeFailure("INSTALL_CANCELLED", "运行时安装已取消")
                    val maximum = minOf(buffer.size.toLong(), expectedBytes - written).toInt()
                    val read = input.read(buffer, 0, maximum)
                    if (read < 0) throw RuntimeFailure("ARCHIVE_TRUNCATED", "运行时归档文件内容不完整")
                    output.write(buffer, 0, read)
                    written += read.toLong()
                }
                output.flush()
                channel.force(true)
            }
        }
        if (written != expectedBytes) throw RuntimeFailure("ARCHIVE_TRUNCATED", "运行时归档文件大小无效")
    }

    private fun safeMode(rawMode: Int, directory: Boolean): Int {
        val permissionBits = rawMode and 0x1ff
        return if (directory) permissionBits or 0x1c0 else permissionBits or 0x180
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
