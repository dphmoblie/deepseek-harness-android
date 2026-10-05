package io.deepseekharness.mobile.virtualscreen

/**
 * 副屏文本注入的**决策模型**：纯函数、不碰 Android 类，因此能在 JVM 单测里逐支覆盖。
 *
 * 真机上的现实是：把一段文字送进副屏里**另一个应用**的输入框，没有任何一条通道是万能的——
 * - `input text`（Shizuku）只接受可打印 ASCII，中文/emoji 直接进不去；
 * - 无障碍 `ACTION_SET_TEXT` 能写任意 Unicode，但要求那一刻正好有一个聚焦的可编辑节点；
 * - 输入框不接受程序化写入时，只剩剪贴板粘贴；
 * - 以上都不行、但文字是纯 ASCII 时，还能逐字符合成按键事件；
 * - 输入法本身（`IME_TAP`）不能用来「替 AI 打字」（理由见 [TextMethod.IME_TAP] 的说明）。
 *
 * 所以这里不做「猜一个方法」，而是把**有序回退链**显式算出来（[plan]），由执行层逐级尝试、逐级记账
 * （[resolve]），最后要么给出真的写进去了的那个方法，要么给出**语义准确**的失败码——绝不把
 * 「这台设备/这个应用写不进去」说成「副屏功能不存在」（那是 `VIRTUAL_SCREEN_UNAVAILABLE` 的语义，会
 * 让模型直接放弃重试）。
 *
 * 分工（本文件刻意只做纯判定，不做注入）：
 * - 判定「该走哪几步、理由是什么、失败该报什么」→ 这里；
 * - 真正调用无障碍 / 剪贴板 / `input keyevent` 的地方 → `DeepSeekAccessibilityService`、
 *   `VirtualScreenTree`、`ShellVirtualScreen`。
 */
object VirtualScreenTextPolicy {
    /**
     * 文本上限，按 UTF-16 代码单元计（一个 emoji 算 2），与
     * `VirtualScreenTree.validText` 和 `VirtualScreenPolicy.inputArguments` 里的 1..512 同口径。
     *
     * 不直接引用那两个常量：前者在 [VirtualScreenTree] 的伴生对象里（可以引用），后者在别人的文件里
     * 内联写字面量。**三处必须一起改**，单测 `VirtualScreenTextPolicyTest`
     * 会断言这里的值与该口径一致。
     */
    const val MAX_TEXT_CHARS = 512

    /** 失败码，语义**绑定调用链**（哪一级走的就报哪一级），不新增含糊的新分类。 */
    object FailureCode {
        /**
         * 这台设备/这个目标应用**当前无法注入这段文字**（没有可写节点、输入框拒绝写入、剪贴板与按键兜底
         * 都用不上）。与 `VIRTUAL_SCREEN_UNAVAILABLE`（副屏功能本身不可用）是两件事，不要混用。
         */
        const val TEXT_UNSUPPORTED = "VIRTUAL_SCREEN_TEXT_UNSUPPORTED"

        /** 可重试：设备锁定或屏幕未交互。 */
        const val DEVICE_LOCKED = "ACCESSIBILITY_DEVICE_LOCKED"

        /** 可重试：两次写操作间隔小于无障碍写节流。 */
        const val RATE_LIMITED = "ACCESSIBILITY_RATE_LIMITED"

        /** 拒绝：目标窗口被判为敏感窗口（密码、验证码、支付、授权弹窗），整窗不做文本写入。 */
        const val SENSITIVE_WINDOW = "ACCESSIBILITY_SENSITIVE_WINDOW"

        /** 可重试：那一刻副屏上没有可读写的窗口。 */
        const val WINDOW_UNAVAILABLE = "ACCESSIBILITY_WINDOW_UNAVAILABLE"

        /** 参数不合法（编号、文本形态）。 */
        const val ACTION_INVALID = "ACCESSIBILITY_ACTION_INVALID"

