package io.deepseekharness.mobile.runtime

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.nio.file.Files
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
            workspace = createWorkspace()
            checkCancellation()

            status.update(RuntimePhase.DOWNLOADING, downloaded = 0, total = 0)
            val manifestBytes = http.downloadBytes(
                source.manifestUrl,
                source.manifestSha256,
                RuntimeLimits.MAX_MANIFEST_BYTES,
            )
            checkCancellation()
            val manifest = RuntimeManifest.parse(manifestBytes, source.manifestUrl.host)
            status.update(RuntimePhase.DOWNLOADING, downloaded = 0, total = manifest.rootfs.compressedBytes)

            http.downloadFile(
                manifest.rootfs.url,
                workspace.archivePart,
                manifest.rootfs.compressedBytes,
                manifest.rootfs.sha256,
            ) { downloaded, total ->
                checkCancellation()
                status.update(RuntimePhase.DOWNLOADING, downloaded = downloaded, total = total)
            }
            checkCancellation()
            status.update(
                RuntimePhase.VERIFYING,
                downloaded = manifest.rootfs.compressedBytes,
                total = manifest.rootfs.compressedBytes,
            )
            status.update(
                RuntimePhase.EXTRACTING,
                downloaded = manifest.rootfs.compressedBytes,
                total = manifest.rootfs.compressedBytes,
            )
            extractor.extract(
                workspace.archivePart,
                workspace.stagingRoot,
                manifest.rootfs.extractedBytes,
                ::isCancelled,
            )
            checkCancellation()
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
            workspace?.let(::cleanupWorkspace)
            val failure = if (isCancelled()) {
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
            cleanAbandonedWorkspaces()
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

    private fun createWorkspace(): Workspace {
        if (!RuntimeFiles.existsNoFollow(store.runtimeParent)) {
            Files.createDirectory(store.runtimeParent.toPath())
        } else if (!RuntimeFiles.isDirectoryNoFollow(store.runtimeParent)) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "运行时父路径不是目录")
        }
        recoverInterruptedPromotion()
        cleanAbandonedWorkspaces()

        val nonce = UUID.randomUUID().toString()
        val stagingRoot = File(store.runtimeParent, "staging-$nonce")
        val stagingManifest = File(store.runtimeParent, "manifest-$nonce.json")
        val archivePart = File(store.runtimeParent, "download-$nonce.part")
        // The extractor itself creates the root with CREATE_NEW-style directory semantics.
        return Workspace(stagingRoot, stagingManifest, archivePart)
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

    private fun cleanupWorkspace(workspace: Workspace) {
        cleanupIfPresent(workspace.archivePart)
        cleanupIfPresent(workspace.stagingManifest)
        cleanupIfPresent(workspace.stagingRoot)
    }

    private fun cleanAbandonedWorkspaces() {
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
    }
}
