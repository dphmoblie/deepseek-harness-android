package io.deepseekharness.mobile.accessibility

import android.content.Context
import java.util.Base64

/**
 * 验证密码相关的受控失败。
 *
 * [code] 直接作为桥接失败码过桥（`ACCESSIBILITY_PASSWORD_*`），文案里**不含任何密码内容**；
 * 继承 [IllegalArgumentException] 是为了在既有的「`IllegalArgumentException` → 配置格式错误」
 * 映射旁边能被单独优先捕获，而不是自创第二套失败通道。
 */
internal class AccessibilityGuardException(val code: String, message: String) : IllegalArgumentException(message)

/** 验证密码族失败码；前端据此区分「没带密码」「密码不对」「被锁定」「新密码太弱」。 */
internal object AccessibilityGuardCodes {
    const val REQUIRED = "ACCESSIBILITY_PASSWORD_REQUIRED"
    const val INVALID = "ACCESSIBILITY_PASSWORD_INVALID"
    const val LOCKED = "ACCESSIBILITY_PASSWORD_LOCKED"
    const val WEAK = "ACCESSIBILITY_PASSWORD_WEAK"

    /** 记录存在但内容不可用（盐 / 哈希 / 迭代次数坏掉）：按「已配置但验不了」处理，不静默放行。 */
    const val CORRUPT = "ACCESSIBILITY_PASSWORD_CORRUPT"
}

/**
 * 「忘记密码」恢复路径（系统生物识别 / 锁屏密码）的失败码。
 *
 * 与 [AccessibilityGuardCodes] 分开：取消是用户的正常操作，界面不该按故障提示；
 * [FAILED] 才是「确认失败」（例如生物识别被系统锁定）。
 */
internal object AccessibilityBiometricCodes {
    const val UNAVAILABLE = "BIOMETRIC_UNAVAILABLE"
    const val FAILED = "BIOMETRIC_FAILED"
    const val CANCELLED = "BIOMETRIC_CANCELLED"
}

/**
 * 「白名单验证密码」的持久化（独立偏好文件 `accessibility_automation_guard`）。
 *
 * 只存 盐 / PBKDF2 哈希（Base64）/ 迭代次数 / 失败计数 / 锁定截止时刻——**明文密码不落盘**，
 * 也不出现在任何日志、审计字段或返回值里（本类只往外给 [AccessibilityPasswordPolicy.PasswordRecord]）。
 *
 * 存储抽象可注入（生产实现包 SharedPreferences，JVM 单测用内存替身），与 `RuntimeStorageDirPreferences`
 * 同一个模式：判定逻辑里不出现 `getSharedPreferences`，否则「配置前后、对错密码、锁定」这条链条
 * 就只能挂在设备上手工点，等于测不到。Base64 用 `java.util.Base64`（API 26 起可用）而不是
 * `android.util.Base64`，就是为了让单测不依赖 android 桩。
 */
internal class AccessibilityPasswordPreferences(private val storage: Storage) {
    /** 存储抽象：生产实现包 SharedPreferences，测试用内存替身。 */
    interface Storage {
        fun readString(key: String): String?
        fun readInt(key: String, fallback: Int): Int
        fun readLong(key: String, fallback: Long): Long
        fun writeString(key: String, value: String?)
        fun writeInt(key: String, value: Int)
        fun writeLong(key: String, value: Long)
    }

    /**
     * 是否已配置密码。
     *
     * 判定只看「盐或哈希键是否存在」，**不看内容是否可解析**：记录坏掉时如果报「未配置」，
     * 白名单就悄悄失去了密码门槛。坏数据一律走 [AccessibilityGuardCodes.CORRUPT]（fail-closed），
     * 用户的出路是生物识别重置——那条路不需要旧密码。
     */
    fun configured(): Boolean = storage.readString(KEY_SALT) != null || storage.readString(KEY_HASH) != null

    /** 读取记录；任一字段缺失或形态不对（盐长度不符、哈希为空、迭代次数非正）都返回 null。 */
    fun readRecord(): AccessibilityPasswordPolicy.PasswordRecord? {
        val salt = decode(storage.readString(KEY_SALT)) ?: return null
        val hash = decode(storage.readString(KEY_HASH)) ?: return null
        val iterations = storage.readInt(KEY_ITERATIONS, 0)
        if (salt.size != AccessibilityPasswordPolicy.SALT_BYTES || hash.isEmpty() || iterations <= 0) return null
        return AccessibilityPasswordPolicy.PasswordRecord(salt, hash, iterations)
    }