        /** 本模块内部/上层异常，归到既有的输入错误类。 */
        const val INVALID = "VIRTUAL_SCREEN_INVALID"
    }

    /** 失败码对应的中文说明；未知码不硬猜，给一句通用解释。 */
    fun failureMessage(code: String): String = when (code) {
        FailureCode.TEXT_UNSUPPORTED ->
            "目标输入框不支持程序化写入，请在副屏上手动输入或改用点击操作"
        FailureCode.DEVICE_LOCKED -> "设备已锁定或屏幕未交互，请解锁后重试"
        FailureCode.RATE_LIMITED -> "副屏文本写入过于频繁，请稍后重试"
        FailureCode.SENSITIVE_WINDOW -> "检测到密码、验证码、支付或权限窗口，已拒绝写入文本"
        FailureCode.WINDOW_UNAVAILABLE -> "副屏上暂时没有可写入的窗口，请重新截图确认目标应用是否在前台"
        FailureCode.ACTION_INVALID -> "副屏文本参数无效：请使用 1～$MAX_TEXT_CHARS 个字符、不含控制字符"
        FailureCode.INVALID -> "副屏文本请求无效，请检查参数"
        else -> "副屏文本注入未完成，请重新观察副屏后再试"
    }

    /** 文本注入方式，按回退链顺序排列。 */
    enum class TextMethod(val label: String, val explanation: String) {
        /** 无障碍：直接对当前聚焦的可编辑节点 `ACTION_SET_TEXT`。 */
        SET_TEXT(
            "无障碍直接写入",
            "副屏上已有聚焦的输入框，直接把文字写进去（支持中文等任意字符）",
        ),

        /** 无障碍：先给候选输入框聚焦并点一下，再 `ACTION_SET_TEXT`。 */
        FOCUS_THEN_SET_TEXT(
            "无障碍聚焦后写入",
            "先在副屏上找到输入框并点一下取得焦点，再写入文字",
        ),

        /** 剪贴板：本应用写入剪贴板后对目标节点 `ACTION_PASTE`。 */
        PASTE(
            "剪贴板粘贴",
            "输入框不接受直接写入时，改用剪贴板粘贴进目标输入框",
        ),

        /** Shizuku `input keyevent`：逐字符合成按键，只对纯 ASCII 有效。 */
        KEY_EVENTS(
            "按键事件逐字符输入",
            "无障碍不可用时的纯 ASCII 兜底：逐字符合成按键事件",
        ),

        /**
         * 输入法：**本模块不会使用**。读路径由 `displayWindowRoot` 明确区分 `TYPE_INPUT_METHOD` 窗口，
         * 也不存在「替 AI 把候选词选进输入框」的公开 API；真机上的输入法兼容靠的是不依赖输入法本身的
         * `ACTION_SET_TEXT` / 剪贴板粘贴，而不是假装能点输入法。
         */
        IME_TAP(
            "输入法点选",
            "输入法不参与 AI 写文字：副屏写入不依赖输入法，也不会代点输入法候选词",
        ),

        /** 没有可行方式：直接如实失败，不做无效注入。 */
        UNSUPPORTED(
            "无法注入",
            "没有可用的文字注入方式，请在副屏上手动输入或改用点击操作",
        ),
    }

    /** 「为什么没有更早的那一步」的机器可读标识，供工具层与真机回执引用。 */
    enum class TextBlock(val reason: String) {
        ACCESSIBILITY_DISABLED("无障碍服务未启用：非 ASCII 文本需要无障碍定向注入"),
        NOT_EDITABLE("副屏当前没有可编辑的输入框"),
        ABSENT("副屏显示编号无效或低于 Android 11，没有按显示编号定位窗口的通道"),
    }

    /** 每次尝试的结果。`false` 不等于「不能试」，而是「这一级这次没成」。 */
    data class TextAttempt(val method: TextMethod, val succeeded: Boolean, val code: String? = null, val detail: String = "")

