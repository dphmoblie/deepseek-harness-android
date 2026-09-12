package io.deepseekharness.mobile.runtime

import android.system.ErrnoException
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
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
            // 用户显式清空运行时：中断更新遗留的用户数据暂存目录一并清理
            // （与下面删除 currentRoot/backupRoot 的语义一致）。
            cleanPreservedWorkspaces()
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
            val bytes = try {
                store.openBundledManifest().use {
                    readBounded(it, RuntimeLimits.MAX_MANIFEST_BYTES)
                }
            } catch (failure: RuntimeFailure) {
                if (failure.code == "BUNDLED_RUNTIME_MISSING") {
                    throw RuntimeFailure(
                        "RUNTIME_SOURCE_NEEDED",
                        "本 APK 未内置运行时：请在设置页填写 manifest 地址与 SHA-256（两者必须成对）后重试安装",
                        failure,
                    )
                }
                throw failure
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
            if (error is RuntimeFailure) {
                if (error.code == "BUNDLED_RUNTIME_MISSING") {
                    throw RuntimeFailure(
                        "RUNTIME_SOURCE_NEEDED",
                        "本 APK 未内置运行时：请在设置页填写 manifest 地址与 SHA-256（两者必须成对）后重试安装",
                        error,
                    )
                }
                throw error
            }
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
        // 中断的提升可能把用户数据留在 preserve-* 暂存目录里：这些目录不具备"暂存文件"语义，
        // 绝不能被 cleanTransientWorkspaces 之类清理掉，只能回填或原样保留。
        val preservedRoots = preservedWorkspaces()
        val currentRoot = RuntimeFiles.existsNoFollow(store.currentRoot)
        val currentManifest = RuntimeFiles.existsNoFollow(store.currentManifest)
        val backupRoot = RuntimeFiles.existsNoFollow(store.backupRoot)
        val backupManifest = RuntimeFiles.existsNoFollow(store.backupManifest)
        try {
            when {
                currentRoot && currentManifest -> {
                    // 存在遗留 preserve-* 时不得删除 backupRoot（实现约定 5）：回填可能尚未完成，
                    // 旧根目录是排查与人工取回用户数据的最后依据。
                    if (preservedRoots.isEmpty()) {
                        cleanupIfPresent(store.backupManifest)
                        cleanupIfPresent(store.backupRoot)
                    }
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
        // 回填必须在上面的分支把 backupRoot 恢复成 currentRoot 之后进行。
        restorePreservedWorkspaces(preservedRoots)
    }

    /**
     * 把中断的提升留在 `preserve-*` 里的用户数据放回当前根目录。
     *
     * 冲突判定与提升时完全一致：能放回的全部放回，放不回的原样留在暂存目录里，
     * 并以受控错误码失败——绝不静默丢在一边，也绝不覆盖新运行时里的同名项。
     * 当前根目录不可用时（例如根目录已被清理）不在此处理：接下来的提升会把新根目录
     * 建好，再由提升流程接手这些暂存目录。
     */
    private fun restorePreservedWorkspaces(preservedRoots: List<File>) {
        if (preservedRoots.isEmpty()) return
        if (!RuntimeFiles.isDirectoryNoFollow(store.currentRoot)) return
        for (preservedRoot in preservedRoots) {
            val unplaced = restorePreservedDirectory(preservedRoot, store.currentRoot, strict = true)
            if (unplaced.isNotEmpty()) {
                throw RuntimeFailure("RUNTIME_PRESERVE_FAILED", unpreservedMessage(unplaced))
            }
        }
    }

    /**
     * 提升暂存根目录为新运行时，并保证访客用户数据跨升级存活。
     *
     * 顺序（见 `docs/运行时更新与数据保留.md` 的实现约定 2）：
     * 1. 备份旧根目录**之前**，先把白名单用户数据移出到 `preserve-<uuid>`；
     * 2. 备份旧根目录与清单，提升新根目录与清单；
     * 3. 把用户数据回填到新根目录的同一相对路径；**只有全部回填成功**才删除 `backupRoot`。
     */
    private fun promoteStaging(workspace: Workspace) {
        val preservedRoot = File(
            store.runtimeParent,
            RuntimePreservePolicy.preserveDirectoryName(UUID.randomUUID().toString()),
        )
        val moved = mutableListOf<String>()
        val progress = RuntimePreservePolicy.PromotionProgress()

        // 阶段一：移出用户数据。必须在旧根目录改名之前完成，否则无法再区分用户数据与运行时产物。
        try {
            movePreservedItems(store.currentRoot, preservedRoot, moved)
        } catch (error: Throwable) {
            // 半途失败：把已移出的数据放回旧根目录；放不回的原样留在 preserve-*，绝不删除。
            try {
                restorePreservedDirectory(preservedRoot, store.currentRoot, strict = false)
            } catch (_: Exception) {
                // 放回失败时数据仍在 preserve-* 目录里，比让它随失败一起消失安全得多。
            }
            throw error as? RuntimeFailure
                ?: RuntimeFailure("RUNTIME_PRESERVE_FAILED", "无法移出旧运行时中的用户数据", error)
        }

        // 阶段二：备份旧运行时，提升新运行时。
        try {
            if (RuntimeFiles.existsNoFollow(store.currentRoot)) {
                Os.rename(store.currentRoot.absolutePath, store.backupRoot.absolutePath)
                progress.rootBackedUp = true
            }
            if (RuntimeFiles.existsNoFollow(store.currentManifest)) {
                Os.rename(store.currentManifest.absolutePath, store.backupManifest.absolutePath)
                progress.manifestBackedUp = true
            }
            Os.rename(workspace.stagingRoot.absolutePath, store.currentRoot.absolutePath)
            progress.rootPromoted = true
            Os.rename(workspace.stagingManifest.absolutePath, store.currentManifest.absolutePath)
            progress.manifestPromoted = true
        } catch (error: Throwable) {
            rollbackPromotion(progress, preservedRoot, moved)
            throw error as? RuntimeFailure
                ?: RuntimeFailure("RUNTIME_PROMOTION_FAILED", "无法启用新运行时", error)
        }

        // 阶段三：回填用户数据。真冲突时抛受控错误码，并保留 backupRoot 供排查或人工取回。
        val unplaced = mutableListOf<String>()
        unplaced += restorePreservedDirectory(preservedRoot, store.currentRoot, strict = true)
        // 中断遗留、且恢复流程当时没有可回填根目录的暂存目录，由新根目录一并接手。
        for (leftover in preservedWorkspaces()) {
            if (leftover.absolutePath == preservedRoot.absolutePath) continue
            unplaced += restorePreservedDirectory(leftover, store.currentRoot, strict = true)
        }
        if (unplaced.isNotEmpty()) {
            throw RuntimeFailure("RUNTIME_PRESERVE_FAILED", unpreservedMessage(unplaced))
        }
        cleanupIfPresent(store.backupManifest)
        cleanupIfPresent(store.backupRoot)
    }

    /**
     * 回滚到提升之前的旧运行时，并把已移出的用户数据放回恢复出来的旧根目录
     * （实现约定 3：否则「回滚」本身就在丢数据）。
     *
     * 该做什么由 [RuntimePreservePolicy.rollbackPlan] 这个纯逻辑函数决定；
     * 本方法只负责把动作落到文件系统上，并且**不得抛出**：它是在提升失败的 catch 分支里调用的，
     * 抛出会掩盖真正的失败原因。
     */
    private fun rollbackPromotion(
        progress: RuntimePreservePolicy.PromotionProgress,
        preservedRoot: File,
        moved: List<String>,
    ) {
        val plan = RuntimePreservePolicy.rollbackPlan(progress, moved.size)
        try {
            if (plan.removePromotedRoot) cleanupIfPresent(store.currentRoot)
            if (plan.removePromotedManifest) cleanupIfPresent(store.currentManifest)
        } catch (_: Exception) {
            // 清理刚提升上来的新运行时失败：仍然继续尝试恢复旧运行时。
        }
        try {
            if (plan.restoreBackedUpRoot && RuntimeFiles.existsNoFollow(store.backupRoot)) {
                Os.rename(store.backupRoot.absolutePath, store.currentRoot.absolutePath)
            }
            if (plan.restoreBackedUpManifest && RuntimeFiles.existsNoFollow(store.backupManifest)) {
                Os.rename(store.backupManifest.absolutePath, store.currentManifest.absolutePath)
            }
        } catch (_: ErrnoException) {
            // Recovery remains confined to the private runtime parent and is retried by reset/install.
        }
        // 旧根目录恢复成功后，把用户数据放回它们原来的位置。
        if (!plan.restorePreservedItems) return
        if (!RuntimeFiles.isDirectoryNoFollow(store.currentRoot)) return
        try {
            restorePreservedDirectory(preservedRoot, store.currentRoot, strict = false)
        } catch (_: Exception) {
            // 回滚已经尽力：暂存目录留在原处，用户数据不会消失，由下次安装或重置处理。
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
        // 注意：`preserve-*` 目录**不在此列**。它装的是用户数据，只允许在用户显式重置时清理。
    }

    /** 运行时父目录下遗留的用户数据暂存目录（中断的提升留下的）。 */
    private fun preservedWorkspaces(): List<File> =
        (store.runtimeParent.listFiles() ?: emptyArray())
            .filter { RuntimePreservePolicy.isPreserveDirectoryName(it.name) }
            .sortedBy { it.name }

    /**
     * 清理用户数据暂存目录。只允许在用户显式重置（`resetWorkspace`）时调用：
     * `preserve-*` 里装的是用户数据，不属于可以随时丢弃的暂存文件。
     */
    private fun cleanPreservedWorkspaces() {
        for (directory in preservedWorkspaces()) cleanupIfPresent(directory)
    }

    /**
     * 把旧根目录里的白名单用户数据移出到 `preserve-*` 暂存目录。
     * 每条移动成功后才记入 `moved`：半途失败时调用方据此把已移出的数据放回原处。
     */
    private fun movePreservedItems(sourceRoot: File, preservedRoot: File, moved: MutableList<String>) {
        if (!RuntimeFiles.isDirectoryNoFollow(sourceRoot)) return
        for (name in RuntimePreservePolicy.preservedNames()) {
            val relative = RuntimePreservePolicy.guestRelativePath(name) ?: continue
            val source = File(sourceRoot, relative)
            if (!RuntimeFiles.existsNoFollow(source)) continue
            ensureDirectory(preservedRoot, createParents = false, message = "无法创建用户数据暂存目录")
            renameUserData(source, File(preservedRoot, name))
            moved += name
        }
    }

    /** 暂存目录里当前实际存在的白名单条目：不依赖持久化台账，可跨进程中断恢复。 */
    private fun pendingPreservedNames(preservedRoot: File): List<String> =
        RuntimePreservePolicy.preservedNames().filter { RuntimeFiles.existsNoFollow(File(preservedRoot, it)) }

    /**
     * 把单个 `preserve-*` 目录里的用户数据回填到目标根目录，返回**未回填**的条目名。
     * 全部回填成功时顺手删掉已经空掉的暂存目录。
     */
    private fun restorePreservedDirectory(
        preservedRoot: File,
        targetRoot: File,
        strict: Boolean,
    ): List<String> {
        val names = pendingPreservedNames(preservedRoot)
        if (names.isEmpty()) {
            removePreservedDirectoryQuietly(preservedRoot)
            return emptyList()
        }
        // 目标根目录不可用：整个暂存目录原样保留，由调用方决定后续处理。
        if (!RuntimeFiles.isDirectoryNoFollow(targetRoot)) return names
        val unplaced = restorePreservedItems(preservedRoot, targetRoot, names, strict)
        if (unplaced.isEmpty()) removePreservedDirectoryQuietly(preservedRoot)
        return unplaced
    }

    /**
     * 按实现约定 4 把暂存目录里的条目回填到目标根目录，返回没能回填的条目名。
     * `strict = false` 时把所有失败折算成「未回填」，供回滚等不允许抛出的路径使用。
     */
    private fun restorePreservedItems(
        preservedRoot: File,
        targetRoot: File,
        names: List<String>,
        strict: Boolean,
    ): List<String> {
        val unplaced = mutableListOf<String>()
        for (name in names) {
            val relative = RuntimePreservePolicy.guestRelativePath(name) ?: continue
            val source = File(preservedRoot, name)
            if (!RuntimeFiles.existsNoFollow(source)) continue
            val target = File(targetRoot, relative)
            try {
                when (classifyRestoreTarget(target)) {
                    RuntimePreservePolicy.RestoreDecision.MOVE_IN -> placePreservedItem(source, target)
                    RuntimePreservePolicy.RestoreDecision.REPLACE_EMPTY_DIRECTORY -> {
                        // 新 rootfs 预建的同名空目录（例如空的 sessions、plugins 目录）：
                        // 删掉空壳后再移入，否则每次正常更新都会被判成冲突而失败。
                        removeEmptyShell(target)
                        placePreservedItem(source, target)
                    }
                    RuntimePreservePolicy.RestoreDecision.CONFLICT -> unplaced += name
                }
            } catch (error: Exception) {
                if (strict) throw error
                unplaced += name
            }
        }
        return unplaced
    }

    /** 取样目标同名项的真实形态，交给纯逻辑策略判定（实现约定 4）。 */
    private fun classifyRestoreTarget(target: File): RuntimePreservePolicy.RestoreDecision {
        if (!RuntimeFiles.existsNoFollow(target)) return RuntimePreservePolicy.RestoreDecision.MOVE_IN
        val directory = RuntimeFiles.isDirectoryNoFollow(target)
        // 读不到目录内容时按「有内容」处理：宁可判成冲突，也不覆盖用户数据。
        val childCount = if (directory) (target.listFiles()?.size ?: 1) else 0
        return RuntimePreservePolicy.decideRestore(present = true, isDirectory = directory, childCount = childCount)
    }

    /**
     * 把单条用户数据放进新根目录的同一相对位置。
     * 新 rootfs 可能没有预建 `root/.dsh`，因此先补齐上级目录。
     */
    private fun placePreservedItem(source: File, target: File) {
        val parent = target.parentFile
            ?: throw RuntimeFailure("RUNTIME_PRESERVE_FAILED", "用户数据回填路径无效")
        ensureDirectory(parent, createParents = true, message = "无法创建用户数据回填目录")
        renameUserData(source, target)
    }

    /**
     * 删除新 rootfs 预建的同名空目录。
     * 用单层删除而不是递归删除：即使目录在检查之后变成非空，也只会失败而不会误删内容。
     */
    private fun removeEmptyShell(shell: File) {
        try {
            Files.deleteIfExists(shell.toPath())
        } catch (error: IOException) {
            throw RuntimeFailure("RUNTIME_PRESERVE_FAILED", "无法清理新运行时中的同名空目录", error)
        }
    }

    /** 确保目录存在；失败一律映射为受控错误码。 */
    private fun ensureDirectory(directory: File, createParents: Boolean, message: String) {
        if (RuntimeFiles.existsNoFollow(directory)) {
            if (!RuntimeFiles.isDirectoryNoFollow(directory)) {
                throw RuntimeFailure("RUNTIME_PRESERVE_FAILED", "$message：路径不是目录")
            }
            return
        }
        try {
            if (createParents) Files.createDirectories(directory.toPath())
            else Files.createDirectory(directory.toPath())
        } catch (error: IOException) {
            throw RuntimeFailure("RUNTIME_PRESERVE_FAILED", message, error)
        }
    }

    /** 私有运行时父目录内的原子移动；跨设备或其它失败统一映射为受控错误码。 */
    private fun renameUserData(source: File, target: File) {
        try {
            Os.rename(source.absolutePath, target.absolutePath)
        } catch (error: ErrnoException) {
            throw RuntimeFailure("RUNTIME_PRESERVE_FAILED", "无法移动运行时用户数据", error)
        }
    }

    /** 删除已经回填干净的暂存目录；删不掉也不影响数据安全，下次恢复流程会再试。 */
    private fun removePreservedDirectoryQuietly(preservedRoot: File) {
        try {
            cleanupIfPresent(preservedRoot)
        } catch (_: RuntimeFailure) {
            // 忽略：目录要么已空，要么留给下次清理。
        }
    }

    /** 未回填清单的可读说明：必须让用户知道数据还在，没有被覆盖也没有被删除。 */
    private fun unpreservedMessage(unplaced: List<String>): String =
        "新运行时中已存在同名数据，未回填：" + unplaced.joinToString("、") +
            "；用户数据与旧运行时备份均已保留，请先处理同名项后重试"

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
