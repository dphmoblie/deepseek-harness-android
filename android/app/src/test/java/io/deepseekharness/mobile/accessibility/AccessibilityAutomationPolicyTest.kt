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
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("Enter OTP"))
        assertTrue(AccessibilityAutomationPolicy.containsSensitiveText("OTP code"))
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

    @Test
    fun hostUnlockFlagsToggleReservedSystemPackagesAndSensitiveText() {
        val reserved = listOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
        )
        for (pkg in reserved) {
            assertFalse("默认拒绝系统包 $pkg", AccessibilityAutomationPolicy.packageAllowed(pkg, emptySet(), true, allowReserved = false))
            assertTrue("开启开关后放行系统包 $pkg", AccessibilityAutomationPolicy.packageAllowed(pkg, emptySet(), true, allowReserved = true))
        }
        val androidDialogClick = "{" +
            "\"packageName\":\"android\"," +
            "\"action\":\"click\"," +
            "\"selector\":{\"viewId\":\"android:id/button1\"}}"
        assertNull(AccessibilityAutomationPolicy.parseAction(androidDialogClick, allowReserved = false))
        assertNotNull(AccessibilityAutomationPolicy.parseAction(androidDialogClick, allowReserved = true))

        val settingsDialogClick = "{" +
            "\"packageName\":\"com.android.settings\"," +
            "\"action\":\"click\"," +
            "\"selector\":{\"viewId\":\"android:id/button1\"}}"
        assertNull(AccessibilityAutomationPolicy.parseAction(settingsDialogClick, allowReserved = false))
        assertNotNull(AccessibilityAutomationPolicy.parseAction(settingsDialogClick, allowReserved = true))

        val ordinaryAppCrossPkg = "{" +
            "\"packageName\":\"com.example.reader\"," +
            "\"action\":\"click\"," +
            "\"selector\":{\"viewId\":\"android:id/button1\"}}"
        assertNull(AccessibilityAutomationPolicy.parseAction(ordinaryAppCrossPkg, allowReserved = true))

        val sensitiveInput = "{" +
            "\"packageName\":\"com.example.reader\"," +
            "\"action\":\"setText\"," +
            "\"selector\":{\"viewId\":\"com.example.reader:id/input\"}," +
            "\"text\":\"验证码123456\"}"
        assertNull(AccessibilityAutomationPolicy.parseAction(sensitiveInput, allowSensitiveText = false))
        assertEquals("验证码123456", AccessibilityAutomationPolicy.parseAction(sensitiveInput, allowSensitiveText = true)?.text)

        // Root / Shizuku 管理器与国产 OEM 安全中心默认禁止，开启开关后才放行
        val privilegedManagers = listOf(
            "me.weishu.kernelsu",
            "com.rifsxd.ksunext",
            "me.bmax.apatch",
            "com.topjohnwu.magisk",
            "moe.shizuku.privileged.api",
            "com.miui.securitycenter",
            "com.oplus.securitypermission",
            "com.vivo.permissionmanager",
            "com.huawei.systemmanager",
            "com.hihonor.systemmanager",
            "com.samsung.android.packageinstaller",
        )
        for (pkg in privilegedManagers) {
            assertTrue("$pkg 应属于受保护包", AccessibilityAutomationPolicy.isReservedPackage(pkg))
            assertFalse("默认必须禁止 $pkg", AccessibilityAutomationPolicy.packageAllowed(pkg, emptySet(), false, allowReserved = false))
            assertTrue("显式开启系统包开关后放行 $pkg", AccessibilityAutomationPolicy.packageAllowed(pkg, emptySet(), true, allowReserved = true))
        }

        // 本应用高危权限开关页与 Root 确认页防 AI 自我提权文本识别（五语 + 防拆字）
        assertTrue(AccessibilityAutomationPolicy.containsSelfPrivilegeGuardText("AI 高危文件与命令权限"))
        assertTrue(AccessibilityAutomationPolicy.containsSelfPrivilegeGuardText("高 危 文 件 与 命 令 权 限"))
        assertTrue(AccessibilityAutomationPolicy.containsSelfPrivilegeGuardText("AI High-Risk File & Command Permissions"))
        assertTrue(AccessibilityAutomationPolicy.containsSelfPrivilegeGuardText("AI 高リスクファイル・コマンド権限"))
        assertTrue(AccessibilityAutomationPolicy.containsSelfPrivilegeGuardText("AI 고위험 파일 및 명령 권한"))
        assertTrue(AccessibilityAutomationPolicy.containsSelfPrivilegeGuardText("【极危 Root 命令】"))
        assertFalse(AccessibilityAutomationPolicy.containsSelfPrivilegeGuardText("开始新对话"))
        assertFalse(AccessibilityAutomationPolicy.containsSelfPrivilegeGuardText("模型与外观设置"))
    }
}
