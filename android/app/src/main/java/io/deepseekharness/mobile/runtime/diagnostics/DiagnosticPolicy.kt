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

    /**
     * 每次 Harness 启动实际注入的模型凭据条数。
     *
     * 只记录**计数**，绝不记录变量名与取值：用于在设备上区分
     * 「App 没有可注入的凭据」与「dsh 侧没有用上」，避免只能靠猜。
     */
    CREDENTIALS,

    /**
     * 插件运行时链接自愈的结果。
     *
     * 只记录计数：`files` 是扫到的包条目数，`count` 是被重新指向运行时实例的包数，
     * `result=denied` 表示有包没能修复。**`count > 0` 即「设备上确实存在重复的运行时包」**
     * 的直接证据 —— 这正是工具调用失败那条链路此前只能靠推断的那一环。
     */
    REPAIR,

    /**
     * 访客里 `@deepseek-ai/dsh-tools` 的模块图探测结果（只读，Harness 每次启动前记录一次）。
     *
     * 只记录计数：`count` = **不同真实路径数**，`files` = 出现位置总数（真实目录 + 符号链接）。
     * **`count > 1` 即同一进程里存在两份模块实例**：`dsh-tools` 的调度器
     * `Symbol('@deepseek-ai/dsh-tools.scheduler')` 是每个物理模块副本各造一个身份，身份失配后
     * `ctx.tools[调度器 Symbol]` 取到 undefined，之后**每一次工具调用都会失败**，报
     * `Cannot read properties of undefined (reading 'prepare')`。
     * 判据只认真实路径：同一个真实目录被多个链接引用多少次都只算一份，因此不会误报。
     *
     * 取值约定：`result=ok` 表示只有一份；`count > 1` 时记 WARN + `result=failed`（一眼可见）；
     * 探测本身失败时记 WARN + `result=denied` + `code=PROBE_FAILED`，与判据成立区分开。
     */
    MODULE_GRAPH,

    /**
     * 运行时自检结果（应用内「运行时自检」入口触发，`check` 与 `repair` 共用本事件）。
     *
     * 用途：设备上 dsh 的 `bash` 工具报 `PTY shell exited during startup`，而应用自己的 Ubuntu
     * 终端正常 —— 断链只可能在「沙箱不可用 / 真实 confine exec 失败 / PTY（node-pty）本身失败」
     * 三者之一。自检把三种可能各测一次，这条记录就是**设备侧唯一的第一手证据**。
     *
     * 只记录受控取值，不含路径、脚本输出或原始报错文本：
     *  - `result=ok` 十八项里没有 fail（编译环境能力项与宿主追加的三项设备事实只报 `warn`，
     *    不会让整体转 `failed`；只有系统 WebView 不可用是 fail）；
     *    `result=failed` 有 fail，`code` 是**按契约顺序的首个失败码**
     *    （例如 `PTY_EXIT_EARLY` / `EXEC_LAUNCHER_FAILED` / `PROBE_UNUSABLE` / `WEBVIEW_UNAVAILABLE`），
     *    一眼能定位到层；
     *  - `result=denied` + `code=SELF_CHECK_FAILED` 表示自检**没跑成**（脚本也没起来或载荷不可信），
     *    与「检查项确实失败」区分开；
     *  - `count`：`check` 时是失败项数，`repair` 时是实际改动数（补执行位 + 建附件目录）。
     *    `warn` 与 `skipped` 不算失败，只出现在界面载荷里。
     */
    SELF_CHECK,

    /**
     * 外置投递区（mailbox）的搬运结果。
     *
     * 只记录受控取值：`reason` 是 `import` / `export` / `state`，`result` 是 `ok` / `failed` /
     * `denied`，`files` 是条目数，`bytes` 是内容总字节数，`count` 是符号链接数或跳过数。
     * **不记录任何路径**（含用户可见路径），也不记录文件名或 manifest 内容。
     */
    MAILBOX,

    /**
     * ≤8 目录白名单（§5.1）的增删与绑定结果。
     *
     * 只记录受控取值：`reason` 是 `add` / `remove` / `read` / `bind`，`result` 是 `ok` /
     * `skipped` / `failed`，`count` 是白名单条数、被丢弃的坏条目数或被跳过的条数，
     * `code` 是受控错误码（如 `STORAGE_DIR_NOT_A_DIRECTORY`）。
     *
     * **不记录路径与目录名**：白名单路径会暴露用户的目录结构（可能含姓名、项目名），
     * 而诊断日志可以经系统分享面板导出。排障只需要「第几条、因为什么码被跳过」。
     */
    STORAGE_DIRS,

    /**
     * 运行时会话快照的**自动**结果（安装/更新前那一份）。
     *
     * 为什么要单独记：自动快照失败**不阻断安装**，所以它不会出现在任何被拒绝的调用里；
     * 如果只写审计，用户在设备上就只剩「审计日志」一条线索。这里记一条就能回答
     * 「这次更新到底有没有可用的备份」。
     *
     * 只记录受控取值：`reason=auto`，`result` 是 `created` / `skipped` / `failed`，
     * `code` 是受控错误码（如 `RUNTIME_SNAPSHOT_EMPTY`），`files` 是文件数、`bytes` 是总字节数、
     * `count` 是当前保留的快照份数。**不记录任何路径与快照标识**。
     */
    RUNTIME_SNAPSHOT,

    /**
     * 分级清理（缓存档 / 插件数据档）。
     *
     * 为什么要单独记：清理是**破坏性且不可撤销**的操作，用户点完只会看到「已清理」。日志里留下
     * 一行才能回答「到底删掉了多少、有没有删失败」——尤其是失败时界面不会把删除失败说成成功。
     *
     * 只记录受控取值：`reason` 是 `cache` / `plugin_data`，`result` 是 `ok` / `failed` / `denied`，
     * `files` 是删除的文件数、`bytes` 是回收的字节数、`count` 是这一档覆盖的条目数
     * （缓存档是白名单条目数、插件数据档是账本里核对到的包数），`code` 是受控错误码
     * （如 `CLEANUP_LEDGER_UNAVAILABLE`）。
     *
     * **不记录任何路径、目录名与包名**：插件包名能反推用户装了什么，而诊断日志可以导出。
     */
    CLEANUP,
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

    /**
     * 应用内查看诊断日志的默认与最大字节数。
     *
     * 查看与导出是两条路径：导出给的是全部文件，查看只给**尾部窗口**——
     * 排障时真正要看的是最近发生了什么，而把 4 MB 全部读进 WebView 既慢又没有意义。
     * 64 KB 大约能容纳上千行受控记录，放大到 256 KB 需要用户显式选择。
     */
    const val DEFAULT_READ_BYTES = 64 * 1024
    const val MAX_READ_BYTES = 256 * 1024

    /** 把界面的读取请求夹到 1 KB..[MAX_READ_BYTES]；非法值回落到默认值。 */
    fun clampReadBytes(requested: Int?): Int {
        if (requested == null) return DEFAULT_READ_BYTES
        if (requested <= 0) return DEFAULT_READ_BYTES
        return requested.coerceIn(1024, MAX_READ_BYTES)
    }

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
        // 访客 Node 的版本号（vX.Y.Z）与模块 ABI（纯数字）：排障时定位「运行时缺 API」用。
        "node",
        // transfer 阶段的字节证据（见 [TransferFields]）：全是数字与布尔，没有路径与文本。
        "expected_bytes",
        "actual_bytes",
        "free_bytes",
        "digest_ok",
        // 插件修复时的三个计数（见 `RuntimePluginManager.repairInstalledIfNeeded`）：
        // plugins = 清单里登记的插件数、relinked = 被接回 profile 模块根的数量、
        // missing = 保留区里已无副本、只能重装的数量。三个都是整数，回答的是「这次
        // 运行时升级有没有抹掉插件链接」，因此必须在导出里看得到。
        "plugins",
        "relinked",
        "missing",
        // 一次 Harness 启动从「用户点打开」到「服务可用」的毫秒数。
        // 它是启动优化唯一的客观口径：只看体感时，「快了」与「这次网络好」分不开，
        // 而导出诊断里的这一列能直接对比改动前后与不同机型。取值是纯数字，
        // 不含路径、主机名或任何凭据，因此可以安全落盘。
        "elapsed_ms",
    )

    // 按字段类型校验取值。单纯用字符集是不够的：`sk-` 开头的 API Key 也能匹配
    // `[A-Za-z0-9._-]+`，所以每种字段都收紧到它实际可能出现的形态。
    private val booleanKeys =
        setOf("enabled", "active", "installed", "running", "connected", "residual", "digest_ok")
    private val countKeys =
        setOf(
            "days",
            "files",
            "bytes",
            "count",
            "sdk",
            "expected_bytes",
            "actual_bytes",
            "free_bytes",
            "plugins",
            "relinked",
            "missing",
            // 启动耗时：纯数字（毫秒），见 [allowedKeys] 里同名条目的说明。
            "elapsed_ms",
        )
    private val countPattern = Regex("^[0-9]{1,12}$")
    private val codePattern = Regex("^[A-Z][A-Z0-9_]{0,47}$")
    private val permittedResults =
        setOf("ok", "failed", "created", "reused", "denied", "cancelled", "started", "skipped", "succeeded")
    private val permittedPermissions = setOf("granted", "denied", "undetermined", "unsupported")
    /** 阶段名、原因码、版本号与 ABI：小写字母开头，只含 `[a-z0-9._-]`。 */
    private val tokenKeys = setOf("phase", "reason", "version", "abi", "permission")
    private val tokenPattern = Regex("^[a-z0-9][a-z0-9._-]{0,47}$")

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

    // 凭据形态：`key=value` / `Authorization: xxx` 与 `Bearer xxx` / `Basic xxx` 两种写法。
    // 词边界保证 `reason=token_missing` 这类合法取值不会被误伤（`token` 后面不是 `:` 或 `=`）。
    //
    // `Authorization` 单独一条且**吃到行尾**（`|` 是字段分隔符，遇到就停）：它的取值通常不是
    // 单个 token，而是 `Bearer xxx` 这种两段式，只redact 第一段会把令牌本体留在导出文件里。
    private val credentialHeader = Regex(
        "(?i)\\b(authorization|proxy-authorization)\\b\\s*[:=]\\s*[^\\n|]*",
    )
    private val credentialAssignment = Regex(
        "(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret)"
            + "\\b\\s*[:=]\\s*([^\\s|]+)",
    )
    private val credentialScheme = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{4,}")
    private val credentialInUrl = Regex("(?i)\\b(https?://)[^\\s/@]+@")

    /**
     * 导出前的最后一道脱敏——纵深防御，不是主要把关点。
     *
     * 主要把关点始终是 [recordLine] 的白名单：字段名必须在册、取值必须是受控形态，正常路径下
     * 一个字都写不出去。但导出是**产物交接**：文件会离开应用私有目录、进到系统分享面板与聊天工具，
     * 所以这里再做一次与调用点无关的兜底——把凭据形态替换成 `<redacted>`，保留字段名以便排障时
     * 仍能看出「这里原本有一个凭据字段」。
     *
     * 它挡不住的东西要说清楚：自由文本里的凭据（例如把整段配置贴进日志）没有可识别的形态，
     * 这条规则对此无能为力；白名单才是保证不出现自由文本的那一层。
     */
    fun scrubExport(text: String): String = text
        .replace(credentialHeader) { "${it.groupValues[1]}=<redacted>" }
        .replace(credentialAssignment) { "${it.groupValues[1]}=<redacted>" }
        .replace(credentialScheme) { "${it.groupValues[1]} <redacted>" }
        .replace(credentialInUrl) { "${it.groupValues[1]}<redacted>@" }

    /**
     * 字段是否可写入：字段名必须在白名单内，且取值必须符合该字段的形态。
     *
     * 只做字符集校验是不够的 —— `sk-` 开头的 API Key 同样由 `[A-Za-z0-9._-]` 组成。
     * 因此每个字段都被收紧到它实际可能出现的取值：
     *  - 布尔字段只接受 `true` / `false`（含 `digest_ok`，它只是「摘要对不对」的结论）；
     *  - 计数字段只接受 1–12 位数字（含 `expected_bytes` / `actual_bytes` / `free_bytes` 三个字节数）；
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
