package io.deepseekharness.mobile.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 接线契约：闸门本身有单测，但「起停入口真的先问闸门」只能由源码结构保证。
 *
 * 顺序是这里唯一的重点：`operations.requireIdle()` 必须在 `withStartOperationLock`
 * 进入 `lifecycleLock.withLock` 之前执行。反过来写，启动请求仍会排在 npm 安装后面。
 */
class RuntimeOperationGateContractTest {
    @Test
    fun startAndOpenAskTheGateBeforeQueueingOnTheLifecycleLock() {
        val source = controllerSource()
        listOf("fun startHarness(", "fun openHarnessAccess()").forEach { signature ->
            val start = source.indexOf(signature)
            assertTrue("找不到 $signature", start >= 0)
            val end = source.indexOf("\n    /**", start + 1)
            assertTrue("$signature 函数体没有正常结束", end > start)
            val body = source.substring(start, end)
            assertTrue("$signature 必须走起停专用锁", body.contains("withStartOperationLock"))
        }
        val helper = source.indexOf("private inline fun <T> withStartOperationLock")
        assertTrue("找不到 withStartOperationLock", helper >= 0)
        val helperEnd = source.indexOf("\n    }", helper)
        assertTrue("withStartOperationLock 函数体没有正常结束", helperEnd > helper)
        val helperBody = source.substring(helper, helperEnd)
        val gateCheck = helperBody.indexOf("operations.requireIdle()")
        val lockAcquire = helperBody.indexOf("lifecycleLock.withLock")
        assertTrue("起停入口必须先检查长操作闸门", gateCheck in 0 until lockAcquire)
    }

    @Test
    fun longOperationsAreRegisteredBeforeTakingTheLock() {
        val source = controllerSource()
        listOf(
            "fun manageHarnessVersions(",
            "fun install(",
            "fun repairRuntime()",
            "fun switchRuntimeVersion(",
            "fun downloadToLibrary(",
            "fun manageRuntimeLibrary(",
            "fun managePlugins(",
            "fun deleteRuntimeVersion(",
            "internal fun retireStaleResidue()",
            "fun reset(",
        ).forEach { signature ->
            val start = source.indexOf(signature)
            assertTrue("找不到 $signature", start >= 0)
            val end = source.indexOf("\n    /**", start + 1)
            assertTrue("$signature 函数体没有正常结束", end > start)
            val body = source.substring(start, end)
            assertTrue("$signature 必须走长操作锁", body.contains("withLongOperationLock"))
        }
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
