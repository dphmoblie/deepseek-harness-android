package io.deepseekharness.mobile.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.ArrayDeque

/**
 * 访客侧可执行文件的落地形态判定。
 *
 * 背景（真机缺陷）：荣耀等 ROM 的 SELinux 禁止应用创建符号链接与硬链接，rootfs 解压器会把这类条目
 * 降级成「复制目标内容」。降级复制后如果执行位没落上（`File.setExecutable` 在这些机器上会静默失败），
 * Ubuntu 的动态链接器副本就没有执行位，于是**任何**动态链接程序 execve 都返回 EACCES：
 * PRoot 报 `proot error: execve("/usr/bin/env"): Permission denied`，而 SELinux 仍然对程序本身
 * 记 `granted { execute }`（解释器的检查在 DAC 层就被拒了），日志上完全看不出问题在哪。
 *
 * 本对象只做纯计算与观测，不依赖 Android API，便于在 JVM 单测里覆盖判定表。
 */
object RuntimeGuestExecutables {
    /** 权限位里的属主执行位。 */
    const val OWNER_EXECUTE_BIT = 0x40

    /** 权限位掩码（rwxrwxrwx）。 */
    const val MODE_MASK = 0x1ff

    /** 任何一段执行位（属主/属组/其他）。 */
    const val EXECUTE_BITS = 0x49

    private const val EXECUTABLE_HEADER_BYTES = 64

    private val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    private const val SHEBANG_FIRST: Byte = '#'.code.toByte()
    private const val SHEBANG_SECOND: Byte = '!'.code.toByte()

    private val PERMISSION_BITS = listOf(
        0x100 to PosixFilePermission.OWNER_READ,
        0x80 to PosixFilePermission.OWNER_WRITE,
        0x40 to PosixFilePermission.OWNER_EXECUTE,
        0x20 to PosixFilePermission.GROUP_READ,
        0x10 to PosixFilePermission.GROUP_WRITE,
        0x8 to PosixFilePermission.GROUP_EXECUTE,
        0x4 to PosixFilePermission.OTHERS_READ,
        0x2 to PosixFilePermission.OTHERS_WRITE,
        0x1 to PosixFilePermission.OTHERS_EXECUTE,
    )

    /**
     * 启动巡检要看的访客关键路径（相对运行时根）。
     *
     * arm64 Ubuntu 的解释器链是 `/lib/ld-linux-aarch64.so.1` → `usr/lib/ld-linux-aarch64.so.1`
     * → `usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1`：链上任一段变成「没有执行位的复制品」，
     * 所有动态链接程序都起不来。`usr/bin/env` 是应用真正下发的第一个程序（PRoot 报错里出现的路径）。
     */
    val CRITICAL_PATHS = listOf(
        "lib/ld-linux-aarch64.so.1",
        "usr/lib/ld-linux-aarch64.so.1",
        "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "usr/bin/env",
        "usr/bin/bash",
        "usr/bin/sh",
        "bin/sh",
        "usr/local/bin/node",
        "opt/node/bin/node",
    )

