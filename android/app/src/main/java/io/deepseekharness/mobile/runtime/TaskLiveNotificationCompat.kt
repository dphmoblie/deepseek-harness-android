package io.deepseekharness.mobile.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import io.deepseekharness.mobile.runtime.TaskLivePromotionPolicy.ActiveNotificationStrategy

/** 为正在执行的任务请求状态栏实时动态；不支持的系统仍显示原来的标准通知。 */
internal object TaskLiveNotificationCompat {
    private const val PROGRESS_STYLE_CLASS_NAME = "android.app.Notification\$ProgressStyle"

    data class ActiveTaskNotificationSpec(
        val channelId: String,
        val smallIconRes: Int,
        val title: String,
        val text: String,
        val shortStatus: String,
        val contentIntent: PendingIntent,
        val timeoutAfterMs: Long,
        val whenMs: Long,
    )

    data class BuiltActiveTaskNotification(
        val notification: Notification,
        val strategy: ActiveNotificationStrategy,
        val hasPromotableCharacteristics: Boolean?,
    )

    /** 给现有 `NotificationCompat.Builder` 附加实况请求 extras；调用方仍需设置 ongoing、标题及通道。 */
    fun apply(builder: NotificationCompat.Builder, shortStatus: String): NotificationCompat.Builder {
        val normalized = TaskLivePromotionPolicy.normalizeShortStatus(shortStatus)
        return builder.addExtras(Bundle().apply {
            putBoolean(TaskLivePromotionPolicy.EXTRA_REQUEST_PROMOTED_ONGOING, true)
            putString(TaskLivePromotionPolicy.EXTRA_SHORT_CRITICAL_TEXT, normalized)
        })
    }

    /**
     * 按运行期系统版本与能力构建进行中任务通知：
     * Android 16+ 优先走原生 `Notification.Builder` + `ProgressStyle`，若结构校验不通过则降级为标准
     * 原生 Live Update 或 `NotificationCompat` 标准通知。
     */
    fun buildActiveTaskNotification(
        context: Context,
        spec: ActiveTaskNotificationSpec,
    ): BuiltActiveTaskNotification {
        var firstRejectedFramework: BuiltActiveTaskNotification? = null
        for (strategy in TaskLivePromotionPolicy.selectStrategies(Build.VERSION.SDK_INT)) {
            when (strategy) {
                ActiveNotificationStrategy.FRAMEWORK_PROGRESS_STYLE,
                ActiveNotificationStrategy.FRAMEWORK_STANDARD,
                -> {
                    val candidate = runCatching {
                        buildFrameworkNotification(
                            context = context,
                            spec = spec,
                            useProgressStyle = strategy == ActiveNotificationStrategy.FRAMEWORK_PROGRESS_STYLE,
                        )
                    }.getOrNull() ?: continue
                    val promotable = hasPromotableCharacteristics(candidate)
                    val wrapped = BuiltActiveTaskNotification(candidate, strategy, promotable)
                    if (TaskLivePromotionPolicy.shouldAcceptFrameworkCandidate(promotable)) {
                        return wrapped
                    }
                    // 优先保留首个成功构建的原生候选（FRAMEWORK_PROGRESS_STYLE 含不确定进度样式），
                    // 避免被后续同样未过晋升校验但无进度条的 FRAMEWORK_STANDARD 覆盖。
                    if (firstRejectedFramework == null) {
                        firstRejectedFramework = wrapped
                    }
                }

                ActiveNotificationStrategy.COMPAT_PROGRESS -> {
                    if (firstRejectedFramework != null) return firstRejectedFramework
                    val compatNotification = buildCompatNotification(context, spec)
                    return BuiltActiveTaskNotification(
                        notification = compatNotification,
                        strategy = strategy,
                        hasPromotableCharacteristics = hasPromotableCharacteristics(compatNotification),
                    )
                }
            }
        }
        val compat = buildCompatNotification(context, spec)
        return BuiltActiveTaskNotification(compat, ActiveNotificationStrategy.COMPAT_PROGRESS, null)
    }

