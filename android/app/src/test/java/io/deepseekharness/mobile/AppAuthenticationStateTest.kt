package io.deepseekharness.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.deepseekharness.mobile.runtime.HarnessAccess

class AppAuthenticationStateTest {
    private val access = HarnessAccess("http://127.0.0.1:3080/", HarnessAccess.USERNAME, "A".repeat(43))

    @Before
    fun resetState() {
        AppAuthenticationState.revokeManagement()
        AppAuthenticationState.revokeHarness()
    }

    @Test
    fun harnessLaunchRequiresManagementAuthentication() {
        assertFalse(AppAuthenticationState.authorizeHarnessLaunch(access))
        assertFalse(AppAuthenticationState.isHarnessAuthenticated())
        assertTrue(AppAuthenticationState.harnessAccess() == null)

        AppAuthenticationState.grantManagement()

        assertTrue(AppAuthenticationState.authorizeHarnessLaunch(access))
        assertTrue(AppAuthenticationState.isHarnessAuthenticated())
        assertTrue(AppAuthenticationState.harnessAccess() == access)
    }

    @Test
    fun managementAndHarnessAuthorizationAreRevokedIndependently() {
        AppAuthenticationState.grantManagement()
        assertTrue(AppAuthenticationState.authorizeHarnessLaunch(access))

        AppAuthenticationState.revokeManagement()

        assertFalse(AppAuthenticationState.isManagementAuthenticated())
        assertTrue(AppAuthenticationState.isHarnessAuthenticated())

        AppAuthenticationState.revokeHarness()
        assertFalse(AppAuthenticationState.isHarnessAuthenticated())
        assertTrue(AppAuthenticationState.harnessAccess() == null)
    }
}
