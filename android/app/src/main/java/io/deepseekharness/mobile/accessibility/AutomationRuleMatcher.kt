package io.deepseekharness.mobile.accessibility

/**
 * 自动化规则匹配器的**纯逻辑**层：零 Android 依赖，可在 JVM 单测里完整覆盖。
 *
 * 为什么要把「匹配」和「执行无障碍动作」拆开：
 * - 匹配的判定条件很多（包名/Activity 白黑名单、多选择器 AND、13 个字段的语义、次数与冷却抑制、
 *   可点击降级），而它**全部**是对「一棵节点树 + 当前时间」的纯函数。挂在服务里就只能靠真机点，
 *   一条边界（比如「冷却刚好 3000ms 算不算过」）要复现一次得先装应用、开权限、走到目标界面。
 * - 执行侧（`performAction`）本来就不可能在 JVM 单测里跑，把两者混在一个类里会让「匹配对不对」
 *   永远无法被验证。这里只产出**决策**，由服务侧照着执行。
 *
 * 输入是 [AutomationNode]（而不是 Android 的 `AccessibilityNodeInfo`），服务侧负责把真实的
 * 节点树**复制**成这个结构。复制而不是直接包一层的原因：`AccessibilityNodeInfo` 是跨进程句柄，
 * 在窗口更新后随时失效，而匹配过程中要反复读 text/desc/bounds（每次读都是一次 IPC）；
 * 复制一遍把「一个窗口的判定」变成纯内存操作，也顺手隔离了节点回收（recycle）的生命周期问题。
 *
 * 抑制状态（次数 / 上次执行时间）由调用方通过 [AutomationMatchState] 注入并回写，匹配器自身
 * 不持有任何可变状态：同一个窗口事件里可以先算决策、执行完再提交计数，中间失败不会污染状态。
 */
internal data class AutomationNode(
    val className: String? = null,
    val text: String? = null,
    val viewId: String? = null,
    val desc: String? = null,
    val clickable: Boolean = false,
    /** 无障碍框架里这个字段叫 `isEnabled`；`false` 的节点对「点击」类动作没有意义。 */
    val enabled: Boolean = true,
    val editable: Boolean = false,
    val bounds: AutomationBounds = AutomationBounds.EMPTY,
    val children: List<AutomationNode> = emptyList(),
) {
    val width: Int get() = bounds.width

    val height: Int get() = bounds.height

    /**
     * 深度优先（前序）展平，附带从根到该节点的下标路径。
     *
     * 带路径是因为执行侧要「按同一个窗口里的位置」再找到那个节点去点击；只给 bounds 的话，
     * 两个 bounds 相同的节点（叠放的容器）无法区分。
     */
    fun flatten(): List<AutomationNodeRef> {
        // 完全空白的根（没有任何可取用属性、也没有子节点）等于「没有节点树」：
        // 真实的无障碍根节点永远带 className，出现这种节点只可能是调用方用 `AutomationNode()` 占位，
        // 或者窗口刚建立还没填充内容。返回空表让上层统一走 EMPTY_TREE，而不是报告「匹配失败」——
        // 「界面还没就绪」和「界面就绪了但没有目标控件」对用户是两件事。
        if (isBlank()) return emptyList()
        val result = ArrayList<AutomationNodeRef>()
        // 用显式栈而不是递归：无障碍树理论上受系统深度限制，但用户上报过的异常树里出现过自环，
        // 显式栈 + 访问集合能把这种情况降级成「少匹配一个子树」而不是 StackOverflowError 崩服务。
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AutomationNode, Boolean>())
        val stack = ArrayDeque<Pair<AutomationNode, List<Int>>>()
        stack.addLast(this to emptyList())
        while (stack.isNotEmpty()) {
            val (node, path) = stack.removeLast()
            if (!visited.add(node)) continue
            result += AutomationNodeRef(node, path)
            // 反向压栈，出栈顺序才是原来的前序（第一个子节点先访问），
            // 保证「第一个命中的节点」在用户看来是「界面上更靠上的那个」。
            node.children.asReversed().forEachIndexed { reversedIndex, child ->
                val index = node.children.size - 1 - reversedIndex
                stack.addLast(child to (path + index))
            }
        }
        return result
    }

    /** 是否是一个「什么都没带」的占位节点（见 [flatten] 的口径）。 */
    private fun isBlank(): Boolean =
        className.isNullOrEmpty() && text.isNullOrEmpty() && viewId.isNullOrEmpty() && desc.isNullOrEmpty() &&
            children.isEmpty()

    companion object {
        /** 只用于构造测试/快照的简写：文本 + 可点击。 */
        fun leaf(text: String?, clickable: Boolean = true): AutomationNode =
            AutomationNode(text = text, clickable = clickable)

        /** 连续 [count] 个可见节点的纵向堆叠；用于构造「需要滚动」的场景。 */
        fun stack(count: Int, height: Int = 200): AutomationNode = AutomationNode(
            className = "android.widget.FrameLayout",
            children = (0 until count).map { index ->
                AutomationNode(
                    className = "android.widget.TextView",
                    text = "item-$index",
                    clickable = true,
                    bounds = AutomationBounds(0, index * height, 1080, (index + 1) * height),
                )
            },
        )
    }
}