    /**
     * 最终判定：成功时 [method] 是真的生效的那一级、[chars] 是实际写入的 UTF-16 代码单元数；
     * 失败时 [code]/[message] 是给模型与用户看的内容，[attempts] 记录逐级尝试的完整链路。
     */
    sealed class TextOutcome {
        abstract val chars: Int
        abstract val attempts: List<TextAttempt>

        data class Success(
            override val chars: Int,
            val method: TextMethod,
            override val attempts: List<TextAttempt>,
        ) : TextOutcome()

        /**
         * 失败时的 [code] 走「成功用哪一级」的同一命名习惯；[message] 是给模型与用户看的中文说明。
         * 两者都不覆写基类的计算属性（否则会遮蔽基类成员），需要统一读取时用 [TextOutcome.codeOf] 一类的小工具。
         */
        data class Failure(
            override val chars: Int,
            val code: String,
            val message: String,
            override val attempts: List<TextAttempt>,
        ) : TextOutcome()

        /** 实际生效（或最后一次尝试）的方法；失败且没有尝试记录时为 null。 */
        val attemptedMethod: TextMethod?
            get() = when (this) {
                is Success -> method
                is Failure -> attempts.lastOrNull()?.method
            }
    }

    /** 文本形态分类。 */
    data class TextKind(
        val asciiOnly: Boolean,
        val hasNonAscii: Boolean,
        val hasEmojiSurrogate: Boolean,
        val length: Int,
    )

    /**
     * 调用时刻的副屏状态，全部由执行层探测后传入；本文件不自己去查。
     *
     * [hasFocusTarget] 表示**执行层已经找到了候选输入框**（[VirtualScreenTree.writeText] 里的
     * `setTextTarget`），也就是「没有聚焦节点，但有一个可聚焦可编辑节点」这一支成立。
     */
    data class TextConditions(
        val asciiOnly: Boolean,
        val accessibilityAvailable: Boolean,
        val hasFocusedEditable: Boolean,
        val hasFocusTarget: Boolean,
        val clipboardAvailable: Boolean,
    )

    /**
     * 决策结果：有序回退链 [chain]、文本分类 [kind]、非 ASCII 且无障碍不可用时的短路原因 [blocked]。
     *
     * [chain] 为空是合法的（[TextMethod.UNSUPPORTED]），表示「这条路当前一步都走不了」。
     */
    data class TextDecision(
        val chain: List<TextMethod>,
        val kind: TextKind,
        val blocked: TextBlock?,
        val reason: String,
    ) {
        /** 回退链的中文摘要，给「为什么这么做」一个可解释的说法。 */
        val plan: String get() = chain.joinToString(" → ") { it.label }.ifEmpty { TextMethod.UNSUPPORTED.label }
    }

    /** 可打印 ASCII：`input text` 与 `input keyevent` 都只对这一段有效。 */
    fun isAsciiOnly(value: String): Boolean = value.isNotEmpty() && value.all { it.code in 0x20..0x7e }

    /** 分类文本形态（纯 ASCII / 含非 ASCII / 是否含 emoji 等非 BMP 代理对 / 长度）。 */
    fun classify(text: String): TextKind {
        var hasNonAscii = false
        var hasSurrogatePair = false
        var index = 0
        while (index < text.length) {
            val current = text[index]
            if (current.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                // 代理对 = 一个非 BMP 码点。emoji 是绝大多数情况；这里按「字符对」如实记账，
                // 所以 [TextKind.hasEmojiSurrogate] 的语义是「含非 BMP 字符」，不特指 emoji。
                hasSurrogatePair = true
                hasNonAscii = true
                index += 2
                continue
            }
            if (current.code > 0x7e) hasNonAscii = true
            index += 1
        }
        return TextKind(
            asciiOnly = text.isNotEmpty() && !hasNonAscii,
            hasNonAscii = hasNonAscii,
            hasEmojiSurrogate = hasSurrogatePair,
            length = text.length,
        )
    }

