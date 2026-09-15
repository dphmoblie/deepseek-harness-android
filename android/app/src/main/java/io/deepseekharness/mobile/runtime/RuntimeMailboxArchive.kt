package io.deepseekharness.mobile.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/** 一次导入/导出使用的限额。默认取 [RuntimeMailboxLimits]，单元测试用小限额覆盖边界分支。 */
internal data class MailboxLimits(
    val maxEntries: Int = RuntimeMailboxLimits.MAX_ENTRIES,
    val maxTotalBytes: Long = RuntimeMailboxLimits.MAX_TOTAL_BYTES,
    val maxFileBytes: Long = RuntimeMailboxLimits.MAX_FILE_BYTES,
    val maxTarBytes: Long = RuntimeMailboxLimits.MAX_TAR_BYTES,
)

internal object MailboxDigest {
    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(chars)
    }

    fun isValid(value: String?): Boolean = value != null && SHA256_PATTERN.matches(value)

    /** 计算整个文件的字节数与 sha256；用于导出产物自证与导入侧校验。 */
    fun bytesAndSha256(file: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                total += read.toLong()
            }
        }
        return total to hex(digest.digest())
    }

    private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
    private const val BUFFER_SIZE = 64 * 1024
}

/**
 * 投递区专用的文件树操作。
 *
 * 与 `RuntimeFiles` 的分工：`RuntimeFiles.deleteTreeNoFollow` 依赖 `SecureDirectoryStream`
 * 与 `android.system.Os`，在 JVM 单元测试（尤其 Windows 夹具）上不可用；投递区的树操作
 * 全部只用 `java.nio.file`，因此**同一套生产代码可以在夹具里真跑**——这是 tar 往返、
 * 原子替换与「失败不留半截」能被实测而不是只靠走查的前提。
 */
internal object MailboxTree {
    fun readAttributesNoFollow(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    } catch (error: IOException) {
        throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法读取投递区条目属性", error)
    }

    /** 真实目录（不是符号链接）。 */
    fun isRealDirectory(path: Path): Boolean =
        readAttributesNoFollow(path)?.let { it.isDirectory && !it.isSymbolicLink } ?: false

    /** 真实常规文件（不是符号链接）。 */
    fun isRealRegularFile(path: Path): Boolean =
        readAttributesNoFollow(path)?.let { it.isRegularFile && !it.isSymbolicLink } ?: false

    fun sizeOrNull(path: Path): Long? = readAttributesNoFollow(path)?.size()

    fun lastModifiedMillis(path: Path): Long = readAttributesNoFollow(path)?.lastModifiedTime()?.toMillis() ?: 0L

    /** 权限位（低 9 位）；宿主不支持 POSIX 视图时返回 null（如 Windows 上的 JVM 夹具）。 */
    fun readMode(path: Path): Int? = try {
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: return null
        permissionBits(view.readAttributes().permissions())
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IOException) {
        null
    }

    /**
     * 落权限位。宿主没有 POSIX 视图时退化为「至少保住执行位」，
     * 两者都不可用时**不静默通过**：把失败交给调用方决定（导入侧视为失败并回滚）。
     */
    fun applyMode(path: Path, mode: Int) {
        try {
            Files.setPosixFilePermissions(path, permissions(mode))
            return
        } catch (_: UnsupportedOperationException) {
            // 无 POSIX 视图：走下面的执行位退化路径。
        } catch (error: IOException) {
            throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法设置投递区条目权限", error)
        }
        if (mode and 0x40 != 0) {
            val file = path.toFile()
            if (!file.setExecutable(true, false)) {
                throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法设置投递区条目执行位")
            }
        }
    }

