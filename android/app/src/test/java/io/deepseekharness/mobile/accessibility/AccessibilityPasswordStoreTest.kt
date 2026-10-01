package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 「验证密码 → 白名单写入」这条链路的 store 级单测。
 *
 * 偏好文件用内存替身（[InMemoryPasswordStorage]），因此不需要设备、也不会污染真实配置；
 * 写入顺序故意走**生产代码里的那份** [AccessibilityWhitelistWritePolicy]，而不是在测试里另抄一遍，
 * 否则「先密码、后格式」的顺序漂移了这里也发现不了。
 */
class AccessibilityPasswordStoreTest {
    /** 内存偏好替身：语义与 SharedPreferences 一致——`write(null)` 等于删键，缺失回退默认值。 */
    private class InMemoryPasswordStorage : AccessibilityPasswordPreferences.Storage {
        private val values = mutableMapOf<String, Any>()

        val snapshot: Map<String, Any> get() = values.toMap()

        override fun readString(key: String): String? = values[key] as? String

        override fun readInt(key: String, fallback: Int): Int = values[key] as? Int ?: fallback

        override fun readLong(key: String, fallback: Long): Long = values[key] as? Long ?: fallback

        override fun writeString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }

        override fun writeInt(key: String, value: Int) {
            values[key] = value
        }

