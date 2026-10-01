package io.deepseekharness.mobile.accessibility

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 「无障碍白名单验证密码」的纯逻辑：口令规则、PBKDF2 派生、常量时间比对与锁定窗口。
 *
 * 这里**不 import 任何 `android.*` 类型**：口令边界、派生可复现性与锁定边界都应当在 JVM 单测里
 * 穷举，而不是靠设备上的手工点击去确认。持久化（盐 / 哈希 / 计数）在同包的
 * [AccessibilityPasswordStore] 里单独处理，因此本文件既不读盘、也不抛受控失败。
 *
 * 密码在本文件里只以「PBKDF2 参数」的形式出现：**没有任何函数返回、记录或拼接过明文副本**，
 * [derive] 的入参用完即弃（`PBEKeySpec.clearPassword()`）。
 */
internal object AccessibilityPasswordPolicy {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 64

    /** 每次设置密码都换一个新盐：同一个密码在两台设备上派生出不同的哈希。 */
    const val SALT_BYTES = 16
    const val KEY_BITS = 256

    /** 迭代次数：120k 次 PBKDF2-HMAC-SHA256。写进记录里，将来调高不影响旧记录校验。 */
    const val DEFAULT_ITERATIONS = 120_000

    const val MAX_FAILURES = 5

    /** 连续失败 [MAX_FAILURES] 次后的锁定时长。 */
    const val LOCK_MS = 30_000L

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /** 落盘记录：只有盐、哈希与迭代次数，**不含密码本身**。 */
    data class PasswordRecord(val salt: ByteArray, val hash: ByteArray, val iterations: Int)

    /**
     * 口令规则：长度 6..64、不是全空白、首尾无空白、无控制字符（含 `\t`/`\n`）。
     *
     * 与前端 `validateAccessibilityPasswordInput` 同口径；这里再判一次是因为桥接层是**信任边界**，
     * 不能假设调用方一定来自那个界面。中间允许出现空格（口令里带空格是用户自己的选择）。
     */
    fun validPassword(value: String): Boolean =
        value.length in MIN_LENGTH..MAX_LENGTH &&
            value.none { it.code < 32 || it.code == 127 } &&
            value.any { !it.isWhitespace() } &&
            value.trim() == value

    /** PBKDF2-HMAC-SHA256 派生；同密码同盐同迭代必得同一结果（单测据此断言可复现性）。 */
    fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** 常量时间比对：不因「前缀对了几位」而泄露时间差。 */
    fun verify(password: String, record: PasswordRecord): Boolean =
        MessageDigest.isEqual(derive(password, record.salt, record.iterations), record.hash)

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * 失败次数达到 [MAX_FAILURES] 后，从最后一次失败起 [LOCK_MS] 内的剩余锁定毫秒数；否则 0。
     *
     * 纯函数，便于把边界（第 4 次不算锁、第 5 次刚锁上、刚好到期、已经过期）全部写成断言。
     * 时钟回拨（`nowMs < lastFailureAtMs`）按「刚失败」处理：宁可多锁一次，也不要因为时间跳变放行。
     */
    fun lockRemainingMs(failures: Int, lastFailureAtMs: Long, nowMs: Long): Long {
        if (failures < MAX_FAILURES) return 0
        val elapsed = nowMs - lastFailureAtMs
        if (elapsed < 0) return LOCK_MS
        return (LOCK_MS - elapsed).coerceAtLeast(0)
    }

    /** 锁定文案用的整秒数：向上取整，剩 1ms 也显示「1 秒」。 */
    fun lockedSeconds(remainingMs: Long): Long = (remainingMs + 999) / 1000
}
