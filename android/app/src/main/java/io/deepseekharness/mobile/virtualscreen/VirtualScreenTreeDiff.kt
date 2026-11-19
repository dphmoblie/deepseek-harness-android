package io.deepseekharness.mobile.virtualscreen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 副屏语义树压缩、选择器匹配与前后结构 Diff（纯 Kotlin，无 Android / OCR 依赖）。
 *
 * 解决无截图、无 OCR 条件下副屏交互的两大核心问题：
 * 1. **深层控件不再被浅层 `maxDepth=4` 吞掉**：遍历深层节点时自动折叠纯布局容器（无文本/无描述/无 ID 且不可交互），
 *    直接输出精简扁平的语义控件表（含精确 `center=[cx,cy]`、状态标志与稳定锚点 `key`）；
 * 2. **每次动作自带结构化真值回执**：基于非坐标主键（`viewId` → `短类名+语义文本` → 结构近邻）做前后 Diff，
 *    明确报告 `checked/selected/text` 变更、新增/消失元素、整页切换，以及选项展开后的 `dy` 坐标漂移与最新中心点。
 */
internal object VirtualScreenTreeDiff {
    const val MAX_SEMANTIC_ELEMENTS = 120
    const val MAX_DIFF_ITEMS = 40
    const val MAX_PAGE_ELEMENTS = 80
    private const val SHIFT_THRESHOLD_PX = 4
    private const val PAGE_CHANGE_RATIO = 0.6

    data class SemanticElement(
        val ref: String,
        val key: String,
        val className: String,
        val viewId: String,
        val text: String,
        val desc: String,
        val bounds: IntArray,
        val centerX: Int,
        val centerY: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checked: Boolean,
        val selected: Boolean,
        val focused: Boolean,
        val enabled: Boolean,
    ) {
        val width: Int get() = (bounds.getOrElse(2) { 0 } - bounds.getOrElse(0) { 0 }).coerceAtLeast(0)
        val height: Int get() = (bounds.getOrElse(3) { 0 } - bounds.getOrElse(1) { 0 }).coerceAtLeast(0)

        fun toCompactJson(): JSONObject {
            val json = JSONObject().put("ref", ref)
            if (className.isNotEmpty()) json.put("cls", className)
            if (viewId.isNotEmpty()) json.put("viewId", viewId)
            if (text.isNotEmpty()) json.put("text", text)
            if (desc.isNotEmpty()) json.put("desc", desc)
            json.put("bounds", JSONArray().put(bounds[0]).put(bounds[1]).put(bounds[2]).put(bounds[3]))
            json.put("center", JSONArray().put(centerX).put(centerY))
            val flags = JSONArray()
            if (clickable) flags.put("clickable")
            if (editable) flags.put("editable")
            if (scrollable) flags.put("scrollable")
            if (checked) flags.put("checked")
            if (selected) flags.put("selected")
            if (focused) flags.put("focused")
            if (!enabled) flags.put("disabled")
            if (flags.length() > 0) json.put("flags", flags)
            return json
        }
    }

    data class SemanticSnapshot(
        val displayId: Int,
        val packageName: String,
        val elements: List<SemanticElement>,
        val truncated: Boolean,
        val imeText: String = "",
    ) {
        fun toCompactJson(): JSONObject {
            val array = JSONArray()
            elements.forEach { array.put(it.toCompactJson()) }
            val result = JSONObject()
                .put("displayId", displayId)
                .put("available", true)
                .put("mode", "compact")
                .put("elementCount", elements.size)
                .put("truncated", truncated)
                .put("elements", array)
            if (packageName.isNotEmpty()) result.put("packageName", packageName)
            if (imeText.isNotEmpty()) result.put("imeText", imeText)
            return result
        }
    }

    /** 副屏语义选择器：支持 viewId / 精确文本 / 子串文本 / desc 组合定位，不依赖绝对像素坐标。 */
    data class NodeSelector(
        val viewId: String? = null,
        val text: String? = null,
        val textContains: String? = null,
        val desc: String? = null,
        val matchIndex: Int = 0,
    ) {
        fun isValid(): Boolean =
            (!viewId.isNullOrBlank() || !text.isNullOrBlank() || !textContains.isNullOrBlank() || !desc.isNullOrBlank()) &&
                matchIndex >= 0
    }

