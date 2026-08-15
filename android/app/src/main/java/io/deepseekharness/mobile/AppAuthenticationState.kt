package io.deepseekharness.mobile

import java.util.concurrent.atomic.AtomicBoolean

internal object AppAuthenticationState {
    private val managementAuthenticated = AtomicBoolean(false)
    private val harnessAuthenticated = AtomicBoolean(false)

    @Synchronized
    fun grantManagement() {
        managementAuthenticated.set(true)
    }

    @Synchronized
    fun revokeManagement() {
        managementAuthenticated.set(false)
    }

    fun isManagementAuthenticated(): Boolean = managementAuthenticated.get()

    @Synchronized
    fun authorizeHarnessLaunch(): Boolean {
        if (!managementAuthenticated.get()) return false
        harnessAuthenticated.set(true)
        return true
    }

    @Synchronized
    fun revokeHarness() {
        harnessAuthenticated.set(false)
    }

    fun isHarnessAuthenticated(): Boolean = harnessAuthenticated.get()
}
