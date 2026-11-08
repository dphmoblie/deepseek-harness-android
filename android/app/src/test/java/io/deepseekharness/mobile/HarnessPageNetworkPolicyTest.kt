package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.PageNetworkAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面网络访问的三档放行规则。
 *
 * 这些用例是**安全边界**的回归防线：默认档一旦漏成放行、或某个档位顺手放开了
 * `file:` / `content:`，都不会有任何界面报错，只会静默多给页面一条出口。
 */
class HarnessPageNetworkPolicyTest {
    private fun allows(mode: PageNetworkAccess, url: String, mainFrame: Boolean = false): Boolean =
        HarnessPageNetworkPolicy.allows(mode, url, mainFrame)

    @Test
    fun `默认档下除本机源之外一律拒绝`() {
        for (url in listOf(
            "https://example.com/a.png",
            "http://192.168.1.5:11434/api/tags",
            "https://dsh-market.com/manifest/skins.json",
        )) {
            assertFalse(url, allows(PageNetworkAccess.APP, url))
        }
    }

    @Test
    fun `允许 HTTP 档只放行明文 http`() {
        assertTrue(allows(PageNetworkAccess.HTTP, "http://192.168.1.5:11434/api/tags"))
        assertTrue(allows(PageNetworkAccess.HTTP, "http://example.com/a.png"))
        // 公网 https 仍不放开：这一档的语义是「明文」，不是「全部」。
        assertFalse(allows(PageNetworkAccess.HTTP, "https://example.com/a.png"))
    }

    @Test
    fun `全部网络档同时放行 http 与 https`() {
        assertTrue(allows(PageNetworkAccess.ALL, "http://192.168.1.5:11434/api/tags"))
        assertTrue(allows(PageNetworkAccess.ALL, "https://example.com/a.png"))
    }

    @Test
    fun `任何档位都不放行主框架请求`() {
        // 页面换站属于导航，不属于「页面发起的请求」；三档都不该把整页换到外部站点。
        assertFalse(allows(PageNetworkAccess.ALL, "https://example.com/", mainFrame = true))
        assertFalse(allows(PageNetworkAccess.HTTP, "http://example.com/", mainFrame = true))
    }

    @Test
    fun `非网络协议与畸形地址一律拒绝`() {
        for (url in listOf(
            "file:///etc/passwd",
            "content://com.example/secret",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "data:text/html,<h1>x</h1>",
            "https://user:pass@example.com/a",
            "https://example.com\\@evil.example/a",
            "https://example.com/a\n",
            "https://example.com/" + "a".repeat(HarnessPageNetworkPolicy.MAX_URL_CHARS),
            "not a url",
            "",
        )) {
            assertFalse(url, allows(PageNetworkAccess.ALL, url))
        }
    }

    @Test
    fun `端口非法时拒绝`() {
        assertFalse(allows(PageNetworkAccess.ALL, "https://example.com:0/a"))
        assertFalse(allows(PageNetworkAccess.ALL, "https://example.com:70000/a"))
    }
}
