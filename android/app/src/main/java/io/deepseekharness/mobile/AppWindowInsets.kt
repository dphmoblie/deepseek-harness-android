package io.deepseekharness.mobile

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** 原生容器统一避让系统栏、挖孔和键盘；WebView 只得到剩余的实际可用视口。 */
internal object AppWindowInsets {
    fun apply(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val content = activity.findViewById<View>(android.R.id.content)
        val initial = Insets.of(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom)
        val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val handled = bars or WindowInsetsCompat.Type.ime()
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val occupied = insets.getInsets(handled)
            view.setPadding(
                initial.left + occupied.left, initial.top + occupied.top,
                initial.right + occupied.right, initial.bottom + occupied.bottom,
            )
            // 避免子视图与 CSS 的安全区再次扣除同一段空间；旋转后会重新计算。
            WindowInsetsCompat.Builder(insets)
                .setInsets(handled, Insets.NONE)
                .setInsetsIgnoringVisibility(bars, Insets.NONE)
                .build()
        }
        ViewCompat.requestApplyInsets(content)
    }
}