        override fun writeLong(key: String, value: Long) {
            values[key] = value
        }
    }

    private class Harness {
        val storage = InMemoryPasswordStorage()
        var clockMs = 10_000L
        val guard = AccessibilityPasswordGuard(AccessibilityPasswordPreferences(storage)) { clockMs }

        /** 落盘后的白名单（模拟 `AccessibilityAutomationStore` 里那份私有偏好里的集合）。 */
        var whitelist: List<String> = emptyList()

        /** 与 `setAllowedPackages` 同序：先过密码，再规范化并落盘。异常时白名单保持原样。 */
        fun write(packages: List<String>, password: String?): List<String> {
            val normalized = AccessibilityWhitelistWritePolicy.authorize(guard, packages, password)
            whitelist = normalized
            return normalized
        }
    }

    private fun codeOf(block: () -> Unit): String = try {
        block()
        "NO_FAILURE"
    } catch (failure: AccessibilityGuardException) {
        failure.code
    }

    @Test
    fun unconfiguredWhitelistWriteIsUnrestricted() {
        val harness = Harness()
        assertFalse(harness.guard.configured())
        // 老用户（没设过密码）的行为不变：不带密码照写。
        val written = harness.write(listOf("com.example.reader"), null)
        assertEquals(listOf("com.example.reader", AccessibilityAutomationPolicy.SELF_PACKAGE).sorted(), written)
        // 没配置时多传一个密码也不该改变结果（密码门槛只由「是否已配置」决定）。
        harness.write(listOf("com.example.writer"), "whatever")
        assertTrue(harness.whitelist.contains(AccessibilityAutomationPolicy.SELF_PACKAGE))
    }

    @Test
    fun configuredWhitelistWriteRejectsMissingAndWrongPasswordAndAcceptsTheRightOne() {
        val harness = Harness()
        harness.write(listOf("com.example.reader"), null)
        harness.guard.set("abc123", null)
        assertTrue(harness.guard.configured())

        assertEquals(AccessibilityGuardCodes.REQUIRED, codeOf { harness.write(listOf("com.example.reader", "com.example.writer"), null) })
        assertEquals(listOf("com.example.reader", AccessibilityAutomationPolicy.SELF_PACKAGE).sorted(), harness.whitelist)

        assertEquals(AccessibilityGuardCodes.INVALID, codeOf { harness.write(listOf("com.example.reader", "com.example.writer"), "abc124") })
        assertEquals(listOf("com.example.reader", AccessibilityAutomationPolicy.SELF_PACKAGE).sorted(), harness.whitelist)

        harness.write(listOf("com.example.reader", "com.example.writer"), "abc123")
        assertEquals(
            listOf("com.example.reader", "com.example.writer", AccessibilityAutomationPolicy.SELF_PACKAGE).sorted(),
            harness.whitelist,
        )
        // 成功一次就把失败计数清零。
        assertEquals(0, harness.storage.readInt(AccessibilityPasswordPreferences.KEY_FAIL_COUNT, 0))
    }

    @Test
    fun fifthFailureLocksTheGateAndEvenTheRightPasswordIsRejectedUntilItExpires() {
        val harness = Harness()
        harness.guard.set("abc123", null)

        // 前 4 次失败仍然是「密码不正确」。
        repeat(AccessibilityPasswordPolicy.MAX_FAILURES - 1) {
            assertEquals(AccessibilityGuardCodes.INVALID, codeOf { harness.write(listOf("com.example.reader"), "wrong") })
        }
        assertEquals(0L, AccessibilityPasswordPolicy.lockRemainingMs(4, harness.clockMs, harness.clockMs))

        // 第 5 次失败触发锁定（这一次本身仍如实报「不正确」）。
        assertEquals(AccessibilityGuardCodes.INVALID, codeOf { harness.write(listOf("com.example.reader"), "wrong") })
        val lockedMessage = try {
            harness.write(listOf("com.example.reader"), "abc123")
            "NO_FAILURE"
        } catch (failure: AccessibilityGuardException) {
            assertEquals(AccessibilityGuardCodes.LOCKED, failure.code)
            failure.message.orEmpty()
        }
        // 锁定文案要给出剩余秒数，而不是只说「已锁定」；30 秒的窗口从最后一次失败算起。
        assertTrue(lockedMessage.contains("30"))
        assertTrue(harness.whitelist.isEmpty())

        // 锁定期间正确密码也进不来。
        assertEquals(AccessibilityGuardCodes.LOCKED, codeOf { harness.write(listOf("com.example.reader"), "abc123") })

        // 到期后恢复，并且尝试次数重新给满：又能用满 4 次机会（第 5 次才再锁）。
        harness.clockMs += AccessibilityPasswordPolicy.LOCK_MS
        repeat(4) { assertEquals(AccessibilityGuardCodes.INVALID, codeOf { harness.write(listOf("com.example.reader"), "wrong") }) }
        harness.write(listOf("com.example.reader"), "abc123")
        assertTrue(harness.whitelist.contains("com.example.reader"))
        assertEquals(0, harness.storage.readInt(AccessibilityPasswordPreferences.KEY_FAIL_COUNT, 0))
    }

    @Test
    fun weakPasswordIsRejectedAndTheOldPasswordSurvives() {
        val harness = Harness()
        harness.guard.set("abc123", null)

        assertEquals(AccessibilityGuardCodes.WEAK, codeOf { harness.guard.set("abc12", "abc123") })
        assertEquals(AccessibilityGuardCodes.WEAK, codeOf { harness.guard.set("abc\t12", "abc123") })
        assertEquals(AccessibilityGuardCodes.WEAK, codeOf { harness.guard.set(" abc123", "abc123") })
        assertEquals(AccessibilityGuardCodes.WEAK, codeOf { harness.guard.set(" ".repeat(8), "abc123") })
        // 改密码要先过当前密码；错的一律无效，旧密码仍然有效。
        assertEquals(AccessibilityGuardCodes.INVALID, codeOf { harness.guard.set("newpass", "abc124") })
        harness.guard.set("newpass", "abc123")
        harness.write(listOf("com.example.reader"), "newpass")
        assertTrue(harness.whitelist.contains("com.example.reader"))
    }

    @Test
    fun firstTimeSetRejectsAStrayCurrentPasswordAsAPlainArgumentError() {
        val harness = Harness()
        val failure = assertThrows(IllegalArgumentException::class.java) { harness.guard.set("abc123", "whatever") }
        // 这不是「验证密码族」的失败，而是调用方把首次设置与修改搞混了：
        // 桥接层把它映射成 ACCESSIBILITY_CONFIG_INVALID，而不是自创一个 guard 码。
        assertFalse(failure is AccessibilityGuardException)
        assertFalse(harness.guard.configured())
    }

    @Test
    fun clearRequiresTheCurrentPasswordAndKeepsTheWhitelist() {
        val harness = Harness()
        harness.guard.set("abc123", null)
        val written = harness.write(listOf("com.example.reader"), "abc123")

        assertEquals(AccessibilityGuardCodes.INVALID, codeOf { harness.guard.clear("abc124") })
        assertTrue(harness.guard.configured())
        harness.guard.clear("abc123")
        assertFalse(harness.guard.configured())
        // 清密码只影响密码：白名单原样保留。
        assertEquals(written, harness.whitelist)
        harness.write(listOf("com.example.reader", "com.example.writer"), null)
        assertTrue(harness.whitelist.contains("com.example.writer"))
    }

    @Test
    fun corruptedRecordFailsClosedAndDeviceConfirmationIsTheWayOut() {
        val harness = Harness()
        harness.storage.writeString(AccessibilityPasswordPreferences.KEY_SALT, "not base64 !!")
        harness.storage.writeString(AccessibilityPasswordPreferences.KEY_HASH, "AAAA")
        // 键存在 => 已配置；内容坏掉 => 不许静默放行（否则白名单就悄悄失去门槛）。
        assertTrue(harness.guard.configured())
        assertEquals(AccessibilityGuardCodes.CORRUPT, codeOf { harness.write(listOf("com.example.reader"), null) })
        assertEquals(AccessibilityGuardCodes.CORRUPT, codeOf { harness.write(listOf("com.example.reader"), "abc123") })
        assertFalse(harness.whitelist.isNotEmpty())

        // 出路是系统身份确认那条路：不需要旧密码，也不需要旧记录可解析。
        harness.guard.resetAfterDeviceConfirmation()
        assertFalse(harness.guard.configured())
        harness.write(listOf("com.example.reader"), null)
        assertTrue(harness.whitelist.contains("com.example.reader"))
    }

    @Test
    fun deviceConfirmationClearsPasswordOnlyAndKeepsTheWhitelist() {
        val harness = Harness()
        harness.guard.set("abc123", null)
        val written = harness.write(listOf("com.example.reader"), "abc123")
        // 先制造失败计数，确认重置也把锁定状态一起清掉。
        codeOf { harness.write(listOf("com.example.reader"), "wrong") }
        assertTrue(harness.storage.readInt(AccessibilityPasswordPreferences.KEY_FAIL_COUNT, 0) > 0)

        harness.guard.resetAfterDeviceConfirmation()

        assertFalse(harness.guard.configured())
        assertEquals(0, harness.storage.readInt(AccessibilityPasswordPreferences.KEY_FAIL_COUNT, 0))
        assertEquals(0L, harness.storage.readLong(AccessibilityPasswordPreferences.KEY_LOCKED_UNTIL_MS, -1L))
        // 白名单一个字都没动：用户丢的是那串数字，不是自己配好的目标应用清单。
        assertEquals(written, harness.whitelist)
        // 重置之后相当于「首次设置」，不需要 currentPassword。
        harness.guard.set("newpass", null)
        assertTrue(harness.guard.configured())
    }

    @Test
    fun plaintextPasswordIsNeverStored() {
        val harness = Harness()
        val password = "abc12345"
        harness.guard.set(password, null)

        val raw = harness.storage.snapshot
        assertFalse(raw.values.any { it is String && it.contains(password) })
        assertEquals(
            AccessibilityPasswordPolicy.DEFAULT_ITERATIONS,
            raw[AccessibilityPasswordPreferences.KEY_ITERATIONS] as Int,
        )
        // 落盘的只有盐与哈希：长度就是策略里定下的那两个数。
        val salt = Base64.getDecoder().decode(raw[AccessibilityPasswordPreferences.KEY_SALT] as String)
        val hash = Base64.getDecoder().decode(raw[AccessibilityPasswordPreferences.KEY_HASH] as String)
        assertEquals(AccessibilityPasswordPolicy.SALT_BYTES, salt.size)
        assertEquals(AccessibilityPasswordPolicy.KEY_BITS / 8, hash.size)
        assertFalse(salt.contentEquals(hash))
    }
}
