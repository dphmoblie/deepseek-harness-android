package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * 快照失败码：统一 `RUNTIME_SNAPSHOT_` 前缀，桥层原样回传给界面。
 *
 * 约定与 `STORAGE_DIR_*`、`ACCESSIBILITY_PASSWORD_*` 一致：码是受控枚举，
 * 文案只描述「发生了什么」，不含任何路径或文件内容。
 */
internal object RuntimeSessionSnapshotCodes {
    /** 会话目录不存在或没有任何可复制的文件：**不产出空快照**。 */
    const val EMPTY = "RUNTIME_SNAPSHOT_EMPTY"

    /** 标识不合法（缺参、超长、含路径分隔符或 `..`）。 */
    const val ID_INVALID = "RUNTIME_SNAPSHOT_ID_INVALID"

    /** 指定的快照不存在（已被淘汰或删除）。 */
    const val NOT_FOUND = "RUNTIME_SNAPSHOT_NOT_FOUND"

    /** 单份快照本身就超过容量上限，淘汰任何旧快照都放不下。 */
    const val TOO_LARGE = "RUNTIME_SNAPSHOT_TOO_LARGE"

    /** 读写快照时的文件系统错误（含遍历、复制、元数据写入）。 */
    const val FAILED = "RUNTIME_SNAPSHOT_FAILED"
}

/**
 * 会话快照的上限与形状常量，**集中在一处**（界面文案与桥层都从这里取值，不要在别处再写一份）。
 */
object RuntimeSessionSnapshotLimits {
    /** 默认最多保留 3 份。 */
    const val MAX_COUNT = 3

    /** 默认合计最多 512 MiB，超出时从最旧的开始淘汰。 */
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    /** 快照根目录名（位于应用私有 `filesDir` 下，**不在 rootfs 里**：更新会整体替换 rootfs）。 */
    const val DIRECTORY_NAME = "runtime-session-snapshots"

    /** 每份快照的元数据文件名。 */
    const val METADATA_FILE = "snapshot.json"

    /**
     * 每份快照里**会话内容**所在的子目录：`<id>/files/` 下原样保留 `/root/.dsh/sessions` 的相对结构，
     * 元数据放在它外面。
     *
     * 为什么要分两层：会话目录里本来就可能有叫 `snapshot.json` 的文件，两者平铺会互相覆盖；
     * 而且恢复时「哪些是内容、哪些是元数据」必须靠结构说话，不能靠文件名猜。
     */
    const val FILES_DIRECTORY = "files"

    /**
     * 访客会话目录相对运行时根的路径。
     *
     * 必须与 [RuntimePreservePolicy] 保留项 `sessions` 的解析结果一致
     * （`RuntimePreservePolicy.guestRelativePath("sessions")`），有单元测试钉住这条等式；
     * 两处不一致会让「自动快照」悄悄备份一个并不存在的目录。
     */
    const val SESSIONS_RELATIVE_PATH = "root/.dsh/sessions"

    /** 标识最大长度。 */
    const val MAX_ID_CHARS = 96

    /** 元数据文件大小上限；超过即视为不可用。 */
    const val MAX_METADATA_BYTES = 8 * 1024

    /** 单份快照的文件数上限与目录深度上限（防止异常目录结构把遍历拖死）。 */
    const val MAX_FILES = 200_000
    const val MAX_DEPTH = 64

    /**
     * 元数据里「超过一小时仍读不到内容」的残骸视为中断写入的残留，创建新快照时清理。
     * 保留一小时是为了绝不因为一次临时读失败就删掉用户的备份。
     */
    const val STALE_PARTIAL_MS = 60L * 60L * 1000L

    private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")

    /** 标识形态校验：只允许字母数字与 `._-`，因此不可能表达路径穿越。 */
    fun validId(id: String?): Boolean = id != null && id.length <= MAX_ID_CHARS && ID_PATTERN.matches(id)
}

/**
 * 会话快照的纯逻辑部分（无 Android API、无文件系统）：便于在 JVM 单测里穷举淘汰与解析的边界。
 */
internal object RuntimeSessionSnapshotPolicy {
    private val VERSION_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")

