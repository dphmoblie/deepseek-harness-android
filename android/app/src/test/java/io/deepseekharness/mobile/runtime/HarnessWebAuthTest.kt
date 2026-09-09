package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HarnessWebAuthTest {
    private val token = "A".repeat(43)
    private val entry = "http://127.0.0.1:3080/?token=$token"

    @Test
    fun acceptsOnlyTheExpectedCanonicalLoopbackEntry() {
        assertEquals(entry, HarnessWebAuth.parseLaunchUrl(entry, 3080)?.toASCIIString())
        listOf(
            entry.replace("http:", "https:"),
            entry.replace("127.0.0.1", "localhost"),
            entry.replace("127.0.0.1", "127.0.0.1.example.invalid"),
            entry.replace("127.0.0.1", "user@127.0.0.1"),
            entry.replace(":3080", ":3081"),
            entry.replace(":3080", ":03080"),
            entry.replace("/?", "/settings?"),
            entry.replace("token=", "%74oken="),
            entry.replace(token, "A".repeat(42)),
            entry.replace(token, "A".repeat(44)),
            "$entry&token=$token",
            "$entry&appVersion=1",
            "$entry#fragment",
            "$entry\r\n",
            " $entry",
        ).forEach { assertNull(HarnessWebAuth.parseLaunchUrl(it, 3080)) }
        assertNull(HarnessWebAuth.parseLaunchUrl(entry.replace(":3080", ":1023")))
        assertNull(HarnessWebAuth.parseLaunchUrl(entry.replace(":3080", ":65536")))
    }

    @Test
    fun waitsForCompleteLineBeforeAcceptingToken() {
        val capture = HarnessWebAuthCapture(3080)
        capture.feed("dsh web: $entry")
        assertNull(capture.url())
        capture.feed("&unexpected=true\n")
        assertNull(capture.url())
        "dsh web: $entry\r\n".forEach { capture.feed(it.toString()) }
        assertEquals(entry, capture.url())
    }

    @Test
    fun retainsEntryAcrossDiagnosticFloodAndClearsOnClose() {
        val capture = HarnessWebAuthCapture(3080)
        capture.feed("x".repeat(20_000) + "dsh web: $entry\n")
        assertNull(capture.url())
        capture.feed("embedded dsh web: $entry\n")
        assertNull(capture.url())
        capture.feed("dsh web: $entry\n")
        capture.feed("diagnostic\n".repeat(4_000))
        assertEquals(entry, capture.url())
        capture.clear()
        assertNull(capture.url())
    }

    @Test
    fun credentialsAreAbsentFromAccessDiagnostics() {
        val password = "B".repeat(43)
        val diagnostic = HarnessAccess(entry, HarnessAccess.USERNAME, password).toString()
        assertFalse(diagnostic.contains(token))
        assertFalse(diagnostic.contains(password))
        assertFalse(diagnostic.contains("?token="))
    }

    private fun HarnessWebAuthCapture.feed(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        append(bytes, bytes.size)
    }
}
