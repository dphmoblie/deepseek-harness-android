package io.deepseekharness.mobile.shizuku

import android.content.Context

/**
 * 根通道的总开关与状态聚合。
 *
 * 开关**默认关闭**，且只有用户在设置里显式打开之后才会去拉 `su`：
 * KernelSU 在首次调用时会弹授权窗，一个默认关着的功能不该在用户没开的时候先把弹窗怼上来。
 *
 * 开关本身只存一个布尔（与 [DeviceShellAccess] 同一形状），状态则是**实时推导**的：
 * [KernelSuProbe] 判断有没有 KernelSU 且是否已授权，[RootSandboxProbe] 判断沙箱能拿到哪一档隔离。
 * 两者都带 60 秒缓存，所以「状态查询」不会变成每帧拉进程。
 */
object RootEngineAccess {

    private const val PREFERENCES = "root_engine_access"
    private const val KEY_ENABLED = "enabled"

    /** 根通道没开时给能力清单的原因码（能力层不读 Context，这个码由调用方喂进去）。 */
    const val REASON_DISABLED = "ROOT_ENGINE_DISABLED"

    fun enabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, false)

    /** 开关只是允许尝试；两条命令入口都必须确认 KernelSU 真正可用后才选根通道。 */
    fun usable(context: Context): Boolean = status(context, packageChecker(context)).usable

    fun save(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_ENABLED, enabled).commit()) { "根通道开关保存失败" }
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
        /** 根通道现在能不能真的执行命令。 */
        val usable: Boolean
            get() = enabled && kernelSuStatus == KernelSuProbe.STATUS_READY
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
        if (!enabled(context)) {
            return Status(
                enabled = false,
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
            kernelSuStatus = probe.status,
            reason = probe.reason,
            degraded = capabilities.degraded,
            degradations = capabilities.degradationCodes,
            suVersion = probe.suVersion,
        )
    }
}
