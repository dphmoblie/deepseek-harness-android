package io.deepseekharness.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppAuthenticationStateTest {
    @Before
    fun resetState() {
        AppAuthenticationState.revokeManagement()
        AppAuthenticationState.revokeHarness()
    }

    @Test
    fun harnessLaunchRequiresManagementAuthentication() {
        assertFalse(AppAuthenticationState.authorizeHarnessLaunch())
        assertFalse(AppAuthenticationState.isHarnessAuthenticated())

        AppAuthenticationState.grantManagement()

        assertTrue(AppAuthenticationState.authorizeHarnessLaunch())
        assertTrue(AppAuthenticationState.isHarnessAuthenticated())
    }

    @Test
    fun managementAndHarnessAuthorizationAreRevokedIndependently() {
        AppAuthenticationState.grantManagement()
        assertTrue(AppAuthenticationState.authorizeHarnessLaunch())

        AppAuthenticationState.revokeManagement()

        assertFalse(AppAuthenticationState.isManagementAuthenticated())
        assertTrue(AppAuthenticationState.isHarnessAuthenticated())

        AppAuthenticationState.revokeHarness()
        assertFalse(AppAuthenticationState.isHarnessAuthenticated())
    }
}
