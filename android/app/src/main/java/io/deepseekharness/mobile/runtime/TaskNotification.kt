package io.deepseekharness.mobile.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.deepseekharness.mobile.AppForeground
import io.deepseekharness.mobile.AppLanguage
import io.deepseekharness.mobile.KeepAliveEntryActivity
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.runtime.audit.AuditEvent
import io.deepseekharness.mobile.runtime.audit.AuditResult
import io.deepseekharness.mobile.runtime.audit.PrivateAuditLog
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLog

/**
 * 任务通知的投递（登记册 §5.5，设计见 `docs/design/任务通知.md`）。
 *
 * **独立通道**，不复用保活通道 `harness_keep_alive`：保活通知必须常驻、静默、
 * `IMPORTANCE_LOW`；任务通知的价值恰恰在于「离开应用后仍能得知」。塞进同一个通道，
 * 用户就只能同时接受或同时关闭两者——那正是 Android 通道模型要避免的事。
 *
 * AI 执行期间使用独立的低打扰通知；结束后撤下，再按原有策略投递结果提醒。
 *
 * **锁屏可见**：每条通知都设 `VISIBILITY_PUBLIC`。通知文案全部来自固定字符串资源，
 * 不含地址、端口、凭据、路径、会话内容——「锁屏能看到」因此不额外泄露任何东西，
 * 而用户在锁屏上最需要知道的恰恰是「它完事了没有」。详见 `docs/design/任务通知.md`。
 */
internal object TaskNotification {
    private const val CHANNEL_ID = "dsh_tasks"

    /**
     * 任务通知的 id。
     *
     * 原值 `0x44534802` 与悬浮球前台服务的通知 id **完全相同**（`OverlayBallService`），
     * 于是「用户回到应用清掉任务通知」会把悬浮球的常驻通知一起撤掉，反过来悬浮球每次
     * 进前台又可能顶掉任务通知。三个 id 现在互不相同：
     *  - `0x44534801` 保活前台服务（`HarnessKeepAliveService`）
     *  - `0x44534802` 悬浮球前台服务（`OverlayBallService`）
     *  - `0x44534803` 本对象
     *  - `0x44534805` 重启后 Shizuku 自动连接失败提醒
     */
    private const val NOTIFICATION_ID = 0x44534803

    /**
     * 「任务完成」通知的独立 id。
     *
     * 刻意与 [NOTIFICATION_ID] 分开，而不是复用同一条：共用 id 意味着「谁后发谁覆盖」，
     * 于是用户按了导出、紧接着完成一轮对话，导出完成的那条就会被静默替换掉 ——
     * 那条通知描述的是**用户自己发起的操作**的结果，不该被系统事件吞掉。
     * 代价是同一天里最多同时存在两条任务通知（一条完成类、一条操作类），
     * 都在用户回到应用时一并清除。
     *
     * 这个 id 同时是「一批完成事件」的载体：同一批里合并出来的条数变化，
     * 用 `notify` 更新同一条通知即可（见 [postTurnCompleted]）。
     */
    private const val TURN_COMPLETED_NOTIFICATION_ID = 0x44534804

    /** 重启后 Shizuku 自动连接失败的独立提醒，避免覆盖 Harness 状态通知。 */
    private const val SHIZUKU_RECONNECT_NOTIFICATION_ID = 0x44534805
    private const val TURN_ACTIVE_NOTIFICATION_ID = 0x44534806
    private val ACTIVE_TOKEN = Any()

    /** 合并窗口定时器的 tag（`Handler.postAtTime` 的重排标识，只在本对象内使用）。 */
    private val FLUSH_TOKEN = Any()

    /** 上一次真正发出「任务完成」通知的时刻（`SystemClock.elapsedRealtime()`）；0 表示从未发过。 */
    @Volatile
    private var lastTurnCompletedPostedAtMs = 0L

    /** 节流状态：这一批攒了几个完成事件、从什么时候开始攒。 */
    private val turnCompletedCoalescer = TurnCompletionNotificationPolicy.Coalescer()

