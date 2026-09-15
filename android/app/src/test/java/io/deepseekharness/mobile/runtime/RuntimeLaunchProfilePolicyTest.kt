package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeLaunchProfilePolicyTest {
    private val requiredMount = ProotBindMount("/dev", "/dev")
    private val sdcardMount = ProotBindMount("/sdcard", "/sdcard")

    @Test
    fun `explicit seccomp failure retries the same mounts without seccomp`() {
        val profile = ProotLaunchProfile(false, listOf(requiredMount))
        val result = ProcessProbeResult(
            exitCode = 1,
            timedOut = false,
            output = "proot error: seccomp operation not permitted",
        )

        assertEquals(
            listOf(profile.copy(disableSeccomp = true)),
            prootProfileFallbacks(profile, result, commandCanFail = true),
        )
    }

    @Test
    fun `proot failure drops optional sdcard without dropping required mounts`() {
        val profile = ProotLaunchProfile(false, listOf(requiredMount, sdcardMount))
        val result = ProcessProbeResult(1, false, "proot error: bind failed")

        assertEquals(
            listOf(profile.copy(bindMounts = listOf(requiredMount))),
            prootProfileFallbacks(profile, result, commandCanFail = true),
        )
    }

    @Test
    fun `probe timeout drops optional sdcard without dropping required mounts`() {
        val profile = ProotLaunchProfile(false, listOf(requiredMount, sdcardMount))
        val result = ProcessProbeResult(exitCode = null, timedOut = true, output = "")

        assertEquals(
            listOf(profile.copy(bindMounts = listOf(requiredMount))),
            prootProfileFallbacks(profile, result, commandCanFail = true),
        )
    }

    @Test
    fun `command failure does not trigger an unrelated mount retry`() {
        val profile = ProotLaunchProfile(false, listOf(requiredMount, sdcardMount))
        val result = ProcessProbeResult(1, false, "Error [ERR_MODULE_NOT_FOUND]")

        assertEquals(emptyList<ProotLaunchProfile>(), prootProfileFallbacks(profile, result, commandCanFail = true))
        assertEquals(
            listOf(profile.copy(bindMounts = listOf(requiredMount))),
            prootProfileFallbacks(profile, result, commandCanFail = false),
        )
    }

    @Test
    fun `proot failure drops the optional mailbox mounts without dropping required ones`() {
        val profile = ProotLaunchProfile(
            false,
            listOf(
                requiredMount,
                ProotBindMount("/storage/emulated/0/Documents/DSH/inbox", "/mnt/inbox"),
                ProotBindMount("/storage/emulated/0/Documents/DSH/outbox", "/mnt/outbox"),
            ),
        )
        val result = ProcessProbeResult(1, false, "proot error: bind failed")

        assertEquals(
            listOf(profile.copy(bindMounts = listOf(requiredMount))),
            prootProfileFallbacks(profile, result, commandCanFail = true),
        )
    }
}
