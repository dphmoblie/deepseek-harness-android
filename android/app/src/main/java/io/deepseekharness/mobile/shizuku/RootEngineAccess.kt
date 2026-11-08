package io.deepseekharness.mobile.shizuku

import android.content.Context

/**
 * 根通道的总开关、护栏档位与状态聚合。
 *
 * 开关**默认关闭**，且只有用户在设置里显式打开之后才会去拉 `su`：
 * KernelSU 在首次调用时会弹授权窗，一个默认关着的功能不该在用户没开的时候先把弹窗怼上来。
 *
 * 两档模式：
 *  - [Mode.OFF]：完全不碰 root；
 *  - [Mode.READ_WRITE]：AI 的设备 Shell 走 `su -c`，读写由 root 权限决定。
 *
 * 护栏档位 [RootCommandGuard.Profile]：
 *  - [RootCommandGuard.Profile.STRICT]：默认，`mount`/`dd`/`insmod`/`setprop` 全部拒绝；
 *  - [RootCommandGuard.Profile.MAXIMUM]：只拦刷机、块设备、核心分区写入、数据销毁、电源与物理内存。
 *
 * 旧的 `enabled` 布尔仍保留为迁移与兼容入口：`true` → 读写 + 严格档，`false` → 关闭。
 */
object RootEngineAccess {

    private const val PREFERENCES = "root_engine_access"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODE = "mode"
    private const val KEY_GUARD_PROFILE = "guard_profile"

    /** 根通道没开时给能力清单的原因码（能力层不读 Context，这个码由调用方喂进去）。 */
    const val REASON_DISABLED = "ROOT_ENGINE_DISABLED"

    enum class Mode(val wireValue: String) {
        OFF("off"),
        READ_WRITE("read_write"),
        ;

        companion object {
            fun fromWire(value: String?): Mode =
                entries.firstOrNull { it.wireValue == value } ?: OFF
        }
    }

    fun mode(context: Context): Mode {
        val stored = preferences(context).getString(KEY_MODE, null)
        if (stored != null) return Mode.fromWire(stored)
        // 迁移：旧版本只有 enabled 布尔；true 视为读写 + 严格档。
        return if (preferences(context).getBoolean(KEY_ENABLED, false)) Mode.READ_WRITE else Mode.OFF
    }

    fun guardProfile(context: Context): RootCommandGuard.Profile =
        RootCommandGuard.Profile.fromWire(preferences(context).getString(KEY_GUARD_PROFILE, null))

    fun enabled(context: Context): Boolean = mode(context) != Mode.OFF

    /** 开关只是允许尝试；两条命令入口都必须确认 KernelSU 真正可用后才选根通道。 */
    fun usable(context: Context): Boolean = status(context, packageChecker(context)).usable

    fun save(context: Context, mode: Mode, guardProfile: RootCommandGuard.Profile) {
        val committed = preferences(context).edit()
            .putString(KEY_MODE, mode.wireValue)
            .putString(KEY_GUARD_PROFILE, guardProfile.wireValue)
            .putBoolean(KEY_ENABLED, mode != Mode.OFF)
            .commit()
        check(committed) { "根通道开关保存失败" }
    }

    /**
     * 兼容旧桥方法 `setRootEngineEnabled`。
     *
     * `true` 保持当前护栏档位（默认严格），`false` 直接关闭；新界面用 [save] 一次写模式和档位。
     */
    fun save(context: Context, enabled: Boolean) {
        save(
            context,
            if (enabled) Mode.READ_WRITE else Mode.OFF,
            guardProfile(context),
        )
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 交给 [status] 的包存在性判断：`KernelSuProbe` 自己不该持有 Context。 */
    fun packageChecker(context: Context): (String) -> Boolean = { packageName ->
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    data class Status(
        val enabled: Boolean,
        val mode: Mode,
        /** 只在 [Mode.READ_WRITE] 下有意义；关闭时保留上次选择，便于下次打开。 */
        val guardProfile: RootCommandGuard.Profile,
        /** [KernelSuProbe] 的三态，或未开启时的 `unsupported`。 */
        val kernelSuStatus: String,
        /** [KernelSuProbe] 的原因码，或 [REASON_DISABLED]。 */
        val reason: String,
        /** 沙箱降级（能跑，但少了一层隔离）。 */
        val degraded: Boolean,
        /** 降级原因码，词表见 [RootSecurityWrapper.DEGRADATION_CODES]。 */
        val degradations: List<String>,
        val suVersion: String?,
    ) {
        /** 根通道现在能不能执行任意 root Shell。 */
        val usable: Boolean
            get() = enabled && mode == Mode.READ_WRITE && kernelSuStatus == KernelSuProbe.STATUS_READY
    }

    /**
     * 读一次完整状态。
     *
     * @param refresh 用户刚在管理器里改过授权、或刚打开开关时传 `true`，丢弃两个探测器的缓存重测。
     */
    fun status(
        context: Context,
        packageChecker: ((String) -> Boolean)? = null,
        refresh: Boolean = false,
    ): Status {
        if (refresh) {
            KernelSuProbe.clearCache()
            RootSandboxProbe.clearCache()
        }
        val currentMode = mode(context)
        val currentProfile = guardProfile(context)
        if (currentMode == Mode.OFF) {
            return Status(
                enabled = false,
                mode = Mode.OFF,
                guardProfile = currentProfile,
                kernelSuStatus = KernelSuProbe.STATUS_UNSUPPORTED,
                reason = REASON_DISABLED,
                degraded = false,
                degradations = emptyList(),
                suVersion = null,
            )
        }
        val probe = KernelSuProbe.probeLocal(packageChecker)
        if (probe.status != KernelSuProbe.STATUS_READY) {
            return Status(
                enabled = true,
                mode = currentMode,
                guardProfile = currentProfile,
                kernelSuStatus = probe.status,
                reason = probe.reason,
                degraded = false,
                degradations = emptyList(),
                suVersion = probe.suVersion,
            )
        }
        val capabilities = RootSandboxProbe.detect()
        return Status(
            enabled = true,
            mode = currentMode,
            guardProfile = currentProfile,
            kernelSuStatus = probe.status,
            reason = probe.reason,
            degraded = capabilities.degraded,
            degradations = capabilities.degradationCodes,
            suVersion = probe.suVersion,
        )
    }
}