    /** 检查当前应用是否被系统/用户允许投递 Android 16 实况动态通知。 */
    fun promotedNotificationPermissionState(context: Context): String {
        if (Build.VERSION.SDK_INT < TaskLivePromotionPolicy.ANDROID_16_API_LEVEL) {
            return "unsupported"
        }
        val canPost = runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching null
            val method = NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            method.invoke(manager) as? Boolean
        }.getOrNull()
        return TaskLivePromotionPolicy.promotedPermissionState(Build.VERSION.SDK_INT, canPost)
    }

    /** 构建「打开实时动态通知权限」的跳转队列：优先直达 API 36 实况设置页，不存在则依次回退。 */
    fun buildPromotionSettingsIntents(packageName: String): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= TaskLivePromotionPolicy.ANDROID_16_API_LEVEL) {
            add(
                Intent(TaskLivePromotionPolicy.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
        add(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
        add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName")),
        )
    }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    private fun buildFrameworkNotification(
        context: Context,
        spec: ActiveTaskNotificationSpec,
        useProgressStyle: Boolean,
    ): Notification {
        val shortStatus = TaskLivePromotionPolicy.normalizeShortStatus(spec.shortStatus)
        val builder = Notification.Builder(context, spec.channelId)
            .setSmallIcon(spec.smallIconRes)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setContentIntent(spec.contentIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(spec.timeoutAfterMs)
            .setUsesChronometer(true)
            .setWhen(spec.whenMs)
            // 进行中通知请求系统提升为独立实况节点，刻意不加入 BackgroundNotificationGroup：
            // 在 Android 16/17 部分 ROM（如 Honor SystemUI）上，既请求 PromotedOngoing 又挂进组摘要
            // 会触发 ShadeViewDiffer.registerNodes 报 DuplicateNodeException 并连环崩溃。
            .addExtras(Bundle().apply {
                putBoolean(TaskLivePromotionPolicy.EXTRA_REQUEST_PROMOTED_ONGOING, true)
                putString(TaskLivePromotionPolicy.EXTRA_SHORT_CRITICAL_TEXT, shortStatus)
            })
        if (useProgressStyle) {
            val progressStyleClass = Class.forName(PROGRESS_STYLE_CLASS_NAME)
            val progressStyle = progressStyleClass.getDeclaredConstructor().newInstance()
            check(invokeSingleArg(progressStyle, "setProgressIndeterminate", true)) {
                "Notification.ProgressStyle.setProgressIndeterminate not found"
            }
            check(progressStyle is Notification.Style) {
                "Notification.ProgressStyle is not a Notification.Style"
            }
            builder.setStyle(progressStyle)
        }
        // 在提供原生 setter 的版本（API 36 / 36.1）再同步调用一次，避免只认内部字段或不同重载；
        // 单独容错，防止个别 ROM 的 setter 抛异常导致整条 ProgressStyle 通知回退。
        runCatching { invokeSingleArg(builder, "setRequestPromotedOngoing", true) }
        runCatching { invokeSingleArg(builder, "setShortCriticalText", shortStatus) }
        return builder.build()
    }

    private fun buildCompatNotification(
        context: Context,
        spec: ActiveTaskNotificationSpec,
    ): Notification = apply(
        NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(spec.smallIconRes)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setContentIntent(spec.contentIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setTimeoutAfter(spec.timeoutAfterMs)
            .setUsesChronometer(true)
            .setWhen(spec.whenMs),
        spec.shortStatus,
    ).build()

    private fun hasPromotableCharacteristics(notification: Notification): Boolean? {
        if (Build.VERSION.SDK_INT < TaskLivePromotionPolicy.ANDROID_16_API_LEVEL) return null
        return runCatching {
            val method = Notification::class.java.getMethod("hasPromotableCharacteristics")
            method.invoke(notification) as? Boolean
        }.getOrNull()
    }

    private fun invokeSingleArg(target: Any, methodName: String, arg: Any): Boolean {
        val candidate = target.javaClass.methods.firstOrNull { method ->
            if (method.name != methodName || method.parameterTypes.size != 1) return@firstOrNull false
            val param = method.parameterTypes[0]
            when (arg) {
                is Boolean -> param == Boolean::class.javaPrimitiveType || param == Boolean::class.javaObjectType
                else -> param.isInstance(arg)
            }
        } ?: return false
        candidate.invoke(target, arg)
        return true
    }
}
