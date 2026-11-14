package io.deepseekharness.mobile.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 「数据保留」的只读状态与两个用户动作：**取回**、**删除**。
 *
 * 一次运行时更新会把会话、插件、工作区文件搬进 `runtimeParent/preserve-<uuid>/`，再合并回新根目录。
 * 搬不动的项（与新镜像里的同名文件冲突）会留在暂存目录里；这个类负责把它们**呈现出来**并让用户
 * 自己决定去向——没有它，冲突项就是「文件没了」。
 *
 * 设计约束：
 * - 只碰 `runtimeParent` 下符合 `preserve-<uuid>` 命名的目录，以及工作区 `root/1` 下的
 *   `recovered/<id>/`；不跟随符号链接、不覆盖任何既有文件；
 * - 扫描与复制都有上界（目录数、条目数、字节数），避免一次误点就把整盘读一遍；
 * - 不依赖 Android API（目录由调用方传入），因此可以用临时目录做 JVM 单测。
 *
 * **旧版本兼容**：老版本装出来的 `preserve-*` 没有 `preserve-report.json`，槽位也只有老的 7 项。
 * 这里一律以**文件系统里实际存在的条目**为准，缺清单就退回默认清单——旧目录不会因为少一份清单
 * 就读不出来，也不会被当成「已处理」删掉。
 */
