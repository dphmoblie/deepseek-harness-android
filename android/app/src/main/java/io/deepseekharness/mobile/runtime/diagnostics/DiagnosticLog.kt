package io.deepseekharness.mobile.runtime.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.system.Os
import io.deepseekharness.mobile.BuildConfig
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDate

/** 诊断日志的对外状态快照；只包含布尔值、计数与时间戳。 */
data class DiagnosticState(
    /** 收集开关；关闭时不写入任何新记录。 */
    val enabled: Boolean,
    /** 保留天数（1..30）。 */
    val retentionDays: Int,
    /** 当前日志文件数。 */
    val fileCount: Int,
    /** 当前日志总字节数。 */
    val totalBytes: Long,
    /** 最近一条记录的时间；从未记录时为 0。 */
    val lastEntryAtMillis: Long,
)

/** 导出结果；[absolutePath] 仅供原生侧使用，不回传 WebView。 */
data class DiagnosticExport(
    val fileName: String,
    val sizeBytes: Long,
    val fileCount: Long,
    val absolutePath: String,
)

/**
 * 应用自诊断日志。
 *
 * 与审计日志（[io.deepseekharness.mobile.runtime.audit.PrivateAuditLog]）的区别：
 *  - 审计日志是安全产物：固定事件/结果枚举、90 天保留、不可关闭；
 *  - 诊断日志是排障产物：用户显式开关、1–30 天保留、可导出分享。
 *
 * 两者的共同底线是**只写受控枚举与受控键值**（见 [DiagnosticPolicy]）：
 * 不含 URL、凭据、终端内容、文件路径、进程号或任何用户数据。
 *
 * 存储：`noBackupFilesDir/diagnostics/`，目录 0700、文件 0600，不参与备份。
 * 导出：`cacheDir/diagnostics/`，仅供 FileProvider 分享，可被系统随时回收。
 */
class DiagnosticLog(context: Context) {
    private val appContext = context.applicationContext
    private val directory: Path = appContext.noBackupFilesDir.toPath().resolve(DIRECTORY_NAME)
    private val exportDirectory: Path = appContext.cacheDir.toPath().resolve(DIRECTORY_NAME)
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Volatile
    private var lastPrunedDate: LocalDate? = null

    fun enabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun retentionDays(): Int = DiagnosticPolicy.clampRetentionDays(
        preferences.getInt(KEY_RETENTION_DAYS, DiagnosticPolicy.DEFAULT_RETENTION_DAYS),
    )

