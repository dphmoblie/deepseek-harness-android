package io.deepseekharness.mobile.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android Context 由设备提供；这里固定退出观察者与启动入口必须共享的锁接线。 */
class RuntimeHarnessExitRegressionTest {
    @Test
    fun noTimeBasedCooldownCanSuppressRestartAfterAProcessDies() {
        val source = source("RuntimeSupervisor.kt")
        assertFalse(source.contains("START_COOLDOWN_MS"))
        assertFalse(source.contains("lastStartAttemptAt"))
        assertTrue(source.contains("if (existing?.isAlive == true && harnessAccess != null)"))
    }

    @Test
    fun oldExitCallbacksCannotClearOrPublishAfterANewStart() {
        val source = source("RuntimeSupervisor.kt")
        val start = source.indexOf("private fun onHarnessExited(process: Process, manifest: RuntimeManifest) = synchronized(lock) {")
        assertTrue(start >= 0)
        val body = source.substring(start, source.indexOf("\n    }", start))
        val identity = body.indexOf("if (harnessProcess !== process) return@synchronized")
        assertTrue(identity >= 0 && identity < body.indexOf("status.snapshot()"))
        assertTrue(identity < body.indexOf("clearHarnessState()"))
        assertTrue(body.contains("status.update("))
        assertTrue(body.contains("TaskNotification.postHarnessStopped("))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/io/deepseekharness/mobile/runtime/$name"
        return listOf(File(relative), File("app/$relative"), File("android/app/$relative"))
            .first { it.isFile }.readText()
    }
}
