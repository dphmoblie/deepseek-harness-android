package io.deepseekharness.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步主题的白名单校验。
 *
 * 这段判断单独抽成纯函数就是为了能在这里驱动：拼进 `evaluateJavascript` 的内容
 * 只经过它一道关，不能只在设备上碰运气。
 */
class HarnessThemeBridgeTest {
    private val accent = "#315fcf"
    private val background = "#101820"
    private val surface = "#18222c"

    @Test
    fun `接受受控字段`() {
        assertTrue(HarnessThemeBridge.accepts("dark", accent, background, surface, 86))
        assertTrue(HarnessThemeBridge.accepts("light", "#FFFFFF", "#ffffff", "#000000", 0))
        assertTrue(HarnessThemeBridge.accepts("system", accent, background, surface, 100))
    }

    @Test
    fun `拒绝未受控的模式`() {
        assertFalse(HarnessThemeBridge.accepts("blue", accent, background, surface, 86))
        assertFalse(HarnessThemeBridge.accepts("", accent, background, surface, 86))
    }

    @Test
    fun `拒绝非法颜色与注入内容`() {
        assertFalse(HarnessThemeBridge.accepts("dark", "#fff", background, surface, 86))
        assertFalse(HarnessThemeBridge.accepts("dark", "red", background, surface, 86))
        assertFalse(HarnessThemeBridge.accepts("dark", "url(https://example.com/a.png)", background, surface, 86))
        assertFalse(HarnessThemeBridge.accepts("dark", accent, "';alert(1);//", surface, 86))
        assertFalse(HarnessThemeBridge.accepts("dark", accent, background, "rgb(0,0,0)", 86))
    }

    @Test
    fun `透明度只接受 0 到 100`() {
        assertFalse(HarnessThemeBridge.accepts("dark", accent, background, surface, -1))
        assertFalse(HarnessThemeBridge.accepts("dark", accent, background, surface, 101))
    }

    @Test
    fun `背景只接受受控的 base64`() {
        assertTrue(HarnessThemeBridge.acceptsBackground("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA=="))
        assertTrue(HarnessThemeBridge.acceptsBackground("AAAA"))
        // 这些都会被拼进 url("data:image/jpeg;base64,…")，字符集必须逐字受控。
        assertFalse(HarnessThemeBridge.acceptsBackground(""))
        assertFalse(HarnessThemeBridge.acceptsBackground("url(https://example.com/a.png)"))
        assertFalse(HarnessThemeBridge.acceptsBackground("data:image/png;base64,AAAA"))
        assertFalse(HarnessThemeBridge.acceptsBackground("AAAA BBBB"))
        assertFalse(HarnessThemeBridge.acceptsBackground("';alert(1);//"))
        assertFalse(HarnessThemeBridge.acceptsBackground("A".repeat(2 * 1024 * 1024 + 4)))
    }
}