    /**
     * 把完整快照折叠成语义控件列表：跳过纯布局容器，只保留有文本/描述/资源 ID 或可交互/带状态的节点。
     * 若某个文本叶子节点的 bounds 退化为空矩形，则自动继承最近非空祖先的 bounds（适配部分 WebView / Compose 节点）。
     */
    fun extractSemantic(
        root: VirtualScreenTree.Snapshot,
        displayId: Int = 0,
        packageName: String = "",
        maxElements: Int = MAX_SEMANTIC_ELEMENTS,
        imeText: String = "",
    ): SemanticSnapshot {
        val out = ArrayList<SemanticElement>()
        val keyCounts = HashMap<String, Int>()
        var truncated = false

        fun walk(node: VirtualScreenTree.Snapshot, inheritedBounds: IntArray?) {
            if (out.size >= maxElements) {
                truncated = true
                return
            }
            val ownBounds = validBoundsOrNull(node.bounds)
            val effectiveBounds = ownBounds ?: inheritedBounds
            val normalizedText = normalizeLabel(node.text)
            val normalizedDesc = normalizeLabel(node.contentDescription)
            val viewId = node.viewId.trim()
            val meaningful = normalizedText.isNotEmpty() ||
                normalizedDesc.isNotEmpty() ||
                viewId.isNotEmpty() ||
                node.clickable ||
                node.editable ||
                node.scrollable ||
                node.checked ||
                node.selected ||
                node.focused
            if (meaningful && effectiveBounds != null) {
                val shortCls = shortClassName(node.className)
                val baseKey = buildBaseKey(shortCls, viewId, normalizedText, normalizedDesc)
                val occurrence = keyCounts.getOrDefault(baseKey, 0)
                keyCounts[baseKey] = occurrence + 1
                val key = if (occurrence == 0) baseKey else "$baseKey#$occurrence"
                val cx = (effectiveBounds[0] + effectiveBounds[2]) / 2
                val cy = (effectiveBounds[1] + effectiveBounds[3]) / 2
                out += SemanticElement(
                    ref = "e${out.size}",
                    key = key,
                    className = shortCls,
                    viewId = viewId,
                    text = normalizedText,
                    desc = normalizedDesc,
                    bounds = effectiveBounds.copyOf(),
                    centerX = cx,
                    centerY = cy,
                    clickable = node.clickable,
                    editable = node.editable,
                    scrollable = node.scrollable,
                    checked = node.checked,
                    selected = node.selected,
                    focused = node.focused,
                    enabled = node.enabled,
                )
            }
            for (child in node.children) {
                if (out.size >= maxElements) {
                    truncated = true
                    break
                }
                walk(child, effectiveBounds)
            }
        }

        walk(root, null)
        return SemanticSnapshot(
            displayId = displayId,
            packageName = packageName,
            elements = out,
            truncated = truncated,
            imeText = imeText,
        )
    }

    /** 判断单个节点属性是否命中语义选择器。 */
    fun matches(
        viewId: String,
        text: String,
        desc: String,
        selector: NodeSelector,
    ): Boolean {
        if (!selector.isValid()) return false
        val normalizedText = normalizeLabel(text)
        val normalizedDesc = normalizeLabel(desc)
        selector.viewId?.takeIf { it.isNotBlank() }?.let { expected ->
            if (viewId.trim() != expected.trim()) return false
        }
        selector.text?.takeIf { it.isNotBlank() }?.let { expected ->
            if (normalizedText != normalizeLabel(expected)) return false
        }
        selector.textContains?.takeIf { it.isNotBlank() }?.let { needle ->
            if (!normalizedText.contains(normalizeLabel(needle), ignoreCase = true)) return false
        }
        selector.desc?.takeIf { it.isNotBlank() }?.let { expected ->
            val target = normalizeLabel(expected)
            if (normalizedDesc != target && !normalizedDesc.contains(target, ignoreCase = true)) return false
        }
        return true
    }

