package io.deepseekharness.mobile.runtime.audit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class AuditEvent {
    PLUGIN_LOAD,
    PLUGIN_DESTROY,
    RUNTIME_INSTALL,
    RUNTIME_START,
    RUNTIME_STOP,
    RUNTIME_RESET,
    TERMINAL_OPEN,
    TERMINAL_CLOSE,
    SHIZUKU_PERMISSION,
}

enum class AuditResult {
    STARTED,
    SUCCEEDED,
    FAILED,
    DENIED,
    CANCELLED,
}

internal object AuditPolicy {
    const val RETENTION_DAYS = 90L

    private val auditFilePattern = Regex("^audit-(\\d{4}-\\d{2}-\\d{2})\\.log$")

    // 详情字段仅允许受控错误码：大写字母、数字、下划线。
    // 原始进程输出、路径、凭据一律不落审计日志，防止敏感信息滞留本地。
    private val detailPattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")

    fun recordLine(instant: Instant, event: AuditEvent, result: AuditResult, detail: String? = null): String {
        val base = "${DateTimeFormatter.ISO_INSTANT.format(instant)}|${event.name}|${result.name}"
        val safeDetail = detail?.takeIf(detailPattern::matches)
        return if (safeDetail == null) "$base\n" else "$base|$safeDetail\n"
    }

    fun utcDate(instant: Instant): LocalDate = instant.atZone(ZoneOffset.UTC).toLocalDate()

    fun fileName(date: LocalDate): String = "audit-$date.log"

    fun parseFileDate(fileName: String): LocalDate? {
        val dateText = auditFilePattern.matchEntire(fileName)?.groupValues?.get(1) ?: return null
        return try {
            LocalDate.parse(dateText, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun retentionCandidates(fileNames: Iterable<String>, today: LocalDate): Set<String> {
        val oldestRetainedDate = today.minusDays(RETENTION_DAYS)
        return fileNames.filterTo(linkedSetOf()) { fileName ->
            parseFileDate(fileName)?.isBefore(oldestRetainedDate) == true
        }
    }
}
