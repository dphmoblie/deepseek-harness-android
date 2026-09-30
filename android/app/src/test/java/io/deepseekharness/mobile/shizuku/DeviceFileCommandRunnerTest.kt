package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DeviceFileCommandRunnerTest {
    private val requestId = "12345678-1234-1234-1234-123456789abc"
    private val runner = DeviceCommandRunner { _, _ -> }

    @Test
    fun fileCommandsUseFixedDeliveryRootsAndNoFollowChecks() {
        val param = "{\"root\":\"inbox\",\"path\":\"reports/a.txt\"}"
        val list = runner.buildInput(requestId, DeviceCommand.FILE_LIST, param)
        val read = runner.buildInput(requestId, DeviceCommand.FILE_READ, param)
        assertTrue(list.contains("/storage/emulated/0/Documents/DSH/inbox"))
        assertTrue(list.contains("readlink -f"))
        assertTrue(list.contains("[ -L \"\$dsh_target\" ]"))
        assertTrue(read.contains("toybox base64"))
        assertTrue(read.contains(DeviceFilePolicy.MAX_FILE_BYTES.toString()))
    }

    @Test
    fun fileWriteUsesAtomicTemporaryFileAndDoesNotOverwriteByDefault() {
        val content = Base64.getEncoder().encodeToString("hello".toByteArray())
        val input = runner.buildInput(
            requestId,
            DeviceCommand.FILE_UPLOAD,
            "{\"root\":\"outbox\",\"path\":\"a.txt\",\"contentBase64\":\"$content\"}",
        )
        assertTrue(input.contains(".dsh-upload-"))
        assertTrue(input.contains("base64 -d"))
        assertTrue(input.contains("exit 7"))
    }

    @Test(expected = RuntimeFailure::class)
    fun fileCommandRejectsAbsolutePath() {
        runner.buildInput(
            requestId,
            DeviceCommand.FILE_READ,
            "{\"root\":\"inbox\",\"path\":\"/storage/emulated/0/Documents/DSH/outbox/a\"}",
        )
    }
}
