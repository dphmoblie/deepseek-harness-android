package io.deepseekharness.mobile.runtime

/**
 * 后台 AI 进行中任务通知的实时动态策略（纯 JVM，不依赖 Android 框架）。
 *
 * 将「平台能力探测 / 样式选择与降级链 / 短状态规范化 / 诊断原因」从反射和系统调用里拆出来，
 * 保证可以在 `:app:testDebugUnitTest` 中完整覆盖分支，避免只能在真机或 instrumentation 中盲测。
 */
internal object TaskLivePromotionPolicy {
    const val ANDROID_16_API_LEVEL = 36

    /**
     * 新建活跃任务专用静默高优通道。
     *
     * 旧通道 `dsh_active_tasks` 使用了 `IMPORTANCE_LOW`：Android 不允许原地提高既有通道的重要级别，
     * 而 HyperOS / MIUI 会把低重要级通道收进「不重要通知」并禁止状态栏实时胶囊与焦点/超级岛展示。
     * 迁到 `IMPORTANCE_DEFAULT` 并关闭声音与振动，既保持零铃声零震动零横幅打扰，
     * 又允许系统与厂商把正在运行的任务提升到顶部状态胶囊或超级岛。
     */
    const val ACTIVE_CHANNEL_ID = "dsh_active_tasks_v2"
    const val LEGACY_ACTIVE_CHANNEL_ID = "dsh_active_tasks"

    // 以下官方键值 / Intent 动作在 API 36 / androidx.core 1.17 才有编译期常量；
    // 在 compileSdk 35 下以字面量传递并在运行期按 SDK 版本/反射启用。
    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
    const val ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"

    enum class ActiveNotificationStrategy {
        /** Android 16+ 原生 `Notification.Builder` + `Notification.ProgressStyle`（无传统进度条）。 */
        FRAMEWORK_PROGRESS_STYLE,

        /** Android 16+ 原生 `Notification.Builder` 标准进行中样式（当 ProgressStyle 不可用或未过结构校验时回落）。 */
        FRAMEWORK_STANDARD,

        /** Android 15 及以下兼容路径，或原生反射整体异常时的最终兜底。 */
        COMPAT_PROGRESS,
    }

    /**
     * 返回当前系统版本应按顺序尝试的通知构建策略。
     *
     * Android 16 及以上优先使用原生 `ProgressStyle`（无分段的不确定进度条），
     * 避免传统 `setProgress(0, 0, true)` 被识别成普通下载进度条；
     * 低于 Android 16 直接走 `NotificationCompat`。
     */
    fun selectStrategies(sdkInt: Int): List<ActiveNotificationStrategy> =
        if (sdkInt >= ANDROID_16_API_LEVEL) {
            listOf(
                ActiveNotificationStrategy.FRAMEWORK_PROGRESS_STYLE,
                ActiveNotificationStrategy.FRAMEWORK_STANDARD,
                ActiveNotificationStrategy.COMPAT_PROGRESS,
            )
        } else {
            listOf(ActiveNotificationStrategy.COMPAT_PROGRESS)
        }

    /**
     * Android 16+ 上判断某个原生通知候选项是否可被采用。
     *
     * `hasPromotableCharacteristics()` 明确返回 `false` 时拒绝，继续尝试下一种策略；
     * 返回 `true` 或系统尚未提供该校验方法（`null`）时采用。
     */
    fun shouldAcceptFrameworkCandidate(hasPromotable: Boolean?): Boolean = hasPromotable != false

    /**
     * 收敛实况权限状态字符串，与前端 `NotificationPermission` 枚举对齐：
     * `"granted"` / `"prompt"` / `"unsupported"`。
     */
    fun promotedPermissionState(sdkInt: Int, canPostPromoted: Boolean?): String = when {
        sdkInt < ANDROID_16_API_LEVEL || canPostPromoted == null -> "unsupported"
        canPostPromoted -> "granted"
        else -> "prompt"
    }

    fun normalizeShortStatus(shortStatus: String): String = shortStatus.trim()

    /**
     * 生成去重诊断原因码（仅允许 `[a-z0-9._-]` 受控令牌，满足 `DiagnosticPolicy` 白名单）。
     *
     * 只在可明确解释「为何无法提升展示」时返回原因：
     * - 结构未通过系统 `hasPromotableCharacteristics()` 校验 → `live_notification_unpromotable`
     * - 系统支持 Live Updates 但当前应用未获授权（常见于侧载安装包 / HyperOS 开关未开）→ `live_notification_denied`
     */
    fun diagnosticReason(
        promotedPermission: String,
        hasPromotable: Boolean?,
    ): String? = when {
        hasPromotable == false -> "live_notification_unpromotable"
        promotedPermission == "prompt" -> "live_notification_denied"
        else -> null
    }

    /**
     * 判断本次诊断原因是否需要写入日志。
     *
     * 当 `currentReason` 为 `null`（即权限已恢复且通知结构可晋升）时不写日志，调用方应同步将
     * 上一次记录的原因复位为 `null`，确保后续同一次运行内权限再次被撤销时仍能记录告警。
     */
    fun shouldRecordDiagnostic(
        previousReason: String?,
        currentReason: String?,
    ): Boolean = currentReason != null && currentReason != previousReason
}
