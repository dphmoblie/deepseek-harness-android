package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证密码的纯逻辑单测：口令边界、PBKDF2 可复现性、常量时间比对与锁定窗口。
 *
 * 全部是 JVM 断言，不碰 SharedPreferences、不需要设备——这正是把规则与持久化分开的原因。
 */
class AccessibilityPasswordPolicyTest {
    private val policy = AccessibilityPasswordPolicy

    @Test
    fun passwordLengthBoundariesAreEnforced() {
        assertTrue(policy.validPassword("abc123"))
        assertTrue(policy.validPassword("a".repeat(policy.MAX_LENGTH)))
        assertFalse(policy.validPassword("a".repeat(policy.MIN_LENGTH - 1)))
        assertFalse(policy.validPassword("a".repeat(policy.MAX_LENGTH + 1)))
        assertFalse(policy.validPassword(""))
    }

    @Test
    fun passwordRejectsControlCharactersBlankOnlyAndSurroundingWhitespace() {
        // 中间的空格是用户自己的选择，允许。
        assertTrue(policy.validPassword("abc 123"))
        assertFalse(policy.validPassword("abc\t12"))
        assertFalse(policy.validPassword("abc\n12"))
        assertFalse(policy.validPassword("abc\u007f12"))
        assertFalse(policy.validPassword(" ".repeat(policy.MIN_LENGTH)))
        // 首尾空白几乎都是粘贴带进来的：原样接受只会让用户反复收到「密码不正确」。
        assertFalse(policy.validPassword(" abc123"))
        assertFalse(policy.validPassword("abc123 "))
        assertFalse(policy.validPassword("\nabc123"))
    }

    @Test
    fun deriveIsReproducibleForSameSaltAndDiffersPerSaltOrIterations() {
        val salt = ByteArray(policy.SALT_BYTES) { it.toByte() }
        val other = ByteArray(policy.SALT_BYTES) { (it + 1).toByte() }
        val first = policy.derive("abc123", salt, policy.DEFAULT_ITERATIONS)
        assertArrayEquals(first, policy.derive("abc123", salt, policy.DEFAULT_ITERATIONS))
        assertEquals(policy.KEY_BITS / 8, first.size)

        assertFalse(first.contentEquals(policy.derive("abc123", other, policy.DEFAULT_ITERATIONS)))
        assertFalse(first.contentEquals(policy.derive("abc123", salt, policy.DEFAULT_ITERATIONS + 1)))
        assertFalse(first.contentEquals(policy.derive("abc124", salt, policy.DEFAULT_ITERATIONS)))

        val fresh = policy.newSalt()
        assertEquals(policy.SALT_BYTES, fresh.size)
        assertFalse(fresh.contentEquals(policy.newSalt()))
    }

    @Test
    fun verifyAcceptsCorrectPasswordAndRejectsWrongOrTamperedRecords() {
        val salt = policy.newSalt()
        val hash = policy.derive("abc123", salt, policy.DEFAULT_ITERATIONS)
        val record = AccessibilityPasswordPolicy.PasswordRecord(salt, hash, policy.DEFAULT_ITERATIONS)
        assertTrue(policy.verify("abc123", record))
        assertFalse(policy.verify("abc124", record))
        assertFalse(policy.verify("ABC123", record))

        val tampered = hash.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(policy.verify("abc123", AccessibilityPasswordPolicy.PasswordRecord(salt, tampered, policy.DEFAULT_ITERATIONS)))
        val tamperedSalt = salt.copyOf().also { it[policy.SALT_BYTES - 1] = (it[policy.SALT_BYTES - 1].toInt() xor 0x01).toByte() }
        assertFalse(policy.verify("abc123", AccessibilityPasswordPolicy.PasswordRecord(tamperedSalt, hash, policy.DEFAULT_ITERATIONS)))
        assertFalse(policy.verify("abc123", AccessibilityPasswordPolicy.PasswordRecord(salt, hash, policy.DEFAULT_ITERATIONS + 1)))
    }

    @Test
    fun lockRemainingMsCoversEveryBoundary() {
        val lockMs = policy.LOCK_MS
        // 第 4 次失败还不锁，第 5 次才锁。
        assertTrue((0 until policy.MAX_FAILURES).all { policy.lockRemainingMs(it, 1_000L, 1_000L) == 0L })
        assertEquals(lockMs, policy.lockRemainingMs(policy.MAX_FAILURES, 1_000L, 1_000L))
        assertEquals(lockMs - 1, policy.lockRemainingMs(policy.MAX_FAILURES, 1_000L, 1_001L))
        assertEquals(1L, policy.lockRemainingMs(policy.MAX_FAILURES, 1_000L, 1_000L + lockMs - 1))
        assertEquals(0L, policy.lockRemainingMs(policy.MAX_FAILURES, 1_000L, 1_000L + lockMs))
        assertEquals(0L, policy.lockRemainingMs(policy.MAX_FAILURES, 1_000L, 1_000L + lockMs + 60_000L))
        // 失败次数只增不减地继续算：第 6 次失败仍然是「再锁 30 秒」。
        assertEquals(lockMs, policy.lockRemainingMs(policy.MAX_FAILURES + 1, 2_000L, 2_000L))
        // 时钟回拨按「刚失败」处理：宁可多锁一次，也不要因为时间跳变放行。
        assertEquals(lockMs, policy.lockRemainingMs(policy.MAX_FAILURES, 5_000L, 1_000L))

        // 文案用的整秒数向上取整：剩 1ms 也显示 1 秒，不会显示 0 秒。
        assertEquals(1L, policy.lockedSeconds(1L))
        assertEquals(1L, policy.lockedSeconds(1_000L))
        assertEquals(2L, policy.lockedSeconds(1_001L))
        assertEquals(lockMs / 1000, policy.lockedSeconds(lockMs))
    }
}
