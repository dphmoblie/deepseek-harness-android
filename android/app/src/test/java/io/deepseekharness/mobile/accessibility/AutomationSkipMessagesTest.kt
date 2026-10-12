package io.deepseekharness.mobile.accessibility

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文案表与两个受控码集**一一对应**。
 *
 * 用反射遍历 `AutomationGateCodes` / `AutomationSkipCodes` 的 `const val` 字段，而不是在用例里抄一份码清单：
 * 抄一份的话，新加码时用例照样绿，界面上就会出现一个英文码。反射取常量需要一个前提——码必须是
 * `const val String`（现状如此），这一点由「解析出的码数量大于 0」这条自检守着，否则解析失效会让
 * 后面所有断言恒真。
 */
class AutomationSkipMessagesTest {

    private fun codes(type: Class<*>): List<String> = type.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }

    private fun declaredCodes(): Set<String> =
        (codes(AutomationGateCodes::class.java) + codes(AutomationSkipCodes::class.java)).toSet()

    @Test
    fun `码集能被反射解析出来`() {
        // 恒真守卫：两个码集一个都没解析出来时，下面的用例会全部无声通过。
        assertTrue(
            "反射没取到码常量，请检查码是否仍是 const val String",
            codes(AutomationGateCodes::class.java).size > 20,
        )
        assertTrue(codes(AutomationSkipCodes::class.java).size > 5)
    }

    @Test
    fun `每个受控码都有中文说明`() {
        for (code in declaredCodes()) {
            val message = AutomationSkipMessages.message(code)
            assertFalse("码 $code 没有登记说明", message == AutomationSkipMessages.UNKNOWN)
            assertTrue("码 $code 的说明是空串", message.isNotBlank())
            assertTrue(
                "码 $code 的说明 ${message.length} 字，超过上限 ${AutomationSkipMessages.MAX_CHARS}",
                message.length <= AutomationSkipMessages.MAX_CHARS,
            )
            assertFalse("码 $code 的说明带了换行或制表符，卡片会被撑破", message.contains('\n') || message.contains('\t'))
        }
    }

    @Test
    fun `文案表里没有多余的码`() {
        // 两个方向的差集都必须为空：漏一个码是界面显示英文码，多一个码是复制的残渣。
        assertEquals(emptySet<String>(), declaredCodes() - AutomationSkipMessages.codes())
        assertEquals(emptySet<String>(), AutomationSkipMessages.codes() - declaredCodes())
    }

    @Test
    fun `未知码回落到通用说明且不抛异常`() {
        // 注意 `SENSITIVE_WINDOW` 本身**不是**受控码（闸门里的取值是 `AUTOMATION_SENSITIVE_WINDOW`，
        // 匹配侧没有这个码），因此它必须回落到通用说明——这条断言顺带钉住「码是精确匹配，不做后缀猜解」。
        for (code in listOf("", "NOT_A_CODE", "sensitive_window", "SENSITIVE_WINDOW", "AUTOMATION_")) {
            assertEquals(AutomationSkipMessages.UNKNOWN, AutomationSkipMessages.message(code))
        }
    }

    @Test
    fun `运行记录用的查询不兜底只回已登记的码`() {
        // 拦截卡片走 message()（必须有一句话可说），运行记录走 messageOrNull()：那里既有成功也有失败，
        // 认不出来的码必须留空让界面显示原始码，否则一次成功的点击会被写成「未知原因，已跳过」。
        for (code in declaredCodes()) {
            assertEquals(AutomationSkipMessages.message(code), AutomationSkipMessages.messageOrNull(code))
        }
        assertEquals(null, AutomationSkipMessages.messageOrNull("NOT_A_CODE"))
        assertEquals(null, AutomationSkipMessages.messageOrNull(""))
        // 与回落版的分工：同一个未知码，一个给通用说明，一个给 null。
        assertEquals(AutomationSkipMessages.UNKNOWN, AutomationSkipMessages.message("NOT_A_CODE"))
    }

    @Test
    fun `拦截关键场景的说明能让人看懂原因`() {
        // 这三条是任务书里点名要「说明原因」的场景：敏感页面拦截、锁屏、白名单外。
        assertTrue(AutomationSkipMessages.message(AutomationGateCodes.SENSITIVE_WINDOW).contains("密码"))
        assertTrue(AutomationSkipMessages.message(AutomationGateCodes.DEVICE_LOCKED).contains("锁屏"))
        assertTrue(AutomationSkipMessages.message(AutomationGateCodes.PACKAGE_NOT_ALLOWED).contains("白名单"))
        // 按应用暂停同样要让人看懂：说明里必须点出「暂停」。写成「已拒绝」的话，用户看不到原因，
        // 第一反应是「自动化坏了」——而这是他上一步自己按下的那个开关。
        assertTrue(AutomationSkipMessages.message(AutomationGateCodes.APP_PAUSED).contains("暂停"))
    }
}
