package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCommandRunnerTest {
    private val runner = DeviceCommandRunner { _, _ -> }

    @Test
    fun uiDumpUsesRequestScopedFileAndCleansIt() {
        val requestId = "12345678-1234-1234-1234-123456789abc"
        val otherRequestId = "abcdef01-4321-4321-4321-cba987654321"
        val input = runner.buildInput(requestId, DeviceCommand.UI_DUMP, "")
        val otherInput = runner.buildInput(otherRequestId, DeviceCommand.UI_DUMP, "")
        val temporary = "/data/local/tmp/dsh-ui-$requestId.xml"

        assertTrue(input.contains("uiautomator dump $temporary && cat $temporary"))
        assertTrue(input.contains("trap 'rm -f $temporary' EXIT HUP INT TERM"))
        assertTrue(input.contains("rm -f $temporary; trap - EXIT HUP INT TERM"))
        assertTrue(input.endsWith("echo __DSH_END_${requestId}__:\$dsh_status\n"))
        assertFalse(otherInput.contains(temporary))
    }

    @Test(expected = RuntimeFailure::class)
    fun uiDumpRejectsUntrustedRequestId() {
        runner.buildInput("../../shared", DeviceCommand.UI_DUMP, "")
    }
}
