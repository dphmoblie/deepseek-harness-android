package io.deepseekharness.mobile.runtime

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * 目录浏览的**受控错误码族**。
 *
 * 规则（分量长度、最大深度、`..` 拒绝……）只有一份，但错误码面按链路分开：
 * `MAILBOX_*` 与 `STORAGE_DIR_*` 是两条已经发布的公开契约，借复用的机会把其中一条的码
 * 换成另一条，等于在重构里悄悄改掉错误码契约。因此这里只注入码，不注入规则。
 */
internal data class DirectoryBrowseCodes(
    /** 路径非法（越出根目录等；路径本身的规则见 `RuntimeMailboxPolicy`）。 */
    val pathInvalid: String,
    /** 目标目录不存在或不再是真实目录（符号链接、普通文件都算）。 */
    val directoryNotFound: String,
    /** 文件系统层面的失败：列不出来、建不出来。 */
    val directoryFailed: String,
    /** 新建时目标已存在（`CREATE_NEW` 语义，绝不覆盖）。 */
    val folderExists: String,
)

/**
 * 目录浏览的文案与可见性过滤口径，按链路各带一份。
 *
 * 文案逐字保留既有实现里的字符串：用户已经见过的提示不因为一次重构而改变。
 *
 * [nameFilter] 是两条链路**唯一**有意的差异：
 * - 投递区过滤掉 App 自己写进去的探测文件（`.dsh-mailbox-probe-*`）与导出中间产物（`.dsh-export-*`）；
 * - 用户共享目录里的条目是用户自己的文件，App 不得以「看起来像我的临时文件」为由隐藏它们。
 * 两边都还要再通过安全名判定（[RuntimeDirectoryBrowser] 里的 `isSafeEntryName`）。
 */
internal class DirectoryBrowseTexts(
    val codes: DirectoryBrowseCodes,
    /** 根目录本身不是真实目录。 */
    val rootUnavailable: String,
    /** 相对路径中的某一级不存在或不再是真实目录。 */
    val missingDirectory: String,
    /** 目录列举失败。 */
    val unreadable: String,
    /** 目标路径越出根目录（双重保险）。 */
    val escaped: String,
    /** 新建目录时路径为空。 */
    val emptyPath: String,
    /** 新建目录时目标已存在。 */
    val folderExists: String,
    /** 新建目录失败。 */
    val createFailed: String,
    val nameFilter: (String) -> Boolean,
)

/** 一次目录列举的结果；[path] 为空表示当前位于传入的根目录。 */
internal data class DirectorySnapshot(
    val path: String?,
    val entries: List<MailboxDirectoryEntry>,
    val truncated: Boolean,
)

/**
 * 目录列举与新建目录的**共享实现**：投递区（`RuntimeMailbox`）与用户目录白名单
 * （`RuntimeStorageDirs`）走同一段代码，只有错误码族、文案与可见性过滤按链路注入。
 *
 * 复用是硬要求而不是顺手：截断上限（[RuntimeMailboxLimits.MAX_DIRECTORY_ENTRIES]）、排序口径
 * （目录在前、同类按名字）、逐级 NoFollow 的符号链接判定、以及 `CREATE_NEW` 的「建目录绝不覆盖」
 * 语义，在两个接口上必须完全一致——前端用的是同一个文件浏览组件，任何一处口径漂移都会变成
 * 「两个页面行为不一样」。
 *
 * 路径解析本身（分量长度、最大深度、保留前缀、`..` 拒绝）不在这里：它仍然只有
 * `RuntimeMailboxPolicy.normalizeSubdirectory` 一份，调用方先规范化再把结果交给本对象。
 */
internal object RuntimeDirectoryBrowser {
    /** 投递区：错误码与文案保持 `MailboxCodes` 原文，可见性过滤沿用投递区自己的口径。 */
    val MAILBOX = DirectoryBrowseTexts(
        codes = DirectoryBrowseCodes(
            pathInvalid = MailboxCodes.PATH_INVALID,
            directoryNotFound = MailboxCodes.DIRECTORY_NOT_FOUND,
            directoryFailed = MailboxCodes.DIRECTORY_FAILED,
            folderExists = MailboxCodes.FOLDER_EXISTS,
        ),
        rootUnavailable = "投递区根目录不可用",
        missingDirectory = "投递区目录不存在",
        unreadable = "无法读取投递区目录",
        escaped = "投递区目录越出根目录",
        emptyPath = "投递区新目录路径不能为空",
        folderExists = "投递区目录已存在",
        createFailed = "无法创建投递区目录",
        nameFilter = { name ->
            name.isNotEmpty() &&
                !name.startsWith(MailboxInputSelection.PROBE_PREFIX) &&
                !name.startsWith(EXPORT_STAGING_PREFIX)
        },
    )

    /**
     * 用户目录白名单：错误码一律取既有的 `STORAGE_DIR_*`，**不携带路径**；
     * 不过滤 `.dsh-*` 前缀——那些目录里的文件是用户自己的。
     */
    val SHARED_STORAGE = DirectoryBrowseTexts(
        codes = DirectoryBrowseCodes(
            pathInvalid = StorageDirCodes.PATH_INVALID,
            directoryNotFound = StorageDirCodes.NOT_A_DIRECTORY,
            directoryFailed = StorageDirCodes.DIRECTORY_FAILED,
            folderExists = StorageDirCodes.FOLDER_EXISTS,
        ),
        rootUnavailable = "存储目录当前不可用",
        missingDirectory = "存储目录不存在或已不是真实目录",
        unreadable = "无法读取存储目录",
        escaped = "存储目录路径越出所选目录",
        emptyPath = "存储目录新目录路径不能为空",
        folderExists = "存储目录下已存在同名条目",
        createFailed = "无法创建存储目录",
        nameFilter = { true },
    )

