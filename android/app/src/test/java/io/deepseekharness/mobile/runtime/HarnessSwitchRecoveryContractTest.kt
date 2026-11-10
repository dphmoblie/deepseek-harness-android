package io.deepseekharness.mobile.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无设备时固定恢复入口的接线；profile 未修复前不能丢掉重试依据。 */
class HarnessSwitchRecoveryContractTest {
    @Test
    fun recoveryCommitsOnlyAfterProfileIsRebuilt() {
        val body = body("private fun recoverPendingHarnessSwitch(")
        val rollback = body.indexOf("rollbackSwitch()")
        val profile = body.indexOf("harnessVersions.prepareProfile()")
        val commit = body.indexOf("harnessVersions.commitSwitch()")
        assertTrue(rollback >= 0 && profile > rollback && commit > profile)
    }

    @Test
    fun coldHarnessAndUbuntuTerminalRecoverBeforeMountingTheSelectedPackage() {
        val start = body("fun startHarness(")
        assertTrue(start.indexOf("recoverPendingHarnessSwitch(\"start\")") in
            0 until start.indexOf("harnessVersions.verify(selectedHarness)"))
        assertFalse(start.contains("runCatching { harnessVersions.rollbackSwitch"))
        val terminal = body("fun createTerminal(")
        assertTrue(terminal.indexOf("recoverPendingHarnessSwitch(\"terminal\")") in
            0 until terminal.indexOf("terminals.create("))
    }

    @Test
    fun failedSwitchKeepsItsRecoveryMarkerUntilTheOldProfileAndServiceAreRestored() {
        val manage = body("fun manageHarnessVersions(")
        val rollback = manage.indexOf("rollbackSwitch()")
        assertTrue(rollback >= 0)
        val profile = manage.indexOf("harnessVersions.prepareProfile()", rollback)
        val restart = manage.indexOf("if (wasRunning) supervisor.startHarness()", rollback)
        val commit = manage.indexOf("if (rolledBack) harnessVersions.commitSwitch()", rollback)
        assertTrue(profile > rollback && restart > profile && commit > restart)
    }

    private fun body(signature: String): String {
        val relative = "src/main/java/io/deepseekharness/mobile/runtime/MobileRuntimeController.kt"
        val source = listOf(File(relative), File("app/$relative"), File("android/app/$relative"))
            .first { it.isFile }.readText()
        val start = source.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        return source.substring(start, source.indexOf("\n    }", start))
    }
}
