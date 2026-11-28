package io.deepseekharness.mobile

import android.app.Activity
import android.content.Context
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.deepseekharness.mobile.runtime.RuntimeFailure

/** 只控制管理页和对话页的系统导航栏。 */
internal object NavigationBarPreference {
    private const val PREFERENCES = "app_navigation_bar"
    private const val KEY = "hidden"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isHidden(context: Context): Boolean = preferences(context).all[KEY] as? Boolean ?: false

    fun apply(activity: Activity, hidden: Boolean = isHidden(activity)) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    fun change(activity: Activity, hidden: Boolean) {
        val preferences = preferences(activity)
        val previous = isHidden(activity)
        apply(activity, hidden)
        if (preferences.edit().putBoolean(KEY, hidden).commit()) return
        // commit 失败也可能改了内存缓存，恢复偏好与窗口的原状态。
        preferences.edit().putBoolean(KEY, previous).apply()
        apply(activity, previous)
        throw RuntimeFailure("NAVIGATION_BAR_SAVE_FAILED", "无法保存导航栏设置")
    }
}
