package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

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

    @Test
    fun harnessEntryUrlIncludesValidatedApplicationVersion() {
        assertEquals(
            "http://127.0.0.1:3080/?appVersion=0.1.8",
            HarnessPageUrl.withAppVersion("http://127.0.0.1:3080/", "0.1.8"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            HarnessPageUrl.withAppVersion("https://example.invalid/", "0.1.8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessPageUrl.withAppVersion("http://127.0.0.1:3080/", "0.1.8&unsafe=true")
        }
    }

    @Test
    fun authenticatedHarnessEntryRetainsTokenWhileAddingApplicationVersion() {
        val token = "A".repeat(41) + "_-"
        val entry = "http://127.0.0.1:3080/?token=$token"
        val page = URI(HarnessPageUrl.withAppVersion(entry, "0.1.9-mobile.5"))

        assertEquals(entry, HarnessPageUrl.parseEntryUrl(entry)?.toASCIIString())
        assertEquals("http", page.scheme)
        assertEquals("127.0.0.1", page.host)
        assertEquals(3080, page.port)
        assertEquals("/", page.rawPath)
        assertEquals("token=$token&appVersion=0.1.9-mobile.5", page.rawQuery)
        assertNull(page.rawFragment)
    }

    @Test
    fun harnessEntriesRejectUnexpectedOriginsPortsAndNoncanonicalPaths() {
        val tokenQuery = "?token=${"A".repeat(43)}"
        val invalidRoots = listOf(
            "https://127.0.0.1:3080/",
            "http://localhost:3080/",
            "http://127.0.0.1.example.invalid:3080/",
            "http://127.0.0.1:3080@example.invalid/",
            "http://user@127.0.0.1:3080/",
            "http://127.0.0.1:1023/",
            "http://127.0.0.1:65536/",
            "http://127.0.0.1:03080/",
            "http://127.0.0.1:3080",
            "http://127.0.0.1:3080//",
            "http://127.0.0.1:3080/%2f",
            "http://127.0.0.1:3080/../",
            "http://127.0.0.1:3080/\n",
        )
        invalidRoots.flatMap { listOf(it, it + tokenQuery) }.forEach { entry ->
            assertNull(HarnessPageUrl.parseEntryUrl(entry))
            assertThrows(IllegalArgumentException::class.java) {
                HarnessPageUrl.withAppVersion(entry, "0.1.9")
            }
        }
    }

    @Test
    fun harnessEntriesRejectExtraOrEncodedAuthenticationParameters() {
        val token = "A".repeat(43)
        val invalidSuffixes = listOf(
            "?",
            "?other=$token",
            "?token=",
            "?token=${"A".repeat(42)}",
            "?token=${"A".repeat(44)}",
            "?token=$token&token=$token",
            "?token=$token&appVersion=0.1.9",
            "?token=$token;other=value",
            "?token=$token#fragment",
            "?token=$token&",
            "?token=%41${"A".repeat(42)}",
            "?%74oken=$token",
            "?token=$token\r\n",
        )
        invalidSuffixes.forEach { suffix ->
            val entry = "http://127.0.0.1:3080/$suffix"
            assertNull(HarnessPageUrl.parseEntryUrl(entry))
            val failure = assertThrows(IllegalArgumentException::class.java) {
                HarnessPageUrl.withAppVersion(entry, "0.1.9")
            }
            assertTrue(failure.message?.contains(token) != true)
        }
    }
}