    /** 逐字符合成按键只对纯 ASCII 有意义：其它字符用 `KeyCharacterMap` 拼不出来。 */
    fun keyEventsSupported(kind: TextKind): Boolean = kind.asciiOnly

    /**
     * 计算回退链。顺序与理由：
     * 1. 已有聚焦可编辑节点 → [TextMethod.SET_TEXT]；
     * 2. 没有聚焦节点但有候选输入框 → [TextMethod.FOCUS_THEN_SET_TEXT]（先聚焦再写）；
     * 3. 写入被拒 → [TextMethod.PASTE]（仅当剪贴板可用）；
     * 4. 纯 ASCII 且（无障碍不可用，或前两级都没落地）→ [TextMethod.KEY_EVENTS]；
     * 5. 都没有 → [TextMethod.UNSUPPORTED]。
     *
     * 非 ASCII + 无障碍不可用是**唯一**会直接空链的情形：`input text`/`keyevent` 都送不进中文，
     * 此时如实标 [TextBlock.ACCESSIBILITY_DISABLED]，不假装还有下一步。
     */
    fun plan(kind: TextKind, conditions: TextConditions): TextDecision {
        if (kind.length !in 1..MAX_TEXT_CHARS) {
            return TextDecision(emptyList(), kind, null, "副屏文本长度无效：只支持 1～$MAX_TEXT_CHARS 个字符")
        }
        val keys = keyEventsSupported(kind)
        if (!conditions.accessibilityAvailable) {
            if (keys) {
                return TextDecision(
                    listOf(TextMethod.KEY_EVENTS),
                    kind,
                    null,
                    "无障碍不可用，纯 ASCII 文本改用按键事件逐字符输入",
                )
            }
            return TextDecision(
                emptyList(),
                kind,
                TextBlock.ACCESSIBILITY_DISABLED,
                "${TextBlock.ACCESSIBILITY_DISABLED.reason}；纯 ASCII 之外的字符也无法用按键事件输入",
            )
        }
        val chain = ArrayList<TextMethod>(4)
        when {
            conditions.hasFocusedEditable -> chain += TextMethod.SET_TEXT
            conditions.hasFocusTarget -> chain += TextMethod.FOCUS_THEN_SET_TEXT
            else -> Unit
        }
        // 先聚焦再写入同样能覆盖「已经有聚焦节点」的情形，作为被拒后的第二级；
        // 反过来（已有焦点时不再找候选框）不行：那会把「输入框拒绝写入」直接推到剪贴板。
        if (conditions.hasFocusTarget && !chain.contains(TextMethod.FOCUS_THEN_SET_TEXT)) {
            chain += TextMethod.FOCUS_THEN_SET_TEXT
        }
        if (conditions.clipboardAvailable && chain.isNotEmpty()) chain += TextMethod.PASTE
        if (keys && chain.none { it == TextMethod.KEY_EVENTS }) chain += TextMethod.KEY_EVENTS
        if (chain.isEmpty()) {
            // 无障碍在，但副屏上没有任何可编辑节点，且文本不是纯 ASCII：没有一条路可走。
            return TextDecision(
                emptyList(),
                kind,
                TextBlock.NOT_EDITABLE,
                "${TextBlock.NOT_EDITABLE.reason}；非 ASCII 文本也无法用按键事件输入",
            )
        }
        val reason = buildString {
            append("无障碍定向注入：")
            append(chain.joinToString(" → ") { it.label })
            if (!conditions.clipboardAvailable && kind.asciiOnly) append("（剪贴板不可用，已跳过粘贴）")
        }
        return TextDecision(chain, kind, null, reason)
    }

