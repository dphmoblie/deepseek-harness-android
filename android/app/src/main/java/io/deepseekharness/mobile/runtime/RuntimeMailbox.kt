package io.deepseekharness.mobile.runtime

import android.os.Build
import android.os.Environment
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * 投递区可用性。
 *
 * 档位与 `docs/真机缺陷与改进清单.md` §5.1 的 T0–T3 口径一致：
 * - [AVAILABLE] 对应 **T2**（已授予「所有文件访问」，且目录真实可读写）；
 * - [NEEDS_PERMISSION] / [UNSUPPORTED] / [UNWRITABLE] 一律落回 **T0**（控制台上传），
 *   界面必须如实说明投递区不可用——**不得静默降级到别的目录**（例如退回到应用私有目录冒充投递区）。
 */
internal enum class MailboxAvailability(val wireValue: String, val level: String) {
    /** T2：可用。 */
    AVAILABLE("available", "T2"),

    /** 系统支持「所有文件访问」但尚未授予：给出授权入口，不代替用户决定。 */
    NEEDS_PERMISSION("needsPermission", "T0"),

    /** 系统不存在这一档（Android 11 以下）：隐藏授权入口，说明只能用控制台上传。 */
    UNSUPPORTED("unsupported", "T0"),

    /** 已授权但目录仍不可写（ROM 限制或目录被占用）：如实报告，不猜测原因。 */
    UNWRITABLE("unwritable", "T0"),
}

/** inbox 中可作为导入候选的 tar（界面用来显示「将导入哪一个」）。 */
internal data class MailboxTarCandidate(val name: String, val bytes: Long)

/** 投递区状态快照。只含**用户可见路径**与计数，不含任何私有路径或文件内容。 */
internal data class MailboxState(
    val availability: MailboxAvailability,
    val supported: Boolean,
    val granted: Boolean,
    val inboxPath: String,
    val outboxPath: String,
    val guestInboxPath: String,
    val guestOutboxPath: String,
    /** inbox 内的常规文件数（未授予权限时恒为 0，不代表目录是空的）。 */
    val inboxFileCount: Int,
    /** inbox 内的 tar 候选，最多列 5 个。 */
    val inboxTars: List<MailboxTarCandidate>,
    /** 导出产物的固定文件名，供界面直接展示。 */
    val exportTarName: String,
    val exportManifestName: String,
    val importDirectory: String,
) {
    val available: Boolean get() = availability == MailboxAvailability.AVAILABLE
}

/** 一次导入的结果。 */
internal data class MailboxImportOutcome(
    /** 归档条目数（目录 + 文件 + 链接）。 */
    val entryCount: Int,
    val fileCount: Int,
    val directoryCount: Int,
    val symlinkCount: Int,
    val hardlinkCount: Int,
    /** 内容总字节数（不含目录与链接）。 */
    val bytes: Long,
    val tarName: String,
    val tarBytes: Long,
    /** 导入时是否用 manifest 逐条校验过。 */
    val verified: Boolean,
    /** 用到的 manifest 文件名；没有 manifest 时为 null。 */
    val manifestName: String?,
    /** inbox 里被忽略的散文件数（它们不会被写进工作区）。 */
    val ignoredFiles: Int,
    /** 落点：相对工作区的固定子目录。 */
    val target: String,
)

/** 一次导出的结果。 */
internal data class MailboxExportOutcome(
    val entryCount: Int,
    val bytes: Long,
    val tarName: String,
    val tarBytes: Long,
    val tarSha256: String,
    val manifestName: String,
    /** 导出起点相对工作区的路径；null 表示整个工作区。 */
    val subdirectory: String?,
    /** 因目标越出导出起点而被跳过的符号链接数。 */
    val skippedLinks: Int,
    /** 因类型无法表达（FIFO / 设备节点 / 套接字）而被跳过的条目数。 */
    val skippedSpecial: Int,
)

internal data class MailboxManifestEntry(
    val path: String,
    val type: String,
    val bytes: Long,
    val sha256: String?,
    val target: String?,
)

/**
 * 导出 manifest 的内容。
 *
 * 形状固定且**只含相对路径**：`entries[].path` 是归档内相对路径，符号链接的 `target`
 * 也只写落点内的相对目标。绝对路径、宿主路径、私有区路径一律不写入产物。
 */
