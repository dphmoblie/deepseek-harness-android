package io.deepseekharness.mobile.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 校验物化后的 rootfs 关键符号链接。
 * 某些打包/分发链路（如经过不支持符号链接的文件系统重新打包）
 * 会把符号链接条目降级成 0 字节普通文件，导致 PRoot 无法加载
 * /bin/bash 等入口。安装完成与每次启动前各校验一次，
 * 损坏即抛出明确错误码，避免含糊的 PROOT_GUEST_EXEC_FAILED。
 */
object RootfsIntegrity {
    private data class RequiredLink(val path: String, val target: String)

    private data class LinkTarget(val absolute: Boolean, val components: List<String>)

    private val REQUIRED_LINKS = listOf(
        RequiredLink("bin", "usr/bin"),
        RequiredLink("lib", "usr/lib"),
        RequiredLink("sbin", "usr/sbin"),
        RequiredLink("usr/bin/sh", "dash"),
        RequiredLink("etc/mtab", "../proc/self/mounts"),
        RequiredLink("etc/os-release", "../usr/lib/os-release"),
        RequiredLink("etc/localtime", "/usr/share/zoneinfo/Etc/UTC"),
        RequiredLink("usr/local/bin/node", "../../../opt/node/bin/node"),
    )

    fun verifyLinks(root: File, failureCode: String) {
        for (link in REQUIRED_LINKS) {
            verifySingleLink(root.toPath(), link, failureCode)
        }
    }

    private fun verifySingleLink(root: Path, link: RequiredLink, failureCode: String) {
        val path = resolve(root, link.path)
        val isLink = try {
            Files.isSymbolicLink(path)
        } catch (error: IOException) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法检查运行时完整性", error)
        }
        if (!isLink) {
            // 兼容 ROM 禁止应用创建符号链接时的降级产物：解压器会把链接复制成
            // 目录或非空文件。目录与非空文件视为可用；缺失或 0 字节空文件
            // 仍是打包/分发损坏特征（PRoot 无法加载入口），保持拒绝。
            val exists = try {
                Files.exists(path)
            } catch (error: IOException) {
                throw RuntimeFailure("FILESYSTEM_ERROR", "无法检查运行时完整性", error)
            }
            if (!exists) {
                throw RuntimeFailure(failureCode, "运行时关键路径缺失：/${link.path}")
            }
            if (Files.isDirectory(path)) return
            if (Files.isRegularFile(path)) {
                val size = try {
                    Files.size(path)
                } catch (error: IOException) {
                    throw RuntimeFailure("FILESYSTEM_ERROR", "无法检查运行时完整性", error)
                }
                if (size > 0) return
            }
            throw RuntimeFailure(failureCode, "运行时关键路径缺失或不是符号链接：/${link.path}")
        }
        val actualTarget = try {
            Files.readSymbolicLink(path)
        } catch (error: IOException) {
            throw RuntimeFailure(failureCode, "无法读取运行时链接目标：/${link.path}", error)
        }
        // 物化器会把 tar 内绝对目标（如 /usr/share/zoneinfo/Etc/UTC）改写为
        // 相对形式（../usr/share/zoneinfo/Etc/UTC），因此必须比较解析后的
        // 规范路径而非字面字符串，否则绝对/相对形式差异会误报。
        if (resolvedTarget(root, link.path, actualTarget) != resolvedTarget(root, link.path, parse(link.target))) {
            throw RuntimeFailure(failureCode, "运行时关键链接目标异常：/${link.path}")
        }
    }

    /** 解析链接目标为以 root 为基准的规范路径：绝对目标相对于 rootfs 根，相对目标相对于链接父目录。 */
    private fun resolvedTarget(root: Path, linkPath: String, target: LinkTarget): Path {
        val base = if (target.absolute) root else resolve(root, parentOf(linkPath))
        var resolved = base
        for (component in target.components) {
            resolved = resolved.resolve(component)
        }
        return resolved.normalize()
    }

    private fun resolvedTarget(root: Path, linkPath: String, target: Path): Path =
        resolvedTarget(root, linkPath, parse(target))

    private fun parentOf(relative: String): String =
        relative.split('/').dropLast(1).joinToString("/")

    private fun resolve(root: Path, relative: String): Path {
        var path = root
        for (component in relative.split('/')) {
            path = path.resolve(component)
        }
        return path
    }

    private fun parse(target: String): LinkTarget {
        val absolute = target.startsWith("/")
        val components = target.split('/').filter { it.isNotEmpty() }
        return LinkTarget(absolute, components)
    }

    private fun parse(target: Path): LinkTarget = parse(target.toString().replace('\\', '/'))
}