/** 节点在树里的位置：节点本身 + 从根到它的子节点下标路径。 */
internal data class AutomationNodeRef(val node: AutomationNode, val path: List<Int>) {
    /** 人类可读的路径，例如 `0/2/1`（根的第一个子节点的第三个子节点的第二个子节点）。 */
    val pathText: String get() = if (path.isEmpty()) "root" else path.joinToString("/")

    val bounds: AutomationBounds get() = node.bounds
}

/** 屏幕矩形。用左上/右下表示，与 `AccessibilityNodeInfo.getBoundsInScreen` 一致。 */
internal data class AutomationBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** 宽度按 `right - left` 算，负数视为 0：异常树里出现过 right < left，不能让尺寸判定负向通过。 */
    val width: Int get() = (right - left).coerceAtLeast(0)

    val height: Int get() = (bottom - top).coerceAtLeast(0)

    /** 中心点：不可点击节点降级成 `clickCenter` 时要点的坐标。 */
    val centerX: Int get() = left + width / 2

    val centerY: Int get() = top + height / 2

    override fun toString(): String = "[$left,$top][$right,$bottom]"

    companion object {
        val EMPTY = AutomationBounds(0, 0, 0, 0)
    }
}

/** 当前前台界面：包名必填，Activity 名可为空（部分系统界面拿不到）。 */
internal data class AutomationScreenInfo(
    val packageName: String,
    val activityName: String? = null,
)

/**
 * 抑制状态：每条规则的「本周期已执行次数 / 上次执行时间」，以及用来识别周期的「上一次窗口」。
 *
 * 可变且由调用方持有：匹配器是纯函数，匹配完由服务侧按 [AutomationDecision] 决定是否提交
 * [AutomationMatchState.commit]。这样「匹配到了但执行失败」不会白吃掉一次次数配额。
 */
internal class AutomationMatchState {
    private val counts = mutableMapOf<String, Int>()
    private val lastActionAtMs = mutableMapOf<String, Long>()

    /** 上一次判定时的窗口标识（Activity 名或包名），用于 [AutomationRule.RESET_ACTIVITY] 的周期判定。 */
    var lastWindowKey: String? = null

    /** 已处理的窗口更新次数，用于 [AutomationRule.RESET_SCREEN] 的周期判定。 */
    var windowUpdateSeq: Long = 0

    fun count(ruleId: String): Int = counts[ruleId] ?: 0

    /**
     * 只把某一条规则的次数清零，时间戳保持不动。
     *
     * 用于「冷却到期、执行窗口重开」：此时窗口确实换了，但**不能**顺手把时间戳也删掉——
     * 时间戳是判断「还在不在冷却里」的唯一依据，删了就等于把冷却一并取消。
     */
    fun resetCount(ruleId: String) {
        counts[ruleId] = 0
    }

    fun lastActionAt(ruleId: String): Long? = lastActionAtMs[ruleId]

