package io.deepseekharness.mobile.runtime

import android.system.ErrnoException
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

class RuntimeInstaller(
    private val store: RuntimeStore,
    private val status: RuntimeStatus,
    private val http: RuntimeHttp = RuntimeHttp(),
    private val extractor: SafeRootfsExtractor = SafeRootfsExtractor(),
    private val externalCancellation: () -> Boolean = { false },
) {
    private data class Workspace(
        val stagingRoot: File,
        val stagingManifest: File,
        val archivePart: File,
        val preserveArchiveOnFailure: Boolean,
    )

    private val installLock = ReentrantLock()
    private val cancellationRequested = AtomicBoolean(false)

    fun install(source: RuntimeSource) {
        if (!installLock.tryLock()) throw RuntimeFailure("INSTALL_IN_PROGRESS", "运行时安装正在进行")
        cancellationRequested.set(false)
        var workspace: Workspace? = null
        try {
            if (!store.runnerAvailable()) {
                throw RuntimeFailure("RUNNER_UNAVAILABLE", "APK 未包含当前架构的受信任运行器")
            }
            prepareRuntimeParent()
            checkCancellation()

            val transferPhase = if (source.isBundled) RuntimePhase.PREPARING else RuntimePhase.DOWNLOADING
            status.update(transferPhase, downloaded = 0, total = 0)
            val manifest = loadManifest(source)
            checkCancellation()
            if (isCurrent(manifest)) {
                status.update(
                    RuntimePhase.READY,
                    downloaded = manifest.rootfs.compressedBytes,
                    total = manifest.rootfs.compressedBytes,
                    nextHarnessUrl = null,
                )
                return
            }
            workspace = createWorkspace(manifest, source.isBundled)
            status.update(transferPhase, downloaded = 0, total = manifest.rootfs.compressedBytes)

            if (source.isBundled) {
                copyBundledRootfs(workspace.archivePart, manifest.rootfs) { copied ->
                    status.update(
                        RuntimePhase.PREPARING,
                        downloaded = copied,
                        total = manifest.rootfs.compressedBytes,
                    )
                }
            } else {
                http.downloadFile(
                    manifest.rootfs.url,
                    workspace.archivePart,
                    manifest.rootfs.compressedBytes,
                    manifest.rootfs.sha256,
                ) { downloaded, total ->
                    checkCancellation()
                    status.update(RuntimePhase.DOWNLOADING, downloaded = downloaded, total = total)
                }
            }
            checkCancellation()
            status.update(
                RuntimePhase.VERIFYING,
                downloaded = manifest.rootfs.compressedBytes,
                total = manifest.rootfs.compressedBytes,
            )
            status.update(
                RuntimePhase.EXTRACTING,
                downloaded = 0,
                total = manifest.rootfs.extractedBytes,
            )
            extractor.extract(
                workspace.archivePart,
                workspace.stagingRoot,
                manifest.rootfs.compressedBytes,
                manifest.rootfs.sha256,
                manifest.rootfs.extractedBytes,
                manifest.rootfs.compression,
                ::isCancelled,
            ) { extracted, total ->
                status.update(RuntimePhase.EXTRACTING, downloaded = extracted, total = total)
            }
            checkCancellation()
            RootfsIntegrity.verifyLinks(workspace.stagingRoot, "ROOTFS_LINKS_CORRUPTED")
            store.writeInstalledManifest(workspace.stagingManifest, manifest)
            promoteStaging(workspace)
            store.updateInstalledManifest(manifest)
            cleanupIfPresent(workspace.archivePart)
            status.update(
                RuntimePhase.READY,
                downloaded = manifest.rootfs.compressedBytes,
                total = manifest.rootfs.compressedBytes,
                nextHarnessUrl = null,
            )
        } catch (error: Throwable) {
            val cleanupFailure = workspace?.let {
                try {
                    cleanupWorkspace(it, preserveArchive = it.preserveArchiveOnFailure)
                    null
                } catch (cleanupError: Throwable) {
                    cleanupError as? RuntimeFailure
                        ?: RuntimeFailure("CLEANUP_FAILED", "无法完整清理安装暂存文件", cleanupError)
                }
            }
            // Cleanup must not leave the public state stuck in a transfer or extraction phase.
            val failure = cleanupFailure ?: if (isCancelled()) {
                RuntimeFailure("INSTALL_CANCELLED", "运行时安装已取消")
            } else {
                error as? RuntimeFailure ?: RuntimeFailure("INSTALL_FAILED", "运行时安装失败", error)
            }
            if (failure.code == "INSTALL_CANCELLED") status.refreshIdle()
            else status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = failure.code)
            throw failure
        } finally {
            installLock.unlock()
        }
    }

    fun cancelInstall() {
        cancellationRequested.set(true)
        http.cancelAll()
    }

    fun resetWorkspace() {
        if (!installLock.tryLock()) throw RuntimeFailure("INSTALL_IN_PROGRESS", "安装期间不能重置运行时")
        try {
            cleanTransientWorkspaces()
            cleanResumeFilesExcept(null)
            cleanupIfPresent(store.backupManifest)
            cleanupIfPresent(store.backupRoot)
            cleanupIfPresent(store.currentManifest)
            cleanupIfPresent(store.currentRoot)
            store.updateInstalledManifest(null)
            status.refreshIdle()
        } finally {
            installLock.unlock()
        }
    }

    /**
     * 指纹比对：目标清单与已安装清单的 rootfs 摘要一致时视为已是最新，
     * 跳过下载与解压（防重复安装，也防内嵌快照覆盖在线更新）。
     */
    private fun isCurrent(manifest: RuntimeManifest): Boolean {
        val installed = store.installedManifest() ?: return false
        return installed.rootfs.sha256 == manifest.rootfs.sha256
    }

    private fun loadManifest(source: RuntimeSource): RuntimeManifest {
        if (source.isBundled) {
            val bytes = store.openBundledManifest().use {
                readBounded(it, RuntimeLimits.MAX_MANIFEST_BYTES)
            }
            return RuntimeManifest.parse(bytes)
        }
        val manifestUrl = source.manifestUrl
            ?: throw RuntimeFailure("SOURCE_INCOMPLETE", "运行时来源无效")
        val manifestSha256 = source.manifestSha256
            ?: throw RuntimeFailure("SOURCE_INCOMPLETE", "运行时来源无效")
        val bytes = http.downloadBytes(
            manifestUrl,
            manifestSha256,
            RuntimeLimits.MAX_MANIFEST_BYTES,
        )
        return RuntimeManifest.parse(bytes, manifestUrl.host)
    }

    private fun copyBundledRootfs(
        destination: File,
        artifact: RootfsArtifact,
        onProgress: (Long) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            store.openBundledRootfs().use { input ->
                FileChannel.open(
                    destination.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    BufferedOutputStream(Channels.newOutputStream(channel), BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            checkCancellation()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            if (written > artifact.compressedBytes - read) {
                                throw RuntimeFailure("ARCHIVE_SIZE_MISMATCH", "APK 内置运行时归档大小无效")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            onProgress(written)
                        }
                        output.flush()
                        channel.force(true)
                    }
                }
            }
        } catch (error: Throwable) {
            if (RuntimeFiles.existsNoFollow(destination)) cleanupIfPresent(destination)
            if (error is RuntimeFailure) throw error
            throw RuntimeFailure("BUNDLED_RUNTIME_READ_FAILED", "无法读取 APK 内置运行时", error)
        }
        if (written != artifact.compressedBytes || digest.digest().toLowerHex() != artifact.sha256) {
            cleanupIfPresent(destination)
            throw RuntimeFailure("ARCHIVE_DIGEST_MISMATCH", "APK 内置运行时完整性校验失败")
        }
    }

    private fun readBounded(input: InputStream, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            checkCancellation()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maximumBytes) {
                throw RuntimeFailure("MANIFEST_SIZE_INVALID", "APK 内置运行时清单大小无效")
            }
            output.write(buffer, 0, read)
        }
        if (total == 0) throw RuntimeFailure("MANIFEST_SIZE_INVALID", "APK 内置运行时清单为空")
        return output.toByteArray()
    }

    private fun ByteArray.toLowerHex(): String {
        val digits = "0123456789abcdef"
        val result = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = digits[value ushr 4]
            result[index * 2 + 1] = digits[value and 0x0f]
        }
        return result.concatToString()
    }

    private fun prepareRuntimeParent() {
        if (!RuntimeFiles.existsNoFollow(store.runtimeParent)) {
            Files.createDirectory(store.runtimeParent.toPath())
        } else if (!RuntimeFiles.isDirectoryNoFollow(store.runtimeParent)) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "运行时父路径不是目录")
        }
        recoverInterruptedPromotion()
        cleanTransientWorkspaces()
    }

    private fun createWorkspace(manifest: RuntimeManifest, bundled: Boolean): Workspace {
        val nonce = UUID.randomUUID().toString()
        val stagingRoot = File(store.runtimeParent, "staging-$nonce")
        val stagingManifest = File(store.runtimeParent, "manifest-$nonce.json")
        val resumeName = if (bundled) null else "rootfs-${manifest.rootfs.sha256}.part"
        cleanResumeFilesExcept(resumeName)
        val archivePart = File(store.runtimeParent, resumeName ?: "download-$nonce.part")
        // The extractor itself creates the root with CREATE_NEW-style directory semantics.
        return Workspace(stagingRoot, stagingManifest, archivePart, preserveArchiveOnFailure = !bundled)
    }

    private fun recoverInterruptedPromotion() {
        val currentRoot = RuntimeFiles.existsNoFollow(store.currentRoot)
        val currentManifest = RuntimeFiles.existsNoFollow(store.currentManifest)
        val backupRoot = RuntimeFiles.existsNoFollow(store.backupRoot)
        val backupManifest = RuntimeFiles.existsNoFollow(store.backupManifest)
        try {
            when {
                currentRoot && currentManifest -> {
                    cleanupIfPresent(store.backupManifest)
                    cleanupIfPresent(store.backupRoot)
                }
                !currentRoot && currentManifest && backupRoot && !backupManifest -> {
                    Os.rename(store.backupRoot.absolutePath, store.currentRoot.absolutePath)
                }
                currentRoot && !currentManifest && backupRoot && backupManifest -> {
                    cleanupIfPresent(store.currentRoot)
                    Os.rename(store.backupRoot.absolutePath, store.currentRoot.absolutePath)
                    Os.rename(store.backupManifest.absolutePath, store.currentManifest.absolutePath)
                }
                !currentRoot && !currentManifest && backupRoot && backupManifest -> {
                    Os.rename(store.backupRoot.absolutePath, store.currentRoot.absolutePath)
                    Os.rename(store.backupManifest.absolutePath, store.currentManifest.absolutePath)
                }
                else -> {
                    cleanupIfPresent(store.currentManifest)
                    cleanupIfPresent(store.currentRoot)
                    cleanupIfPresent(store.backupManifest)
                    cleanupIfPresent(store.backupRoot)
                }
            }
        } catch (error: ErrnoException) {
            throw RuntimeFailure("RUNTIME_RECOVERY_FAILED", "无法恢复中断的运行时安装", error)
        } finally {
            store.invalidateInstalledManifest()
        }
    }

    private fun promoteStaging(workspace: Workspace) {
        var rootBackedUp = false
        var manifestBackedUp = false
        try {
            if (RuntimeFiles.existsNoFollow(store.currentRoot)) {
                Os.rename(store.currentRoot.absolutePath, store.backupRoot.absolutePath)
                rootBackedUp = true
            }
            if (RuntimeFiles.existsNoFollow(store.currentManifest)) {
                Os.rename(store.currentManifest.absolutePath, store.backupManifest.absolutePath)
                manifestBackedUp = true
            }
            Os.rename(workspace.stagingRoot.absolutePath, store.currentRoot.absolutePath)
            Os.rename(workspace.stagingManifest.absolutePath, store.currentManifest.absolutePath)
        } catch (error: ErrnoException) {
            rollbackPromotion(rootBackedUp, manifestBackedUp)
            throw RuntimeFailure("RUNTIME_PROMOTION_FAILED", "无法启用新运行时", error)
        }
        cleanupIfPresent(store.backupManifest)
        cleanupIfPresent(store.backupRoot)
    }

    private fun rollbackPromotion(rootBackedUp: Boolean, manifestBackedUp: Boolean) {
        cleanupIfPresent(store.currentManifest)
        cleanupIfPresent(store.currentRoot)
        try {
            if (rootBackedUp && RuntimeFiles.existsNoFollow(store.backupRoot)) {
                Os.rename(store.backupRoot.absolutePath, store.currentRoot.absolutePath)
            }
            if (manifestBackedUp && RuntimeFiles.existsNoFollow(store.backupManifest)) {
                Os.rename(store.backupManifest.absolutePath, store.currentManifest.absolutePath)
            }
        } catch (_: ErrnoException) {
            // Recovery remains confined to the private runtime parent and is retried by reset/install.
        }
    }

    private fun cleanupWorkspace(workspace: Workspace, preserveArchive: Boolean = false) {
        if (!preserveArchive) cleanupIfPresent(workspace.archivePart)
        cleanupIfPresent(workspace.stagingManifest)
        cleanupIfPresent(workspace.stagingRoot)
    }

    private fun cleanTransientWorkspaces() {
        val children = store.runtimeParent.listFiles() ?: return
        for (child in children) {
            val name = child.name
            if (
                (name.startsWith("staging-") && UUID_SUFFIX.matches(name.removePrefix("staging-"))) ||
                (name.startsWith("manifest-") && name.endsWith(".json") && UUID_SUFFIX.matches(name.removePrefix("manifest-").removeSuffix(".json"))) ||
                (name.startsWith("download-") && name.endsWith(".part") && UUID_SUFFIX.matches(name.removePrefix("download-").removeSuffix(".part")))
            ) {
                cleanupIfPresent(child)
            }
        }
    }

    private fun cleanResumeFilesExcept(keepName: String?) {
        val children = store.runtimeParent.listFiles() ?: return
        for (child in children) {
            val name = child.name
            if (RESUME_FILE.matches(name) && name != keepName) cleanupIfPresent(child)
        }
    }

    private fun cleanupIfPresent(target: File) {
        if (!RuntimeFiles.existsNoFollow(target)) return
        RuntimeFiles.deleteTreeNoFollow(target, store.runtimeParent)
    }

    private fun checkCancellation() {
        if (isCancelled()) throw RuntimeFailure("INSTALL_CANCELLED", "运行时安装已取消")
    }

    private fun isCancelled(): Boolean = cancellationRequested.get() || externalCancellation()

    companion object {
        private val UUID_SUFFIX = Regex("^[a-f0-9-]{36}$")
        private val RESUME_FILE = Regex("^rootfs-[a-f0-9]{64}\\.part$")
        private const val BUFFER_SIZE = 64 * 1024
    }
}
