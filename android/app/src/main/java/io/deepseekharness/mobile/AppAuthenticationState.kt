package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.HarnessAccess
import java.util.concurrent.atomic.AtomicBoolean

internal object AppAuthenticationState {
    /**
     * A single publication of the Harness credentials.  The access value may be reused while
     * one Harness process is running, so rollback must identify the publication itself rather
     * than compare only [HarnessAccess] (otherwise an old request can revoke a newer one).
     */
    class HarnessAuthorization internal constructor()

    private val harnessAuthenticated = AtomicBoolean(false)
    private var harnessAccess: HarnessAccess? = null
    private var harnessOwner: Any? = null
    private var harnessAuthorization: HarnessAuthorization? = null

    @Synchronized
    fun authorizeHarnessLaunch(access: HarnessAccess): HarnessAuthorization {
        val authorization = HarnessAuthorization()
        harnessOwner = null
        harnessAccess = access
        harnessAuthorization = authorization
        harnessAuthenticated.set(true)
        return authorization
    }

    @Synchronized
    fun revokeHarness() {
        harnessAuthenticated.set(false)
        harnessAccess = null
        harnessOwner = null
        harnessAuthorization = null
    }

    /** 仅撤销仍属于本次发布的授权，避免失败的旧请求清掉新请求的凭据。 */
    @Synchronized
    fun revokeHarness(authorization: HarnessAuthorization): Boolean {
        if (!harnessAuthenticated.get() || harnessAuthorization !== authorization) return false
        revokeHarness()
        return true
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
