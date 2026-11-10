package io.deepseekharness.mobile

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppOrientationPreferenceTest {
    @Test
    fun `三个方向模式映射到 Android 常量`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, AppOrientationPreference.requestedOrientation(AppOrientationPreference.MODE_SYSTEM))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppOrientationPreference.requestedOrientation(AppOrientationPreference.MODE_PORTRAIT))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, AppOrientationPreference.requestedOrientation(AppOrientationPreference.MODE_LANDSCAPE))
    }

    @Test
    fun `只接受受控方向模式`() {
        assertTrue(AppOrientationPreference.isValid(AppOrientationPreference.MODE_SYSTEM))
        assertTrue(AppOrientationPreference.isValid(AppOrientationPreference.MODE_PORTRAIT))
        assertTrue(AppOrientationPreference.isValid(AppOrientationPreference.MODE_LANDSCAPE))
        assertFalse(AppOrientationPreference.isValid("auto"))
        assertFalse(AppOrientationPreference.isValid("vertical"))
        assertFalse(AppOrientationPreference.isValid(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法模式不能转换`() {
        AppOrientationPreference.requestedOrientation("vertical")
    }

    @Test
    fun `先切换窗口再保存偏好`() {
        val steps = mutableListOf<String>()
        AppOrientationPreference.change("landscape", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            apply = { steps += "apply:$it" },
            persist = { steps += "persist:$it"; true },
        )
        assertEquals(listOf("apply:${ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}", "persist:landscape"), steps)
    }

    @Test
    fun `系统拒绝方向请求时不保存偏好`() {
        var persisted = false
        try {
            AppOrientationPreference.change("landscape", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                apply = { throw IllegalStateException("not allowed") },
                persist = { persisted = true; true },
            )
            fail("应保留系统拒绝的失败结果")
        } catch (_: IllegalStateException) {
            assertFalse(persisted)
        }
    }

    @Test
    fun `保存失败时恢复窗口原方向`() {
        val applied = mutableListOf<Int>()
        try {
            AppOrientationPreference.change("landscape", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                apply = applied::add,
                persist = { false },
            )
            fail("应报告保存失败")
        } catch (failure: io.deepseekharness.mobile.runtime.RuntimeFailure) {
            assertEquals("APP_ORIENTATION_SAVE_FAILED", failure.code)
            assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT), applied)
        }
    }
}
