package io.deepseekharness.mobile.shizuku

import android.content.Context

/** 仅保存授权开关；命令正文、执行输出、应用清单均不持久化。 */
object DeviceShellAccess {
    private const val PREFERENCES = "device_shell_access"
    /** AI Shell 恒允许：设置页的开关已移除，历史保存的关闭状态不再生效。 */
    fun enabled(context: Context): Boolean = true

    fun save(context: Context, enabled: Boolean) {
        check(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", enabled).commit()) { "Shell 授权设置保存失败" }
    }
}
