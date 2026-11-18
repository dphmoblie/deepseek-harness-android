package io.deepseekharness.mobile.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.deepseekharness.mobile.AppLanguage
import io.deepseekharness.mobile.KeepAliveEntryActivity
import io.deepseekharness.mobile.R

/**
 * 把本应用的后台通知收进同一个 Android notification group。
 *
 * 任务进度、任务完成、保活、悬浮球与副屏原先各占一条；折叠后通知栏只保留一条组摘要，
 * 展开仍能看到每个子通知。摘要只做聚合与入口，不承载任何用户数据，也不提供破坏性动作。
 *
 * 子通知必须各自 `setGroup(GROUP_KEY)`；这里负责按 `activeNotifications` 重新计算数量并
 * 更新摘要。所有投递都捕获 `SecurityException`：通知权限可能在检查与投递之间被撤销，
 * 通知失败不能反向影响任务或服务。
 */
internal object BackgroundNotificationGroup {
    const val GROUP_KEY = "io.deepseekharness.mobile.BACKGROUND"

    /** 组摘要的稳定 id；与任务、保活、悬浮球、副屏的子通知 id 均不冲突。 */
    const val SUMMARY_ID = 0x44534800

    private const val CHANNEL_ID = "dsh_background"

    fun apply(builder: NotificationCompat.Builder): NotificationCompat.Builder =
        builder.setGroup(GROUP_KEY)

    /**
     * 重新计算组内子通知数量并同步摘要。
     *
     * 过滤掉摘要自身，避免「摘要把自己也算成一条」而永远删不掉。没有子通知时取消摘要。
     * 直接读 `activeNotifications` 而不是自己维护计数：服务可能在任意进程状态下启停，
     * 内存计数一旦与系统真实状态分叉，就会留下一条永远不消失的摘要。
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val system = app.getSystemService(NotificationManager::class.java) ?: return
        val active = try {
            system.activeNotifications
        } catch (_: Throwable) {
            return
        }
        val count = active.count { status ->
            status.id != SUMMARY_ID && status.notification.group == GROUP_KEY
        }
        if (count == 0) {
            runCatching { system.cancel(SUMMARY_ID) }
            return
        }
        ensureChannel(app)
        val localized = AppLanguage.localizedContext(app)
        val summary = NotificationCompat.Builder(localized, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setContentTitle(localized.getString(R.string.background_notification_summary_title, count))
            .setContentText(localized.getString(R.string.background_notification_summary_text))
            .setContentIntent(openAppIntent(localized))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setNumber(count)
            .build()
        try {
            system.notify(SUMMARY_ID, summary)
        } catch (_: SecurityException) {
            // 权限在检查与投递之间被撤销：摘要丢失不影响子通知与服务本身。
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val localized = AppLanguage.localizedContext(context)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.background_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localized.getString(R.string.background_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, KeepAliveEntryActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