    /** 只按文件头判断「这个文件看起来是要被执行的」：ELF 魔数或 `#!` 解释器声明。 */
    fun looksExecutable(header: ByteArray, length: Int): Boolean {
        if (length <= 0) return false
        val limit = minOf(length, header.size)
        if (limit >= ELF_MAGIC.size) {
            var matches = true
            for (index in ELF_MAGIC.indices) {
                if (header[index] != ELF_MAGIC[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return limit >= 2 && header[0] == SHEBANG_FIRST && header[1] == SHEBANG_SECOND
    }

    fun needsOwnerExecute(mode: Int): Boolean = (mode and OWNER_EXECUTE_BIT) == 0

    /**
     * 只补属主执行位，不动其它权限位；已经可执行时返回 null（调用方据此不做任何写入）。
     */
    fun repairedMode(mode: Int): Int? {
        if (!needsOwnerExecute(mode)) return null
        return (mode and MODE_MASK) or OWNER_EXECUTE_BIT
    }

    fun toMode(permissions: Set<PosixFilePermission>): Int {
        var mode = 0
        for ((bit, permission) in PERMISSION_BITS) {
            if (permission in permissions) mode = mode or bit
        }
        return mode
    }

    fun fromMode(mode: Int): Set<PosixFilePermission> {
        val permissions = HashSet<PosixFilePermission>()
        for ((bit, permission) in PERMISSION_BITS) {
            if ((mode and bit) != 0) permissions.add(permission)
        }
        return permissions
    }

    /** 供 logcat 单行观测某个访客关键路径的落地形态。 */
    fun describe(root: File, relativePath: String): String {
        val path = File(root, relativePath).toPath()
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
        if (attributes == null) return "path=" + relativePath + ",kind=missing"
        val kind = when {
            attributes.isSymbolicLink -> "symlink"
            attributes.isDirectory -> "directory"
            attributes.isRegularFile -> "file"
            else -> "other"
        }
        val target = if (attributes.isSymbolicLink) {
            ",target=" + (try {
                Files.readSymbolicLink(path).toString()
            } catch (_: Throwable) {
                "?"
            })
        } else {
            ""
        }
        val mode = if (attributes.isRegularFile) ",mode=0" + Integer.toOctalString(readMode(path)) else ""
        val execAccess = ",execAccess=" + (try {
            Files.isExecutable(path)
        } catch (_: Throwable) {
            false
        })
        return "path=" + relativePath + ",kind=" + kind + target + ",size=" + attributes.size() + mode + execAccess
    }

    internal fun readMode(path: Path): Int = try {
        toMode(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
    } catch (_: Throwable) {
        0
    }

    internal fun readMode(file: File): Int = readMode(file.toPath())

    internal fun looksExecutable(path: Path): Boolean = try {
        Files.newInputStream(path, StandardOpenOption.READ).use { input: InputStream ->
            val header = ByteArray(EXECUTABLE_HEADER_BYTES)
            val read = input.read(header)
            read > 0 && looksExecutable(header, read)
        }
    } catch (_: Throwable) {
        false
    }
}

/**
 * 一次性补齐运行时根内「看起来可执行、却缺属主执行位」的常规文件。
 *
 * 为什么需要它：0.2.4 之前的解压器在符号链接/硬链接降级时用 `File.setExecutable` 落执行位，
 * 该 API 在荣耀等 ROM 上会**静默失败**，于是已装好的运行时根里可能残留一批没有执行位的解释器副本；
 * 光修解压器救不了这些已经落地的目录，必须在启动前就地补位。
 *
 * 只加不减：仅当文件「缺属主执行位」且文件头是 ELF 或 `#!` 时补上属主执行位，其它内容一律不碰。
 * 整个扫描完成才写标记文件；被中断或触到上限时不写，下次启动继续。
 */
class RuntimeExecutableRepair(
    private val root: File,
    private val markerFile: File,
    private val log: (String) -> Unit = {},
    private val maxScanned: Int = MAX_SCANNED_FILES,
    private val deadlineMillis: Long = DEADLINE_MILLIS,
) {
    data class Outcome(
        val ran: Boolean,
        val scanned: Int,
        val repaired: Int,
        val failed: Int,
        val elapsedMillis: Long,
        val completed: Boolean,
    )

    private enum class RepairResult { UNCHANGED, REPAIRED, FAILED }

    /** 标记文件存在即视为「这个运行时根已经修过」；重装运行时后根目录重建，标记随之消失。 */
    fun isPending(): Boolean = !markerFile.exists()

    fun runIfNeeded(shouldStop: () -> Boolean = { false }): Outcome {
        if (!isPending()) return NOT_RUN
        return run(shouldStop)
    }

    fun run(shouldStop: () -> Boolean = { false }): Outcome {
        val startedAt = System.currentTimeMillis()
        var scanned = 0
        var repaired = 0
        var failed = 0
        var completed = true
        val pendingDirectories = ArrayDeque<File>()
        pendingDirectories.addLast(root)
        val expired = { System.currentTimeMillis() - startedAt > deadlineMillis }

        scan@ while (pendingDirectories.isNotEmpty()) {
            if (shouldStop() || expired()) {
                completed = false
                break@scan
            }
            val directory = pendingDirectories.removeLast()
            val children = try {
                directory.listFiles()
            } catch (_: Throwable) {
                null
            } ?: continue
            for (child in children) {
                if (shouldStop() || expired()) {
                    completed = false
                    break@scan
                }
                val path = child.toPath()
                val attributes = try {
                    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: Throwable) {
                    continue
                }
                if (attributes.isDirectory) {
                    pendingDirectories.addLast(child)
                    continue
                }
                if (!attributes.isRegularFile) continue
                scanned += 1
                if (scanned > maxScanned) {
                    completed = false
                    break@scan
                }
                when (repairIfNeeded(path)) {
                    RepairResult.REPAIRED -> repaired += 1
                    RepairResult.FAILED -> failed += 1
                    RepairResult.UNCHANGED -> Unit
                }
            }
        }

        if (completed) {
            try {
                markerFile.parentFile?.mkdirs()
                markerFile.writeText("repaired=" + repaired + "\nscanned=" + scanned + "\n")
            } catch (_: Throwable) {
                // 标记写不进去只影响下次启动会再扫一遍，不影响本次修复结果。
            }
        }
        return Outcome(
            ran = true,
            scanned = scanned,
            repaired = repaired,
            failed = failed,
            elapsedMillis = System.currentTimeMillis() - startedAt,
            completed = completed,
        )
    }

    private fun repairIfNeeded(path: Path): RepairResult {
        val mode = RuntimeGuestExecutables.readMode(path)
        val target = RuntimeGuestExecutables.repairedMode(mode) ?: return RepairResult.UNCHANGED
        if (!RuntimeGuestExecutables.looksExecutable(path)) return RepairResult.UNCHANGED
        return try {
            Files.setPosixFilePermissions(path, RuntimeGuestExecutables.fromMode(target))
            RepairResult.REPAIRED
        } catch (error: Throwable) {
            if (failedLogCount < MAX_LOGGED_FAILURES) {
                failedLogCount += 1
                log(
                    "exec repair failed: path=" + relative(path) + ",mode=0" + Integer.toOctalString(mode) +
                        ",detail=" + (error.message ?: error.javaClass.simpleName),
                )
            }
            RepairResult.FAILED
        }
    }

    private var failedLogCount = 0

    private fun relative(path: Path): String = try {
        root.toPath().toAbsolutePath().relativize(path.toAbsolutePath()).toString()
    } catch (_: Throwable) {
        path.fileName?.toString() ?: "?"
    }

    companion object {
        /** 单次扫描的文件数上限。 */
        const val MAX_SCANNED_FILES = 200_000

        /** 单次扫描的耗时上限（毫秒）。 */
        const val DEADLINE_MILLIS = 60_000L

        private const val MAX_LOGGED_FAILURES = 5

        private val NOT_RUN = Outcome(
            ran = false,
            scanned = 0,
            repaired = 0,
            failed = 0,
            elapsedMillis = 0,
            completed = true,
        )
    }
}