    /**
     * 列出 [root] 下的 [path]（已由调用方规范化的相对路径，null/空表示根目录本身）。
     *
     * 目录解析逐级使用 NoFollow 检查，符号链接不会把浏览范围带出去；只返回常规文件和真实目录，
     * 最多 [RuntimeMailboxLimits.MAX_DIRECTORY_ENTRIES] 项并以 truncated 标记截断。
     */
    fun snapshot(root: File, path: String?, texts: DirectoryBrowseTexts): DirectorySnapshot {
        val target = requireDirectory(root, path, texts)
        val allEntries = try {
            Files.newDirectoryStream(target.toPath()).use { stream ->
                stream.asSequence()
                    .filter { entry ->
                        val name = entry.fileName?.toString() ?: return@filter false
                        texts.nameFilter(name) && isSafeEntryName(name)
                    }
                    .mapNotNull { entry ->
                        val name = entry.fileName?.toString() ?: return@mapNotNull null
                        val attributes = MailboxTree.readAttributesNoFollow(entry) ?: return@mapNotNull null
                        when {
                            attributes.isDirectory && !attributes.isSymbolicLink ->
                                MailboxDirectoryEntry(name, "directory", 0L)
                            attributes.isRegularFile && !attributes.isSymbolicLink ->
                                MailboxDirectoryEntry(name, "file", MailboxTree.sizeOrNull(entry) ?: 0L)
                            else -> null
                        }
                    }
                    // 只多读一项即可判断是否截断，避免公共目录中有大量条目时把整棵列表读入内存。
                    .take(RuntimeMailboxLimits.MAX_DIRECTORY_ENTRIES + 1)
                    .toList()
            }
        } catch (error: IOException) {
            throw RuntimeFailure(texts.codes.directoryFailed, texts.unreadable, error)
        }
        return DirectorySnapshot(
            path = path,
            entries = allEntries
                .sortedWith(compareBy<MailboxDirectoryEntry>({ if (it.kind == "directory") 0 else 1 }, { it.name }))
                .take(RuntimeMailboxLimits.MAX_DIRECTORY_ENTRIES),
            truncated = allEntries.size > RuntimeMailboxLimits.MAX_DIRECTORY_ENTRIES,
        )
    }

    /**
     * 在 [root] 下创建相对目录 [path]（已由调用方规范化）。
     *
     * 父目录可按需创建，但最终目录必须通过 `CREATE_NEW` 建立，已有文件或目录一律拒绝，
     * 避免把用户误操作变成静默覆盖。不返回列表：调用方建完再走一次自己的列举入口，
     * 这样「建完即回新列表」的快照与「直接浏览」的快照必然同源。
     */
    fun createFolder(root: File, path: String, texts: DirectoryBrowseTexts) {
        val target = root.toPath().resolve(path).normalize()
        if (!target.startsWith(root.toPath().toAbsolutePath().normalize())) {
            throw RuntimeFailure(texts.codes.pathInvalid, texts.escaped)
        }
        try {
            // 逐级 NoFollow 创建父目录，拒绝任何中间符号链接。
            target.parent?.let { parent -> MailboxTree.createDirectoriesNoFollow(root.toPath(), parent) }
            Files.createDirectory(target)
        } catch (_: FileAlreadyExistsException) {
            throw RuntimeFailure(texts.codes.folderExists, texts.folderExists)
        } catch (error: IOException) {
            throw RuntimeFailure(texts.codes.directoryFailed, texts.createFailed, error)
        }
    }

    /**
     * 逐级确认相对路径中的每一段都是真实目录，拒绝中间符号链接，返回宿主的绝对路径。
     *
     * 对外暴露是因为投递区导出还要用它定位 outbox 下的目标目录（`exportWorkspace`）：
     * 那条链路只关心「解析出来的目录」，不需要列举，但**校验口径必须与浏览完全一致**，
     * 所以两条路共用一个实现，而不是各自逐级 `isRealDirectory` 一遍。
     */
    fun requireDirectory(root: File, path: String?, texts: DirectoryBrowseTexts): File {
        var cursor = root.toPath().toAbsolutePath().normalize()
        if (!MailboxTree.isRealDirectory(cursor)) {
            throw RuntimeFailure(texts.codes.directoryNotFound, texts.rootUnavailable)
        }
        path.orEmpty().split('/').filter { it.isNotEmpty() }.forEach { component ->
            cursor = cursor.resolve(component)
            if (!MailboxTree.isRealDirectory(cursor)) {
                throw RuntimeFailure(texts.codes.directoryNotFound, texts.missingDirectory)
            }
        }
        return cursor.toFile()
    }

    /**
     * 条目名是否安全：复用投递区那一套（空、超长、`..`/`.` 分段、反斜杠、NUL、CR/LF）。
     * 这里只当布尔判定用，异常不外传——列不出来一个条目不是错误，跳过它即可。
     */
    private fun isSafeEntryName(name: String): Boolean = try {
        RuntimeMailboxPolicy.normalizeEntryName(name)
        true
    } catch (_: RuntimeFailure) {
        false
    }

    /** 导出中间产物的固定前缀：与 `RuntimeMailbox` 的可见性口径保持一致。 */
    private const val EXPORT_STAGING_PREFIX = ".dsh-export-"
}
