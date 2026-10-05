package io.deepseekharness.mobile.virtualscreen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VirtualScreenTree] 纯函数单测。
 *
 * JVM 里没有 Android 运行时，所以这里只覆盖不碰 Android 类的两个函数：[VirtualScreenTree.validText] 与
 * [VirtualScreenTree.summarize]（[VirtualScreenTree.Snapshot] 是纯数据，可以直接构造）。`dump`/`setText`
 * 依赖无障碍服务与真实副屏，只能在真机上验证，不在这里假装覆盖。
 */
class VirtualScreenTreeTest {
    /** 构造节点快照：默认是全空的叶子节点，用例只写自己关心的字段。 */
    private fun snapshot(
        className: String = "",
        text: String = "",
        viewId: String = "",
        contentDescription: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        focused: Boolean = false,
        enabled: Boolean = true,
        bounds: IntArray = intArrayOf(0, 0, 0, 0),
        children: List<VirtualScreenTree.Snapshot> = emptyList(),
    ) = VirtualScreenTree.Snapshot(
        className = className,
        text = text,
        viewId = viewId,
        contentDescription = contentDescription,
        clickable = clickable,
        editable = editable,
        focused = focused,
        enabled = enabled,
        bounds = bounds,
        children = children,
    )

    /** 取出 bounds 数组，方便按 [left, top, right, bottom] 直接断言。 */
    private fun boundsOf(json: JSONObject): List<Int> {
        val bounds = json.getJSONArray("bounds")
        return (0 until bounds.length()).map { bounds.getInt(it) }
    }

