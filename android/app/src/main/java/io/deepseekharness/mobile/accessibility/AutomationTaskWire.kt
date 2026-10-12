package io.deepseekharness.mobile.accessibility

import org.json.JSONArray
import org.json.JSONObject

/**
 * `automationTask` 设备桥命令的入参形状。
 *
 * 为什么单独一层：命令处理跑在无障碍服务里（只有真机到得了），而参数形状是**契约**——
 * 放进纯 JVM 代码就能逐条单测，不用等到真机上才发现「少一个字段整条命令就挂」。
 */
internal sealed interface AutomationTaskRequest {
    data class Plan(
        val taskId: String,
        val packageName: String,
        val steps: List<AutomationTaskStepSpec>,
    ) : AutomationTaskRequest

    data class Gate(val stepId: String) : AutomationTaskRequest

    data class Record(val stepId: String, val outcome: AutomationTaskStepOutcome) : AutomationTaskRequest

    data class Resume(val stepIds: List<String>) : AutomationTaskRequest

    object Cancel : AutomationTaskRequest

    object Status : AutomationTaskRequest

    data class Invalid(val code: String, val message: String) : AutomationTaskRequest
}

/**
 * `automationTask` 的线上形状：解析入参、拼回执。
 *
 * 入参一律是一个 JSON 对象，`op` 决定其余字段（与既有的 `automationRules` 同风格，不用位置参数）：
 * - `{"op":"plan","taskId":"t1","packageName":"com.example.app","steps":[{"id":"s1","action":"click","target":"...","verify":"windowChanged","timeoutMs":5000,"maxAttempts":1}]}`
 * - `{"op":"gate","step":"s1"}`
 * - `{"op":"record","step":"s1","status":"ok","code":"AUTOMATION_ACTION_PERFORMED","verified":true,"timedOut":false}`
 * - `{"op":"resume","steps":["s1","s2"]}` / `{"op":"cancel"}` / `{"op":"status"}`（空参数按 status 处理）
 *
 * 可省略的字段：`verify`（按动作取 [AutomationTaskActions.defaultVerify]）、`timeoutMs`（默认 5000）、
 * `maxAttempts`（默认 1）；`steps[].target` 只有 `wait` 步骤可以不写。
 *
 * 回执也是 JSON：`ok` / `code` / `message` / `retryable` / `task`（台账快照）。
 * **`ok` 只表示这条命令被受理、台账已推进**，不表示那一步动作成功——动作成不成看
 * `task.steps[].status` 与 `task.lastCode`（成功但没通过校验是 `unknown`，不是 `ok`）。
 * 回执里**不带任何界面文字**：快照只有步骤标识、状态、尝试次数与受控码，它经设备桥回到模型侧，
 * 不能反过来推出屏幕上有什么。
 */
internal object AutomationTaskWire {
    const val OP_PLAN = "plan"
    const val OP_GATE = "gate"
    const val OP_RECORD = "record"
    const val OP_RESUME = "resume"
    const val OP_CANCEL = "cancel"
    const val OP_STATUS = "status"

    /** 所有 op：错误消息里逐个列出来，免得调用方靠猜。 */
    val OPS = listOf(OP_PLAN, OP_GATE, OP_RECORD, OP_RESUME, OP_CANCEL, OP_STATUS)

    const val DEFAULT_TIMEOUT_MS = 5_000L
    const val DEFAULT_MAX_ATTEMPTS = 1

    /** 计划里可被记成「一步做完」的三种结果（`skipped` 只由取消产生，调用方不许自己报）。 */
    val RECORD_STATUSES = setOf(
        AutomationTaskStepStatus.OK,
        AutomationTaskStepStatus.FAILED,
        AutomationTaskStepStatus.UNKNOWN,
    )

