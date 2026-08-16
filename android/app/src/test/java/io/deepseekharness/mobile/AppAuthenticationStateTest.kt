package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.HarnessAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppAuthenticationStateTest {
    private val access = HarnessAccess("http://127.0.0.1:3080/", HarnessAccess.USERNAME, "A".repeat(43))

    @Before
    fun resetState() {
        AppAuthenticationState.revokeHarness()
    }

    @Test
    fun harnessLaunchIsAuthorizedWithoutDeviceAuthentication() {
        AppAuthenticationState.authorizeHarnessLaunch(access)
        assertTrue(AppAuthenticationState.isHarnessAuthenticated())
        assertTrue(AppAuthenticationState.harnessAccess() == access)
    }

    @Test
    fun harnessAuthorizationIsRevokedWithItsCredentials() {
        AppAuthenticationState.authorizeHarnessLaunch(access)
        AppAuthenticationState.revokeHarness()
        assertFalse(AppAuthenticationState.isHarnessAuthenticated())
        assertTrue(AppAuthenticationState.harnessAccess() == null)
    }
}
