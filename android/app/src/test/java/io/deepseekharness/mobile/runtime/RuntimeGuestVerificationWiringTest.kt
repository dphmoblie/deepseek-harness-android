package io.deepseekharness.mobile.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「跳过 PRoot 自检」的接线回归用例。
 *
 * 缓存本身（[RuntimeGuestVerification]）与去重（[RuntimePreparationReuse]）是纯逻辑，已被各自的
 * 用例覆盖；但真正决定安全性的还有三处**接线**，它们都需要 Android `Context`，JVM 里跑不起来：
 *  - 只有完整启动成功（阶段 RUNNING）才能写「已验证」；
 *  - 任何一次非取消的启动失败都必须让缓存失效；
 *  - 安装/更新/切换/清空运行时都必须让缓存失效。
 *
 * 所以这里按仓库既有做法（见 `RuntimeHarnessExitRegressionTest`）读源码做断言：它不验证行为，
 * 只保证「后来者重构时不会把这三条安全接线悄悄删掉」。改坏时失败信息就是那条接线本身。
 */
class RuntimeGuestVerificationWiringTest {
    @Test
    fun `only a successful start records a verified profile`() {
        val source = source("RuntimeSupervisor.kt")
        val start = source.indexOf("fun startHarness(")
        assertTrue(start >= 0)
        val mark = source.indexOf("launchResolver.markGuestVerified()", start)
        assertTrue("启动成功路径必须写入已验证缓存", mark >= 0)
        // 必须先进入 RUNNING：探测通过（verifyGuest 成功）不算，PRoot→node→dsh→鉴权全部通过才算。
        val running = source.indexOf("RuntimePhase.RUNNING", start)
        assertTrue(running in 0 until mark)
    }

    @Test
    fun `every non-cancelled start failure invalidates the verified profile`() {
        val source = source("RuntimeSupervisor.kt")
        val start = source.indexOf("fun startHarness(")
        val end = source.indexOf("private fun failStart(", start)
        assertTrue(start >= 0 && end > start)
        val body = source.substring(start, end)

        val failureBranch = body.substringAfter("} catch (failure: RuntimeFailure) {")
        val invalidate = failureBranch.indexOf("launchResolver.invalidateGuestVerification()")
        assertTrue("受控失败必须让缓存失效", invalidate >= 0)
        // 用户取消不是失败：失效判断必须在「取消」分支之前，但只在非取消时执行。
        assertTrue(failureBranch.indexOf("failure.code != START_CANCELLED_CODE") in 0 until invalidate)

        // 非受控异常（编程错误/系统异常）同样不能让下一次启动继续信任缓存。
        val uncontrolled = body.substringAfter("} catch (failure: Throwable) {")
        assertTrue(uncontrolled.indexOf("launchResolver.invalidateGuestVerification()") in 0..uncontrolled.indexOf("throw failure"))
    }

    @Test
    fun `the preflight probe is skipped only for a cached profile with a matching token`() {
        val source = source("RuntimeLaunchResolver.kt")
        val verify = source.substringAfter("fun verifyGuest(").substringBefore("private fun resolveProfile(")
        assertTrue(
            "跳过条件必须是「来自缓存的启动档 + 令牌逐字匹配」",
            verify.contains("lookup.fromCache && guestVerification.allowsSkip(lookup.profile.key)"),
        )
        assertTrue("探测失败必须让缓存失效", verify.contains("guestVerification.invalidate()"))
    }

    @Test
    fun `installing updating or clearing the runtime invalidates the verified profile`() {
        val store = source("RuntimeStore.kt")
        listOf("fun updateInstalledManifest(", "fun invalidateInstalledManifest()").forEach { declaration ->
            val start = store.indexOf(declaration)
            assertTrue("找不到 $declaration", start >= 0)
            val body = store.substring(start, store.indexOf("\n    }", start))
            assertTrue(
                "$declaration 必须清掉「已验证」缓存",
                body.contains("RuntimeGuestVerification.from(appContext).invalidate()"),
            )
        }
        // 安装、版本切换与清空都必须经过这两个入口，否则上面的失效就覆盖不到。
        val installer = source("RuntimeInstaller.kt")
        assertTrue(installer.contains("store.updateInstalledManifest("))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/io/deepseekharness/mobile/runtime/$name"
        return listOf(File(relative), File("app/$relative"), File("android/app/$relative"))
            .first { it.isFile }.readText()
    }
}