    fun parse(param: String): AutomationTaskRequest {
        val trimmed = param.trim()
        if (trimmed.isEmpty()) return AutomationTaskRequest.Status
        val json = try {
            JSONObject(trimmed)
        } catch (_: Throwable) {
            return reject(AutomationTaskCodes.PARAM_INVALID, "参数不是合法的 JSON 对象")
        }
        val op = stringOrNull(json, "op")
            ?: return reject(AutomationTaskCodes.PARAM_INVALID, "缺少 op，允许：${OPS.joinToString("、")}")
        return when (op) {
            OP_PLAN -> plan(json)
            OP_GATE -> stepId(json, "gate")?.let { AutomationTaskRequest.Gate(it) } ?: missingStep("gate")
            OP_RECORD -> record(json)
            OP_RESUME -> resume(json)
            OP_CANCEL -> AutomationTaskRequest.Cancel
            OP_STATUS -> AutomationTaskRequest.Status
            else -> reject(AutomationTaskCodes.PARAM_INVALID, "op 不受支持：$op（允许：${OPS.joinToString("、")}）")
        }
    }

    /** 放行：`step` 与 `attempt` 是调用方要执行的那一步。 */
    fun accepted(gate: AutomationTaskGate.Proceed, snapshot: AutomationTaskSnapshot): String =
        JSONObject()
            .put("ok", true)
            .put("code", JSONObject.NULL)
            .put("message", "可以执行步骤 ${gate.stepId}（第 ${gate.attempt + 1} 次尝试）")
            .put("retryable", false)
            .put("step", gate.stepId)
            .put("attempt", gate.attempt)
            .put("task", snapshot(snapshot))
            .toString()

    /** 拒绝：带上台账现状——调用方要能一眼看出「现在到哪一步了」。 */
    fun refused(
        code: String,
        message: String,
        retryable: Boolean,
        snapshot: AutomationTaskSnapshot?,
    ): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .put("retryable", retryable)
        .apply { if (snapshot != null) put("task", snapshot(snapshot)) }
        .toString()

    /** 记账完成（`record` / `cancel` / `resume` / `status` 的常规回执）。 */
    fun done(snapshot: AutomationTaskSnapshot): String = JSONObject()
        .put("ok", true)
        .put("code", snapshot.lastCode ?: JSONObject.NULL)
        .put("message", snapshot.lastMessage ?: "台账已更新（任务：${snapshot.status}）")
        .put("retryable", false)
        .put("task", snapshot(snapshot))
        .toString()

