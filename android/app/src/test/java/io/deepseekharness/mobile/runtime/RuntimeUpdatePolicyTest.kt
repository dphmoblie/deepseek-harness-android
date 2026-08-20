package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeUpdatePolicyTest {
    private val runtimeId = "ubuntu-24.04-arm64-deepseek-harness"

    @Test
    fun detectsNewerBundledReleaseOfTheSameRuntime() {
        assertTrue(
            RuntimeUpdatePolicy.isAvailable(
                runtimeId,
                "0.1.7",
                "a".repeat(64),
                runtimeId,
                "0.1.8",
                "b".repeat(64),
            ),
        )
    }

    @Test
    fun doesNotDowngradeANewerRemoteRelease() {
        assertFalse(
            RuntimeUpdatePolicy.isAvailable(
                runtimeId,
                "0.1.9",
                "a".repeat(64),
                runtimeId,
                "0.1.8",
                "b".repeat(64),
            ),
        )
    }

    @Test
    fun ignoresDifferentRuntimeIdsEqualVersionsAndUnavailableManifests() {
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.1.7", "a".repeat(64), "other", "0.1.8", "b".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.1.8", "a".repeat(64), runtimeId, "0.1.8", "b".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable(null, "0.1.7", "a".repeat(64), runtimeId, "0.1.8", "b".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, "custom-label", "a".repeat(64), runtimeId, "0.1.8", "b".repeat(64)))
    }

    @Test
    fun comparesPrereleasesBeforeStableReleases() {
        assertTrue(RuntimeUpdatePolicy.compareVersions("0.1.8", "0.1.8-rc.6") > 0)
        assertTrue(RuntimeUpdatePolicy.compareVersions("0.1.9", "0.1.10") < 0)
        assertTrue(RuntimeUpdatePolicy.compareVersions("2026.08.18", "2026.08.17") > 0)
        assertTrue(RuntimeUpdatePolicy.compareVersions("custom", "0.1.8") == 0)
    }
}