    /**
     * 更新收集设置。关闭时不删除已有文件——用户可能先关掉再导出；
     * 需要立即清除请调用 [clear]。真实删除仍受保留策略约束。
     */
    @Synchronized
    fun setSettings(enabled: Boolean, retentionDays: Int): DiagnosticState {
        val days = DiagnosticPolicy.clampRetentionDays(retentionDays)
        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_RETENTION_DAYS, days)
            .apply()
        // 设置变化本身是排障时最需要看到的信息之一；记录发生在写入开关生效之后。
        record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.LOG_SETTINGS,
            mapOf("enabled" to enabled.toString(), "days" to days.toString()),
        )
        return state()
    }

    /** 写入一条诊断记录；开关关闭时不做任何事（也不创建目录）。 */
    fun record(
        level: DiagnosticLevel,
        event: DiagnosticEvent,
        fields: Map<String, String> = emptyMap(),
        instant: Instant = Instant.now(),
    ) {
        if (!enabled()) return
        synchronized(PROCESS_LOCK) {
            try {
                ensurePrivateDirectory()
                val today = DiagnosticPolicy.utcDate(instant)
                pruneIfNeeded(today)
                append(DiagnosticPolicy.fileName(today), DiagnosticPolicy.recordLine(instant, level, event, fields))
            } catch (_: Throwable) {
                // 诊断日志绝不允许改变被观测操作的结果，也不允许把异常细节扩散出去。
            }
        }
    }

    @Synchronized
    fun state(): DiagnosticState {
        val entries = listEntries()
        return DiagnosticState(
            enabled = enabled(),
            retentionDays = retentionDays(),
            fileCount = entries.size,
            totalBytes = entries.sumOf { it.second },
            lastEntryAtMillis = entries.maxOfOrNull { it.third } ?: 0L,
        )
    }

    /** 删除全部诊断日志。目录不存在时视为已清空。 */
    @Synchronized
    fun clear(): DiagnosticState {
        try {
            listEntries().forEach { (name, _, _) ->
                try {
                    Files.deleteIfExists(directory.resolve(name))
                } catch (_: Throwable) {
                    // 单个文件删除失败不阻断其余清理；下次保留策略会再试。
                }
            }
        } catch (_: Throwable) {
            // 目录不可读时保持现状。
        }
        lastPrunedDate = null
        return state()
    }

    /**
     * 把全部诊断日志合并成一个文本文件，供系统分享面板导出。
     *
     * 文件头只包含应用版本、Android SDK 级别与 ABI：这些是排障必需且不构成用户数据，
     * 不含设备标识、账号、URL、路径或凭据。返回 null 表示没有可导出的内容。
     */
    @Synchronized
    fun export(instant: Instant = Instant.now()): DiagnosticExport? {
        val entries = listEntries().sortedBy { it.first }
        if (entries.isEmpty()) return null
        val text = buildString {
            append("# DeepSeek Harness Android 诊断日志\n")
            append("# 生成时间: ").append(instant.toString()).append('\n')
            append("# 应用版本: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("# Android SDK: ").append(Build.VERSION.SDK_INT)
                .append("  ABI: ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown").append('\n')
            append("# 保留策略: ").append(retentionDays()).append(" 天\n")
            append("# 内容说明: 仅包含应用内部状态码与布尔值，不含 URL、凭据、终端内容或用户数据。\n")
            entries.forEach { (name, _, _) ->
                append("\n===== ").append(name).append(" =====\n")
                append(readBounded(directory.resolve(name)))
            }
        }
        val fileName = DiagnosticPolicy.exportFileName(instant)
        val target = exportDirectory.resolve(fileName)
        return try {
            Files.createDirectories(exportDirectory)
            Os.chmod(exportDirectory.toString(), DIRECTORY_MODE)
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            Files.newByteChannel(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                Os.chmod(target.toString(), FILE_MODE)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            record(
                DiagnosticLevel.INFO,
                DiagnosticEvent.LOG_EXPORT,
                mapOf(
                    "files" to entries.size.toString(),
                    "bytes" to bytes.size.toString(),
                ),
            )
            DiagnosticExport(fileName, bytes.size.toLong(), entries.size.toLong(), target.toString())
        } catch (_: Throwable) {
            try {
                Files.deleteIfExists(target)
            } catch (_: Throwable) {
                // 保留原始失败：导出失败不应留下半截文件。
            }
            null
        }
    }

    private fun readBounded(path: Path): String = try {
        Files.readAllBytes(path).toString(StandardCharsets.UTF_8)
    } catch (_: Throwable) {
        "（该文件无法读取）\n"
    }

    private fun ensurePrivateDirectory() {
        val attributes = try {
            Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            try {
                Files.createDirectory(directory)
            } catch (_: FileAlreadyExistsException) {
                // 同进程内可能已由其他调用创建。
            }
            Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw IOException("Invalid diagnostics directory")
        }
        Os.chmod(directory.toString(), DIRECTORY_MODE)
    }

    /** 保留策略 + 容量上限。按 UTC 日期判断，每天最多执行一次完整扫描。 */
    private fun pruneIfNeeded(today: LocalDate) {
        if (lastPrunedDate == today) return
        try {
            val entries = listEntries()
            val victims = DiagnosticPolicy.retentionCandidates(
                entries.map { it.first },
                today,
                retentionDays(),
            ) + DiagnosticPolicy.sizeCandidates(entries.map { it.first to it.second })
            var completed = true
            victims.forEach { name ->
                try {
                    Files.deleteIfExists(directory.resolve(name))
                } catch (_: Throwable) {
                    completed = false
                }
            }
            if (completed) lastPrunedDate = today
        } catch (_: Throwable) {
            // 清理失败只会让日志多留一会儿，下次写入时重试。
        }
    }

    /** 目录内 (文件名, 字节数, 最后修改毫秒)；无法读取的条目被跳过。 */
    private fun listEntries(): List<Triple<String, Long, Long>> = try {
        Files.newDirectoryStream(directory).use { stream ->
            stream.mapNotNull { path ->
                try {
                    val attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    if (!attributes.isRegularFile || attributes.isSymbolicLink) return@mapNotNull null
                    val name = path.fileName.toString()
                    if (DiagnosticPolicy.parseFileDate(name) == null) return@mapNotNull null
                    Triple(name, attributes.size(), attributes.lastModifiedTime().toMillis())
                } catch (_: Throwable) {
                    null
                }
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun append(fileName: String, line: String) {
        val path = directory.resolve(fileName)
        val channel = try {
            FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: FileAlreadyExistsException) {
            requireRegularFile(path)
            if (Files.size(path) + line.toByteArray(StandardCharsets.UTF_8).size > DiagnosticPolicy.MAX_FILE_BYTES) {
                // 单日文件达到上限：停止追加而不是无限增长，保留策略仍会按期清理。
                return
            }
            FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS,
            )
        }
        channel.use {
            Os.chmod(path.toString(), FILE_MODE)
            val buffer = ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8))
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
    }

    private fun requireRegularFile(path: Path) {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            throw IOException("Invalid diagnostics file")
        }
    }

    /** 供插件构造 FileProvider URI 使用。 */
    fun exportedFile(export: DiagnosticExport): java.io.File = java.io.File(export.absolutePath)

    fun fileProviderAuthority(): String = "${appContext.packageName}$AUTHORITY_SUFFIX"

    private companion object {
        const val DIRECTORY_NAME = "diagnostics"
        const val PREFERENCES = "diagnostic_log_settings"
        const val KEY_ENABLED = "diagnostic_log_enabled"
        const val KEY_RETENTION_DAYS = "diagnostic_log_retention_days"
        const val AUTHORITY_SUFFIX = ".diagnostics"
        const val DIRECTORY_MODE = 0b111_000_000 // 0700
        const val FILE_MODE = 0b110_000_000 // 0600
        val PROCESS_LOCK = Any()
    }
}
