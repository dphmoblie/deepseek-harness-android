package io.deepseekharness.mobile.shizuku

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.AppForeground
import io.deepseekharness.mobile.AppLanguage
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.RootCommandApprovalActivity
import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 宿主侧高危/极危根命令确认协调器。
 *
 * 为什么必须放在 Android 宿主进程而不是 PRoot 访客内：
 * - PRoot 访客内所有进程共享同一 Linux UID，均可读到设备桥令牌（见 `docs/design/设备桥信任边界.md`）；
 * - 只有把 [RootCommandGuard.ApprovalRoute.HOST_CONFIRM]（Tier 2）与
 *   [RootCommandGuard.ApprovalRoute.APP_WARNING]（Tier 3）的最终决策留在宿主 JVM 内存中、
 *   由未导出的原生 `RootCommandApprovalActivity` 收集人类显式确认，访客才无法伪造「已同意」。
 *
 * 并发策略：单槽（single-flight）。同时只允许一条高危/极危命令处于待确认态，
 * 避免多个阻塞请求占满 `DeviceBridgeServer` 的工作线程池；第二条并发请求立刻以
 * `ROOT_APPROVAL_BUSY` 快速失败。
 */
object RootApprovalCoordinator {

    const val DEFAULT_TIMEOUT_MS = 120_000L
    const val CHANNEL_ID = "dsh_root_approval"
    const val NOTIFICATION_ID = 0x44534807
    const val MAX_JUSTIFICATION_CHARS = 512

    enum class Outcome {
        APPROVED,
        REJECTED,
        CANCELLED,
    }

    data class HostApprovalRequest(
        val requestId: String,
        val script: String,
        val scriptSha256: String,
        val assessment: RootCommandGuard.Assessment,
        val justification: String,
        val degraded: Boolean,
        val degradations: List<String>,
        val createdAtMs: Long,
        val timeoutMs: Long,
    )

    private class ActiveRequest(
        val request: HostApprovalRequest,
        val context: Context?,
        val decision: CompletableFuture<Outcome> = CompletableFuture(),
    )

    private val active = AtomicReference<ActiveRequest?>(null)

    /** 测试注入点：默认通过前台 Activity / 悬浮窗或高优先级通知拉起 [RootCommandApprovalActivity]。 */
    @Volatile
    var presenter: ((Context?, HostApprovalRequest) -> Boolean)? = null

    /** 测试注入点：结单时清理提醒通知。 */
    @Volatile
    var dismissNotificationHook: ((Context?) -> Unit)? = null

