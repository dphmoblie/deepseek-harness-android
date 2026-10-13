package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输入法窗口文字的挑选规则（纯函数，无 Android 依赖）。
 *
 * 这些用例锁的是需求 14 的行为：AI 读副屏节点树时要能看到「用输入法打进去、但没落到应用窗口节点上」的字，
 * 而且不能把候选栏上的按键名当成用户输入。
 */
class VirtualScreenImeTextTest {
    @Test
    fun `可编辑且聚焦的候选优先`() {
        val picked = VirtualScreenImeText.pick(
            listOf(
                VirtualScreenImeText.Candidate("q", editable = false, focused = false, depth = 2),
                VirtualScreenImeText.Candidate("你好", editable = true, focused = false, depth = 3),
                VirtualScreenImeText.Candidate("你好世界", editable = true, focused = true, depth = 4),
            ),
        )
        assertEquals("你好世界", picked)
    }

    @Test
    fun `只可编辑的候选优于只聚焦的`() {
        val picked = VirtualScreenImeText.pick(
            listOf(
                VirtualScreenImeText.Candidate("候选", editable = false, focused = true, depth = 2),
                VirtualScreenImeText.Candidate("正在输入", editable = true, focused = false, depth = 5),
            ),
        )
        assertEquals("正在输入", picked)
    }

    @Test
    fun `都没有可编辑与聚焦时取离根更近的`() {
        val picked = VirtualScreenImeText.pick(
            listOf(
                VirtualScreenImeText.Candidate("深层", editable = false, focused = false, depth = 6),
                VirtualScreenImeText.Candidate("浅层", editable = false, focused = false, depth = 1),
            ),
        )
        assertEquals("浅层", picked)
    }

    @Test
    fun `空白候选被忽略，换行与制表符折叠成空格`() {
        val picked = VirtualScreenImeText.pick(
            listOf(
                VirtualScreenImeText.Candidate("   ", editable = true, focused = true, depth = 1),
                VirtualScreenImeText.Candidate("你好\n世界\t啊", editable = false, focused = false, depth = 2),
            ),
        )
        assertEquals("你好 世界 啊", picked)
    }

    @Test
    fun `没有候选时返回空串而不是猜一个`() {
        assertEquals("", VirtualScreenImeText.pick(emptyList()))
        assertEquals("", VirtualScreenImeText.pick(listOf(VirtualScreenImeText.Candidate("", editable = true, focused = true, depth = 0))))
    }

    @Test
    fun `超长文本截断到上限并加省略号，且不切半个代理对`() {
        val long = "甲".repeat(VirtualScreenImeText.MAX_CHARS + 10)
        val normalized = VirtualScreenImeText.normalize(long)
        assertEquals(VirtualScreenImeText.MAX_CHARS + 1, normalized.length)
        assertEquals("…", normalized.takeLast(1))

        // 120 个 emoji 正好是 240 个 char：截断点落在低代理之后，取满 120 个 char 仍是 60 个完整 emoji。
        val emoji = "🙂".repeat(VirtualScreenImeText.MAX_CHARS)
        assertEquals(VirtualScreenImeText.MAX_CHARS + 1, VirtualScreenImeText.normalize(emoji).length)

        // 前置一个半角字符，让第 MAX_CHARS 个 char 正落在某个 emoji 的高代理上：必须回退一位，不能留半个。
        val prefixed = "甲" + "🙂".repeat(VirtualScreenImeText.MAX_CHARS)
        val truncated = VirtualScreenImeText.normalize(prefixed)
        assertEquals(VirtualScreenImeText.MAX_CHARS, truncated.length)
        assertEquals("…", truncated.takeLast(1))
        val body = truncated.dropLast(1)
        assertEquals(body.count { it.isHighSurrogate() }, body.count { it.isLowSurrogate() })
    }

    @Test
    fun `normalize 折叠空白并 trim`() {
        assertEquals("你好 世界", VirtualScreenImeText.normalize("  你好 \n 世界  "))
        assertEquals("", VirtualScreenImeText.normalize("\n\t"))
    }
}
