package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题模式 → 「是否深色外观」的判定表。
 *
 * 这条判定错一次，用户看到的就是状态栏与界面不匹配（浅色界面配深色状态栏），
 * 所以三种合法模式 × 系统两种取值共六种组合全部钉住，另加非法值的回退行为。
 */
class AppThemePreferenceTest {
    @Test
    fun `显式选择不跟随系统`() {
        assertTrue(AppThemePreference.isDark(AppThemePreference.MODE_DARK, systemNight = false))
        assertTrue(AppThemePreference.isDark(AppThemePreference.MODE_DARK, systemNight = true))
        assertFalse(AppThemePreference.isDark(AppThemePreference.MODE_LIGHT, systemNight = false))
        assertFalse(AppThemePreference.isDark(AppThemePreference.MODE_LIGHT, systemNight = true))
    }

    @Test
    fun `跟随系统时由系统决定`() {
        assertTrue(AppThemePreference.isDark(AppThemePreference.MODE_SYSTEM, systemNight = true))
        assertFalse(AppThemePreference.isDark(AppThemePreference.MODE_SYSTEM, systemNight = false))
    }

    @Test
    fun `非法模式按跟随系统处理而不是猜一个`() {
        // 与 current() 的回退保持一致：读到脏值时宁可跟随系统，也不要固定成某一种外观。
        assertTrue(AppThemePreference.isDark("darkk", systemNight = true))
        assertFalse(AppThemePreference.isDark("", systemNight = false))
        assertTrue(AppThemePreference.isDark("DARK", systemNight = true))
    }

    @Test
    fun `深浅两套底色与 Web 侧的 --bg 取值一致`() {
        // 取值写错会在状态栏与页面之间露出一条色带，因此把数值本身钉住。
        assertEquals(0xFF111315.toInt(), AppThemePreference.DARK_BAR_COLOR)
        assertEquals(0xFFF3F5F8.toInt(), AppThemePreference.LIGHT_BAR_COLOR)
    }
}
