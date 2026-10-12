package io.deepseekharness.mobile.accessibility

/**
 * 单应用任务台账：把「AI 要在这个应用里做一件事」从**一串互不相干的单次动作**变成
 * **一份有步骤状态的台账**。
 *
 * 本文件**不 import 任何 `android.*`**，理由与 [AutomationRuleExecutor] 相同：步骤顺序、重试上限、
 * 超时口径、目标过期、重复提交这些边界都必须能在 JVM 单测里精确构造。
 *
 * ## 它不做什么（刻意）
 *
 * 台账**不自己执行动作**，也**不替用户点确认浮层**。每一次真实动作仍然走既有的一次动作一条命令
 * （`accessibilityAction` / `tap` / `inputText`），该要的用户确认一步都不少。台账负责的是
 * 「这一步该不该发、发过之后算不算成、还能不能再发、目标是否还是原来那个」。原因是安全侧的口径：
 * 一次性确认 N 步等于把「动作前确认」变成「任务前确认」，那是**放宽**而不是增强，必须由用户拍板，
 * 不能顺手做掉。
 *
 * ## 三条不可放松的口径
 *
 * 1. **不确定就是不确定**：动作发出去了但没观察到预期变化 ⇒ 该步记 `unknown`（不是 `failed`）。
 *    「没执行」与「执行了但没看出效果」在真机上无法区分，而把它说成失败会诱导调用方立刻重发——
 *    对「发送、提交、支付」这类不可逆动作，重发就是重复下单。`unknown` **永不自动重试**。
 * 2. **认不出的失败码按不安全处理**：只有明确列在 [AutomationTaskRetry.RETRYABLE] 里的码才允许再试一次；
 *    其余一律 `unknown` + `NEEDS_CHECK`。新增一个设备侧错误码时，默认结果是「不许重试」，
 *    而不是「悄悄变成可重试」。
 * 3. **目标不是原来那个就不发**：前台换了应用（[AutomationTaskCodes.TARGET_LEFT]）或同一应用换了界面
 *    （[AutomationTaskCodes.TARGET_STALE]）时，台账把任务置 `blocked` 并给出恢复动作，
 *    绝不用过期坐标/过期指纹继续点。
 */

/** 任务台账的受控码。取值必须匹配 `AuditPolicy.detailPattern`（`^[A-Z][A-Z0-9_]{0,63}$`）。 */
internal object AutomationTaskCodes {
    // 计划期
    const val PLAN_INVALID = "AUTOMATION_TASK_PLAN_INVALID"
    const val TOO_MANY_STEPS = "AUTOMATION_TASK_TOO_MANY_STEPS"

    // 线上形状（参数根本不是那份形状，与台账状态无关，见 `AutomationTaskWire`）
    const val PARAM_INVALID = "AUTOMATION_TASK_PARAM_INVALID"
    const val RECORD_INVALID = "AUTOMATION_TASK_RECORD_INVALID"

    // 台账闸门
    const val NOT_PLANNED = "AUTOMATION_TASK_NOT_PLANNED"
    const val SERVICE_DISCONNECTED = "AUTOMATION_TASK_SERVICE_DISCONNECTED"
    const val BUSY = "AUTOMATION_TASK_BUSY"
    const val STEP_UNKNOWN = "AUTOMATION_TASK_STEP_UNKNOWN"
    const val STEP_OUT_OF_ORDER = "AUTOMATION_TASK_STEP_OUT_OF_ORDER"
    const val STEP_ALREADY_SETTLED = "AUTOMATION_TASK_STEP_ALREADY_SETTLED"
    const val CANCELLED = "AUTOMATION_TASK_CANCELLED"

    // 场景类拒绝（重发没有意义，换时机/换配置才有）
    const val TARGET_LEFT = "AUTOMATION_TASK_TARGET_LEFT"
    const val TARGET_STALE = "AUTOMATION_TASK_TARGET_STALE"
    const val DEVICE_LOCKED = "AUTOMATION_TASK_DEVICE_LOCKED"
    const val SENSITIVE_WINDOW = "AUTOMATION_TASK_SENSITIVE_WINDOW"
    const val PACKAGE_NOT_ALLOWED = "AUTOMATION_TASK_PACKAGE_NOT_ALLOWED"

