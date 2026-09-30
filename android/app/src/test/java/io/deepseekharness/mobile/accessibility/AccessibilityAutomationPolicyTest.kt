package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAutomationPolicyTest {
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
        assertFalse(AccessibilityAutomationPolicy.validPackage("io.deepseekharness.mobile"))
        assertFalse(AccessibilityAutomationPolicy.validPackage("com.example.reader;id"))
        assertFalse(AccessibilityAutomationPolicy.validPackages(listOf("com.example.reader", "com.example.reader")))
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