    /**
     * 记录「当前窗口标识」，在窗口标识变化时清掉**全部**计数。
     *
     * 清全部而不是清某一条规则：窗口标识（包名/Activity）变化对**所有**规则都是「用户换了界面」，
     * 按规则分别决定是否清空会让两条 `resetOn` 不同的规则对同一个事件有不同解释，用户无法预测。
     * `resetOn` 的差别体现在**同一个窗口标识内**：`activity` 口径只在窗口标识变化时重开配额，
     * `screen` 口径还能被一次显式的窗口更新（[advanceWindow]）重开。
     */
    fun beginWindow(windowKey: String) {
        if (lastWindowKey == windowKey) return
        lastWindowKey = windowKey
        // 窗口变化 = 用户进了新界面：次数与时间戳一起清。
        // 只清次数会让新界面上的第一次执行被旧界面的时间戳按冷却挡住（用户看到「换了界面还是不动」）。
        counts.clear()
        lastActionAtMs.clear()
    }

    /**
     * 在**同一个窗口标识内**推进一次屏幕周期，并清掉计数。
     *
     * 只该在调用方确认「用户进入了新的界面」（同一个 Activity 内的页面切换、抽屉展开等）时调用；
     * 每次 `onAccessibilityEvent` 无条件调用会让 `resetOn=screen` 的规则退化成无限次执行——
     * 那正是 `maxActions` 想防住的事。
     */
    fun advanceWindow() {
        windowUpdateSeq += 1
        counts.clear()
        // 同上：新屏幕要能立刻执行，冷却不能跨屏幕继承。
        lastActionAtMs.clear()
    }

    /**
     * 记录一次成功的执行。
     *
     * 计数在本窗口周期内**累加**，不会因为一次冷却结束而归零——`maxActions` 的语义是
     * 「这个界面上最多执行几次」。若把「距上次执行已超过冷却」也算作新周期，`maxActions` 会退化成
     * 「每冷却一次就能再来一遍」，设了上限也拦不住反复触发。
     */
    fun commit(rule: AutomationRule, nowMs: Long) {
        val previous = lastActionAtMs[rule.id]
        // 时钟回退（系统对时、注入时钟步进）时 `nowMs - previous` 为负，负数永远小于冷却时间，
        // 规则会被永久卡死。这里按「上一个周期已经结束」处理：从 1 开始重新计数。
        val elapsed = previous?.let { nowMs - it }
        val count = if (elapsed != null && elapsed < 0) 0 else counts[rule.id] ?: 0
        counts[rule.id] = count + 1
        lastActionAtMs[rule.id] = nowMs
    }

    /** 只在「确认为新的屏幕周期」时推进；调用方不该在每次 `onAccessibilityEvent` 里无条件调用。 */
    fun reset() {
        counts.clear()
        lastActionAtMs.clear()
        lastWindowKey = null
        windowUpdateSeq = 0
    }
}

/** 一次判定产出的决策：要执行什么、命中在哪、为什么。 */
internal data class AutomationDecision(
    val ruleId: String,
    /** 命中节点的坐标；[degradedToCenter] 为 true 时实际点的是它的中心点。 */
    val bounds: AutomationBounds,
    val nodePath: List<Int>,
    val nodePathText: String,
    val action: AutomationAction,
    /** 稳定诊断码（`[A-Z][A-Z0-9_]*`，可直接进审计详情的受控字段）。 */
    val code: String,
    /** 中文说明；含具体字段名与命中节点坐标，便于用户对照界面自己排查。 */
    val reason: String,
    /** 命中该节点的选择器下标；AND 语义下正常就是全部下标（从 0 开始）。 */
    val selectorIndexes: List<Int>,
    /** true 表示 `click` 因节点不可点击而降级为 `clickCenter`（显式标记，不藏在 reason 文本里）。 */
    val degradedToCenter: Boolean = false,
) {
    val kind: String get() = if (degradedToCenter) KIND_CENTER_FALLBACK else action.type

    /** 要使用的坐标：降级时明确用节点中心，其余情况用节点 bounds。 */
    val resolvedBounds: AutomationBounds
        get() = if (degradedToCenter) {
            AutomationBounds(bounds.centerX, bounds.centerY, bounds.centerX, bounds.centerY)
        } else {
            bounds
        }

    companion object {
        const val KIND_CENTER_FALLBACK = "clickCenter"
    }
}

/** 一次匹配里没产出决策的规则及原因，用于「测试规则」界面直接展示。 */
internal data class AutomationSkip(
    val ruleId: String,
    val code: String,
    val reason: String,
)

