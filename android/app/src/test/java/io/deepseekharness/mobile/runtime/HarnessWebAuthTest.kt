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

    @Test
    fun browserLaunchUrlCarriesTheBrowserTokenInTheQueryAndNoFragment() {
        val browserToken = "B".repeat(43)
        val launch = HarnessWebAuth.browserLaunchUrl(entry, browserToken)
        // 必须与 dsh 自己的 ?token= 区分：交接用 dshMobileToken，重定向时才由预载补上 dsh 的令牌。
        assertEquals("http://127.0.0.1:3080/?dshMobileToken=$browserToken", launch?.toASCIIString())
        assertEquals(null, launch?.rawFragment)
        // 非法令牌一律不构造 URL，避免把半截凭据交出去。
        assertEquals(null, HarnessWebAuth.browserLaunchUrl(entry, "too-short"))
    }

    @Test
    fun browserLaunchUrlCarriesTheSessionOnlyInTheFragment() {
        val browserToken = "B".repeat(43)
        val session = "session-49c96fce-f065-4c1d-b1af-6c339631657a"
        val launch = HarnessWebAuth.browserLaunchUrl(entry, browserToken, session)
        // 查询串会随 dsh 的 ?token= 登录跳转 303 丢失，会话键必须放片段。
        assertEquals("http://127.0.0.1:3080/?dshMobileToken=$browserToken#dshMobileSession=$session",
            launch?.toASCIIString())
        // 非法会话键按「没有会话」处理，令牌交接仍然有效。
        listOf("", "session-", "not-a-session", "session-ok\r\nSet-Cookie: x=y",
            "session-" + "a".repeat(191), "session-ok&next=evil").forEach { invalid ->
            assertEquals("非法会话键：$invalid", "http://127.0.0.1:3080/?dshMobileToken=$browserToken",
                HarnessWebAuth.browserLaunchUrl(entry, browserToken, invalid)?.toASCIIString())
        }
    }

    @Test
    fun sessionCookieIsParsedOnlyFromTheExactNameAndStrictFormat() {
        val session = "session-df626b7d-64c9-4c09-a467-b0fde3f1f86e"
        assertEquals(session, HarnessWebAuth.sessionFromCookieHeader(
            "dsh_mobile_token=${"C".repeat(43)}; dsh_mobile_last_session=$session; other=1"))
        assertEquals(session, HarnessWebAuth.sessionFromCookieHeader("dsh_mobile_last_session=$session"))
        listOf(
            null,
            "",
            "dsh_mobile_last_session=",
            "dsh_mobile_last_session=not-a-session",
            "dsh_mobile_last_session=\"$session\"",
            "dsh_mobile_last_session=$session%20",
            "x_dsh_mobile_last_session=$session",
            "dsh_mobile_last_session=$session\r\nX-Wrong: value",
        ).forEach { header ->
            assertNull("非法 Cookie 头：$header", HarnessWebAuth.sessionFromCookieHeader(header))
        }
    }

    private fun HarnessWebAuthCapture.feed(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        append(bytes, bytes.size)
    }
}
