package io.deepseekharness.mobile.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStartCancellationTest {
    @Test
    fun cancellationIsVisibleToAnActiveStartAndClearedForTheNextStart() {
        val cancellation = RuntimeStartCancellation()

        assertTrue(cancellation.tryBegin())
        assertFalse(cancellation.tryBegin())
        assertTrue(cancellation.request())
        assertTrue(cancellation.isRequested())

        cancellation.finish()

        assertFalse(cancellation.isRequested())
        assertFalse(cancellation.request())
        assertTrue(cancellation.tryBegin())
        assertFalse(cancellation.isRequested())
    }

    @Test
    fun processProbeChecksCancellationWhileTheProcessIsStillRunning() {
        val process = WaitingProcess()
        var checks = 0

        val failure = assertThrows(RuntimeFailure::class.java) {
            ProcessProbe.run(
                spec = RuntimeLaunchSpec(listOf("unused"), emptyMap()),
                workingDirectory = File("."),
                timeoutSeconds = 30,
                externalCancellation = { ++checks >= 2 },
                processStarter = { _, _ -> process },
            )
        }

        assertTrue(checks >= 2)
        assertFalse(process.isAlive)
        assertTrue(failure.code == RuntimeSupervisor.START_CANCELLED_CODE)
    }

    private class WaitingProcess : Process() {
        private var alive = true

        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { alive = false }
        override fun destroyForcibly(): Process = apply { alive = false }
        override fun isAlive(): Boolean = alive
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            Thread.sleep(minOf(unit.toMillis(timeout), 5L))
            return !alive
        }
    }
}