/** 一次匹配的完整结果。 */
internal data class AutomationMatchResult(
    /** 按规则原顺序，每个包名匹配且被抑制但未执行的规则各一条。 */
    val decisions: List<AutomationDecision>,
    /** 按规则原顺序，每条跳过规则的原因。 */
    val skipped: List<AutomationSkip>,
) {
    val hasAction: Boolean get() = decisions.isNotEmpty()

    companion object {
        val EMPTY = AutomationMatchResult(emptyList(), emptyList())
    }
}

/**
 * 纯匹配器。输入一棵节点树 + 当前界面 + 规则列表 + 抑制状态，输出决策与跳过原因。
 *
 * 顺序保证：规则按传入顺序评估（用户列表里的先后顺序就是他理解的优先级）；每条规则内部取
 * **前序第一个**满足全部选择器的节点（我们的匹配约定：命中就取屏幕顺序最靠前的那一个，
 * 不做「最后遍历到」的兜底）：用户写下「点这个按钮」，期望的是屏幕上最靠上/最外层的那一个。
 */
internal object AutomationRuleMatcher {
    /**
     * 评估一次。
     *
     * @param root 当前窗口的节点树根；传 `null` 或空树时所有规则都记为「节点为空」。
     * @param screen 当前前台包名与 Activity。
     * @param rules 规则列表；调用方不必预先过滤 `enabled`（本方法自己会跳过并给出原因）。
     * @param state 抑制状态；本方法**只读不提交**，由调用方按决策调用
     *        [AutomationMatchState.commit]，避免「执行失败却已算一次」。
     * @param nowMs 当前时间（毫秒）；注入是为了让冷却判定可被单测直接推进，而不是靠 sleep。
     */
    fun match(
        root: AutomationNode?,
        screen: AutomationScreenInfo,
        rules: List<AutomationRule>,
        state: AutomationMatchState = AutomationMatchState(),
        nowMs: Long = 0L,
    ): AutomationMatchResult {
        if (rules.isEmpty()) return AutomationMatchResult.EMPTY
        val nodes = root?.flatten().orEmpty()
        val decisions = ArrayList<AutomationDecision>()
        val skipped = ArrayList<AutomationSkip>()
        // 同一个窗口里，同一棵节点树对每条规则只展平一次（上面算了一次），
        // 但每条规则的抑制状态与选择器都不同，因此这里没有「同一节点被两条规则命中」的互斥——
        // 用户显式写了两条规则就说明他要两个动作，静默取其一反而更难排查。
        rules.forEach { rule ->
            val decision = evaluate(rule, nodes, screen, state, nowMs)
            when (decision) {
                is Evaluation.Decided -> decisions += decision.decision
                is Evaluation.Skipped -> skipped += AutomationSkip(rule.id, decision.code, decision.reason)
            }
        }
        return AutomationMatchResult(decisions, skipped)
    }

    /** 单条规则的评估结果。 */
    private sealed interface Evaluation {
        data class Decided(val decision: AutomationDecision) : Evaluation

        data class Skipped(val code: String, val reason: String) : Evaluation
    }

