package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AutomationWindowDigest] 的用例：结构敏感、内容无感、形状固定。 */
class AutomationWindowDigestTest {
    private val structure = listOf(
        AutomationWindowDigest.entry("android.widget.FrameLayout", null, 0, 0, 1080, 2400),
        AutomationWindowDigest.entry("android.widget.Button", "com.example.app:id/ok", 40, 2000, 1040, 2120),
    )

    @Test
    fun `同一个界面每次算出来都一样`() {
        assertEquals(
            AutomationWindowDigest.of("com.example.app", 7, structure),
            AutomationWindowDigest.of("com.example.app", 7, structure),
        )
    }

    @Test
    fun `指纹是十六位小写十六进制`() {
        val digest = AutomationWindowDigest.of("com.example.app", 7, structure)
        assertEquals(16, digest.length)
        assertTrue("指纹只应有十六进制字符：$digest", digest.all { it in "0123456789abcdef" })
    }

    @Test
    fun `包名 窗口编号 结构与顺序任一变化都换一个指纹`() {
        val base = AutomationWindowDigest.of("com.example.app", 7, structure)
        assertNotEquals(base, AutomationWindowDigest.of("com.example.other", 7, structure))
        assertNotEquals(base, AutomationWindowDigest.of("com.example.app", 8, structure))
        assertNotEquals(base, AutomationWindowDigest.of("com.example.app", 7, structure.dropLast(1)))
        assertNotEquals(base, AutomationWindowDigest.of("com.example.app", 7, structure.reversed()))
        assertNotEquals(
            base,
            AutomationWindowDigest.of("com.example.app", 7, listOf(structure[0]) + AutomationWindowDigest.entry("android.widget.Button", "com.example.app:id/ok", 40, 1999, 1040, 2120)),
        )
    }

    @Test
    fun `结构项里没有文本的位置`() {
        // 只有类名 / viewId / 边界：屏幕上的文字进不来，指纹就不能反推出界面内容。
        assertEquals(
            "android.widget.Button|com.example.app:id/ok|40,2000,1040,2120",
            AutomationWindowDigest.entry("android.widget.Button", "com.example.app:id/ok", 40, 2000, 1040, 2120),
        )
        assertEquals("||0,0,0,0", AutomationWindowDigest.entry(null, null, 0, 0, 0, 0))
    }
}
