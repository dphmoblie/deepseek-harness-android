package io.deepseekharness.mobile.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * 把暂存目录里的用户数据**合并**进新根目录：逐项搬移，同名不覆盖。
 *
 * 为什么不再「整项二选一」：新 rootfs 只要预建了一个非空的同名目录（自带示例、`inbox/` 之类），
 * 旧实现就判整项冲突，用户数据全部留在 `preserve-*` 里——从用户视角看就是「更新后文件没了」。
 * 合并把「能搬的都搬进去」当成默认，搬不动的少数几项留下并记账。
 *
 * 安全边界（与 `RuntimePreservePolicy` 的分工：这里只做文件操作，判定在纯策略里）：
 * - **绝不覆盖**：目标同名项存在就跳过（目录则递归下去合并），文件/符号链接一律记为冲突；
 * - **不跟随符号链接**：源与目标都用 `NOFOLLOW_LINKS` 判断，符号链接按冲突处理；
 * - 深度与条目数有界：暂存的是用户数据，不能把一次更新变成无边界的树遍历。
 *
 * 纯文件操作、不依赖 Android API，因此可以直接用临时目录做 JVM 单测。
 */
object PreservedMerge {
    /** 递归深度上限：用户数据目录再深也不该超过这个量级。 */
    private const val MAX_DEPTH = 8

    /** 单次合并最多访问的条目数：超出后剩下的全部记为冲突（仍留在暂存目录里）。 */
    private const val MAX_VISITED_ENTRIES = 20_000

    /**
     * @param moved 实际搬进去的条目数
     * @param conflicts 没能搬进去的相对路径（相对合并根，例如 `audit/notes.md`）
     * @param skipped 按调用方给的排除表跳过的相对路径（例如旧令牌、旧脚本）；它们**不是**冲突
     */
    data class Result(val moved: Int, val conflicts: List<String>, val skipped: List<String> = emptyList())

    /**
     * 把 [source] 的内容合并进 [target]（两者都应是目录）。
     *
     * [skip] 命中时该条目原样留在源目录里（既不搬也不删）。`root` 整树回填用它排除旧令牌/旧脚本：
     * 这些项不属于「冲突」，调用方不应把它们报成没搬成功。
     *
     * @throws java.io.IOException 目标目录建不出来或搬移失败时抛出，由调用方折算成受控错误码。
     */
    fun merge(source: File, target: File, skip: (String) -> Boolean = { false }): Result {
        val conflicts = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val counter = intArrayOf(0, 0)
        if (!isDirectory(source)) return Result(0, if (Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) listOf(".") else emptyList())
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target.toPath())
        }
        if (!isDirectory(target)) return Result(0, listOf("."))
        mergeInto(source, target, "", 0, counter, conflicts, skipped, skip)
        return Result(counter[0], conflicts, skipped)
    }

    private fun mergeInto(
        source: File,
        target: File,
        prefix: String,
        depth: Int,
        counter: IntArray,
        conflicts: MutableList<String>,
        skipped: MutableList<String>,
        skip: (String) -> Boolean,
    ) {
        if (depth > MAX_DEPTH) {
            conflicts += prefix.ifEmpty { "." }
            return
        }
        val children = source.listFiles()?.sortedBy { it.name }
        if (children == null) {
            // 读不到目录内容：按冲突处理，绝不当作「空目录」把用户的目录删掉。
            conflicts += prefix.ifEmpty { "." }
            return
        }
        for (child in children) {
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            counter[1] += 1
            if (counter[1] > MAX_VISITED_ENTRIES) {
                conflicts += prefix.ifEmpty { "." }
                return
            }
            if (skip(relative)) {
                skipped += relative
                continue
            }
            // 源里的符号链接一律按冲突处理：即便目标不存在也不能搬。移动链接本身虽不会跟随，
            // 但回填后的新根里会留下指向旧暂存路径/外部路径的死链，和「不跟随」的边界相悖。
            if (Files.isSymbolicLink(child.toPath())) {
                conflicts += relative
                continue
            }
            val destination = File(target, child.name)
            if (!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (isDirectory(child)) {
                    // 目标目录缺失时也要递归，才能对其子项应用 skip（例如旧令牌和运行时脚本）。
                    Files.createDirectories(destination.toPath())
                    mergeInto(child, destination, relative, depth + 1, counter, conflicts, skipped, skip)
                } else {
                    Files.move(child.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    counter[0] += 1
                }
                continue
            }
            if (isDirectory(child) && isDirectory(destination)) {
                mergeInto(child, destination, relative, depth + 1, counter, conflicts, skipped, skip)
                continue
            }
            conflicts += relative
        }
    }

    /** 不跟随符号链接的「是不是目录」。 */
    internal fun isDirectory(file: File): Boolean =
        Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())

    /**
     * [root] 里是否只剩「按排除表跳过」的条目。
     *
     * 整树回填后用它决定能不能删掉暂存的那份 `root`：只剩旧令牌/旧脚本/死链时它们本来就会被
     * 新运行时重建，留着没有意义；只要还有一条没跳过的东西就返回 `false`（绝不误删）。
     */
    fun hasOnlySkipped(root: File, skip: (String) -> Boolean): Boolean {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        if (!isDirectory(root)) return skip("")
        val pending = ArrayDeque<Pair<File, String>>()
        pending.addLast(root to "")
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_VISITED_ENTRIES) {
            val (current, prefix) = pending.removeFirst()
            visited += 1
            val children = current.listFiles() ?: return false
            for (child in children) {
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                val skipped = skip(relative)
                if (isDirectory(child)) {
                    if (skipped) continue
                    pending.addLast(child to relative)
                    continue
                }
                if (!skipped) return false
            }
        }
        return pending.isEmpty()
    }
}
