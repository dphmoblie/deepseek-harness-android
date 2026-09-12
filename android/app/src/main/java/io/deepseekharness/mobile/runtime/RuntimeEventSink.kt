package io.deepseekharness.mobile.runtime

/**
 * 运行时事件出口。
 *
 * 背景：Capacitor 插件会在 Activity 销毁（含划掉最近任务）时被销毁，而前台服务
 * 仍可能继续持有运行时。因此事件出口必须是可替换的订阅者，而不是构造期固定的
 * WebView 回调；没有订阅者时事件被直接丢弃，不缓存、不落盘，也不涉及任何凭据。
 *
 * 线程约束：回调可能在运行时工作线程上触发，实现方自行负责切回主线程。
 */
interface RuntimeEventSink {
    /** 安装或启动进度发生变化。 */
    fun onProgress(snapshot: RuntimeStateSnapshot)

    /** 终端输出分片；[suppressPublicOutput] 为真时只供内部命令解析使用。 */
    fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean)

    /** 终端会话结束。 */
    fun onTerminalExit(sessionId: String, exitCode: Int)
}

/**
 * 后台保持与恢复状态快照。
 *
 * 只包含布尔值、枚举与时间戳：不含 Harness 地址、临时 Basic Auth 密码、
 * 模型 API Key、终端内容或任何其他敏感信息，可安全回传 WebView 显示。
 */
data class RuntimeKeepAliveSnapshot(
    /** 用户设置中的「后台保持 Harness」开关。 */
    val keepRuntimeInBackground: Boolean,
    /** 本进程的前台服务当前是否已挂载运行时。 */
    val foregroundServiceActive: Boolean,
    /** Shizuku 设备 Shell 是否已授权可用；仅用于辅助连接恢复，未授权时降级为 false。 */
    val deviceShellReady: Boolean,
    /** 是否存在本进程无法复用、必须重新连接的旧 Harness 会话。 */
    val reconnectRequired: Boolean,
    /** 持久化的最后一次运行意图。 */
    val lastIntent: RuntimeIntent,
    /** 持久化的最近运行阶段；从未记录时为 null。 */
    val lastPhase: RuntimePhase?,
    /** 最近一次状态写入时间；从未记录时为 0。 */
    val lastUpdatedAtMillis: Long,
)
