package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上次完整启动成功」缓存的用例（内存替身，不碰 SharedPreferences）。
 *
 * 重点不是「能存能读」，而是**信任边界**：令牌只对同一个 `profileKey` 有效、坏值/旧版本/
 * 读失败一律按「未验证」处理、失效之后连写失败都不能把信任恢复回来。这几条决定了
 * 「跳过 PRoot 自检」会不会变成「真实损坏被缓存掩盖」。
 */
class RuntimeGuestVerificationTest {
    private val profileKey =
        "37:34:google/redfin:arm64-v8a:runtime-1:0.2.14:" + "a".repeat(64) + ":sandbox-bind-v1:mailbox-off:userdirs-0"

    @Test
    fun `missing token never skips the probe`() {
        assertFalse(RuntimeGuestVerification(FakeStorage()).allowsSkip(profileKey))
    }

    @Test
    fun `a verified token only covers its own profile key`() {
        val storage = FakeStorage()
        val cache = RuntimeGuestVerification(storage)
        cache.markVerified(profileKey)

        assertTrue(cache.allowsSkip(profileKey))
        // 运行时更新、rootfs 摘要变化、设备升级都会让 profileKey 变：此时必须重新探测。
        assertFalse(cache.allowsSkip("$profileKey:next-runtime"))
        assertFalse(cache.allowsSkip("37:35:google/redfin:arm64-v8a:runtime-1:0.2.14:" + "b".repeat(64)))
    }

    @Test
    fun `marking after an invalidation trusts the key again`() {
        val cache = RuntimeGuestVerification(FakeStorage())
        cache.markVerified(profileKey)
        cache.invalidate()
        assertFalse(cache.allowsSkip(profileKey))

        cache.markVerified(profileKey)
        assertTrue(cache.allowsSkip(profileKey))
    }

    /**
     * 失效写盘失败时，本次进程内也必须停止信任：磁盘上可能还留着上一次的令牌，
     * 只靠磁盘状态判断会让「启动失败之后的下一次启动」继续跳过自检。
     */
    @Test
    fun `invalidation stops trust even when the write fails`() {
        val storage = FakeStorage(failOnRemove = true)
        val cache = RuntimeGuestVerification(storage)
        cache.markVerified(profileKey)
        assertTrue(cache.allowsSkip(profileKey))

        cache.invalidate()

        assertFalse(cache.allowsSkip(profileKey))
        // 进程内兜底不改变磁盘状态这个事实：这正是「跨进程只能等下次失败再清」的边界。
        assertEquals(RuntimeGuestVerification.tokenFor(profileKey), storage.token)
    }

    @Test
    fun `invalidation removes the token from storage`() {
        val storage = FakeStorage()
        val cache = RuntimeGuestVerification(storage)
        cache.markVerified(profileKey)
        cache.invalidate()

        assertNull(storage.token)
        assertFalse(cache.allowsSkip(profileKey))
    }

    @Test
    fun `unreadable storage falls back to probing`() {
        val cache = RuntimeGuestVerification(FakeStorage(failOnRead = true))
        assertFalse(cache.allowsSkip(profileKey))
    }

    @Test
    fun `a failed cache write never prevents the start from reporting success`() {
        val cache = RuntimeGuestVerification(FakeStorage(failOnWrite = true))
        cache.markVerified(profileKey)
        // 写不进去 = 下次多探测一次，而不是本次启动失败（markVerified 不抛）。
        assertFalse(cache.allowsSkip(profileKey))
    }

    @Test
    fun `tokens from older cache versions are not trusted`() {
        val storage = FakeStorage().apply { token = "verified-v0:$profileKey" }
        assertFalse(RuntimeGuestVerification(storage).allowsSkip(profileKey))
    }

    @Test
    fun `only the exact token shape is trusted`() {
        // 键串出现但形态不对（例如旧版本把 key 与标记顺序拼反）：一律重新探测。
        assertFalse(RuntimeGuestVerification(FakeStorage().apply { token = "$profileKey:verified" }).allowsSkip(profileKey))
        assertFalse(RuntimeGuestVerification(FakeStorage().apply { token = "" }).allowsSkip(profileKey))
        assertFalse(RuntimeGuestVerification.matches(null, profileKey))
        assertTrue(RuntimeGuestVerification.matches(RuntimeGuestVerification.tokenFor(profileKey), profileKey))
    }

    private class FakeStorage(
        private val failOnRead: Boolean = false,
        private val failOnWrite: Boolean = false,
        private val failOnRemove: Boolean = false,
    ) : RuntimeGuestVerification.Storage {
        var token: String? = null

        override fun readToken(): String? {
            if (failOnRead) throw IllegalStateException("preferences unavailable")
            return token
        }

        override fun writeToken(value: String?) {
            if (value == null) {
                if (failOnRemove) throw IllegalStateException("preferences unavailable")
                token = null
                return
            }
            if (failOnWrite) throw IllegalStateException("preferences unavailable")
            token = value
        }
    }
}