    /**
     * 比较动作前后的两份语义快照，输出精简的结构化变动摘要（零截图、零 OCR）。
     */
    fun diff(before: SemanticSnapshot, after: SemanticSnapshot): JSONObject {
        val packageChanged = before.packageName.isNotEmpty() &&
            after.packageName.isNotEmpty() &&
            before.packageName != after.packageName

        val matchedBefore = HashSet<Int>()
        val matchedAfter = HashSet<Int>()
        val pairs = ArrayList<Pair<SemanticElement, SemanticElement>>()

        // 第 1 轮：按稳定主键（viewId 或 短类名+文本/描述）精确匹配。
        val beforeByKey = HashMap<String, Int>(before.elements.size)
        before.elements.forEachIndexed { index, element -> beforeByKey[element.key] = index }
        after.elements.forEachIndexed { afterIndex, afterEl ->
            val beforeIndex = beforeByKey[afterEl.key]
            if (beforeIndex != null && matchedBefore.add(beforeIndex)) {
                matchedAfter.add(afterIndex)
                pairs += before.elements[beforeIndex] to afterEl
            }
        }

        // 第 2 轮：同 viewId 但文字变更的控件（如按钮「发送」→「发送中…」）。
        val remainingBeforeById = HashMap<String, ArrayDeque<Int>>()
        before.elements.forEachIndexed { index, el ->
            if (index !in matchedBefore && el.viewId.isNotEmpty()) {
                remainingBeforeById.getOrPut(el.viewId) { ArrayDeque() }.addLast(index)
            }
        }
        after.elements.forEachIndexed { afterIndex, afterEl ->
            if (afterIndex in matchedAfter || afterEl.viewId.isEmpty()) return@forEachIndexed
            val queue = remainingBeforeById[afterEl.viewId] ?: return@forEachIndexed
            val beforeIndex = queue.removeFirstOrNull() ?: return@forEachIndexed
            matchedBefore.add(beforeIndex)
            matchedAfter.add(afterIndex)
            pairs += before.elements[beforeIndex] to afterEl
        }

        // 第 3 轮：同类且空间高度重叠（IoU >= 0.45）的原地文案更新控件。
        after.elements.forEachIndexed { afterIndex, afterEl ->
            if (afterIndex in matchedAfter) return@forEachIndexed
            var bestBefore = -1
            var bestIou = 0.45
            before.elements.forEachIndexed { beforeIndex, beforeEl ->
                if (beforeIndex in matchedBefore) return@forEachIndexed
                if (beforeEl.className != afterEl.className) return@forEachIndexed
                val iou = boundsIou(beforeEl.bounds, afterEl.bounds)
                if (iou >= bestIou) {
                    bestIou = iou
                    bestBefore = beforeIndex
                }
            }
            if (bestBefore >= 0) {
                matchedBefore.add(bestBefore)
                matchedAfter.add(afterIndex)
                pairs += before.elements[bestBefore] to afterEl
            }
        }

        val added = after.elements.filterIndexed { index, _ -> index !in matchedAfter }
        val removed = before.elements.filterIndexed { index, _ -> index !in matchedBefore }
        val updated = ArrayList<JSONObject>()
        var meaningfulUpdateCount = 0
        var textChangedCount = 0

        for ((prev, curr) in pairs) {
            val changes = JSONObject()
            var hasSemanticChange = false
            var contentReplaced = false
            if (prev.text != curr.text) {
                changes.put("text", JSONArray().put(prev.text).put(curr.text))
                hasSemanticChange = true
                contentReplaced = true
            }
            if (prev.desc != curr.desc) {
                changes.put("desc", JSONArray().put(prev.desc).put(curr.desc))
                hasSemanticChange = true
                contentReplaced = true
            }
            if (contentReplaced) textChangedCount++
            if (prev.checked != curr.checked) {
                changes.put("checked", JSONArray().put(prev.checked).put(curr.checked))
                hasSemanticChange = true
            }
            if (prev.selected != curr.selected) {
                changes.put("selected", JSONArray().put(prev.selected).put(curr.selected))
                hasSemanticChange = true
            }
            if (prev.enabled != curr.enabled) {
                changes.put("enabled", JSONArray().put(prev.enabled).put(curr.enabled))
                hasSemanticChange = true
            }
            if (prev.focused != curr.focused) {
                changes.put("focused", JSONArray().put(prev.focused).put(curr.focused))
                hasSemanticChange = true
            }
            val dx = curr.centerX - prev.centerX
            val dy = curr.centerY - prev.centerY
            val dh = curr.height - prev.height
            val shifted = abs(dx) >= SHIFT_THRESHOLD_PX || abs(dy) >= SHIFT_THRESHOLD_PX || abs(dh) >= SHIFT_THRESHOLD_PX
            if (abs(dx) >= SHIFT_THRESHOLD_PX) changes.put("dx", dx)
            if (abs(dy) >= SHIFT_THRESHOLD_PX) changes.put("dy", dy)
            if (abs(dh) >= SHIFT_THRESHOLD_PX) changes.put("dh", dh)

            if (hasSemanticChange || shifted) {
                meaningfulUpdateCount++
                if (updated.size < MAX_DIFF_ITEMS) {
                    val item = JSONObject().put("ref", curr.ref)
                    if (curr.viewId.isNotEmpty()) item.put("viewId", curr.viewId)
                    if (curr.text.isNotEmpty()) item.put("text", curr.text)
                    if (curr.desc.isNotEmpty() && curr.text.isEmpty()) item.put("desc", curr.desc)
                    item.put("changes", changes)
                    item.put("center", JSONArray().put(curr.centerX).put(curr.centerY))
                    item.put("bounds", JSONArray().put(curr.bounds[0]).put(curr.bounds[1]).put(curr.bounds[2]).put(curr.bounds[3]))
                    updated += item
                }
            }
        }

        val imeChanged = before.imeText != after.imeText
        val denom = maxOf(before.elements.size, after.elements.size, 1)
        // 同构模板翻页（如答题页从第 10 题切到第 11 题）控件框位置重合、但正文大面积替换，同样属于整页切换。
        val churnRatio = (added.size + removed.size + textChangedCount).toDouble() / denom.toDouble()
        val pageChanged = packageChanged || (denom >= 4 && churnRatio > PAGE_CHANGE_RATIO)
        val changed = pageChanged || added.isNotEmpty() || removed.isNotEmpty() || meaningfulUpdateCount > 0 || imeChanged

        val result = JSONObject()
            .put("source", "accessibility_diff")
            .put("changed", changed)
            .put("kind", when {
                !changed -> "unchanged"
                pageChanged -> "page_changed"
                else -> "delta"
            })
            .put("elementCount", after.elements.size)
            .put("addedCount", added.size)
            .put("removedCount", removed.size)
            .put("updatedCount", meaningfulUpdateCount)
        if (after.packageName.isNotEmpty()) result.put("packageName", after.packageName)
        if (imeChanged && after.imeText.isNotEmpty()) result.put("imeText", after.imeText)

        if (pageChanged) {
            val pageElements = JSONArray()
            after.elements.take(MAX_PAGE_ELEMENTS).forEach { pageElements.put(it.toCompactJson()) }
            result.put("elements", pageElements)
            if (after.elements.size > MAX_PAGE_ELEMENTS || after.truncated) result.put("truncated", true)
        } else if (changed) {
            if (added.isNotEmpty()) {
                val addedArray = JSONArray()
                added.take(MAX_DIFF_ITEMS).forEach { addedArray.put(it.toCompactJson()) }
                result.put("added", addedArray)
            }
            if (removed.isNotEmpty()) {
                val removedArray = JSONArray()
                removed.take(MAX_DIFF_ITEMS).forEach { item ->
                    val summary = JSONObject().put("ref", item.ref)
                    if (item.viewId.isNotEmpty()) summary.put("viewId", item.viewId)
                    if (item.text.isNotEmpty()) summary.put("text", item.text)
                    else if (item.desc.isNotEmpty()) summary.put("desc", item.desc)
                    removedArray.put(summary)
                }
                result.put("removed", removedArray)
            }
            if (updated.isNotEmpty()) {
                val updatedArray = JSONArray()
                updated.forEach { updatedArray.put(it) }
                result.put("updated", updatedArray)
            }
        }
        return result
    }

