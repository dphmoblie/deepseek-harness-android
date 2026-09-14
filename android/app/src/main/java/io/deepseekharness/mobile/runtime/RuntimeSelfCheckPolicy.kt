package io.deepseekharness.mobile.runtime

/**
 * 运行时自检的受控载荷策略（纯逻辑，不依赖 Android，可单测）。
 *
 * 自检结果来自访客里的 node 脚本，属于**跨进程的不可信输入**：即便脚本是我们自己发布的，
 * 也要在这里把 id / status / code 再校验一遍，不合法的条目直接丢弃 —— 这样回给 WebView 的
 * 永远只有封闭枚举，不会因为脚本改动把自由文本或路径带出来。
 *
 * 另一件必须在这里做的事是**汇总**：诊断日志只认「首个失败码 + 失败项数」，而「首个」是按
 * 契约顺序算的。这段判定与 UI 载荷构造分开，才能单测它的顺序语义。
 */
internal object RuntimeSelfCheckPolicy {
    const val CHECK = "check"
    const val REPAIR = "repair"

    /** 单次载荷的条目上限；自检固定只有十项，多出来的一律不可信。 */
    const val MAX_CHECKS = 32

    /** 计数上限：修复只有三个可改目标，超过这个数就说明载荷不可信。 */
    const val MAX_COUNT = 64

    /** 操作名白名单：其它值一律拒绝（返回 null，由调用方转成受控错误）。 */
    fun operation(value: String?): String? = value?.takeIf { it == CHECK || it == REPAIR }

    /** 检查项 id：契约冻结，**这个顺序就是回传顺序**（与访客脚本、界面一致）。 */
    val CHECK_IDS = listOf(
        "shell",
        "node",
        "sandbox_launcher",
        "sandbox_probe",
        "sandbox_exec",
        "pty",
        "pty_sandbox",
        "dsh_home",
        "attachments",
        "rg",
    )

    /** 状态白名单。`ok` 不带 `code`，其余状态必须带 `code`。 */
    private val STATUSES = setOf("ok", "warn", "fail", "skipped")

    /**
     * 每个检查项允许的「错误码 → 状态」表。
     *
     * 一张表同时约束两件事：码必须属于该检查项，且状态必须与码的语义一致
     * （`PROBE_PARTIAL` 只能是 warn、`ATTACHMENTS_MISSING` 只能是 warn、`PTY_MODULE_MISSING` 只能是 skipped）。
     *
     * `skipped` 的语义是「这一项没有可测的前提」，不是「测了但失败」。因此三个依赖沙箱启动器的
     * 检查项都允许 `LAUNCHER_MISSING`（skipped）：启动器缺失或没有执行位时，`sandbox_launcher`
     * 如实报 fail 并给出精确原因，`sandbox_probe` / `sandbox_exec` / `pty_sandbox` 一律跳过 ——
     * 报 `PROBE_UNUSABLE` 等于断言「这台设备的内核不支持 Landlock」，而那是没测过的结论。
     */
    private val CODES: Map<String, Map<String, String>> = mapOf(
        "shell" to mapOf("SHELL_MISSING" to "fail"),
        "node" to mapOf("NODE_MISSING" to "fail"),
        "sandbox_launcher" to mapOf(
            "LAUNCHER_MISSING" to "fail",
            "LAUNCHER_NOT_EXECUTABLE" to "fail",
        ),
        "sandbox_probe" to mapOf(
            "LAUNCHER_MISSING" to "skipped",
            "PROBE_UNUSABLE" to "fail",
            "PROBE_PARTIAL" to "warn",
        ),
        "sandbox_exec" to mapOf(
            "LAUNCHER_MISSING" to "skipped",
            "EXEC_LAUNCHER_FAILED" to "fail",
            "EXEC_COMMAND_FAILED" to "fail",
        ),
        "pty" to mapOf(
            "PTY_MODULE_MISSING" to "skipped",
            "PTY_LOAD_FAILED" to "fail",
            "PTY_EXIT_EARLY" to "fail",
            "PTY_TIMEOUT" to "fail",
        ),
        "pty_sandbox" to mapOf(
            "PTY_MODULE_MISSING" to "skipped",
            "LAUNCHER_MISSING" to "skipped",
            "PTY_LOAD_FAILED" to "fail",
            "PTY_EXIT_EARLY" to "fail",
            "PTY_TIMEOUT" to "fail",
        ),
        "dsh_home" to mapOf(
            "HOME_MISSING" to "fail",
            "HOME_NOT_WRITABLE" to "fail",
        ),
        "attachments" to mapOf(
            "ATTACHMENTS_MISSING" to "warn",
            "ATTACHMENTS_NOT_WRITABLE" to "fail",
        ),
        "rg" to mapOf(
            "RG_MISSING" to "warn",
            "RG_NOT_EXECUTABLE" to "warn",
        ),
    )

    /** 访客脚本回传的原始条目：字段都还没校验过。 */
    internal data class RawCheck(val id: String, val status: String, val code: String?)

    /** 校验后的条目：`code` 只在该检查项非 `ok` 时存在。 */
    internal data class Check(val id: String, val status: String, val code: String?)

    /** 十项检查的汇总：诊断日志只写这两个值。 */
    internal data class Summary(val firstFailureCode: String?, val failedCount: Int)

    /** 修复计数：`candidates` 是本次涉及的目标数，`repaired` 是实际改动的数量。 */
    internal data class RepairSummary(val repaired: Int, val candidates: Int)

    /**
     * 校验并归一化检查项：丢弃不合法条目，按契约顺序回传。
     *
     * 丢弃而不是猜测：两端对契约的理解一旦不一致，编造一个状态比少一行更危险 —— 那会把
     * 「PTY 真的失败了」与「脚本字段写错了」混成同一个结论。同一个 id 重复出现时保留首个。
     */
    fun sanitize(raw: List<RawCheck>): List<Check> {
        val accepted = LinkedHashMap<String, Check>()
        for (item in raw.take(MAX_CHECKS)) {
            val codes = CODES[item.id] ?: continue
            if (item.status !in STATUSES) continue
            if (item.status == "ok") {
                // `ok` 不允许带码：带码说明这条载荷自相矛盾，整条丢弃。
                if (item.code != null) continue
                accepted.putIfAbsent(item.id, Check(item.id, item.status, null))
                continue
            }
            val code = item.code ?: continue
            if (codes[code] != item.status) continue
            accepted.putIfAbsent(item.id, Check(item.id, item.status, code))
        }
        // 顺序一律以契约为准，不受访客输出顺序影响。
        return CHECK_IDS.mapNotNull { accepted[it] }
    }

    /** 汇总：失败只算 `fail`；`warn` 与 `skipped` 是「有情况」但不是失败，不改变整体结论。 */
    fun summarize(checks: List<Check>): Summary {
        val failures = checks.filter { it.status == "fail" }
        return Summary(firstFailureCode = failures.firstOrNull()?.code, failedCount = failures.size)
    }

    /**
     * 解析修复计数：缺失、负数、超过上限，或「改动数多于涉及目标数」的载荷一律视为不可信。
     * 后者不是多余校验：修复只做补可执行位与建附件目录，改动数不可能超过目标数。
     */
    fun repairSummary(repaired: Int?, candidates: Int?): RepairSummary? {
        if (repaired == null || candidates == null) return null
        if (repaired !in 0..MAX_COUNT || candidates !in 0..MAX_COUNT) return null
        if (repaired > candidates) return null
        return RepairSummary(repaired = repaired, candidates = candidates)
    }
}
