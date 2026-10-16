package io.deepseekharness.mobile.shizuku

/**
 * 重启后是否值得自动连接并在失败时提醒用户。
 *
 * 未安装、未运行或未授权都属于用户尚未准备好 Shizuku，不能把这些状态误报成连接
 * 失败；只有「已运行且已授权但尚未连接」才进入自动绑定路径。
 */
internal object ShizukuAutoReconnectPolicy {
    fun shouldAttempt(state: ShizukuState): Boolean =
        state.installed && state.running && state.permission == "granted" && !state.connected
}
}
