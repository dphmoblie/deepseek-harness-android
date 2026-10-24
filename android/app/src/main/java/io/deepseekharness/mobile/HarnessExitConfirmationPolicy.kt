package io.deepseekharness.mobile

/**
 * DSH 页面退出确认策略。
 *
 * 只负责判断返回动作，不持有 Activity 或对话框，便于在 JVM 测试中覆盖误触保护。
 */
class HarnessExitConfirmationPolicy(
    private val confirmationWindowMs: Long = DEFAULT_CONFIRMATION_WINDOW_MS,
) {
    enum class Decision {
        /** WebView 仍有页面历史，交给页面自身回退。 */
        WEB_BACK,

        /** WebView 已到入口页，需要显示退出确认。 */
        PROMPT,

        /** 已在确认窗口内再次触发返回，允许退出。 */
        EXIT,
    }

    private var promptedAtMs: Long? = null

    fun onBackPressed(canGoBack: Boolean, nowMs: Long): Decision {
        if (canGoBack) {
            reset()
            return Decision.WEB_BACK
        }

        val previousPromptAtMs = promptedAtMs
        if (previousPromptAtMs != null && isWithinWindow(previousPromptAtMs, nowMs)) {
            reset()
            return Decision.EXIT
        }

        promptedAtMs = nowMs
        return Decision.PROMPT
    }

    /** 用户取消对话框或页面重新绑定后清除待确认状态。 */
    fun reset() {
        promptedAtMs = null
    }

    private fun isWithinWindow(previousPromptAtMs: Long, nowMs: Long): Boolean {
        if (confirmationWindowMs <= 0L || nowMs < previousPromptAtMs) return false
        return nowMs - previousPromptAtMs <= confirmationWindowMs
    }

    companion object {
        const val DEFAULT_CONFIRMATION_WINDOW_MS = 2_000L
    }
}
