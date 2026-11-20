package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class LocalDeviceFileStoreTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private fun rootResolver(base: Path): (DeviceFilePolicy.Root) -> Path = { root ->
        base.resolve(root.wireValue)
    }

    @Test
    fun `mkdir write read list work directly without Shizuku`() {
        val base = temporaryFolder.newFolder("dsh-mailbox").toPath()
        val resolver = rootResolver(base)

        val mkdir = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_MKDIR,
            """{"root":"outbox","path":"reports/2026"}""",
            resolver,
        )!!
        assertTrue(mkdir.ok)
        assertEquals(0, mkdir.exitCode)

        val payload = Base64.getEncoder().encodeToString("hello 投递区".toByteArray(Charsets.UTF_8))
        val write = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_WRITE,
            """{"root":"outbox","path":"reports/2026/note.txt","contentBase64":"$payload"}""",
            resolver,
        )!!
        assertTrue(write.ok)

        val duplicate = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_WRITE,
            """{"root":"outbox","path":"reports/2026/note.txt","contentBase64":"$payload"}""",
            resolver,
        )!!
        assertFalse(duplicate.ok)
        assertEquals("DEVICE_FILE_EXISTS", duplicate.errorCode)

        val updatedPayload = Base64.getEncoder().encodeToString("updated".toByteArray(Charsets.UTF_8))
        val overwrite = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_UPLOAD,
            """{"root":"outbox","path":"reports/2026/note.txt","contentBase64":"$updatedPayload","overwrite":true}""",
            resolver,
        )!!
        assertTrue(overwrite.ok)

        val read = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_DOWNLOAD,
            """{"root":"outbox","path":"reports/2026/note.txt"}""",
            resolver,
        )!!
        assertTrue(read.ok)
        assertEquals(updatedPayload, read.text)

        val listRoot = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_LIST,
            """{"root":"outbox","path":""}""",
            resolver,
        )!!
        assertTrue(listRoot.ok)
        assertEquals("reports/", listRoot.text)

        val listNested = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_LIST,
            """{"root":"outbox","path":"reports/2026"}""",
            resolver,
        )!!
        assertTrue(listNested.ok)
        assertEquals("note.txt", listNested.text)
    }

    @Test
    fun `rejects missing files, oversized files, and invalid paths cleanly`() {
        val base = temporaryFolder.newFolder("dsh-mailbox-limits").toPath()
        val inbox = base.resolve("inbox")
        Files.createDirectories(inbox)
        val resolver = rootResolver(base)

        val missing = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_READ,
            """{"root":"inbox","path":"nope.txt"}""",
            resolver,
        )!!
        assertFalse(missing.ok)
        assertEquals("DEVICE_FILE_NOT_FOUND", missing.errorCode)

        Files.write(inbox.resolve("big.bin"), ByteArray(DeviceFilePolicy.MAX_FILE_BYTES + 1))
        val tooLarge = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_READ,
            """{"root":"inbox","path":"big.bin"}""",
            resolver,
        )!!
        assertFalse(tooLarge.ok)
        assertEquals("DEVICE_FILE_TOO_LARGE", tooLarge.errorCode)

        val invalid = assertThrows(RuntimeFailure::class.java) {
            LocalDeviceFileStore.tryExecute(
                DeviceCommand.FILE_READ,
                """{"root":"inbox","path":"../escape.txt"}""",
                resolver,
            )
        }
        assertEquals("DEVICE_FILE_INVALID", invalid.code)
    }

    @Test
    fun `rejects symlink traversal inside mailbox with DEVICE_FILE_ESCAPE`() {
        val base = temporaryFolder.newFolder("dsh-mailbox-symlink").toPath()
        val inbox = base.resolve("inbox")
        val outside = temporaryFolder.newFolder("outside").toPath()
        Files.createDirectories(inbox)
        Files.write(outside.resolve("secret.txt"), "top-secret".toByteArray())
        try {
            Files.createSymbolicLink(inbox.resolve("escape"), outside)
        } catch (error: Exception) {
            assumeNoException(error)
        }

        val escaped = LocalDeviceFileStore.tryExecute(
            DeviceCommand.FILE_READ,
            """{"root":"inbox","path":"escape/secret.txt"}""",
            rootResolver(base),
        )!!
        assertFalse(escaped.ok)
        assertEquals("DEVICE_FILE_ESCAPE", escaped.errorCode)
    }
}