    private fun evaluate(
        rule: AutomationRule,
        nodes: List<AutomationNodeRef>,
        screen: AutomationScreenInfo,
        state: AutomationMatchState,
        nowMs: Long,
    ): Evaluation {
        if (rule.packageName != screen.packageName) {
            return Evaluation.Skipped(
                AutomationSkipCodes.PACKAGE_MISMATCH,
                "当前前台是 ${screen.packageName}，规则只对 ${rule.packageName} 生效",
            )
        }
        if (!rule.enabled) {
            return Evaluation.Skipped(AutomationSkipCodes.RULE_DISABLED, "规则已停用")
        }
        val activity = screen.activityName
        if (activity == null && (rule.allowActivities.isNotEmpty() || rule.denyActivities.isNotEmpty())) {
            // 拿不到 Activity 名时**不猜**：白名单类规则绝不能因为「不知道现在在哪」就放行，
            // 否则用户的「只在 A 页执行」会在某些系统界面上变成「到处都执行」。
            return Evaluation.Skipped(
                AutomationSkipCodes.ACTIVITY_UNKNOWN,
                "当前界面拿不到 Activity 名称，无法判定 Activity 白名单/黑名单",
            )
        }
        if (activity != null && rule.allowActivities.isNotEmpty() &&
            rule.allowActivities.none { compileRulePattern(it, "Activity 白名单").containsMatchIn(activity) }
        ) {
            return Evaluation.Skipped(
                AutomationSkipCodes.ACTIVITY_NOT_ALLOWED,
                "当前 Activity $activity 不在白名单内（${rule.allowActivities.joinToString("、")}）",
            )
        }
        if (activity != null) {
            val blocked = rule.denyActivities.firstOrNull {
                compileRulePattern(it, "Activity 黑名单").containsMatchIn(activity)
            }
            if (blocked != null) {
                return Evaluation.Skipped(
                    AutomationSkipCodes.ACTIVITY_EXCLUDED,
                    "当前 Activity $activity 命中黑名单（$blocked）",
                )
            }
        }
        if (nodes.isEmpty()) {
            return Evaluation.Skipped(AutomationSkipCodes.EMPTY_TREE, "当前窗口没有可匹配的节点")
        }
        if (rule.matchDelayMs > 0) {
            // 延迟是「进入界面后等一会儿再点」的保护，真实实现由服务侧在延迟后重新匹配；
            // 匹配器只负责如实报出这个约束，而不是在这里 sleep（sleep 会阻塞事件线程）。
            return Evaluation.Skipped(
                AutomationSkipCodes.MATCH_DELAY_PENDING,
                "规则设置了 ${rule.matchDelayMs} 毫秒匹配延迟，需由服务侧在延迟后重新匹配",
            )
        }
        val matched = firstMatch(rule, nodes)
            ?: return Evaluation.Skipped(
                AutomationSkipCodes.NODE_NOT_FOUND,
                "没有节点同时满足全部 ${rule.selectors.size} 个选择器",
            )

        val suppression = checkSuppression(rule, state, nowMs)
        if (suppression != null) return suppression

        val node = matched.node.node
        val action = rule.action
        // `click` 的目标节点必须真的可点击：无障碍框架在不可点击节点上 `ACTION_CLICK` 会静默失败
        // （返回 false 或干脆不产生效果），用户看到的是「规则明明匹配了却不动」。降级成点中心
        // 至少能落到触摸目标上，且必须显式记进 reason，否则用户无法从现象反推是哪一步被降级了。
        if (action.type == ACTION_CLICK && !node.clickable) {
            return Evaluation.Decided(
                AutomationDecision(
                    ruleId = rule.id,
                    bounds = node.bounds,
                    nodePath = matched.path,
                    nodePathText = matched.pathText,
                    action = action,
                    code = AutomationSkipCodes.CLICK_CENTER_FALLBACK,
                    reason = "命中节点不可点击（clickable=false），已降级为点击中心点 " +
                        "(${node.bounds.centerX},${node.bounds.centerY})",
                    selectorIndexes = matched.selectorIndexes,
                    degradedToCenter = true,
                ),
            )
        }
        return Evaluation.Decided(
            AutomationDecision(
                ruleId = rule.id,
                bounds = node.bounds,
                nodePath = matched.path,
                nodePathText = matched.pathText,
                action = action,
                code = AutomationSkipCodes.MATCHED,
                reason = describeMatch(rule, matched.selectorIndexes, node),
                selectorIndexes = matched.selectorIndexes,
            ),
        )
    }

