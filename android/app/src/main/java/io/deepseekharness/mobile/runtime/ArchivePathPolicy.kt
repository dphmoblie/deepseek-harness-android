package io.deepseekharness.mobile.runtime

import java.nio.file.Path

object ArchivePathPolicy {
    fun resolveEntry(root: Path, rawName: String): Path {
        val name = rawName.removeSuffix("/")
        validateRelativeName(name)
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(name).normalize()
        if (!resolved.startsWith(normalizedRoot) || resolved == normalizedRoot) {
            throw RuntimeFailure("ARCHIVE_PATH_INVALID", "归档条目越出运行时目录")
        }
        return resolved
    }

    fun normalizeSymlinkTarget(root: Path, linkPath: Path, rawTarget: String): String {
        validateLinkName(rawTarget, allowAbsolute = true)
        val normalizedRoot = root.toAbsolutePath().normalize()
        val absoluteTarget = rawTarget.startsWith('/')
        val resolved = if (absoluteTarget) {
            normalizedRoot.resolve(rawTarget.removePrefix("/")).normalize()
        } else {
            linkPath.parent.resolve(rawTarget).normalize()
        }
        if (!resolved.startsWith(normalizedRoot)) {
            throw RuntimeFailure("ARCHIVE_LINK_INVALID", "归档符号链接越出运行时目录")
        }
        return if (absoluteTarget) {
            linkPath.parent.relativize(resolved).toString().replace('\\', '/').ifEmpty { "." }
        } else {
            rawTarget
        }
    }

    fun validateSymlinkTarget(root: Path, linkPath: Path, rawTarget: String) {
        normalizeSymlinkTarget(root, linkPath, rawTarget)
    }

    fun resolveHardlinkTarget(root: Path, rawTarget: String): Path {
        validateLinkName(rawTarget, allowAbsolute = true)
        val normalizedRoot = root.toAbsolutePath().normalize()
        val archiveRelative = rawTarget.removePrefix("/")
        val resolved = normalizedRoot.resolve(archiveRelative).normalize()
        if (!resolved.startsWith(normalizedRoot) || resolved == normalizedRoot) {
            throw RuntimeFailure("ARCHIVE_LINK_INVALID", "归档硬链接越出运行时目录")
        }
        return resolved
    }

    private fun validateRelativeName(name: String) {
        if (
            name.isEmpty() || name.length > RuntimeLimits.MAX_ARCHIVE_PATH_CHARS ||
            name.startsWith('/') || name.startsWith('\\') || name.contains('\\') ||
            name.contains('\u0000') || name.any { it == '\r' || it == '\n' }
        ) {
            throw RuntimeFailure("ARCHIVE_PATH_INVALID", "归档路径格式无效")
        }
        val components = name.split('/')
        if (components.any { it.isEmpty() || it == ".." || it.length > RuntimeLimits.MAX_ARCHIVE_COMPONENT_CHARS }) {
            throw RuntimeFailure("ARCHIVE_PATH_INVALID", "归档路径分段无效")
        }
    }

    private fun validateLinkName(name: String, allowAbsolute: Boolean) {
        if (
            name.isEmpty() || name.length > RuntimeLimits.MAX_ARCHIVE_PATH_CHARS ||
            (!allowAbsolute && name.startsWith('/')) || name.startsWith('\\') || name.contains('\\') ||
            name.contains('\u0000') || name.any { it == '\r' || it == '\n' }
        ) {
            throw RuntimeFailure("ARCHIVE_LINK_INVALID", "归档链接格式无效")
        }
        val withoutRoot = name.removePrefix("/")
        if (withoutRoot.isEmpty() || withoutRoot.split('/').any { it.isEmpty() || it.length > RuntimeLimits.MAX_ARCHIVE_COMPONENT_CHARS }) {
            throw RuntimeFailure("ARCHIVE_LINK_INVALID", "归档链接分段无效")
        }
    }
}
