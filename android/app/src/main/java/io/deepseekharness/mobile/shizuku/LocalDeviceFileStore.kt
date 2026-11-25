package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64

/**
 * 投递区（`/storage/emulated/0/Documents/DSH/{inbox,outbox}`）的宿主本地直连执行器。
 *
 * 背景：原先 `mobile_device_file_*` 全部经 `DeviceBridgeServer -> ShizukuRuntime.create()`
 * 在 Shizuku Shell 里跑 `toybox`，一旦 Shizuku 未启动或 Binder 假死，连投递区读写都会挂起
 * 并报 `SHIZUKU_BINDER_TIMEOUT`。当应用自身已具备共享存储访问权限时，直接由宿主在
 * [DeviceFilePolicy] 同一套安全边界内完成读写（全链路 `NOFOLLOW_LINKS` 防符号链接逃逸、
 * 128 KiB 上限、同目录临时文件原子写入、默认拒绝覆盖），完全不依赖 Shizuku；仅在宿主无权限
 * 访问该目录时返回 `null`，交由调用方回退到既有 Shizuku 通道。
 */
internal object LocalDeviceFileStore {
    private val FILE_COMMANDS = setOf(
        DeviceCommand.FILE_LIST,
        DeviceCommand.FILE_READ,
        DeviceCommand.FILE_DOWNLOAD,
        DeviceCommand.FILE_WRITE,
        DeviceCommand.FILE_UPLOAD,
        DeviceCommand.FILE_MKDIR,
    )

    fun isFileCommand(command: DeviceCommand): Boolean = command in FILE_COMMANDS

