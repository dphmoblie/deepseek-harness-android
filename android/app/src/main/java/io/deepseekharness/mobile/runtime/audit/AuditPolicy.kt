package io.deepseekharness.mobile.runtime.audit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class AuditEvent {
    APP_AUTH,
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

    fun recordLine(instant: Instant, event: AuditEvent, result: AuditResult): String =
        "${DateTimeFormatter.ISO_INSTANT.format(instant)}|${event.name}|${result.name}\n"

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
