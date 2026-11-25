package io.deepseekharness.mobile.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** 外部分享先落私有暂存区；与根目录提升共用锁，投递成功后才删除原件。 */
internal class RuntimeSharedInbox(
    private val filesDir: File,
    private val runtimeAnchor: File,
    private val currentRoot: File,
    private val publish: (Path, Path, Path, List<String>, Path) -> Delivery = RuntimeSharedFilePublisher::publish,
) {
    constructor(store: RuntimeStore) : this(store.sharedFilesDirectory, store.runtimeParent.parentFile!!, store.currentRoot)

    // 一批文件有多个结果时，优先提示可操作的空间不足。
    enum class Delivery { DELIVERED, STAGED, UNAVAILABLE, NO_SPACE }

    fun stagingDirectory(): File = withLock {
        val inbox = File(filesDir, "inbox")
        ensureDirectory(inbox)
        cleanupOrphanedPending(inbox)
        inbox
    }

    /**
     * 为一次导入登记临时文件的所有权。登记和清理必须在同一把锁下完成，
     * 这样 Activity 重建时不会误删仍在复制中的文件。
     */
    fun <T> withPendingFile(action: (File) -> T): T {
        val pending = withLock {
            val inbox = File(filesDir, "inbox")
            ensureDirectory(inbox)
            cleanupOrphanedPending(inbox)
            val file = File(inbox, ".pending-${UUID.randomUUID()}")
            val permissions = if (file.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else emptyArray()
            Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), *permissions).use { }
            activePending.add(file.toPath().toAbsolutePath().normalize())
            file
        }
        return try {
            action(pending)
        } finally {
            withLock {
                activePending.remove(pending.toPath().toAbsolutePath().normalize())
                runCatching { Files.deleteIfExists(pending.toPath()) }
            }
        }
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

    /** 同卷移动只更新目录项，安装和启动无需在锁内读写文件内容。 */
    fun deliver(): Delivery = withLock {
        if (Thread.currentThread().isInterrupted) return@withLock Delivery.UNAVAILABLE
        val staged = File(filesDir, "inbox")
        if (!Files.exists(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) return@withLock Delivery.DELIVERED
        try {
            ensureDirectory(staged)
            cleanupOrphanedPending(staged)
            val source = staged.toPath()
            val anchor = runtimeAnchor.toPath().toAbsolutePath().normalize()
            val current = currentRoot.toPath().toAbsolutePath().normalize()
            require(current != anchor && current.startsWith(anchor))
            val directories = anchor.relativize(current).map { it.toString() } + listOf("root", "1", "inbox")
            var result = Delivery.DELIVERED
            val entries = Files.newDirectoryStream(source).use { it.map { entry -> entry.fileName } }
            for (name in entries) {
                if (name.toString().startsWith(".pending-")) continue
                if (Thread.currentThread().isInterrupted) return@withLock maxOf(result, Delivery.UNAVAILABLE)
                val delivered = try {
                    publish(source, name, anchor, directories, name)
                } catch (_: FileAlreadyExistsException) {
                    val unique = source.fileSystem.getPath("${UUID.randomUUID()}-${name.toString().takeLast(160)}")
                    try {
                        publish(source, name, anchor, directories, unique)
                    } catch (_: FileAlreadyExistsException) {
                        Delivery.UNAVAILABLE
                    }
                }
                result = maxOf(result, delivered)
            }
            result
        } catch (_: Exception) {
            Delivery.UNAVAILABLE
        }
    }

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

    /** 只清理本应用生成的、直接位于私有 inbox 下的普通文件。 */
    private fun cleanupOrphanedPending(inbox: File) {
        val entries = try {
            Files.newDirectoryStream(inbox.toPath()).use { it.toList() }
        } catch (_: Exception) {
            return
        }
        entries.forEach { path ->
            val name = path.fileName?.toString() ?: return@forEach
            if (!PENDING_NAME.matches(name) || activePending.contains(path.toAbsolutePath().normalize())) return@forEach
            try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attributes.isRegularFile && !attributes.isSymbolicLink) {
                    Files.deleteIfExists(path)
                }
            } catch (_: Exception) {
                // 清理失败不影响后续分享；原件留给下一次尝试。
            }
        }
    }

    companion object {
        private val lock = Any()
        private val activePending = mutableSetOf<Path>()
        private val PENDING_NAME = Regex("^\\.pending-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        fun <T> withLock(action: () -> T): T = synchronized(lock, action)
    }
}