    /**
     * 按分量创建目录，逐级 NoFollow：任何一级已存在的条目必须是**真实目录**，
     * 否则拒绝 —— 这样后续写入不可能被一个符号链接引到落点之外。
     */
    fun createDirectoriesNoFollow(root: Path, target: Path) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw RuntimeFailure(MailboxCodes.PATH_INVALID, "投递区目录越出落点")
        }
        var cursor = normalizedRoot
        if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !isRealDirectory(cursor)) {
            throw RuntimeFailure(MailboxCodes.PATH_INVALID, "投递区落点不是真实目录")
        }
        for (component in normalizedRoot.relativize(normalizedTarget)) {
            cursor = cursor.resolve(component)
            val attributes = readAttributesNoFollow(cursor)
            if (attributes == null) {
                try {
                    Files.createDirectory(cursor)
                } catch (_: FileAlreadyExistsException) {
                    // 并发创建：下面的一致性检查会兜住。
                }
            }
            if (!isRealDirectory(cursor)) {
                throw RuntimeFailure(MailboxCodes.PATH_INVALID, "投递区目录被非目录条目占用")
            }
        }
    }

    /**
     * 递归删除目录树，全程 NoFollow（不跟随符号链接）。
     *
     * 作用域校验与 `RuntimeFiles.deleteTreeNoFollow` 同口径：待删目录必须**直接**位于
     * 传入的允许父目录之下，且父目录必须是真实目录。调用方只允许传投递区自己创建的暂存目录。
     */
    fun deleteTreeNoFollow(root: Path, allowedParent: Path) {
        val normalizedParent = allowedParent.toAbsolutePath().normalize()
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (normalizedRoot.parent != normalizedParent || normalizedRoot == normalizedParent) {
            throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "拒绝删除投递区以外的路径")
        }
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) return
        if (!isRealDirectory(normalizedRoot)) {
            Files.deleteIfExists(normalizedRoot)
            return
        }
        Files.walkFileTree(
            normalizedRoot,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult = throw error
            },
        )
    }

    /** 同文件系统内的原子改名；目标已存在时按 `REPLACE_EXISTING` 覆盖。 */
    fun move(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法原子替换投递区产物", error)
        }
    }

    /**
     * 用 [source] 整体替换 [destination]（导入提交的唯一入口）。
     *
     * 三步：旧内容改名到 [stagingParent] 下的临时名 → 新内容改名就位 → 删除旧内容。
     * 这样做的两个性质都是验收要求的：
     *  - **幂等/可重入**：落点被整体替换，重复导入不会累积出第二份条目；
     *  - **失败不留半截产物**：改名失败时把旧内容放回原位，落点要么是旧的完整内容，
     *    要么是新的完整内容，不会出现合并后的中间态。
     */
    fun replaceDirectory(source: Path, destination: Path, stagingParent: Path) {
        val backup = stagingParent.resolve("mailbox-previous-${java.util.UUID.randomUUID()}")
        val replaced = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
        if (replaced) {
            if (!isRealDirectory(destination)) {
                throw RuntimeFailure(MailboxCodes.WORKSPACE_UNAVAILABLE, "导入落点被非目录条目占用")
            }
            move(destination, backup)
        }
        try {
            move(source, destination)
        } catch (error: Throwable) {
            if (replaced) {
                try {
                    move(backup, destination)
                } catch (_: Throwable) {
                    // 回滚也失败：旧内容仍在 stagingParent 里，不会被删除；如实抛出原始失败。
                }
            }
            throw error
        }
        if (replaced) deleteTreeQuietly(backup)
    }

    fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Throwable) {
            // 清理是尽力而为：失败原因由真正的失败路径报出，不在清理里覆盖它。
        }
    }

    /** 递归删除投递区自建的暂存目录；失败不抛出（失败原因由真正的失败路径报出）。 */
    fun deleteTreeQuietly(root: Path) {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Throwable) {
            // 尽力而为；残留的暂存目录会在下一次导入的清理里被删掉。
        }
    }

    fun permissionBits(permissions: Set<PosixFilePermission>): Int {
        var bits = 0
        permissions.forEach { permission ->
            bits = bits or when (permission) {
                PosixFilePermission.OWNER_READ -> 0x100
                PosixFilePermission.OWNER_WRITE -> 0x080
                PosixFilePermission.OWNER_EXECUTE -> 0x040
                PosixFilePermission.GROUP_READ -> 0x020
                PosixFilePermission.GROUP_WRITE -> 0x010
                PosixFilePermission.GROUP_EXECUTE -> 0x008
                PosixFilePermission.OTHERS_READ -> 0x004
                PosixFilePermission.OTHERS_WRITE -> 0x002
                PosixFilePermission.OTHERS_EXECUTE -> 0x001
            }
        }
        return bits
    }

    private fun permissions(mode: Int): Set<PosixFilePermission> {
        val builder = LinkedHashSet<PosixFilePermission>(9)
        if (mode and 0x100 != 0) builder += PosixFilePermission.OWNER_READ
        if (mode and 0x080 != 0) builder += PosixFilePermission.OWNER_WRITE
        if (mode and 0x040 != 0) builder += PosixFilePermission.OWNER_EXECUTE
        if (mode and 0x020 != 0) builder += PosixFilePermission.GROUP_READ
        if (mode and 0x010 != 0) builder += PosixFilePermission.GROUP_WRITE
        if (mode and 0x008 != 0) builder += PosixFilePermission.GROUP_EXECUTE
        if (mode and 0x004 != 0) builder += PosixFilePermission.OTHERS_READ
        if (mode and 0x002 != 0) builder += PosixFilePermission.OTHERS_WRITE
        if (mode and 0x001 != 0) builder += PosixFilePermission.OTHERS_EXECUTE
        return builder
    }
}

/**
 * 归档读取与规划。
 *
 * **[plan] 不写任何文件**：`../` 逃逸、绝对路径条目、指向私有区的绝对符号链接、
 * 超限、重复条目全部在这一步被拒绝，因此被拒绝的导入在访客私有区里不产生任何改动
 * （对应 §4.6 的 R2 与「摘要校验」两项）。
 */
