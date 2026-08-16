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
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
                                        writeRegularFile(tarInput, path, entry.size, shouldCancel) { written ->
                                            extractedBytes += written
                                            onProgress(extractedBytes, expectedExtractedBytes)
                                        }
                                        Os.chmod(path.toString(), safeMode(entry.mode, directory = false))
                                        if (entry.mode and 0x40 != 0) execFiles.add(path)
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

    private fun drainCompressedInput(input: InputStream, shouldCancel: () -> Boolean) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            if (shouldCancel()) throw RuntimeFailure("INSTALL_CANCELLED", "运行时安装已取消")
            if (input.read(buffer) < 0) return
        }
    }

    /**
     * 为归档内可执行文件批量盖章 `security.android.exec` 扩展属性。
     * Android 15+ 要求应用数据目录内的 ELF 携带该属性才能 exec，
     * 旧内核不支持时整体忽略（盖章是增强项，失败不影响 rootfs 解压）。
     */
    private fun stampExecutableFiles(paths: List<Path>) {
        if (paths.isEmpty()) return
        try {
            paths.chunked(EXEC_STAMP_BATCH_SIZE).forEach { batch ->
                val workers = batch.map { path ->
                    // android.jar 无 ProcessBuilder.Redirect.DISCARD（桌面 JDK 9+ 专有），
                    // 使用默认 PIPE 并在等待后关闭流，setfattr 成功时无输出不会阻塞
                    val process = ProcessBuilder(
                        "/system/bin/setfattr",
                        "-n",
                        "security.android.exec",
                        "-v",
                        "1",
                        path.toString(),
                    )
                        .redirectErrorStream(true)
                        .start()
                    process to path
                }
                workers.forEach { (process, path) ->
                    try {
                        val finished = process.waitFor(EXEC_STAMP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        process.inputStream.close()
                        if (!finished) {
                            process.destroyForcibly()
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        process.destroyForcibly()
                        return@forEach
                    }
                }
            }
        } catch (_: Throwable) {
            // setfattr 缺失或内核不支持 exec 属性时忽略
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
        private const val EXEC_STAMP_BATCH_SIZE = 64
        private const val EXEC_STAMP_TIMEOUT_SECONDS = 30L
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
    }
}

private class CloseShieldInputStream(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
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
