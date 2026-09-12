package io.deepseekharness.mobile.runtime.diagnostics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** 诊断日志级别。只影响阅读与过滤，不改变写入格式。 */
enum class DiagnosticLevel { INFO, WARN, ERROR }

/**
 * 诊断事件：固定枚举，不接受自由文本。
 *
 * 新增事件时必须同时确认它不携带 URL、凭据、路径、进程号或终端内容——
 * 这些内容一旦落盘就可能随导出文件离开设备。
 */
enum class DiagnosticEvent {
    /** 插件（WebView 侧）加载与销毁。 */
    APP_START,
    APP_DESTROY,

    /** 运行时阶段变化。 */
    RUNTIME_PHASE,

    /** Harness 启动与停止的结果码。 */
    HARNESS_START,
    HARNESS_STOP,

    /** 设备桥建立结果。 */
    DEVICE_BRIDGE,

    /** 前台服务（后台保持）启停。 */
    KEEP_ALIVE,

    /** 进程被回收后的残留判定与重新连接判定。 */
    RECOVERY,

    /** Shizuku 授权与连接状态（仅在用户显式操作时记录，不随轮询写入）。 */
    SHIZUKU,

    /** 诊断日志自身的设置变化。 */
    LOG_SETTINGS,

    /** 日志导出与清理。 */
    LOG_EXPORT,
}

/**
 * 诊断日志的写入策略。
 *
 * 安全模型（与本项目审计日志一致）：只允许**受控枚举 + 受控键值**。
 *  - 事件与级别是固定枚举；
 *  - 字段名来自固定白名单；
 *  - 字段值必须匹配 `[A-Za-z0-9._-]{1,48}`，因此**无法表达**路径（不含 `/`）、
 *    URL（不含 `/` 与 `:`）、JSON、带空格的自由文本或任何凭据形态；
 *  - 单个字段非法时只丢弃该字段，保留事件本身：宁可少一条上下文，也不落盘不受控内容。
 *
 * 本对象不依赖 Android API，便于单元测试。
 */
object DiagnosticPolicy {
    const val MIN_RETENTION_DAYS = 1
    const val MAX_RETENTION_DAYS = 30
    const val DEFAULT_RETENTION_DAYS = 3

    /** 单文件上限；超过后当天不再追加，避免单日刷爆存储。 */
    const val MAX_FILE_BYTES = 512L * 1024

    /** 目录总量上限；超出时按日期从旧到新删除。 */
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024

    /** 一行最多容纳的字段数。 */
    const val MAX_FIELDS = 8

    /** 单行最大长度（UTF-8 之外按字符计），防止构造出超长行。 */
    const val MAX_LINE_CHARS = 512

    private val filePattern = Regex("^diagnostic-(\\d{4}-\\d{2}-\\d{2})\\.log$")
    private val keyPattern = Regex("^[a-z][a-z0-9_]{0,23}$")

    /**
     * 允许的字段名白名单。
     * 白名单之外一律丢弃：新增字段必须先在评审中确认它不构成敏感信息。
     */
    private val allowedKeys = setOf(
        "phase",
        "code",
        "result",
        "reason",
        "enabled",
        "days",
        "files",
        "bytes",
        "count",
        "residual",
        "installed",
        "running",
        "permission",
        "connected",
        "active",
        "version",
        "sdk",
        "abi",
    )

    // 按字段类型校验取值。单纯用字符集是不够的：`sk-` 开头的 API Key 也能匹配
    // `[A-Za-z0-9._-]+`，所以每种字段都收紧到它实际可能出现的形态。
    private val booleanKeys = setOf("enabled", "active", "installed", "running", "connected", "residual")
    private val countKeys = setOf("days", "files", "bytes", "count", "sdk")
    private val countPattern = Regex("^[0-9]{1,12}$")
    private val codePattern = Regex("^[A-Z][A-Z0-9_]{0,47}$")
    private val permittedResults =
        setOf("ok", "failed", "created", "reused", "denied", "cancelled", "started", "skipped", "succeeded")
    private val permittedPermissions = setOf("granted", "denied", "undetermined", "unsupported")
    /** 阶段名、原因码、版本号与 ABI：小写字母开头，只含 `[a-z0-9._-]`。 */
    private val tokenKeys = setOf("phase", "reason", "version", "abi", "permission")
    private val tokenPattern = Regex("^[a-z0-9][a-z0-9._-]{0,31}$")