internal class MailboxArchiveReader(private val limits: MailboxLimits = MailboxLimits()) {
    fun plan(archive: File, destinationRoot: Path): MailboxPlan {
        val path = archive.toPath()
        val attributes = MailboxTree.readAttributesNoFollow(path)
            ?: throw RuntimeFailure(MailboxCodes.INPUT_INVALID, "投递输入不存在")
        if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            throw RuntimeFailure(MailboxCodes.INPUT_INVALID, "投递输入不是常规文件")
        }
        val tarBytes = attributes.size()
        if (tarBytes <= 0L) {
            throw RuntimeFailure(MailboxCodes.INPUT_NOT_TAR, "投递输入为空文件")
        }
        if (tarBytes > limits.maxTarBytes) {
            throw RuntimeFailure(MailboxCodes.SIZE_LIMIT, "投递归档大小超出限额")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val entries = ArrayList<MailboxEntry>()
        val seen = HashSet<String>()
        val budget = longArrayOf(0L)

        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val counter = CountingInputStream(DigestInputStream(Channels.newInputStream(channel), digest))
            val buffered = BufferedInputStream(counter, BUFFER_SIZE)
            val tar = TarArchiveInputStream(buffered, TAR_ENCODING)
            try {
                while (true) {
                    val entry = tar.nextEntry as? TarArchiveEntry ?: break
                    readEntry(tar, entry, destinationRoot, entries, seen, budget)
                }
                drainToEof(buffered)
            } catch (error: RuntimeFailure) {
                throw error
            } catch (error: IOException) {
                throw RuntimeFailure(MailboxCodes.INPUT_NOT_TAR, "投递输入不是可解析的 tar 内容", error)
            } catch (error: IllegalArgumentException) {
                throw RuntimeFailure(MailboxCodes.INPUT_NOT_TAR, "投递输入不是可解析的 tar 内容", error)
            } finally {
                closeQuietly(tar)
            }
            if (counter.counted != tarBytes) {
                throw RuntimeFailure(MailboxCodes.CONTENT_MISMATCH, "投递归档在读取期间被改动")
            }
        }

        if (entries.isEmpty() && !looksLikeEmptyArchive(path, tarBytes)) {
            throw RuntimeFailure(MailboxCodes.INPUT_NOT_TAR, "投递输入不是可解析的 tar 内容")
        }
        return MailboxPlan(
            entries = entries,
            totalBytes = budget[0],
            tarBytes = tarBytes,
            tarSha256 = MailboxDigest.hex(digest.digest()),
        )
    }

    private fun readEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        destinationRoot: Path,
        entries: MutableList<MailboxEntry>,
        seen: MutableSet<String>,
        budget: LongArray,
    ) {
        val rawName = entry.name
        val trimmed = rawName.removeSuffix("/")
        // GNU tar 会写一条 "./" 根目录条目：只接受它是目录，且不占用条目名额。
        if (trimmed.isEmpty() || trimmed == ".") {
            if (!entry.isDirectory) {
                throw RuntimeFailure(MailboxCodes.PATH_INVALID, "归档根条目类型无效")
            }
            return
        }
        val name = RuntimeMailboxPolicy.normalizeEntryName(rawName)
        if (!seen.add(name)) {
            throw RuntimeFailure(MailboxCodes.DUPLICATE_ENTRY, "归档包含重复条目")
        }
        if (entries.size >= limits.maxEntries) {
            throw RuntimeFailure(MailboxCodes.ENTRY_LIMIT, "归档条目数量超过投放限额")
        }
        if (!tar.canReadEntryData(entry)) {
            throw RuntimeFailure(MailboxCodes.ENTRY_TYPE_REJECTED, "归档包含不支持的条目")
        }
        // 先解析出落点：路径越界（含符号链接目标）在这一步就被拒绝，之后才谈内容。
        RuntimeMailboxPolicy.resolveEntry(destinationRoot, name)
        val mode = entry.mode and 0x1FF
        when (classifyEntry(entry)) {
            ArchiveEntryKind.HARD_LINK -> {
                if (entry.size != 0L) {
                    throw RuntimeFailure(MailboxCodes.LINK_INVALID, "归档硬链接包含意外数据")
                }
                entries += MailboxEntry(
                    name = name,
                    kind = MailboxEntryKind.HARDLINK,
                    bytes = 0L,
                    mode = mode,
                    sha256 = null,
                    hardlinkTarget = RuntimeMailboxPolicy.normalizeHardlinkSource(entry.linkName),
                )
            }
            ArchiveEntryKind.DIRECTORY -> entries += MailboxEntry(
                name = name,
                kind = MailboxEntryKind.DIRECTORY,
                bytes = 0L,
                mode = mode,
                sha256 = null,
            )
            ArchiveEntryKind.SYMBOLIC_LINK -> {
                if (entry.size != 0L) {
                    throw RuntimeFailure(MailboxCodes.LINK_INVALID, "归档符号链接包含意外数据")
                }
                entries += MailboxEntry(
                    name = name,
                    kind = MailboxEntryKind.SYMLINK,
                    bytes = 0L,
                    mode = mode,
                    sha256 = null,
                    linkTarget = RuntimeMailboxPolicy.normalizeLinkTarget(destinationRoot, name, entry.linkName),
                )
            }
            ArchiveEntryKind.FILE -> {
                if (entry.isSparse || entry.size < 0L) {
                    throw RuntimeFailure(MailboxCodes.ENTRY_TYPE_REJECTED, "归档包含稀疏或无效文件")
                }
                if (entry.size > limits.maxFileBytes) {
                    throw RuntimeFailure(MailboxCodes.SIZE_LIMIT, "归档内单个文件超过投放限额")
                }
                if (budget[0] > limits.maxTotalBytes - entry.size) {
                    throw RuntimeFailure(MailboxCodes.SIZE_LIMIT, "归档内容总大小超过投放限额")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var remaining = entry.size
                val buffer = ByteArray(BUFFER_SIZE)
                // 条目内容被截断时 commons-compress 抛的是 IOException("Truncated TAR archive")，
                // 必须与「头部根本不是 tar」区分开：这里的结论是归档不完整，不是格式不对。
                try {
                    while (remaining > 0L) {
                        val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) {
                            throw RuntimeFailure(MailboxCodes.ARCHIVE_TRUNCATED, "归档内文件内容不完整")
                        }
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        remaining -= read.toLong()
                    }
                } catch (error: IOException) {
                    throw RuntimeFailure(MailboxCodes.ARCHIVE_TRUNCATED, "归档内文件内容不完整", error)
                }
                budget[0] += entry.size
                entries += MailboxEntry(
                    name = name,
                    kind = MailboxEntryKind.FILE,
                    bytes = entry.size,
                    mode = mode,
                    sha256 = MailboxDigest.hex(digest.digest()),
                )
            }
            ArchiveEntryKind.UNSUPPORTED ->
                throw RuntimeFailure(MailboxCodes.ENTRY_TYPE_REJECTED, "归档包含设备节点、FIFO 等不支持的条目")
        }
    }

    /** 全零填充且长度为 512 整数倍的归档按「合法的空 tar」接受，其余内容判为非 tar。 */
    private fun looksLikeEmptyArchive(path: Path, tarBytes: Long): Boolean {
        if (tarBytes % RECORD_SIZE != 0L) return false
        val probe = ByteArray(minOf(tarBytes, RECORD_SIZE.toLong()).toInt())
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(probe)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
        }
        return probe.all { it == 0.toByte() }
    }

    private fun drainToEof(input: InputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            if (input.read(buffer) < 0) return
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val RECORD_SIZE = 512
    }
}