    // 结果分类
    const val TIMEOUT = "AUTOMATION_TASK_TIMEOUT"
    const val ATTEMPTS_EXHAUSTED = "AUTOMATION_TASK_ATTEMPTS_EXHAUSTED"
    const val NEEDS_CHECK = "AUTOMATION_TASK_NEEDS_CHECK"
    const val VERIFIED = "AUTOMATION_TASK_VERIFIED"
    const val NOT_VERIFIED = "AUTOMATION_TASK_NOT_VERIFIED"
}

/** 步骤状态。`unknown` 是**独立**一档，不允许被折进 `failed`。 */
internal object AutomationTaskStepStatus {
    const val PENDING = "pending"
    const val OK = "ok"
    const val FAILED = "failed"
    const val UNKNOWN = "unknown"
    const val SKIPPED = "skipped"

    fun settled(status: String): Boolean = status == OK || status == FAILED || status == UNKNOWN || status == SKIPPED
}

/** 任务状态。 */
internal object AutomationTaskStatus {
    const val PLANNED = "planned"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val BLOCKED = "blocked"
    const val CANCELLED = "cancelled"
}

/** 动作与校验模式的受控集合。模式决定「这一步怎么算成」。 */
internal object AutomationTaskActions {
    const val CLICK = "click"
    const val SET_TEXT = "setText"
    const val SCROLL = "scroll"
    const val BACK = "back"
    const val WAIT = "wait"

    val ALL = listOf(CLICK, SET_TEXT, SCROLL, BACK, WAIT)

    const val VERIFY_NONE = "none"
    const val VERIFY_WINDOW_CHANGED = "windowChanged"
    const val VERIFY_TARGET_GONE = "targetGone"
    const val VERIFY_TEXT_APPLIED = "textApplied"
    const val VERIFY_PACKAGE_CHANGED = "packageChanged"

    /** 允许的校验模式（按动作给定）：写错组合在校验期就拒，不用等到真机上才发现「验不了」。 */
    fun verifyModes(action: String): List<String> = when (action) {
        CLICK -> listOf(VERIFY_WINDOW_CHANGED, VERIFY_TARGET_GONE, VERIFY_NONE)
        SET_TEXT -> listOf(VERIFY_TEXT_APPLIED, VERIFY_WINDOW_CHANGED, VERIFY_NONE)
        SCROLL -> listOf(VERIFY_WINDOW_CHANGED, VERIFY_NONE)
        BACK -> listOf(VERIFY_PACKAGE_CHANGED, VERIFY_WINDOW_CHANGED, VERIFY_NONE)
        WAIT -> listOf(VERIFY_NONE)
        else -> emptyList()
    }

    /**
     * 默认校验模式（调用方不写时用这个）。
     *
     * `wait` 与 `scroll` 默认 `none`：等一会儿与世界面变化本来就没有必然的一一对应；
     * 给它们硬塞一个「必须变」反而会把正常结果判成 `unknown`。
     */
    fun defaultVerify(action: String): String = when (action) {
        CLICK -> VERIFY_WINDOW_CHANGED
        SET_TEXT -> VERIFY_TEXT_APPLIED
        BACK -> VERIFY_PACKAGE_CHANGED
        else -> VERIFY_NONE
    }

    /** 这一步需不需要校验：`none` 之外都要，且未通过时记 `unknown`。 */
    fun requiresVerification(verify: String): Boolean = verify != VERIFY_NONE
}

/** 重试口径：唯一出处。 */
internal object AutomationTaskRetry {
    /**
     * 明确**可以**再试一次的失败码（动作可判定为没生效，重发不会重复产生副作用）。
     *
     * 逐个都要求「设备侧明确告诉我们这次没动成」：
     * - `AUTOMATION_NODE_UNRESOLVED` / `AUTOMATION_NODE_STALE`：节点没找到或已经不是那个节点；
     * - `AUTOMATION_CLICK_FAILED` / `AUTOMATION_SWIPE_FAILED` / `AUTOMATION_KEY_FAILED`：系统拒绝了这次注入；
     * - `ACCESSIBILITY_WINDOW_UNAVAILABLE`：当时没有可读窗口；
     * - `ACCESSIBILITY_RATE_LIMITED`：被动作间隔拦下，根本没发出去。
     */
    val RETRYABLE = setOf(
        "AUTOMATION_NODE_UNRESOLVED",
        "AUTOMATION_NODE_STALE",
        "AUTOMATION_CLICK_FAILED",
        "AUTOMATION_SWIPE_FAILED",
        "AUTOMATION_KEY_FAILED",
        "ACCESSIBILITY_WINDOW_UNAVAILABLE",
        "ACCESSIBILITY_RATE_LIMITED",
    )

