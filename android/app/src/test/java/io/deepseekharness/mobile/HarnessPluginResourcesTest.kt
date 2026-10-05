package io.deepseekharness.mobile

import org.junit.Assert.*
import org.junit.Test

class HarnessPluginResourcesTest {
    @Test fun `允许市场目录统计与缩略图读取`() {
        for (path in listOf("/manifest/skins.json", "/manifest/pets.json", "/manifest/plugins.json", "/api/stats", "/assets/skin.webp")) {
            assertTrue(HarnessPluginResources.allows("https://dsh-market.com$path", "GET", false))
        }
        assertTrue(HarnessPluginResources.allows("https://dsh-market.com:443/manifest/skins.json", "OPTIONS", false))
    }

    @Test fun `拒绝导航写请求伪装来源与本地资源`() {
        val url = "https://dsh-market.com/manifest/skins.json"
        assertFalse(HarnessPluginResources.allows(url, "GET", true))
        for (method in listOf("POST", "PUT", "DELETE", "PATCH")) {
            assertFalse(HarnessPluginResources.allows(url, method, false))
        }
        for (invalid in listOf(
            "http://dsh-market.com/manifest/skins.json", "https://dsh-market.com.evil.example/a",
            "https://dsh-market.com@evil.example/a", "https://user@dsh-market.com/a",
            "https://dsh-market.com:8443/a", "https://sub.dsh-market.com/a",
            "file:///etc/passwd", "content://example/a", "https://dsh-market.com\\@evil.example/a",
            "https://dsh-market.com/\n", "https://dsh-market.com/" + "a".repeat(8192),
        )) assertFalse(HarnessPluginResources.allows(invalid, "GET", false))
    }
}
