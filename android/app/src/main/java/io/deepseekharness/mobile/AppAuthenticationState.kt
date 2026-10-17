package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.HarnessAccess
import java.util.concurrent.atomic.AtomicBoolean

internal object AppAuthenticationState {
    private val harnessAuthenticated = AtomicBoolean(false)
    private var harnessAccess: HarnessAccess? = null
    private var harnessOwner: Any? = null

    @Synchronized
    fun authorizeHarnessLaunch(access: HarnessAccess) {
        harnessOwner = null
        harnessAccess = access
        harnessAuthenticated.set(true)
    }

    @Synchronized
    fun revokeHarness() {
        harnessAuthenticated.set(false)
        harnessAccess = null
        harnessOwner = null
    }

    @Synchronized
    fun claimHarnessAccess(owner: Any, access: HarnessAccess): Boolean {
        if (!harnessAuthenticated.get() || harnessAccess != access) return false
        harnessOwner = owner
        return true
    }

    @Synchronized
    fun ownsHarnessAccess(owner: Any): Boolean = harnessAuthenticated.get() && harnessOwner === owner

    /** 新窗口或新运行周期接管后，旧 Activity 不得再撤销它的授权。 */
    @Synchronized
    fun revokeHarness(owner: Any): Boolean {
        if (!ownsHarnessAccess(owner)) return false
        revokeHarness()
        return true
    }

    fun isHarnessAuthenticated(): Boolean = harnessAuthenticated.get()

    @Synchronized
    fun harnessAccess(): HarnessAccess? = if (harnessAuthenticated.get()) harnessAccess else null
}
