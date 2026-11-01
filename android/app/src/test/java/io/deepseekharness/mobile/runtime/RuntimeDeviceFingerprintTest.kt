package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDeviceFingerprintTest {
    @Test
    fun `device build token is stable and does not contain raw fingerprint`() {
        val raw = "vendor/device/product:15/AP3A.240905.015/1234567:user/release-keys"
        val token = runtimeDeviceFingerprintToken(raw)

        assertEquals(token, runtimeDeviceFingerprintToken(raw))
        assertTrue(token.startsWith("device-fp-v1:"))
        assertEquals(64, token.removePrefix("device-fp-v1:").length)
        assertTrue(token.removePrefix("device-fp-v1:").all { it in "0123456789abcdef" })
        assertFalse(token.contains(raw))
    }

    @Test
    fun `different build fingerprints invalidate the token`() {
        assertNotEquals(
            runtimeDeviceFingerprintToken("vendor/device/product:15/one"),
            runtimeDeviceFingerprintToken("vendor/device/product:15/two"),
        )
    }
}
