package io.deepseekharness.mobile.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 回归保护：运行时修复必须能绕过已经损坏的插件清单，并在后台完成后发通知。 */
class RuntimeRepairContractTest {
    @Test
    fun `修复不被插件 checkpoint 阻断且会发送完成通知`() {
        val source = controllerSource()
        val start = source.indexOf("fun repairRuntime()")
        assertTrue("找不到 repairRuntime()", start >= 0)
        val end = source.indexOf("\n    /**", start + 1)
        assertTrue("repairRuntime() 函数体没有正常结束", end > start)
        val body = source.substring(start, end)
        assertTrue("插件 checkpoint 失败时仍必须继续修复", body.contains("catch (failure: Exception)"))
        assertTrue("checkpoint 失败必须留下受控诊断", body.contains("PLUGIN_CHECKPOINT_FAILED"))
        assertTrue("修复完成后必须通知后台用户", body.contains("RUNTIME_REPAIRED"))
    }

    private fun controllerSource(): String {
        val relative = "src/main/java/io/deepseekharness/mobile/runtime/MobileRuntimeController.kt"
        val file = listOf(File(relative), File("app/$relative"), File("android/app/$relative"))
            .firstOrNull { it.isFile }
        if (file == null) {
            fail("找不到 MobileRuntimeController.kt（工作目录：${File(".").absolutePath}）")
            return ""
        }
        return file.readText()
    }
}