    /**
     * 场景类拒绝：**重发没有意义**，要换时机或换配置。它们不消耗重试次数。
     */
    val SCENE_REFUSALS = setOf(
        AutomationTaskCodes.TARGET_LEFT,
        AutomationTaskCodes.TARGET_STALE,
        AutomationTaskCodes.DEVICE_LOCKED,
        AutomationTaskCodes.SENSITIVE_WINDOW,
        AutomationTaskCodes.PACKAGE_NOT_ALLOWED,
        "ACCESSIBILITY_SENSITIVE_WINDOW",
        "ACCESSIBILITY_PACKAGE_DENIED",
        "ACCESSIBILITY_DEVICE_LOCKED",
        "ACCESSIBILITY_ACTION_CANCELLED",
        "AUTOMATION_ACTION_UNSUPPORTED",
        "AUTOMATION_SELF_PACKAGE_BLOCKED",
    )

    /** 允许再试一次：次数还有余量，且这个码明确属于可重试类。 */
    fun mayRetry(code: String?, attemptsUsed: Int, maxAttempts: Int): Boolean =
        attemptsUsed < maxAttempts && code != null && code in RETRYABLE
}

/** 一步的计划。 */
internal data class AutomationTaskStepSpec(
    val id: String,
    val action: String,
    val target: String,
    val verify: String,
    val timeoutMs: Long,
    val maxAttempts: Int,
)

/** 台账里一步的现状。 */
internal data class AutomationTaskStepState(
    val id: String,
    val action: String,
    val verify: String,
    val timeoutMs: Long,
    val maxAttempts: Int,
    val status: String = AutomationTaskStepStatus.PENDING,
    val attemptsUsed: Int = 0,
    val code: String? = null,
    val startedAtMs: Long? = null,
    /** 这一步**开始时**的窗口指纹：重试前拿它与当前指纹比对，不相同就说明「失败时的现场已经不是现在这个」。 */
    val startedDigest: String? = null,
    val elapsedMs: Long = 0,
)

/** 一次动作回来的结果（由服务侧填，台账只做分类）。 */
internal data class AutomationTaskStepOutcome(
    val ok: Boolean,
    val code: String? = null,
    val verified: Boolean = true,
    val timedOut: Boolean = false,
)

/** 现场事实：闸门只看这几个，不猜。 */
internal data class AutomationTaskFacts(
    val foregroundPackage: String,
    val windowDigest: String,
    val locked: Boolean = false,
    val sensitiveWindow: Boolean = false,
    val packageAllowed: Boolean = true,
    val serviceConnected: Boolean = true,
)

/** 闸门结论。 */
internal sealed interface AutomationTaskGate {
    /** 放行：把这一步记成「在跑」，调用方随后必须用 [AutomationTaskLedger.record] 交回结果。 */
    data class Proceed(val stepId: String, val attempt: Int) : AutomationTaskGate

    /** 拒发。任务状态可能被置成 `blocked`（场景类）或保持原样（可重试类）。 */
    data class Refused(val code: String, val message: String, val retryable: Boolean) : AutomationTaskGate
}

/** 对外快照（桥接层直接序列化它，不另抄一份字段）。 */
internal data class AutomationTaskSnapshot(
    val taskId: String,
    val status: String,
    val packageName: String,
    val steps: List<AutomationTaskStepState>,
    val nextStepId: String?,
    val lastCode: String?,
    val lastMessage: String?,
) {
    val settledSteps: Int get() = steps.count { AutomationTaskStepStatus.settled(it.status) }
    val pendingSteps: Int get() = steps.count { it.status == AutomationTaskStepStatus.PENDING }
}

