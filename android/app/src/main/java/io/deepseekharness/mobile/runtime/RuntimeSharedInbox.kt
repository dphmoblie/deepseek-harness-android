package io.deepseekharness.mobile.runtime

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

/** 外部分享先落私有暂存区；与根目录提升共用锁，投递成功后才删除原件。 */
internal class RuntimeSharedInbox(
    private val filesDir: File,
    private val runtimeAnchor: File,
    private val currentRoot: File,
) {
    constructor(store: RuntimeStore) : this(store.sharedFilesDirectory, store.runtimeParent.parentFile!!, store.currentRoot)

    fun stagingDirectory(): File = withLock {
        val inbox = File(filesDir, "inbox")
        ensureDirectory(inbox)
        inbox
    }

    /** 只能在访客已停止时调用；运行中投递不创建任何访客可修改的目录。 */
    fun prepareWorkspace() = withLock {
        if (!Files.exists(currentRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return@withLock
        val anchor = runtimeAnchor.toPath().toAbsolutePath().normalize()
        val current = currentRoot.toPath().toAbsolutePath().normalize()
        require(current != anchor && current.startsWith(anchor))
        val relative = anchor.relativize(current)
        var directory = runtimeAnchor
        (relative.map { it.toString() } + listOf("root", "1", "inbox")).forEach { name ->
            directory = File(directory, name)
            ensureDirectory(directory)
        }
    }

    /** 返回是否已全部投递；不支持安全访问或失败时保留暂存文件供下次重试。 */
    fun deliver(): Boolean = withLock {
        val staged = File(filesDir, "inbox")
        if (!Files.exists(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) return@withLock true
        try {
            withDirectory(filesDir, listOf("inbox")) { source ->
                // 应用被杀时可能留下半个文件；只有原子发布后的完整暂存文件可以迁移。
                val entries = source.map { it.fileName }.filterNot { it.toString().startsWith(".pending-") }.toList()
                if (entries.isEmpty()) return@withDirectory true
                val anchor = runtimeAnchor.toPath().toAbsolutePath().normalize()
                val current = currentRoot.toPath().toAbsolutePath().normalize()
                require(current != anchor && current.startsWith(anchor))
                withDirectory(runtimeAnchor, anchor.relativize(current).map { it.toString() } + listOf("root", "1", "inbox")) { target ->
                    var complete = true
                    entries.forEach { name ->
                        try {
                            transfer(source, target, name)
                        } catch (_: Exception) {
                            complete = false
                        }
                    }
                    complete
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun transfer(source: SecureDirectoryStream<Path>, target: SecureDirectoryStream<Path>, name: Path) {
        val attributes = source.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > MAX_FILE_BYTES) {
            throw RuntimeFailure("SHARED_FILE_INVALID", "分享暂存文件不可用")
        }
        var targetName = name
        source.newByteChannel(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val outputChannel = try {
                    createTarget(target, targetName)
                } catch (_: FileAlreadyExistsException) {
                    // 同名文件不覆盖，另起应用生成的唯一名字；原始文件名仍留在后缀中。
                    targetName = name.fileSystem.getPath("${UUID.randomUUID()}-${name.toString().takeLast(160)}")
                    createTarget(target, targetName)
                }
                outputChannel.use { output ->
                    val buffer = ByteBuffer.allocate(16 * 1024)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) throw RuntimeFailure("SHARED_FILE_INVALID", "分享文件超过大小限制")
                        digest.update(buffer.array(), 0, read)
                        buffer.flip()
                        while (buffer.hasRemaining()) {
                            if (output.write(buffer) <= 0) throw RuntimeFailure("SHARED_FILE_WRITE_FAILED", "分享文件投递失败")
                        }
                        buffer.clear()
                    }
                    if (total != attributes.size()) throw RuntimeFailure("SHARED_FILE_CHANGED", "分享文件发生变化")
                    (output as? FileChannel)?.force(true)
                }
                // 重新从固定目录句柄打开目标并校验，复制或并发替换失败时仍保留原件。
                val copied = target.getFileAttributeView(targetName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
                if (!copied.isRegularFile || copied.isSymbolicLink || copied.size() != attributes.size()) {
                    throw RuntimeFailure("SHARED_FILE_CHANGED", "分享文件投递校验失败")
                }
                val expected = digest.digest()
                val actual = MessageDigest.getInstance("SHA-256")
                target.newByteChannel(targetName, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { verification ->
                    val buffer = ByteBuffer.allocate(16 * 1024)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val read = verification.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) throw RuntimeFailure("SHARED_FILE_CHANGED", "分享文件投递校验失败")
                        actual.update(buffer.array(), 0, read)
                        buffer.clear()
                    }
                    if (total != attributes.size() || !MessageDigest.isEqual(expected, actual.digest())) {
                        throw RuntimeFailure("SHARED_FILE_CHANGED", "分享文件投递校验失败")
                    }
                }
        }
        // 迁移期间失败必须保留源；不能按名字清目标，因为访客可能已把该名字换成自己的文件。
        source.deleteFile(name)
    }

    private fun createTarget(target: SecureDirectoryStream<Path>, name: Path) = target.newByteChannel(
        name,
        setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
    )

    private fun ensureDirectory(directory: File) {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (directory.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.createDirectory(directory.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectory(directory.toPath())
            }
        }
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw RuntimeFailure("SHARED_INBOX_UNAVAILABLE", "分享收件箱不是安全目录")
        }
    }

    private fun <T> withDirectory(anchor: File, names: List<String>, action: (SecureDirectoryStream<Path>) -> T): T {
        fun visit(parent: SecureDirectoryStream<Path>, index: Int): T =
            if (index == names.size) action(parent)
            else parent.newDirectoryStream(anchor.toPath().fileSystem.getPath(names[index]), LinkOption.NOFOLLOW_LINKS)
                .use { visit(it, index + 1) }
        return Files.newDirectoryStream(anchor.toPath()).use { stream ->
            if (stream !is SecureDirectoryStream<*>) throw RuntimeFailure("SHARED_SECURE_ACCESS_UNAVAILABLE", "系统不支持安全投递分享文件")
            @Suppress("UNCHECKED_CAST")
            visit(stream as SecureDirectoryStream<Path>, 0)
        }
    }

    companion object {
        private val lock = Any()
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024

        fun <T> withLock(action: () -> T): T = synchronized(lock, action)
    }
}
