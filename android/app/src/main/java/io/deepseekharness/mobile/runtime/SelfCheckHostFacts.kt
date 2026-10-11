package io.deepseekharness.mobile.runtime

/**
 * 宿主追加的四项设备事实（纯逻辑，不依赖 Android，也不依赖 Capacitor，可 JVM 单测）。
 *
 * 为什么要有这四项：插件链接、无障碍服务、副屏悬浮窗权限、系统 WebView 这四件事**访客里的 node
 * 脚本看不到** —— 它们都住在 Android 侧（插件管理器、系统服务、包管理器）。所以自检载荷由两段拼成：
 * 访客脚本产出 14 项（前 11 项链路 + 3 项编译环境能力），宿主读事实后追加这 4 项，合计 18 项。
 * 访客脚本一行都不用改，老载荷（只带 14 项）也仍然合法。
 *
 * 为什么判定与取值分开：Android API 只能在真机上跑，而「事实 → 受控条目」的判定必须能在 JVM 单测里
 * 钉死。所以取值留在 [RuntimeSelfCheck]，本类只吃一组已经是纯值的 [Facts]，吐
 * [RuntimeSelfCheckPolicy.RawCheck]（仍然要过 `RuntimeSelfCheckPolicy.sanitize` 的同一张码表）。
 *
 * 三态语义：每个事实都是 `Boolean?`，`null` 表示**探测本身失败（未知）**。未知一律出
 * `*_PROBE_UNKNOWN`（warn），绝不用 `ok` 掩盖没读到的事实，也不把没读到的结论说成故障 ——
 * 与编译环境能力项同一口径。
 */
internal object SelfCheckHostFacts {
    /**
     * 宿主读取到的设备事实。每项为 `null` 表示「探测失败，未知」，不是 `false`。
     *
     * - [pluginLinksMissing]：插件管理器清单里登记、但运行时模块链接缺失（保留区已无副本，只能重装）的插件是否存在。
     * - [pluginRepairFailed]：最近一次插件修复是否有包修复失败/被拒。
     * - [accessibilityConnected]：无障碍服务当前是否已连接（`DeepSeekAccessibilityService.current() != null`）。
     * - [overlayPermissionGranted]：是否已授予悬浮窗权限（副屏、悬浮球依赖它）。
     * - [webViewAvailable]：系统 WebView 是否可用（`WebView.getCurrentWebViewPackage() != null`）。
     * - [webViewVersion]：系统报告的**原始**版本串，只作报告里的版本字段，回传前必须过 [versionText]。
     */
    internal data class Facts(
        val pluginLinksMissing: Boolean? = null,
        val pluginRepairFailed: Boolean? = null,
        val accessibilityConnected: Boolean? = null,
        val overlayPermissionGranted: Boolean? = null,
        val webViewAvailable: Boolean? = null,
        val webViewVersion: String? = null,
    )

    /**
     * 版本文本形态白名单：与前端 `src/runtimeSelfCheck.ts` 的 `VERSION_TEXT_PATTERN` 逐字同规则
     * （首字符字母数字，其后只允许字母数字与 `._+-`，总长 1..64）。
     *
     * 版本串来自系统包管理器，仍按白名单放行：界面上出现的永远是短文本，不带空白、路径或任意内容。
     */
    private val VERSION_TEXT = Regex("^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$")

    /** 归一化版本文本：为空或不匹配白名单一律回 `null`（界面显示为未知），绝不回传未校验的原文。 */
    fun versionText(value: String?): String? = value?.takeIf { VERSION_TEXT.matches(it) }

    /**
     * 四项事实 → 受控条目；顺序与 [RuntimeSelfCheckPolicy.CHECK_IDS] 末尾四项一致
     * （`plugins` / `accessibility` / `virtualscreen` / `webview`）。
     */
    fun rawChecks(facts: Facts): List<RuntimeSelfCheckPolicy.RawCheck> = listOf(
        pluginCheck(facts),
        accessibilityCheck(facts.accessibilityConnected),
        overlayCheck(facts.overlayPermissionGranted),
        webViewCheck(facts.webViewAvailable),
    )

    /**
     * 插件链接：两个事实必须都读到才敢下结论，任何一个未知就整项按未知呈现。
     *
     * 链接缺失优先于「上次修复失败」：前者是**当前**可处置的状态（设置页的人工修复入口能处理），
     * 后者是历史结论，两者同时成立时报更靠前的那个。
     */
    private fun pluginCheck(facts: Facts): RuntimeSelfCheckPolicy.RawCheck {
        if (facts.pluginLinksMissing == null || facts.pluginRepairFailed == null) {
            return warn("plugins", "PLUGIN_PROBE_UNKNOWN")
        }
        return when {
            facts.pluginLinksMissing -> warn("plugins", "PLUGIN_LINK_MISSING")
            facts.pluginRepairFailed -> warn("plugins", "PLUGIN_LINK_FAILED")
            else -> ok("plugins")
        }
    }

    /** 无障碍是**可选功能**：没连接不是故障，如实报 warn 并说明它影响哪些能力。 */
    private fun accessibilityCheck(connected: Boolean?): RuntimeSelfCheckPolicy.RawCheck = when (connected) {
        null -> warn("accessibility", "ACCESSIBILITY_PROBE_UNKNOWN")
        false -> warn("accessibility", "ACCESSIBILITY_NOT_CONNECTED")
        true -> ok("accessibility")
    }

    /** 悬浮窗权限同样是可选功能（副屏与悬浮球依赖它）；副屏主链路另有自己的自检与设置页。 */
    private fun overlayCheck(granted: Boolean?): RuntimeSelfCheckPolicy.RawCheck = when (granted) {
        null -> warn("virtualscreen", "OVERLAY_PROBE_UNKNOWN")
        false -> warn("virtualscreen", "OVERLAY_PERMISSION_MISSING")
        true -> ok("virtualscreen")
    }

    /**
     * 系统 WebView 是**唯一一项 `fail`**：界面本身就由 WebView 渲染，它不可用时应用根本起不来，
     * 因此这是运行时链路级别的故障，而不是可选功能没开。
     */
    private fun webViewCheck(available: Boolean?): RuntimeSelfCheckPolicy.RawCheck = when (available) {
        null -> warn("webview", "WEBVIEW_PROBE_UNKNOWN")
        false -> fail("webview", "WEBVIEW_UNAVAILABLE")
        true -> ok("webview")
    }

    private fun ok(id: String): RuntimeSelfCheckPolicy.RawCheck =
        RuntimeSelfCheckPolicy.RawCheck(id, "ok", null)

    private fun warn(id: String, code: String): RuntimeSelfCheckPolicy.RawCheck =
        RuntimeSelfCheckPolicy.RawCheck(id, "warn", code)

    private fun fail(id: String, code: String): RuntimeSelfCheckPolicy.RawCheck =
        RuntimeSelfCheckPolicy.RawCheck(id, "fail", code)
}
