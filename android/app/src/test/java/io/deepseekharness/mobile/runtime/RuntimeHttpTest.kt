package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
