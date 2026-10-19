package io.deepseekharness.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessActivityPolicyTest {
    @Test
    fun allowsOnlyTheDeepSeekAuthorizationEndpoint() {
        assertTrue(isDeepSeekAuthorizationUrl("https://platform.deepseek.com/dsh/authorize?state=state"))
        assertTrue(isDeepSeekAuthorizationUrl("https://PLATFORM.DEEPSEEK.COM:443/dsh/authorize?code_challenge=challenge"))
    }

    @Test
    fun rejectsLookalikeOrCredentialBearingUrls() {
        val rejected = listOf(
            "http://platform.deepseek.com/dsh/authorize?state=state",
            "https://evil.example/dsh/authorize?state=state",
            "https://platform.deepseek.com:8443/dsh/authorize?state=state",
            "https://platform.deepseek.com/dsh/authorize",
            "https://platform.deepseek.com/dsh/authorize/?state=state",
            "https://platform.deepseek.com/dsh/authorize/extra?state=state",
            "https://user:password@platform.deepseek.com/dsh/authorize?state=state",
            "https://platform.deepseek.com/dsh/authorize?state=state#fragment",
            "https://platform.deepseek.com/dsh/authorize?state=state\n",
            "https://platform.deepseek.com/dsh/authorize?state=\\state",
            "https://platform.deepseek.com/dsh/authorize?state=" + "x".repeat(8192),
        )
        rejected.forEach { value ->
            assertFalse(value, isDeepSeekAuthorizationUrl(value))
        }
    }

    @Test
    fun onlyMainFrameGetNavigationMayOpenTheBrowser() {
        val url = "https://platform.deepseek.com/dsh/authorize?state=state"
        assertTrue(isDeepSeekAuthorizationNavigation(url, "GET", true))
        assertFalse(isDeepSeekAuthorizationNavigation(url, "GET", false))
        assertFalse(isDeepSeekAuthorizationNavigation(url, "POST", true))
        assertFalse(isDeepSeekAuthorizationNavigation("about:blank", "GET", true))
    }
}
