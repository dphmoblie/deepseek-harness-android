package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.HarnessAccess
import java.util.concurrent.atomic.AtomicBoolean

internal object AppAuthenticationState {
    private val harnessAuthenticated = AtomicBoolean(false)
    private var harnessAccess: HarnessAccess? = null

    @Synchronized
    fun authorizeHarnessLaunch(access: HarnessAccess) {
        harnessAccess = access
        harnessAuthenticated.set(true)
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