internal data class MailboxManifest(
    val format: String,
    val subdirectory: String?,
    val entryCount: Int,
    val totalBytes: Long,
    val tarName: String,
    val tarBytes: Long,
    val tarSha256: String,
    val skippedLinks: Int,
    val skippedSpecial: Int,
    val entries: List<MailboxManifestEntry>,
)

internal object MailboxManifestCodec {
    private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

    fun render(plan: MailboxExportPlan, tarBytes: Long, tarSha256: String, tarName: String): String {
        val entries = JSONArray()
        plan.entries.forEach { entry ->
            val json = JSONObject()
                .put("path", entry.name)
                .put("type", wireType(entry.kind))
                .put("bytes", entry.bytes)
            if (entry.sha256 != null) json.put("sha256", entry.sha256)
            if (entry.linkTarget != null) json.put("target", entry.linkTarget)
            entries.put(json)
        }
        return JSONObject()
            .put("format", RuntimeMailboxLayout.MANIFEST_FORMAT)
            .put("subdirectory", plan.subdirectory ?: JSONObject.NULL)
            .put("entryCount", plan.entries.size)
            .put("totalBytes", plan.totalBytes)
            .put("tarName", tarName)
            .put("tarBytes", tarBytes)
            .put("tarSha256", tarSha256)
            .put(
                "skipped",
                JSONObject().put("linksOutside", plan.skippedLinks).put("special", plan.skippedSpecial),
            )
            .put("entries", entries)
            .toString(2)
    }

    /** 解析并严格校验 manifest；任何字段缺失、类型不符或越界都按结构非法拒绝。 */
    fun parse(text: String): MailboxManifest {
        val json = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 不是合法的 JSON", error)
        }
        val format = json.optString("format", "")
        if (format != RuntimeMailboxLayout.MANIFEST_FORMAT) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 格式标识不受支持")
        }
        val subdirectory = if (json.isNull("subdirectory")) {
            null
        } else {
            // manifest 是一份整体产物：其中任何路径不合法都按「结构非法」报，
            // 不把内部的路径码泄漏成两种失败口径。
            try {
                RuntimeMailboxPolicy.normalizeSubdirectory(json.optString("subdirectory", ""))
            } catch (error: RuntimeFailure) {
                throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 导出起点无效", error)
            }
        }
        val entryCount = json.requiredCount("entryCount")
        val totalBytes = json.requiredCount("totalBytes")
        val tarBytes = json.requiredCount("tarBytes")
        val tarName = json.optString("tarName", "")
        if (tarName.isEmpty() || tarName.contains('/') || tarName.length > 255) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 归档名无效")
        }
        val tarSha256 = json.optString("tarSha256", "").lowercase()
        if (!SHA256_PATTERN.matches(tarSha256)) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 归档摘要无效")
        }
        if (entryCount > RuntimeMailboxLimits.MAX_ENTRIES) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 条目数超过限额")
        }
        val skipped = json.optJSONObject("skipped")
        val array = json.optJSONArray("entries")
            ?: throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 缺少条目表")
        if (array.length().toLong() != entryCount) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 条目数与声明不一致")
        }
        val entries = ArrayList<MailboxManifestEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 条目格式无效")
            val path = try {
                RuntimeMailboxPolicy.normalizeManifestPath(item.optString("path", ""))
            } catch (error: RuntimeFailure) {
                throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 条目路径无效", error)
            }
            val type = item.optString("type", "")
            if (type !in WIRE_TYPES) {
                throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 条目类型无效")
            }
            val bytes = item.requiredCount("bytes")
            val sha256 = item.optString("sha256", "").ifEmpty { null }
            if (type == WIRE_FILE && (sha256 == null || !SHA256_PATTERN.matches(sha256))) {
                throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 文件条目缺少有效摘要")
            }
            val target = item.optString("target", "").ifEmpty { null }
            if (type == WIRE_SYMLINK) {
                if (target == null || target.startsWith('/')) {
                    throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 符号链接目标无效")
                }
            }
            entries += MailboxManifestEntry(path, type, bytes, sha256, target)
        }
        return MailboxManifest(
            format = format,
            subdirectory = subdirectory,
            entryCount = entryCount.toInt(),
            totalBytes = totalBytes,
            tarName = tarName,
            tarBytes = tarBytes,
            tarSha256 = tarSha256,
            skippedLinks = skipped?.optInt("linksOutside", 0) ?: 0,
            skippedSpecial = skipped?.optInt("special", 0) ?: 0,
            entries = entries,
        )
    }

    private fun JSONObject.requiredCount(key: String): Long {
        val value = opt(key)
        if (value !is Number) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 缺少数值字段")
        }
        val asLong = value.toLong()
        if (asLong < 0L || value.toDouble() != asLong.toDouble()) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 数值字段越界")
        }
        return asLong
    }

    const val WIRE_FILE = "file"
    const val WIRE_DIRECTORY = "directory"
    const val WIRE_SYMLINK = "symlink"

    private val WIRE_TYPES = setOf(WIRE_FILE, WIRE_DIRECTORY, WIRE_SYMLINK)

    private fun wireType(kind: MailboxEntryKind): String = when (kind) {
        MailboxEntryKind.FILE -> WIRE_FILE
        MailboxEntryKind.DIRECTORY -> WIRE_DIRECTORY
        MailboxEntryKind.SYMLINK -> WIRE_SYMLINK
        MailboxEntryKind.HARDLINK -> WIRE_FILE
    }
}

