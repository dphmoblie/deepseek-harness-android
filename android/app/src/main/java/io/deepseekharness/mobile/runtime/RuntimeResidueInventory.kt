package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 诊断字段里的字节数：策略只接受 12 位以内的纯数字，超出按上限收敛。
 *
 * 收敛（而不是原样写）是必须的：超限字段会被策略**静默丢弃**，那一行就少了一个数字，
 * 排障的人会以为「字节数没记」，而不是「字节数太大」。
 */
internal fun residueBytesToken(bytes: Long): String = bytes.coerceIn(0L, MAX_DIAGNOSTIC_BYTES).toString()

private const val MAX_DIAGNOSTIC_BYTES = 999_999_999_999L

/**
 * 运行时目录的尺寸统计：不跟随符号链接，遇到读不了的条目跳过，绝不抛出。
 *
 * 条目预算是为了不把一次盘点变成一次全盘遍历：扫到上限就停，并用 [Size.truncated] 说明
 * 「数字只是下界」——宁可少报也不能让盘点把安装卡住。
 */
internal object RuntimeResidueSizing {
    /** 与 [RuntimeFiles] 删除预算同量级。 */
    const val MAX_ENTRIES = 250_000

    /**
     * @param bytes 已统计到的字节数（[truncated] 为 true 时是下界）
     * @param entries 已统计到的条目数（不含根条目本身）
     * @param truncated 是否因为条目预算而提前停止
     */
    data class Size(val bytes: Long, val entries: Int, val truncated: Boolean)

    fun of(target: File): Size {
        val root = target.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Size(0, 0, false)
        var bytes = 0L
        var entries = 0
        var truncated = false
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        bytes += attributes.size()
                        entries += 1
                        return if (entries >= MAX_ENTRIES) {
                            truncated = true
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
                        if (entries >= MAX_ENTRIES) {
                            truncated = true
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    // 读不了的条目（权限、竞态删除）直接跳过：少算一点，也好过让盘点失败。
                    override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE

                    override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (_: Throwable) {
            // 保留已经统计到的部分：盘点是排障信息，绝不因为一次 I/O 失败就没有结果。
        }
        return Size(bytes, entries, truncated)
    }
}

/**
 * 运行时父目录（`noBackupFilesDir/dsh-runtime`）的直接子项盘点。
 *
 * 只按已知前缀归类，只输出**类别、份数、总字节**：任何路径名、UUID、哈希都不会进入诊断日志，
 * 因此它的输出天然满足 [io.deepseekharness.mobile.runtime.diagnostics.DiagnosticPolicy] 的白名单与
 * token 规则（见 [lines]，字段合法性由 `RuntimeResidueInventoryTest` 直接对着策略断言）。
 *
 * 存在意义：`current/`、`previous/`、`retained/`、`preserve-*`、`staging-*` 与删不掉的 `stale-*`
 * 都是整份 rootfs（约 960 MB 一份），设备上却看不见它们。盘点行让「哪一类占了几份、共多少字节」
 * 出现在诊断页与导出的日志里。
 */
internal class RuntimeResidueInventory(private val runtimeParent: File) {
    data class Category(val token: String, val entries: Int, val bytes: Long)

    data class Report(val categories: List<Category>, val entries: Int, val bytes: Long, val truncated: Boolean) {
        /** 是否还有未回收的 `stale-*` 残留。 */
        val hasResidue: Boolean get() = categories.any { it.token == STALE_TOKEN && it.entries > 0 }
    }

    /** 一条待写入诊断日志的记录；字段已经过白名单/token 规范。 */
    data class Line(val level: DiagnosticLevel, val fields: Map<String, String>)

    fun report(): Report {
        val children = try {
            runtimeParent.listFiles()?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        val tallies = LinkedHashMap<String, Tally>()
        var truncated = false
        for (child in children) {
            val size = RuntimeResidueSizing.of(child)
            truncated = truncated || size.truncated
            val tally = tallies.getOrPut(categoryOf(child.name)) { Tally() }
            tally.entries += 1
            tally.bytes += size.bytes
        }
        // 按固定类别顺序输出：日志里每次盘点的行序一致，肉眼扫一眼就能发现多出来的那一类。
        val categories = CATEGORY_TOKENS.mapNotNull { token ->
            tallies[token]?.let { Category(token, it.entries, it.bytes) }
        }
        return Report(
            categories = categories,
            entries = categories.sumOf { it.entries },
            bytes = categories.sumOf { it.bytes },
            truncated = truncated,
        )
    }

    /**
     * 诊断行：一条总计行（`reason` 是调用时机）+ 每个非空类别一行（`reason` 是类别 token）。
     *
     * 空类别不写行，避免每次盘点都刷一屏「0 份」。
     */
    fun lines(site: String): List<Line> {
        val report = report()
        val total = buildMap {
            put("phase", INVENTORY_PHASE)
            put("result", "ok")
            put("reason", RuntimeResidueTokens.of(site, fallback = FALLBACK_SITE))
            put("count", report.entries.toString())
            put("bytes", residueBytesToken(report.bytes))
            put("residual", if (report.hasResidue) "true" else "false")
            if (report.truncated) put("code", "BYTES_TRUNCATED")
        }
        return buildList {
            add(Line(DiagnosticLevel.INFO, total))
            report.categories.forEach { category ->
                add(
                    Line(
                        DiagnosticLevel.INFO,
                        mapOf(
                            "phase" to INVENTORY_PHASE,
                            "result" to "ok",
                            "reason" to category.token,
                            "count" to category.entries.toString(),
                            "bytes" to residueBytesToken(category.bytes),
                        ),
                    ),
                )
            }
        }
    }

    private class Tally {
        var entries: Int = 0
        var bytes: Long = 0
    }

    companion object {
        const val STALE_TOKEN = "stale"

        /** 已知类别（与 [categoryOf] 一一对应），也是输出顺序。 */
        val CATEGORY_TOKENS = listOf(
            "current",
            "previous",
            "retained",
            STALE_TOKEN,
            "preserve",
            "staging",
            "manifest",
            "download",
            "resume",
            "other",
        )

        /**
         * 只按**已知前缀白名单**归类；认不出来的落到 `other`（而不是丢掉）：盘点要如实反映目录里
         * 有东西，只是不说是哪一个。
         */
        fun categoryOf(name: String): String = when {
            name == "current" || name.startsWith("current-") -> "current"
            name == "previous" || name.startsWith("previous-") -> "previous"
            name == "retained" || name.startsWith("retained-") -> "retained"
            RuntimeResidueNames.isResidueName(name) -> STALE_TOKEN
            name.startsWith(RuntimePreservePolicy.PRESERVE_DIRECTORY_PREFIX) -> "preserve"
            name.startsWith("staging-") -> "staging"
            name.startsWith("manifest-") -> "manifest"
            name.startsWith("download-") -> "download"
            RuntimeInstaller.RESUME_FILE.matches(name) -> "resume"
            else -> "other"
        }

        private const val INVENTORY_PHASE = "inventory"
        private const val FALLBACK_SITE = "scan"
    }
}