/**
 * 把规划落到暂存目录。
 *
 * 顺序固定：**目录 → 常规文件 → 硬链接（内容复制）→ 符号链接 → 目录权限**。
 * 符号链接最后建是为了让「写入期不存在任何链接」成为结构上的保证（配合
 * [MailboxTree.createDirectoriesNoFollow] 的逐级 NoFollow，链接无法把写操作引出落点）。
 *
 * 契约：[destination] 必须是**调用方自己创建的**暂存目录；失败时它会被整树删除
 * （「失败不留半截产物」），因此调用方不能把已有内容交给它。
 */
internal class MailboxArchiveExtractor(private val limits: MailboxLimits = MailboxLimits()) {
    fun extract(archive: File, plan: MailboxPlan, destination: Path) {
        val root = destination.toAbsolutePath().normalize()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectories(root)
            } catch (error: IOException) {
                throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法创建投递暂存目录", error)
            }
        }
        if (!MailboxTree.isRealDirectory(root)) {
            throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "投递暂存目录不可用")
        }

        val directories = ArrayList<Pair<Path, Int>>()
        val symlinks = ArrayList<Pair<Path, String>>()
        val hardlinks = ArrayList<Pair<Path, String>>()
        var writtenBytes = 0L
        var failure: Throwable? = null
        try {
            FileChannel.open(archive.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                BufferedInputStream(Channels.newInputStream(channel), BUFFER_SIZE).use { buffered ->
                    TarArchiveInputStream(buffered, TAR_ENCODING).use { tar ->
                        val planned = plan.entries.iterator()
                        while (true) {
                            val entry = tar.nextEntry as? TarArchiveEntry ?: break
                            val trimmed = entry.name.removeSuffix("/")
                            if (trimmed.isEmpty() || trimmed == ".") continue
                            if (!planned.hasNext()) {
                                throw RuntimeFailure(MailboxCodes.CONTENT_MISMATCH, "归档在两次读取之间被改动")
                            }
                            val expected = planned.next()
                            val target = RuntimeMailboxPolicy.resolveEntry(root, expected.name)
                            when (expected.kind) {
                                MailboxEntryKind.DIRECTORY -> {
                                    // 目录条目必须真的建出来：空目录也要存在，且权限在最后一并落。
                                    MailboxTree.createDirectoriesNoFollow(root, target)
                                    directories += target to expected.mode
                                }
                                MailboxEntryKind.SYMLINK -> symlinks += target to (expected.linkTarget ?: "")
                                MailboxEntryKind.HARDLINK -> hardlinks += target to (expected.hardlinkTarget ?: "")
                                MailboxEntryKind.FILE -> {
                                    if (writtenBytes > limits.maxTotalBytes - expected.bytes) {
                                        throw RuntimeFailure(
                                            MailboxCodes.SIZE_LIMIT,
                                            "投递内容总大小超过限额",
                                        )
                                    }
                                    MailboxTree.createDirectoriesNoFollow(root, target.parent)
                                    writeFile(tar, target, expected)
                                    writtenBytes += expected.bytes
                                }
                            }
                        }
                        if (planned.hasNext()) {
                            throw RuntimeFailure(MailboxCodes.CONTENT_MISMATCH, "归档在两次读取之间被改动")
                        }
                    }
                }
            }
            materializeHardlinks(root, hardlinks, limits, writtenBytes)
            createSymlinks(symlinks)
            // 目录权限最后落：先落权限会让不可写目录挡住后续写入。
            directories.sortedByDescending { depthOf(root, it.first) }.forEach { (path, mode) ->
                MailboxTree.applyMode(path, mode)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (failure != null) {
                // 失败不留半截产物：暂存目录整体删除，由调用方决定是否重试。
                MailboxTree.deleteTreeQuietly(root)
            }
        }
    }

    private fun writeFile(tar: TarArchiveInputStream, target: Path, expected: MailboxEntry) {
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = expected.bytes
        val buffer = ByteArray(BUFFER_SIZE)
        val options = arrayOf<OpenOption>(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        try {
            Files.newOutputStream(target, *options).use { output ->
                while (remaining > 0L) {
                    val read = readEntryChunk(tar, buffer, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) {
                        throw RuntimeFailure(MailboxCodes.ARCHIVE_TRUNCATED, "归档内文件内容不完整")
                    }
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    remaining -= read.toLong()
                }
            }
        } catch (error: FileAlreadyExistsException) {
            throw RuntimeFailure(MailboxCodes.DUPLICATE_ENTRY, "投递落点已存在同名条目")
        } catch (error: IOException) {
            throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法写入投递落点", error)
        }
        val actual = MailboxDigest.hex(digest.digest())
        if (expected.sha256 != null && actual != expected.sha256) {
            throw RuntimeFailure(MailboxCodes.CONTENT_MISMATCH, "投递条目内容与规划不一致")
        }
        MailboxTree.applyMode(target, expected.mode)
    }

    /**
     * 硬链接按**内容复制**落地。
     *
     * 理由是本机实测事实（P0-1）：`link(2)` 在 f2fs / tmpfs / FUSE 三处一律 EACCES，
     * 建立硬链接必然失败；因此这里不尝试 `link`，直接按源文件内容复制并独立落权限位，
     * 语义上等价于「同一个内容的第二份文件」，且导入结果可预期。
     */
    private fun materializeHardlinks(
        root: Path,
        pending: List<Pair<Path, String>>,
        limits: MailboxLimits,
        alreadyWritten: Long,
    ) {
        if (pending.isEmpty()) return
        var total = alreadyWritten
        pending.forEach { (link, sourceName) ->
            val source = RuntimeMailboxPolicy.resolveEntry(root, sourceName)
            if (!MailboxTree.isRealRegularFile(source)) {
                throw RuntimeFailure(MailboxCodes.LINK_INVALID, "归档硬链接源不是落点内的常规文件")
            }
            val bytes = MailboxTree.sizeOrNull(source)
                ?: throw RuntimeFailure(MailboxCodes.LINK_INVALID, "归档硬链接源不可读")
            if (total > limits.maxTotalBytes - bytes) {
                throw RuntimeFailure(MailboxCodes.SIZE_LIMIT, "投递内容总大小超过限额")
            }
            total += bytes
            MailboxTree.createDirectoriesNoFollow(root, link.parent)
            try {
                Files.copy(source, link, LinkOption.NOFOLLOW_LINKS)
            } catch (error: FileAlreadyExistsException) {
                throw RuntimeFailure(MailboxCodes.DUPLICATE_ENTRY, "投递落点已存在同名条目")
            } catch (error: IOException) {
                throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法写入投递落点", error)
            }
            MailboxTree.readMode(source)?.let { MailboxTree.applyMode(link, it) }
        }
    }

    /**
     * 建符号链接。设备拒绝（部分 ROM 的 SELinux 策略给出 EACCES/EPERM）时**明确报错**，
     * 不降级成复制：降级会让「符号链接仍是链接」这条验收判据失真（§4.6 R1）。
     */
    private fun createSymlinks(pending: List<Pair<Path, String>>) {
        pending.forEach { (link, rawTarget) ->
            if (rawTarget.startsWith('/')) {
                throw RuntimeFailure(MailboxCodes.LINK_ABSOLUTE, "归档符号链接使用绝对目标，已拒绝")
            }
            try {
                Files.createSymbolicLink(link, Paths.get(rawTarget))
            } catch (error: FileAlreadyExistsException) {
                throw RuntimeFailure(MailboxCodes.DUPLICATE_ENTRY, "投递落点已存在同名条目")
            } catch (_: UnsupportedOperationException) {
                throw RuntimeFailure(MailboxCodes.LINK_UNSUPPORTED, "当前设备不支持创建符号链接")
            } catch (_: SecurityException) {
                throw RuntimeFailure(MailboxCodes.LINK_UNSUPPORTED, "当前设备不支持创建符号链接")
            } catch (error: IOException) {
                throw RuntimeFailure(MailboxCodes.LINK_UNSUPPORTED, "当前设备不支持创建符号链接", error)
            }
        }
    }

    private fun depthOf(root: Path, path: Path): Int = root.relativize(path).nameCount

    /** 从归档读一段条目内容；读取期被截断时不冒领成文件系统错误。 */
    private fun readEntryChunk(tar: TarArchiveInputStream, buffer: ByteArray, length: Int): Int = try {
        tar.read(buffer, 0, length)
    } catch (error: IOException) {
        throw RuntimeFailure(MailboxCodes.ARCHIVE_TRUNCATED, "归档内文件内容不完整", error)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

/**
 * 工作区（或指定子目录）扫描：产出导出规划。
 *
 * 只读，且 **`entries` 按条目名排序**：tar 字节因此可复现——同一棵未改动的工作区
 * 连续导出两次得到相同的 sha256，这一点由单元测试直接断言。
 */
internal class MailboxWorkspaceScanner(private val limits: MailboxLimits = MailboxLimits()) {
    /** 扫描期间的字数预算：条目数与内容总字节。 */
    private class Budget {
        var entries = 0
        var bytes = 0L
    }

    fun scan(workspace: Path, subdirectory: String?): MailboxExportPlan {
        if (!MailboxTree.isRealDirectory(workspace)) {
            throw RuntimeFailure(MailboxCodes.WORKSPACE_UNAVAILABLE, "工作区不可用")
        }
        val root = subdirectory?.let { RuntimeMailboxPolicy.resolveEntry(workspace, it) } ?: workspace
        if (!MailboxTree.isRealDirectory(root)) {
            throw RuntimeFailure(MailboxCodes.WORKSPACE_UNAVAILABLE, "指定的工作区子目录不存在或不是目录")
        }
        val prefix = subdirectory?.let { "$it/" } ?: ""
        val entries = ArrayList<MailboxEntry>()
        val skipped = intArrayOf(0, 0)
        val budget = Budget()

        Files.walkFileTree(
            root,
            emptySet(),
            MAX_WALK_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root) {
                        entries += directoryEntry(root, prefix, dir, budget)
                    } else if (prefix.isNotEmpty()) {
                        entries += directoryEntry(root, prefix, root, budget)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    when {
                        attrs.isSymbolicLink -> collectSymlink(root, prefix, file, entries, skipped, budget)
                        attrs.isRegularFile -> entries += fileEntry(root, prefix, file, attrs, budget)
                        else -> skipped[1] += 1
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
                    throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法读取工作区条目", error)
            },
        )
        return MailboxExportPlan(
            subdirectory = subdirectory,
            prefix = prefix,
            entries = entries.sortedBy { it.name },
            totalBytes = entries.sumOf { it.bytes },
            skippedLinks = skipped[0],
            skippedSpecial = skipped[1],
        )
    }

    private fun directoryEntry(
        root: Path,
        prefix: String,
        directory: Path,
        budget: Budget,
    ): MailboxEntry {
        // 用规范化后的返回值：导出子目录时起点自身的名字是 "<子目录>/"，必须收敛成 "<子目录>"。
        val name = RuntimeMailboxPolicy.normalizeEntryName(prefix + relativeName(root, directory))
        return MailboxEntry(
            name = name,
            kind = MailboxEntryKind.DIRECTORY,
            bytes = 0L,
            mode = MailboxTree.readMode(directory) ?: DEFAULT_DIRECTORY_MODE,
            sha256 = null,
        ).also { ensureEntryBudget(it, budget) }
    }

    private fun fileEntry(
        root: Path,
        prefix: String,
        file: Path,
        attrs: BasicFileAttributes,
        budget: Budget,
    ): MailboxEntry {
        val name = RuntimeMailboxPolicy.normalizeEntryName(prefix + relativeName(root, file))
        if (attrs.size() > limits.maxFileBytes) {
            throw RuntimeFailure(MailboxCodes.EXPORT_LIMIT, "工作区单个文件超过导出限额")
        }
        val (bytes, sha256) = MailboxDigest.bytesAndSha256(file.toFile())
        if (bytes != attrs.size()) {
            throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "工作区文件在导出期间被改动")
        }
        return MailboxEntry(
            name = name,
            kind = MailboxEntryKind.FILE,
            bytes = bytes,
            mode = MailboxTree.readMode(file) ?: DEFAULT_FILE_MODE,
            sha256 = sha256,
        ).also { ensureEntryBudget(it, budget) }
    }

    /**
     * 符号链接条目只在**目标仍在导出起点内**时才收录。
     *
     * 绝对目标与越出起点的目标一律跳过并计数：产物里不允许出现绝对路径或宿主/私有区路径
     * （「不把绝对路径写进给用户的产物」），而这些链接在导入端本来也会被拒绝。
     */
    private fun collectSymlink(
        root: Path,
        prefix: String,
        file: Path,
        entries: MutableList<MailboxEntry>,
        skipped: IntArray,
        budget: Budget,
    ) {
        val rawTarget = try {
            Files.readSymbolicLink(file).toString().replace('\\', '/')
        } catch (_: IOException) {
            skipped[0] += 1
            return
        } catch (_: UnsupportedOperationException) {
            skipped[0] += 1
            return
        }
        if (rawTarget.startsWith('/')) {
            skipped[0] += 1
            return
        }
        val parent = file.parent ?: run {
            skipped[0] += 1
            return
        }
        val resolved = parent.resolve(rawTarget).normalize()
        if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
            skipped[0] += 1
            return
        }
        val name = RuntimeMailboxPolicy.normalizeEntryName(prefix + relativeName(root, file))
        entries += MailboxEntry(
            name = name,
            kind = MailboxEntryKind.SYMLINK,
            bytes = 0L,
            mode = MailboxTree.readMode(file) ?: DEFAULT_LINK_MODE,
            sha256 = null,
            linkTarget = parent.relativize(resolved).toString().replace('\\', '/').ifEmpty { "." },
        ).also { ensureEntryBudget(it, budget) }
    }

    private fun ensureEntryBudget(entry: MailboxEntry, budget: Budget) {
        if (budget.entries >= limits.maxEntries) {
            throw RuntimeFailure(MailboxCodes.EXPORT_LIMIT, "工作区条目数量超过导出限额")
        }
        budget.entries += 1
        if (budget.bytes > limits.maxTotalBytes - entry.bytes) {
            throw RuntimeFailure(MailboxCodes.EXPORT_LIMIT, "工作区内容总大小超过导出限额")
        }
        budget.bytes += entry.bytes
    }

    private fun relativeName(root: Path, path: Path): String =
        root.relativize(path).toString().replace('\\', '/')

    private companion object {
        const val MAX_WALK_DEPTH = 256
        const val DEFAULT_FILE_MODE = 0x1A4
        const val DEFAULT_DIRECTORY_MODE = 0x1ED
        const val DEFAULT_LINK_MODE = 0x1FF
    }
}

