package io.deepseekharness.mobile.runtime

import java.nio.file.Path

/**
 * 外置投递区（mailbox）的固定路径与绑定落点。
 *
 * 设计前提见 `docs/真机缺陷与改进清单.md` §四：工作区始终留在应用内私有存储，
 * **可访问性完全由投递区承担**——用户从手机侧访问文件走投递区，不走工作区。
 *
 * ```
 * 手机侧（用户可见）                          访客内（固定挂载点）
 * /storage/emulated/0/Documents/DSH/inbox  →  /mnt/inbox     用户放 tar 给 Agent
 * /storage/emulated/0/Documents/DSH/outbox ←  /mnt/outbox    App 放 tar 与 manifest 给用户
 * 工作区 <currentRoot>/root/1（私有 f2fs，保持不动）
 * ```
 *
 * **宿主路径刻意写成 `/storage/emulated/0` 而不是 `/sdcard`**：`/sdcard` 是符号链接，
 * 而 PRoot 的绑定源必须是解析后的真实路径（否则绑定建立在一个由系统重定向的路径上，
 * 且 `RuntimeCommand.validateBindMount` 的字符校验之外还要人能看懂绑定到了哪里）。
 */
object RuntimeMailboxLayout {
    /** 公共存储的真实路径前缀（不是 `/sdcard` 这个符号链接）。 */
    const val PUBLIC_STORAGE = "/storage/emulated/0"

    /** 用户可见目录的基准：`Documents`。真机实测 `/sdcard` 根不可写，但本目录可写。 */
    const val PUBLIC_DOCUMENTS = "$PUBLIC_STORAGE/Documents"

    /** App 在 `Documents` 下自建的子目录名；`inbox` / `outbox` 由 App 创建。 */
    const val APP_DIRECTORY = "DSH"
    const val INBOX_DIRECTORY = "inbox"
    const val OUTBOX_DIRECTORY = "outbox"

    /** 访客侧固定挂载点。绑定只在宿主目录确实可访问时才追加（见 RuntimeLaunchResolver）。 */
    const val GUEST_INBOX = "/mnt/inbox"
    const val GUEST_OUTBOX = "/mnt/outbox"

    /** 导入落点：工作区内的固定子目录，导入的相对路径全部落在这里。 */
    const val IMPORT_DIRECTORY = "mailbox-import"

    /**
     * 导出产物的**固定文件名**。
     *
     * 固定而不是按时间戳命名：导出要求幂等/可重入，重复执行只覆盖同一组文件，
     * 不会在 outbox 里堆出第二份、第三份产物，也不会让用户分不清哪一份是最新的。
     */
    const val EXPORT_TAR_NAME = "dsh-workspace.tar"
    const val EXPORT_MANIFEST_NAME = "dsh-workspace.manifest.json"
    const val EXPORT_SHA256_NAME = "dsh-workspace.tar.sha256"

    /** manifest 的格式标识；导入端只接受本格式，其它一律按「结构非法」拒绝。 */
    const val MANIFEST_FORMAT = "dsh-mailbox-export/1"

    /** 访客挂载点与宿主子目录名的对应关系（顺序固定，便于报告与测试）。 */
    val MOUNT_POINTS: List<Pair<String, String>> = listOf(
        GUEST_INBOX to INBOX_DIRECTORY,
        GUEST_OUTBOX to OUTBOX_DIRECTORY,
    )
}

/**
 * 投递区限额。
 *
 * 取值依据：投递区是**手机侧的批量搬运通道**，不是工作区的替代品，因此限额按
 * 「一次能搬完、出错能重来」来定，而不是按 rootfs 那种整盘解压的尺度（见 [RuntimeLimits]）。
 * 超限一律**明确报错**，不截断、不部分导入。
 */
object RuntimeMailboxLimits {
    /** 单次导入/导出的条目数上限（含目录与链接条目）。 */
    const val MAX_ENTRIES = 20_000

    /** 单次导入/导出的内容总字节上限：512 MiB。 */
    const val MAX_TOTAL_BYTES = 536_870_912L

    /** 单个常规文件的字节上限：256 MiB。 */
    const val MAX_FILE_BYTES = 268_435_456L

    /** 归档文件自身的字节上限：512 MiB。 */
    const val MAX_TAR_BYTES = 536_870_912L

    /** 条目相对路径的字符上限（与 `RuntimeWorkspaceFiles` 的口径一致）。 */
    const val MAX_PATH_CHARS = 240

