package io.deepseekharness.mobile.runtime

import android.os.Bundle
import androidx.core.app.NotificationCompat

/** 为正在执行的任务请求状态栏实时动态；不支持的系统仍显示原来的标准通知。 */
internal object TaskLiveNotificationCompat {
    // 这两个官方 extras 在 core 1.17 / API 36.1 才有常量，当前 compileSdk 35 可直接传键值。
    // https://developer.android.com/develop/ui/views/notifications/live-update
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    /** 调用方仍需设置 ongoing、标题、标准布局及非最低优先级的通知通道。 */
    fun apply(builder: NotificationCompat.Builder, shortStatus: String): NotificationCompat.Builder =
        builder.addExtras(Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            putString(EXTRA_SHORT_CRITICAL_TEXT, shortStatus)
        })
}
