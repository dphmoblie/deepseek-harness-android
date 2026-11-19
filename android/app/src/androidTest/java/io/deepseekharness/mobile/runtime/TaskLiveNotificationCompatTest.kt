package io.deepseekharness.mobile.runtime

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.KeepAliveEntryActivity
import io.deepseekharness.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 只构建通知，不投递，不需要通知授权。 */
@RunWith(AndroidJUnit4::class)
class TaskLiveNotificationCompatTest {
    @Test
    fun promotionRequestKeepsStandardNotificationIntact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, KeepAliveEntryActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, "test_tasks")
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setContentTitle("AI 任务运行中")
            .setContentText("点击返回对话")
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setProgress(0, 0, true)

        val notification = TaskLiveNotificationCompat.apply(builder, "运行中").build()

        assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals("运行中", notification.extras.getString("android.shortCriticalText"))
        assertEquals("AI 任务运行中", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("点击返回对话", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        assertEquals(contentIntent, notification.contentIntent)
        assertNotNull(notification.smallIcon)
    }

    @Test
    fun activeTaskNotificationBuildsForCurrentSdkAndExposesPromotionIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contentIntent = PendingIntent.getActivity(
            context, 1, Intent(context, KeepAliveEntryActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val built = TaskLiveNotificationCompat.buildActiveTaskNotification(
            context = context,
            spec = TaskLiveNotificationCompat.ActiveTaskNotificationSpec(
                channelId = TaskLivePromotionPolicy.ACTIVE_CHANNEL_ID,
                smallIconRes = R.drawable.ic_keep_alive,
                title = "AI 正在处理任务",
                text = "正在运行 1 项任务，点按返回对话",
                shortStatus = "运行中",
                contentIntent = contentIntent,
                timeoutAfterMs = 60_000L,
                whenMs = System.currentTimeMillis(),
            ),
        )

        val notification = built.notification
        assertTrue(notification.extras.getBoolean(TaskLivePromotionPolicy.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertEquals("运行中", notification.extras.getString(TaskLivePromotionPolicy.EXTRA_SHORT_CRITICAL_TEXT))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        assertEquals(contentIntent, notification.contentIntent)
        assertNull(notification.group)
        if (android.os.Build.VERSION.SDK_INT >= TaskLivePromotionPolicy.ANDROID_16_API_LEVEL) {
            assertEquals(true, built.hasPromotableCharacteristics)
        } else {
            assertEquals(
                TaskLivePromotionPolicy.ActiveNotificationStrategy.COMPAT_PROGRESS,
                built.strategy,
            )
        }
    }
}