    /** 条目相对路径的深度上限（`a/b/c` 为 3 层）。 */
    const val MAX_PATH_DEPTH = 32

    /** 单个路径分段的字符上限（与 tar 头部与常见文件系统一致）。 */
    const val MAX_COMPONENT_CHARS = 255

    /** manifest 文本的字节上限：8 MiB（覆盖 2 万条目绰绰有余）。 */
    const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
}

/**
 * 投递区受控错误码。
 *
 * 新增这些码的原因：投递区失败的原因必须能被界面与日志区分开——「没有存储权限」
 * 与「用户给的 tar 越界」是两件完全不同的事，前者要引导授权，后者要如实拒绝并说明原因。
 * 码只用于分类，**不携带路径**（路径不进日志、不进审计）。
 */
internal object MailboxCodes {
    /** 投递区不可用：未授予「所有文件访问」，或目录存在但探测不可读写。 */
    const val UNAVAILABLE = "MAILBOX_UNAVAILABLE"

    /** 系统不存在「所有文件访问」这一档（Android 11 以下），投递区不可用。 */
    const val UNSUPPORTED = "MAILBOX_UNSUPPORTED"

    /** inbox 的输入选择不成立：没有 tar、有多个 tar 而无法确定、或选中的不是常规文件。 */
    const val INPUT_INVALID = "MAILBOX_INPUT_INVALID"

    /** 选中的输入不是 tar 内容（也不接受散文件：散文件会丢符号链接与执行位）。 */
    const val INPUT_NOT_TAR = "MAILBOX_INPUT_NOT_TAR"

    /** 条目路径非法：绝对路径、`..`、`.` 分段、反斜杠、NUL、超长或超深。 */
    const val PATH_INVALID = "MAILBOX_PATH_INVALID"

    /** 符号链接目标为绝对路径——一律拒绝。 */
    const val LINK_ABSOLUTE = "MAILBOX_LINK_ABSOLUTE"

    /** 符号链接目标规范化后越出导入落点。 */
    const val LINK_ESCAPE = "MAILBOX_LINK_ESCAPE"

    /** 符号链接 / 硬链接条目自身格式非法，或硬链接目标不是落点内的常规文件。 */
    const val LINK_INVALID = "MAILBOX_LINK_INVALID"

    /** 设备拒绝创建符号链接（部分 ROM 的 SELinux 策略）。不静默降级成复制。 */
    const val LINK_UNSUPPORTED = "MAILBOX_LINK_UNSUPPORTED"

    /** 条目数超过 [RuntimeMailboxLimits.MAX_ENTRIES]。 */
    const val ENTRY_LIMIT = "MAILBOX_ENTRY_LIMIT"

    /** 总字节或单文件字节超过限额。 */
    const val SIZE_LIMIT = "MAILBOX_SIZE_LIMIT"

    /** FIFO、设备节点、稀疏文件等不支持的条目类型。 */
    const val ENTRY_TYPE_REJECTED = "MAILBOX_ENTRY_TYPE_REJECTED"

    /** 归档内出现重复条目。 */
    const val DUPLICATE_ENTRY = "MAILBOX_DUPLICATE_ENTRY"

    /** 归档内容被截断（条目声明的字节数没读满）。 */
    const val ARCHIVE_TRUNCATED = "MAILBOX_ARCHIVE_TRUNCATED"

    /** manifest 结构非法（缺字段、类型不对、条数超限）。 */
    const val MANIFEST_INVALID = "MAILBOX_MANIFEST_INVALID"

    /** manifest 与 tar 内容不一致（tar 摘要、条目数、单条字节数或 sha256 不符）。 */
    const val MANIFEST_MISMATCH = "MAILBOX_MANIFEST_MISMATCH"

    /** 解包过程中读回的字节数与规划不一致（归档在两次读取之间被改动）。 */
    const val CONTENT_MISMATCH = "MAILBOX_CONTENT_MISMATCH"

    /** 工作区不可用（运行时未安装）或导出子目录不成立。 */
    const val WORKSPACE_UNAVAILABLE = "MAILBOX_WORKSPACE_UNAVAILABLE"

    /** 导出内容超过限额。 */
    const val EXPORT_LIMIT = "MAILBOX_EXPORT_LIMIT"

    /** 其它文件系统失败（创建目录、写文件、原子改名）。 */
    const val FILESYSTEM_ERROR = "MAILBOX_FILESYSTEM_ERROR"
}