/**
 * 外置投递区门面：路径解析、状态查询、宿主→访客绑定，以及两个一键搬运动作。
 *
 * 三条硬边界：
 * 1. **只做批量搬运**：投递区不是工作区，不承担保留语义（`RuntimePreservePolicy` 的白名单里
 *    没有它，也不需要——投递区目录本来就在应用私有存储之外，运行时更新不会碰它）。
 * 2. **不用 `write` 工具写投递区**：实测该路径对 dsh 的 `write` 工具是 EACCES（P0-1 同一根因）。
 *    这是「投递区不是工作区」的判据，本实现刻意不去「修好」它；搬运由 App 侧完成。
 * 3. **无权限不静默降级**：没有「所有文件访问」时如实返回不可用并给出授权入口，
 *    绝不退回到别的目录冒充投递区。
 */
internal class RuntimeMailbox(private val store: RuntimeStore) {
    private data class HostDirectories(val inbox: File, val outbox: File) {
        fun byName(name: String): File =
            if (name == RuntimeMailboxLayout.INBOX_DIRECTORY) inbox else outbox
    }

    /** 工作区：与 `RuntimeWorkspaceFiles` 同一路径口径（`currentRoot/root/1`）。 */
    private val workspace: File get() = File(store.currentRoot, "root/1")

    fun state(): MailboxState {
        val supported = accessSupported()
        val granted = supported && allFilesGranted()
        val directories = if (granted) hostDirectories(create = true, probeWrite = true) else null
        val availability = when {
            !supported -> MailboxAvailability.UNSUPPORTED
            !granted -> MailboxAvailability.NEEDS_PERMISSION
            directories == null -> MailboxAvailability.UNWRITABLE
            else -> MailboxAvailability.AVAILABLE
        }
        return MailboxState(
            availability = availability,
            supported = supported,
            granted = granted,
            inboxPath = displayPath(RuntimeMailboxLayout.INBOX_DIRECTORY),
            outboxPath = displayPath(RuntimeMailboxLayout.OUTBOX_DIRECTORY),
            guestInboxPath = RuntimeMailboxLayout.GUEST_INBOX,
            guestOutboxPath = RuntimeMailboxLayout.GUEST_OUTBOX,
            inboxFileCount = directories?.let { countInboxFiles(it.inbox) } ?: 0,
            inboxTars = directories?.let { listTars(it.inbox) } ?: emptyList(),
            exportTarName = RuntimeMailboxLayout.EXPORT_TAR_NAME,
            exportManifestName = RuntimeMailboxLayout.EXPORT_MANIFEST_NAME,
            importDirectory = RuntimeMailboxLayout.IMPORT_DIRECTORY,
        )
    }

    /**
     * 追加到 PRoot 启动档的投递区绑定。
     *
     * **不可访问时返回空列表**：绑定一个不存在的宿主路径会让 PRoot 直接起不来，
     * 因此这里只在宿主目录确实可访问时才追加，并同时确保访客侧挂载点存在。
     * 这条分支（可用 → 追加两个绑定；不可用 → 一个也不追加）是设计的一部分，
     * 不是降级。
     */
    fun bindMounts(): List<ProotBindMount> {
        val directories = hostDirectories(create = true, probeWrite = false) ?: return emptyList()
        if (!ensureGuestMountPoint(RuntimeMailboxLayout.GUEST_INBOX)) return emptyList()
        if (!ensureGuestMountPoint(RuntimeMailboxLayout.GUEST_OUTBOX)) return emptyList()
        return RuntimeMailboxLayout.MOUNT_POINTS.map { (target, name) ->
            ProotBindMount(directories.byName(name).absolutePath, target)
        }
    }