/** 写 tar。条目顺序、权限位、uid/gid 与时间戳全部显式给定，保证同一棵树得到同样的字节。 */
internal class MailboxArchiveWriter {
    /**
     * @param exportRoot 导出起点目录：整个工作区，或用户指定的那个子目录。
     *   条目名带 `<子目录>/` 前缀，因此**必须**以导出起点（而不是工作区）为基准解析源路径。
     */
    fun write(exportRoot: Path, plan: MailboxExportPlan, target: File) {
        val options = arrayOf<OpenOption>(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        try {
            Files.newOutputStream(target.toPath(), *options).use { raw ->
                BufferedOutputStream(raw, BUFFER_SIZE).use { buffered ->
                    TarArchiveOutputStream(buffered, RECORD_SIZE, TAR_ENCODING).use { tar ->
                        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                        plan.entries.forEach { entry -> writeEntry(tar, exportRoot, plan, entry) }
                        tar.finish()
                    }
                }
            }
        } catch (error: IOException) {
            throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法写出投递归档", error)
        }
    }

    private fun writeEntry(
        tar: TarArchiveOutputStream,
        exportRoot: Path,
        plan: MailboxExportPlan,
        entry: MailboxEntry,
    ) {
        val source = sourceOf(exportRoot, plan, entry)
        val tarName = if (entry.kind == MailboxEntryKind.DIRECTORY) "${entry.name}/" else entry.name
        val header = TarArchiveEntry(tarName, linkFlag(entry.kind))
        header.mode = entry.mode or typeBits(entry.kind)
        header.setIds(0, 0)
        header.userName = OWNER_NAME
        header.groupName = OWNER_NAME
        header.setModTime(MailboxTree.lastModifiedMillis(source))
        when (entry.kind) {
            MailboxEntryKind.DIRECTORY -> {
                tar.putArchiveEntry(header)
                tar.closeArchiveEntry()
            }
            MailboxEntryKind.SYMLINK -> {
                header.linkName = entry.linkTarget
                header.size = 0L
                tar.putArchiveEntry(header)
                tar.closeArchiveEntry()
            }
            MailboxEntryKind.FILE -> {
                header.size = entry.bytes
                tar.putArchiveEntry(header)
                Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    while (copied < entry.bytes) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), entry.bytes - copied).toInt())
                        if (read < 0) {
                            throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "工作区文件在导出期间被截断")
                        }
                        tar.write(buffer, 0, read)
                        copied += read.toLong()
                    }
                }
                tar.closeArchiveEntry()
            }
            MailboxEntryKind.HARDLINK ->
                throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "导出不产出硬链接条目")
        }
    }

    /**
     * 条目名 → 源路径。
     *
     * 导出子目录时，起点自身的条目名就是子目录名（不带尾随 `/`），其余条目名带 `<子目录>/` 前缀；
     * 两者都要落到**导出起点**之下，因此前缀先去、起点条目单独处理。
     */
    private fun sourceOf(exportRoot: Path, plan: MailboxExportPlan, entry: MailboxEntry): Path {
        val relative = when {
            entry.name == plan.subdirectory -> ""
            plan.prefix.isEmpty() -> entry.name
            else -> entry.name.removePrefix(plan.prefix)
        }
        val normalizedRoot = exportRoot.toAbsolutePath().normalize()
        if (relative.isEmpty()) return normalizedRoot
        return RuntimeMailboxPolicy.resolveEntry(normalizedRoot, relative)
    }

    private fun linkFlag(kind: MailboxEntryKind): Byte = when (kind) {        MailboxEntryKind.DIRECTORY -> TarConstants.LF_DIR
        MailboxEntryKind.SYMLINK -> TarConstants.LF_SYMLINK
        MailboxEntryKind.HARDLINK -> TarConstants.LF_LINK
        MailboxEntryKind.FILE -> TarConstants.LF_NORMAL
    }

    private fun typeBits(kind: MailboxEntryKind): Int = when (kind) {
        MailboxEntryKind.DIRECTORY -> 0x4000
        MailboxEntryKind.SYMLINK -> 0xA000
        MailboxEntryKind.HARDLINK -> 0x8000
        MailboxEntryKind.FILE -> 0x8000
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val RECORD_SIZE = 512
        const val OWNER_NAME = "root"
    }
}