    /** 参数形状问题：没有台账可带。 */
    fun invalid(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .put("retryable", false)
        .toString()

    /**
     * 入参形状不对时的解析结果。
     *
     * 注意与上面的回执构造器 `invalid` 区分：那个产出的是给调用方读的 JSON 文本，
     * 这个产出的是解析结论本身；两者同名会让 `parse` 的返回类型悄悄变成 `String`。
     */
    private fun reject(code: String, message: String): AutomationTaskRequest =
        AutomationTaskRequest.Invalid(code, message)

    private fun plan(json: JSONObject): AutomationTaskRequest {
        val taskId = stringOrNull(json, "taskId")
            ?: return reject(AutomationTaskCodes.PARAM_INVALID, "缺少 taskId")
        val packageName = stringOrNull(json, "packageName")
            ?: return reject(AutomationTaskCodes.PARAM_INVALID, "缺少 packageName")
        val rawSteps = json.opt("steps")
        if (rawSteps !is JSONArray || rawSteps.length() == 0) {
            return reject(AutomationTaskCodes.PARAM_INVALID, "steps 必须是非空数组")
        }
        val steps = ArrayList<AutomationTaskStepSpec>(rawSteps.length())
        for (index in 0 until rawSteps.length()) {
            val raw = rawSteps.opt(index)
            if (raw !is JSONObject) {
                return reject(AutomationTaskCodes.PARAM_INVALID, "第 ${index + 1} 个步骤不是对象")
            }
            val id = stringOrNull(raw, "id")
                ?: return reject(AutomationTaskCodes.PARAM_INVALID, "第 ${index + 1} 个步骤缺少 id")
            val action = stringOrNull(raw, "action")
                ?: return reject(AutomationTaskCodes.PARAM_INVALID, "步骤 $id 缺少 action")
            val timeout = longOrNull(raw, "timeoutMs") ?: DEFAULT_TIMEOUT_MS
            val attempts = longOrNull(raw, "maxAttempts")?.toInt() ?: DEFAULT_MAX_ATTEMPTS
            steps += AutomationTaskStepSpec(
                id = id,
                action = action,
                target = stringOrNull(raw, "target").orEmpty(),
                verify = stringOrNull(raw, "verify") ?: AutomationTaskActions.defaultVerify(action),
                timeoutMs = timeout,
                maxAttempts = attempts,
            )
        }
        return AutomationTaskRequest.Plan(taskId, packageName, steps)
    }

    private fun record(json: JSONObject): AutomationTaskRequest {
        val stepId = stepId(json, "record") ?: return missingStep("record")
        val status = stringOrNull(json, "status")
            ?: return reject(AutomationTaskCodes.RECORD_INVALID, "record 缺少 status")
        if (status !in RECORD_STATUSES) {
            return reject(
                AutomationTaskCodes.RECORD_INVALID,
                "record 的 status 只能是 ${RECORD_STATUSES.joinToString("、")}",
            )
        }
        return AutomationTaskRequest.Record(
            stepId = stepId,
            outcome = AutomationTaskStepOutcome(
                ok = status == AutomationTaskStepStatus.OK,
                code = stringOrNull(json, "code"),
                verified = boolOrNull(json, "verified") ?: (status == AutomationTaskStepStatus.OK),
                timedOut = boolOrNull(json, "timedOut") ?: false,
            ),
        )
    }

    private fun resume(json: JSONObject): AutomationTaskRequest {
        val raw = json.opt("steps")
        if (raw !is JSONArray || raw.length() == 0) {
            return reject(AutomationTaskCodes.PARAM_INVALID, "resume 需要 steps：按原计划的顺序列出全部步骤标识")
        }
        val ids = ArrayList<String>(raw.length())
        for (index in 0 until raw.length()) {
            val value = raw.opt(index)
            if (value !is String || value.isBlank()) {
                return reject(AutomationTaskCodes.PARAM_INVALID, "第 ${index + 1} 个步骤标识不是字符串")
            }
            ids += value
        }
        return AutomationTaskRequest.Resume(ids)
    }

    private fun stepId(json: JSONObject, op: String): String? = stringOrNull(json, "step")?.takeIf { it.isNotBlank() }

    private fun missingStep(op: String): AutomationTaskRequest =
        reject(AutomationTaskCodes.PARAM_INVALID, "$op 缺少 step（步骤标识）")

    private fun snapshot(snapshot: AutomationTaskSnapshot): JSONObject = JSONObject()
        .put("taskId", snapshot.taskId)
        .put("status", snapshot.status)
        .put("packageName", snapshot.packageName)
        .put("nextStep", snapshot.nextStepId ?: JSONObject.NULL)
        .put("settledSteps", snapshot.settledSteps)
        .put("pendingSteps", snapshot.pendingSteps)
        .put("lastCode", snapshot.lastCode ?: JSONObject.NULL)
        .put("lastMessage", snapshot.lastMessage ?: JSONObject.NULL)
        .put(
            "steps",
            JSONArray().also { array ->
                snapshot.steps.forEach { step ->
                    array.put(
                        JSONObject()
                            .put("id", step.id)
                            .put("status", step.status)
                            .put("attempts", step.attemptsUsed)
                            .put("code", step.code ?: JSONObject.NULL),
                    )
                }
            },
        )

    private fun stringOrNull(json: JSONObject, key: String): String? =
        when (val value = json.opt(key)) {
            is String -> value
            else -> null
        }

    private fun longOrNull(json: JSONObject, key: String): Long? =
        when (val value = json.opt(key)) {
            is Number -> value.toLong()
            else -> null
        }

    private fun boolOrNull(json: JSONObject, key: String): Boolean? =
        when (val value = json.opt(key)) {
            is Boolean -> value
            else -> null
        }
}
