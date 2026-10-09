package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAutomationPolicyTest {
    @Test
    fun whitelistToggleOnlyChangesOrdinaryPackageMembership() {
        val listed = setOf("com.example.reader")
        assertTrue(AccessibilityAutomationPolicy.packageAllowed("com.example.reader", listed))
        assertFalse(AccessibilityAutomationPolicy.packageAllowed("com.example.notes", listed))
        assertTrue(AccessibilityAutomationPolicy.packageAllowed("com.example.notes", listed, false))
        assertTrue(AccessibilityAutomationPolicy.packageAllowed("com.miui.calculator", emptySet(), false))
        assertFalse(AccessibilityAutomationPolicy.packageAllowed("com.example.notes", listed, true))
        for (invalid in listOf("", "bad package", "com.example.reader;id", "android", "com.android.settings",
            "com.android.systemui", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller")) {
            assertFalse(invalid, AccessibilityAutomationPolicy.packageAllowed(invalid, setOf(invalid), false))
        }
    }

    @Test
    fun whitelistHasNoCountLimitAndAllowsOrdinaryVendorApplications() {
        assertTrue(AccessibilityAutomationPolicy.validPackages((1..300).map { "com.example.app$it" }))
        assertTrue(AccessibilityAutomationPolicy.validPackage("com.miui.calculator"))
        assertTrue(AccessibilityAutomationPolicy.validPackage("com.samsung.android.calendar"))
        assertFalse(AccessibilityAutomationPolicy.validPackage("com.android.permissioncontroller"))
    }

    @Test
    fun onlyValidUserApplicationPackagesAreAccepted() {
        assertTrue(AccessibilityAutomationPolicy.validPackage("com.example.reader"))
        assertFalse(AccessibilityAutomationPolicy.validPackage("com.android.settings"))
        assertFalse(AccessibilityAutomationPolicy.validPackage("com.example.reader;id"))
        assertFalse(AccessibilityAutomationPolicy.validPackages(listOf("com.example.reader", "com.example.reader")))
    }

    /** 本应用是自动化目标之一（用户不必手动添加），但其它系统包仍然被拒。 */
    @Test
    fun selfPackageIsAlwaysAcceptedWhileSystemPackagesStayRejected() {
        assertTrue(AccessibilityAutomationPolicy.validPackage(AccessibilityAutomationPolicy.SELF_PACKAGE))
        assertTrue(AccessibilityAutomationPolicy.validPackages(listOf(AccessibilityAutomationPolicy.SELF_PACKAGE)))
        // 只放开了自己一个：保留清单里的系统包一个都不能漏。
        assertFalse(AccessibilityAutomationPolicy.validPackage("com.android.settings"))
        assertFalse(AccessibilityAutomationPolicy.validPackage("com.android.permissioncontroller"))
        assertFalse(AccessibilityAutomationPolicy.validPackage("com.android.systemui"))
        assertFalse(AccessibilityAutomationPolicy.validPackage("android"))
        assertFalse(AccessibilityAutomationPolicy.validPackage("com.android.packageinstaller"))
        assertFalse(AccessibilityAutomationPolicy.validPackage("com.google.android.permissioncontroller"))
    }

    @Test
    fun withSelfAddsSelfOnceAndKeepsOrderStable() {
        val added = AccessibilityAutomationPolicy.withSelf(listOf("com.example.reader", "com.example.writer"))
        assertEquals(listOf("com.example.reader", "com.example.writer", AccessibilityAutomationPolicy.SELF_PACKAGE).sorted(), added)

        // 幂等：再来一次结果完全相同。
        assertEquals(added, AccessibilityAutomationPolicy.withSelf(added))
        // 已经在里面时不会出现第二份。
        assertEquals(1, added.count { it == AccessibilityAutomationPolicy.SELF_PACKAGE })
        // 空列表也要带上自己：新装应用的白名单不是「空的」。
        assertEquals(listOf(AccessibilityAutomationPolicy.SELF_PACKAGE), AccessibilityAutomationPolicy.withSelf(emptyList()))
    }

    @Test
    fun withSelfTrimsDropsDuplicatesAndKeepsInvalidEntries() {
        assertEquals(
            listOf("com.example.reader", AccessibilityAutomationPolicy.SELF_PACKAGE),
            AccessibilityAutomationPolicy.withSelf(listOf(" com.example.reader ", "com.example.reader")),
        )
        // 排序稳定：输入顺序不影响结果，`state()` 里那份 sorted 也就不用再排一次。
        assertEquals(
            AccessibilityAutomationPolicy.withSelf(listOf("com.example.writer", "com.example.reader")),
            AccessibilityAutomationPolicy.withSelf(listOf("com.example.reader", "com.example.writer")),
        )
        // `withSelf` **不做**合法性过滤：非法包名照旧由 validPackages 整份拒绝，
        // 否则这里会变成一处「悄悄丢掉非法项」的地方，与「异常即全失效」的语义冲突。
        val invalid = AccessibilityAutomationPolicy.withSelf(listOf("com.example.reader;id"))
        assertTrue(invalid.contains("com.example.reader;id"))
        assertFalse(AccessibilityAutomationPolicy.validPackages(invalid))
    }

    @Test
    fun actionRequiresFullViewIdAndExactShape() {
        val valid = "{" +
            "\"packageName\":\"com.example.reader\"," +
            "\"action\":\"click\"," +
            "\"selector\":{\"viewId\":\"com.example.reader:id/submit\"}}"
        assertNotNull(AccessibilityAutomationPolicy.parseAction(valid))
        assertNull(AccessibilityAutomationPolicy.parseAction(valid.replace("submit", "other;id")))
        assertNull(AccessibilityAutomationPolicy.parseAction(valid.replace("click", "tap")))
    }

    @Test
    fun textInputRejectsSensitiveContentAndControlCharacters() {
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("请输入验证码"))
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("payment amount"))
        val safe = "{" +
            "\"packageName\":\"com.example.reader\"," +
            "\"action\":\"setText\"," +
            "\"selector\":{\"viewId\":\"com.example.reader:id/search\"}," +
            "\"text\":\"hello\"}"
        assertNotNull(AccessibilityAutomationPolicy.parseAction(safe))
        assertNull(AccessibilityAutomationPolicy.parseAction(safe.replace("hello", "验证码")))
        assertNull(AccessibilityAutomationPolicy.parseAction(safe.replace("hello", "a\\nb")))
    }

    @Test
    fun sensitiveTextDetectionSurvivesSplitAndZeroWidthObfuscation() {
        // 对抗性应用把敏感词拆开或插入不可见字符，归一化后仍必须命中。
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("密 码"))
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("密​码"))
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("密  码"))
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("验‍证⁠码"))
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("支　付"))
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("p a s s w o r d"))
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("pay​ment"))
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("请输入\n支付密码"))
        // 归一化不能误伤正常文本：含空白的普通句子依然放行。
        assertFalse(AccessibilityAutomationPolicy.containsSensitiveText("请输入用户名"))
        assertFalse(AccessibilityAutomationPolicy.containsSensitiveText("search for articles"))
        assertFalse(AccessibilityAutomationPolicy.containsSensitiveText("用 户 名"))
        assertFalse(AccessibilityAutomationPolicy.containsSensitiveText(null))
        assertFalse(AccessibilityAutomationPolicy.containsSensitiveText(""))
    }

    @Test
    fun scrollAcceptsOnlyTwoDirections() {
        val value = "{" +
            "\"packageName\":\"com.example.reader\"," +
            "\"action\":\"scroll\"," +
            "\"selector\":{\"viewId\":\"com.example.reader:id/list\"}," +
            "\"direction\":\"forward\"}"
        assertEquals("scroll", AccessibilityAutomationPolicy.parseAction(value)?.action)
        assertNull(AccessibilityAutomationPolicy.parseAction(value.replace("forward", "up")))
    }
}