/** 归档内允许落地的条目类型。FIFO / 设备节点 / 稀疏文件不在其中。 */
internal enum class MailboxEntryKind {
    DIRECTORY,
    FILE,

    /** 符号链接：只允许**相对**目标，且规范化后必须仍在导入落点内。 */
    SYMLINK,

    /** 硬链接：按内容复制落地（本机 `link(2)` 被拒，见 P0-1），不做 `link` 调用。 */
    HARDLINK,
}

/** 规划阶段得出的单个条目。规划阶段**不写任何文件**。 */
internal data class MailboxEntry(
    /** 归档内相对路径（`/` 分隔，无前导 `./`，目录不带尾部 `/`）。 */
    val name: String,
    val kind: MailboxEntryKind,
    /** 常规文件的字节数；其余类型恒为 0。 */
    val bytes: Long,
    /** 权限位（只取低 9 位）。 */
    val mode: Int,
    /** 常规文件内容的 sha256（小写十六进制）；其余类型为 null。 */
    val sha256: String?,
    /** 符号链接目标（已规范化为落点内的**相对**路径）。 */
    val linkTarget: String? = null,
    /** 硬链接源（归档内相对路径）。 */
    val hardlinkTarget: String? = null,
)

/**
 * 导入规划：完整校验 tar 之后的结论。
 *
 * 规划与解包分成两步是**为了对得上 §4.6 的验收口径**——
 * 含 `../` 条目或绝对符号链接的 tar 必须在「访客私有区不产生任何改动」的前提下被拒绝，
 * 因此所有判定都发生在写第一个字节之前。
 */
internal data class MailboxPlan(
    val entries: List<MailboxEntry>,
    val totalBytes: Long,
    val tarBytes: Long,
    val tarSha256: String,
) {
    val fileCount: Int get() = entries.count { it.kind == MailboxEntryKind.FILE }
    val directoryCount: Int get() = entries.count { it.kind == MailboxEntryKind.DIRECTORY }
    val symlinkCount: Int get() = entries.count { it.kind == MailboxEntryKind.SYMLINK }
    val hardlinkCount: Int get() = entries.count { it.kind == MailboxEntryKind.HARDLINK }
}

/** 导出规划：遍历工作区之后的结论；`entries` 已按名字排序，保证 tar 字节可复现。 */
internal data class MailboxExportPlan(
    /** 导出起点相对工作区的路径；null 表示整个工作区。 */
    val subdirectory: String?,
    /** tar 条目名前缀：导出子目录时为 `"<子目录>/"`，导出整个工作区时为空串。 */
    val prefix: String,
    val entries: List<MailboxEntry>,
    val totalBytes: Long,
    /** 因目标越出导出起点而被跳过的符号链接数（不在产物里留下私有路径）。 */
    val skippedLinks: Int,
    /** 因类型无法表达（FIFO / 设备节点 / 套接字）而被跳过的条目数。 */
    val skippedSpecial: Int,
) {
    val fileCount: Int get() = entries.count { it.kind == MailboxEntryKind.FILE }
    val directoryCount: Int get() = entries.count { it.kind == MailboxEntryKind.DIRECTORY }
    val symlinkCount: Int get() = entries.count { it.kind == MailboxEntryKind.SYMLINK }
}

/**
 * 投递区的**纯策略**：路径与链接目标的校验规则。
 *
 * 与 `ArchivePathPolicy`（rootfs 解压）刻意分开，因为两者的取舍不同：
 * - rootfs 解压必须保住 Ubuntu 镜像里的**绝对符号链接**，做法是改写成暂存根内的相对链接；
 * - 投递区面对的是用户/Agent 投来的 tar，绝对符号链接没有任何正当用途，只可能把
 *   私有区（如 `/root/.dsh`）钓出导入落点，因此**一律拒绝**（§4.6 的 R2 按拒绝判定）。
 *
 * 本对象不接触文件系统，可在 JVM 单元测试里穷举。
 */
internal object RuntimeMailboxPolicy {
    private const val LEADING_DOT_SLASH = "./"