    private fun validBoundsOrNull(raw: IntArray): IntArray? {
        if (raw.size < 4) return null
        val l = raw[0]
        val t = raw[1]
        val r = raw[2]
        val b = raw[3]
        if (r <= l || b <= t) return null
        return intArrayOf(l, t, r, b)
    }

    private fun shortClassName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.substringAfterLast('.')
    }

    private fun normalizeLabel(raw: String): String {
        val collapsed = raw.replace(WHITESPACE, " ").trim()
        return if (collapsed.length <= 120) collapsed else collapsed.take(120) + "…"
    }

    private fun buildBaseKey(shortCls: String, viewId: String, text: String, desc: String): String = when {
        viewId.isNotEmpty() && text.isNotEmpty() -> "id:$viewId|t:${text.take(40)}"
        viewId.isNotEmpty() -> "id:$viewId"
        text.isNotEmpty() -> "cls:$shortCls|t:${text.take(48)}"
        desc.isNotEmpty() -> "cls:$shortCls|d:${desc.take(48)}"
        else -> "cls:$shortCls"
    }

    private fun boundsIou(a: IntArray, b: IntArray): Double {
        val interLeft = maxOf(a[0], b[0])
        val interTop = maxOf(a[1], b[1])
        val interRight = minOf(a[2], b[2])
        val interBottom = minOf(a[3], b[3])
        if (interRight <= interLeft || interBottom <= interTop) return 0.0
        val inter = (interRight - interLeft).toDouble() * (interBottom - interTop).toDouble()
        val areaA = (a[2] - a[0]).coerceAtLeast(0).toDouble() * (a[3] - a[1]).coerceAtLeast(0).toDouble()
        val areaB = (b[2] - b[0]).coerceAtLeast(0).toDouble() * (b[3] - b[1]).coerceAtLeast(0).toDouble()
        val union = areaA + areaB - inter
        return if (union <= 0.0) 0.0 else inter / union
    }

    private val WHITESPACE = Regex("\\s+")
}
