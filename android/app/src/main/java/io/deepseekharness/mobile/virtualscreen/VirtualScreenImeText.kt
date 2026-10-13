package io.deepseekharness.mobile.virtualscreen

/**
 * 副屏「输入法窗口里正在编辑的文字」的挑选规则。
 *
 * 背景：读路径（[VirtualScreenTree.dump]）只认**应用窗口**——[DeepSeekAccessibilityService] 的
 * `displayWindowRoot` 在同一个显示编号上只返回一个根节点，且把输入法窗口排在最后。于是用户用输入法
 * 打进去、但应用窗口里读不到的那段字（候选/组合中的内容）在节点树里**完全没有痕迹**，AI 只能看到
 * 一个空输入框，怎么读都是「没输入」。
 *
 * 这一层是纯函数：把一个输入法窗口抽成候选列表，再按「谁最可能是用户此刻在打的那段字」排序。
 * 挑选顺序固定，不依赖节点顺序：
 * 1. 可编辑且聚焦（输入法里真正的编辑区）
 * 2. 可编辑
 * 3. 聚焦
 * 4. 离根更近（浅的先）
 *
 * 都不满足时返回空串——宁可如实报「输入法窗口里没有文字」，也不要拿候选栏上的一个按键名冒充用户输入。
 */
internal object VirtualScreenImeText {
    /** 一次读取最多看多少个输入法节点：输入法窗口通常只有几十个节点，给到 120 足够。 */
    const val MAX_NODES = 120

    /** 输入法窗口的遍历深度上限。 */
    const val MAX_DEPTH = 6

    /** 单条文本上限：与节点字段同口径，避免一条超长候选把调用方的上下文吃光。 */
    const val MAX_CHARS = 120

    /** 输入法窗口里的一个文本候选。[depth] 是它在窗口树里的深度（根为 0）。 */
    data class Candidate(
        val text: String,
        val editable: Boolean,
        val focused: Boolean,
        val depth: Int,
    )

    /** 空白折叠：输入法候选区的换行、制表符会污染单行 JSON。 */
    private val WHITESPACE = Regex("\\s+")

    /** 按固定优先级挑出用户此刻在打的那段字；没有候选时返回空串。 */
    fun pick(candidates: List<Candidate>): String {
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        var bestDepth = Int.MAX_VALUE
        for (candidate in candidates) {
            val text = normalize(candidate.text)
            if (text.isEmpty()) continue
            val rank = when {
                candidate.editable && candidate.focused -> 0
                candidate.editable -> 1
                candidate.focused -> 2
                else -> 3
            }
            if (rank < bestRank || (rank == bestRank && candidate.depth < bestDepth)) {
                best = text
                bestRank = rank
                bestDepth = candidate.depth
            }
        }
        return best.orEmpty()
    }

    /** 折叠空白、trim、按 [MAX_CHARS] 截断（截断点落在高代理上时回退一位，不吐半个 emoji）。 */
    fun normalize(value: String): String {
        val collapsed = value.replace(WHITESPACE, " ").trim()
        if (collapsed.length <= MAX_CHARS) return collapsed
        var end = MAX_CHARS
        if (collapsed[end - 1].isHighSurrogate()) end--
        return collapsed.take(end) + "…"
    }
}