    fun writeRecord(record: AccessibilityPasswordPolicy.PasswordRecord) {
        storage.writeString(KEY_SALT, encode(record.salt))
        storage.writeString(KEY_HASH, encode(record.hash))
        storage.writeInt(KEY_ITERATIONS, record.iterations)
    }

    fun clearRecord() {
        storage.writeString(KEY_SALT, null)
        storage.writeString(KEY_HASH, null)
        storage.writeInt(KEY_ITERATIONS, 0)
    }

    fun readFailCount(): Int = storage.readInt(KEY_FAIL_COUNT, 0).coerceAtLeast(0)

    fun readLockedUntilMs(): Long = storage.readLong(KEY_LOCKED_UNTIL_MS, 0L)

    fun writeFailures(failCount: Int, lockedUntilMs: Long) {
        storage.writeInt(KEY_FAIL_COUNT, failCount)
        storage.writeLong(KEY_LOCKED_UNTIL_MS, lockedUntilMs)
    }

    fun clearFailures() = writeFailures(0, 0L)

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String?): ByteArray? {
        if (value.isNullOrEmpty()) return null
        return try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val FILE_NAME = "accessibility_automation_guard"
        const val KEY_SALT = "salt"
        const val KEY_HASH = "hash"
        const val KEY_ITERATIONS = "iterations"
        const val KEY_FAIL_COUNT = "fail_count"
        const val KEY_LOCKED_UNTIL_MS = "locked_until_ms"

        /** 生产实现：独立偏好文件，仅本应用可读（只存盐、哈希与计数）。 */
        fun from(context: Context): AccessibilityPasswordPreferences {
            val preferences = context.applicationContext
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            return AccessibilityPasswordPreferences(object : Storage {
                override fun readString(key: String): String? =
                    if (preferences.contains(key)) preferences.getString(key, null) else null

                override fun readInt(key: String, fallback: Int): Int = preferences.getInt(key, fallback)

                override fun readLong(key: String, fallback: Long): Long = preferences.getLong(key, fallback)

                override fun writeString(key: String, value: String?) {
                    val editor = preferences.edit()
                    if (value == null) editor.remove(key) else editor.putString(key, value)
                    editor.apply()
                }

                override fun writeInt(key: String, value: Int) {
                    preferences.edit().putInt(key, value).apply()
                }

                override fun writeLong(key: String, value: Long) {
                    preferences.edit().putLong(key, value).apply()
                }
            })
        }
    }
}

/**
 * 验证密码的判定与写入逻辑（与 `Context` 无关，偏好来源可注入）。
 *
 * 时间统一由 [nowMs] 提供，测试可以精确摆布锁定窗口的边界，不必真的等 30 秒。
 */
