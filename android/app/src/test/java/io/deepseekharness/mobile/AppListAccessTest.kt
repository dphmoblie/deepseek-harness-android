package io.deepseekharness.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「读取应用列表」的判定口径。
 *
 * 重点在第一条：AOSP / Pixel / 三星没有 `com.android.permission.GET_INSTALLED_APPS`，
 * 此时 `checkSelfPermission` 恒为拒绝。如果把「不存在」当成「未授予」，界面会永远显示
 * 一个无法完成的授权入口，用户去系统设置里也找不到对应开关。
 */
class AppListAccessTest {

    @Test
    fun `系统没有这条权限时不需要引导`() {
        assertFalse(AppListAccess.requiresGrant(supported = false, granted = false))
    }

    @Test
    fun `系统有这条权限但未授予时需要引导`() {
        assertTrue(AppListAccess.requiresGrant(supported = true, granted = false))
    }

    @Test
    fun `系统有这条权限且已授予时不需要引导`() {
        assertFalse(AppListAccess.requiresGrant(supported = true, granted = true))
    }
}