    /**
     * 抑制判定：只有**次数上限**一条闸门，冷却决定配额什么时候重开。
     *
     * 语义定调（这是唯一自洽的口径，两个方向的用例都能对上）：
     * - `actionCoolDownMs` 是**装配额的执行窗口**长度：窗口内累计执行
     *   [AutomationRule.maxActions] 次后由上限拦住；窗口整体过去（`elapsed >= actionCoolDownMs`）
     *   之后重开配额，下一次执行再重新计数。
     * - 冷却**本身不拦人**：配额没用完就该放行。若把冷却也当闸门，`maxActions=3` 配
     *   10000 毫秒冷却的规则会在整个窗口里只执行得了一次（第一次执行后每一次都被冷却挡住，
     *   计数永远累不到 3），上限反而永远轮不到生效——用户设的「最多 3 次」变成了「最多 1 次」。
     * - 拦住下一次执行的是**上限**：`maxActions=1` 时第二次起一律报上限，但 reason 里同时
     *   给出「冷却还有多久重开」，用户才知道要等多久而不是以为规则坏了。
     * - 时钟回退（系统对时）时按「窗口已过去」处理，否则时间戳落在未来会让规则永久失效。
     * - 冷却为 0 表示没有时间窗口，配额用完后不会自动重开（时间推得再远也一样）。
     */
    private fun checkSuppression(
        rule: AutomationRule,
        state: AutomationMatchState,
        nowMs: Long,
    ): Evaluation? {
        val previous = state.lastActionAt(rule.id)
        val elapsed = previous?.let { nowMs - it }
        val rolledBack = elapsed != null && elapsed < 0
        // 窗口整体过去（或时钟回退）：重开配额。只清次数，时间戳留着继续算「冷却还剩多久」。
        if (previous != null && (rolledBack || (rule.actionCoolDownMs > 0 && elapsed!! >= rule.actionCoolDownMs))) {
            state.resetCount(rule.id)
        }
        val count = state.count(rule.id)
        if (count >= rule.maxActions) {
            // 冷却剩余时间顺带报出来：到上限时用户真正关心的是「还要等多久才能再来一次」。
            val remaining = if (!rolledBack && elapsed != null && rule.actionCoolDownMs > 0) {
                val left = rule.actionCoolDownMs - elapsed
                if (left > 0) "冷却还剩 $left 毫秒后重开" else "冷却已到点，下次判定即重开"
            } else if (rule.actionCoolDownMs > 0) {
                "冷却 ${rule.actionCoolDownMs} 毫秒后重开"
            } else {
                "不会自动重开"
            }
            return Evaluation.Skipped(
                AutomationSkipCodes.ACTION_MAX_REACHED,
                "本界面已执行 $count 次（上限 ${rule.maxActions} 次），$remaining",
            )
        }
        return null
    }

    private data class Matched(
        val node: AutomationNodeRef,
        /** 命中的选择器下标；AND 语义下正常情况就是全部下标，列出来是为了让 reason 可复核。 */
        val selectorIndexes: List<Int>,
    ) {
        val path: List<Int> get() = node.path

        val pathText: String get() = node.pathText
    }

    /**
     * 前序第一个满足**全部**选择器的节点。
     *
     * 多选择器之间是 AND：用户的写法是「文本是 X **且** id 是 Y」，每个选择器描述**同一个**节点
     * （而不是「X 下面的 Y」这种层级关系）——需要层级关系的场景由用户在界面上用唯一的 id/text 定位。
     * 因此这里必须先跑完所有选择器才能判定命中，不能「任一选择器命中就算命中」（那是 OR，
     * 会让 `text=登录` + `id=:id/cancel` 这种明显矛盾的规则匹到两个不同按钮）。
     */
    private fun firstMatch(rule: AutomationRule, nodes: List<AutomationNodeRef>): Matched? {
        nodes.forEach { ref ->
            val satisfied = ArrayList<Int>(rule.selectors.size)
            rule.selectors.forEachIndexed { index, selector ->
                if (matches(selector, ref.node)) satisfied += index
            }
            if (satisfied.size == rule.selectors.size) return Matched(ref, satisfied)
        }
        return null
    }

    /** 单个选择器的判定；所有非空条件都要成立（AND）。 */
    private fun matches(selector: Selector, node: AutomationNode): Boolean {
        selector.text?.let { if (node.text != it) return false }
        selector.textContains?.let { if (node.text?.contains(it) != true) return false }
        selector.textStartsWith?.let { if (node.text?.startsWith(it) != true) return false }
        selector.textEndsWith?.let { if (node.text?.endsWith(it) != true) return false }
        selector.id?.let { if (!matchesViewId(it, node.viewId)) return false }
        selector.desc?.let { if (node.desc != it) return false }
        selector.descContains?.let { if (node.desc?.contains(it) != true) return false }
        selector.className?.let { if (node.className != it) return false }
        selector.clickable?.let { if (node.clickable != it) return false }
        selector.enabled?.let { if (node.enabled != it) return false }
        selector.editable?.let { if (node.editable != it) return false }
        selector.minWidth?.let { if (node.width < it) return false }
        selector.minHeight?.let { if (node.height < it) return false }
        return true
    }

