package io.deepseekharness.mobile.runtime

/**
 * 用户对运行时表现出的最后一次意图。
 *
 * Android 进程被系统强制停止后，Harness 的临时 Basic Auth 凭据随进程一起消失，
 * 无法凭它恢复旧会话；因此这里只保存可以安全重建的意图标记，用于提示重新连接。
 */
enum class RuntimeIntent(val wireValue: String) {
    RUNNING("running"),
    STOPPED("stopped"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun parse(value: String?): RuntimeIntent =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * 持久化的运行时恢复记录：运行意图、最近阶段与写入时间。
 * 不包含地址、凭据、进程号或终端内容。
 */
data class RuntimeIntentRecord(
    val intent: RuntimeIntent,
    val phase: RuntimePhase?,
    val updatedAtMillis: Long,
) {
    companion object {
        val EMPTY = RuntimeIntentRecord(RuntimeIntent.UNKNOWN, null, 0L)
    }
}

/**
 * 前台服务与共享运行时之间的纯决策逻辑：不依赖 Android API，便于单元测试。
 *
 * 重要限制：前台服务只提高本应用进程被系统回收的优先级，不能阻止 Android 或
 * 厂商系统在内存压力、电量策略或后台限制下杀死进程；本策略不做也无法做保活承诺。
 */
object HarnessKeepAlivePolicy {
    /**
     * 是否应当让前台服务保持运行：必须同时满足「用户开启设置」与
     * 「本进程确实持有正在运行的 Harness」，避免留下空转的通知。
     */
    fun shouldRunService(keepRuntimeInBackground: Boolean, runtimeRunning: Boolean): Boolean =
        keepRuntimeInBackground && runtimeRunning

    /**
     * 插件销毁时是否必须释放共享运行时。
     * 前台服务仍负责运行时时不得立即 shutdown，否则划掉最近任务会终结 Harness。
     */
    fun shouldReleaseRuntimeOnPluginDetach(foregroundServiceActive: Boolean): Boolean =
        !foregroundServiceActive

    /**
     * 服务重新收到启动请求时是否应当立刻结束。
     * 进程被系统重启后没有可管理的运行时，旧认证会话无法恢复，继续前台服务只会
     * 留下一个无法解释的通知。
     */
    fun shouldStopServiceWithoutController(controllerAvailable: Boolean): Boolean =
        !controllerAvailable

    /**
     * 是否需要用户重新连接。
     *
     * 本进程持有正在运行的 Harness 时不需要；否则只要检测到残留的 Harness 进程，
     * 或最后一次意图是运行中，就必须提示重新连接——旧凭据已经不存在了。
     */
    fun requiresReconnect(
        runtimeOwnedRunning: Boolean,
        residualProcess: Boolean,
        lastIntent: RuntimeIntent,
    ): Boolean {
        if (runtimeOwnedRunning) return false
        return residualProcess || lastIntent == RuntimeIntent.RUNNING
    }
}