    /**
     * 向宿主发起一次不可被访客伪造的确认请求，并阻塞等待人类在 App 页作出决定。
     *
     * @return 人类明确确认后签发的 [RootExecutionGrant]；拒绝、取消、超时或无法拉起界面一律抛受控 [RuntimeFailure]。
     */
    fun requestApproval(
        context: Context?,
        requestId: String,
        script: String,
        assessment: RootCommandGuard.Assessment,
        justification: String = "",
        degraded: Boolean = false,
        degradations: List<String> = emptyList(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RootExecutionGrant {
        require(assessment.requiresHostConfirm) {
            "只有 HOST_CONFIRM / APP_WARNING 路由需要宿主确认，实际为 ${assessment.route}"
        }
        val cleanJustification = sanitizeJustification(justification)
        val request = HostApprovalRequest(
            requestId = requestId,
            script = script,
            scriptSha256 = RootApprovalRegistry.sha256(script),
            assessment = assessment,
            justification = cleanJustification,
            degraded = degraded,
            degradations = degradations,
            createdAtMs = System.currentTimeMillis(),
            timeoutMs = timeoutMs,
        )
        val entry = ActiveRequest(request, context?.applicationContext)
        if (!active.compareAndSet(null, entry)) {
            throw RuntimeFailure(
                "ROOT_APPROVAL_BUSY",
                "已有一条高风险根命令正在等待应用内确认，请先完成或取消当前确认",
            )
        }
        try {
            val presented = present(entry.context, request)
            if (!presented) {
                throw RuntimeFailure(
                    "ROOT_APPROVAL_UNAVAILABLE",
                    "无法打开应用警告确认页，请先打开 DeepSeek Harness 应用后重试",
                )
            }
            val outcome = try {
                entry.decision.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                throw RuntimeFailure("ROOT_APPROVAL_TIMEOUT", "等待应用内确认高风险根命令超时")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeFailure("ROOT_APPROVAL_CANCELLED", "高风险根命令确认已中断", error)
            }
            return when (outcome) {
                Outcome.APPROVED -> RootExecutionGrant(
                    scriptSha256 = request.scriptSha256,
                    assessment = assessment,
                    source = if (assessment.route == RootCommandGuard.ApprovalRoute.APP_WARNING) {
                        RootApprovalSource.APP_WARNING_CONFIRMED
                    } else {
                        RootApprovalSource.HOST_CONFIRMED
                    },
                )
                Outcome.REJECTED -> throw RuntimeFailure(
                    "ROOT_APPROVAL_REJECTED",
                    "用户在应用警告页拒绝了该高风险根命令",
                )
                Outcome.CANCELLED -> throw RuntimeFailure(
                    "ROOT_APPROVAL_CANCELLED",
                    "高风险根命令确认已取消",
                )
            }
        } finally {
            active.compareAndSet(entry, null)
            dismissNotification(entry.context)
            dismissApprovalActivity(request.requestId)
        }
    }

    /** 查询指定 [requestId] 对应的在途请求（供 [RootCommandApprovalActivity] 渲染）。 */
    fun currentRequest(requestId: String): HostApprovalRequest? {
        val current = active.get() ?: return null
        return if (current.request.requestId == requestId && !current.decision.isDone) current.request else null
    }

    /** 是否有尚未结单的宿主确认请求。 */
    fun hasPendingRequest(): Boolean {
        val current = active.get() ?: return false
        return !current.decision.isDone
    }

    /**
     * 当用户回到 `MainActivity` / `HarnessActivity` 时若存在在途请求，自动拉起应用警告页。
     * 这保证了即使应用在后台被 ROM 限制后台启动，用户一点开 App 就能立刻看到警告并处理。
     */
    fun presentIfPending(activity: Activity) {
        val current = active.get() ?: return
        if (current.decision.isDone) return
        if (activity is RootCommandApprovalActivity) return
        runCatching {
            activity.startActivity(RootCommandApprovalActivity.createIntent(activity, current.request.requestId))
        }
    }

    /** 由 [RootCommandApprovalActivity] 结单。 */
    fun resolve(requestId: String, outcome: Outcome): Boolean {
        val current = active.get() ?: return false
        if (current.request.requestId != requestId) return false
        return current.decision.complete(outcome)
    }

    /** 由设备桥 `shellApprovalCancel` 在访客取消轮次时中止等待并收起通知。 */
    fun cancel(requestId: String? = null): Boolean {
        val current = active.get() ?: return false
        if (requestId != null && requestId.isNotEmpty() && current.request.requestId != requestId) return false
        val completed = current.decision.complete(Outcome.CANCELLED)
        dismissNotification(current.context)
        dismissApprovalActivity(current.request.requestId)
        return completed
    }

    /** 桥关闭或运行时停止时统一结清在途确认，避免后台线程泄漏。 */
    fun cancelAll() {
        val current = active.getAndSet(null) ?: return
        current.decision.complete(Outcome.CANCELLED)
        dismissNotification(current.context)
        dismissApprovalActivity(current.request.requestId)
    }

    /** 纯文本清理：移除控制字符与 BiDi 视觉欺骗字符，限制长度。 */
    fun sanitizeJustification(raw: String): String {
        if (raw.isBlank()) return ""
        val out = StringBuilder(minOf(raw.length, MAX_JUSTIFICATION_CHARS))
        for (ch in raw) {
            if (out.length >= MAX_JUSTIFICATION_CHARS) break
            if (isDisallowedDisplayControl(ch)) continue
            out.append(if (ch == '\r' || ch == '\n' || ch == '\t') ' ' else ch)
        }
        return out.toString().trim()
    }

    /** 为警告页展示清理潜在的 BiDi 方向覆盖字符（避免脚本肉眼看起来与真实执行顺序相反）。 */
    fun sanitizeScriptForDisplay(script: String): Pair<String, Boolean> {
        var stripped = false
        val out = StringBuilder(script.length)
        for (ch in script) {
            if (isBidiOverride(ch)) {
                stripped = true
                continue
            }
            out.append(ch)
        }
        return out.toString() to stripped
    }

    private fun isBidiOverride(ch: Char): Boolean = ch in '\u202A'..'\u202E' ||
        ch in '\u2066'..'\u2069' ||
        ch == '\u200E' || ch == '\u200F' || ch == '\u061C'

    private fun isDisallowedDisplayControl(ch: Char): Boolean =
        (ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') || ch.code == 0x7F || isBidiOverride(ch)

    private fun present(context: Context?, request: HostApprovalRequest): Boolean {
        presenter?.let { return it(context, request) }
        val appContext = context ?: return false

        // 1. 若应用自己正在前台，直接从当前前台 Activity 打开警告页（无 Android 10+ 后台启动限制）。
        val foreground = AppForeground.currentActivity()
        if (foreground != null) {
            val started = runCatching {
                foreground.startActivity(RootCommandApprovalActivity.createIntent(foreground, request.requestId))
            }.isSuccess
            if (started) return true
        }

        // 2. 应用在后台（例如用户在外部浏览器或其他应用）：
        //    发高优先级提醒通知（已授予通知权限时），并在允许后台启动时尝试直接拉起 App；
        //    即便通知与后台弹页均受限，用户切回本应用时 MainActivity/HarnessActivity.onResume()
        //    也会通过 presentIfPending 自动弹出警告页，因此只要存在宿主 Context 即保持等待。
        postApprovalNotification(appContext, request)
        val canStartFromBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)
        if (canStartFromBackground) {
            runCatching {
                val intent = RootCommandApprovalActivity.createIntent(appContext, request.requestId).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                PendingIntent.getActivity(
                    appContext,
                    NOTIFICATION_ID,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ).send()
            }
        }
        return true
    }

    private fun postApprovalNotification(context: Context, request: HostApprovalRequest): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return@runCatching false
        }
        val localized = AppLanguage.localizedContext(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                localized.getString(R.string.root_approval_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = localized.getString(R.string.root_approval_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
        val intent = RootCommandApprovalActivity.createIntent(context, request.requestId).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val isCritical = request.assessment.route == RootCommandGuard.ApprovalRoute.APP_WARNING
        // 通知只使用固定文案，绝不包含脚本正文或任何设备/会话原文。
        val title = localized.getString(
            if (isCritical) R.string.root_approval_notification_critical_title
            else R.string.root_approval_notification_high_title,
        )
        val text = localized.getString(
            if (isCritical) R.string.root_approval_notification_critical_text
            else R.string.root_approval_notification_high_text,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }.getOrDefault(false)

    private fun dismissNotification(context: Context?) {
        dismissNotificationHook?.let {
            it(context)
            return
        }
        val appContext = context ?: return
        runCatching { NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID) }
    }

    private fun dismissApprovalActivity(requestId: String) {
        val current = AppForeground.currentActivity() as? RootCommandApprovalActivity ?: return
        runCatching { current.finishIfStale(requestId) }
    }
}
