package io.deepseekharness.mobile.runtime

/**
 * 访客侧「任务完成」事件在原生侧的收单规则（登记册 §5.5 / §5.6-C）。
 *
 * 这个类的全部价值是**把不受信输入挡在通知系统之外**：桥上的请求来自容器内的访客进程，
 * 它只被允许说一句「某一轮结束了」，绝不允许决定通知的内容、频率或对象。
 * 因此这里做三件事，全部是纯函数，可以在 JVM 单测里逐条钉住：
 *
 *  1. 参数校验：只接受空串或一个极小 JSON（会话摘要 + 固定 reason），且长度封顶；
 *  2. 限流：同秒上限与最小间隔，命中即**静默忽略**（返回受理，不发通知，不报错）；
 *  3. 事件序号：给上层一个「刚才确实受理了一次完成」的单调计数，用于前台提示。
 *
 * 刻意不在这里做的事：不解析会话正文（根本没有这个字段）、不写盘、不读时间以外的状态。
 */
internal object TurnCompletionPolicy {

    /** `param` 的硬长度上限（字符）。极小 JSON 的合法形态最长远小于它。 */
    const val MAX_PARAM_CHARS = 96

    /** 同一秒内最多受理的完成事件数；超出的**静默忽略**。 */
    const val MAX_CALLS_PER_SECOND = 3

    /** 两次被受理的完成事件之间的最小间隔（毫秒）；更快的重复静默忽略。 */
    const val MIN_INTERVAL_MS = 1_000L

    /** 允许的 `reason`。固定集合，访客侧不得自造。 */
    val REASON_CODES = setOf("turn-end", "turn-error")

    /** `sessions` 的上限：一轮里最多允许声称完成这么多件事。 */
    const val MAX_SESSIONS = 20

    /** 会话摘要：16 位小写十六进制。不是路径、标题、cwd 或正文。 */
    private val sessionDigestPattern = Regex("^[0-9a-f]{16}$")

    private val reasonPattern = Regex("^[a-z][a-z-]{0,15}$")

    /** 参数校验结论。 */
    sealed interface Parsed {
        /** 合法参数：`session` 为 null 表示这次事件不带会话摘要。 */
        data class Valid(val session: String?) : Parsed

        /** 非法参数：不予受理，也不落任何原文。 */
        data object Invalid : Parsed
    }

    /** 限流判定的受控原因；只有这两条，便于审计按同一组取值排查。 */
    enum class DropReason { TOO_SOON, LIMIT }

    /**
     * 桥侧收单点持有的状态。
     *
     * 刻意**不是**线程安全的可变结构：桥的线程池只有 2 个线程，且本类只在
     * `synchronized` 块内使用（见 [TaskNotification.recordTurnCompleted] 的调用点）。
     * 换成 AtomicLong 会让「同一秒计数」和「上次受理时刻」这两项无法一起原子更新。
     */
    class State {
        private var windowStartedAtMs: Long = 0L
        private var callsInWindow: Int = 0
        private var lastAcceptedAtMs: Long = Long.MIN_VALUE

        /**
         * 尝试受理一次完成事件；返回 null 表示受理，否则是被静默忽略的原因。
         *
         * 三条判定的顺序是有意的：先判同秒配额（防止用极短间隔的密集请求把
         * 「最小间隔」这条规则变成噪声源），再判最小间隔。
         */
        @Synchronized
        fun claim(nowMs: Long): DropReason? {
            if (windowStartedAtMs == 0L || nowMs - windowStartedAtMs >= SECOND_MS) {
                windowStartedAtMs = nowMs
                callsInWindow = 0
            }
            if (callsInWindow >= MAX_CALLS_PER_SECOND) return DropReason.LIMIT
            if (lastAcceptedAtMs != Long.MIN_VALUE && nowMs - lastAcceptedAtMs < MIN_INTERVAL_MS) {
                return DropReason.TOO_SOON
            }
            callsInWindow++
            lastAcceptedAtMs = nowMs
            return null
        }
    }

    /**
     * 解析 `param`。
     *
     * 只要出现任何一个未预期的键，整条请求作废 —— 这是刻意选择的失败方向：
     * 「多带了什么」比「少带了什么」更值得警惕，而宽容解析会让未来某个版本
     * 悄悄开始接收会话标题之类的字段。
     */
    fun parseParam(param: String): Parsed {
        if (param.isEmpty()) return Parsed.Valid(null)
        if (param.length > MAX_PARAM_CHARS) return Parsed.Invalid
        val json = try {
            org.json.JSONObject(param)
        } catch (_: Throwable) {
            return Parsed.Invalid
        }
        val keys = json.keys().asSequence().toSet()
        if (keys.isEmpty() || keys.any { it !in KEYS }) return Parsed.Invalid
        val session = json.opt("session")
        val reason = json.opt("reason")
        val sessions = json.opt("sessions")
        if (session != null && (session !is String || !sessionDigestPattern.matches(session))) {
            return Parsed.Invalid
        }
        if (reason != null && (reason !is String || !reasonPattern.matches(reason) || reason !in REASON_CODES)) {
            return Parsed.Invalid
        }
        // `sessions` 只用于让访客侧说明「这一轮里完成了几件事」，因此只接受一个小整数。
        // 它不进通知文案、不进审计，只是给未来可能的聚合留一个受控入口。
        if (sessions != null && (sessions !is Int || sessions !in 1..MAX_SESSIONS)) {
            return Parsed.Invalid
        }
        return Parsed.Valid(session as String?)
    }

    private const val SECOND_MS = 1_000L
    private val KEYS = setOf("session", "reason", "sessions")
}
