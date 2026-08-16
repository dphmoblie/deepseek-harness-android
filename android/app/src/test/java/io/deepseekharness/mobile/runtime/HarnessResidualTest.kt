package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessResidualTest {
    @Test
    fun parsesValidPid() {
        assertEquals(1234, HarnessResidual.parsePid("1234"))
        assertEquals(5678, HarnessResidual.parsePid(" 5678\n"))
    }

    @Test
    fun rejectsInvalidPidContent() {
        assertNull(HarnessResidual.parsePid(""))
        assertNull(HarnessResidual.parsePid("abc"))
        assertNull(HarnessResidual.parsePid("-5"))
        assertNull(HarnessResidual.parsePid("0"))
        assertNull(HarnessResidual.parsePid("1"))
        assertNull(HarnessResidual.parsePid("12 34"))
    }

    @Test
    fun matchesProotRunnerExecutable() {
        val runner = "/data/user/0/io.deepseekharness.mobile/no_backup/dsh-runner/proot"
        val cmdline = "$runner\u0000-r\u0000/data/user/0/io.deepseekharness.mobile/no_backup/dsh-runtime/current"

        assertTrue(HarnessResidual.isProotProcess(cmdline, runner))
    }

    @Test
    fun rejectsMismatchedExecutable() {
        val runner = "/data/user/0/io.deepseekharness.mobile/no_backup/dsh-runner/proot"

        assertFalse(HarnessResidual.isProotProcess("", runner))
        assertFalse(HarnessResidual.isProotProcess("/system/bin/sh\u0000-c\u0000true", runner))
        // pid 复用防护：其他进程恰好以运行器路径开头但不是它
        assertFalse(HarnessResidual.isProotProcess("${runner}-extra\u0000-r", runner))
        assertFalse(HarnessResidual.isProotProcess("$runner\u0000-r", "/other/path/proot"))
    }
}
