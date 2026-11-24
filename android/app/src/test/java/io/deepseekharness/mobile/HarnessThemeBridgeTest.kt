package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
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
        // 控件透明度：40..100 合法，缺省按 100。
        assertTrue(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 40))
        assertTrue(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100))
        assertFalse(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 39))
        assertFalse(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 101))
        // 侧栏透明度：0..100 合法，缺省按 100；越界整体拒绝。
        assertTrue(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100, 0))
        assertTrue(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100, 100))
        assertFalse(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100, -1))
        assertFalse(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100, 101))
        // 右侧面板透明度：与左侧栏分开，0..100 合法，缺省按 40；越界整体拒绝。
        assertTrue(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100, 100, 0))
        assertTrue(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100, 100, 40))
        assertTrue(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100, 100, 100))
        assertFalse(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100, 100, -1))
        assertFalse(HarnessThemeBridge.accepts("dark", accent, background, surface, 86, 100, 100, 101))
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
    fun `背景只接受内容地址，不接受路径与网络地址`() {
        assertTrue(HarnessThemeBridge.acceptsBackgroundUri("content://media/external/video/media/42"))
        assertTrue(HarnessThemeBridge.acceptsBackgroundUri(
            "content://com.android.providers.media.documents/document/video%3A42",
        ))
        // file:// 会把私有路径交给页面，http(s):// 会绕开「背景只来自本机」。
        assertFalse(HarnessThemeBridge.acceptsBackgroundUri("file:///data/user/0/app/files/a.mp4"))
        assertFalse(HarnessThemeBridge.acceptsBackgroundUri("https://example.com/a.mp4"))
        assertFalse(HarnessThemeBridge.acceptsBackgroundUri("content://"))
        assertFalse(HarnessThemeBridge.acceptsBackgroundUri(""))
        assertFalse(HarnessThemeBridge.acceptsBackgroundUri("';alert(1);//"))
        assertFalse(HarnessThemeBridge.acceptsBackgroundUri("content://" + "a".repeat(2100)))
    }

    @Test
    fun `背景类型由 mime 决定，别的一律拒绝`() {
        assertEquals("video", HarnessThemeBridge.kindOf("video/mp4"))
        assertEquals("image", HarnessThemeBridge.kindOf("image/webp"))
        assertEquals(null, HarnessThemeBridge.kindOf("application/pdf"))
        assertEquals(null, HarnessThemeBridge.kindOf(""))
    }

    @Test
    fun `同步背景开关只接受真正的布尔值`() {
        assertTrue(HarnessThemeBridge.acceptsSyncBackground(true))
        assertTrue(HarnessThemeBridge.acceptsSyncBackground(false))
        // "true" / 1 这类取值来自序列化错误或页面注入，不能当成用户意图。
        assertFalse(HarnessThemeBridge.acceptsSyncBackground("true"))
        assertFalse(HarnessThemeBridge.acceptsSyncBackground(1))
        assertFalse(HarnessThemeBridge.acceptsSyncBackground(null))
    }

    @Test
    fun `关掉开关后页面不再有背景`() {
        val uri = "content://media/external/video/media/42"
        assertTrue(HarnessThemeBridge.backgroundEnabled(uri, true))
        // 地址仍在（用户选过的文件留着），但开关关掉就必须不再显示，这是原来的破口。
        assertFalse(HarnessThemeBridge.backgroundEnabled(uri, false))
        assertFalse(HarnessThemeBridge.backgroundEnabled(null, true))
        assertFalse(HarnessThemeBridge.backgroundEnabled(null, false))
    }

    @Test
    fun `只有同步中的背景视频才放开自动播放`() {
        assertTrue(HarnessThemeBridge.allowsBackgroundAutoplay("video", true))
        // 静态图没有播放一说，放开只会把权限白送给页面里的其它媒体。
        assertFalse(HarnessThemeBridge.allowsBackgroundAutoplay("image", true))
        assertFalse(HarnessThemeBridge.allowsBackgroundAutoplay(null, true))
        // 关掉开关后立刻收回，不能留在「上次是视频」的状态里。
        assertFalse(HarnessThemeBridge.allowsBackgroundAutoplay("video", false))
    }

    @Test
    fun `背景路径是固定的同源路径`() {
        assertTrue(HarnessThemeBridge.BACKGROUND_PATH.startsWith("/__shell/"))
        assertFalse(HarnessThemeBridge.BACKGROUND_PATH.contains("://"))
    }

    @Test
    fun `文字深浅按页面底下那块 surface 的亮度选`() {
        // 深色面板（本机实测的 #2a2919、默认深色档的 #18222c）：白字。
        assertEquals(HarnessThemeBridge.DARK_PAGE, HarnessThemeBridge.pagePalette("#2a2919"))
        assertEquals(HarnessThemeBridge.DARK_PAGE, HarnessThemeBridge.pagePalette("#18222c"))
        assertEquals(HarnessThemeBridge.DARK_PAGE, HarnessThemeBridge.pagePalette("#000000"))
        // 浅色面板（deriveSurface 在浅色档给出的近白）：黑字。
        assertEquals(HarnessThemeBridge.LIGHT_PAGE, HarnessThemeBridge.pagePalette("#fbfcfd"))
        assertEquals(HarnessThemeBridge.LIGHT_PAGE, HarnessThemeBridge.pagePalette("#ffffff"))
        // 中间灰：黑字对比度 5.3、白字只有 3.95，所以判给深色文字。
        assertEquals(HarnessThemeBridge.LIGHT_PAGE, HarnessThemeBridge.pagePalette("#808080"))
        assertEquals(HarnessThemeBridge.DARK_PAGE, HarnessThemeBridge.pagePalette("#666666"))
        assertEquals(HarnessThemeBridge.LIGHT_PAGE, HarnessThemeBridge.pagePalette("#999999"))
    }

    @Test
    fun `无法解析的面板色不给结论`() {
        assertEquals(null, HarnessThemeBridge.pagePalette("#fff"))
        assertEquals(null, HarnessThemeBridge.pagePalette("rgb(0,0,0)"))
        assertEquals(null, HarnessThemeBridge.pagePalette(""))
        assertEquals(null, HarnessThemeBridge.relativeLuminance("#12345"))
        assertEquals(null, HarnessThemeBridge.relativeLuminance("red"))
    }

    @Test
    fun `相对亮度按 WCAG 线性化且单调`() {
        assertEquals(0.0, HarnessThemeBridge.relativeLuminance("#000000")!!, 1e-9)
        assertEquals(1.0, HarnessThemeBridge.relativeLuminance("#ffffff")!!, 1e-9)
        val grey = HarnessThemeBridge.relativeLuminance("#808080")!!
        assertTrue(grey > 0.20 && grey < 0.23)
        assertTrue(HarnessThemeBridge.relativeLuminance("#333333")!! < grey)
        assertTrue(HarnessThemeBridge.relativeLuminance("#cccccc")!! > grey)
        // 大小写不改变结论：白名单与解析都按十六进制处理。
        assertEquals(HarnessThemeBridge.relativeLuminance("#aaaaaa"), HarnessThemeBridge.relativeLuminance("#AAAAAA"))
    }

    @Test
    fun `标签色与官方调色板逐字一致`() {
        // android.css 的 var() 缺省值必须与这里一致，否则「桥没升级」时会掉成另一套字色。
        assertEquals("#f9fafb", HarnessThemeBridge.DARK_PAGE.primary)
        assertEquals("#cfd3d6", HarnessThemeBridge.DARK_PAGE.secondary)
        assertEquals("#adb2b8", HarnessThemeBridge.DARK_PAGE.tertiary)
        assertEquals("#0f1115", HarnessThemeBridge.LIGHT_PAGE.primary)
        assertEquals("#61666b", HarnessThemeBridge.LIGHT_PAGE.secondary)
        assertEquals("#81858c", HarnessThemeBridge.LIGHT_PAGE.tertiary)
        assertEquals("dark", HarnessThemeBridge.DARK_PAGE.css)
        assertEquals("light", HarnessThemeBridge.LIGHT_PAGE.css)
    }

    @Test
    fun `强调色只在格式合法时给出 ARGB`() {
        assertEquals(0xFF315FCF.toInt(), HarnessThemeBridge.toArgb("#315FCF"))
        assertEquals(0xFF000000.toInt(), HarnessThemeBridge.toArgb("#000000"))
        assertEquals(null, HarnessThemeBridge.toArgb(null))
        assertEquals(null, HarnessThemeBridge.toArgb("#fff"))
        assertEquals(null, HarnessThemeBridge.toArgb("red"))
        assertEquals(null, HarnessThemeBridge.toArgb("';alert(1);//"))
    }
}