class PreserveStore(
    private val runtimeParent: File,
    private val currentRoot: File,
) {
    /** 一条保留项：槽位、分类、访客相对路径与实际体积。 */
    data class Item(
        val slot: String,
        val category: String,
        val path: String,
        val bytes: Long,
        val files: Int,
    )

    /** 一个待处理的暂存目录。`report` 只在该目录带清单时出现。 */
    data class Pending(
        val id: String,
        val updatedAtMillis: Long,
        val bytes: Long,
        val items: List<Item>,
        val report: RuntimePreservePolicy.ReportSummary?,
    )

    data class State(val pending: List<Pending>)

    /** 取回结果：落到工作区 `recovered/<id>/` 下的副本。 */
    data class Recovered(
        val id: String,
        val target: String,
        val files: Int,
        val bytes: Long,
        val skippedSymlinks: Int,
    )

    /**
     * 列出待处理的暂存目录。
     *
     * 只读：不移动、不删除、不改写清单。目录按最近修改时间倒序（最近一次更新排在最前）。
     */
    fun state(): State {
        val pending = preserveDirectories()
            .mapNotNull { directory -> readPending(directory) }
        return State(pending)
    }

    /**
     * 把某个暂存目录里的条目**复制**到工作区 `root/1/recovered/<id>/`。
     *
     * 用复制而不是移动：复制失败时暂存目录仍是完整的原件；成功之后由用户显式「删除」释放空间。
     * 同名不覆盖——目标已存在时改名为 `<name>-1`、`<name>-2`……，绝不悄悄替换用户的文件。
     */
    fun recover(id: String): Recovered {
        val directory = preserveDirectory(id)
        // 取回要落到工作区里；运行时没装好时连工作区都没有，不能顺手造一个半份根目录。
        if (!Files.isDirectory(currentRoot.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(currentRoot.toPath())) {
            throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "运行时尚未安装，取回的数据没有落脚点")
        }
        val workspace = File(currentRoot, RuntimePreservePolicy.DEFAULT_WORKSPACE_ROOT)
        val target = File(File(workspace, RECOVERED_DIRECTORY), id)
        ensureDirectory(target)
        // UI 列表只展示前 32 项；取回不能复用这个截断结果，否则后面的用户文件会永远留在暂存目录。
        val slots = directory.listFiles()?.asSequence()
            ?.filter { it.name != RuntimePreservePolicy.REPORT_FILE_NAME }
            ?.sortedBy { it.name }
            ?.toList()
            ?: throw RuntimeFailure("PRESERVE_RECOVER_FAILED", "无法读取待处理的数据副本")
        var files = 0
        var bytes = 0L
        var skippedSymlinks = 0
        val budget = RecoveryBudget()
        for (source in slots) {
            val destination = File(target, source.name)
            val result = copyTree(source, destination, budget)
            bytes += result.bytes
            files += result.files
            skippedSymlinks += result.skippedSymlinks
        }
        return Recovered(id, "recovered/$id", files, bytes, skippedSymlinks)
    }

    /**
     * 删除一个暂存目录（用户显式丢弃）。
     *
     * 只删 `preserve-<uuid>` 命名的目录；不跟随符号链接；条目数有界，超出即拒绝而不是半删。
     */
    fun discard(id: String): Boolean {
        val directory = preserveDirectory(id)
        return try {
            val count = countEntries(directory)
            if (count > MAX_DISCARD_ENTRIES) {
                throw RuntimeFailure("PRESERVE_DISCARD_FAILED", "待删除的暂存目录条目过多，已拒绝自动删除")
            }
            deleteTree(directory)
            true
        } catch (error: IOException) {
            throw RuntimeFailure("PRESERVE_DISCARD_FAILED", "无法删除待处理的数据副本", error)
        }
    }

    private fun preserveDirectories(): List<File> =
        (runtimeParent.listFiles() ?: throw RuntimeFailure("PRESERVE_READ_FAILED", "无法读取待处理的数据副本目录"))
            .filter {
                RuntimePreservePolicy.isPreserveDirectoryName(it.name) &&
                    Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(it.toPath())
            }
            .sortedByDescending { it.lastModified() }
            .take(MAX_DIRECTORIES)

    private fun preserveDirectory(id: String): File {
        if (!RuntimePreservePolicy.isPreserveDirectoryName(id)) {
            throw RuntimeFailure("PRESERVE_INVALID_ID", "数据保留目录标识无效")
        }
        val directory = File(runtimeParent, id)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory.toPath())) {
            throw RuntimeFailure("PRESERVE_NOT_FOUND", "没有找到这份待处理的数据副本")
        }
        return directory
    }

    private fun readPending(directory: File): Pending? {
        val reportText = readReport(directory)
        val paths = RuntimePreservePolicy.parseReportPaths(reportText) ?: RuntimePreservePolicy.restoreSlots()
        val items = readItems(directory, paths)
        if (items.isEmpty()) return null
        return Pending(
            id = directory.name,
            updatedAtMillis = directory.lastModified().coerceAtLeast(0),
            bytes = items.sumOf { it.bytes },
            items = items,
            report = RuntimePreservePolicy.parseReportSummary(reportText),
        )
    }

    private fun readReport(directory: File): String? = try {
        val file = File(directory, RuntimePreservePolicy.REPORT_FILE_NAME)
        if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && Files.size(file.toPath()) <= 16 * 1024L) {
            file.readText(Charsets.UTF_8)
        } else null
    } catch (_: Exception) {
        null
    }

    /** 暂存目录里的实际条目（以文件系统为准，不轻信清单）；只读，且有条目上界。 */
    private fun readItems(
        directory: File,
        paths: Map<String, String> = RuntimePreservePolicy.preservedPaths(),
    ): List<Item> {
        val children = directory.listFiles()?.sortedBy { it.name }
            ?: throw RuntimeFailure("PRESERVE_READ_FAILED", "无法读取待处理的数据副本")
        val items = mutableListOf<Item>()
        for (child in children) {
            if (child.name == RuntimePreservePolicy.REPORT_FILE_NAME) continue
            if (!Files.exists(child.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            // 整树保留（`guest-home`）：按 `/root` 下的顶层条目展开，界面才有分类可看，
            // 而不是一个「整个用户空间 1 项」。槽位名带上子路径，取回/删除仍按目录 id 操作。
            if (child.name == RuntimePreservePolicy.GUEST_HOME_SLOT &&
                Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(child.toPath())) {
                val entries = child.listFiles()
                    ?.sortedBy { it.name }
                    ?: throw RuntimeFailure("PRESERVE_READ_FAILED", "无法读取用户数据暂存目录")
                if (entries.size <= MAX_ITEMS_PER_DIRECTORY - items.size &&
                    entries.all { entry -> entry.name.length <= 289 && entry.name.none { it == '\\' || it.isISOControl() } }) {
                    for (entry in entries) {
                        val measure = measureTree(entry)
                        items += Item(
                            slot = "${RuntimePreservePolicy.GUEST_HOME_SLOT}/${entry.name}",
                            category = RuntimePreservePolicy.categoryOfPath(entry.name).wireValue,
                            path = "${RuntimePreservePolicy.GUEST_HOME_ROOT}/${entry.name}",
                            bytes = measure.bytes,
                            files = measure.files,
                        )
                    }
                    continue
                }
                // 条目太多时展示整块目录，不能把前 32 项伪装成这份副本的全部内容。
            }
            val measure = measureTree(child)
            items += Item(
                slot = child.name,
                category = RuntimePreservePolicy.categoryOf(child.name).wireValue,
                path = paths[child.name] ?: "",
                bytes = measure.bytes,
                files = measure.files,
            )
            if (items.size >= MAX_ITEMS_PER_DIRECTORY) break
        }
        return items
    }

    private class Measure(val files: Int, val bytes: Long)

    /** 复制结果：搬了多少文件、多少字节，以及跳过了几个符号链接（不跟随）。 */
    private class CopyResult(val files: Int, val bytes: Long, val skippedSymlinks: Int)

    private class RecoveryBudget {
        var files = 0
        var entries = 0
        var bytes = 0L
        var skippedSymlinks = 0
    }

    /** 目录/文件的体积与文件数；符号链接只计数不跟随，条目数有界。 */
    private fun measureTree(root: File): Measure {
        var files = 0
        var bytes = 0L
        var visited = 0
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty() && visited < MAX_MEASURED_ENTRIES) {
            val current = pending.removeFirst()
            visited += 1
            if (Files.isSymbolicLink(current.toPath())) continue
            if (Files.isDirectory(current.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                for (child in current.listFiles() ?: continue) pending.addLast(child)
                continue
            }
            files += 1
            bytes += try {
                Files.size(current.toPath())
            } catch (_: Exception) {
                0L
            }
        }
        return Measure(files, bytes)
    }

    private fun countEntries(directory: File): Int {
        var count = 0
        val pending = ArrayDeque<Path>()
        pending.addLast(directory.toPath())
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            count += 1
            if (count > MAX_DISCARD_ENTRIES) return count
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) continue
            Files.newDirectoryStream(current).use { children ->
                for (child in children) {
                    if (count + pending.size >= MAX_DISCARD_ENTRIES) return MAX_DISCARD_ENTRIES + 1
                    pending.addLast(child)
                }
            }
        }
        return count
    }

    private fun copyTree(source: File, destination: File, budget: RecoveryBudget): CopyResult {
        var files = 0
        var bytes = 0L
        var skipped = 0
        val pending = ArrayDeque<Pair<File, File>>()
        pending.addLast(source to destination)
        while (pending.isNotEmpty()) {
            budget.entries += 1
            if (budget.entries > MAX_RECOVER_ENTRIES) {
                throw RuntimeFailure("PRESERVE_RECOVER_TOO_LARGE", "待取回的文件数超过上限，请在文件管理里逐个目录取回")
            }
            val (from, to) = pending.removeFirst()
            if (Files.isSymbolicLink(from.toPath())) {
                skipped += 1
                budget.skippedSymlinks += 1
                continue
            }
            if (Files.isDirectory(from.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                ensureDirectory(to)
                Files.newDirectoryStream(from.toPath()).use { children ->
                    for (child in children) {
                        if (budget.entries + pending.size >= MAX_RECOVER_ENTRIES) {
                            throw RuntimeFailure("PRESERVE_RECOVER_TOO_LARGE", "待取回的条目数超过上限，请在文件管理里逐个目录取回")
                        }
                        pending.addLast(child.toFile() to File(to, child.fileName.toString()))
                    }
                }
                continue
            }
            if (!Files.isRegularFile(from.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw RuntimeFailure("PRESERVE_RECOVER_FAILED", "待取回的条目不是普通文件")
            }
            if (budget.files >= MAX_RECOVER_FILES) {
                throw RuntimeFailure("PRESERVE_RECOVER_TOO_LARGE", "待取回的文件数超过上限，请在文件管理里逐个目录取回")
            }
            val target = uniqueTarget(to)
            ensureDirectory(target.parentFile ?: to)
            val copiedBytes = Files.size(from.toPath())
            if (copiedBytes > MAX_RECOVER_BYTES - budget.bytes) {
                throw RuntimeFailure("PRESERVE_RECOVER_TOO_LARGE", "待取回的数据超过上限，请在文件管理里逐个目录取回")
            }
            Files.copy(from.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            files += 1
            bytes += copiedBytes
            budget.files += 1
            budget.bytes += copiedBytes
        }
        return CopyResult(files, bytes, skipped)
    }

    /** 目标已存在时顺延改名：`a.txt` → `a-1.txt`，绝不覆盖。 */
    private fun uniqueTarget(target: File): File {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return target
        val name = target.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        for (index in 1..MAX_RENAME_ATTEMPTS) {
            val candidate = File(target.parentFile, "$stem-$index$suffix")
            if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return candidate
        }
        throw RuntimeFailure("PRESERVE_RECOVER_FAILED", "目标目录里同名文件过多，无法安全取回")
    }

    private fun ensureDirectory(directory: File) {
        val root = currentRoot.toPath().toAbsolutePath().normalize()
        val target = directory.toPath().toAbsolutePath().normalize()
        if (!target.startsWith(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw RuntimeFailure("PRESERVE_RECOVER_FAILED", "取回目标路径不是普通目录")
        }
        var path = root
        for (part in root.relativize(target)) {
            path = path.resolve(part)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw RuntimeFailure("PRESERVE_RECOVER_FAILED", "取回目标路径不是普通目录")
            }
        }
    }

    /** 递归删除；符号链接只删链接自身（`walkFileTree` 默认不跟随）。 */
    private fun deleteTree(directory: File) {
        Files.walkFileTree(
            directory.toPath(),
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): java.nio.file.FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
    }

    companion object {
        /** 取回后的落脚点：工作区下的 `recovered/<id>/`，文件管理页可以直接浏览与分享。 */
        const val RECOVERED_DIRECTORY = "recovered"

        private const val MAX_DIRECTORIES = 8
        private const val MAX_ITEMS_PER_DIRECTORY = 32
        private const val MAX_MEASURED_ENTRIES = 5_000
        private const val MAX_DISCARD_ENTRIES = 50_000
        private const val MAX_RECOVER_FILES = 5_000
        private const val MAX_RECOVER_ENTRIES = 20_000
        private const val MAX_RECOVER_BYTES = 256L * 1024 * 1024
        private const val MAX_RENAME_ATTEMPTS = 100
    }
}
