package io.deepseekharness.mobile.virtualscreen

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 副屏（虚拟显示器）节点树读取与定向文本注入。
 *
 * ## 为什么只能由本应用的无障碍服务来读
 * 真机（HONOR / Android 17）实测：`uiautomator dump --display <副屏编号>` 会忽略 `--display` 只 dump 主屏，
 * `screencap -d <副屏编号>` 直接报 `Display Id 'N' is not valid.`。副屏的节点树只能从无障碍服务自己的
 * `AccessibilityNodeInfo` 拿：服务侧 `getWindows()` 返回的窗口里，`AccessibilityWindowInfo.getDisplayId()`
 * 是唯一能按显示编号定位窗口的公开 API（Android 11 / API 30 起），因此低版本一律返回失败，而不是猜一个窗口。
 *
 * ## 边界与取舍（每一条都是刻意的）
 * 1. **只作用于传入的显示编号**：只从窗口列表里挑 `displayId` 相等的窗口，绝不回退到主屏的
 *    `rootInActiveWindow`；`displayId <= 0` 直接拒绝（0 是主屏）。这与 [VirtualScreenPolicy.inputArguments]、
 *    [VirtualScreenPolicy.targetResumed] 的「不能向主屏发送副屏输入」是同一条规则——否则本模块会成为
 *    绕过无障碍包白名单去读主屏的后门。
 * 2. **不要求副屏目标应用在无障碍自动化白名单里**：白名单拦的是「本应用替用户在别处乱点」，而副屏会话
 *    本身就是用户在原生页面里明确选择、并且此刻正看着画面的那块屏；再要求用户把同一个应用抄进白名单，
 *    只是把同一次选择做两遍，换不来额外安全。这一条**不**放宽其它任何闸门（见下）。
 * 3. **敏感窗口整屏拒绝**：复用服务上既有的 `containsSensitiveWindow`（密码框、验证码、支付、权限弹窗，
 *    也包括扫描不完的超大节点树），命中时 [dump] 返回 `available=false` 加原因，[setText] 返回 false。
 * 4. **锁定/熄屏拒绝**：复用既有 `isLockedOrScreenOff`。
 * 5. **写路径有频率限制**：复用既有 `ACTION_INTERVAL_MS`，只有系统接受这次注入才记账；读路径不限频
 *    （与既有的主屏 `accessibilityTree` 一致）。
 * 6. **审计沿用既有事件类别**：读记 `ACCESSIBILITY_READ`，写记 `ACCESSIBILITY_ACTION`，不新增枚举。
 *    服务未连接时拿不到可写审计的应用上下文，这类失败只返回原因、不产生审计记录。
 * 7. **不抛异常**：无障碍服务未启用（真机当前就是这个状态）时 [dump]/[setText] 都返回失败结果，
 *    由上层提示用户去系统设置里开启无障碍服务。
 *
 * ## 未见真机验证的部分
 * 「无障碍服务能否看到另一进程创建的虚拟显示器窗口」这一点在本机无法验证（服务未启用、也没有设备）。
 * 如果真机上 `getWindows()` 根本不含副屏窗口，[dump] 会如实返回 `available=false`
 * （原因「显示编号 N 上没有可读取的窗口」），不会退化成读主屏。
 */
object VirtualScreenTree {
    /** 单次读取的最大节点数；超出只标 truncated，不继续遍历。 */
    const val MAX_NODES = 400

    /** 单次读取的最大深度（根为 0）。 */
    const val MAX_DEPTH = 8

    /** 一次定向文本注入的最大字符数。 */
    const val MAX_TEXT_CHARS = 512

    /**
     * 节点字段里文本类字段（className/text/viewId/desc）的单条上限。
     * 比 [MAX_TEXT_CHARS] 更小是刻意的：一屏几百个节点，每个都带上限长文本会很快把调用方的上下文吃光。
     */
    private const val FIELD_TEXT_LIMIT = 120

    /** 服务未连接时的统一原因：这是真机上唯一「用户自己能修」的前置条件。 */
    private const val SERVICE_UNAVAILABLE = "无障碍服务未启用：请在系统设置里开启 DeepSeek 的无障碍服务后重试"

    /**
     * 零宽字符：屏幕上看不见，却能让「看起来一样的文本」实际不同（把不可见字符写进别人的输入框、
     * 绕过重复内容校验）。这里一律拒绝。
     *
     * 注意 U+200D（ZWJ）也在拒绝之列，因此 👨‍👩‍👧 这类用 ZWJ 拼合的 emoji 序列会被拒；
     * 普通 emoji（😀）与常见的组合 emoji（带肤色修饰符、旗帜）不受影响。
     */
    private val ZERO_WIDTH = setOf('\u180E', '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')

    /** 空白折叠：节点里的换行、制表符会污染单行 JSON，统一折成一个空格（见 [normalize]）。 */
    private val WHITESPACE = Regex("\\s+")

