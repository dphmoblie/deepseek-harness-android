package io.deepseekharness.mobile.runtime

import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** 只保存已校验的原始安装包，不保存用户会话和密钥。缓存键为完整 SHA-256。 */
class RuntimeLibrary(private val store: RuntimeStore) {
    private val directory = File(store.runtimeParent, "library")
    companion object {
        fun requireId(value: String): String {
            if (!Regex("^[a-f0-9]{64}$").matches(value)) throw RuntimeFailure("RUNTIME_VERSION_INVALID", "版本标识无效")
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
    fun download(source: RuntimeSource) {
        prepare()
        val uri = source.manifestUrl ?: throw RuntimeFailure("SOURCE_INCOMPLETE", "请先选择安卓运行时包")
        val digest = source.manifestSha256 ?: throw RuntimeFailure("SOURCE_INCOMPLETE", "缺少运行时清单摘要")
        val http = RuntimeHttp()
        val manifest = RuntimeManifest.parse(http.downloadBytes(uri, digest, RuntimeLimits.MAX_MANIFEST_BYTES), uri.host)
        val target = path(manifest.rootfs.sha256, ".part").toFile()
        try {
            http.downloadFile(manifest.rootfs.url, target, manifest.rootfs.compressedBytes, manifest.rootfs.sha256) { _, _ -> }
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
                    entries.put(JSONObject().put("id", id).put("version", manifest.version)
                        .put("dshVersion", manifest.dshVersion ?: "未声明")
                        .put("bytes", manifest.rootfs.compressedBytes)
                        .put("active", store.installedManifest()?.rootfs?.sha256 == id))
                } catch (_: RuntimeFailure) { /* 损坏条目不冒充已下载版本。 */ }
            }
        }
        return entries
    }
    fun retain(manifest: RuntimeManifest, archive: File) {
        prepare()
        val id = manifest.rootfs.sha256
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
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.size(file.toPath()) != manifest.rootfs.compressedBytes) {
            throw RuntimeFailure("ARCHIVE_DIGEST_MISMATCH", "本地安装包长度不正确")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != manifest.rootfs.sha256) {
            throw RuntimeFailure("ARCHIVE_DIGEST_MISMATCH", "本地安装包校验失败，请重新下载")
        }
    }
}
