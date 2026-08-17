package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessPageSessionTest {
    @Test
    fun acceptedCookieAllowsExactlyOnePageLoad() {
        val gate = HarnessPageLoadGate()

        assertEquals(CookieLoadDecision.LOAD, gate.onCookieStored(true))
        assertEquals(CookieLoadDecision.IGNORE, gate.onCookieStored(true))
    }

    @Test
    fun rejectedAndLateCookieCallbacksNeverLoadThePage() {
        val rejected = HarnessPageLoadGate()
        assertEquals(CookieLoadDecision.REJECT, rejected.onCookieStored(false))
        assertEquals(CookieLoadDecision.IGNORE, rejected.onCookieStored(true))

        val destroyed = HarnessPageLoadGate()
        destroyed.cancel()
        assertEquals(CookieLoadDecision.IGNORE, destroyed.onCookieStored(true))
    }

    @Test
    fun cookieIsHostOnlyHttpOnlyAndRejectsHeaderInjection() {
        val cookie = HarnessSessionCookie.authenticated("A".repeat(43))

        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))
        assertTrue(!cookie.contains("Domain="))
        assertThrows(IllegalArgumentException::class.java) {
            HarnessSessionCookie.authenticated("invalid; Domain=example.invalid")
        }
    }

    @Test
    fun cookieOriginOnlyAcceptsHarnessPortRange() {
        assertEquals("http://127.0.0.1:3080", HarnessSessionCookie.origin(3080))
        assertThrows(IllegalArgumentException::class.java) { HarnessSessionCookie.origin(80) }
        assertThrows(IllegalArgumentException::class.java) { HarnessSessionCookie.origin(65536) }
    }
}