    /**
     * 无障碍服务是否可用（= 本模块此刻能不能读副屏节点树）。
     *
     * 需要同时满足：服务已连接，且系统版本不低于 Android 11（`AccessibilityWindowInfo.getDisplayId()`
     * 的最低版本）。**服务已连接但系统版本过低时同样返回 false**，因为对调用方而言结果一样：
     * 这条路径读不到副屏。两种原因的区别只在 [dump] 的 `reason` 里（[available] 无法区分）。
     */
    fun available(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && DeepSeekAccessibilityService.current() != null

    /**
     * 取副屏（[displayId]）窗口的节点树。
     *
     * 返回的 JSON 字段固定为 `displayId`、`available`、`nodeCount`、`truncated`、`nodes`；
     * `packageName` 与 `reason` 只在有值时出现（空字段不输出，省 token）。[available] 为 false 时
     * `nodes` 恒为空数组、`reason` 说明具体原因（服务未启用 / 显示编号无效 / 没有窗口 / 敏感窗口 / 设备锁定 /
     * 系统版本过低）。
     *
     * `nodes` 是窗口根节点数组（通常只有一个元素），节点总数看 `nodeCount`，树形结构在节点自己的
     * `children` 里（见 [summarize]）。
     *
     * [maxDepth] 与 [maxNodes] 会被夹到 [MAX_DEPTH]、[MAX_NODES] 以内（调用方给得更大也不会放开）；
     * 触到任一上限都只把 `truncated` 置 true 并停止遍历，不会「跳过限制继续往下挖」。
     */
    fun dump(displayId: Int, maxDepth: Int, maxNodes: Int): JSONObject {
        blockReason(displayId)?.let { return failure(displayId, it) }
        val service = DeepSeekAccessibilityService.current() ?: return failure(displayId, SERVICE_UNAVAILABLE)
        val root = service.displayRootFor(displayId).let { access -> access.root ?: return failure(displayId, access.reason ?: "副屏窗口不可读") }
        val state = WalkState(maxDepth.coerceIn(0, MAX_DEPTH), maxNodes.coerceIn(1, MAX_NODES))
        // 包名必须在遍历前取：遍历结束时根节点已经被回收。
        val packageName = try {
            root.packageName?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val tree = try {
            JSONArray().put(summarize(state.snapshot(root, 0)))
        } catch (_: Throwable) {
            null
        }
        return tree?.let { success(displayId, packageName, state.count, state.truncated, it) }
            ?: failure(displayId, "副屏节点树读取失败：窗口在读取过程中失效，请重新读取")
    }

    /**
     * 把文本写进副屏当前聚焦的可编辑节点（支持中文等任意 Unicode）。返回 true 表示系统接受了这次注入。
     *
     * 顺序固定：先 [validText]，再 `displayId` 合法性，然后由服务执行同一套闸门（锁定/熄屏 → 频率限制 →
     * 按显示编号取窗口 → 敏感窗口 → `findFocus(FOCUS_INPUT)` 且可编辑）并派发 `ACTION_SET_TEXT`。
     * 任何一步失败都返回 false，不抛异常；服务在其中的每次失败都会写审计。
     */
    fun setText(displayId: Int, text: String): Boolean {
        if (!validText(text)) return false
        if (blockReason(displayId) != null) return false
        val service = DeepSeekAccessibilityService.current() ?: return false
        return try {
            service.injectTextOnDisplay(displayId, text)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 纯函数：文本校验。
     *
     * 规则：长度 1..[MAX_TEXT_CHARS]（按 UTF-16 代码单元计，与既有 `inputText` 的 1..512 同口径，
     * 因此一个 emoji 算 2），且不含任何控制字符（U+0000..U+001F、U+007F..U+009F，含换行与制表符）
     * 或零宽字符（见 [ZERO_WIDTH]）。其余字符——中文、emoji、任意非控制字符——一律放行。
     */
    fun validText(text: String): Boolean =
        text.length in 1..MAX_TEXT_CHARS &&
            text.none { it.code <= 0x1F || it.code in 0x7F..0x9F || it in ZERO_WIDTH }

    /**
     * 纯函数：把抽好的节点快照转成 JSON（字段稳定、文本截断、去换行），供单测直接调用。
     *
     * 字段规则：
     * - 恒定出现：`index`（同级序，根为 0；同级的序号就是它在 `children` 里的位置，某个 `getChild()`
     *   返回 null 的槽位会被跳过，因此可能小于系统的原始 childId）、`clickable`、`editable`、`focused`、
     *   `enabled`、`bounds`（`[left, top, right, bottom]`，长度恒为 4，源数组不足处补 0）；
     * - 只在有值时出现：`className`、`text`、`viewId`、`desc`、`children`（空字符串与空数组都不输出）；
     * - 文本类字段先折叠空白（换行、制表符折成单个空格再 trim），超过 [FIELD_TEXT_LIMIT] 个字符时
     *   截为前 120 个字符并追加 `…`（最长 121 个字符），且不会把 emoji 的代理对切成半个字符。
     */
    fun summarize(node: Snapshot): JSONObject = summarize(node, 0)

    private fun summarize(node: Snapshot, index: Int): JSONObject {
        val json = JSONObject()
            .put("index", index)
            .put("clickable", node.clickable)
            .put("editable", node.editable)
            .put("focused", node.focused)
            .put("enabled", node.enabled)
        putField(json, "className", node.className)
        putField(json, "text", node.text)
        putField(json, "viewId", node.viewId)
        putField(json, "desc", node.contentDescription)
        val bounds = JSONArray()
        for (position in 0 until 4) bounds.put(node.bounds.getOrElse(position) { 0 })
        json.put("bounds", bounds)
        if (node.children.isNotEmpty()) {
            val children = JSONArray()
            node.children.forEachIndexed { childIndex, child -> children.put(summarize(child, childIndex)) }
            json.put("children", children)
        }
        return json
    }

    /**
     * 纯数据节点快照：只含 String / Boolean / IntArray / 自身列表，因此可以在 JVM 单测里直接构造，
     * 不把任何 Android 类带进 [summarize]（这是它能在单测里跑的前提）。
     */
    data class Snapshot(
        val className: String,
        val text: String,
        val viewId: String,
        val contentDescription: String,
        val clickable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val enabled: Boolean,
        val bounds: IntArray,
        val children: List<Snapshot>,
    )

    /** 与系统状态无关的前置拒绝；返回 null 表示可以继续。 */
    private fun blockReason(displayId: Int): String? = when {
        displayId <= 0 -> "显示编号 $displayId 无效：副屏编号必须大于 0（0 是主屏），本模块不复用主屏窗口"
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> "系统版本低于 Android 11，无法按显示编号定位窗口"
        else -> null
    }

    /** 失败信封：结构字段恒定出现，[reason] 说明原因，`nodes` 为空数组。 */
    private fun failure(displayId: Int, reason: String): JSONObject = JSONObject()
        .put("displayId", displayId)
        .put("available", false)
        .put("nodeCount", 0)
        .put("truncated", false)
        .put("nodes", JSONArray())
        .put("reason", reason)

    /** 成功信封：`packageName` 为空时省略（没有名字的窗口不值得占一个字段）。 */
    private fun success(
        displayId: Int,
        packageName: String,
        nodeCount: Int,
        truncated: Boolean,
        nodes: JSONArray,
    ): JSONObject {
        val result = JSONObject()
            .put("displayId", displayId)
            .put("available", true)
            .put("nodeCount", nodeCount)
            .put("truncated", truncated)
            .put("nodes", nodes)
        if (packageName.isNotEmpty()) result.put("packageName", packageName)
        return result
    }

    /** 文本类字段统一走这里：折叠空白 → 截断 → 空串不输出。 */
    private fun putField(json: JSONObject, name: String, value: String) {
        val normalized = normalize(value)
        if (normalized.isNotEmpty()) json.put(name, normalized)
    }

    private fun normalize(value: String): String = truncate(value.replace(WHITESPACE, " ").trim())

    private fun truncate(value: String): String {
        if (value.length <= FIELD_TEXT_LIMIT) return value
        var end = FIELD_TEXT_LIMIT
        // 截断点落在高代理上时回退一位：否则会输出半个 emoji（落单的代理字符），消费方拿到的是坏字符。
        if (value[end - 1].isHighSurrogate()) end--
        return value.take(end) + "…"
    }

    /**
     * 遍历状态：本轮的深度/节点上限、已读数与截断标记。
     *
     * 节点回收约定：本模块把 `window.root` 与 `getChild()` 拿到的节点都当作调用方持有，读完立即 `recycle()`
     * （Android 13 起 `recycle()` 已是空操作，保留是为了覆盖 minSdk 26：API 26..32 才真正有池化语义）。
     * 一次读取可能同时持有几百个节点，不回收会在旧版本上反复申请同一批池对象。
     */
    private class WalkState(private val maxDepth: Int, private val maxNodes: Int) {
        var count = 0
            private set
        var truncated = false
            private set

        /** 把 [node] 转成快照并回收它（含它的子节点）；节点数或深度到顶时置 [truncated] 并停止遍历。 */
        fun snapshot(node: AccessibilityNodeInfo, depth: Int): Snapshot {
            count++
            val children = ArrayList<Snapshot>()
            if (depth >= maxDepth) {
                if (node.childCount > 0) truncated = true
            } else {
                for (index in 0 until node.childCount) {
                    if (count >= maxNodes) {
                        truncated = true
                        break
                    }
                    val child = node.getChild(index) ?: continue
                    children.add(snapshot(child, depth + 1))
                }
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val snapshot = Snapshot(
                className = node.className?.toString().orEmpty(),
                text = node.text?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                contentDescription = node.contentDescription?.toString().orEmpty(),
                clickable = node.isClickable,
                editable = node.isEditable,
                focused = node.isFocused,
                enabled = node.isEnabled,
                bounds = intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                children = children,
            )
            // 快照只保存 String 与基本类型，读完这一层的字段就能回收，不必等整棵树读完。
            node.recycle()
            return snapshot
        }
    }
}