    /**
     * 毫秒时间戳 → ISO8601 UTC 字符串（如 `2026-10-01T10:48:23.123Z`）。
     *
     * 用 `Instant.toString()` 而不是自定义格式：输出固定 UTC、不受语言与地区影响，
     * 前端 `new Date(...)` 能直接解析（桥层契约要求 `createdAt` 是可解析的 ISO8601）。
     * 磁盘上的元数据仍存毫秒数：那是给程序读的，字符串只出现在桥层载荷里。
     */
    fun isoInstant(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    /** 生成标识：`snap-<毫秒时间戳>-<随机后缀>`，同时满足「可读、可排序、可当目录名」。 */
    fun snapshotId(createdAtMs: Long, nonce: String): String {
        val suffix = nonce.lowercase(Locale.ROOT)
            .filter { it in '0'..'9' || it in 'a'..'f' }
            .take(8)
            .ifEmpty { "00000000" }
        return "snap-${createdAtMs.coerceAtLeast(0)}-$suffix"
    }

    /** 校验标识；不合法一律 [RuntimeSessionSnapshotCodes.ID_INVALID]。 */
    fun requireId(raw: String?): String {
        val id = raw?.trim().orEmpty()
        if (!RuntimeSessionSnapshotLimits.validId(id)) {
            throw RuntimeFailure(RuntimeSessionSnapshotCodes.ID_INVALID, "会话快照标识无效")
        }
        return id
    }

    /**
     * 淘汰计划：从**最旧**的开始挑，直到「现有份数 + 1 ≤ [maxCount]」且「现有字节 + [incomingBytes] ≤ [maxBytes]」。
     *
     * 纯函数：不删任何东西，只回传要删的标识，实际删除由调用方完成。同一时间戳时按标识排序，
     * 保证结果稳定可断言。**先淘汰再写新的**：反过来会出现「写完了才超限」的窗口。
     */
    fun evictionPlan(
        existing: List<RuntimeSessionSnapshotMeta>,
        incomingBytes: Long,
        maxCount: Int = RuntimeSessionSnapshotLimits.MAX_COUNT,
        maxBytes: Long = RuntimeSessionSnapshotLimits.MAX_TOTAL_BYTES,
    ): List<String> {
        val oldestFirst = existing.sortedWith(compareBy({ it.createdAtMs }, { it.id }))
        var remainingCount = oldestFirst.size
        var remainingBytes = oldestFirst.sumOf { it.totalBytes }
        val evicted = mutableListOf<String>()
        for (snapshot in oldestFirst) {
            if (remainingCount + 1 <= maxCount && remainingBytes + incomingBytes <= maxBytes) break
            evicted += snapshot.id
            remainingCount -= 1
            remainingBytes -= snapshot.totalBytes
        }
        return evicted
    }

    /** 元数据落盘文本：可选字段写成真实 JSON null，读回时才知道「当时确实没读到」。 */
    fun metadataJson(meta: RuntimeSessionSnapshotMeta): String = JSONObject()
        .put("schemaVersion", 1)
        .put("id", meta.id)
        .put("createdAtMs", meta.createdAtMs)
        .put("dshVersion", meta.dshVersion ?: JSONObject.NULL)
        .put("runtimeVersion", meta.runtimeVersion ?: JSONObject.NULL)
        .put("runtimeId", meta.runtimeId ?: JSONObject.NULL)
        .put("fileCount", meta.fileCount)
        .put("totalBytes", meta.totalBytes)
        .toString()

    /**
     * 解析元数据；任何一处不合规都返回 `null`（= 这份快照不可用），绝不抛异常。
     *
     * `id` 必须与目录名一致：目录被改名或元数据被换过时，宁可当成不可用，也不要把一份
     * 来源不明的目录当成用户备份。
     */
    fun parseMetadata(expectedId: String, text: String?): RuntimeSessionSnapshotMeta? = try {
        val json = JSONObject(text.orEmpty())
        val id = json.optString("id", "")
        val createdAtMs = json.optLong("createdAtMs", 0L)
        val fileCount = json.optInt("fileCount", -1)
        val totalBytes = json.optLong("totalBytes", -1L)
        val usable = id == expectedId && RuntimeSessionSnapshotLimits.validId(id) &&
            createdAtMs > 0L && fileCount >= 0 && totalBytes >= 0L
        if (!usable) {
            null
        } else {
            RuntimeSessionSnapshotMeta(
                id = id,
                createdAtMs = createdAtMs,
                dshVersion = optionalVersion(json, "dshVersion"),
                runtimeVersion = optionalVersion(json, "runtimeVersion"),
                runtimeId = optionalVersion(json, "runtimeId"),
                fileCount = fileCount,
                totalBytes = totalBytes,
            )
        }
    } catch (_: Throwable) {
        null
    }

    /** 展示用版本串：缺失、非字符串或形态可疑一律按「没读到」处理，不猜也不原样透传。 */
    private fun optionalVersion(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optString(key, "")
        return value.takeIf { VERSION_PATTERN.matches(it) }
    }
}

/** 当前运行时身份，写进快照元数据。读不到的一律 null（不猜版本号）。 */
data class RuntimeIdentitySnapshot(
    val dshVersion: String?,
    val runtimeVersion: String?,
    val runtimeId: String?,
) {
    companion object {
        val UNKNOWN = RuntimeIdentitySnapshot(null, null, null)
    }
}

/** 一份快照的元数据；`snapshot.json` 与桥层载荷同源。 */
data class RuntimeSessionSnapshotMeta(
    val id: String,
    val createdAtMs: Long,
    val dshVersion: String?,
    val runtimeVersion: String?,
    val runtimeId: String?,
    val fileCount: Int,
    val totalBytes: Long,
) {
    /**
     * 桥层载荷。字段名由平台层契约冻结：`id` / `createdAt`（ISO8601）/ `bytes` / `fileCount`
     * / `dshVersion?` / `runtimeVersion?`。可选字段**缺失**即表示「当时没读到」
     * （与 `getRuntimeVersions` 的 `dshVersion` 同一约定），而不是空串。
     *
     * 磁盘元数据里的 `createdAtMs` 与 `totalBytes` 保持毫秒/字节原值，
     * `runtimeId` 只留在磁盘上（界面用不到，少暴露一个运行时内部标识）。
     */
    fun toJs(): JSONObject = JSONObject()
        .put("id", id)
        .put("createdAt", RuntimeSessionSnapshotPolicy.isoInstant(createdAtMs))
        .put("bytes", totalBytes)
        .put("fileCount", fileCount)
        .also { json ->
            dshVersion?.let { json.put("dshVersion", it) }
            runtimeVersion?.let { json.put("runtimeVersion", it) }
        }
}

/** 快照总览（四个桥方法里三个的返回体）。 */
data class RuntimeSessionSnapshotState(
    /**
     * 快照根目录的**应用私有**绝对路径；不含任何会话内容。
     *
     * 只留在 Kotlin 侧（排障与单测用），**不进桥层载荷**：平台层契约冻结的字段是
     * `maxSnapshots` / `maxBytes` / `totalBytes` / `snapshots`，多一个路径字段没有用处。
     */
    val directory: String,
    /** 按创建时间从新到旧。 */
    val snapshots: List<RuntimeSessionSnapshotMeta>,
    val totalBytes: Long,
    val maxBytes: Long,
    val maxCount: Int,
) {
    fun toJs(): JSONObject = JSONObject()
        .put("maxSnapshots", maxCount)
        .put("maxBytes", maxBytes)
        .put("totalBytes", totalBytes)
        .put(
            "snapshots",
            JSONArray().also { array -> snapshots.forEach { array.put(it.toJs()) } },
        )
}

/** 恢复结果：合并回填的计数 + 恢复后的总览。 */
data class RuntimeSessionSnapshotRestoreResult(
    val restoredFileCount: Int,
    val skippedFileCount: Int,
    val state: RuntimeSessionSnapshotState,
) {
    fun toJs(): JSONObject = JSONObject()
        .put("restoredFileCount", restoredFileCount)
        .put("skippedFileCount", skippedFileCount)
        .put("state", state.toJs())
}

/**
 * 「安装/更新前自动快照」的结论。
 *
 * 三态而不是布尔：`skipped` 表示**没有可备份的会话数据**（首次安装、会话目录为空），
 * 它和 `failed`（真的出错）都必须能让用户看见，但不是同一件事 —— 把首次安装报成
 * 「快照失败」只会制造噪音，把真正的失败混进 skipped 又会让用户以为已经有了备份。
 */
object RuntimeAutoSnapshotStatus {
    const val CREATED = "created"
    const val SKIPPED = "skipped"
    const val FAILED = "failed"
}

/**
 * 更新前自动快照的结论（不阻断安装，由桥层写进安装结果与审计）。
 *
 * 桥层载荷的字段名由平台层契约冻结：`status` / `snapshotId`（仅 `created`）/ `code` 与
 * `message`（仅 `skipped`/`failed`）。`evictedIds` 是契约之外的一个附加字段，对应
 * 「淘汰了哪些旧快照必须如实报告」这条要求；平台层的校验请按「允许存在但可忽略」处理。
 */
data class RuntimeAutoSnapshotOutcome(
    val status: String,
    val snapshotId: String? = null,
    val evictedIds: List<String> = emptyList(),
    val code: String? = null,
    val message: String? = null,
) {
    fun toJs(): JSONObject = JSONObject()
        .put("status", status)
        .also { json ->
            snapshotId?.let { json.put("snapshotId", it) }
            if (evictedIds.isNotEmpty()) {
                json.put("evictedIds", JSONArray().also { array -> evictedIds.forEach { array.put(it) } })
            }
            code?.let { json.put("code", it) }
            message?.let { json.put("message", it) }
        }

    companion object {
        fun created(snapshotId: String, evictedIds: List<String>): RuntimeAutoSnapshotOutcome =
            RuntimeAutoSnapshotOutcome(
                status = RuntimeAutoSnapshotStatus.CREATED,
                snapshotId = snapshotId,
                evictedIds = evictedIds,
            )

        fun skipped(code: String, message: String): RuntimeAutoSnapshotOutcome =
            RuntimeAutoSnapshotOutcome(
                status = RuntimeAutoSnapshotStatus.SKIPPED,
                code = code,
                message = message,
            )

        fun failed(code: String, message: String): RuntimeAutoSnapshotOutcome =
            RuntimeAutoSnapshotOutcome(
                status = RuntimeAutoSnapshotStatus.FAILED,
                code = code,
                message = message,
            )
    }
}

/**
 * 运行时会话快照：把**当前已安装**运行时的 `/root/.dsh/sessions` 整棵树按字节复制到应用私有目录。
 *
 * 为什么需要它：会话文件本身不会因为更新消失（[RuntimePreservePolicy] 会把 `sessions` 搬进新根），
 * 真正的风险是**新镜像里的 dsh 读不出旧 dsh 写的会话**（会话格式/压缩可能不同）。因此这是
 * 「给用户一份主动备份与回退手段」，**不是**「保证会话永不丢失」——任何面向用户的文案都不要
 * 写成保证。
 *
 * 三条硬边界：
 *  1. **只做字节复制**（`Files.copy`），不解压、不改写、不解析会话文件：会话是 zstd 压缩的，
 *     任何「顺手规范化」都会毁掉它；
 *  2. **根目录注入**：本类只认构造时给的两个目录，因此 JVM 单测可以用临时目录把
 *     创建/淘汰/恢复/删除四条链路跑完，不依赖真机，也不碰 Android API；
 *  3. **不做事务承诺**：Harness 正在写会话时拍下的快照是尽力而为的一帧，不保证是某个一致点。
 *
 * @param sessionRoot 已安装运行时的会话目录（宿主侧 `currentRoot/root/.dsh/sessions`）。
 * @param snapshotsRoot 快照根目录（应用私有 `filesDir/runtime-session-snapshots`）。
 * @param identity 当前运行时身份，写进元数据。
 */
class RuntimeSessionSnapshots(
    private val sessionRoot: File,
    private val snapshotsRoot: File,
    private val identity: () -> RuntimeIdentitySnapshot = { RuntimeIdentitySnapshot.UNKNOWN },
    private val maxCount: Int = RuntimeSessionSnapshotLimits.MAX_COUNT,
    private val maxBytes: Long = RuntimeSessionSnapshotLimits.MAX_TOTAL_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = { UUID.randomUUID().toString().replace("-", "").take(8) },
) {
    private class SnapshotEntry(val relative: String, val source: File, val bytes: Long)

    private class SnapshotCreation(
        val state: RuntimeSessionSnapshotState,
        val evictedIds: List<String>,
        /** 刚写进去的那一份的标识：不能靠「列表第一个」推断 —— 同一毫秒里连拍两份时排序会有歧义。 */
        val createdId: String,
    )

    /** 快照根目录（供界面显示与排障，不含会话内容）。 */
    val directory: File get() = snapshotsRoot

    /** 总览：只读，不要求运行时已停止（界面在任何阶段都能显示现有备份）。 */
    fun state(): RuntimeSessionSnapshotState {
        val snapshots = listMeta()
        return RuntimeSessionSnapshotState(
            directory = snapshotsRoot.absolutePath,
            snapshots = snapshots,
            totalBytes = snapshots.sumOf { it.totalBytes },
            maxBytes = maxBytes,
            maxCount = maxCount,
        )
    }

    /**
     * 创建一份快照。
     *
     * 顺序刻意如此：**先淘汰旧的，再写新的**，且元数据**最后写**——一旦中途失败或进程被杀，
     * 留下的目录没有 `snapshot.json`，[state] 不会把它当成可用备份（`pruneUnusable` 之后清理）。
     */
    fun create(): RuntimeSessionSnapshotState = createInternal().state

    /**
     * 「安装/更新前自动快照」入口：**永不抛异常**。
     *
     * 调用方（安装流程）不需要处理任何异常，只需把结论写进结果与审计：更新不能因为备份失败
     * 而失败，但失败必须可见。会话目录不存在或为空 → `skipped`（首次安装没有可备份的东西），
     * 其余错误 → `failed`。
     */
    fun createBeforeUpdate(): RuntimeAutoSnapshotOutcome = try {
        val creation = createInternal()
        RuntimeAutoSnapshotOutcome.created(creation.createdId, creation.evictedIds)
    } catch (failure: RuntimeFailure) {
        if (failure.code == RuntimeSessionSnapshotCodes.EMPTY) {
            // skipped 也要带文案：界面要能直接告诉用户「这次更新没有生成备份，因为还没有会话数据」。
            RuntimeAutoSnapshotOutcome.skipped(failure.code, failure.message ?: "当前没有可备份的会话数据")
        } else {
            RuntimeAutoSnapshotOutcome.failed(failure.code, failure.message ?: "无法生成会话快照")
        }
    } catch (_: Throwable) {
        RuntimeAutoSnapshotOutcome.failed(RuntimeSessionSnapshotCodes.FAILED, "无法生成会话快照")
    }

    /**
     * 恢复一份快照：**合并回填**，同名文件一律不覆盖。
     *
     * 不覆盖是刻意的：更新之后新 dsh 可能已经写过同名会话（例如同一个会话被新格式重写过），
     * 那种内容比旧快照更接近当前运行时；跳过它并如实计数，用户仍能知道「有多少文件没回填」。
     */
    fun restore(id: String): RuntimeSessionSnapshotRestoreResult {
        val meta = requireMeta(id)
        // 只遍历内容目录：元数据与内容是分开两层存放的（见 FILES_DIRECTORY 的说明）。
        val entries = collectEntries(File(snapshotDirectory(meta.id), RuntimeSessionSnapshotLimits.FILES_DIRECTORY))
        // 内容与元数据对不上时拒绝恢复：宁可让用户看到「这份备份不可用」，
        // 也不要把半份备份悄悄回填成看起来正常的会话目录。
        if (entries.size != meta.fileCount || entries.sumOf { it.bytes } != meta.totalBytes) {
            throw RuntimeFailure(
                RuntimeSessionSnapshotCodes.FAILED,
                "快照内容与元数据不一致（记录 ${meta.fileCount} 个文件 / ${meta.totalBytes} 字节），已拒绝恢复",
            )
        }
        if (!ensureSessionRoot()) {
            throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法准备会话目录")
        }
        var restored = 0
        var skipped = 0
        for (entry in entries) {
            val target = File(sessionRoot, entry.relative)
            if (existsNoFollow(target)) {
                skipped += 1
                continue
            }
            target.parentFile?.let { parent ->
                if (!existsNoFollow(parent) && !parent.mkdirs()) {
                    throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法创建会话目录")
                }
            }
            try {
                Files.copy(entry.source.toPath(), target.toPath())
            } catch (error: IOException) {
                throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法恢复会话文件", error)
            }
            restored += 1
        }
        return RuntimeSessionSnapshotRestoreResult(restored, skipped, state())
    }

    /** 删除一份快照（只删它自己的目录，绝不触碰会话目录）。 */
    fun delete(id: String): RuntimeSessionSnapshotState {
        val meta = requireMeta(id)
        deleteTree(snapshotDirectory(meta.id))
        return state()
    }

    private fun createInternal(): SnapshotCreation {
        val entries = collectEntries(sessionRoot)
        if (entries.isEmpty()) {
            throw RuntimeFailure(
                RuntimeSessionSnapshotCodes.EMPTY,
                "当前没有可备份的会话数据，未生成快照",
            )
        }
        val totalBytes = entries.sumOf { it.bytes }
        if (totalBytes > maxBytes) {
            throw RuntimeFailure(
                RuntimeSessionSnapshotCodes.TOO_LARGE,
                "会话数据超过快照容量上限，未生成快照",
            )
        }
        if (!prepareSnapshotsRoot()) {
            throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法准备快照目录")
        }
        pruneUnusable()
        val existing = listMeta()
        val evicted = RuntimeSessionSnapshotPolicy.evictionPlan(existing, totalBytes, maxCount, maxBytes)
        for (id in evicted) {
            deleteTree(snapshotDirectory(id))
        }

        val createdAtMs = now()
        // 只读一次身份：identity() 可能要去解析运行时里的 package.json，不该为三个字段各读一遍。
        val currentIdentity = identity()
        val meta = RuntimeSessionSnapshotMeta(
            id = RuntimeSessionSnapshotPolicy.snapshotId(createdAtMs, nonce()),
            createdAtMs = createdAtMs,
            dshVersion = currentIdentity.dshVersion,
            runtimeVersion = currentIdentity.runtimeVersion,
            runtimeId = currentIdentity.runtimeId,
            fileCount = entries.size,
            totalBytes = totalBytes,
        )
        val target = snapshotDirectory(meta.id)
        if (existsNoFollow(target)) {
            throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "快照标识冲突，请重试")
        }
        try {
            val payloadRoot = File(target, RuntimeSessionSnapshotLimits.FILES_DIRECTORY)
            for (entry in entries) {
                val destination = File(payloadRoot, entry.relative)
                val parent = destination.parentFile
                if (parent != null && !existsNoFollow(parent) && !parent.mkdirs()) {
                    throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法创建快照目录")
                }
                Files.copy(entry.source.toPath(), destination.toPath())
            }
            // 元数据最后写：它存在即代表这份快照是完整的。
            File(target, RuntimeSessionSnapshotLimits.METADATA_FILE)
                .writeText(RuntimeSessionSnapshotPolicy.metadataJson(meta), Charsets.UTF_8)
        } catch (error: Throwable) {
            // 失败的半成品立刻清掉，避免用户看到一份缺文件的「备份」。
            runCatching { deleteTree(target) }
            throw error as? RuntimeFailure
                ?: RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法生成会话快照", error)
        }
        return SnapshotCreation(state(), evicted, meta.id)
    }

