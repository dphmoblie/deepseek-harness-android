package io.deepseekharness.mobile.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.deepseekharness.mobile.MainActivity
import io.deepseekharness.mobile.R

/**
 * 任务通知的投递（登记册 §5.5，设计见 `docs/任务通知.md`）。
 *
 * **独立通道**，不复用保活通道 `harness_keep_alive`：保活通知必须常驻、静默、
 * `IMPORTANCE_LOW`；任务通知的价值恰恰在于「离开应用后仍能得知」。塞进同一个通道，
 * 用户就只能同时接受或同时关闭两者——那正是 Android 通道模型要避免的事。
 *
 * **不常驻、可划掉**：没有进行中的任务时通知不该存在；做成常驻就等于第二个保活通知，
 * 会把通道语义搞混。
 */
internal object TaskNotification {
    private const val CHANNEL_ID = "dsh_tasks"

    /** 独立 id，与保活通知（`0x44534801`）不同。 */
    private const val NOTIFICATION_ID = 0x44534802

    /**
     * 发一条「Harness 已停止」类通知。
     *
     * 未授予通知权限时**静默返回**而不是抛错：通知只是提醒，用户明确拒绝了提醒，
     * 界面另有状态显示（设计文档第三节第 5 条）。这里不因此改用别的常驻手段来「补偿」。
     */
    fun postHarnessStopped(context: Context, kind: TaskNotificationKind) {
        val titleRes = when (kind) {
            TaskNotificationKind.HARNESS_STOPPED -> R.string.task_notification_stopped_title
            TaskNotificationKind.HARNESS_EXITED_DURING_START -> R.string.task_notification_failed_title
            else -> return
        }
        val textRes = when (kind) {
            TaskNotificationKind.HARNESS_STOPPED -> R.string.task_notification_stopped_text
            TaskNotificationKind.HARNESS_EXITED_DURING_START -> R.string.task_notification_failed_text
            else -> return
        }
        post(context, titleRes, textRes)
    }

    /**
     * 发一条「运行环境已安装/已更新」通知。
     *
     * 安装要下载并解压整个 rootfs（数百 MB），用户几乎必然切走——这是三类通知里
     * 最不会被错过、也最需要的一条：他回来时既要确认装完了，也要知道下一步做什么。
     */
    fun postInstallCompleted(context: Context, kind: TaskNotificationKind) {
        val titleRes = when (kind) {
            TaskNotificationKind.RUNTIME_INSTALLED -> R.string.task_notification_installed_title
            TaskNotificationKind.RUNTIME_UPDATED -> R.string.task_notification_updated_title
            else -> return
        }
        val textRes = when (kind) {
            TaskNotificationKind.RUNTIME_INSTALLED -> R.string.task_notification_installed_text
            TaskNotificationKind.RUNTIME_UPDATED -> R.string.task_notification_updated_text
            else -> return
        }
        post(context, titleRes, textRes)
    }

    /**
     * 发一条「工作区已导出到投递区」通知。
     *
     * 导出要遍历整个工作区并把 tar 写进 outbox，工作区大时要几十秒；用户按完按钮就去干别的了，
     * 只在界面上更新状态等于什么都没告诉他。条目数是**确定值**（操作已经返回），
     * 因此可以如实带上——这不是进度百分比。
     */
    fun postWorkspaceExported(context: Context, entryCount: Int) {
        postWithCount(context, R.string.task_notification_exported_title, R.string.task_notification_exported_text, entryCount)
    }

    /** 发一条「已导入工作区」通知，语义同上。 */
    fun postWorkspaceImported(context: Context, entryCount: Int) {
        postWithCount(context, R.string.task_notification_imported_title, R.string.task_notification_imported_text, entryCount)
    }

    private fun postWithCount(context: Context, titleRes: Int, textRes: Int, count: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes, count))
            .setContentIntent(openAppIntent(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        postSafely(manager, notification)
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, titleRes: Int, textRes: Int) {
        val manager = NotificationManagerCompat.from(context)
        // areNotificationsEnabled() 覆盖两件事：用户关掉了通知，或（Android 13+）没授予权限。
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes))
            .setContentIntent(openAppIntent(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            // 通知本身不承载动作：点一下只回到应用。隔着锁屏就能触发的破坏性入口不做。
            .build()
        postSafely(manager, notification)
    }

    private fun postSafely(manager: NotificationManagerCompat, notification: android.app.Notification) {
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 权限在检查与投递之间被撤销时会抛：通知丢了不影响运行时状态本身。
        }
    }

    /** 用户回到应用即清掉：应用已经在前台时，这条通知没有存在价值。 */
    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.task_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.task_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}