    /** 便宜的可用性判断（供启动档缓存键使用，不创建任何文件）。 */
    fun mountableNow(): Boolean = hostDirectories(create = false, probeWrite = false) != null

    fun importInbox(): MailboxImportOutcome {
        val directories = requireDirectories()
        val target = requireWorkspace()
        val input = MailboxInputSelection.select(directories.inbox)
        val destination = File(target, RuntimeMailboxLayout.IMPORT_DIRECTORY)
        // 规划阶段不写任何文件：越界、绝对符号链接、超限、重复条目都在这里被拒绝，
        // 因此被拒绝的导入在访客私有区里不产生任何改动。
        val plan = MailboxArchiveReader().plan(input.tar, destination.toPath())
        var verified = false
        var manifestName: String? = null
        input.manifest?.let { manifestFile ->
            val manifest = MailboxManifestCodec.parse(readTextBounded(manifestFile))
            MailboxManifestVerifier.verify(manifest, plan)
            verified = true
            manifestName = manifestFile.name
        }
        input.sha256File?.let { shaFile ->
            val expected = readTextBounded(shaFile).trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
            if (!MailboxDigest.isValid(expected) || expected != plan.tarSha256) {
                throw RuntimeFailure(MailboxCodes.MANIFEST_MISMATCH, "投递归档摘要与 .sha256 文件不一致")
            }
        }

        val staging = File(store.runtimeParent, STAGING_PREFIX + UUID.randomUUID())
        try {
            MailboxArchiveExtractor().extract(input.tar, plan, staging.toPath())
            MailboxTree.replaceDirectory(staging.toPath(), destination.toPath(), store.runtimeParent.toPath())
        } finally {
            MailboxTree.deleteTreeQuietly(staging.toPath())
        }
        recordOutcome(
            DiagnosticEvent.MAILBOX,
            mapOf(
                "reason" to "import",
                "result" to "ok",
                "files" to plan.entries.size.toString(),
                "bytes" to plan.totalBytes.toString(),
                "count" to plan.symlinkCount.toString(),
            ),
        )
        return MailboxImportOutcome(
            entryCount = plan.entries.size,
            fileCount = plan.fileCount,
            directoryCount = plan.directoryCount,
            symlinkCount = plan.symlinkCount,
            hardlinkCount = plan.hardlinkCount,
            bytes = plan.totalBytes,
            tarName = input.tar.name,
            tarBytes = plan.tarBytes,
            verified = verified,
            manifestName = manifestName,
            ignoredFiles = input.ignoredFiles,
            target = RuntimeMailboxLayout.IMPORT_DIRECTORY,
        )
    }