/**
 * 台账本体。单槽位：同一时刻只允许一个任务（[AutomationTaskCodes.BUSY]）。
 *
 * 单槽位是**真机的诚实反映**：无障碍动作之间有 `ACTION_INTERVAL_MS = 350L` 的全局间隔，
 * 而且每一步都可能弹出用户确认浮层（`pendingConfirmation` 也是单槽位）。允许多任务排队只会
 * 制造「排队中的任务在几分钟后突然开始点屏幕」这种用户无法预期的行为。
 */
internal class AutomationTaskLedger {
    private var taskId: String? = null
    private var status: String = AutomationTaskStatus.PLANNED
    private var packageName: String = ""
    private var digest: String? = null
    private var steps: MutableList<AutomationTaskStepState> = mutableListOf()
    private var lastCode: String? = null
    private var lastMessage: String? = null

    /** 计划：校验 + 落台账。同 id 的旧任务（已收口）允许被新计划覆盖，未收口的直接 [AutomationTaskCodes.BUSY]。 */
    fun plan(
        taskId: String,
        packageName: String,
        windowDigest: String?,
        specs: List<AutomationTaskStepSpec>,
    ): AutomationTaskGate {
        if (!AutomationTaskPlan.isTaskId(taskId)) return invalid("任务标识不合法")
        if (!AutomationTaskPlan.isPackageName(packageName)) return invalid("目标应用包名不合法")
        if (specs.isEmpty()) return invalid("任务至少要有一个步骤")
        if (specs.size > AutomationTaskPlan.MAX_STEPS) {
            lastCode = AutomationTaskCodes.TOO_MANY_STEPS
            lastMessage = "任务步骤过多（最多 ${AutomationTaskPlan.MAX_STEPS} 步）"
            return AutomationTaskGate.Refused(lastCode!!, lastMessage!!, false)
        }
        val seen = mutableSetOf<String>()
        for (spec in specs) {
            val problem = AutomationTaskPlan.validate(spec, seen)
            if (problem != null) return invalid(problem)
            seen.add(spec.id)
        }
        // 单槽位：只要台账里那个任务还没收口，就**不接受任何新计划**——包括同一个 taskId 的重复提交。
        // 重复提交要的是「别执行第二次」，而不是「再执行一遍」：调用方改用 op:"status" 读现状。
        // 只有已收口（done / failed / cancelled）的任务才允许被新计划覆盖。
        if (this.taskId != null && !automationTaskSettled(this.status)) {
            lastCode = AutomationTaskCodes.BUSY
            lastMessage = "已有一个未收口的任务（$taskId），先取消或等它结束"
            return AutomationTaskGate.Refused(lastCode!!, lastMessage!!, false)
        }
        this.taskId = taskId
        this.packageName = packageName
        this.digest = windowDigest
        this.steps = specs.map { AutomationTaskStepState(it.id, it.action, it.verify, it.timeoutMs, it.maxAttempts) }.toMutableList()
        this.status = AutomationTaskStatus.PLANNED
        this.lastCode = null
        this.lastMessage = null
        return AutomationTaskGate.Proceed(specs.first().id, 0)
    }