    /** 枚举一份目录树里的**常规文件**：不跟随符号链接，深度与条目数都有上限，结果按相对路径排序。 */
    private fun collectEntries(root: File): List<SnapshotEntry> {
        if (!isDirectoryNoFollow(root)) return emptyList()
        val rootPath = root.toPath()
        val entries = mutableListOf<SnapshotEntry>()

        fun walk(directory: File, depth: Int) {
            if (depth > RuntimeSessionSnapshotLimits.MAX_DEPTH) {
                throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "会话目录层级过深，无法生成快照")
            }
            val children = directory.listFiles()
                ?: throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法读取会话目录")
            for (child in children) {
                val attributes = attributesNoFollow(child)
                    ?: throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法读取会话文件属性")
                when {
                    // 符号链接只跳过、不复制：它的语义是「指向别处」，复制目标会把它变成普通文件。
                    attributes.isSymbolicLink -> Unit
                    attributes.isDirectory -> walk(child, depth + 1)
                    attributes.isRegularFile -> {
                        if (entries.size >= RuntimeSessionSnapshotLimits.MAX_FILES) {
                            throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "会话文件数量超过快照上限")
                        }
                        entries += SnapshotEntry(
                            relative = relativePath(rootPath, child.toPath()),
                            source = child,
                            bytes = attributes.size(),
                        )
                    }
                }
            }
        }

        walk(root, 0)
        return entries.sortedBy { it.relative }
    }

    /** 相对路径统一用 `/`，并在这里挡掉空段与 `..`：快照内部结构不接受任何越界表达。 */
    private fun relativePath(rootPath: Path, path: Path): String {
        val relative = rootPath.relativize(path).toString().replace(File.separatorChar, '/')
        val segments = relative.split('/')
        if (relative.isEmpty() || relative.startsWith("/") ||
            segments.any { it.isEmpty() || it == "." || it == ".." }
        ) {
            throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "会话文件相对路径无效")
        }
        return relative
    }

    private fun listMeta(): List<RuntimeSessionSnapshotMeta> {
        val children = snapshotsRoot.listFiles() ?: return emptyList()
        val metas = mutableListOf<RuntimeSessionSnapshotMeta>()
        for (child in children) {
            val id = child.name
            if (!RuntimeSessionSnapshotLimits.validId(id) || !isDirectoryNoFollow(child)) continue
            readMeta(id)?.let { metas += it }
        }
        return metas.sortedWith(compareByDescending<RuntimeSessionSnapshotMeta> { it.createdAtMs }.thenBy { it.id })
    }

    private fun requireMeta(rawId: String?): RuntimeSessionSnapshotMeta {
        val id = RuntimeSessionSnapshotPolicy.requireId(rawId)
        return readMeta(id)
            ?: throw RuntimeFailure(RuntimeSessionSnapshotCodes.NOT_FOUND, "会话快照不存在或已被删除")
    }

    private fun readMeta(id: String): RuntimeSessionSnapshotMeta? {
        val directory = snapshotDirectory(id)
        if (!isDirectoryNoFollow(directory)) return null
        val metadataFile = File(directory, RuntimeSessionSnapshotLimits.METADATA_FILE)
        val text = readMetadataText(metadataFile) ?: return null
        return RuntimeSessionSnapshotPolicy.parseMetadata(id, text)
    }

    private fun readMetadataText(file: File): String? = try {
        val attributes = attributesNoFollow(file)
        val size = attributes?.size() ?: 0L
        if (attributes == null || !attributes.isRegularFile || attributes.isSymbolicLink ||
            size <= 0L || size > RuntimeSessionSnapshotLimits.MAX_METADATA_BYTES
        ) {
            null
        } else {
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        }
    } catch (_: IOException) {
        null
    }

    /**
     * 清理无法使用的残留：名字不是合法标识的条目、不是目录的条目，以及**超过一小时**仍读不到
     * 元数据的残骸（中断写入的产物）。一条都不能是有效备份 —— 有元数据的快照一律不动。
     */
    private fun pruneUnusable() {
        val children = snapshotsRoot.listFiles() ?: return
        val nowMs = now()
        for (child in children) {
            val id = child.name
            if (!RuntimeSessionSnapshotLimits.validId(id) || !isDirectoryNoFollow(child)) {
                runCatching { deleteTree(child) }
                continue
            }
            if (readMeta(id) != null) continue
            val stale = child.lastModified().let { it > 0L && nowMs - it > RuntimeSessionSnapshotLimits.STALE_PARTIAL_MS }
            if (stale) runCatching { deleteTree(child) }
        }
    }

    private fun prepareSnapshotsRoot(): Boolean = snapshotsRoot.isDirectory || snapshotsRoot.mkdirs()

    private fun ensureSessionRoot(): Boolean = sessionRoot.isDirectory || sessionRoot.mkdirs()

    private fun snapshotDirectory(id: String): File {
        val directory = File(snapshotsRoot, id)
        // 双保险：标识已经过形态校验，这里再确认解析后的父目录就是快照根（不跟随符号链接）。
        val parent = directory.canonicalFile.parentFile
        if (parent == null || parent != snapshotsRoot.canonicalFile) {
            throw RuntimeFailure(RuntimeSessionSnapshotCodes.ID_INVALID, "会话快照标识越界")
        }
        return directory
    }

    /** 递归删除快照目录：不跟随符号链接（删链接本身），条目数有上限。 */
    private fun deleteTree(root: File) {
        var budget = RuntimeSessionSnapshotLimits.MAX_FILES
        fun remove(target: File, depth: Int) {
            if (depth > RuntimeSessionSnapshotLimits.MAX_DEPTH || budget-- <= 0) {
                throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "快照目录结构异常，无法删除")
            }
            val attributes = attributesNoFollow(target) ?: return
            if (attributes.isDirectory && !attributes.isSymbolicLink) {
                target.listFiles()?.forEach { remove(it, depth + 1) }
                if (!target.delete() && existsNoFollow(target)) {
                    throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法删除会话快照")
                }
            } else if (!target.delete() && existsNoFollow(target)) {
                throw RuntimeFailure(RuntimeSessionSnapshotCodes.FAILED, "无法删除会话快照")
            }
        }
        remove(root, 0)
    }

    private fun existsNoFollow(file: File): Boolean = attributesNoFollow(file) != null

    private fun isDirectoryNoFollow(file: File): Boolean = attributesNoFollow(file)?.let {
        it.isDirectory && !it.isSymbolicLink
    } ?: false

    private fun attributesNoFollow(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    }
}
