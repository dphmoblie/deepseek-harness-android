package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副屏深层语义压缩与无 OCR 前后结构 Diff（[VirtualScreenTreeDiff]）单元测试。
 */
class VirtualScreenTreeDiffTest {
    private fun node(
        className: String = "android.widget.FrameLayout",
        text: String = "",
        viewId: String = "",
        desc: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        checked: Boolean = false,
        selected: Boolean = false,
        focused: Boolean = false,
        enabled: Boolean = true,
        bounds: IntArray = intArrayOf(0, 0, 1080, 2400),
        children: List<VirtualScreenTree.Snapshot> = emptyList(),
    ) = VirtualScreenTree.Snapshot(
        className = className,
        text = text,
        viewId = viewId,
        contentDescription = desc,
        clickable = clickable,
        editable = editable,
        focused = focused,
        enabled = enabled,
        bounds = bounds,
        children = children,
        checked = checked,
        selected = selected,
        scrollable = scrollable,
    )

    @Test
    fun `collapses deep empty layout wrappers and extracts interactive controls with exact centers`() {
        var deep: VirtualScreenTree.Snapshot = node(
            className = "android.widget.RadioButton",
            text = "D. 无需另行告知",
            viewId = "com.quiz:id/opt_d",
            clickable = true,
            bounds = intArrayOf(48, 900, 1032, 980),
        )
        // 套 12 层无语义布局容器（旧 maxDepth=4 会直接截断丢掉该选项）。
        repeat(12) {
            deep = node(className = "android.widget.LinearLayout", children = listOf(deep))
        }

        val semantic = VirtualScreenTreeDiff.extractSemantic(deep, displayId = 7, packageName = "com.quiz")
        assertEquals(1, semantic.elements.size)
        assertFalse(semantic.truncated)
        val el = semantic.elements.single()
        assertEquals("RadioButton", el.className)
        assertEquals("com.quiz:id/opt_d", el.viewId)
        assertEquals("D. 无需另行告知", el.text)
        assertEquals(540, el.centerX)
        assertEquals(940, el.centerY)
        assertTrue(el.clickable)
    }

    @Test
    fun `diff detects checked flip and sibling downward layout shift without false remove-add churn`() {
        val beforeRoot = node(
            children = listOf(
                node(className = "android.widget.TextView", text = "第 10 题：下列哪项正确？", bounds = intArrayOf(40, 120, 1040, 240)),
                node(className = "android.widget.RadioButton", text = "A. 选项一", clickable = true, bounds = intArrayOf(48, 300, 1032, 384)),
                node(className = "android.widget.RadioButton", text = "B. 选项二", clickable = true, checked = false, bounds = intArrayOf(48, 400, 1032, 484)),
                node(className = "android.widget.RadioButton", text = "C. 选项三", clickable = true, bounds = intArrayOf(48, 500, 1032, 584)),
                node(className = "android.widget.RadioButton", text = "D. 选项四", clickable = true, bounds = intArrayOf(48, 600, 1032, 684)),
            ),
        )
        // 选中 B 后 B 增高 44px，C 和 D 整体下移 44px，新增提示「已选择 B」。
        val afterRoot = node(
            children = listOf(
                node(className = "android.widget.TextView", text = "第 10 题：下列哪项正确？", bounds = intArrayOf(40, 120, 1040, 240)),
                node(className = "android.widget.RadioButton", text = "A. 选项一", clickable = true, bounds = intArrayOf(48, 300, 1032, 384)),
                node(className = "android.widget.RadioButton", text = "B. 选项二", clickable = true, checked = true, bounds = intArrayOf(48, 400, 1032, 528)),
                node(className = "android.widget.TextView", text = "已选择 B", bounds = intArrayOf(64, 490, 300, 520)),
                node(className = "android.widget.RadioButton", text = "C. 选项三", clickable = true, bounds = intArrayOf(48, 544, 1032, 628)),
                node(className = "android.widget.RadioButton", text = "D. 选项四", clickable = true, bounds = intArrayOf(48, 644, 1032, 728)),
            ),
        )

        val before = VirtualScreenTreeDiff.extractSemantic(beforeRoot, displayId = 7, packageName = "com.quiz")
        val after = VirtualScreenTreeDiff.extractSemantic(afterRoot, displayId = 7, packageName = "com.quiz")
        val delta = VirtualScreenTreeDiff.diff(before, after)

        assertEquals("accessibility_diff", delta.getString("source"))
        assertTrue(delta.getBoolean("changed"))
        assertEquals("delta", delta.getString("kind"))
        assertEquals(1, delta.getInt("addedCount"))
        assertEquals(0, delta.getInt("removedCount"))
        assertEquals(3, delta.getInt("updatedCount"))

        val updated = delta.getJSONArray("updated")
        val bUpdate = (0 until updated.length()).map { updated.getJSONObject(it) }.first { it.getString("text") == "B. 选项二" }
        assertEquals(false, bUpdate.getJSONObject("changes").getJSONArray("checked").getBoolean(0))
        assertEquals(true, bUpdate.getJSONObject("changes").getJSONArray("checked").getBoolean(1))
        assertEquals(44, bUpdate.getJSONObject("changes").getInt("dh"))

        val cUpdate = (0 until updated.length()).map { updated.getJSONObject(it) }.first { it.getString("text") == "C. 选项三" }
        assertEquals(44, cUpdate.getJSONObject("changes").getInt("dy"))
        assertEquals(586, cUpdate.getJSONArray("center").getInt(1))
    }

