package io.deepseekharness.mobile.runtime

import android.system.Os
import io.deepseekharness.mobile.runtime.diagnostics.TransferFields
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/** 只保存已校验的原始安装包，不保存用户会话和密钥。缓存键为完整 SHA-256。 */
class RuntimeLibrary(private val store: RuntimeStore) {
    private val directory = File(store.runtimeParent, "library")
    companion object {
        /** 容量与纯策略同源：界面上的「已下载 X/5」和淘汰判定必须用同一个数。 */
        const val MAX_CAPACITY = RuntimeVersionPolicy.LIBRARY_CAPACITY

        /**
         * 条目元数据（锁定状态与归档时间）的后缀。
         *
         * 刻意不用 `.json`：版本库里的 `*.json` 一律被当成清单扫描，元数据混进去会被当成坏条目清掉。
         */
        private const val META_SUFFIX = ".meta"

        /** 元数据只有两个字段，超过这个体积一定是坏文件而不是我们的 sidecar。 */
        private const val META_LIMIT_BYTES = 4L * 1024L

        private val ID_PATTERN = Regex("^[a-f0-9]{64}$")

        /** 一个版本库条目的元数据：用户是否锁定、归档何时落地。 */
        data class LibraryMeta(val pinned: Boolean, val downloadedAt: Long)

        fun requireId(value: String): String {
            if (!ID_PATTERN.matches(value)) throw RuntimeFailure("RUNTIME_VERSION_INVALID", "版本标识无效")
            return value
        }
    }
    private fun prepare() {
        for (folder in listOf(store.runtimeParent, directory)) {
            if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(folder.toPath())
            if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) throw RuntimeFailure("FILESYSTEM_ERROR", "版本库目录无效")
            Os.chmod(folder.absolutePath, 0x1c0)
        }
    }
    private fun path(id: String, suffix: String) = File(directory, requireId(id) + suffix).toPath()

    /** 一个条目算「已下载」的唯一规则：清单与归档同时在。 */
    private fun present(id: String): Boolean =
        Files.isRegularFile(path(id, ".json"), LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(path(id, ".bundle"), LinkOption.NOFOLLOW_LINKS)

    /**
     * 版本库条目：只收「清单 + 归档」齐备且文件名是 64 位 SHA-256 的条目。
     *
     * 归档时间取 sidecar 里的 `downloadedAt`；旧版本库没有 sidecar，回落到归档文件的修改时间，
     * 所以存量条目不需要迁移，也不会因为少了一个 sidecar 就从列表里消失。
     */
    private fun entries(): List<RuntimeVersionPolicy.LibraryEntry> {
        prepare()
        val activeId = store.installedManifest()?.rootfs?.sha256
        val found = mutableListOf<RuntimeVersionPolicy.LibraryEntry>()
        Files.newDirectoryStream(directory.toPath(), "*.json").use { stream ->
            for (file in stream) {
                if (found.size >= 1000) break
                val id = file.fileName.toString().removeSuffix(".json")
                if (!ID_PATTERN.matches(id) || !present(id)) continue
                val meta = readMeta(id)
                found += RuntimeVersionPolicy.LibraryEntry(
                    id = id,
                    downloadedAt = meta.downloadedAt,
                    pinned = meta.pinned,
                    active = id == activeId,
                )
            }
        }
        return found
    }

    /**
     * 清掉不可能再用的残留：名字不是 64 位 SHA-256 的清单/归档，以及只剩一半的条目。
     *
     * 没有这一步，一个半途写坏或名字不合法（例如 `<64位>.meta.json` 这类历史遗留）的旧文件会永远占着配额，
     * 又因为不合规而挑不出来淘汰，用户看到的是「一直说版本库已满，可列表里又找不到能删的东西」。
     *
     * 先收集再删除：边遍历边删目录项在 Android 上不是所有文件系统都保证安全。
     */
    private fun sweepBroken() {
        prepare()
        val broken = mutableListOf<java.nio.file.Path>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (file in stream) {
                val name = file.fileName.toString()
                val suffix = when {
                    name.endsWith(".json") -> ".json"
                    name.endsWith(".bundle") -> ".bundle"
                    else -> continue
                }
                val id = name.removeSuffix(suffix)
                // 用 add 而不是 `+=`：`Path` 本身也是 `Iterable<Path>`，`+=` 会被解析成「拼接再赋值」。
                if (!ID_PATTERN.matches(id) || !present(id)) broken.add(file)
            }
        }
        for (file in broken) Files.deleteIfExists(file)
    }

    /**
     * 读条目元数据：锁定状态与归档时间。
     *
     * sidecar 缺失或读坏一律按「未锁定 + 归档文件修改时间」兜底：宁可把一个坏 sidecar 当成未锁定，
     * 也不能让一个可用的版本因为读不懂元数据而在界面上消失。
     */
    private fun readMeta(validId: String): LibraryMeta {
        val fallback = LibraryMeta(
            pinned = false,
            downloadedAt = try {
                Files.getLastModifiedTime(path(validId, ".bundle"), LinkOption.NOFOLLOW_LINKS).toMillis()
            } catch (_: Throwable) { 0L },
        )
        return try {
            val file = path(validId, META_SUFFIX)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > META_LIMIT_BYTES) {
                fallback
            } else {
                val raw = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readBytes() }
                val json = JSONObject(String(raw, Charsets.UTF_8))
                LibraryMeta(
                    pinned = json.optBoolean("pinned", false),
                    downloadedAt = json.optLong("downloadedAt", fallback.downloadedAt),
                )
            }
        } catch (_: Throwable) { fallback }
    }

    /** 元数据读取入口：条目不存在或只剩一半时以 `RUNTIME_VERSION_MISSING` 明确失败。 */
    fun meta(id: String): LibraryMeta {
        val validId = requireId(id)
        prepare()
        if (!present(validId)) throw RuntimeFailure("RUNTIME_VERSION_MISSING", "本地版本不存在")
        return readMeta(validId)
    }

    /**
     * 锁定/解锁一个已下载的版本。
     *
     * 锁定只影响自动淘汰：被锁定的条目永远不会被挑走，满额时宁可明确失败（`RUNTIME_LIBRARY_FULL`）
     * 也不会删掉它。写入沿用下载路径的原子替换 + 私有权限，避免半截 sidecar 被读成「未锁定」。
     */
    fun setPinned(id: String, pinned: Boolean): LibraryMeta {
        val validId = requireId(id)
        prepare()
        if (!present(validId)) throw RuntimeFailure("RUNTIME_VERSION_MISSING", "本地版本不存在")
        val current = readMeta(validId)
        val updated = LibraryMeta(pinned = pinned, downloadedAt = current.downloadedAt)
        writeMeta(validId, updated)
        return updated
    }

    private fun writeMeta(validId: String, meta: LibraryMeta) {
        val pending = Files.createTempFile(directory.toPath(), "meta-", ".part")
        try {
            Os.chmod(pending.toString(), 0x180)
            val payload = JSONObject().put("pinned", meta.pinned).put("downloadedAt", meta.downloadedAt).toString()
            Files.write(pending, payload.toByteArray(Charsets.UTF_8))
            Files.move(pending, path(validId, META_SUFFIX), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(pending) }
    }

    fun count(): Int = entries().size

    fun canAdd(id: String): Boolean {
        prepare()
        return RuntimeVersionPolicy.canAddToLibrary(existing = present(requireId(id)), count = count())
    }

    /** 删除一个条目：清单、归档与元数据一并清掉，不留「删了但列表里还在」的半份。 */
    fun delete(id: String): Boolean {
        prepare()
        val validId = requireId(id)
        var deleted = false
        for (suffix in listOf(".json", ".bundle", META_SUFFIX)) {
            if (Files.deleteIfExists(path(validId, suffix))) deleted = true
        }
        return deleted
    }

    /**
     * 满额时腾位：只淘汰最旧的、未锁定且非当前使用的条目。
     *
     * 挑不出对象（全被锁定，或只剩当前使用/本次要写入的那条）时以 `RUNTIME_LIBRARY_FULL` 明确失败——
     * 用户要的语义是「版本库满了，请先删掉或解锁一个」，而不是「我悄悄替你删了一个锁定的版本」。
     */
    private fun autoEvictIfNeeded(incomingId: String) {
        if (canAdd(incomingId)) return
        val candidate = RuntimeVersionPolicy.evictionCandidate(entries(), incomingId)
            ?: throw RuntimeFailure("RUNTIME_LIBRARY_FULL", "版本库已满，请先删除或解锁一个版本")
        delete(candidate)
    }

    fun download(source: RuntimeSource, onProgress: ((Long, Long) -> Unit)? = null) {
        prepare()
        // 先在写入路径清一次残留：坏文件不清理就永远占着配额，还会让满额淘汰挑不出对象。
        sweepBroken()
        val uri = source.manifestUrl ?: throw RuntimeFailure("SOURCE_INCOMPLETE", "请先选择安卓运行时包")
        val digest = source.manifestSha256 ?: throw RuntimeFailure("SOURCE_INCOMPLETE", "缺少运行时清单摘要")
        val http = RuntimeHttp()
        val manifest = RuntimeManifest.parse(http.downloadBytes(uri, digest, RuntimeLimits.MAX_MANIFEST_BYTES), uri.host)
        autoEvictIfNeeded(manifest.rootfs.sha256)
        val target = path(manifest.rootfs.sha256, ".part").toFile()
        try {
            http.downloadFile(manifest.rootfs.url, target, manifest.rootfs.compressedBytes, manifest.rootfs.sha256) { downloaded, total ->
                onProgress?.invoke(downloaded, total)
            }
            retain(manifest, target)
        } finally { Files.deleteIfExists(target.toPath()) }
    }
    fun manifest(id: String): RuntimeManifest {
        prepare()
        val file = path(id, ".json")
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > RuntimeLimits.MAX_MANIFEST_BYTES) {
            throw RuntimeFailure("RUNTIME_VERSION_MISSING", "本地版本清单不存在")
        }
        val parsed = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { RuntimeManifest.parse(it.readBytes()) }
        if (parsed.rootfs.sha256 != id) throw RuntimeFailure("MANIFEST_DIGEST_MISMATCH", "版本标识与归档不一致")
        return parsed
    }
    fun list(): JSONArray {
        prepare()
        val entries = JSONArray()
        Files.newDirectoryStream(directory.toPath(), "*.json").use { stream ->
            for (file in stream) {
                if (entries.length() >= 100) break
                val id = file.fileName.toString().removeSuffix(".json")
                try {
                    val manifest = manifest(id)
                    val archive = path(id, ".bundle")
                    if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.size(archive) != manifest.rootfs.compressedBytes) continue
                    val meta = readMeta(id)
                    entries.put(JSONObject().put("id", id).put("version", manifest.version)
                        .put("dshVersion", manifest.dshVersion ?: "未声明")
                        .put("bytes", manifest.rootfs.compressedBytes)
                        .put("pinned", meta.pinned)
                        .put("active", store.installedManifest()?.rootfs?.sha256 == id)
                        // 时间读不到（0）时宁可不给这个键，也不回一个 1970 年的时间戳去骗界面。
                        .also { json ->
                            if (meta.downloadedAt > 0L) {
                                json.put("downloadedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(meta.downloadedAt)))
                            }
                        })
                } catch (_: RuntimeFailure) { /* 损坏条目不冒充已下载版本。 */ }
            }
        }
        return entries
    }
    fun retain(manifest: RuntimeManifest, archive: File) {
        prepare()
        sweepBroken()
        val id = manifest.rootfs.sha256
        autoEvictIfNeeded(id)
        val destination = path(id, ".bundle")
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            val temporary = Files.createTempFile(directory.toPath(), "download-", ".part")
            try {
                Os.chmod(temporary.toString(), 0x180)
                Files.newInputStream(archive.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                    Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output -> input.copyTo(output) }
                }
                verify(temporary.toFile(), manifest)
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(temporary) }
        } else verify(destination.toFile(), manifest)
        val target = path(id, ".json")
        val pending = Files.createTempFile(directory.toPath(), "manifest-", ".part")
        try {
            Os.chmod(pending.toString(), 0x180)
            Files.write(pending, manifest.rawBytes)
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(pending) }
        // 归档时间只在条目第一次落地时写：重下同一个版本不该把它刷新成「最新下载」，
        // 否则每次重下都会把这条挪到淘汰顺序的末尾，等于变相永久保命。
        if (!Files.exists(path(id, META_SUFFIX), LinkOption.NOFOLLOW_LINKS)) {
            val landedAt = try { Files.getLastModifiedTime(path(id, ".bundle"), LinkOption.NOFOLLOW_LINKS).toMillis() } catch (_: Throwable) { 0L }
            writeMeta(id, LibraryMeta(pinned = false, downloadedAt = landedAt))
        }
    }
    fun copyArchive(id: String, destination: File) {
        val manifest = manifest(id)
        val archive = path(id, ".bundle").toFile()
        verify(archive, manifest)
        Files.newInputStream(archive.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output -> input.copyTo(output) }
        }
    }
    private fun verify(file: File, manifest: RuntimeManifest) {
        // 失败时带上长度证据：安装器把它写进诊断日志，用户不用再猜「哪一份安装包有问题」。
        val expectedBytes = manifest.rootfs.compressedBytes
        val path = file.toPath()
        val regular = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        val actualBytes = if (regular) Files.size(path) else 0L
        if (!regular || actualBytes != expectedBytes) {
            throw RuntimeFailure(
                "ARCHIVE_DIGEST_MISMATCH",
                "本地安装包长度不正确",
                details = TransferFields.of(expectedBytes, actualBytes),
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != manifest.rootfs.sha256) {
            throw RuntimeFailure(
                "ARCHIVE_DIGEST_MISMATCH",
                "本地安装包校验失败，请重新下载",
                details = TransferFields.of(expectedBytes, actualBytes, digestOk = false),
            )
        }
    }
}