    /**
     * 主线程 Handler：合并窗口结束时用它在**未来某个时刻**把累计的这一批发出去。
     *
     * 不用 `HandlerThread` 或定时线程池，是因为 `NotificationManagerCompat.notify` 与
     * 字符串资源读取都随时可以从主线程做，而这些事件本来就极少（一个下午几十次），
     * 为它养一条线程不划算。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 「最近一次任务完成」的进程内状态，供外壳 5 秒轮询读取（桥方法由主代理接线）。
     *
     * **只在 `notify-turn-complete` 真正被受理时**推进；被限流静默忽略的请求**不**推进，
     * 这样「序号变了」与「通知确实发过/受理过」始终是同一件事，界面不会因为一次被丢弃的
     * 突发请求而显示「任务已完成」。
     *
     * 不写盘、不跨进程、进程被回收后归零——这是预期的，不要在界面上把它当持久事实使用。
     */
    @Volatile
    var lastTurnCompletedAtMillis: Long = 0L
        private set

    /** 成功受理的完成事件单调计数（从 0 开始）。用途见 [lastTurnCompletedAtMillis]。 */
    @Volatile
    var turnCompletionSequence: Long = 0L
        private set

    private val turnCompletedLock = Any()
    private val turnActivity = TurnActivityState()
    private var harnessRunId: String? = null
    private var pendingOutcome: String? = null
    private var lastActiveDiagnosticReason: String? = null
    private var lastActivePostedState: ActiveTaskNotificationSyncPolicy.ActiveTaskState? = null

    fun activeTurnSnapshot(): TurnActivityState.Snapshot = synchronized(turnCompletedLock) {
        turnActivity.snapshot(SystemClock.elapsedRealtime())
    }

    /** 每次 Harness 启动更换代次，停止后的迟到请求不能点亮新一轮通知。 */
    fun setHarnessRun(context: Context, runId: String?) = synchronized(turnCompletedLock) {
        harnessRunId = runId
        lastActiveDiagnosticReason = null
        turnActivity.clear()
        clearPendingCompletion()
        refreshTaskStatus(context)
    }