    fun fileName(date: LocalDate): String = "diagnostic-$date.log"

    fun utcDate(instant: Instant): LocalDate = instant.atZone(ZoneOffset.UTC).toLocalDate()

    fun parseFileDate(fileName: String): LocalDate? {
        val dateText = filePattern.matchEntire(fileName)?.groupValues?.get(1) ?: return null
        return try {
            LocalDate.parse(dateText, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun clampRetentionDays(days: Int): Int = days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

    /**
     * 组装一行诊断记录。
     *
     * 非法字段被丢弃而不是让整行失败：事件本身（谁发生了）比上下文更值得保留，
     * 同时保证任何情况下都不会写出不受控内容。
     */
    fun recordLine(
        instant: Instant,
        level: DiagnosticLevel,
        event: DiagnosticEvent,
        fields: Map<String, String> = emptyMap(),
    ): String {
        val builder = StringBuilder(96)
        builder.append(DateTimeFormatter.ISO_INSTANT.format(instant))
            .append('|').append(level.name)
            .append('|').append(event.name)
        fields.entries
            .asSequence()
            .filter { (key, value) -> isAllowedField(key, value) }
            .take(MAX_FIELDS)
            .forEach { (key, value) ->
                builder.append('|').append(key).append('=').append(value)
            }
        builder.append('\n')
        val line = builder.toString()
        return if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS - 1) + "\n"
    }

    fun isAllowedKey(key: String): Boolean = keyPattern.matches(key) && key in allowedKeys

    /**
     * 字段是否可写入：字段名必须在白名单内，且取值必须符合该字段的形态。
     *
     * 只做字符集校验是不够的 —— `sk-` 开头的 API Key 同样由 `[A-Za-z0-9._-]` 组成。
     * 因此每个字段都被收紧到它实际可能出现的取值：
     *  - 布尔字段只接受 `true` / `false`；
     *  - 计数字段只接受 1–12 位数字；
     *  - `code` 只接受大写错误码，`result` / `permission` 只接受封闭集合；
     *  - `phase` / `reason` / `version` / `abi` 只接受小写 token。
     */
    fun isAllowedField(key: String, value: String): Boolean {
        if (!isAllowedKey(key)) return false
        return when (key) {
            in booleanKeys -> value == "true" || value == "false"
            in countKeys -> countPattern.matches(value)
            "code" -> codePattern.matches(value)
            "result" -> value in permittedResults
            "permission" -> value in permittedPermissions
            in tokenKeys -> tokenPattern.matches(value)
            else -> false
        }
    }

    /**
     * 超出保留期的文件名集合。
     * 与审计日志一致：按 UTC 日期文件名判断，保留 [retentionDays] 天（含当天）。
     */
    fun retentionCandidates(fileNames: Iterable<String>, today: LocalDate, retentionDays: Int): Set<String> {
        val days = clampRetentionDays(retentionDays)
        val oldestRetainedDate = today.minusDays(days.toLong())
        return fileNames.filterTo(linkedSetOf()) { name ->
            parseFileDate(name)?.isBefore(oldestRetainedDate) == true
        }
    }

    /**
     * 在保留期之内、但目录总量超限时，需要优先删除的文件（从最旧开始）。
     * [entries] 为 (文件名, 字节数)，只有能解析出日期的文件参与计算。
     */
    fun sizeCandidates(
        entries: List<Pair<String, Long>>,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): Set<String> {
        val dated = entries
            .mapNotNull { (name, bytes) -> parseFileDate(name)?.let { Triple(it, name, bytes) } }
            .sortedBy { it.first }
        var total = dated.sumOf { it.third }
        if (total <= maxTotalBytes) return emptySet()
        val victims = linkedSetOf<String>()
        for ((_, name, bytes) in dated) {
            if (total <= maxTotalBytes) break
            victims.add(name)
            total -= bytes
        }
        return victims
    }

    /** 导出文件名；只用 UTC 时间戳，不含包名、设备信息或用户输入。 */
    fun exportFileName(instant: Instant): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC)
            .format(instant)
        return "dsh-diagnostic-$stamp.txt"
    }
}
