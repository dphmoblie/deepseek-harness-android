package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.HarnessAccess
import java.util.concurrent.atomic.AtomicBoolean

internal object AppAuthenticationState {
    private val managementAuthenticated = AtomicBoolean(false)
    private val harnessAuthenticated = AtomicBoolean(false)
    private var harnessAccess: HarnessAccess? = null

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
    fun authorizeHarnessLaunch(access: HarnessAccess): Boolean {
        if (!managementAuthenticated.get()) return false
        harnessAccess = access
        harnessAuthenticated.set(true)
        return true
    }

    @Synchronized
    fun revokeHarness() {
        harnessAuthenticated.set(false)
        harnessAccess = null
    }

    fun isHarnessAuthenticated(): Boolean = harnessAuthenticated.get()

    @Synchronized
    fun harnessAccess(): HarnessAccess? = if (harnessAuthenticated.get()) harnessAccess else null
}
