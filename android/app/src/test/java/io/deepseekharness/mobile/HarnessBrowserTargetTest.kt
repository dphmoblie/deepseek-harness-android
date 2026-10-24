package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 外部浏览器接管只交给白名单里的浏览器：那段 URL 的片段里有一次令牌，
 * 交给未知应用等于把令牌交出去。这几条钉住「按偏好选」「不认识就不选」。
 */
class HarnessBrowserTargetTest {
    @Test
    fun `按偏好顺序选择已安装的受信任浏览器`() {
        assertEquals(
            "com.android.chrome",
            HarnessBrowserTarget.pick(listOf("com.miui.browser", "com.android.chrome")),
        )
        assertEquals("com.miui.browser", HarnessBrowserTarget.pick(listOf("com.miui.browser")))
        assertEquals(
            "org.mozilla.firefox",
            HarnessBrowserTarget.pick(listOf("com.android.browser", "org.mozilla.firefox")),
        )
    }

    @Test
    fun `没有受信任浏览器时返回空，不降级给任意应用`() {
        assertNull(HarnessBrowserTarget.pick(emptyList()))
        assertNull(HarnessBrowserTarget.pick(listOf("com.example.unknown", "org.chromium.webview_shell")))
    }

    @Test
    fun `白名单不含重复项，也不含调试壳`() {
        val packages = HarnessBrowserTarget.preferredPackages()
        assertEquals(packages.size, packages.toSet().size)
        assertFalse(packages.contains("org.chromium.webview_shell"))
        assertFalse(packages.contains("com.example.unknown"))
    }
}
