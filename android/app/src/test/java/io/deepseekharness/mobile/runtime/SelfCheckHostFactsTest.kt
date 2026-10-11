package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主追加的四项设备事实的判定测试（纯逻辑，可 JVM 单测）。
 *
 * 这里挡的是两件会直接误导排障的事：
 *  - 把**没读到**（探测失败）当成 `ok` 或 `fail` —— 前者会掩盖问题，后者会凭空造出故障；
 *  - 让这四项的码表与访客 14 项的码表各说各话 —— 产码方与校验表必须一一对得上。
 */
class SelfCheckHostFactsTest {
    private fun entries(facts: SelfCheckHostFacts.Facts): Map<String, Pair<String, String?>> =
        SelfCheckHostFacts.rawChecks(facts).associate { it.id to (it.status to it.code) }

    /** 四项事实全部读到且都正常。 */
    private val healthy = SelfCheckHostFacts.Facts(
        pluginLinksMissing = false,
        pluginRepairFailed = false,
        accessibilityConnected = true,
        overlayPermissionGranted = true,
        webViewAvailable = true,
        webViewVersion = "120.0.6099.230",
    )

    @Test
    fun appendsExactlyTheFourHostItemsAfterTheGuestFourteen() {
        val ids = SelfCheckHostFacts.rawChecks(healthy).map { it.id }
        assertEquals(listOf("plugins", "accessibility", "virtualscreen", "webview"), ids)
        // 宿主四项只能追加在契约末尾：不能插到访客 14 项中间去。
        assertEquals(RuntimeSelfCheckPolicy.CHECK_IDS.takeLast(4), ids)

        // 与访客 14 项（这里都用 ok）合并经同一套 sanitize 后，顺序就是完整的 18 项契约顺序。
        val guest = RuntimeSelfCheckPolicy.CHECK_IDS.take(14).map { RuntimeSelfCheckPolicy.RawCheck(it, "ok", null) }
        val merged = RuntimeSelfCheckPolicy.sanitize(guest + SelfCheckHostFacts.rawChecks(healthy))
        assertEquals(RuntimeSelfCheckPolicy.CHECK_IDS, merged.map { it.id })
        assertEquals(18, merged.size)
    }

    @Test
    fun mapsEveryHealthyFactToAnOkItemWithoutACode() {
        val checks = RuntimeSelfCheckPolicy.sanitize(SelfCheckHostFacts.rawChecks(healthy))
        assertEquals(listOf("plugins", "accessibility", "virtualscreen", "webview"), checks.map { it.id })
        assertTrue(checks.all { it.status == "ok" && it.code == null })
        // 全正常时宿主四项不产生任何失败。
        assertEquals(RuntimeSelfCheckPolicy.Summary(null, 0), RuntimeSelfCheckPolicy.summarize(checks))
    }

    @Test
    fun reportsEachUnavailableFactWithItsOwnCode() {
        val facts = entries(
            SelfCheckHostFacts.Facts(
                pluginLinksMissing = true,
                pluginRepairFailed = false,
                accessibilityConnected = false,
                overlayPermissionGranted = false,
                webViewAvailable = false,
            ),
        )
        assertEquals("warn" to "PLUGIN_LINK_MISSING", facts.getValue("plugins"))
        assertEquals("warn" to "ACCESSIBILITY_NOT_CONNECTED", facts.getValue("accessibility"))
        assertEquals("warn" to "OVERLAY_PERMISSION_MISSING", facts.getValue("virtualscreen"))
        // 唯一一项 fail：WebView 不可用。
        assertEquals("fail" to "WEBVIEW_UNAVAILABLE", facts.getValue("webview"))
    }

