package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class RuntimeHttpTest {
    @Test
    fun acceptsExactRemainingContentRange() {
        assertTrue(RuntimeHttp.contentRangeMatches("bytes 128-999/1000", 128, 1000))
        assertTrue(RuntimeHttp.contentRangeMatches("bytes 0-999/1000", 0, 1000))
    }

    @Test
    fun rejectsMismatchedOrPartialContentRanges() {
        listOf(
            "bytes 127-999/1000",
            "bytes 128-998/1000",
            "bytes 128-999/1001",
            "bytes */1000",
            "bytes 128-999/*",
            "bytes 9223372036854775808-999/1000",
            null,
        ).forEach { value ->
            assertFalse(RuntimeHttp.contentRangeMatches(value, 128, 1000))
        }
    }

    @Test
    fun allowsPinnedReleaseDownloadsToRedirectToAPublicCdn() {
        val redirected = RuntimeHttp.resolveRedirect(
            URI("https://github.com/example/project/releases/download/v1/rootfs.bundle"),
            "https://release-assets.githubusercontent.com/github-production-release-asset/rootfs.bundle",
        )

        assertEquals("release-assets.githubusercontent.com", redirected.host)
    }

    @Test
    fun rejectsRedirectsToLocalOrCleartextDestinations() {
        val source = URI("https://downloads.example.invalid/rootfs.bundle")

        assertThrows(RuntimeFailure::class.java) {
            RuntimeHttp.resolveRedirect(source, "https://127.0.0.1/rootfs.bundle")
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeHttp.resolveRedirect(source, "http://downloads.example.invalid/rootfs.bundle")
        }
    }
}
