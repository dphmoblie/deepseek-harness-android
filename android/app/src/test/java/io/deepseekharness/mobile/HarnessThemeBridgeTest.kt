package io.deepseekharness.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步主题与背景的白名单校验。
 *
 * 这些判断单独抽成纯函数就是为了能在这里驱动：拼进 `evaluateJavascript` 的内容、
 * 以及按块过桥的 base64，都只经过它们一道关，不能只在设备上碰运气。
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
    fun `背景分块只接受受控的 base64`() {
        assertTrue(HarnessThemeBridge.acceptsUploadChunk("/9j/4AAQSkZJRgABAQAAAQABAAD/"))
        assertTrue(HarnessThemeBridge.acceptsUploadChunk("AAAA"))
        assertFalse(HarnessThemeBridge.acceptsUploadChunk(""))
        assertFalse(HarnessThemeBridge.acceptsUploadChunk("url(https://example.com/a.mp4)"))
        assertFalse(HarnessThemeBridge.acceptsUploadChunk("AAAA BBBB"))
        assertFalse(HarnessThemeBridge.acceptsUploadChunk("';alert(1);//"))
        assertFalse(HarnessThemeBridge.acceptsUploadChunk("A".repeat(512 * 1024 + 4)))
    }

    @Test
    fun `背景格式只认 MP4 与 JPEG`() {
        assertTrue(HarnessThemeBridge.acceptsUploadMime("video/mp4"))
        assertTrue(HarnessThemeBridge.acceptsUploadMime("image/jpeg"))
        assertFalse(HarnessThemeBridge.acceptsUploadMime("video/webm"))
        assertFalse(HarnessThemeBridge.acceptsUploadMime("text/html"))
        assertFalse(HarnessThemeBridge.acceptsUploadMime(""))
    }

    @Test
    fun `注入样式只作用于已同步的页面，且不能闭合 style 标签`() {
        val rules = HarnessThemeBridge.STYLE_RULES
        assertTrue(rules.contains("html[data-dsh-theme-source='shell'] body::before"))
        assertTrue(rules.contains("--dsh-theme-background-image"))
        assertTrue(rules.contains("video[data-dsh-shell-background]"))
        assertTrue(rules.contains("accent-color"))
        // 这段内容会被塞进 <style>：出现闭合标签就等于给了注入一个出口。
        assertFalse(rules.contains("</style", ignoreCase = true))
        assertFalse(rules.contains("<script", ignoreCase = true))
    }

    @Test
    fun `背景路径是固定的同源路径`() {
        assertTrue(HarnessThemeBridge.BACKGROUND_PATH.startsWith("/__shell/"))
        assertFalse(HarnessThemeBridge.BACKGROUND_PATH.contains("://"))
    }
}