    @Test
    fun treatsUnknownFactsAsProbeUnknownRatherThanOkOrFail() {
        // 四项事实全未知（探测全部失败）。
        val unknown = entries(SelfCheckHostFacts.Facts())
        assertEquals("warn" to "PLUGIN_PROBE_UNKNOWN", unknown.getValue("plugins"))
        assertEquals("warn" to "ACCESSIBILITY_PROBE_UNKNOWN", unknown.getValue("accessibility"))
        assertEquals("warn" to "OVERLAY_PROBE_UNKNOWN", unknown.getValue("virtualscreen"))
        assertEquals("warn" to "WEBVIEW_PROBE_UNKNOWN", unknown.getValue("webview"))

        val checks = RuntimeSelfCheckPolicy.sanitize(SelfCheckHostFacts.rawChecks(SelfCheckHostFacts.Facts()))
        // 未知既不许冒充 ok，也不许冒充 fail（后者等于凭空造一个「WebView 不可用」的结论）。
        assertTrue(checks.none { it.status == "ok" })
        assertTrue(checks.none { it.status == "fail" })
        assertEquals(RuntimeSelfCheckPolicy.Summary(null, 0), RuntimeSelfCheckPolicy.summarize(checks))
    }

    @Test
    fun requiresBothPluginFactsBeforeConcludingAnything() {
        // 只读到一半（修复结果未知）时整项按未知呈现，不去用另一半猜结论。
        val halfRead = entries(SelfCheckHostFacts.Facts(pluginLinksMissing = true))
        assertEquals("warn" to "PLUGIN_PROBE_UNKNOWN", halfRead.getValue("plugins"))

        val fullyRead = entries(SelfCheckHostFacts.Facts(pluginLinksMissing = true, pluginRepairFailed = false))
        assertEquals("warn" to "PLUGIN_LINK_MISSING", fullyRead.getValue("plugins"))
    }

    @Test
    fun prefersTheCurrentLinkStateOverTheHistoricalRepairFailure() {
        // 两个事实同时成立时报「当前可处置的那个」：链接缺失优先于「上次修复失败」。
        val both = entries(SelfCheckHostFacts.Facts(pluginLinksMissing = true, pluginRepairFailed = true))
        assertEquals("warn" to "PLUGIN_LINK_MISSING", both.getValue("plugins"))

        val repairOnly = entries(SelfCheckHostFacts.Facts(pluginLinksMissing = false, pluginRepairFailed = true))
        assertEquals("warn" to "PLUGIN_LINK_FAILED", repairOnly.getValue("plugins"))
    }

    @Test
    fun emitsOnlyCodesThatThePolicyTableAccepts() {
        // 产码方与码表不许各说各话：本类产出的每一条都必须能原样过策略层（状态与码表一致）。
        val samples = listOf(
            SelfCheckHostFacts.Facts(),
            SelfCheckHostFacts.Facts(pluginLinksMissing = true, pluginRepairFailed = false),
            SelfCheckHostFacts.Facts(pluginLinksMissing = false, pluginRepairFailed = true),
            SelfCheckHostFacts.Facts(accessibilityConnected = false),
            SelfCheckHostFacts.Facts(overlayPermissionGranted = false),
            SelfCheckHostFacts.Facts(webViewAvailable = false),
            healthy,
        )
        for (facts in samples) {
            for (item in SelfCheckHostFacts.rawChecks(facts)) {
                assertEquals(
                    "宿主条目被策略层丢弃：$item",
                    listOf(item.id),
                    RuntimeSelfCheckPolicy.sanitize(listOf(item)).map { it.id },
                )
            }
        }
    }

    @Test
    fun normalizesTheWebViewVersionText() {
        // 版本号是纯信息：形态合规才回传，其余一律 null（界面显示为未知），绝不回传原文。
        assertEquals("120.0.6099.230", SelfCheckHostFacts.versionText("120.0.6099.230"))
        assertEquals("16.0.0.1-beta+2", SelfCheckHostFacts.versionText("16.0.0.1-beta+2"))
        assertNull(SelfCheckHostFacts.versionText(null))
        assertNull(SelfCheckHostFacts.versionText(""))
        assertNull(SelfCheckHostFacts.versionText(" 120.0 ")) // 带空白
        assertNull(SelfCheckHostFacts.versionText("/data/app/webview/base.apk")) // 路径（含 `/`）
        assertNull(SelfCheckHostFacts.versionText("120.0\n6099")) // 换行
        assertNull(SelfCheckHostFacts.versionText("v" + "1".repeat(64))) // 超长（65 字符）
    }
}