internal class AccessibilityPasswordGuard(
    private val preferences: AccessibilityPasswordPreferences,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun configured(): Boolean = preferences.configured()

    /**
     * 白名单写入前的门槛。
     *
     * - 未配置密码 → 直接放行（老用户的既有行为不变）；
     * - 已配置但没带密码 → [AccessibilityGuardCodes.REQUIRED]；
     * - 锁定期内 → [AccessibilityGuardCodes.LOCKED]（文案带剩余秒数，不带任何密码内容）；
     * - 比对失败 → 记一次失败后抛 [AccessibilityGuardCodes.INVALID]；
     * - 通过 → 清零失败计数。
     */
    fun verifyOrThrow(password: String?) {
        if (!preferences.configured()) return
        val record = preferences.readRecord() ?: throw AccessibilityGuardException(
            AccessibilityGuardCodes.CORRUPT,
            "验证密码记录不可用，请用系统身份确认重置验证密码",
        )
        if (password == null) {
            throw AccessibilityGuardException(AccessibilityGuardCodes.REQUIRED, "修改无障碍白名单需要验证密码")
        }
        val now = nowMs()
        val lockedUntil = preferences.readLockedUntilMs()
        if (lockedUntil > now) {
            val seconds = AccessibilityPasswordPolicy.lockedSeconds(lockedUntil - now)
            throw AccessibilityGuardException(
                AccessibilityGuardCodes.LOCKED,
                "验证密码已锁定，请在 $seconds 秒后重试",
            )
        }
        // 上一次锁定已经到期：计数清零，让用户重新拿到完整的尝试次数，而不是「锁一过就只剩一次机会」。
        if (preferences.readFailCount() >= AccessibilityPasswordPolicy.MAX_FAILURES) preferences.clearFailures()
        if (!AccessibilityPasswordPolicy.verify(password, record)) {
            registerFailure(now)
            throw AccessibilityGuardException(AccessibilityGuardCodes.INVALID, "验证密码不正确")
        }
        preferences.clearFailures()
    }

    /**
     * 设置或修改密码。
     *
     * 已配置时必须先过当前密码（含锁定与失败计数）；未配置时**不接受** `currentPassword`
     * （那说明调用方把「首次设置」和「修改」搞混了，直接按配置格式错误拒绝，不静默忽略）。
     * 新密码不合规一律 [AccessibilityGuardCodes.WEAK]，阈值只有 [AccessibilityPasswordPolicy] 一份。
     */
    fun set(newPassword: String, currentPassword: String?) {
        if (preferences.configured()) {
            verifyOrThrow(currentPassword)
        } else if (currentPassword != null) {
            throw IllegalArgumentException("尚未设置验证密码时不需要提供当前密码")
        }
        if (!AccessibilityPasswordPolicy.validPassword(newPassword)) {
            throw AccessibilityGuardException(
                AccessibilityGuardCodes.WEAK,
                "验证密码需要 6 到 64 个字符，且不能是空白或含控制字符",
            )
        }
        val salt = AccessibilityPasswordPolicy.newSalt()
        val hash = AccessibilityPasswordPolicy.derive(newPassword, salt, AccessibilityPasswordPolicy.DEFAULT_ITERATIONS)
        preferences.writeRecord(
            AccessibilityPasswordPolicy.PasswordRecord(salt, hash, AccessibilityPasswordPolicy.DEFAULT_ITERATIONS),
        )
        preferences.clearFailures()
    }

    /** 清除密码（必须带当前密码）。**只动密码，不动白名单**。 */
    fun clear(currentPassword: String) {
        verifyOrThrow(currentPassword)
        preferences.clearRecord()
        preferences.clearFailures()
    }

    /**
     * 忘记密码的恢复路径：清除密码记录与失败计数。
     *
     * **调用边界（唯一入口）**：只有已经完成系统生物识别 / 锁屏密码确认的路径
     * （`MobileRuntimePlugin.resetAccessibilityPasswordWithBiometric`）才允许调到这里；
     * 桥接层**不得**为它再开一个不验证的插件方法，否则「知道密码」这道门就等于不存在。
     * 生效范围刻意只包住密码：**白名单原样保留**——用户丢的是那串数字，不是自己配好的目标应用清单。
     */
    fun resetAfterDeviceConfirmation() {
        preferences.clearRecord()
        preferences.clearFailures()
    }

    private fun registerFailure(now: Long) {
        val failures = preferences.readFailCount() + 1
        // 用策略层的锁定判定决定要不要写锁定时刻，避免这里再抄一遍「第几次开始锁」。
        val lockedUntil = if (AccessibilityPasswordPolicy.lockRemainingMs(failures, now, now) > 0) {
            now + AccessibilityPasswordPolicy.LOCK_MS
        } else {
            0L
        }
        preferences.writeFailures(failures, lockedUntil)
    }
}

/**
 * 「白名单验证密码」的门面：把 [AccessibilityPasswordGuard] 接到本应用的私有偏好文件上。
 *
 * 密码只在本模块内流转（入参 → PBKDF2），**不写进审计字段、诊断日志、返回值或异常文案**；
 * 返回值里只有 `passwordConfigured` 这一个布尔（见 [AccessibilityAutomationStore.state]）。
 * 读取白名单（服务侧每读一次窗口都要走）不经过这里，因此不会产生哈希运算。
 */
internal object AccessibilityPasswordStore {
    fun configured(context: Context): Boolean = guard(context).configured()

    fun verifyOrThrow(context: Context, password: String?) = guard(context).verifyOrThrow(password)

    fun set(context: Context, newPassword: String, currentPassword: String?) {
        guard(context).set(newPassword, currentPassword)
    }

    fun clear(context: Context, currentPassword: String) {
        guard(context).clear(currentPassword)
    }

    /** 只允许在**已通过**系统生物识别 / 锁屏密码确认之后调用，见 [AccessibilityPasswordGuard.resetAfterDeviceConfirmation]。 */
    fun resetAfterDeviceConfirmation(context: Context) = guard(context).resetAfterDeviceConfirmation()

    private fun guard(context: Context): AccessibilityPasswordGuard =
        AccessibilityPasswordGuard(AccessibilityPasswordPreferences.from(context))
}
