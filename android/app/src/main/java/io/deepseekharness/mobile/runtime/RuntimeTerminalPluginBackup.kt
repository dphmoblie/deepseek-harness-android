package io.deepseekharness.mobile.runtime

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Preserves third-party packages installed through the guest terminal.
 *
 * The regular runtime preserve list deliberately excludes `profiles`: most entries there are
 * links into the old rootfs. This helper moves only non-runtime package directories (and a
 * symlink's in-root target), then records the package names needed to merge the old profile.
 */
internal object RuntimeTerminalPluginBackup {
    private const val PREFIX = "terminal-plugin-backup-"
    private const val PROFILE = "root/.dsh/profiles/web/package.json"
    private const val WEB_MODULES = "root/.dsh/profiles/web/node_modules"
    private const val FALLBACK_MODULES = "root/.dsh/profiles/node_modules"
    private const val SNAPSHOT = "profile.json"
    private const val MAX_PACKAGES = 256
    private const val MAX_PACKAGE_NAME = 214
    private const val MAX_PROFILE_BYTES = 1L * 1024 * 1024
    private val PACKAGE_NAME = Regex("^(?:@[a-z0-9][a-z0-9._-]*\\/[a-z0-9][a-z0-9._-]*|[a-z0-9][a-z0-9._-]*)$")
    private val UUID_SUFFIX = Regex("^[a-f0-9-]{36}$")

    data class Handle internal constructor(val directory: File)

    private data class PackageEntry(val relative: String, val name: String, val targetRelative: String? = null)

    fun backup(runtimeParent: File, sourceRoot: File): Handle? {
        if (!RuntimeFiles.isDirectoryNoFollow(sourceRoot)) return null
        val candidates = mutableListOf<PackageEntry>()
        collectCandidates(sourceRoot, WEB_MODULES, candidates)
        collectCandidates(sourceRoot, FALLBACK_MODULES, candidates)
        if (candidates.isEmpty()) return null

        val directory = File(runtimeParent, PREFIX + UUID.randomUUID().toString())
        ensureDirectory(directory)
        val moved = mutableListOf<PackageEntry>()
        val movedTargets = mutableSetOf<String>()
        try {
            for (entry in candidates) {
                val source = File(sourceRoot, entry.relative)
                val target = File(directory, entry.relative)
                ensureInside(directory, target)
                ensureDirectory(target.parentFile ?: throw failure("插件备份目录无效"))
                if (!RuntimeFiles.existsNoFollow(source)) continue
                entry.targetRelative?.let { targetRelative ->
                    if (movedTargets.add(targetRelative)) {
                        val targetSource = File(sourceRoot, targetRelative)
                        val targetBackup = File(directory, targetRelative)
                        ensureInside(sourceRoot, targetSource)
                        ensureInside(directory, targetBackup)
                        if (!RuntimeFiles.isDirectoryNoFollow(targetSource)) throw failure("终端插件目标路径无效")
                        ensureDirectory(targetBackup.parentFile ?: throw failure("插件备份目录无效"))
                        Os.rename(targetSource.absolutePath, targetBackup.absolutePath)
                    }
                } ?: if (!RuntimeFiles.isDirectoryNoFollow(source)) throw failure("终端插件路径类型已改变")
                Os.rename(source.absolutePath, target.absolutePath)
                moved += entry
            }
            writeSnapshot(directory, sourceRoot, moved)
            return Handle(directory)
        } catch (error: Throwable) {
            // A failed backup must not turn into a silent loss.  Best-effort rollback leaves the
            // backup directory in place if a move cannot be undone, so recovery can retry it.
            moved.asReversed().forEach { entry ->
                runCatching {
                    val source = File(directory, entry.relative)
                    val target = File(sourceRoot, entry.relative)
                    if (RuntimeFiles.existsNoFollow(source) && !RuntimeFiles.existsNoFollow(target)) {
                        ensureDirectory(target.parentFile ?: return@runCatching)
                        Os.rename(source.absolutePath, target.absolutePath)
                    }
                    entry.targetRelative?.let { targetRelative ->
                        val targetSource = File(directory, targetRelative)
                        val targetBackup = File(sourceRoot, targetRelative)
                        if (RuntimeFiles.existsNoFollow(targetSource) && !RuntimeFiles.existsNoFollow(targetBackup)) {
                            ensureDirectory(targetBackup.parentFile ?: return@runCatching)
                            Os.rename(targetSource.absolutePath, targetBackup.absolutePath)
                        }
                    }
                }
            }
            throw error as? RuntimeFailure ?: failure("无法备份终端插件", error)
        }
    }

    /** Restores one backup.  A conflict is reported without replacing the new runtime's files. */
    fun restore(handle: Handle, targetRoot: File, strict: Boolean): Int {
        val directory = handle.directory
        if (!isBackupDirectoryName(directory.name) || !RuntimeFiles.isDirectoryNoFollow(directory)) return 0
        val entries = listBackupPackages(directory)
        if (entries.isEmpty()) {
            removeQuietly(directory)
            return 0
        }
        val moved = mutableListOf<PackageEntry>()
        val movedTargets = mutableSetOf<String>()
        try {
            for (entry in entries) {
                val source = File(directory, entry.relative)
                val target = File(targetRoot, entry.relative)
                ensureInside(directory, source)
                ensureInside(targetRoot, target)
                if (RuntimeFiles.existsNoFollow(target)) throw failure("终端插件与新运行时冲突：${entry.name}")
                ensureDirectory(target.parentFile ?: throw failure("插件回填路径无效"))
                entry.targetRelative?.let { targetRelative ->
                    if (movedTargets.add(targetRelative)) {
                        val targetSource = File(directory, targetRelative)
                        val targetRestore = File(targetRoot, targetRelative)
                        ensureInside(directory, targetSource)
                        ensureInside(targetRoot, targetRestore)
                        if (RuntimeFiles.existsNoFollow(targetRestore)) throw failure("终端插件依赖与新运行时冲突：${entry.name}")
                        ensureDirectory(targetRestore.parentFile ?: throw failure("插件回填路径无效"))
                        Os.rename(targetSource.absolutePath, targetRestore.absolutePath)
                    }
                }
                Os.rename(source.absolutePath, target.absolutePath)
                moved += entry
            }
            mergeProfile(directory, targetRoot, moved.map { it.name }.distinct())
            removeQuietly(directory)
            return moved.size
        } catch (error: Throwable) {
            moved.asReversed().forEach { entry ->
                runCatching {
                    val source = File(targetRoot, entry.relative)
                    val target = File(directory, entry.relative)
                    if (RuntimeFiles.existsNoFollow(source) && !RuntimeFiles.existsNoFollow(target)) {
                        ensureDirectory(target.parentFile ?: return@runCatching)
                        Os.rename(source.absolutePath, target.absolutePath)
                    }
                    entry.targetRelative?.let { targetRelative ->
                        val targetSource = File(targetRoot, targetRelative)
                        val targetBackup = File(directory, targetRelative)
                        if (RuntimeFiles.existsNoFollow(targetSource) && !RuntimeFiles.existsNoFollow(targetBackup)) {
                            ensureDirectory(targetBackup.parentFile ?: return@runCatching)
                            Os.rename(targetSource.absolutePath, targetBackup.absolutePath)
                        }
                    }
                }
            }
            if (strict) throw error as? RuntimeFailure ?: failure("无法恢复终端插件", error)
            return 0
        }
    }

    fun pending(runtimeParent: File): List<Handle> =
        (runtimeParent.listFiles() ?: emptyArray())
            .filter { isBackupDirectoryName(it.name) && RuntimeFiles.isDirectoryNoFollow(it) }
            .sortedBy { it.name }
            .map(::Handle)

    fun isBackupDirectoryName(name: String): Boolean =
        name.startsWith(PREFIX) && UUID_SUFFIX.matches(name.removePrefix(PREFIX))

    /** Pure name gate used by tests and by the filesystem scanner. */
    internal fun isAllowedUserPackageName(name: String): Boolean =
        name.length <= MAX_PACKAGE_NAME && PACKAGE_NAME.matches(name) && !isOfficial(name)

    fun remove(handle: Handle) = removeQuietly(handle.directory)

    private fun collectCandidates(root: File, modulesRelative: String, output: MutableList<PackageEntry>) {
        if (output.size >= MAX_PACKAGES) return
        val modules = File(root, modulesRelative)
        if (!RuntimeFiles.isDirectoryNoFollow(modules)) return
        val items = modules.listFiles() ?: return
        for (item in items.sortedBy { it.name }) {
            if (output.size >= MAX_PACKAGES || item.name == ".bin" || item.name.startsWith(".")) continue
            if (item.name.startsWith("@")) {
                if (!RuntimeFiles.isDirectoryNoFollow(item) || item.name.length > 64) continue
                val children = item.listFiles() ?: continue
                for (child in children.sortedBy { it.name }) {
                    val name = item.name + "/" + child.name
                    packageEntry(root, modulesRelative, name, child)?.let(output::add)
                }
            } else {
                packageEntry(root, modulesRelative, item.name, item)?.let(output::add)
            }
        }
    }

    private fun packageEntry(root: File, modulesRelative: String, name: String, file: File): PackageEntry? {
        if (!isAllowedUserPackageName(name)) return null
        if (RuntimeFiles.isDirectoryNoFollow(file)) return PackageEntry("$modulesRelative/$name", name)
        if (!Files.isSymbolicLink(file.toPath())) return null
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val targetCanonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!targetCanonical.toPath().startsWith(rootCanonical.toPath())) return null
        if (!RuntimeFiles.isDirectoryNoFollow(targetCanonical)) return null
        val targetRelative = rootCanonical.toPath().relativize(targetCanonical.toPath()).toString().replace(File.separatorChar, '/')
        if (targetRelative.isEmpty() || targetRelative.startsWith("../") || targetRelative == "..") return null
        // App-managed plugins already live in the separately preserved plugin-manager area;
        // moving their links here would create a second physical copy and split module identity.
        if (targetRelative == "root/.dsh-mobile" || targetRelative.startsWith("root/.dsh-mobile/")) return null
        return PackageEntry("$modulesRelative/$name", name, targetRelative)
    }

    private fun isOfficial(name: String): Boolean =
        name.startsWith("@deepseek-ai/") || name == "@deepseek-harness/dsh-mobile-shizuku" ||
            name in setOf("react", "react-dom", "cordis")

    private fun listBackupPackages(directory: File): List<PackageEntry> {
        val snapshot = File(directory, SNAPSHOT)
        if (!RuntimeFiles.existsNoFollow(snapshot) || !isRegularFile(snapshot) || snapshot.length() > MAX_PROFILE_BYTES) return emptyList()
        val result = mutableListOf<PackageEntry>()
        val entries = runCatching { JSONObject(snapshot.readText(Charsets.UTF_8)).optJSONArray("entries") }.getOrNull() ?: return emptyList()
        for (index in 0 until entries.length()) {
            val row = entries.optJSONObject(index) ?: continue
            val name = row.optString("name")
            val relative = row.optString("relative")
            val target = row.optString("targetRelative").takeIf { it.isNotEmpty() }
            if (!isAllowedUserPackageName(name) || !relative.startsWith("root/.dsh/profiles/") || !safeRelative(relative)) continue
            if (target != null && !safeRelative(target)) continue
            result += PackageEntry(relative, name, target)
            if (result.size >= MAX_PACKAGES) break
        }
        return result
    }

    private fun writeSnapshot(directory: File, sourceRoot: File, entries: List<PackageEntry>) {
        val names = entries.map { it.name }.distinct()
        val savedEntries = JSONArray()
        entries.forEach { entry ->
            savedEntries.put(JSONObject().put("name", entry.name).put("relative", entry.relative).apply {
                entry.targetRelative?.let { put("targetRelative", it) }
            })
        }
        val value = JSONObject().put("version", 1).put("bundles", JSONArray(names)).put("entries", savedEntries)
        val profile = File(sourceRoot, PROFILE)
        if (RuntimeFiles.existsNoFollow(profile) && isRegularFile(profile) && profile.length() <= MAX_PROFILE_BYTES) {
            runCatching {
                val old = JSONObject(profile.readText(Charsets.UTF_8))
                val dependencies = JSONObject()
                old.optJSONObject("dependencies")?.let { source ->
                    names.forEach { name -> if (source.has(name)) dependencies.put(name, source.opt(name)) }
                }
                value.put("dependencies", dependencies)
            }
        }
        writeJson(File(directory, SNAPSHOT), value)
    }

    private fun mergeProfile(directory: File, targetRoot: File, names: List<String>) {
        val profile = File(targetRoot, PROFILE)
        val snapshot = File(directory, SNAPSHOT)
        ensureInside(targetRoot, profile)
        val value = if (RuntimeFiles.existsNoFollow(profile)) {
            if (!isRegularFile(profile)) throw failure("运行时插件清单路径无效")
            if (profile.length() > MAX_PROFILE_BYTES) throw failure("运行时插件清单过大")
            runCatching { JSONObject(profile.readText(Charsets.UTF_8)) }.getOrElse { throw failure("运行时插件清单无效", it) }
        } else JSONObject()
        val bundles = value.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")
            ?: JSONArray()
        val present = mutableSetOf<String>()
        for (index in 0 until bundles.length()) bundles.optString(index).takeIf { it.isNotEmpty() }?.let(present::add)
        val saved = runCatching { JSONObject(snapshot.readText(Charsets.UTF_8)) }.getOrElse { JSONObject() }
        val savedBundles = saved.optJSONArray("bundles") ?: JSONArray()
        val merged = JSONArray()
        for (index in 0 until bundles.length()) merged.put(bundles.opt(index))
        for (index in 0 until savedBundles.length()) {
            val name = savedBundles.optString(index)
            if (PACKAGE_NAME.matches(name) && !isOfficial(name) && present.add(name)) merged.put(name)
        }
        val dsh = value.optJSONObject("dsh") ?: JSONObject().also { value.put("dsh", it) }
        val profileObject = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
        profileObject.put("bundles", merged)
        val dependencies = value.optJSONObject("dependencies") ?: JSONObject().also { value.put("dependencies", it) }
        val savedDependencies = saved.optJSONObject("dependencies")
        if (savedDependencies != null) {
            names.forEach { name -> if (!dependencies.has(name) && savedDependencies.has(name)) dependencies.put(name, savedDependencies.opt(name)) }
        }
        writeJson(profile, value)
    }

    private fun writeJson(file: File, value: JSONObject) {
        val parent = file.parentFile ?: throw failure("插件清单路径无效")
        ensureDirectory(parent)
        if (RuntimeFiles.existsNoFollow(file) && !isRegularFile(file)) throw failure("插件清单路径无效")
        val temporary = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                Channels.newOutputStream(channel).use { output ->
                    output.write((value.toString(2) + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                channel.force(true)
            }
            Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            throw failure("无法保存终端插件清单", error)
        }
    }

    private fun ensureDirectory(directory: File) {
        if (RuntimeFiles.existsNoFollow(directory)) {
            if (!RuntimeFiles.isDirectoryNoFollow(directory)) throw failure("插件备份路径无效")
            return
        }
        // Files.createDirectories follows existing ancestors.  Validate every one first so a
        // malicious profile cannot redirect a restore outside the guest root through a symlink.
        var ancestor: File? = directory.parentFile
        while (ancestor != null && !RuntimeFiles.existsNoFollow(ancestor)) ancestor = ancestor.parentFile
        while (ancestor != null) {
            if (!RuntimeFiles.isDirectoryNoFollow(ancestor)) throw failure("插件备份路径无效")
            ancestor = ancestor.parentFile
        }
        try { Files.createDirectories(directory.toPath()) } catch (error: IOException) { throw failure("无法创建插件备份目录", error) }
    }

    private fun ensureInside(parent: File, child: File) {
        val base = parent.toPath().toAbsolutePath().normalize()
        val path = child.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(base) || path == base) throw failure("插件备份路径越界")
        var cursor = child.parentFile
        while (cursor != null && cursor.absolutePath != parent.absolutePath) {
            if (RuntimeFiles.existsNoFollow(cursor) && !RuntimeFiles.isDirectoryNoFollow(cursor)) throw failure("插件备份路径无效")
            cursor = cursor.parentFile
        }
    }

    private fun isRegularFile(file: File): Boolean = try {
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) { false }

    private fun safeRelative(value: String): Boolean =
        value.isNotEmpty() && !value.startsWith("/") && !value.contains('\\') &&
            value.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun removeQuietly(directory: File) {
        runCatching { RuntimeFiles.deleteTreeNoFollowFallback(directory, directory.parentFile ?: return) }
    }

    private fun failure(message: String, cause: Throwable? = null): RuntimeFailure =
        RuntimeFailure("RUNTIME_PLUGIN_BACKUP_FAILED", message, cause)
}