    /** 闸门：能不能发这一步。 */
    fun gate(stepId: String, facts: AutomationTaskFacts, nowMs: Long): AutomationTaskGate {
        val current = taskId ?: return refuse(AutomationTaskCodes.NOT_PLANNED, "还没有任务：先提交计划", false)
        if (status == AutomationTaskStatus.CANCELLED) return refuse(AutomationTaskCodes.CANCELLED, "任务已取消", false)
        val index = steps.indexOfFirst { it.id == stepId }
        if (index < 0) return refuse(AutomationTaskCodes.STEP_UNKNOWN, "任务里没有这个步骤", false)
        val step = steps[index]
        if (step.status == AutomationTaskStepStatus.OK) {
            // 幂等：同一步重复提交不是错误，而是**明确不加执行**（调用方拿返回的状态自己判断）。
            return refuse(AutomationTaskCodes.STEP_ALREADY_SETTLED, "这一步已经成功，不再执行", false)
        }
        val firstPending = steps.indexOfFirst { it.status == AutomationTaskStepStatus.PENDING }
        if (step.status == AutomationTaskStepStatus.UNKNOWN) {
            // 结果不确定的一步：**不重发**，也**不算完成**。调用方要么确认现场后收口，要么重新规划。
            return refuse(AutomationTaskCodes.NEEDS_CHECK, "这一步的结果不确定（${step.code ?: "无码"}）：先确认现场，不要直接重发", false)
        }
        if (step.status != AutomationTaskStepStatus.PENDING && step.status != AutomationTaskStepStatus.FAILED) {
            return refuse(AutomationTaskCodes.STEP_ALREADY_SETTLED, "这一步已经收口（${step.status}）", false)
        }
        if (firstPending >= 0 && index > firstPending) {
            return refuse(AutomationTaskCodes.STEP_OUT_OF_ORDER, "先做第 ${firstPending + 1} 步（${steps[firstPending].id}）", false)
        }
        if (!facts.packageAllowed) return block(AutomationTaskCodes.PACKAGE_NOT_ALLOWED, "当前应用不在无障碍自动化白名单中")
        if (!facts.serviceConnected) return block(AutomationTaskCodes.SERVICE_DISCONNECTED, "无障碍服务未连接")
        if (facts.locked) return block(AutomationTaskCodes.DEVICE_LOCKED, "设备已锁定或熄屏")
        if (facts.sensitiveWindow) return block(AutomationTaskCodes.SENSITIVE_WINDOW, "检测到密码、验证码、支付或权限窗口")
        if (facts.foregroundPackage != packageName) {
            return block(AutomationTaskCodes.TARGET_LEFT, "前台已不是 $packageName（现在是 ${facts.foregroundPackage}）")
        }
        // 计划做好之后、第一步真正发出去之前，界面就变了 —— 这份计划是按旧界面写的，不能用。
        val planned = digest
        val started = steps.any { it.attemptsUsed > 0 }
        if (!started && planned != null && facts.windowDigest != planned) {
            return block(AutomationTaskCodes.TARGET_STALE, "计划做好之后界面已经变化：请重新读取节点树，再用新的指纹恢复任务")
        }
        if (step.status == AutomationTaskStepStatus.FAILED) {
            if (!AutomationTaskRetry.mayRetry(step.code, step.attemptsUsed, step.maxAttempts)) {
                val code = if (step.code in AutomationTaskRetry.RETRYABLE) AutomationTaskCodes.ATTEMPTS_EXHAUSTED else AutomationTaskCodes.NEEDS_CHECK
                return refuse(code, "这一步上次的结果不能重发（${step.code ?: "无码"}），请先确认现场", false)
            }
            // 失败时的现场已经不是现在这个：先重新读，别拿旧目标再试一次。
            if (step.startedDigest != null && facts.windowDigest != step.startedDigest) {
                return block(AutomationTaskCodes.TARGET_STALE, "这一步失败之后界面又变了：先重新读取节点树，再决定还要不要重发")
            }
        }
        val attempt = step.attemptsUsed + 1
        steps[index] = step.copy(
            status = AutomationTaskStepStatus.PENDING,
            startedAtMs = nowMs,
            startedDigest = facts.windowDigest,
            attemptsUsed = attempt,
        )
        status = AutomationTaskStatus.RUNNING
        lastCode = null
        lastMessage = null
        return AutomationTaskGate.Proceed(stepId, attempt)
    }