    /**
     * `id` 的两种写法：`pkg:id/name` 要求与节点完全相同；`:id/name` 只看冒号及之后的部分。
     *
     * 用「结尾匹配」而不是 `contains`：`:id/title` 不能匹配到 `:id/subtitle`——包含匹配会让
     * 用户以为写的是精确后缀，实际命中一堆无关节点（这正是解析期拒绝 `id: "login"` 这种写法的同一个理由）。
     */
    private fun matchesViewId(selectorId: String, nodeViewId: String?): Boolean {
        val actual = nodeViewId?.trim().orEmpty()
        if (actual.isEmpty()) return false
        if (!selectorId.startsWith(":")) return actual == selectorId
        return actual == selectorId || actual.endsWith(selectorId)
    }

    private fun describeMatch(rule: AutomationRule, selectorIndexes: List<Int>, node: AutomationNode): String {
        val fields = selectorIndexes.joinToString("；") { describeSelector(rule.selectors[it]) }
        return "第 ${selectorIndexes.joinToString("、") { (it + 1).toString() }} 个选择器全部命中（$fields），" +
            "节点 ${node.className ?: "未知类型"} ${node.bounds}"
    }

    /**
     * 人类可读的选择器摘要，例如 `text=登录、clickable=true`。
     *
     * 字段名与 JSON 字段一致：用户看到 reason 时可以直接对着他写的那份规则改。
     */
    private fun describeSelector(selector: Selector): String {
        val parts = ArrayList<String>()
        selector.text?.let { parts += "text=$it" }
        selector.textContains?.let { parts += "textContains=$it" }
        selector.textStartsWith?.let { parts += "textStartsWith=$it" }
        selector.textEndsWith?.let { parts += "textEndsWith=$it" }
        selector.id?.let { parts += "id=$it" }
        selector.desc?.let { parts += "desc=$it" }
        selector.descContains?.let { parts += "descContains=$it" }
        selector.className?.let { parts += "className=$it" }
        selector.clickable?.let { parts += "clickable=$it" }
        selector.enabled?.let { parts += "enabled=$it" }
        selector.editable?.let { parts += "editable=$it" }
        selector.minWidth?.let { parts += "minWidth=$it" }
        selector.minHeight?.let { parts += "minHeight=$it" }
        return parts.joinToString("、")
    }

    /** 动作常量与 [AutomationAction.type] 的取值一一对应。 */
    const val ACTION_CLICK = "click"
    const val ACTION_CLICK_CENTER = "clickCenter"
    const val ACTION_LONG_CLICK = "longClick"
    const val ACTION_BACK = "back"
    const val ACTION_SWIPE = "swipe"
    const val ACTION_KEY = "key"
    const val ACTION_WAIT = "wait"
    const val ACTION_LAUNCH = "launch"
}

/**
 * 跳过/命中原因的**稳定诊断码**。
 *
 * 取值全部是 `[A-Z][A-Z0-9_]*`：审计详情字段（见 `AuditPolicy.detailPattern`）只接受这种形态，
 * 而这里每个码最终都会被执行器写进 `ACCESSIBILITY_ACTION` 的详情里。中文说明放在
 * `reason` 里给用户看，码放这里给日志与界面筛选看——两者不能混成一个字段。
 */
internal object AutomationSkipCodes {
    const val MATCHED = "MATCHED"
    const val RULE_DISABLED = "RULE_DISABLED"
    const val PACKAGE_MISMATCH = "PACKAGE_MISMATCH"
    const val ACTIVITY_UNKNOWN = "ACTIVITY_UNKNOWN"
    const val ACTIVITY_NOT_ALLOWED = "ACTIVITY_NOT_ALLOWED"
    const val ACTIVITY_EXCLUDED = "ACTIVITY_EXCLUDED"
    const val EMPTY_TREE = "EMPTY_TREE"
    const val MATCH_DELAY_PENDING = "MATCH_DELAY_PENDING"
    const val NODE_NOT_FOUND = "NODE_NOT_FOUND"
    const val ACTION_MAX_REACHED = "ACTION_MAX_REACHED"
    const val COOLDOWN_ACTIVE = "COOLDOWN_ACTIVE"
    const val CLICK_CENTER_FALLBACK = "CLICK_CENTER_FALLBACK"
}
