package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeLaunchProfilePolicyTest {
    private val requiredMount = ProotBindMount("/dev", "/dev")
    private val userDirectoryMount = ProotBindMount("/storage/emulated/0/Download", "/mnt/user/1")

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
    fun `proot failure drops optional user directories without dropping required mounts`() {
        val profile = ProotLaunchProfile(false, listOf(requiredMount, userDirectoryMount))
        val result = ProcessProbeResult(1, false, "proot error: bind failed")

        assertEquals(
            listOf(profile.copy(bindMounts = listOf(requiredMount))),
            prootProfileFallbacks(profile, result, commandCanFail = true),
        )
    }

    @Test
    fun `probe timeout drops optional user directories without dropping required mounts`() {
        val profile = ProotLaunchProfile(false, listOf(requiredMount, userDirectoryMount))
        val result = ProcessProbeResult(exitCode = null, timedOut = true, output = "")

        assertEquals(
            listOf(profile.copy(bindMounts = listOf(requiredMount))),
            prootProfileFallbacks(profile, result, commandCanFail = true),
        )
    }

    @Test
    fun `command failure does not trigger an unrelated mount retry`() {
        val profile = ProotLaunchProfile(false, listOf(requiredMount, userDirectoryMount))
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

    /**
     * 投递区与用户目录同时在场时，两种可选绑定各自生成一个回退档，且都保住必需绑定。
     *
     * 这条用例钉住「可选能力不能拖垮会话启动」：任何一次 PRoot 失败都必须能找到一份
     * 少掉某一类可选绑定的启动档，而不是只剩「全部撤掉」或「全部保留」两个极端。
     */
    @Test
    fun `optional bindings are dropped independently of each other`() {
        val mailboxMount = ProotBindMount("/storage/emulated/0/Documents/DSH/inbox", "/mnt/inbox")
        val profile = ProotLaunchProfile(false, listOf(requiredMount, mailboxMount, userDirectoryMount))
        val result = ProcessProbeResult(1, false, "proot error: bind failed")

        val fallbacks = prootProfileFallbacks(profile, result, commandCanFail = true)

        assertEquals(2, fallbacks.size)
        assertEquals(
            listOf(listOf(requiredMount, userDirectoryMount), listOf(requiredMount, mailboxMount)),
            fallbacks.map { it.bindMounts },
        )
        // `/sdcard` 整体绑定已被白名单取代：它的固定挂载点不再出现在任何回退档里。
        assertEquals(0, fallbacks.count { fallback -> fallback.bindMounts.any { it.target == "/sdcard" } })
    }
}
