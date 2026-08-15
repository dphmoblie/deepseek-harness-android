package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BestEffortCleanupTest {
    @Test
    fun runsTerminalAndServiceCleanupAfterProcessStopFailure() {
        val calls = mutableListOf<String>()
        val failure = assertThrows(RuntimeFailure::class.java) {
            BestEffortCleanup.runAll(
                {
                    calls.add("supervisor")
                    throw RuntimeFailure("STOP_FAILED", "stop failed")
                },
                { calls.add("ubuntu-pty") },
                { calls.add("shizuku-user-service") },
            )
        }
        assertEquals("STOP_FAILED", failure.code)
        assertEquals(listOf("supervisor", "ubuntu-pty", "shizuku-user-service"), calls)
    }
}