    /**
     * 校验并规范化归档条目名。
     *
     * 接受：`a/b.txt`、`./a/b.txt`（GNU tar 常见前缀）、`dir/`（目录条目）。
     * 拒绝：绝对路径、`..`、`.` 分段、空分段（`a//b`）、反斜杠、NUL、CR/LF、超长、超深。
     * 返回：去掉前导 `./` 与尾部 `/` 的规范形式。
     */
    fun normalizeEntryName(rawName: String): String {
        var name = rawName
        while (name.startsWith(LEADING_DOT_SLASH)) name = name.removePrefix(LEADING_DOT_SLASH)
        name = name.removeSuffix("/")
        validateRelative(name, MailboxCodes.PATH_INVALID, "归档条目路径无效")
        return name
    }

    /** 把规范化的条目名解析成落点内的绝对路径；越界一律拒绝（双重保险）。 */
    fun resolveEntry(root: Path, name: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(name).normalize()
        if (!resolved.startsWith(normalizedRoot) || resolved == normalizedRoot) {
            throw RuntimeFailure(MailboxCodes.PATH_INVALID, "归档条目越出导入落点")
        }
        return resolved
    }

    /**
     * 校验符号链接目标，返回**规范化后**的相对目标。
     *
     * 三条规则（顺序即判定顺序）：
     * 1. 绝对目标（以 `/` 开头）一律拒绝 [MailboxCodes.LINK_ABSOLUTE]；
     * 2. 目标自身格式非法（空、反斜杠、NUL、CR/LF、超长）拒绝 [MailboxCodes.LINK_INVALID]；
     * 3. 按 POSIX 语义（相对链接所在目录）规范化后越出落点，拒绝 [MailboxCodes.LINK_ESCAPE]。
     */
    fun normalizeLinkTarget(root: Path, name: String, rawTarget: String): String {
        if (rawTarget.startsWith('/')) {
            throw RuntimeFailure(MailboxCodes.LINK_ABSOLUTE, "归档符号链接使用绝对目标，已拒绝")
        }
        if (
            rawTarget.isEmpty() || rawTarget.length > RuntimeMailboxLimits.MAX_PATH_CHARS ||
            rawTarget.contains('\\') || rawTarget.contains('\u0000') ||
            rawTarget.any { it == '\r' || it == '\n' }
        ) {
            throw RuntimeFailure(MailboxCodes.LINK_INVALID, "归档符号链接目标格式无效")
        }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val link = resolveEntry(normalizedRoot, name)
        val linkParent = link.parent ?: throw RuntimeFailure(MailboxCodes.LINK_INVALID, "归档符号链接路径无效")
        val resolved = linkParent.resolve(rawTarget).normalize()
        if (!resolved.startsWith(normalizedRoot)) {
            throw RuntimeFailure(MailboxCodes.LINK_ESCAPE, "归档符号链接目标越出导入落点")
        }
        return linkParent.relativize(resolved).toString().replace('\\', '/').ifEmpty { "." }
    }

    /**
     * 校验硬链接源（tar 的 `linkName` 是**归档根相对**路径，允许带前导 `/`）。
     * 返回规范化后的归档内相对路径。
     */
    fun normalizeHardlinkSource(rawTarget: String): String {
        val candidate = rawTarget.removePrefix("/")
        if (candidate.isEmpty()) {
            throw RuntimeFailure(MailboxCodes.LINK_INVALID, "归档硬链接源路径无效")
        }
        return normalizeEntryName(candidate)
    }

    /**
     * 校验导出子目录参数：省略或空白等价于「整个工作区」；其余必须是通过同一套规则
     * 的相对路径（不含 `.` / `..` 分段）。
     */
    fun normalizeSubdirectory(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return normalizeEntryName(trimmed)
    }

    /** manifest 条目路径必须与归档条目同规则：绝不允许把绝对路径或宿主路径写进产物。 */
    fun normalizeManifestPath(raw: String): String = normalizeEntryName(raw)

    private fun validateRelative(name: String, code: String, message: String) {
        if (
            name.isEmpty() || name.length > RuntimeMailboxLimits.MAX_PATH_CHARS ||
            name.startsWith('/') || name.startsWith('\\') || name.contains('\\') ||
            name.contains('\u0000') || name.any { it == '\r' || it == '\n' }
        ) {
            throw RuntimeFailure(code, message)
        }
        val components = name.split('/')
        if (components.size > RuntimeMailboxLimits.MAX_PATH_DEPTH) {
            throw RuntimeFailure(code, "$message：路径层级超过上限")
        }
        if (components.any {
                it.isEmpty() || it == "." || it == ".." || it.length > RuntimeMailboxLimits.MAX_COMPONENT_CHARS
            }
        ) {
            throw RuntimeFailure(code, "$message：路径分段无效")
        }
    }
}