    fun exportWorkspace(subdirectory: String?): MailboxExportOutcome {
        val directories = requireDirectories()
        val target = requireWorkspace()
        val normalized = RuntimeMailboxPolicy.normalizeSubdirectory(subdirectory)
        val plan = MailboxWorkspaceScanner().scan(target.toPath(), normalized)
        // 导出起点：整个工作区，或用户指定的子目录。tar 条目名以子目录名为前缀，
        // 因此写盘时必须以导出起点（而不是工作区）为基准解析源文件。
        val exportRoot = normalized?.let { File(target, it) } ?: target

        val suffix = UUID.randomUUID().toString()
        val tarTemp = File(directories.outbox, ".dsh-export-$suffix.tar.tmp")
        val manifestTemp = File(directories.outbox, ".dsh-export-$suffix.manifest.tmp")
        val shaTemp = File(directories.outbox, ".dsh-export-$suffix.sha256.tmp")
        try {
            MailboxArchiveWriter().write(exportRoot.toPath(), plan, tarTemp)
            val (tarBytes, tarSha256) = MailboxDigest.bytesAndSha256(tarTemp)
            val manifestText = MailboxManifestCodec.render(
                plan,
                tarBytes,
                tarSha256,
                RuntimeMailboxLayout.EXPORT_TAR_NAME,
            )
            writeTextAtomic(manifestTemp, manifestText)
            writeTextAtomic(shaTemp, "$tarSha256  ${RuntimeMailboxLayout.EXPORT_TAR_NAME}\n")
            // 导出自证：把刚写出的归档重新读一遍，用同一份 manifest 逐条比对。
            // 工作区在「扫描」与「写盘」之间被改动时（Agent 正在写文件），这里会失败并
            // 丢掉临时产物，而不是留下一个 manifest 与 tar 不符的产物让导入端去拒。
            MailboxManifestVerifier.verify(
                MailboxManifestCodec.parse(manifestText),
                MailboxArchiveReader().plan(tarTemp, store.runtimeParent.toPath()),
            )
            // 三个产物全部写完之后才改名：中途失败只会留下临时文件，不会留下半个产物。
            MailboxTree.move(tarTemp.toPath(), File(directories.outbox, RuntimeMailboxLayout.EXPORT_TAR_NAME).toPath())
            MailboxTree.move(
                manifestTemp.toPath(),
                File(directories.outbox, RuntimeMailboxLayout.EXPORT_MANIFEST_NAME).toPath(),
            )
            MailboxTree.move(
                shaTemp.toPath(),
                File(directories.outbox, RuntimeMailboxLayout.EXPORT_SHA256_NAME).toPath(),
            )
            recordOutcome(
                DiagnosticEvent.MAILBOX,
                mapOf(
                    "reason" to "export",
                    "result" to "ok",
                    "files" to plan.entries.size.toString(),
                    "bytes" to plan.totalBytes.toString(),
                    "count" to plan.skippedLinks.toString(),
                ),
            )
            return MailboxExportOutcome(
                entryCount = plan.entries.size,
                bytes = plan.totalBytes,
                tarName = RuntimeMailboxLayout.EXPORT_TAR_NAME,
                tarBytes = tarBytes,
                tarSha256 = tarSha256,
                manifestName = RuntimeMailboxLayout.EXPORT_MANIFEST_NAME,
                subdirectory = normalized,
                skippedLinks = plan.skippedLinks,
                skippedSpecial = plan.skippedSpecial,
            )
        } finally {
            MailboxTree.deleteQuietly(tarTemp.toPath())
            MailboxTree.deleteQuietly(manifestTemp.toPath())
            MailboxTree.deleteQuietly(shaTemp.toPath())
        }
    }

    private fun requireDirectories(): HostDirectories {
        if (!accessSupported()) {
            throw RuntimeFailure(
                MailboxCodes.UNSUPPORTED,
                "当前系统不支持「所有文件访问」，投递区不可用；请在终端或控制台上传",
            )
        }
        if (!allFilesGranted()) {
            throw RuntimeFailure(
                MailboxCodes.UNAVAILABLE,
                "尚未授予「所有文件访问」，投递区不可用；请先在系统设置中授予后重试",
            )
        }
        return hostDirectories(create = true, probeWrite = true)
            ?: throw RuntimeFailure(MailboxCodes.UNAVAILABLE, "投递区目录不可读写")
    }

    private fun requireWorkspace(): File {
        if (!MailboxTree.isRealDirectory(workspace.toPath())) {
            throw RuntimeFailure(MailboxCodes.WORKSPACE_UNAVAILABLE, "工作区不可用（运行时尚未安装）")
        }
        return workspace
    }

    /**
     * 解析宿主侧目录。
     *
     * `Documents` 先 `canonicalFile` 解析成**真实路径**再往下拼（`/sdcard` 是符号链接，不能作为
     * PRoot 绑定源）；`DSH` / `inbox` / `outbox` 三级要求是真实目录（NoFollow），
     * 任何一级是符号链接都直接判不可用——否则绑定源可能被替换成别处。
     */
    private fun hostDirectories(create: Boolean, probeWrite: Boolean): HostDirectories? {
        if (!accessSupported() || !allFilesGranted()) return null
        val documents = File(RuntimeMailboxLayout.PUBLIC_DOCUMENTS)
        if (!documents.isDirectory) return null
        val realDocuments = try {
            documents.canonicalFile
        } catch (_: IOException) {
            return null
        }
        val appDirectory = File(realDocuments, RuntimeMailboxLayout.APP_DIRECTORY)
        if (!ensureDirectory(appDirectory, create)) return null
        val inbox = File(appDirectory, RuntimeMailboxLayout.INBOX_DIRECTORY)
        val outbox = File(appDirectory, RuntimeMailboxLayout.OUTBOX_DIRECTORY)
        if (!ensureDirectory(inbox, create) || !ensureDirectory(outbox, create)) return null
        if (!inbox.canRead() || !inbox.canWrite() || !inbox.canExecute()) return null
        if (!outbox.canRead() || !outbox.canWrite() || !outbox.canExecute()) return null
        if (probeWrite && (!writeProbe(inbox) || !writeProbe(outbox))) return null
        return HostDirectories(inbox, outbox)
    }

