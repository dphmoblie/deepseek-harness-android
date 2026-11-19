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
 * 把本应用的常驻后台与结果通知收进同一个 Android notification group。
 *
 * 任务完成、保活、悬浮球与副屏原先各占一条；当组内存在至少两条子通知时折叠为一条组摘要，
 * 展开仍能看到每个子通知（单条时不包裹「1 个后台通知」）。摘要只做聚合与入口，不承载用户数据。
 * 注意进行中实况任务通知（`0x44534806`）刻意不加入本组，避免部分 ROM 的 SystemUI
 * 将其同时登记在实况提升节点与组子节点下而触发 `DuplicateNodeException`。
 *
 * 子通知各自 `setGroup(GROUP_KEY)`；这里通过 [BackgroundNotificationGroupPolicy] 去重并同步摘要，
 * 避免状态未变化时重复 `notify(SUMMARY_ID)` 触发通知树重绘。所有投递都捕获 `SecurityException`：
 * 通知权限可能在检查与投递之间被撤销，通知失败不能反向影响任务或服务。
 */
internal object BackgroundNotificationGroup {
    const val GROUP_KEY = "io.deepseekharness.mobile.BACKGROUND"

    /** 组摘要的稳定 id；与任务、保活、悬浮球、副屏的子通知 id 均不冲突。 */
    const val SUMMARY_ID = 0x44534800

    private const val CHANNEL_ID = "dsh_background"
    private val syncLock = Any()
    private var lastSyncedState: BackgroundNotificationGroupPolicy.SummaryState? = null

    fun apply(builder: NotificationCompat.Builder): NotificationCompat.Builder =
        builder.setGroup(GROUP_KEY)

    /**
     * 重新计算组内子通知数量并在状态确实变化时同步摘要。
     *
     * 过滤掉摘要自身，避免「摘要把自己也算成一条」而永远删不掉。子通知不足两条时取消摘要。
     * 直接读 `activeNotifications` 而不是自己维护计数：服务可能在任意进程状态下启停，
     * 或摘要被系统/用户清掉，真实通知集合才是权威状态。
     */
    fun sync(context: Context) = synchronized(syncLock) {
        val app = context.applicationContext
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            lastSyncedState = null
            return@synchronized
        }
        val system = app.getSystemService(NotificationManager::class.java) ?: return@synchronized
        val active = try {
            system.activeNotifications
        } catch (_: Throwable) {
            return@synchronized
        }
        val summaryPresent = active.any { it.id == SUMMARY_ID }
        val count = active.count { status ->
            status.id != SUMMARY_ID && status.notification.group == GROUP_KEY
        }
        val localeTag = AppLanguage.current(app)
        when (val decision = BackgroundNotificationGroupPolicy.decide(count, localeTag, summaryPresent, lastSyncedState)) {
            BackgroundNotificationGroupPolicy.SummaryDecision.NoOp -> return@synchronized
            BackgroundNotificationGroupPolicy.SummaryDecision.Cancel -> {
                runCatching { system.cancel(SUMMARY_ID) }
                lastSyncedState = null
                return@synchronized
            }
            is BackgroundNotificationGroupPolicy.SummaryDecision.Post -> {
                ensureChannel(app)
                val localized = AppLanguage.localizedContext(app)
                val summaryCount = decision.state.childCount
                val summary = NotificationCompat.Builder(localized, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_keep_alive)
                    .setContentTitle(localized.getString(R.string.background_notification_summary_title, summaryCount))
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
                    .setNumber(summaryCount)
                    .build()
                try {
                    system.notify(SUMMARY_ID, summary)
                    lastSyncedState = decision.state
                } catch (_: SecurityException) {
                    // 权限在检查与投递之间被撤销：摘要丢失不影响子通知与服务本身。
                    lastSyncedState = null
                }
            }
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
