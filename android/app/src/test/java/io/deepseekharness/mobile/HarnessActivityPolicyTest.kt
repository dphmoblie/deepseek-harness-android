package io.deepseekharness.mobile

import android.net.Uri
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

    @Test
    fun backgroundResourceIsLimitedToTheHarnessOriginAndSubresourceMethods() {
        val origin = HarnessActivity.Origin(
            "http",
            "127.0.0.1",
            18080,
            "http://127.0.0.1:18080/",
        )
        val path = "http://127.0.0.1:18080/__shell/background"
        assertTrue(isHarnessBackgroundRequest(origin, Uri.parse(path), "GET", false))
        assertTrue(isHarnessBackgroundRequest(origin, Uri.parse(path), "HEAD", false))
        assertFalse(isHarnessBackgroundRequest(origin, Uri.parse(path), "POST", false))
        assertFalse(isHarnessBackgroundRequest(origin, Uri.parse(path), "GET", true))
        assertFalse(isHarnessBackgroundRequest(origin, Uri.parse("https://dsh-market.com/__shell/background"), "GET", false))
    }
}