private const val TAR_ENCODING = "UTF-8"

/**
 * 选择本次要导入的 tar。
 *
 * 规则刻意是**确定性**的：优先取导出产物的固定名 [RuntimeMailboxLayout.EXPORT_TAR_NAME]，
 * 否则要求 inbox 里恰好只有一个 `.tar`；一个都没有（或出现多个而无法确定）一律明确报错，
 * 不猜、不按修改时间挑。**散文件永远不会被导入**，只计入 `ignoredFiles`。
 *
 * 与 Android 无关，因此可以在夹具里直接测：inbox 里放散文件、放两个 tar、放非 tar 内容
 * 这些情况都只靠文件系统构造。
 */
internal object MailboxInputSelection {
    class Selected(val tar: File, val manifest: File?, val sha256File: File?, val ignoredFiles: Int)

    fun select(inbox: File): Selected {
        if (!MailboxTree.isRealDirectory(inbox.toPath())) {
            throw RuntimeFailure(MailboxCodes.UNAVAILABLE, "投递区 inbox 目录不可用")
        }
        val files = (inbox.listFiles() ?: emptyArray())
            .filter { MailboxTree.isRealRegularFile(it.toPath()) && !it.name.startsWith(PROBE_PREFIX) }
        val tars = files.filter { it.name.lowercase().endsWith(".tar") }
        val chosen = when {
            tars.isEmpty() -> throw RuntimeFailure(
                MailboxCodes.INPUT_INVALID,
                "inbox 里没有 tar 文件；散文件不会被导入，请先打成 tar",
            )
            tars.any { it.name == RuntimeMailboxLayout.EXPORT_TAR_NAME } ->
                tars.first { it.name == RuntimeMailboxLayout.EXPORT_TAR_NAME }
            tars.size == 1 -> tars.first()
            else -> throw RuntimeFailure(
                MailboxCodes.INPUT_INVALID,
                "inbox 里有多个 tar，无法确定导入哪一个；请一次只放一个",
            )
        }
        val base = chosen.name.removeSuffix(".tar")
        val manifest = listOf("$base.manifest.json", "manifest.json")
            .map { File(inbox, it) }
            .firstOrNull { MailboxTree.isRealRegularFile(it.toPath()) }
        val sha256File = listOf("${chosen.name}.sha256", "$base.sha256")
            .map { File(inbox, it) }
            .firstOrNull { MailboxTree.isRealRegularFile(it.toPath()) }
        val ignored = files.count { it != chosen && it != manifest && it != sha256File && !it.name.startsWith(".") }
        return Selected(chosen, manifest, sha256File, ignored)
    }

