package io.deepseekharness.mobile.runtime

import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * 把 APK 内的认证预载脚本刷新到**已安装**运行时的固定路径。
 *
 * 运行时可以独立于 APK 安装、切换和回退。只更新 APK 里的预载不会影响设备上已经解压的
 * rootfs；而旧预载把 dsh 令牌的发现完全押在 stdout 截获上，一旦插件日志先打印了带
 * `token=` 的地址，就会把外置浏览器送到 dsh 的 401 页。因此每次启动前都把 APK 里的
 * 预载原子替换到当前运行时，保证宿主侧修复不依赖用户先重装/升级运行环境。
 *
 * 只改这一个固定文件：不改 manifest、不动 rootfs 链接，也不触碰运行时摘要。
 * 目标必须是普通文件；临时文件与目标同目录，写入并 fsync 后再原子替换。
 */
internal object MobileAuthPreload {
    const val ASSET_PATH = "support/mobile-auth-preload.cjs"
    const val GUEST_PATH = "/usr/local/lib/dsh-mobile-auth.cjs"

    private const val MAX_BYTES = 256 * 1024
    private const val BUFFER_BYTES = 16 * 1024

    fun install(root: File, openAsset: () -> InputStream) {
        val relative = GUEST_PATH.removePrefix("/").split('/')
        var directory = root
        for (part in relative.dropLast(1)) {
            directory = File(directory, part)
            if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "运行时认证预载目录无效")
            }
        }

        val target = File(directory, relative.last())
        val targetPath = target.toPath()
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "运行时认证预载目标不是普通文件")
        }

        val pending = try {
            Files.createTempFile(directory.toPath(), ".dsh-mobile-auth.", ".tmp")
        } catch (error: Throwable) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "无法创建运行时认证预载临时文件", error)
        }

        try {
            Files.newOutputStream(pending, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                .use { output ->
                    openAsset().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) {
                                throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "运行时认证预载超过大小上限")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    output.flush()
                }
            FileChannel.open(pending, StandardOpenOption.WRITE).use { it.force(true) }
            // 0600 也足够同 UID 的访客读取；尽力恢复 0644，保持与 rootfs 原件一致。
            runCatching {
                Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-r--r--"))
            }
            try {
                Files.move(pending, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            if (error is RuntimeFailure) throw error
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "无法更新运行时认证预载", error)
        } finally {
            runCatching { Files.deleteIfExists(pending) }
        }
    }
}