    /**
     * 记结果。**分类规则就是本文件的全部价值**：
     *
     * | 输入 | 记成 |
     * | --- | --- |
     * | 超时 | `unknown` + [AutomationTaskCodes.TIMEOUT]（可能已经动过，绝不重发） |
     * | 失败且需校验但没通过校验 | `unknown` + [AutomationTaskCodes.NOT_VERIFIED] |
     * | 失败 + 明确可重试的码 | `failed` + 原码（还允许再试，直到次数用完） |
     * | 失败 + 其余码 | `unknown` + [AutomationTaskCodes.NEEDS_CHECK]（认不出来就当不安全） |
     * | 成功但没通过校验 | `unknown` + [AutomationTaskCodes.NOT_VERIFIED] |
     * | 成功且通过校验 | `ok` |
     */
    fun record(stepId: String, outcome: AutomationTaskStepOutcome, nowMs: Long): AutomationTaskSnapshot {
        val index = steps.indexOfFirst { it.id == stepId }
        if (index < 0) return snapshot()
        val step = steps[index]
        val elapsed = step.startedAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0
        val settled = classify(step, outcome).copy(elapsedMs = elapsed, startedAtMs = null)
        steps[index] = settled
        lastCode = settled.code
        lastMessage = message(settled)
        if (steps.all { AutomationTaskStepStatus.settled(it.status) }) {
            status = if (steps.any { it.status != AutomationTaskStepStatus.OK }) AutomationTaskStatus.FAILED
            else AutomationTaskStatus.DONE
        } else {
            // 还有未收口的步骤：任务继续挂着，调用方可以接着走下一步、重试可重试的那一步，或者取消。
            status = AutomationTaskStatus.RUNNING
        }
        return snapshot()
    }

    /** 取消：未收口的步骤记 `skipped` + [AutomationTaskCodes.CANCELLED]。 */
    fun cancel(): AutomationTaskSnapshot {
        steps = steps.map { step ->
            if (AutomationTaskStepStatus.settled(step.status)) step
            else step.copy(status = AutomationTaskStepStatus.SKIPPED, code = AutomationTaskCodes.CANCELLED)
        }.toMutableList()
        if (taskId != null) status = AutomationTaskStatus.CANCELLED
        lastCode = AutomationTaskCodes.CANCELLED
        lastMessage = "任务已取消"
        return snapshot()
    }

    /**
     * 恢复：目标变了之后，用**新的窗口指纹**把被阻塞的任务重新打开。
     *
     * 只接受与当前计划**完全相同的步骤 id 列表**：换目标的重新规划是调用方的事（它得重新读节点树），
     * 台账不做「帮你把剩下的步骤改一改」这种事——那等于替调用方猜目标。
     */
    fun resume(stepIds: List<String>, windowDigest: String): AutomationTaskGate {
        if (taskId == null) return refuse(AutomationTaskCodes.NOT_PLANNED, "还没有任务：先提交计划", false)
        if (steps.map { it.id } != stepIds) return invalid("恢复时的步骤列表与原计划不一致")
        digest = windowDigest
        status = AutomationTaskStatus.RUNNING
        lastCode = null
        lastMessage = null
        return AutomationTaskGate.Proceed(steps.firstOrNull { it.status == AutomationTaskStepStatus.PENDING }?.id ?: stepIds.last(), 0)
    }

    fun snapshot(): AutomationTaskSnapshot = AutomationTaskSnapshot(
        taskId = taskId.orEmpty(),
        status = status,
        packageName = packageName,
        steps = steps.toList(),
        nextStepId = steps.firstOrNull { it.status == AutomationTaskStepStatus.PENDING }?.id,
        lastCode = lastCode,
        lastMessage = lastMessage,
    )

    /** 阻塞：场景类拒绝把任务置 `blocked`，等 [resume]。 */
    private fun block(code: String, message: String): AutomationTaskGate {
        if (taskId != null) status = AutomationTaskStatus.BLOCKED
        lastCode = code
        lastMessage = message
        return AutomationTaskGate.Refused(code, message, false)
    }

    private fun refuse(code: String, message: String, retryable: Boolean): AutomationTaskGate {
        lastCode = code
        lastMessage = message
        return AutomationTaskGate.Refused(code, message, retryable)
    }

    private fun invalid(message: String): AutomationTaskGate {
        lastCode = AutomationTaskCodes.PLAN_INVALID
        lastMessage = message
        return AutomationTaskGate.Refused(AutomationTaskCodes.PLAN_INVALID, message, false)
    }

