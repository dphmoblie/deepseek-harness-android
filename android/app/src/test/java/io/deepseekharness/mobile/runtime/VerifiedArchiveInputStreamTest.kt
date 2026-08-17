package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class VerifiedArchiveInputStreamTest {
    @Test
    fun `drains to eof and verifies before closing the source`() {
        val payload = "verified archive payload".toByteArray()
        val source = TrackingInputStream(payload)

        VerifiedArchiveInputStream(source, payload.size.toLong(), sha256(payload)).use { input ->
            assertEquals(payload[0].toInt() and 0xff, input.read())
            input.drainAndVerify()

            assertTrue(source.eofObserved)
            assertFalse(source.closed)
        }

        assertTrue(source.closed)
    }

    @Test
    fun `hashes bytes consumed through skip`() {
        val payload = ByteArray(32 * 1024) { index -> (index and 0xff).toByte() }

        VerifiedArchiveInputStream(
            ByteArrayInputStream(payload),
            payload.size.toLong(),
            sha256(payload),
        ).use { input ->
            assertEquals(8_193L, input.skip(8_193L))
            input.drainAndVerify()
        }
    }

    @Test
    fun `rejects an archive shorter than its declared size`() {
        val payload = "short".toByteArray()

        assertFailure("ARCHIVE_SOURCE_SIZE_MISMATCH") {
            VerifiedArchiveInputStream(
                ByteArrayInputStream(payload),
                payload.size + 1L,
                sha256(payload),
            ).use { it.drainAndVerify() }
        }
    }

    @Test
    fun `rejects an archive larger than its declared size`() {
        val payload = "too-large".toByteArray()

        assertFailure("ARCHIVE_SOURCE_SIZE_MISMATCH") {
            VerifiedArchiveInputStream(
                ByteArrayInputStream(payload),
                payload.size - 1L,
                sha256(payload),
            ).use { it.drainAndVerify() }
        }
    }

    @Test
    fun `rejects a digest mismatch after reaching eof`() {
        val payload = "archive".toByteArray()
        val differentPayload = "changed".toByteArray()

        assertFailure("ARCHIVE_SOURCE_DIGEST_MISMATCH") {
            VerifiedArchiveInputStream(
                ByteArrayInputStream(payload),
                payload.size.toLong(),
                sha256(differentPayload),
            ).use { it.drainAndVerify() }
        }
    }

    @Test
    fun `honors cancellation while draining the source`() {
        val payload = "cancelled archive".toByteArray()

        assertFailure("INSTALL_CANCELLED") {
            VerifiedArchiveInputStream(
                ByteArrayInputStream(payload),
                payload.size.toLong(),
                sha256(payload),
            ).use { it.drainAndVerify { true } }
        }
    }

    private fun assertFailure(code: String, operation: () -> Unit) {
        val failure = assertThrows(RuntimeFailure::class.java, operation)
        assertEquals(code, failure.code)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set
        var eofObserved = false
            private set

        override fun read(): Int = super.read().also { result ->
            if (result < 0) eofObserved = true
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int =
            super.read(destination, offset, length).also { result ->
                if (result < 0) eofObserved = true
            }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
