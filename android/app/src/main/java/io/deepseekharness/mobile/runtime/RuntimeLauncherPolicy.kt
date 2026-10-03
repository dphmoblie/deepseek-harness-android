package io.deepseekharness.mobile.runtime

/**
 * 运行器文件（proot 本体与 loader）可用性的纯策略判定。
 *
 * 存在的原因是一次真机回归：「文件内容正确」并不等于「文件能被系统执行」。部分 ROM 上
 * `File.setExecutable` 会静默失败、`security.android.exec` 盖章也会失败，而被吞掉的失败
 * 恰好让「内容一致但缺属主执行位」的 loader 被永久复用：PRoot 每次启动都在 `execve` 上
 * 拿到 EACCES，界面只显示「PRoot 无法加载 Ubuntu 程序」，日志里看不出是哪一环。
 *
 * 判定刻意只吃一次观测（[Observation]）、不碰文件系统，方便用纯 JVM 单测把判定表钉死；
 * 真正的观测、修复与回退都在 `RuntimeStore` 里。
 */
object RuntimeLauncherPolicy {
    /** 运行器文件的目标权限：属主读、写、执行（0o700）。 */
    const val EXECUTABLE_FILE_MODE = 0x1c0

    /** 权限位里的属主执行位。 */
    const val OWNER_EXECUTE_BIT = 0x40

    /** 判定码：可直接进诊断日志与失败消息（受控大写枚举，不含路径）。 */
    const val OK = "RUNNER_OK"
    const val MISSING = "RUNNER_MISSING"
    const val NOT_REGULAR = "RUNNER_NOT_REGULAR"
    const val EMPTY = "RUNNER_EMPTY"
    const val NOT_EXECUTABLE = "RUNNER_NOT_EXECUTABLE"
    const val EXEC_DENIED = "RUNNER_EXEC_DENIED"

    /**
     * 一次观测：是否常规文件、字节数、权限位、系统是否允许执行、是否带执行标记。
     *
     * [execAccess] 来自 `access(X_OK)`，比 mode 位更接近「能不能真的执行」：Android 会在
     * mode 之外再施加应用数据目录的执行策略，只看 mode 位会漏掉「有执行位但被策略拒绝」。
     */
    data class Observation(
        val regular: Boolean,
        val size: Long,
        val mode: Int,
        val execAccess: Boolean,
        val stamped: Boolean,
    )

    /** 观测为 null 表示路径不存在（`lstat` 返回 ENOENT）。 */
    fun failureCode(observation: Observation?): String? {
        if (observation == null) return MISSING
        if (!observation.regular) return NOT_REGULAR
        if (observation.size <= 0) return EMPTY
        if (!hasOwnerExecute(observation.mode)) return NOT_EXECUTABLE
        if (!observation.execAccess) return EXEC_DENIED
        return null
    }

    /** 根内 loader 的要求：**必须是可执行的常规文件**（Landlock 按最终 inode 判定，链接无效）。 */
    fun isUsable(observation: Observation?): Boolean = failureCode(observation) == null

    /**
     * 兜底形态的可用性：常规文件按 [isUsable] 判定，符号链接只看 `access(X_OK)`（会跟随链接）。
     *
     * 私有回退既可能是指向 APK 的符号链接，也可能是 ROM 禁止链接时的拷贝，两种都要能判。
     */
    fun isUsablePath(observation: Observation?): Boolean {
        if (observation == null || observation.size <= 0) return false
        if (!observation.regular) return observation.execAccess
        return isUsable(observation)
    }

    /** 只有「缺属主执行位」这一种形态可以就地补权限修好。 */
    fun repairableInPlace(observation: Observation?): Boolean =
        failureCode(observation) == NOT_EXECUTABLE

    /**
     * 是否值得尝试去掉 `security.android.exec` 标记。
     *
     * 只在「有执行位、系统仍不允许执行」时尝试：这种情况下该标记在本 ROM 上的语义可能恰好是
     * 「禁止执行」，去掉它对应用没有副作用（属性本来就是可选的，旧内核也不支持）。
     */
    fun shouldDropStamp(observation: Observation?): Boolean =
        observation != null && observation.stamped && failureCode(observation) == EXEC_DENIED

    fun hasOwnerExecute(mode: Int): Boolean = (mode and OWNER_EXECUTE_BIT) != 0

    /** 日志与失败消息用的可读描述；八进制权限位是排查这个回归最关键的一项。 */
    fun describe(observation: Observation?): String {
        if (observation == null) return "missing"
        return "regular=" + observation.regular +
            ",size=" + observation.size +
            ",mode=0" + Integer.toOctalString(observation.mode and 0x1ff) +
            ",execAccess=" + observation.execAccess +
            ",stamped=" + observation.stamped
    }
}
