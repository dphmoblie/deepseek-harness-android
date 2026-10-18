package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.HarnessAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun harnessAuthorizationIsRevokedWithItsLease() {
        val authorization = AppAuthenticationState.authorizeHarnessLaunch(access)
        assertTrue(AppAuthenticationState.revokeHarness(authorization))
        assertFalse(AppAuthenticationState.isHarnessAuthenticated())
        assertTrue(AppAuthenticationState.harnessAccess() == null)
    }

    @Test
    fun stoppedRuntimeCannotClaimOrRevokeTheRestartedRuntime() {
        val oldOwner = Any()
        val nextOwner = Any()
        val nextAccess = access.copy(password = "B".repeat(43))
        AppAuthenticationState.authorizeHarnessLaunch(access)
        assertTrue(AppAuthenticationState.claimHarnessAccess(oldOwner, access))

        AppAuthenticationState.authorizeHarnessLaunch(nextAccess)
        assertFalse(AppAuthenticationState.ownsHarnessAccess(oldOwner))
        assertFalse(AppAuthenticationState.claimHarnessAccess(oldOwner, access))
        assertFalse(AppAuthenticationState.revokeHarness(oldOwner))
        assertTrue(AppAuthenticationState.claimHarnessAccess(nextOwner, nextAccess))
        assertFalse(AppAuthenticationState.revokeHarness(oldOwner))
        assertEquals(nextAccess, AppAuthenticationState.harnessAccess())
        assertTrue(AppAuthenticationState.revokeHarness(nextOwner))
    }

    @Test
    fun rotationAndRendererReplacementCanTakeOwnershipWithoutOldDestroyRevokingIt() {
        val oldOwner = Any()
        val replacement = Any()
        AppAuthenticationState.authorizeHarnessLaunch(access)
        assertTrue(AppAuthenticationState.claimHarnessAccess(oldOwner, access))
        assertTrue(AppAuthenticationState.claimHarnessAccess(replacement, access))
        assertFalse(AppAuthenticationState.revokeHarness(oldOwner))
        assertTrue(AppAuthenticationState.ownsHarnessAccess(replacement))
        assertEquals(access, AppAuthenticationState.harnessAccess())
    }

    @Test
    fun reauthorizingTheSameRuntimeInvalidatesThePreviousWindowClaim() {
        val oldOwner = Any()
        AppAuthenticationState.authorizeHarnessLaunch(access)
        assertTrue(AppAuthenticationState.claimHarnessAccess(oldOwner, access))
        AppAuthenticationState.authorizeHarnessLaunch(access)
        assertFalse(AppAuthenticationState.revokeHarness(oldOwner))
        assertEquals(access, AppAuthenticationState.harnessAccess())
        assertTrue(AppAuthenticationState.claimHarnessAccess(oldOwner, access))
    }

    @Test
    fun staleAuthorizationLeaseCannotRevokeAReauthorizationWithTheSameAccess() {
        val oldAuthorization = AppAuthenticationState.authorizeHarnessLaunch(access)
        val newAuthorization = AppAuthenticationState.authorizeHarnessLaunch(access)

        assertFalse(AppAuthenticationState.revokeHarness(oldAuthorization))
        assertTrue(AppAuthenticationState.isHarnessAuthenticated())
        assertEquals(access, AppAuthenticationState.harnessAccess())
        assertTrue(AppAuthenticationState.revokeHarness(newAuthorization))
        assertFalse(AppAuthenticationState.isHarnessAuthenticated())
    }
}