    /**
     * 尝试直接由宿主文件系统执行投递区命令。
     *
     * 返回 `null` 仅表示宿主当前无权访问目标存储（例如尚未授予「所有文件访问」），
     * 调用方应回退到 Shizuku 通道；其余受控业务错误（非法参数、越界、文件不存在、
     * 超过大小上限、已存在）直接返回或抛出对应受控结果。
     */
    fun tryExecute(
        command: DeviceCommand,
        param: String,
        rootResolver: (DeviceFilePolicy.Root) -> Path = { Paths.get(it.absolutePath) },
        allowUnrestricted: Boolean = false,
    ): DeviceCommandResult? {
        if (!isFileCommand(command)) return null
        val requireContent = command == DeviceCommand.FILE_WRITE || command == DeviceCommand.FILE_UPLOAD
        val request = DeviceFilePolicy.parse(param, requireContent = requireContent, allowUnrestricted = allowUnrestricted)
        val rootPath = rootResolver(request.root)
        val maxFileBytes = if (allowUnrestricted) DeviceFilePolicy.UNRESTRICTED_MAX_FILE_BYTES else DeviceFilePolicy.MAX_FILE_BYTES
        val unrestrictedAbsolute = allowUnrestricted && request.root == DeviceFilePolicy.Root.DEVICE && request.path.startsWith("/")

        return try {
            when (command) {
                DeviceCommand.FILE_LIST -> executeList(rootPath, request, unrestrictedAbsolute)
                DeviceCommand.FILE_READ, DeviceCommand.FILE_DOWNLOAD -> executeRead(rootPath, request, maxFileBytes, unrestrictedAbsolute)
                DeviceCommand.FILE_WRITE, DeviceCommand.FILE_UPLOAD -> executeWrite(rootPath, request, maxFileBytes, unrestrictedAbsolute)
                DeviceCommand.FILE_MKDIR -> executeMkdir(rootPath, request, unrestrictedAbsolute)
                else -> null
            }
        } catch (_: AccessDeniedException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (error: RuntimeFailure) {
            if (error.code == "DEVICE_FILE_INVALID") throw error
            failureResult(error.code, error.message ?: "投递区文件操作失败")
        } catch (_: IOException) {
            failureResult("DEVICE_FILE_FAILED", "投递区文件操作失败，请确认存储可用后重试。")
        }
    }

    private fun readAttributesOrNull(path: Path, vararg options: LinkOption): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, *options)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun executeList(rootPath: Path, request: DeviceFilePolicy.Request, unrestrictedAbsolute: Boolean): DeviceCommandResult {
        val target = if (unrestrictedAbsolute) {
            val direct = Paths.get(request.path).normalize()
            val attrs = readAttributesOrNull(direct)
                ?: return failureResult("DEVICE_FILE_NOT_FOUND", "目标目录不存在。")
            if (!attrs.isDirectory) {
                return failureResult("DEVICE_FILE_NOT_FOUND", "目标目录不存在。")
            }
            direct
        } else {
            val realRoot = resolveVerifiedRoot(rootPath, createIfMissing = false)
                ?: return failureResult("DEVICE_FILE_NOT_FOUND", "投递区目录不存在。")
            val resolved = resolveSafeTarget(realRoot, request.path, requireExistingParents = true)
                ?: return failureResult("DEVICE_FILE_ESCAPE", "投递区路径解析越出固定目录，或目录包含不受信任的符号链接；本次调用已拒绝。")
            if (Files.isSymbolicLink(resolved) || !Files.isDirectory(resolved)) {
                return failureResult("DEVICE_FILE_NOT_FOUND", "目标目录不存在。")
            }
            resolved
        }
        val names = mutableListOf<String>()
        Files.newDirectoryStream(target).use { stream ->
            for (entry in stream) {
                val fileName = entry.fileName?.toString() ?: continue
                val suffix = if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) "/" else ""
                names.add(fileName + suffix)
            }
        }
        names.sort()
        val truncated = names.size > DeviceFilePolicy.MAX_LIST_ENTRIES
        val limited = if (truncated) names.subList(0, DeviceFilePolicy.MAX_LIST_ENTRIES) else names
        val text = if (limited.isEmpty()) "" else limited.joinToString("\n")
        return DeviceCommandResult(ok = true, exitCode = 0, text = text, truncated = truncated, errorCode = null)
    }

    private fun executeRead(
        rootPath: Path,
        request: DeviceFilePolicy.Request,
        maxFileBytes: Int,
        unrestrictedAbsolute: Boolean,
    ): DeviceCommandResult {
        if (request.path.isEmpty() || request.path == "/") {
            throw RuntimeFailure("DEVICE_FILE_INVALID", "读取目标不能为空或根目录")
        }
        val target = if (unrestrictedAbsolute) {
            val direct = Paths.get(request.path).normalize()
            val attrs = readAttributesOrNull(direct)
                ?: return failureResult("DEVICE_FILE_NOT_FOUND", "目标文件不存在。")
            if (!attrs.isRegularFile) {
                return failureResult("DEVICE_FILE_NOT_FOUND", "目标文件不存在。")
            }
            direct
        } else {
            val realRoot = resolveVerifiedRoot(rootPath, createIfMissing = false)
                ?: return failureResult("DEVICE_FILE_NOT_FOUND", "投递区文件不存在。")
            val resolved = resolveSafeTarget(realRoot, request.path, requireExistingParents = true)
                ?: return failureResult("DEVICE_FILE_ESCAPE", "投递区路径解析越出固定目录，或目录包含不受信任的符号链接；本次调用已拒绝。")
            if (Files.isSymbolicLink(resolved) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                return failureResult("DEVICE_FILE_NOT_FOUND", "投递区文件不存在。")
            }
            val realTarget = resolved.toRealPath()
            if (!isInsideRoot(realRoot, realTarget)) {
                return failureResult("DEVICE_FILE_ESCAPE", "投递区路径解析越出固定目录，或目录包含不受信任的符号链接；本次调用已拒绝。")
            }
            resolved
        }
        val size = Files.size(target)
        val limitLabel = if (maxFileBytes >= 1024 * 1024) "${maxFileBytes / (1024 * 1024)} MiB" else "${maxFileBytes / 1024} KiB"
        if (size > maxFileBytes.toLong()) {
            return failureResult("DEVICE_FILE_TOO_LARGE", "文件超过单次传输 $limitLabel 上限。")
        }
        val bytes = Files.readAllBytes(target)
        if (bytes.size > maxFileBytes) {
            return failureResult("DEVICE_FILE_TOO_LARGE", "文件超过单次传输 $limitLabel 上限。")
        }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return DeviceCommandResult(ok = true, exitCode = 0, text = encoded, truncated = false, errorCode = null)
    }

    private fun executeWrite(
        rootPath: Path,
        request: DeviceFilePolicy.Request,
        maxFileBytes: Int,
        unrestrictedAbsolute: Boolean,
    ): DeviceCommandResult {
        if (request.path.isEmpty() || request.path == "/") {
            throw RuntimeFailure("DEVICE_FILE_INVALID", "写入目标不能为空或根目录")
        }
        val encoded = request.contentBase64 ?: throw RuntimeFailure("DEVICE_FILE_INVALID", "文件内容缺失")
        val bytes = DeviceFilePolicy.validateBase64(encoded, maxBytes = maxFileBytes)
        val target = if (unrestrictedAbsolute) {
            val direct = Paths.get(request.path).normalize()
            direct.parent?.let { parent ->
                if (readAttributesOrNull(parent) == null) Files.createDirectories(parent)
            }
            direct
        } else {
            val realRoot = resolveVerifiedRoot(rootPath, createIfMissing = true)
                ?: return failureResult("DEVICE_FILE_FAILED", "无法准备投递区目录。")
            resolveSafeTarget(realRoot, request.path, requireExistingParents = true)
                ?: return failureResult("DEVICE_FILE_ESCAPE", "投递区路径解析越出固定目录，或目录包含不受信任的符号链接；本次调用已拒绝。")
        }
        if (!unrestrictedAbsolute && Files.isSymbolicLink(target)) {
            return failureResult("DEVICE_FILE_ESCAPE", "投递区路径解析越出固定目录，或目录包含不受信任的符号链接；本次调用已拒绝。")
        }
        val existing = readAttributesOrNull(target, LinkOption.NOFOLLOW_LINKS)
        if (existing != null) {
            if (!request.overwrite || existing.isDirectory) {
                return failureResult("DEVICE_FILE_EXISTS", "目标文件已存在且未允许覆盖。")
            }
        }
        val parent = target.parent
            ?: return failureResult("DEVICE_FILE_ESCAPE", "目标路径缺少父目录。")
        val pending = Files.createTempFile(parent, ".dsh-upload-", ".tmp")
        try {
            Files.write(pending, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(pending) }
        }
        return DeviceCommandResult(ok = true, exitCode = 0, text = "", truncated = false, errorCode = null)
    }

    private fun executeMkdir(
        rootPath: Path,
        request: DeviceFilePolicy.Request,
        unrestrictedAbsolute: Boolean,
    ): DeviceCommandResult {
        if (request.path.isEmpty() || request.path == "/") {
            throw RuntimeFailure("DEVICE_FILE_INVALID", "目录目标不能为空或根目录")
        }
        if (unrestrictedAbsolute) {
            val target = Paths.get(request.path).normalize()
            val existing = readAttributesOrNull(target)
            if (existing != null && !existing.isDirectory) {
                return failureResult("DEVICE_FILE_EXISTS", "目标位置已存在同名文件，无法创建目录。")
            }
            if (existing == null) Files.createDirectories(target)
            return DeviceCommandResult(ok = true, exitCode = 0, text = "", truncated = false, errorCode = null)
        }
        val realRoot = resolveVerifiedRoot(rootPath, createIfMissing = true)
            ?: return failureResult("DEVICE_FILE_FAILED", "无法准备投递区目录。")
        var cursor = realRoot
        for (component in request.path.split('/')) {
            cursor = cursor.resolve(component)
            if (Files.isSymbolicLink(cursor)) {
                return failureResult("DEVICE_FILE_ESCAPE", "投递区路径解析越出固定目录，或目录包含不受信任的符号链接；本次调用已拒绝。")
            }
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    return failureResult("DEVICE_FILE_EXISTS", "目标位置已存在同名文件，无法创建目录。")
                }
            } else {
                Files.createDirectory(cursor)
            }
            if (!isInsideRoot(realRoot, cursor.toRealPath())) {
                return failureResult("DEVICE_FILE_ESCAPE", "投递区路径解析越出固定目录，或目录包含不受信任的符号链接；本次调用已拒绝。")
            }
        }
        return DeviceCommandResult(ok = true, exitCode = 0, text = "", truncated = false, errorCode = null)
    }

    private fun resolveVerifiedRoot(rootPath: Path, createIfMissing: Boolean): Path? {
        val parent = rootPath.parent
        if (parent != null && Files.isSymbolicLink(parent)) {
            throw RuntimeFailure("DEVICE_FILE_ESCAPE", "投递区上级目录是符号链接，已拒绝访问")
        }
        if (Files.isSymbolicLink(rootPath)) {
            throw RuntimeFailure("DEVICE_FILE_ESCAPE", "投递区根目录是符号链接，已拒绝访问")
        }
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            if (!createIfMissing) return null
            Files.createDirectories(rootPath)
        }
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw RuntimeFailure("DEVICE_FILE_ESCAPE", "投递区根目录不是有效目录")
        }
        return rootPath.toRealPath()
    }

    /**
     * 逐级解析相对路径并拒绝任意中间符号链接。
     * 返回 `null` 表示路径逃逸或包含符号链接；缺少父目录时抛出 `DEVICE_FILE_NOT_FOUND` 或由调用方处理。
     */
    private fun resolveSafeTarget(realRoot: Path, relativePath: String, requireExistingParents: Boolean): Path? {
        if (relativePath.isEmpty()) return realRoot
        val parts = relativePath.split('/')
        var cursor = realRoot
        for (index in parts.indices) {
            val part = parts[index]
            val isLast = index == parts.lastIndex
            cursor = cursor.resolve(part)
            if (Files.isSymbolicLink(cursor)) return null
            if (!isLast) {
                if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    if (requireExistingParents) {
                        throw RuntimeFailure("DEVICE_FILE_NOT_FOUND", "投递区父目录不存在")
                    }
                    return null
                }
                if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) return null
                if (!isInsideRoot(realRoot, cursor.toRealPath())) return null
            }
        }
        val parent = cursor.parent ?: return null
        if (!isInsideRoot(realRoot, parent.toRealPath())) return null
        return cursor
    }

    private fun isInsideRoot(realRoot: Path, candidate: Path): Boolean {
        val normalizedRoot = realRoot.normalize()
        val normalizedCandidate = candidate.normalize()
        return normalizedCandidate == normalizedRoot || normalizedCandidate.startsWith(normalizedRoot)
    }

    private fun failureResult(code: String, message: String): DeviceCommandResult = DeviceCommandResult(
        ok = false,
        exitCode = 1,
        text = message,
        truncated = false,
        errorCode = code,
    )
}
