package io.deepseekharness.mobile.runtime

import android.system.Os
import android.system.OsConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class SafeRootfsExtractor {
    private data class PendingSymlink(val path: Path, val target: String)
    private data class PendingHardlink(val path: Path, val target: Path)
    private data class PendingDirectoryMode(val path: Path, val mode: Int)

    fun extract(
        archive: File,
        destination: File,
        expectedArchiveBytes: Long,
        expectedArchiveSha256: String,
        expectedExtractedBytes: Long,
        compression: RootfsCompression,
        shouldCancel: () -> Boolean = { false },
        onProgress: (extractedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        if (
            expectedArchiveBytes !in 1..RuntimeLimits.MAX_COMPRESSED_BYTES ||
            !SHA256_PATTERN.matches(expectedArchiveSha256) ||
            expectedExtractedBytes !in 1..RuntimeLimits.MAX_EXTRACTED_BYTES
        ) {
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
        val execFiles = ArrayList<Path>()
        var entryCount = 0
        var extractedBytes = 0L
        var degradedSymlinkCount = 0
        onProgress(0, expectedExtractedBytes)

        try {
            FileChannel.open(
                archive.toPath(),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { archiveChannel ->
                VerifiedArchiveInputStream(
                    Channels.newInputStream(archiveChannel),
                    expectedArchiveBytes,
                    expectedArchiveSha256,
                ).use { verifiedInput ->
                    // Tar and gzip may close independently; the verified descriptor stays open through raw EOF validation.
                    val bufferedInput = BufferedInputStream(CloseShieldInputStream(verifiedInput), BUFFER_SIZE)
                    compressedInput(bufferedInput, compression).use { archiveInput ->
                        TarArchiveInputStream(CloseShieldInputStream(archiveInput)).use { tarInput ->
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
                                when (classifyEntry(entry)) {
                                    ArchiveEntryKind.HARD_LINK -> {
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
                                    ArchiveEntryKind.DIRECTORY -> {
                                        ensureDirectory(path, root)
                                        directoryModes.add(PendingDirectoryMode(path, safeMode(entry.mode, directory = true)))
                                    }
                                    ArchiveEntryKind.FILE -> {
                                        if (entry.isSparse || entry.size < 0) {
                                            throw RuntimeFailure("ARCHIVE_FEATURE_UNSUPPORTED", "运行时归档包含稀疏或无效文件")
                                        }
                                        if (entry.size > expectedExtractedBytes - extractedBytes) {
                                            throw RuntimeFailure("ARCHIVE_EXPANSION_LIMIT", "运行时归档解压大小超过声明值")
                                        }
                                        ensureDirectory(path.parent, root)
                                        writeRegularFile(tarInput, path, entry.size, shouldCancel) { written ->
                                            extractedBytes += written
                                            onProgress(extractedBytes, expectedExtractedBytes)
                                        }
                                        Os.chmod(path.toString(), safeMode(entry.mode, directory = false))
                                        if (entry.mode and 0x40 != 0) execFiles.add(path)
                                    }
                                    ArchiveEntryKind.SYMBOLIC_LINK -> {
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
                                    ArchiveEntryKind.UNSUPPORTED -> throw RuntimeFailure(
                                        "ARCHIVE_ENTRY_TYPE_REJECTED",
                                        "运行时归档包含设备节点或其他不支持的条目类型",
                                    )
                                }
                            }
                        }
                        drainCompressedInput(archiveInput, shouldCancel)
                    }
                    verifiedInput.drainAndVerify(shouldCancel)
                }
            }

            if (extractedBytes != expectedExtractedBytes) {
                throw RuntimeFailure("ARCHIVE_SIZE_MISMATCH", "运行时实际解压大小与清单不一致")
            }
            if (shouldCancel()) throw RuntimeFailure("INSTALL_CANCELLED", "运行时安装已取消")
            stampExecutableFiles(execFiles)
            createHardlinks(hardlinks)
            for ((path, target) in symlinks) {
                if (RuntimeFiles.existsNoFollow(path.toFile())) {
                    throw RuntimeFailure("ARCHIVE_DUPLICATE_ENTRY", "符号链接目标路径已存在")
                }
                try {
                    Os.symlink(target, path.toString())
                } catch (error: android.system.ErrnoException) {
                    // 部分 ROM（如荣耀）SELinux 禁止应用创建符号链接（EACCES/EPERM）：
                    // 降级为复制目标内容，保证安装在任何设备上都能完成。
                    if (error.errno == android.system.OsConstants.EACCES ||
                        error.errno == android.system.OsConstants.EPERM ||
                        error.errno == android.system.OsConstants.ENOTSUP ||
                        error.errno == android.system.OsConstants.EXDEV
                    ) {
                        degradedSymlinkCount += 1
                        copySymlinkTargetAsFallback(path, target, root)
                    } else {
                        throw error
                    }
                }
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
            val detail = error.message ?: error.javaClass.simpleName
            throw RuntimeFailure("ARCHIVE_EXTRACTION_FAILED", "无法解压运行时归档：" + detail, error)
        }
    }

    private fun compressedInput(input: InputStream, compression: RootfsCompression): InputStream = when (compression) {
        RootfsCompression.GZIP -> GzipCompressorInputStream(input)
    }

    private fun drainCompressedInput(input: InputStream, shouldCancel: () -> Boolean) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            if (shouldCancel()) throw RuntimeFailure("INSTALL_CANCELLED", "运行时安装已取消")
            if (input.read(buffer) < 0) return
        }
    }

    /**
     * 为归档内可执行文件批量盖章 `security.android.exec` 扩展属性。
     * Android 15+ 要求应用数据目录内的 ELF 携带该属性才能 exec。
     * 直接走 Os.setxattr 系统调用，不依赖可能被厂商裁剪的 /system/bin/setfattr；
     * 旧内核不支持该属性时整体忽略（盖章是增强项，失败不影响 rootfs 解压）。
     */
    private fun stampExecutableFiles(paths: List<Path>) {
        if (paths.isEmpty()) return
        val stampValue = byteArrayOf(EXEC_XATTR_VALUE_BYTE)
        var consecutiveFailures = 0
        for (path in paths) {
            try {
                Os.setxattr(path.toString(), EXEC_XATTR_NAME, stampValue, 0)
                consecutiveFailures = 0
            } catch (_: Throwable) {
                // 旧内核返回 ENOTSUP：前几个连续失败即可断定全盘不支持，提前退出
                consecutiveFailures++
                if (consecutiveFailures >= EXEC_STAMP_EARLY_ABORT_FAILURES) return
            }
        }
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
                try {
                    Os.link(link.target.toString(), link.path.toString())
                } catch (error: android.system.ErrnoException) {
                    // 荣耀等 ROM 的 SELinux 可能同时禁止硬链接（EACCES/EPERM）：
                    // 与符号链接一致，降级为复制目标文件；目标已校验为常规文件且位于 rootfs 内。
                    if (error.errno == OsConstants.EACCES ||
                        error.errno == OsConstants.EPERM ||
                        error.errno == OsConstants.ENOTSUP ||
                        error.errno == OsConstants.EXDEV
                    ) {
                        val targetFile = link.target.toFile()
                        link.path.parent.toFile().mkdirs()
                        targetFile.copyTo(link.path.toFile(), overwrite = false)
                        link.path.toFile().setExecutable(targetFile.canExecute(), false)
                    } else {
                        throw error
                    }
                }
                iterator.remove()
                progress = true
            }
            if (!progress) {
                throw RuntimeFailure("ARCHIVE_LINK_INVALID", "归档硬链接目标不存在或形成循环")
            }
        }
    }

    /**
     * 符号链接创建被设备拒绝时的降级：按 POSIX 语义（链接内容相对链接所在目录）
     * 解析目标，并以复制替代链接。复制保留原文件的可执行位（如 node 等入口）。
     * 目录目标复制时跳过内部符号链接（见函数体注释）。
     */
    private fun copySymlinkTargetAsFallback(linkPath: Path, rawTarget: String, root: Path) {
        val absoluteLink = linkPath.toAbsolutePath().normalize()
        val resolved = if (rawTarget.startsWith("/")) {
            root.resolve(rawTarget.removePrefix("/")).normalize()
        } else {
            absoluteLink.parent.resolve(rawTarget).normalize()
        }
        if (!resolved.startsWith(root)) {
            throw RuntimeFailure("ARCHIVE_LINK_INVALID", "符号链接降级目标越界: $rawTarget")
        }
        val destination = absoluteLink
        val sourceFile = resolved.toFile()
        if (sourceFile.isDirectory) {
            // 复制目录时跳过内部符号链接：pnpm 包目录内的依赖链接指向 .pnpm 其它包，
            // 跟随复制会膨胀/成环/产生空目录。扁平 profiles/node_modules 下的包依赖
            // 由 Node 从该扁平目录向上解析，无需复制内部链接。
            sourceFile.walkTopDown().forEach { file ->
                if (Files.isSymbolicLink(file.toPath())) return@forEach
                val relative = resolved.relativize(file.toPath())
                val targetFile = destination.resolve(relative).toFile()
                if (file.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    file.copyTo(targetFile, overwrite = false)
                    targetFile.setExecutable(file.canExecute(), false)
                }
            }
        } else if (sourceFile.isFile) {
            destination.parent.toFile().mkdirs()
            sourceFile.copyTo(destination.toFile(), overwrite = false)
            destination.toFile().setExecutable(sourceFile.canExecute(), false)
        } else {
            throw RuntimeFailure("ARCHIVE_LINK_INVALID", "符号链接降级目标不可用: $rawTarget")
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
        onBytesWritten: (Long) -> Unit,
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
                    onBytesWritten(read.toLong())
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
        private const val EXEC_XATTR_NAME = "security.android.exec"
        private const val EXEC_XATTR_VALUE_BYTE: Byte = '1'.code.toByte()
        private const val EXEC_STAMP_EARLY_ABORT_FAILURES = 8
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
    }
}

private class CloseShieldInputStream(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
}

internal enum class ArchiveEntryKind {
    HARD_LINK,
    DIRECTORY,
    SYMBOLIC_LINK,
    FILE,
    UNSUPPORTED,
}

/**
 * commons-compress 1.27.1 的 TarArchiveEntry.isFile() 对符号链接（linkFlag '2'）、
 * 硬链接（'1'）、设备节点（'3'/'4'）与 FIFO（'6'）均返回 true，仅目录（'5'）
 * 与以 "/" 结尾的名字返回 false。因此类型分派必须在 isFile 之前先判定
 * isLink/isSymbolicLink，否则符号链接会被当作普通文件物化成 0 字节 + 0777
 * 的普通文件（真机 rootfs 损坏的根因）。
 */
internal fun classifyEntry(entry: TarArchiveEntry): ArchiveEntryKind = when {
    entry.isLink -> ArchiveEntryKind.HARD_LINK
    entry.isDirectory -> ArchiveEntryKind.DIRECTORY
    entry.isSymbolicLink -> ArchiveEntryKind.SYMBOLIC_LINK
    entry.isFile && !entry.isCharacterDevice && !entry.isBlockDevice && !entry.isFIFO -> ArchiveEntryKind.FILE
    else -> ArchiveEntryKind.UNSUPPORTED
}

internal class VerifiedArchiveInputStream(
    input: InputStream,
    private val expectedBytes: Long,
    expectedSha256: String,
) : FilterInputStream(input) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val expectedDigest = expectedSha256.chunked(2)
        .map { octet -> octet.toInt(16).toByte() }
        .toByteArray()
    private var countedBytes = 0L
    private var reachedEof = false
    private var verificationAttempted = false

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            record(byteArrayOf(value.toByte()), 0, 1)
        } else {
            reachedEof = true
        }
        return value
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(destination, offset, length)
        if (read > 0) {
            record(destination, offset, read)
        } else if (read < 0) {
            reachedEof = true
        }
        return read
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0) return 0
        val buffer = ByteArray(minOf(byteCount, SKIP_BUFFER_BYTES.toLong()).toInt())
        var skipped = 0L
        while (skipped < byteCount) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), byteCount - skipped).toInt())
            if (read < 0) break
            skipped += read.toLong()
        }
        return skipped
    }

    fun drainAndVerify(shouldCancel: () -> Boolean = { false }) {
        if (verificationAttempted) {
            throw IllegalStateException("Archive source verification was already attempted")
        }
        val buffer = ByteArray(DRAIN_BUFFER_BYTES)
        while (!reachedEof) {
            if (shouldCancel()) throw RuntimeFailure("INSTALL_CANCELLED", "运行时安装已取消")
            read(buffer)
        }
        verificationAttempted = true
        if (countedBytes != expectedBytes) {
            throw RuntimeFailure("ARCHIVE_SOURCE_SIZE_MISMATCH", "实际读取的运行时归档大小与清单不一致")
        }
        val actualDigest = digest.digest()
        if (!MessageDigest.isEqual(actualDigest, expectedDigest)) {
            throw RuntimeFailure("ARCHIVE_SOURCE_DIGEST_MISMATCH", "实际读取的运行时归档摘要与清单不一致")
        }
    }

    private fun record(source: ByteArray, offset: Int, length: Int) {
        if (countedBytes > expectedBytes - length) {
            throw RuntimeFailure("ARCHIVE_SOURCE_SIZE_MISMATCH", "实际读取的运行时归档超过清单大小")
        }
        digest.update(source, offset, length)
        countedBytes += length.toLong()
    }

    private companion object {
        const val SKIP_BUFFER_BYTES = 8 * 1024
        const val DRAIN_BUFFER_BYTES = 64 * 1024
    }
}
