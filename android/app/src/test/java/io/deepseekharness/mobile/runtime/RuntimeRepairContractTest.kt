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

    @Test
    fun `修复源归档损坏时统一返回不可用错误`() {
        val source = controllerSource()
        val start = source.indexOf("fun repairRuntime()")
        assertTrue("找不到 repairRuntime()", start >= 0)
        val end = source.indexOf("\n    /**", start + 1)
        val body = source.substring(start, end)
        listOf(
            "RUNTIME_LIBRARY_VERSION_MISSING",
            "ARCHIVE_DIGEST_MISMATCH",
            "MANIFEST_INVALID",
            "MANIFEST_DIGEST_MISMATCH",
        ).forEach { code ->
            assertTrue("$code 必须被识别为修复源不可用", body.contains("\"$code\""))
        }
        assertTrue("修复源不可用必须返回统一错误码", body.contains("RUNTIME_REPAIR_UNAVAILABLE"))
    }

    @Test
    fun `版本管理缺失使用独立错误码`() {
        val installer = readSource("src/main/java/io/deepseekharness/mobile/runtime/RuntimeInstaller.kt")
        val library = readSource("src/main/java/io/deepseekharness/mobile/runtime/RuntimeLibrary.kt")
        assertTrue(installer.contains("RUNTIME_PREVIOUS_MISSING"))
        assertTrue(installer.contains("RUNTIME_VERSION_SWITCH_UNAVAILABLE"))
        assertTrue(library.contains("RUNTIME_LIBRARY_VERSION_MISSING"))
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

    private fun readSource(relative: String): String {
        val file = listOf(File(relative), File("app/$relative"), File("android/app/$relative"))
            .firstOrNull { it.isFile }
        if (file == null) {
            fail("找不到 $relative（工作目录：${File(".").absolutePath}）")
            return ""
        }
        return file.readText()
    }
}