    private fun ensureDirectory(directory: File, create: Boolean): Boolean {
        val path = directory.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return MailboxTree.isRealDirectory(path)
        if (!create) return false
        return try {
            directory.mkdirs() || MailboxTree.isRealDirectory(path)
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * 真实写探测：FUSE 上 `canWrite()` 不一定可信，因此真的写一个隐藏临时文件再删掉。
     * 探测失败不抛错，由调用方降级为「不可用」——投递区不可用是预期状态，不是故障。
     */
    private fun writeProbe(directory: File): Boolean {
        val probe = File(directory, "$PROBE_PREFIX${UUID.randomUUID()}")
        return try {
            Files.newOutputStream(
                probe.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { it.write(PROBE_BYTES) }
            true
        } catch (_: Throwable) {
            false
        } finally {
            probe.delete()
        }
    }

    /** 访客侧挂载点：落在 rootfs 里的 `/mnt/inbox`、`/mnt/outbox`，不存在时创建（逐级 NoFollow）。 */
    private fun ensureGuestMountPoint(guestPath: String): Boolean {
        if (!MailboxTree.isRealDirectory(store.currentRoot.toPath())) return false
        val directory = File(store.currentRoot, guestPath.removePrefix("/"))
        return try {
            MailboxTree.createDirectoriesNoFollow(store.currentRoot.toPath(), directory.toPath())
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun listRegularFiles(directory: File): List<File> {
        val entries = directory.listFiles() ?: return emptyList()
        return entries.filter { isRegularFileNoFollow(it) && !it.name.startsWith(PROBE_PREFIX) }
    }

    private fun isRegularFileNoFollow(file: File): Boolean = MailboxTree.isRealRegularFile(file.toPath())

    private fun countInboxFiles(inbox: File): Int = listRegularFiles(inbox).size

    private fun listTars(inbox: File): List<MailboxTarCandidate> = listRegularFiles(inbox)
        .filter { it.name.lowercase().endsWith(".tar") }
        .sortedBy { it.name }
        .take(MAX_LISTED_TARS)
        .map { MailboxTarCandidate(it.name, MailboxTree.sizeOrNull(it.toPath()) ?: 0L) }

    private fun readTextBounded(file: File): String {
        if ((MailboxTree.sizeOrNull(file.toPath()) ?: 0L) > RuntimeMailboxLimits.MAX_MANIFEST_BYTES) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "manifest 文件超过大小上限")
        }
        return try {
            Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                .use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (error: IOException) {
            throw RuntimeFailure(MailboxCodes.MANIFEST_INVALID, "无法读取 manifest 文件", error)
        }
    }

    private fun writeTextAtomic(file: File, text: String) {
        try {
            Files.newOutputStream(
                file.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (error: IOException) {
            throw RuntimeFailure(MailboxCodes.FILESYSTEM_ERROR, "无法写出投递产物", error)
        }
    }

    private fun accessSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun allFilesGranted(): Boolean = try {
        Environment.isExternalStorageManager()
    } catch (_: Throwable) {
        false
    }

    /** 用户可见路径：始终展示文档里的固定路径，不把 canonical 后的宿主路径暴露到界面。 */
    private fun displayPath(name: String): String =
        "${RuntimeMailboxLayout.PUBLIC_DOCUMENTS}/${RuntimeMailboxLayout.APP_DIRECTORY}/$name"

    private fun recordOutcome(event: DiagnosticEvent, fields: Map<String, String>) {
        try {
            store.diagnostics.record(DiagnosticLevel.INFO, event, fields)
        } catch (_: Throwable) {
            // 诊断日志本身不可用时不能影响搬运结果。
        }
    }

    private companion object {
        /** 导入暂存目录前缀：与正式落点同处 `runtimeParent` 之下，保证改名是同一文件系统内的原子操作。 */
        const val STAGING_PREFIX = "mailbox-staging-"
        const val MAX_LISTED_TARS = 5

        /** 写探测文件的固定前缀：既不会被当成导入输入，也不会被算作「被忽略的散文件」。 */
        const val PROBE_PREFIX = MailboxInputSelection.PROBE_PREFIX

        /** 写探测的内容：一个字节即可证明「能创建 + 能写 + 能删」。 */
        val PROBE_BYTES = byteArrayOf('1'.code.toByte())
    }
}
