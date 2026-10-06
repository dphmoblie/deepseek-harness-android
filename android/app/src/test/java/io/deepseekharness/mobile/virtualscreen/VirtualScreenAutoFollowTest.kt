package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动跟随（autoFollowTick）的三个策略：off / pull_back / promote。
 *
 * 判定是纯函数，节流是可注入时钟的状态机——两者都不依赖设备，因此可以在单测里把
 * 「目标跳去主屏」「副屏前台换成别的应用」「同一目标被反复判定」这些真机才出现的时序钉住。
 */
class VirtualScreenAutoFollowTest {
    private val sessionTarget = "com.tencent.mm/.ui.LauncherUI"
    private val selfPackage = "io.deepseekharness.mobile"
    private val home = "com.hihonor.android.launcher"
    private val inputMethods = setOf("com.baidu.input_honor")

    private fun decide(
        policy: String,
        sessionAlive: Boolean = true,
        target: String = sessionTarget,
        virtualForeground: String? = "com.tencent.mm/.ui.LauncherUI",
        mainForeground: String? = "com.hihonor.android.launcher/.Launcher",
    ) = VirtualScreenPolicy.autoFollowDecision(
        policy = policy,
        sessionAlive = sessionAlive,
        sessionTarget = target,
        virtualForeground = virtualForeground,
        mainForeground = mainForeground,
        selfPackage = selfPackage,
        inputMethods = inputMethods,
        homePackage = home,
    )

    @Test fun `off 与无效策略都不动作`() {
        assertEquals("自动跟随已关闭", skipped(decide(VirtualScreenPolicy.AUTO_FOLLOW_OFF)))
        assertTrue(skipped(decide("aggressive")).contains("自动跟随策略无效"))
        // 策略集与配置入口的校验必须同源：这里列出的三条就是全部合法值。
        assertEquals(listOf("off", "pull_back", "promote"), VirtualScreenPolicy.AUTO_FOLLOW_POLICIES.keys.toList())
    }

    @Test fun `会话失效或没有目标时一律不动作`() {
        assertEquals(VirtualScreenPolicy.SESSION_DEAD_MESSAGE, skipped(decide("pull_back", sessionAlive = false)))
        assertEquals(VirtualScreenPolicy.SESSION_DEAD_MESSAGE, skipped(decide("promote", sessionAlive = false)))
        assertEquals("会话还没有目标应用", skipped(decide("pull_back", target = "")))
        assertEquals("会话还没有目标应用", skipped(decide("promote", target = "")))
    }

    @Test fun `promote 把副屏前台提升为会话目标且幂等`() {
        val applied = decide(
            "promote",
            target = "com.android.settings/.Main",
            virtualForeground = "com.tencent.mm/.ui.LauncherUI",
        )
        assertTrue(applied is VirtualScreenPolicy.AutoFollowDecision.Applied)
        applied as VirtualScreenPolicy.AutoFollowDecision.Applied
        assertEquals(VirtualScreenPolicy.AUTO_FOLLOW_PROMOTE, applied.rule)
        assertEquals("com.tencent.mm", applied.target)

        // 幂等：副屏前台已经是会话目标（哪怕是同包的另一个 Activity）就不要再启动一次。
        assertEquals("副屏最前台已经是会话目标：com.tencent.mm", skipped(decide("promote")))
        assertEquals(
            "副屏最前台已经是会话目标：com.tencent.mm",
            skipped(decide("promote", virtualForeground = "com.tencent.mm/.plugin.appbrand.ui.AppBrandUI")),
        )
    }

    @Test fun `promote 不提升桌面-输入法-系统界面与本应用`() {
        // 目标离开副屏时副屏前台常常变成桌面：把它提升成会话目标等于把会话毁掉。
        for (candidate in listOf(
            "com.hihonor.android.launcher/.Launcher",
            "com.baidu.input_honor/.Ime",
            "com.android.systemui/.Keyguard",
            "com.android.shell/.Shell",
            "$selfPackage/.MainActivity",
            "bad-name",
        )) {
            val decision = decide("promote", target = "com.android.settings/.Main", virtualForeground = candidate)
            assertTrue("$candidate 不该被提升：$decision", decision is VirtualScreenPolicy.AutoFollowDecision.Skipped)
            assertTrue(
                "$candidate 的跳过理由要说明是「不可跟随」",
                skipped(decision).contains(VirtualScreenPolicy.FOLLOW_NONE_MESSAGE),
            )
        }
        assertEquals(
            "副屏上没有解析到最前台应用",
            skipped(decide("promote", target = "com.android.settings/.Main", virtualForeground = null)),
        )
    }

