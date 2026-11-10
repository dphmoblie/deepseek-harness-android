package io.deepseekharness.mobile

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import io.deepseekharness.mobile.runtime.RuntimeFailure

/** 应用宿主页面的屏幕方向偏好；副屏画面的比例由 VirtualScreenPreferences 单独控制。 */
internal object AppOrientationPreference {
    private const val PREFERENCES = "app_orientation"
    private const val KEY = "mode"

    const val MODE_SYSTEM = "system"
    const val MODE_PORTRAIT = "portrait"
    const val MODE_LANDSCAPE = "landscape"

    private val MODES = setOf(MODE_SYSTEM, MODE_PORTRAIT, MODE_LANDSCAPE)

    fun isValid(mode: String): Boolean = mode in MODES

    /** 读取脏值时跟随系统，不把用户困在某个方向。 */
    fun current(context: Context): String = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .all[KEY]
        ?.let { it as? String }
        ?.takeIf(::isValid)
        ?: MODE_SYSTEM

    /** 同步写入，以便向设置页报告落盘失败。 */
    fun save(context: Context, mode: String): Boolean {
        require(isValid(mode)) { "不支持的应用屏幕方向" }
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = current(context)
        if (preferences.edit().putString(KEY, mode).commit()) return true
        // commit() 失败时 Android 仍可能已经更新进程内的 SharedPreferences 缓存；
        // 立即恢复旧值，避免下次 onResume 又按未落盘的新方向旋转窗口。
        preferences.edit().putString(KEY, previous).apply()
        return false
    }

    /** 将方向应用到宿主 Activity；不负责落盘，便于失败时不留下半套设置。 */
    fun apply(activity: Activity, mode: String = current(activity)) {
        val requested = requestedOrientation(mode)
        if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
    }

    /** 系统拒绝方向请求时保留旧偏好；落盘失败时也把窗口退回原来的方向。 */
    fun change(mode: String, previous: Int, apply: (Int) -> Unit, persist: (String) -> Boolean) {
        apply(requestedOrientation(mode))
        try {
            if (!persist(mode)) throw RuntimeFailure("APP_ORIENTATION_SAVE_FAILED", "无法保存应用屏幕方向")
        } catch (error: Throwable) {
            runCatching { apply(previous) }
            throw error
        }
    }

    fun requestedOrientation(mode: String): Int {
        require(isValid(mode)) { "不支持的应用屏幕方向" }
        return when (mode) {
            MODE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            MODE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
