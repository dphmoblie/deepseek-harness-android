package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DeviceFilePolicyTest {
    @Test
    fun acceptsOnlyDeliveryRootsAndRelativePaths() {
        val request = DeviceFilePolicy.parse(
            "{\"root\":\"inbox\",\"path\":\"reports/今日.md\",\"overwrite\":true}",
        )
        assertEquals(DeviceFilePolicy.Root.INBOX, request.root)
        assertEquals("reports/今日.md", request.path)
        assertTrue(request.overwrite)
    }

    @Test
    fun rejectsPathTraversalShellCharactersAndUnknownFields() {
        listOf(
            "{\"root\":\"/\",\"path\":\"a\"}",
            "{\"root\":\"outbox\",\"path\":\"../private\"}",
            "{\"root\":\"outbox\",\"path\":\"a;reboot\"}",
            "{\"root\":\"outbox\",\"path\":\"a\\\\b\"}",
            "{\"root\":\"outbox\",\"path\":\"a\",\"extra\":1}",
        ).forEach { value ->
            try {
                DeviceFilePolicy.parse(value)
                throw AssertionError("应拒绝文件请求：$value")
            } catch (_: RuntimeFailure) {
                // 预期拒绝，不能进入设备 Shell。
            }
        }
    }

    @Test
    fun enforcesDecodedFileSizeAndStrictBase64() {
        val content = Base64.getEncoder().encodeToString(ByteArray(DeviceFilePolicy.MAX_FILE_BYTES))
        val request = DeviceFilePolicy.parse(
            "{\"root\":\"outbox\",\"path\":\"a.bin\",\"contentBase64\":\"$content\"}",
            requireContent = true,
        )
        assertEquals(content, request.contentBase64)

        val tooLarge = Base64.getEncoder().encodeToString(ByteArray(DeviceFilePolicy.MAX_FILE_BYTES + 1))
        listOf(
            "{\"root\":\"outbox\",\"path\":\"a.bin\",\"contentBase64\":\"$tooLarge\"}",
            "{\"root\":\"outbox\",\"path\":\"a.bin\",\"contentBase64\":\"Zm9v!\"}",
        ).forEach { value ->
            try {
                DeviceFilePolicy.parse(value, requireContent = true)
                throw AssertionError("应拒绝过大或非法 Base64")
            } catch (_: RuntimeFailure) {
                // 预期拒绝。
            }
        }
    }

    @Test
    fun allowsEmptyDirectoryPathOnlyForListing() {
        val root = DeviceFilePolicy.parse("{\"root\":\"inbox\",\"path\":\"\"}")
        assertEquals("", root.path)
        assertFalse(root.overwrite)
    }
}