    /** 写探测文件的固定前缀：既不会被当成导入输入，也不会被算作「被忽略的散文件」。 */
    const val PROBE_PREFIX = ".dsh-mailbox-probe-"
}

/**
 * manifest 与归档的逐条比对（导入侧的唯一判定点）。
 *
 * 任何一项不符都**整单拒绝**：不做部分导入。这是 §4.4「摘要不符时报错，不做部分导入」
 * 与 §4.6「人为改一个字节后导入必须报错」两条的落点。
 */
internal object MailboxManifestVerifier {
    fun verify(manifest: MailboxManifest, plan: MailboxPlan) {
        if (manifest.tarSha256 != plan.tarSha256 || manifest.tarBytes != plan.tarBytes) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_MISMATCH, "投递归档摘要与 manifest 不一致")
        }
        if (manifest.entryCount != plan.entries.size || manifest.totalBytes != plan.totalBytes) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_MISMATCH, "投递归档条目数与 manifest 不一致")
        }
        val declared = manifest.entries.associateBy { it.path }
        if (declared.size != manifest.entries.size) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 含重复条目路径")
        }
        plan.entries.forEach { entry ->
            val item = declared[entry.name]
                ?: throw RuntimeFailure(MailboxCodes.MANIFEST_MISMATCH, "manifest 缺少归档中的条目")
            if (item.bytes != entry.bytes) {
                throw RuntimeFailure(MailboxCodes.MANIFEST_MISMATCH, "manifest 条目字节数与归档不一致")
            }
            when (entry.kind) {
                MailboxEntryKind.FILE -> if (item.type != MailboxManifestCodec.WIRE_FILE ||
                    item.sha256 != entry.sha256
                ) {
                    throw RuntimeFailure(MailboxCodes.MANIFEST_MISMATCH, "manifest 条目摘要与归档不一致")
                }
                MailboxEntryKind.DIRECTORY -> if (item.type != MailboxManifestCodec.WIRE_DIRECTORY) {
                    throw RuntimeFailure(MailboxCodes.MANIFEST_MISMATCH, "manifest 条目类型与归档不一致")
                }
                MailboxEntryKind.SYMLINK -> if (item.type != MailboxManifestCodec.WIRE_SYMLINK ||
                    item.target != entry.linkTarget
                ) {
                    throw RuntimeFailure(MailboxCodes.MANIFEST_MISMATCH, "manifest 符号链接目标与归档不一致")
                }
                MailboxEntryKind.HARDLINK -> Unit
            }
        }
    }
}

/** 统计实际读取的字节数：用于证明摘要覆盖了整个归档文件（见 [MailboxArchiveReader.plan]）。 */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var counted = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) counted += 1
        return value
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(destination, offset, length)
        if (read > 0) counted += read.toLong()
        return read
    }

    override fun skip(byteCount: Long): Long {
        val skipped = super.skip(byteCount)
        if (skipped > 0) counted += skipped
        return skipped
    }
}

private class DigestInputStream(input: InputStream, private val digest: MessageDigest) : FilterInputStream(input) {
    override fun read(): Int {
        val value = super.read()
        if (value >= 0) digest.update(value.toByte())
        return value
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(destination, offset, length)
        if (read > 0) digest.update(destination, offset, read)
        return read
    }
}

private fun closeQuietly(closeable: java.io.Closeable) {
    try {
        closeable.close()
    } catch (_: Throwable) {
        // 归档已经读完或被上层拒绝；关闭失败不改变结论。
    }
}