    /**
     * 用「实际尝试记录」收口：第一个成功的级就是结果；全部失败时按最后一级的失败码如实报错
     * （最后一级是 [TextMethod.UNSUPPORTED] 时归到 [FailureCode.TEXT_UNSUPPORTED]）。
     */
    fun resolve(decision: TextDecision, attempts: List<TextAttempt>): TextOutcome {
        val succeeded = attempts.firstOrNull { it.succeeded }
        if (succeeded != null) {
            return TextOutcome.Success(decision.kind.length, succeeded.method, attempts)
        }
        if (decision.chain.isEmpty()) {
            val code = if (decision.blocked == null) FailureCode.ACTION_INVALID else FailureCode.TEXT_UNSUPPORTED
            return TextOutcome.Failure(0, code, decision.reason, attempts)
        }
        val last = attempts.lastOrNull() ?: decision.chain.last().let { TextAttempt(it, false, FailureCode.TEXT_UNSUPPORTED) }
        val code = last.code ?: FailureCode.TEXT_UNSUPPORTED
        return TextOutcome.Failure(0, code, messageOf(last), attempts)
    }

    /** 单次尝试的中文说明：失败用码对应的用户可读解释，细节（[TextAttempt.detail]）跟着一起给。 */
    private fun messageOf(attempt: TextAttempt): String {
        val base = failureMessage(attempt.code ?: FailureCode.TEXT_UNSUPPORTED)
        return if (attempt.detail.isEmpty()) base else "$base（${attempt.detail}）"
    }

    /** 逐级尝试的中文摘要，用于工具结果与真机回执。 */
    fun describe(attempts: List<TextAttempt>): String = attempts.joinToString("；") { attempt ->
        val state = if (attempt.succeeded) "成功" else "失败"
        buildString {
            append(attempt.method.label)
            append(state)
            if (!attempt.succeeded) {
                attempt.code?.let { append("（$it）") }
                if (attempt.detail.isNotEmpty()) append("：${attempt.detail}")
            }
        }
    }

    /**
     * `submit` 的中文尾注：把「请求了提交但没按成」与「已经提交」「根本没请求」区分开，
     * 不让调用方把「写进去了但没发出去」读成「已经发送」。
     */
    fun submitNote(succeeded: Boolean, submitted: Boolean, submitRequested: Boolean): String = when {
        submitted -> "；已按一次回车提交"
        submitRequested && !succeeded -> "；文字没写进去，没有按回车"
        submitRequested -> "；文字写进去了，但回车提交未生效"
        else -> ""
    }

    /** 失败码；成功时为 null。 */
    fun codeOf(outcome: TextOutcome): String? = (outcome as? TextOutcome.Failure)?.code

    /** 统一的中文说明：成功说清楚用的是哪一级，失败直接给用户可读原因。 */
    fun messageOf(outcome: TextOutcome): String = when (outcome) {
        is TextOutcome.Success -> "已通过${outcome.method.label}写入 ${outcome.chars} 个字符"
        is TextOutcome.Failure -> outcome.message
    }

    /** 参数与前置条件不满足时抛：这一句在工具层直接透传给模型。 */
    fun requireInjectable(text: String) {
        require(text.length in 1..MAX_TEXT_CHARS) { "副屏文本长度无效" }
        require(VirtualScreenTree.validText(text)) { "副屏文本含控制字符或零宽字符，无法注入" }
    }

    /**
     * 结构化结果的 JSON 形状（与工具面 `method`/`chars`/`code` 字段一一对应）。
     *
     * 放在这里而不是执行层，是为了让外层「回退链最终结果长什么样」也有单测可断言。
     */
    fun encode(outcome: TextOutcome, submit: Boolean): org.json.JSONObject {
        val json = org.json.JSONObject()
            .put("chars", outcome.chars)
            .put("submit", submit)
            .put("steps", describe(outcome.attempts))
        when (outcome) {
            is TextOutcome.Success -> json.put("method", outcome.method.name).put("label", outcome.method.label)
            is TextOutcome.Failure -> json.put("code", outcome.code).put("reason", outcome.message)
        }
        return json
    }
}