    @Test fun `promote 不看主屏而 pull_back 不看副屏新前台`() {
        // pull_back 只认「会话目标出现在主屏最前台」：副屏前台换了别的应用、主屏却不是目标 ⇒ 不动作。
        assertTrue(
            skipped(
                decide(
                    "pull_back",
                    virtualForeground = "com.tencent.mm/.ui.LauncherUI",
                    mainForeground = "com.hihonor.android.launcher/.Launcher",
                ),
            ).contains("主屏最前台不是会话目标"),
        )
        // promote 与主屏无关：主屏停着会话目标也不影响「把副屏前台提升为目标」。
        val decision = decide(
            "promote",
            target = "com.android.settings/.Main",
            virtualForeground = "com.tencent.mm/.ui.LauncherUI",
            mainForeground = "com.tencent.mm/.ui.LauncherUI",
        )
        assertTrue(decision is VirtualScreenPolicy.AutoFollowDecision.Applied)
    }

    @Test fun `pull_back 只在目标跑到主屏最前台时把它拉回副屏`() {
        val applied = decide(
            "pull_back",
            virtualForeground = "com.hihonor.android.launcher/.Launcher",
            mainForeground = "com.tencent.mm/.ui.LauncherUI",
        )
        assertTrue(applied is VirtualScreenPolicy.AutoFollowDecision.Applied)
        applied as VirtualScreenPolicy.AutoFollowDecision.Applied
        assertEquals(VirtualScreenPolicy.AUTO_FOLLOW_PULL_BACK, applied.rule)
        assertEquals("com.tencent.mm", applied.target)

        // 目标仍在副屏（同包任意 Activity）⇒ 不动作，不能因为主屏也开着它就反复 am start。
        // 这一条的判据排在「主屏最前台是不是会话目标」之后，所以必须把两个前台都摆成同一个包，
        // 否则拦下它的会是前一条门槛，测不到「仍在副屏上」这条守卫。
        assertTrue(
            skipped(
                decide(
                    "pull_back",
                    virtualForeground = "com.tencent.mm/.plugin.appbrand.ui.AppBrandUI",
                    mainForeground = "com.tencent.mm/.ui.LauncherUI",
                ),
            ).contains("仍在副屏上"),
        )
        // 主屏最前台根本不是会话目标（例如桌面）时也必须不动作，理由要说清是这一条拦下的。
        assertTrue(
            skipped(decide("pull_back", virtualForeground = "com.tencent.mm/.plugin.appbrand.ui.AppBrandUI"))
                .contains("主屏最前台不是会话目标"),
        )
        assertEquals("主屏上没有解析到最前台应用", skipped(decide("pull_back", mainForeground = null)))
    }

    @Test fun `节流按规则与目标生效 换规则或换目标立刻放行`() {
        assertEquals(2000L, VirtualScreenPolicy.AUTO_FOLLOW_THROTTLE_MILLIS)
        val clock = longArrayOf(1_000L)
        val throttle = VirtualScreenPolicy.FollowThrottle(now = { clock[0] })

        assertTrue("第一次必须放行", throttle.attempt("pull_back", "com.a"))
        assertFalse("同一规则同一目标在窗口内只能动作一次", throttle.attempt("pull_back", "com.a"))
        clock[0] += 1_999L
        assertFalse("窗口还差 1 毫秒", throttle.attempt("pull_back", "com.a"))
        clock[0] += 1L
        assertTrue("满 2000 毫秒放行", throttle.attempt("pull_back", "com.a"))

        assertTrue("换了目标要立刻跟过去", throttle.attempt("pull_back", "com.b"))
        assertTrue("换了规则要立刻放行", throttle.attempt("promote", "com.b"))
        assertFalse(throttle.attempt("promote", "com.b"))

        // 窗口是可配置的：测试与将来的策略调整都不该被 2 秒硬编码锁死。
        val tight = VirtualScreenPolicy.FollowThrottle(windowMillis = 10L, now = { clock[0] })
        assertTrue(tight.attempt("promote", "com.b"))
        assertFalse(tight.attempt("promote", "com.b"))
        clock[0] += 10L
        assertTrue(tight.attempt("promote", "com.b"))
    }

    private fun skipped(decision: VirtualScreenPolicy.AutoFollowDecision): String {
        assertTrue("期望 Skipped，实际 $decision", decision is VirtualScreenPolicy.AutoFollowDecision.Skipped)
        return (decision as VirtualScreenPolicy.AutoFollowDecision.Skipped).reason
    }
}