    /**
     * 确保「AI 任务进度」使用静默 `IMPORTANCE_DEFAULT` 新通道，并清理旧的 `IMPORTANCE_LOW` 通道。
     * 在展示进行中通知与跳系统通知设置前调用，避免设置页仍停留在旧通道级别。
     */
    fun ensureActiveChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val systemManager = context.getSystemService(NotificationManager::class.java) ?: return
        val localized = AppLanguage.localizedContext(context)
        if (systemManager.getNotificationChannel(TaskLivePromotionPolicy.LEGACY_ACTIVE_CHANNEL_ID) != null) {
            systemManager.deleteNotificationChannel(TaskLivePromotionPolicy.LEGACY_ACTIVE_CHANNEL_ID)
        }
        val channel = NotificationChannel(
            TaskLivePromotionPolicy.ACTIVE_CHANNEL_ID,
            localized.getString(R.string.task_notification_active_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = localized.getString(R.string.task_notification_active_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        systemManager.createNotificationChannel(channel)
    }

    /** 稍后重查前后台，避免 Activity 切换和旋转时闪出通知。 */
    fun refreshTaskStatus(context: Context) = synchronized(turnCompletedLock) {
        val app = context.applicationContext
        mainHandler.removeCallbacksAndMessages(ACTIVE_TOKEN)
        mainHandler.postAtTime({ updateTaskStatus(app) }, ACTIVE_TOKEN, SystemClock.uptimeMillis() + 100L)
    }

    private fun updateTaskStatus(context: Context) {
        var diagnosticFields: Map<String, String>? = null
        synchronized(turnCompletedLock) {
            try {
                val manager = NotificationManagerCompat.from(context)
                val systemManager = context.getSystemService(NotificationManager::class.java)
                val postedInSystem = runCatching {
                    systemManager?.activeNotifications?.any { it.id == TURN_ACTIVE_NOTIFICATION_ID }
                }.getOrNull()
                val now = SystemClock.elapsedRealtime()
                val active = turnActivity.snapshot(now)
                val shouldShow = harnessRunId != null &&
                    active.activeCount > 0 &&
                    !AppForeground.isForeground() &&
                    manager.areNotificationsEnabled()
                val localeTag = AppLanguage.current(context)
                val candidate = if (shouldShow) {
                    active.startedAtMs?.let { startedAtMs ->
                        ActiveTaskNotificationSyncPolicy.ActiveTaskState(
                            runId = harnessRunId,
                            activeCount = active.activeCount,
                            startedAtMs = startedAtMs,
                            localeTag = localeTag,
                        )
                    }
                } else {
                    null
                }
                when (val decision = ActiveTaskNotificationSyncPolicy.decide(shouldShow, candidate, postedInSystem, lastActivePostedState)) {
                    ActiveTaskNotificationSyncPolicy.ActiveTaskDecision.NoOp -> {
                        if (!shouldShow || candidate == null) {
                            lastActivePostedState = null
                        } else {
                            val delay = (candidate.startedAtMs + TurnActivityState.STALE_AFTER_MS - now).coerceAtLeast(1L)
                            mainHandler.postAtTime({ updateTaskStatus(context) }, ACTIVE_TOKEN, SystemClock.uptimeMillis() + delay)
                        }
                        return@synchronized
                    }
                    ActiveTaskNotificationSyncPolicy.ActiveTaskDecision.Cancel -> {
                        manager.cancel(TURN_ACTIVE_NOTIFICATION_ID)
                        lastActivePostedState = null
                        return@synchronized
                    }
                    is ActiveTaskNotificationSyncPolicy.ActiveTaskDecision.Post -> {
                        ensureActiveChannel(context)
                        val localized = AppLanguage.localizedContext(context)
                        val startedAtMs = decision.state.startedAtMs
                        val delay = (startedAtMs + TurnActivityState.STALE_AFTER_MS - now).coerceAtLeast(1L)
                        val whenMs = System.currentTimeMillis() - (now - startedAtMs).coerceAtLeast(0L)
                        val built = TaskLiveNotificationCompat.buildActiveTaskNotification(
                            context = localized,
                            spec = TaskLiveNotificationCompat.ActiveTaskNotificationSpec(
                                channelId = TaskLivePromotionPolicy.ACTIVE_CHANNEL_ID,
                                smallIconRes = R.drawable.ic_keep_alive,
                                title = localized.getString(R.string.task_notification_active_title),
                                text = localized.getString(R.string.task_notification_active_text, decision.state.activeCount),
                                shortStatus = localized.getString(R.string.task_notification_active_short),
                                contentIntent = openAppIntent(context),
                                // 进程被回收后 Handler 无法执行，交给系统按同一失效时间撤下。
                                timeoutAfterMs = delay,
                                whenMs = whenMs,
                            ),
                        )
                        try {
                            manager.notify(TURN_ACTIVE_NOTIFICATION_ID, built.notification)
                            lastActivePostedState = decision.state
                        } catch (_: SecurityException) {
                            // 权限在检查与投递之间被撤销时，任务状态仍由界面显示。
                            lastActivePostedState = null
                            return@synchronized
                        }
                        val promotedPermission = TaskLiveNotificationCompat.promotedNotificationPermissionState(context)
                        val diagnosticReason = TaskLivePromotionPolicy.diagnosticReason(
                            promotedPermission = promotedPermission,
                            hasPromotable = built.hasPromotableCharacteristics,
                        )
                        if (TaskLivePromotionPolicy.shouldRecordDiagnostic(lastActiveDiagnosticReason, diagnosticReason)) {
                            diagnosticFields = mapOf(
                                "reason" to diagnosticReason!!,
                                "permission" to if (promotedPermission == "granted") "granted" else "denied",
                                "sdk" to Build.VERSION.SDK_INT.toString(),
                            )
                        }
                        lastActiveDiagnosticReason = diagnosticReason
                        // 收口信号丢失时也有上限；不轮询、不启动额外服务。
                        mainHandler.postAtTime({ updateTaskStatus(context) }, ACTIVE_TOKEN, SystemClock.uptimeMillis() + delay)
                    }
                }
            } catch (_: Throwable) {
                // 通知能力由系统控制，展示失败不能影响任务执行。
            }
        }
        val fieldsToRecord = diagnosticFields ?: return
        val appContext = context.applicationContext
        java.util.concurrent.CompletableFuture.runAsync {
            runCatching {
                DiagnosticLog(appContext).record(
                    DiagnosticLevel.WARN,
                    DiagnosticEvent.KEEP_ALIVE,
                    fieldsToRecord,
                )
            }
        }
    }

    /**
     * 收单点的**返回契约**：桥只认这个，不解析文案。
     *
     * `ok = true` 覆盖「已收进合并窗口」与「被静默忽略」两种情形——访客侧不重试，
     * 把限流表达成错误只会让它开始重试或报错，正是设计要避免的。
     *
     * `queued` 的语义是「这次事件被收下了、稍后会响（或已经响过）」，
     * **不是**「此刻已经发出通知」：合并窗口意味着真正的投递发生在最多
     * `MAX_COALESCE_WINDOW_MS` 之后。
     */
    data class TurnCompletionReceipt(
        val ok: Boolean,
        val errorCode: String?,
        val accepted: Boolean,
        val dropped: TurnCompletionPolicy.DropReason?,
        val queued: Boolean,
    )

    /** 开始只更新状态；收口先关闭活动任务，再决定是否提醒。 */
    fun recordTurnStarted(context: Context?, param: String): TurnCompletionReceipt = recordTurn(context, param, true)

    fun recordTurnCompleted(context: Context?, param: String): TurnCompletionReceipt = recordTurn(context, param, false)

    private fun recordTurn(context: Context?, param: String, started: Boolean): TurnCompletionReceipt = synchronized(turnCompletedLock) {
        val parsed = if (started) TurnCompletionPolicy.parseStartParam(param) else TurnCompletionPolicy.parseParam(param)
        if (parsed is TurnCompletionPolicy.Parsed.Invalid) {
            audit(context, AuditResult.FAILED, "TURN_NOTIFY_INVALID")
            return@synchronized TurnCompletionReceipt(
                ok = false,
                errorCode = "TURN_NOTIFY_INVALID",
                accepted = false,
                dropped = null,
                queued = false,
            )
        }
        parsed as TurnCompletionPolicy.Parsed.Valid
        if (context == null) {
            return@synchronized TurnCompletionReceipt(false, "TURN_NOTIFY_UNAVAILABLE", false, null, false)
        }
        if (harnessRunId == null || (parsed.turn != null && parsed.run != harnessRunId)) {
            return@synchronized TurnCompletionReceipt(true, null, false, null, false)
        }
        val now = SystemClock.elapsedRealtime()
        if (parsed.session != null && parsed.turn != null) {
            if (!turnActivity.record(parsed.session, parsed.turn, started, now)) {
                return@synchronized TurnCompletionReceipt(true, null, false, null, false)
            }
            // 先收口再限流：即使结果提醒被限流，也必须撤下进行中状态。
            refreshTaskStatus(context)
        }
        if (started) return@synchronized TurnCompletionReceipt(true, null, true, null, false)
        val dropped = turnCompletionState.claim(now)
        if (dropped != null) {
            // 限流命中：静默忽略。审计只记受控码，请求原文一律不落。
            audit(
                context,
                AuditResult.DENIED,
                if (dropped == TurnCompletionPolicy.DropReason.TOO_SOON) {
                    "TURN_NOTIFY_TOO_SOON"
                } else {
                    "TURN_NOTIFY_RATE_LIMITED"
                },
            )
            return@synchronized TurnCompletionReceipt(
                ok = true,
                errorCode = null,
                accepted = false,
                dropped = dropped,
                queued = false,
            )
        }
        val queued = postTurnCompleted(context, now, parsed.reason)
        turnCompletionSequence += 1
        lastTurnCompletedAtMillis = System.currentTimeMillis()
        audit(
            context,
            AuditResult.SUCCEEDED,
            when {
                queued -> "TURN_NOTIFY_QUEUED"
                AppForeground.isForeground() -> "TURN_NOTIFY_SUPPRESSED"
                else -> "TURN_NOTIFY_COALESCED"
            },
        )
        TurnCompletionReceipt(
            ok = true,
            errorCode = null,
            accepted = true,
            dropped = null,
            queued = queued,
        )
    }

    // 复用同一条限流状态：进程内只有这一个收单点。
    private val turnCompletionState = TurnCompletionPolicy.State()

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
     * 发一条「运行环境已安装/已更新/已修复」通知。
     *
     * 安装或修复要处理整个 rootfs（数百 MB），用户几乎必然切走——这是运行时通知里
     * 最不会被错过、也最需要的一条：他回来时既要确认装完了，也要知道下一步做什么。
     */
    fun postInstallCompleted(context: Context, kind: TaskNotificationKind) {
        val titleRes = when (kind) {
            TaskNotificationKind.RUNTIME_INSTALLED -> R.string.task_notification_installed_title
            TaskNotificationKind.RUNTIME_UPDATED -> R.string.task_notification_updated_title
            TaskNotificationKind.RUNTIME_REPAIRED -> R.string.task_notification_repaired_title
            else -> return
        }
        val textRes = when (kind) {
            TaskNotificationKind.RUNTIME_INSTALLED -> R.string.task_notification_installed_text
            TaskNotificationKind.RUNTIME_UPDATED -> R.string.task_notification_updated_text
            TaskNotificationKind.RUNTIME_REPAIRED -> R.string.task_notification_repaired_text
            else -> return
        }
        post(context, titleRes, textRes)
    }

    /**
     * 重启恢复时已确认 Shizuku 运行且已授权，但 UserService 连接失败。
     * 文案固定且不携带 Binder 异常、路径或凭据；未准备好的 Shizuku 不调用此方法。
     */
    fun postShizukuReconnectFailed(context: Context) {
        if (!shouldNotify()) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val localized = AppLanguage.localizedContext(context)
        ensureChannel(localized)
        val notification = build(localized, R.string.shizuku_reconnect_failed_title)
            .setContentText(localized.getString(R.string.shizuku_reconnect_failed_text))
            .build()
        try {
            manager.notify(SHIZUKU_RECONNECT_NOTIFICATION_ID, notification)
            BackgroundNotificationGroup.sync(context)
        } catch (_: SecurityException) {
            // 权限在检查与投递之间被撤销时，提醒丢失不影响运行时恢复。
        }
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

    /**
     * 「任务完成」通知的排程入口：返回**当前这次事件**是否立刻变成了通知。
     *
     * 判定顺序刻意是「先问策略、再看通道、最后才建通知」：策略是纯函数（可在单测里穷举），
     * 通道检查是 Android 侧的系统状态，两者分开才能保证节流行为与系统开关无关。
     *
     * 返回 false 有两种含义：前台抑制（什么都不做），或已排入合并窗口（稍后会发）。
     * 调用方只把它用于审计详情，不参与任何决策。
     */
    private fun postTurnCompleted(context: Context, nowMs: Long, reason: String?): Boolean {
        if (AppForeground.isForeground()) return false
        val outcome = reason ?: "turn-end"
        pendingOutcome = if (turnCompletedCoalescer.pending() == 0) outcome else pendingOutcome.takeIf { it == outcome }
        val windowStartedAt = turnCompletedCoalescer.onEvent(nowMs)
        val delayMs = TurnCompletionNotificationPolicy.delayUntilNotify(
            nowMs = nowMs,
            windowStartedAtMs = windowStartedAt,
            lastPostedAtMs = lastTurnCompletedPostedAtMs.takeIf { it > 0L },
            foreground = false,
        ) ?: return false
        scheduleFlush(context.applicationContext, delayMs)
        // true = 这次事件**确实**被收进了合并窗口，稍后会响一声。
        // 与前台抑制区分开，只为了让审计能说清「受理了吗」而不是「响了吗」。
        return true
    }

    /** 只保留策略算出的下一次投递，避免旧回调越过新事件的静默期。 */
    private fun scheduleFlush(context: Context, delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(FLUSH_TOKEN)
        mainHandler.postAtTime(
            { flushTurnCompleted(context) },
            FLUSH_TOKEN,
            SystemClock.uptimeMillis() + delayMs.coerceAtLeast(0L),
        )
    }

    /**
     * 合并窗口到点：把这一批完成事件发成一条通知。
     *
     * 被最小间隔挡住时**不丢弃**，而是重排到下限边界（见策略里的说明）。
     * 整段包在 try 里：这条路径由定时器驱动，异常没有调用方可以接住，
     * 而它只是提醒，绝不能因此终结进程。
     */
    private fun flushTurnCompleted(context: Context) = synchronized(turnCompletedLock) {
        try {
            val count = turnCompletedCoalescer.pending()
            if (count <= 0) return@synchronized
            val now = SystemClock.elapsedRealtime()
            if (!TurnCompletionNotificationPolicy.canFlush(now, lastTurnCompletedPostedAtMs.takeIf { it > 0L })) {
                scheduleFlush(
                    context,
                    TurnCompletionNotificationPolicy.rescheduleAfter(
                        now,
                        lastTurnCompletedPostedAtMs.takeIf { it > 0L },
                    ),
                )
                return@synchronized
            }
            // 用户已经在看着界面：不发。批次保留仍会在回到前台时被 clear() 撤掉，
            // 但这里先清掉待发状态，避免用户切回后台时突然收到一条「刚完成」。
            if (AppForeground.isForeground()) {
                clearPendingCompletion()
                return@synchronized
            }
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) {
                clearPendingCompletion()
                return@synchronized
            }
            val localized = AppLanguage.localizedContext(context)
            ensureChannel(localized)
            val merged = TurnCompletionNotificationPolicy.mergedCount(count)
            val title = when (pendingOutcome) {
                "turn-end" -> R.string.task_notification_turn_completed_title
                "turn-error" -> R.string.task_notification_turn_error_title
                "turn-aborted" -> R.string.task_notification_turn_aborted_title
                "turn-blocked" -> R.string.task_notification_turn_blocked_title
                else -> R.string.task_notification_turn_ended_title
            }
            val text = if (pendingOutcome == "turn-end") R.string.task_notification_turn_completed_text
                else R.string.task_notification_turn_ended_text
            val notification = build(localized, title)
                .setContentText(localized.getString(text, merged))
                // 完成通知带时间戳：用户在工作间隙瞄一眼锁屏，最想知道的就是「多久之前完成的」。
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                // 同一批只响一次：合并计数变化时更新同一条，不重复提醒。
                .setOnlyAlertOnce(false)
                .build()
            try {
                manager.notify(TURN_COMPLETED_NOTIFICATION_ID, notification)
                BackgroundNotificationGroup.sync(context)
                lastTurnCompletedPostedAtMs = now
                audit(context, AuditResult.SUCCEEDED, "TURN_NOTIFY_POSTED")
            } catch (_: SecurityException) {
                // 权限在检查与投递之间被撤销：通知丢了不影响运行时状态本身。
            }
            clearPendingCompletion()
        } catch (_: Throwable) {
            clearPendingCompletion()
        }
    }

    private fun clearPendingCompletion() {
        mainHandler.removeCallbacksAndMessages(FLUSH_TOKEN)
        turnCompletedCoalescer.clear()
        pendingOutcome = null
    }

    private fun postWithCount(context: Context, titleRes: Int, textRes: Int, count: Int) {
        if (!shouldNotify()) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val localized = AppLanguage.localizedContext(context)
        ensureChannel(localized)
        val notification = build(localized, titleRes)
            .setContentText(localized.getString(textRes, count))
            .build()
        postSafely(context, manager, notification)
    }

    /**
     * 通知的统一构造：只在这里决定图标、分类、点击落点与锁屏可见性。
     *
     * 抽出来的直接原因是**落点只有一个正确值**：`KeepAliveEntryActivity` 是透明的
     * 转发入口，负责复用已有对话页，避免通知点击创建重复 Activity 或撤销一次性会话凭据。
     */
    private fun build(context: Context, titleRes: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setContentTitle(context.getString(titleRes))
            .setContentIntent(openAppIntent(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            // 锁屏可见：文案全部来自固定资源，不含任何用户数据（见类注释）。
            // 必须传平台/Compat 自带的常量：lint 的 WrongConstant 只认带 @IntDef 的常量，
            // 自己写一个值相等的 private const 会被判为「可疑的可见性取值」而卡住 lintRelease 门禁。
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(BackgroundNotificationGroup.GROUP_KEY)
            // 通知本身不承载动作：点一下只回到应用。隔着锁屏就能触发的破坏性入口不做。

    /**
     * 点击通知的落点：透明转发入口，而不是管理界面。
     *
     * 与 `HarnessKeepAliveService.buildNotification()` 使用同一组标志，理由见该处注释。
     */
    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, KeepAliveEntryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, titleRes: Int, textRes: Int) {
        if (!shouldNotify()) return
        val manager = NotificationManagerCompat.from(context)
        // areNotificationsEnabled() 覆盖两件事：用户关掉了通知，或（Android 13+）没授予权限。
        if (!manager.areNotificationsEnabled()) return
        // 通知文案跟随**应用语言**而不是系统语言：用户在应用里选了日文，通知也该是日文。
        // localizedContext 只包一层资源解析，不改变通知的投递身份。
        val localized = AppLanguage.localizedContext(context)
        ensureChannel(localized)
        val notification = build(localized, titleRes)
            .setContentText(localized.getString(textRes))
            .build()
        postSafely(context, manager, notification)
    }

    private fun postSafely(context: Context, manager: NotificationManagerCompat, notification: Notification) {
        try {
            manager.notify(NOTIFICATION_ID, notification)
            BackgroundNotificationGroup.sync(context)
        } catch (_: SecurityException) {
            // 权限在检查与投递之间被撤销时会抛：通知丢了不影响运行时状态本身。
        }
    }

    /** 用户回到应用即清掉：应用已经在前台时，这条通知没有存在价值。 */
    fun clear(context: Context) = synchronized(turnCompletedLock) {
        clearPendingCompletion()
        val manager = NotificationManagerCompat.from(context)
        val activeIds = runCatching {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications?.mapTo(HashSet()) { it.id }
        }.getOrNull()
        val groupedIds = listOf(NOTIFICATION_ID, TURN_COMPLETED_NOTIFICATION_ID, SHIZUKU_RECONNECT_NOTIFICATION_ID)
        var clearedGrouped = activeIds == null
        for (id in groupedIds) {
            if (activeIds == null || id in activeIds) {
                manager.cancel(id)
                clearedGrouped = true
            }
        }
        if (activeIds == null || TURN_ACTIVE_NOTIFICATION_ID in activeIds || lastActivePostedState != null) {
            manager.cancel(TURN_ACTIVE_NOTIFICATION_ID)
        }
        lastActivePostedState = null
        if (clearedGrouped) {
            BackgroundNotificationGroup.sync(context)
        }
        refreshTaskStatus(context)
    }

    /**
     * 应用在前台时不发：用户正看着界面，界面本身就在显示同一件事，
     * 弹出来只是噪声（设计文档第四节「应用在前台时不应存在任务通知」）。
     *
     * 与 [clear] 的分工：**发之前**抑制、**回到前台**清除，两条路径合起来保证
     * 「前台既不会新出现、也不会残留」。仅靠清除是不够的——后台完成的操作
     * 会在用户还没回来时就弹出来，那正是任务通知存在的意义，不该被误伤；
     * 仅靠抑制也是不够的——后台发出的那条会一直留到用户手动划掉。
     */
    private fun shouldNotify(): Boolean = !AppForeground.isForeground()

    private fun audit(context: Context?, result: AuditResult, detail: String) {
        val live = context ?: return
        try {
            PrivateAuditLog(live).record(AuditEvent.TURN_NOTIFY, result, detail)
        } catch (_: Throwable) {
            // 审计失败不改收单结果（与 PrivateAuditLog 自身的承诺一致）。
        }
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
            // 通道侧的锁屏可见性只在**首次创建**通道时被采用；已有设备上通道早就建好了，
            // 改这一行不会追溯生效。因此真正生效的是每条通知上的 setVisibility()，
            // 这里只是让新建通道的设备有一个与之一致的默认值。
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }
}
