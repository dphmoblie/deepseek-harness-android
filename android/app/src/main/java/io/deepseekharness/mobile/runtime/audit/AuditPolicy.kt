package io.deepseekharness.mobile.runtime.audit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class AuditEvent {
    PLUGIN_LIST,
    PLUGIN_ENABLE,
    PLUGIN_UPDATE,
    PLUGIN_LOAD,
    PLUGIN_DESTROY,
    RUNTIME_INSTALL,
    RUNTIME_START,
    RUNTIME_STOP,
    RUNTIME_RESET,

    /** 运行时版本：切换到保留下来的上一版本。 */
    RUNTIME_VERSION_SWITCH,

    /** 运行时版本：删除保留下来的上一版本。 */
    RUNTIME_VERSION_DELETE,

    /**
     * 运行时会话快照：创建、恢复、删除，以及安装/更新前的自动快照。
     *
     * 只记结果与受控错误码（如 `RUNTIME_SNAPSHOT_EMPTY`）；快照标识是应用自己生成的
     * `snap-<时间戳>-<随机>`，不含用户内容，因此可作为详情记录。
     */
    RUNTIME_SNAPSHOT,
    TERMINAL_OPEN,
    TERMINAL_CLOSE,
    SHIZUKU_PERMISSION,

    /** 无障碍应用白名单与系统设置入口。 */
    ACCESSIBILITY_CONFIG,

    /** 无障碍窗口读取；不记录窗口内容、包名或资源 ID。 */
    ACCESSIBILITY_READ,

    /** 无障碍节点动作；不记录输入内容、包名或资源 ID。 */
    ACCESSIBILITY_ACTION,

    /** 外置投递区：一键导入（inbox 的 tar → 工作区 mailbox-import）。 */
    MAILBOX_IMPORT,

    /** 外置投递区：一键导出（工作区 → outbox 的 tar + manifest + sha256）。 */
    MAILBOX_EXPORT,

    /** 存储目录白名单：用户通过 SAF 新增一个要绑进访客的目录。 */
    STORAGE_DIR_ADD,

    /** 存储目录白名单：用户移除一个目录。 */
    STORAGE_DIR_REMOVE,

    /**
     * 应用自身更新：检查、下载、调起系统安装器、打开安装授权设置页。
     *
     * 只记动作与受控结果码（如 `APP_UPDATE_FILE_MISSING`、`APP_UPDATE_INSTALL_PERMISSION`）；
     * 下载地址、落地路径与更新说明一律不落审计。
     */
    APP_UPDATE,
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
