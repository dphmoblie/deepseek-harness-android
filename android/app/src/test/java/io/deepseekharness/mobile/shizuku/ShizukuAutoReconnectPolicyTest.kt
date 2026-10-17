package io.deepseekharness.mobile.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuAutoReconnectPolicyTest {
    private fun state(
        installed: Boolean = true,
        running: Boolean = true,
        permission: String = "granted",
        connected: Boolean = false,
    ) = ShizukuState(installed, running, permission, connected, "13", "13.6.0")

    @Test
    fun onlyAuthorizedRunningDisconnectedStateIsEligible() {
        assertTrue(ShizukuAutoReconnectPolicy.shouldAttempt(state()))
        assertFalse(ShizukuAutoReconnectPolicy.shouldAttempt(state(installed = false)))
        assertFalse(ShizukuAutoReconnectPolicy.shouldAttempt(state(running = false)))
        assertFalse(ShizukuAutoReconnectPolicy.shouldAttempt(state(permission = "undetermined")))
        assertFalse(ShizukuAutoReconnectPolicy.shouldAttempt(state(connected = true)))
    }
}
