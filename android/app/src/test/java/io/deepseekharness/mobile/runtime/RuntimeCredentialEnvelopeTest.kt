package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class RuntimeCredentialEnvelopeTest {
    @Test
    fun roundTripsBoundedCiphertext() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(32) { (it + 10).toByte() }

        val decoded = RuntimeCredentialEnvelope.decode(RuntimeCredentialEnvelope.encode(iv, ciphertext))

        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun rejectsMalformedOrUnsupportedEnvelopes() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCredentialEnvelope.decode("not-base64")
        }
        val unsupported = ByteArray(30).apply {
            this[0] = 2
            this[1] = 12
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCredentialEnvelope.decode(Base64.getEncoder().encodeToString(unsupported))
        }
    }
}