    @Test
    fun `diff classifies full page transition and embeds compact elements of the new page`() {
        val page1 = VirtualScreenTreeDiff.extractSemantic(
            node(children = (1..5).map { node(className = "android.widget.TextView", text = "题目1-条目$it", bounds = intArrayOf(0, it * 100, 900, it * 100 + 80)) }),
            displayId = 7,
            packageName = "com.quiz",
        )
        val page2 = VirtualScreenTreeDiff.extractSemantic(
            node(children = (1..5).map { node(className = "android.widget.TextView", text = "题目2-条目$it", bounds = intArrayOf(0, it * 100, 900, it * 100 + 80)) }),
            displayId = 7,
            packageName = "com.quiz",
        )

        val delta = VirtualScreenTreeDiff.diff(page1, page2)
        assertTrue(delta.getBoolean("changed"))
        assertEquals("page_changed", delta.getString("kind"))
        assertEquals(5, delta.getJSONArray("elements").length())
    }

    @Test
    fun `diff reports unchanged when semantic elements stay identical`() {
        val snap = VirtualScreenTreeDiff.extractSemantic(
            node(children = listOf(node(className = "android.widget.Button", text = "提交", clickable = true, bounds = intArrayOf(100, 900, 500, 1000)))),
            displayId = 7,
            packageName = "com.quiz",
        )
        val delta = VirtualScreenTreeDiff.diff(snap, snap)
        assertFalse(delta.getBoolean("changed"))
        assertEquals("unchanged", delta.getString("kind"))
    }

    @Test
    fun `selector matching supports viewId exact text case-insensitive textContains and desc`() {
        assertTrue(
            VirtualScreenTreeDiff.matches(
                viewId = "com.quiz:id/next_btn",
                text = "下一题 Next",
                desc = "前往下一题",
                selector = VirtualScreenTreeDiff.NodeSelector(viewId = "com.quiz:id/next_btn", textContains = "next"),
            ),
        )
        assertFalse(
            VirtualScreenTreeDiff.matches(
                viewId = "com.quiz:id/prev_btn",
                text = "上一题",
                desc = "返回上一题",
                selector = VirtualScreenTreeDiff.NodeSelector(text = "下一题"),
            ),
        )
        assertTrue(
            VirtualScreenTreeDiff.matches(
                viewId = "",
                text = "",
                desc = "关闭弹窗按钮",
                selector = VirtualScreenTreeDiff.NodeSelector(desc = "关闭弹窗"),
            ),
        )
    }
}