    /** 是否残留落单的代理字符（被切开的半个 emoji）——JSON 里出现它，消费方拿到的是坏字符。 */
    private fun hasLoneSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current.isHighSurrogate()) {
                val next = value.getOrNull(index + 1)
                if (next == null || !next.isLowSurrogate()) return true
                index += 2
                continue
            }
            if (current.isLowSurrogate()) return true
            index += 1
        }
        return false
    }

    @Test
    fun `validText 接受中文 emoji 与任意非控制字符`() {
        assertTrue(VirtualScreenTree.validText("你好，世界"))
        assertTrue(VirtualScreenTree.validText("😀"))
        assertTrue(VirtualScreenTree.validText("混合 mixed 中文 123 ！@#￥%……&*（）"))
        assertTrue(VirtualScreenTree.validText("a"))
        assertTrue(VirtualScreenTree.validText("a".repeat(VirtualScreenTree.MAX_TEXT_CHARS)))
    }

    @Test
    fun `validText 拒绝空串 换行 控制字符与零宽字符`() {
        val rejected = listOf(
            // 空串
            "",
            // 换行、回车、制表符都算控制字符
            "第一行\n第二行",
            "a\rb",
            "a\tb",
            // 典型控制字符与 C1 区
            "\u0000",
            "\u0007",
            "\u001F",
            "\u007F",
            "\u0085",
            // 零宽字符：屏幕上不可见，却能让「看起来一样」的文本实际不同
            "a\u200Bb",
            "a\u200Cb",
            "a\u200Db",
            "a\u2060b",
            "a\uFEFFb",
            "a\u180Eb",
        )
        for (text in rejected) {
            val label = text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            assertFalse("应拒绝：「$label」", VirtualScreenTree.validText(text))
        }
    }

    @Test
    fun `validText 按 UTF-16 代码单元计算长度上限`() {
        // 上限按代码单元计（与既有 inputText 的 1..512 同口径），所以一个 emoji 占 2 个
        assertFalse(VirtualScreenTree.validText("a".repeat(VirtualScreenTree.MAX_TEXT_CHARS + 1)))
        assertTrue(VirtualScreenTree.validText("😀".repeat(VirtualScreenTree.MAX_TEXT_CHARS / 2)))
        assertFalse(VirtualScreenTree.validText("😀".repeat(VirtualScreenTree.MAX_TEXT_CHARS / 2 + 1)))
    }

    @Test
    fun `summarize 输出稳定字段并省略空字段`() {
        val json = VirtualScreenTree.summarize(
            snapshot(
                className = "android.widget.Button",
                text = "确定",
                clickable = true,
                enabled = false,
                bounds = intArrayOf(10, 20, 30, 40),
            ),
        )
        // 恒定字段：index、布尔量与 bounds
        assertEquals(0, json.getInt("index"))
        assertEquals("android.widget.Button", json.getString("className"))
        assertEquals("确定", json.getString("text"))
        assertTrue(json.getBoolean("clickable"))
        assertFalse(json.getBoolean("editable"))
        assertFalse(json.getBoolean("focused"))
        assertFalse(json.getBoolean("enabled"))
        assertEquals(listOf(10, 20, 30, 40), boundsOf(json))
        // 空字段不输出：没有 viewId、desc、子节点，也没有失败原因
        assertFalse(json.has("viewId"))
        assertFalse(json.has("desc"))
        assertFalse(json.has("children"))
        assertFalse(json.has("reason"))
    }

    @Test
    fun `summarize 按同级顺序编号子节点并保留嵌套结构`() {
        val tree = snapshot(
            className = "android.widget.FrameLayout",
            children = listOf(
                snapshot(className = "android.widget.TextView"),
                snapshot(className = "android.widget.EditText", editable = true, focused = true),
                snapshot(
                    className = "android.widget.Button",
                    children = listOf(snapshot(className = "android.widget.TextView", text = "提交")),
                ),
            ),
        )
        val json = VirtualScreenTree.summarize(tree)
        val children = json.getJSONArray("children")
        assertEquals(3, children.length())
        assertEquals(
            listOf(0, 1, 2),
            (0 until children.length()).map { children.getJSONObject(it).getInt("index") },
        )
        assertEquals("android.widget.TextView", children.getJSONObject(0).getString("className"))
        assertTrue(children.getJSONObject(1).getBoolean("editable"))
        assertTrue(children.getJSONObject(1).getBoolean("focused"))
        assertFalse(children.getJSONObject(0).has("children"))
        // 孙节点留在自己父节点的 children 里，不会摊平到顶层
        val grandChild = children.getJSONObject(2).getJSONArray("children").getJSONObject(0)
        assertEquals(0, grandChild.getInt("index"))
        assertEquals("提交", grandChild.getString("text"))
    }

    @Test
    fun `summarize 把文本截断到 120 字符并追加省略号`() {
        val text = VirtualScreenTree.summarize(snapshot(text = "词".repeat(200))).getString("text")
        assertEquals("词".repeat(120) + "…", text)
        assertEquals(121, text.length)
        // 恰好 120 字符不截断
        val exact = "词".repeat(120)
        assertEquals(exact, VirtualScreenTree.summarize(snapshot(text = exact)).getString("text"))
        // className 走同一条截断规则
        assertEquals(
            "a".repeat(120) + "…",
            VirtualScreenTree.summarize(snapshot(className = "a".repeat(200))).getString("className"),
        )
    }

    @Test
    fun `summarize 不切开 emoji 的代理对`() {
        // 119 个 a + 一个 emoji（2 个代码单元）= 121 个代码单元：截断点正好落在 emoji 中间
        val json = VirtualScreenTree.summarize(snapshot(text = "a".repeat(119) + "😀"))
        val text = json.getString("text")
        assertEquals("a".repeat(119) + "…", text)
        assertFalse("截断不能留下落单的代理字符", hasLoneSurrogate(text))
    }

    @Test
    fun `summarize 折叠换行与连续空白 纯空白字段不输出`() {
        val json = VirtualScreenTree.summarize(snapshot(text = "第一行\n第二行\t 制表  多空格\r\n"))
        assertEquals("第一行 第二行 制表 多空格", json.getString("text"))
        // 纯空白等于没有内容：字段整个不输出
        assertFalse(VirtualScreenTree.summarize(snapshot(text = " \n\t\r ")).has("text"))
    }

    @Test
    fun `summarize 的 bounds 恒为四位数组`() {
        assertEquals(listOf(0, 0, 0, 0), boundsOf(VirtualScreenTree.summarize(snapshot())))
        // 源数组不足四位时补 0，保证消费方永远拿到 [left, top, right, bottom]
        assertEquals(
            listOf(1, 2, 0, 0),
            boundsOf(VirtualScreenTree.summarize(snapshot(bounds = intArrayOf(1, 2)))),
        )
        // 多出来的位数被忽略
        assertEquals(
            listOf(1, 2, 3, 4),
            boundsOf(VirtualScreenTree.summarize(snapshot(bounds = intArrayOf(1, 2, 3, 4, 5)))),
        )
    }
}