    private fun classify(step: AutomationTaskStepState, outcome: AutomationTaskStepOutcome): AutomationTaskStepState {
        val needsVerify = AutomationTaskActions.requiresVerification(step.verify)
        return when {
            outcome.timedOut -> step.copy(status = AutomationTaskStepStatus.UNKNOWN, code = AutomationTaskCodes.TIMEOUT)
            outcome.ok && (!needsVerify || outcome.verified) -> step.copy(status = AutomationTaskStepStatus.OK, code = AutomationTaskCodes.VERIFIED)
            outcome.ok -> step.copy(status = AutomationTaskStepStatus.UNKNOWN, code = AutomationTaskCodes.NOT_VERIFIED)
            needsVerify && !outcome.verified -> step.copy(status = AutomationTaskStepStatus.UNKNOWN, code = AutomationTaskCodes.NOT_VERIFIED)
            outcome.code != null && outcome.code in AutomationTaskRetry.RETRYABLE ->
                step.copy(status = AutomationTaskStepStatus.FAILED, code = outcome.code)
            else -> step.copy(status = AutomationTaskStepStatus.UNKNOWN, code = outcome.code ?: AutomationTaskCodes.NEEDS_CHECK)
        }
    }

    private fun message(step: AutomationTaskStepState): String = when (step.code) {
        AutomationTaskCodes.VERIFIED -> "第 ${step.id} 步已完成"
        AutomationTaskCodes.TIMEOUT -> "第 ${step.id} 步超时：这次是否已经生效不确定，请先确认现场再决定"
        AutomationTaskCodes.NOT_VERIFIED -> "第 ${step.id} 步发出去了但没有观察到预期变化：先确认现场，不要直接重发"
        AutomationTaskCodes.NEEDS_CHECK -> "第 ${step.id} 步结果不确定（${step.code ?: "无码"}）：先确认现场"
        else -> "第 ${step.id} 步失败：${step.code ?: "无码"}"
    }
}

/** 计划校验（纯函数，便于逐条单测）。 */
internal object AutomationTaskPlan {
    const val MAX_STEPS = 20
    const val MIN_TIMEOUT_MS = 100L
    const val MAX_TIMEOUT_MS = 30_000L
    const val MAX_ATTEMPTS = 3
    const val MAX_TARGET_CHARS = 200
    const val MAX_TASK_ID_CHARS = 64
    const val MAX_PACKAGE_CHARS = 192

    private val TASK_ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val PACKAGE = Regex("^(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")

    fun isTaskId(value: String): Boolean = value.length in 1..MAX_TASK_ID_CHARS && TASK_ID.matches(value)

    /** 与 `PluginInstallLock.isPackageName` 同一把尺子的子集：这里只认完整包名，不需要 `..` 那几道。 */
    fun isPackageName(value: String): Boolean = value.length in 1..MAX_PACKAGE_CHARS && PACKAGE.matches(value)

    /** 返回 null 表示这条计划合法；否则是一句给调用方看的拒绝原因。 */
    fun validate(spec: AutomationTaskStepSpec, seen: MutableSet<String>): String? {
        if (!isTaskId(spec.id)) return "步骤标识不合法（只允许小写字母、数字、下划线、短横线）"
        if (spec.id in seen) return "步骤标识重复：${spec.id}"
        if (spec.action !in AutomationTaskActions.ALL) return "步骤 ${spec.id} 的动作不受支持"
        if (spec.verify !in AutomationTaskActions.verifyModes(spec.action)) return "步骤 ${spec.id} 的校验模式与动作不匹配"
        if (spec.action != AutomationTaskActions.WAIT && spec.target.isBlank()) return "步骤 ${spec.id} 缺少目标"
        if (spec.target.length > MAX_TARGET_CHARS) return "步骤 ${spec.id} 的目标过长"
        if (spec.action == AutomationTaskActions.WAIT && spec.target.isNotEmpty()) return "等待步骤不接受目标"
        if (spec.timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) return "步骤 ${spec.id} 的超时不在 $MIN_TIMEOUT_MS..$MAX_TIMEOUT_MS 毫秒内"
        if (spec.maxAttempts !in 1..MAX_ATTEMPTS) return "步骤 ${spec.id} 的重试上限不在 1..$MAX_ATTEMPTS 内"
        return null
    }
}

/** 任务是否已收口（`done` / `failed` / `cancelled` 之外都算没收口）。 */
internal fun automationTaskSettled(status: String): Boolean =
    status == AutomationTaskStatus.DONE || status == AutomationTaskStatus.FAILED || status == AutomationTaskStatus.CANCELLED
