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
            throw RuntimeFailure(failureCode, "运行时关键路径缺失或不是符号链接：/${link.path}")
        }
        val actualTarget = try {
            parse(Files.readSymbolicLink(path))
        } catch (error: IOException) {
            throw RuntimeFailure(failureCode, "无法读取运行时链接目标：/${link.path}", error)
        }
        if (actualTarget != parse(link.target)) {
            throw RuntimeFailure(failureCode, "运行时关键链接目标异常：/${link.path}")
        }
    }

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
