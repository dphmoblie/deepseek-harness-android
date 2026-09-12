package io.deepseekharness.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.runtime.HarnessKeepAlivePolicy
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLog

/**
 * 「后台保持 Harness」前台服务。
 *
 * 职责边界：
 *  - 只把本应用进程标记为前台服务，提升其在锁屏、返回桌面、划掉最近任务后的存活优先级；
 *  - 不执行任何 Shell 命令，不连接 Shizuku，不持有或读取任何凭据；
 *  - 通知内容固定，不含 URL、密码、API Key、终端输出或其他用户数据；
 *  - 明确不承诺绝对保活：Android 与厂商系统的内存回收、强制停止、电池与后台策略
 *    仍然可以随时结束本进程。
 *
 * 运行时归属：本服务只挂载/卸载 [RuntimeHost] 中的前台服务标记，真正的运行时由
 * [RuntimeHost] 统一持有；进程被系统重启后没有可管理的运行时时，服务立即结束，
 * 避免留下无法解释的通知。
 */
class HarnessKeepAliveService : Service() {
    /** 服务侧自有实例：不依赖插件是否存活，服务启停本身也要进诊断时间线。 */
    private val diagnostics by lazy { DiagnosticLog(this) }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须最先进入前台状态。
        //
        // Android 12+ 对 startForegroundService() 启动的服务有 5 秒硬性要求：超时未调用
        // startForeground() 会抛 ForegroundServiceDidNotStartInTimeException 并终结整个进程。
        // 先前「没有 controller 就直接 stopSelf()」的写法全程没有进入前台状态，只是运气好
        // 没触发；这里改成先无条件进入前台，再决定是否立即结束。
        startForegroundCompat()
        if (HarnessKeepAlivePolicy.shouldStopServiceWithoutController(RuntimeHost.controllerOrNull() != null)) {
            // 进程被系统回收后重启：运行时与临时认证凭据都已不存在，旧会话无法恢复。
            // 此时保持前台服务只会留下一个空转通知，因此立即结束，由界面提示重新连接。
            diagnostics.record(
                DiagnosticLevel.WARN,
                DiagnosticEvent.KEEP_ALIVE,
                mapOf("reason" to "no_runtime", "active" to "false"),
            )
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        RuntimeHost.attachForegroundService()
        diagnostics.record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.KEEP_ALIVE,
            mapOf("reason" to "foreground", "active" to "true"),
        )
        // 故意不使用 START_STICKY：系统重启本服务时进程内已无运行时，
        // 重新拉起只会产生一个无法自解释的通知。
        return START_NOT_STICKY
    }

    /**
     * 划掉最近任务时不主动调用 `stopSelf()`。
     *
     * 是否继续运行完全交给系统与厂商后台策略判断；本方法有意留空，不在此处
     * 终止服务，也不做任何保活承诺。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 有意留空：保留前台服务与运行时，由系统策略决定后续行为。
    }

    override fun onDestroy() {
        stopForegroundCompat()
        RuntimeHost.detachForegroundService()
        diagnostics.record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.KEEP_ALIVE,
            mapOf("reason" to "destroyed", "active" to "false"),
        )
        super.onDestroy()
    }

    /** 仅以启动方式运行，不提供绑定接口。 */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * 通知只使用固定资源文案：不含运行时地址、端口、凭据、版本、终端内容或会话标识，
     * 因而在锁屏与通知栏都不会泄露用户数据。
     */
    private fun buildNotification(): Notification {
        // 入口刻意不指向 singleTask 的 MainActivity：那会 clear-top 掉正在显示的
        // HarnessActivity 并撤销会话凭据。KeepAliveEntryActivity 只做透明转发。
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, KeepAliveEntryActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keep_alive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.keep_alive_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "harness_keep_alive"
        private const val NOTIFICATION_ID = 0x44534801

        /**
         * 启动前台服务。
         *
         * 系统可能因后台启动限制或厂商策略拒绝启动前台服务；此时静默降级，
         * Harness 继续按原有方式运行，只是失去优先级提升。
         */
        fun start(context: Context) {
            val intent = Intent(context, HarnessKeepAliveService::class.java)
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Throwable) {
                // 降级：不提升优先级，也不影响已启动的 Harness。
            }
        }

        /**
         * 显式停止：由 stopService 触发 [onDestroy]，统一走 [RuntimeHost.detachForegroundService]。
         */
        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(Intent(context.applicationContext, HarnessKeepAliveService::class.java))
            } catch (_: Throwable) {
                // 服务未运行时 stopService 本身即为无操作。
            }
        }
    }
}
