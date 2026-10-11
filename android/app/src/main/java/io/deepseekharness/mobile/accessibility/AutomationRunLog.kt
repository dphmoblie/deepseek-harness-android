package io.deepseekharness.mobile.accessibility

/**
 * 一次自动化执行的**受控记录**。
 *
 * 只搬运受控码与布尔：`AutomationActionResult` 里的 `reason`（中文句子）、`nodePathText`、`bounds`、
 * `fingerprint` 一律不带出来——它们能反推出界面内容，而这条记录会经桥接回到前端界面。
 * 中文说明由 [AutomationSkipMessages] 按码现取，两者不合并成同一个字段。
 */
internal data class AutomationRunRecord(
    /** 记录时刻，由调用方注入（服务传挂钟毫秒）。 */
    val atMs: Long,
    val ruleId: String,
    /** 实际执行的动作类型，取值来自规则模型（受控短串，不含界面文本）。 */
    val action: String,
    val code: String,
    val ok: Boolean,
    /** 是否真的向后端发出过动作；试运行、节点消失、后端缺失都是 false。 */
    val performed: Boolean,
    val degraded: Boolean,
)

/** 一段窗口内按码聚合的拦截/跳过原因，供「敏感页面拦截原因」卡片展示。 */
internal data class AutomationBlockedReason(val code: String, val count: Int)

/**
 * `AutomationExecutionReport` → 界面可用的受控记录（纯函数，可 JVM 单测）。
 */
internal object AutomationRunLogPolicy {
    /** 每个包名保留的记录条数上限。 */
    const val MAX_RECORDS_PER_PACKAGE = 50

    /** 聚合后保留的原因码条数上限（按出现次数取最多的几个）。 */
    const val MAX_BLOCKED_CODES = 16

    /**
     * 提取记录。**保留全部结果**（含试运行的 `performed = false`），因为界面要回答的是
     * 「规则有没有被评估到」，而不只是「有没有点下去」。
     */
    fun records(
        report: AutomationExecutionReport,
        nowMs: Long,
        limit: Int = MAX_RECORDS_PER_PACKAGE,
    ): List<AutomationRunRecord> {
        val bounded = limit.coerceAtLeast(0)
        if (bounded == 0) return emptyList()
        return report.results.take(bounded).map { result ->
            AutomationRunRecord(
                atMs = nowMs,
                ruleId = result.ruleId,
                action = result.action,
                code = result.code,
                ok = result.ok,
                performed = result.performed,
                degraded = result.degraded,
            )
        }
    }

    /**
     * 聚合 `report.skipped` 的原因码：计数降序、同数按码升序，结果稳定（用例逐字断言）。
     *
     * 闸门的拒绝（锁屏、敏感窗口、节流、白名单）与匹配器的跳过都在 `skipped` 里，
     * 因此这一份就够界面回答「这次为什么没动」。
     */
    fun blocked(
        report: AutomationExecutionReport,
        limit: Int = MAX_BLOCKED_CODES,
    ): List<AutomationBlockedReason> {
        val bounded = limit.coerceAtLeast(0)
        if (bounded == 0) return emptyList()
        return report.skipped
            .groupingBy { it.code }
            .eachCount()
            .map { (code, count) -> AutomationBlockedReason(code, count) }
            .sortedWith(compareByDescending<AutomationBlockedReason> { it.count }.thenBy { it.code })
            .take(bounded)
    }
}

/**
 * 自动化运行记录的**进程内**环形缓冲，按目标包名分桶。
 *
 * 刻意不落盘：它是「刚刚发生了什么」的运维视图，写进磁盘只会扩大隐私面（界面历史、包名），
 * 而进程重启后本来也该从空开始。条数上限沿用 [AutomationRunLogPolicy.MAX_RECORDS_PER_PACKAGE]，
 * 超出时丢弃最旧的记录并累计 [droppedCount]，界面据此如实说明「只保留最近 N 条」。
 *
 * 线程约束：只在无障碍服务的主线程里读写（服务的事件回调与桥接命令都在这条线上）。
 */
internal class AutomationRunHistory(
    private val capacity: Int = AutomationRunLogPolicy.MAX_RECORDS_PER_PACKAGE,
) {
    private class Bucket {
        val runs = ArrayDeque<AutomationRunRecord>()
        val blocked = mutableMapOf<String, Int>()
    }

    private val buckets = linkedMapOf<String, Bucket>()
    private var dropped = 0

    /** 因超出容量被丢弃的记录条数（累计，不随 [clear] 之外的读操作变化）。 */
    val droppedCount: Int get() = dropped

    val limit: Int get() = capacity.coerceAtLeast(0)

    /** 写入一次执行的记录与拦截原因。包名为空即忽略（服务侧拿不到前台包时不该记一条无主记录）。 */
    fun record(packageName: String, report: AutomationExecutionReport, nowMs: Long) {
        if (packageName.isEmpty()) return
        val bucket = buckets.getOrPut(packageName) { Bucket() }
        for (entry in AutomationRunLogPolicy.blocked(report)) {
            bucket.blocked[entry.code] = (bucket.blocked[entry.code] ?: 0) + entry.count
        }
        val bounded = limit
        if (bounded == 0) return
        for (record in AutomationRunLogPolicy.records(report, nowMs, bounded)) {
            bucket.runs.addLast(record)
            while (bucket.runs.size > bounded) {
                bucket.runs.removeFirst()
                dropped++
            }
        }
    }

    /** 最新在前。`packageName` 为空表示「所有包名按时间合并」。 */
    fun runs(packageName: String? = null, limit: Int = this.limit): List<AutomationRunRecord> {
        val bounded = limit.coerceAtLeast(0)
        if (bounded == 0) return emptyList()
        val source = if (packageName == null) {
            // 跨包合并要按时间排，不能按「桶的先后」拼：否则先出现的包永远排在前面，
            // 界面上就会看到「最近一次执行」被一个更早的包压在下面。
            // sortedWith 是稳定排序，同一毫秒的多条记录仍保持写入顺序。
            buckets.values.flatMap { it.runs }.sortedWith(compareBy { it.atMs })
        } else {
            buckets[packageName]?.runs.orEmpty()
        }
        // 同一拍写入的多条记录时刻相同，靠插入顺序（即列表顺序）稳定；取尾部是新记录。
        return source.takeLast(bounded).reversed()
    }

    /** 拦截原因聚合：计数降序、同数按码升序。 */
    fun blocked(
        packageName: String? = null,
        limit: Int = AutomationRunLogPolicy.MAX_BLOCKED_CODES,
    ): List<AutomationBlockedReason> {
        val bounded = limit.coerceAtLeast(0)
        if (bounded == 0) return emptyList()
        val totals = mutableMapOf<String, Int>()
        val source = if (packageName == null) buckets.values else listOfNotNull(buckets[packageName])
        for (bucket in source) {
            for ((code, count) in bucket.blocked) {
                totals[code] = (totals[code] ?: 0) + count
            }
        }
        return totals
            .map { (code, count) -> AutomationBlockedReason(code, count) }
            .sortedWith(compareByDescending<AutomationBlockedReason> { it.count }.thenBy { it.code })
            .take(bounded)
    }

    /** 出现过的包名（按首次出现顺序）。 */
    fun packages(): List<String> = buckets.keys.toList()

    fun clear() {
        buckets.clear()
        dropped = 0
    }
}
