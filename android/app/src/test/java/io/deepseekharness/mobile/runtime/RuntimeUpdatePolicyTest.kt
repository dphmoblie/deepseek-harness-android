package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeUpdatePolicyTest {
    @Test
    fun detectsChangedBundledRootfs() {
        assertTrue(RuntimeUpdatePolicy.isAvailable("a".repeat(64), "b".repeat(64)))
    }

    @Test
    fun ignoresMatchingOrUnavailableManifests() {
        assertFalse(RuntimeUpdatePolicy.isAvailable("a".repeat(64), "a".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable(null, "a".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable("a".repeat(64), null))
    }
}
