package io.deepseekharness.mobile

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat

/**
 * 前台服务进入前台状态的统一入口。
 *
 * 背景：通过 `Context.startForegroundService()` 启动的服务，系统强制要求它在时限内
 * （Android 12+ 约 5 秒，部分厂商 ROM 更短）调用一次 `Service.startForeground()`。
 * 一次都没调用就 `stopSelf()`，系统会抛
 * `ForegroundServiceDidNotStartInTimeException` 并**终结整个进程**——
 * 用户看到的就是「一开开关 App 闪退」，且重启后 onResume 再次同步服务会陷入闪退循环。
 *
 * 因此这里收敛两条真机上踩过的坑：
 *  1. POST_NOTIFICATIONS 未授予**不能**阻止进入前台。Android 13+ 上通知权限只决定
 *     常驻通知是否可见，前台服务身份本身不依赖它；因为缺通知权限而跳过 startForeground
 *     正是上述进程级崩溃的直接原因。
 *  2. 厂商 ROM 可能拒绝带类型的 startForeground（specialUse 审核、后台启动限制等）。
 *     第一次失败后再用不带类型的重载试一次：Android 14+ 会回退到清单里声明的服务类型，
 *     旧系统上它本来就是常规调用。两次都失败才返回 false，由调用方受控收尾。
 *
 * 本对象刻意不抛出任何异常：服务的 `onStartCommand` 里逃出去的异常同样会终结进程。
 */
internal object ForegroundServices {

    /**
     * 尽力进入前台状态。
     *
     * @return true 表示系统已受理前台身份；false 表示两次尝试都被拒绝，
     *   调用方应当记录受控诊断并 `stopSelf()`，而不是继续运行业务逻辑。
     */
    fun start(
        service: Service,
        notificationId: Int,
        notification: Notification,
        logTag: String,
        label: String,
    ): Boolean {
        // Android 14+ 必须在调用点给出具体的前台服务类型（清单里也已声明 specialUse）。
        val typedAccepted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    service,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                ServiceCompat.startForeground(service, notificationId, notification, 0)
            }
        }
        if (typedAccepted.isSuccess) return true
        android.util.Log.w(
            logTag,
            "$label typed startForeground failed: " +
                "${typedAccepted.exceptionOrNull()?.javaClass?.simpleName}",
        )

        // 兜底：不带类型的重载在 Android 14+ 会采用清单中声明的类型。
        // 部分 MIUI/HyperOS 版本拒绝带类型调用、却接受这一条。
        val fallbackAccepted = runCatching {
            @Suppress("DEPRECATION")
            service.startForeground(notificationId, notification)
        }
        if (fallbackAccepted.isSuccess) return true
        android.util.Log.w(
            logTag,
            "$label fallback startForeground failed: " +
                "${fallbackAccepted.exceptionOrNull()?.javaClass?.simpleName}",
        )
        return false
    }
}
