package io.deepseekharness.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.app.KeyguardManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import io.deepseekharness.mobile.runtime.audit.AuditEvent
import io.deepseekharness.mobile.runtime.audit.AuditResult
import io.deepseekharness.mobile.runtime.audit.PrivateAuditLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 需要用户在系统设置中手动开启的无障碍服务。
 *
 * 服务只保存当前窗口的短时引用，不监听或上传事件流；动作必须来自白名单应用、
 * 稳定的完整 viewId，并且由原生策略再次检查锁屏、敏感窗口与频率限制。
 */
class DeepSeekAccessibilityService : AccessibilityService() {
    private var lastActionAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingConfirmation = AtomicReference<PendingConfirmation?>(null)

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceRef.set(this)
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100L
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = cancelPendingConfirmation()

    override fun onDestroy() {
        cancelPendingConfirmation()
        serviceRef.compareAndSet(this, null)
        super.onDestroy()
    }

    @Synchronized
    fun execute(command: String, param: String): DeviceCommandResult {
        val result = executeChecked(command, param)
        val event = if (command == "accessibilityAction") AuditEvent.ACCESSIBILITY_ACTION else AuditEvent.ACCESSIBILITY_READ
        val auditResult = when {
            result.ok -> AuditResult.SUCCEEDED
            result.errorCode?.let(CONFIRMATION_CANCEL_CODES::contains) == true -> AuditResult.CANCELLED
            else -> AuditResult.DENIED
        }
        PrivateAuditLog(this).record(event, auditResult, result.errorCode)
        return result
    }

    private fun executeChecked(command: String, param: String): DeviceCommandResult {
        val now = SystemClock.elapsedRealtime()
        if (isLockedOrScreenOff()) return failure("ACCESSIBILITY_DEVICE_LOCKED", "设备已锁定或屏幕未交互")
        if (now - lastActionAt < ACTION_INTERVAL_MS && command == "accessibilityAction") {
            return failure("ACCESSIBILITY_RATE_LIMITED", "无障碍动作过于频繁，请稍后再试")
        }
        val root = rootInActiveWindow ?: return failure("ACCESSIBILITY_WINDOW_UNAVAILABLE", "当前没有可读取的应用窗口")
        val packageName = root.packageName?.toString().orEmpty()
        val allowed = AccessibilityAutomationStore.allowedPackages(this)
        if (!AccessibilityAutomationPolicy.validPackage(packageName) || packageName !in allowed) {
            return failure("ACCESSIBILITY_PACKAGE_DENIED", "当前应用不在无障碍自动化白名单中")
        }
        if (containsSensitiveWindow(root)) return failure("ACCESSIBILITY_SENSITIVE_WINDOW", "检测到密码、验证码、支付或权限窗口，已拒绝自动化")
        return when (command) {
            "accessibilityTree" -> if (param.isBlank()) tree(root, packageName)
            else failure("ACCESSIBILITY_ACTION_INVALID", "无障碍层级读取不接受参数")
            "accessibilityAction" -> action(root, packageName, param).also { if (it.ok) lastActionAt = now }
            else -> failure("DEVICE_COMMAND_INVALID", "无障碍命令不受支持")
        }
    }

    private fun tree(root: AccessibilityNodeInfo, packageName: String): DeviceCommandResult {
        val result = JSONObject().put("packageName", packageName)
        val nodes = JSONArray()
        appendNodes(root, nodes, 0)
        result.put("nodes", nodes)
        return DeviceCommandResult(true, 0, result.toString(), nodes.length() >= MAX_TREE_NODES, null)
    }

    private fun appendNodes(node: AccessibilityNodeInfo?, result: JSONArray, depth: Int) {
        if (node == null || depth > MAX_TREE_DEPTH || result.length() >= MAX_TREE_NODES) return
        val viewId = node.viewIdResourceName
        val text = if (node.isEditable) "" else safeText(node.text)
        val hint = if (node.isEditable) "" else safeText(node.hintText)
        val description = if (node.isEditable) "" else safeText(node.contentDescription)
        val bounds = Rect().also(node::getBoundsInScreen)
        val entry = JSONObject()
            .put("className", node.className?.toString()?.take(120).orEmpty())
            .put("viewId", viewId.orEmpty())
            .put("text", text)
            .put("hint", hint)
            .put("contentDescription", description)
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("scrollable", node.isScrollable)
            .put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
        result.put(entry)
        for (index in 0 until node.childCount) {
            if (result.length() >= MAX_TREE_NODES) break
            appendNodes(node.getChild(index), result, depth + 1)
        }
    }

    private fun action(root: AccessibilityNodeInfo, packageName: String, param: String): DeviceCommandResult {
        val request = AccessibilityAutomationPolicy.parseAction(param)
            ?: return failure("ACCESSIBILITY_ACTION_INVALID", "无障碍动作参数无效")
        if (request.packageName != packageName) return failure("ACCESSIBILITY_PACKAGE_DENIED", "动作目标不是当前前台应用")
        val node = findByViewId(root, request.viewId)
            ?: return failure("ACCESSIBILITY_NODE_NOT_FOUND", "未找到唯一的目标节点")
        if (node.packageName?.toString() != packageName || node.isPassword ||
            AccessibilityAutomationPolicy.containsSensitiveText(node.viewIdResourceName)
        ) return failure("ACCESSIBILITY_ACTION_REJECTED", "目标节点不允许此操作")
        when (requestConfirmation(request)) {
            ConfirmationOutcome.APPROVED -> Unit
            ConfirmationOutcome.REJECTED -> return failure("ACCESSIBILITY_CONFIRM_REJECTED", "用户拒绝了无障碍动作")
            ConfirmationOutcome.TIMEOUT -> return failure("ACCESSIBILITY_CONFIRM_TIMEOUT", "无障碍动作确认已超时")
            ConfirmationOutcome.CANCELLED -> return failure("ACCESSIBILITY_CONFIRM_CANCELLED", "无障碍动作确认已取消")
        }
        return executeConfirmedAction(request)
    }

    /** 在无障碍覆盖层中显示一次性确认，等待发生在设备桥工作线程。 */
    private fun requestConfirmation(request: AccessibilityAutomationPolicy.ActionRequest): ConfirmationOutcome {
        val confirmation = PendingConfirmation(request)
        if (!pendingConfirmation.compareAndSet(null, confirmation)) return ConfirmationOutcome.CANCELLED
        mainHandler.post { showConfirmation(confirmation) }
        return try {
            if (!confirmation.latch.await(CONFIRMATION_TIMEOUT_MS, TimeUnit.SECONDS)) {
                confirmation.cancelled.set(true)
                ConfirmationOutcome.TIMEOUT
            } else if (confirmation.cancelled.get()) {
                ConfirmationOutcome.CANCELLED
            } else if (confirmation.approved.get()) {
                ConfirmationOutcome.APPROVED
            } else ConfirmationOutcome.REJECTED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            confirmation.cancelled.set(true)
            ConfirmationOutcome.CANCELLED
        } finally {
            pendingConfirmation.compareAndSet(confirmation, null)
            mainHandler.post { removeConfirmation(confirmation) }
        }
    }

    private fun showConfirmation(confirmation: PendingConfirmation) {
        if (confirmation.cancelled.get() || pendingConfirmation.get() !== confirmation) return
        val windowManager = getSystemService(WindowManager::class.java) ?: return finishConfirmation(confirmation, false)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 24)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "确认无障碍动作"
            textSize = 19f
            setTextColor(Color.BLACK)
        }
        val details = TextView(this).apply {
            val input = confirmation.request.text?.length?.toString() ?: "0"
            text = "应用：${confirmation.request.packageName}\n动作：${confirmation.request.action}\nviewId：${confirmation.request.viewId}\n输入长度：$input"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 16, 0, 18)
        }
        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        val reject = Button(this).apply {
            text = "拒绝"
            setOnClickListener { finishConfirmation(confirmation, false) }
        }
        val approve = Button(this).apply {
            text = "确认执行"
            setOnClickListener { finishConfirmation(confirmation, true) }
        }
        buttons.addView(reject)
        buttons.addView(approve)
        panel.addView(title)
        panel.addView(details)
        panel.addView(buttons)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 72
            this.title = "Harness 无障碍确认"
        }
        try {
            windowManager.addView(panel, params)
            confirmation.windowManager = windowManager
            confirmation.panel = panel
        } catch (_: Throwable) {
            finishConfirmation(confirmation, false)
        }
    }

    private fun finishConfirmation(confirmation: PendingConfirmation, approved: Boolean) {
        confirmation.approved.set(approved)
        if (!confirmation.latch.countDownIfOpen()) return
    }

    private fun removeConfirmation(confirmation: PendingConfirmation) {
        val panel = confirmation.panel ?: return
        try {
            confirmation.windowManager?.removeViewImmediate(panel)
        } catch (_: Throwable) {
        }
        confirmation.panel = null
        confirmation.windowManager = null
    }

    private fun cancelPendingConfirmation() {
        pendingConfirmation.get()?.let {
            it.cancelled.set(true)
            finishConfirmation(it, false)
            mainHandler.post { removeConfirmation(it) }
        }
    }

    /** 确认后重新获取当前窗口并在无障碍主线程执行节点动作。 */
    private fun executeConfirmedAction(request: AccessibilityAutomationPolicy.ActionRequest): DeviceCommandResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return performConfirmedAction(request)
        val latch = CountDownLatch(1)
        val result = AtomicReference<DeviceCommandResult>()
        mainHandler.post {
            result.set(performConfirmedAction(request))
            latch.countDown()
        }
        return try {
            if (latch.await(CONFIRMATION_TIMEOUT_MS, TimeUnit.SECONDS)) {
                result.get() ?: failure("ACCESSIBILITY_ACTION_CANCELLED", "无障碍动作未完成")
            } else failure("ACCESSIBILITY_ACTION_TIMEOUT", "无障碍动作执行超时")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failure("ACCESSIBILITY_ACTION_CANCELLED", "无障碍动作已取消")
        }
    }

    private fun performConfirmedAction(request: AccessibilityAutomationPolicy.ActionRequest): DeviceCommandResult {
        val now = SystemClock.elapsedRealtime()
        if (isLockedOrScreenOff()) return failure("ACCESSIBILITY_DEVICE_LOCKED", "设备已锁定或屏幕未交互")
        if (now - lastActionAt < ACTION_INTERVAL_MS) return failure("ACCESSIBILITY_RATE_LIMITED", "无障碍动作过于频繁，请稍后再试")
        val root = rootInActiveWindow ?: return failure("ACCESSIBILITY_WINDOW_UNAVAILABLE", "当前没有可读取的应用窗口")
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName != request.packageName || packageName !in AccessibilityAutomationStore.allowedPackages(this)) {
            return failure("ACCESSIBILITY_PACKAGE_DENIED", "确认后前台应用已变化")
        }
        if (containsSensitiveWindow(root)) return failure("ACCESSIBILITY_SENSITIVE_WINDOW", "确认后检测到敏感窗口")
        val node = findByViewId(root, request.viewId)
            ?: return failure("ACCESSIBILITY_NODE_NOT_FOUND", "确认后未找到唯一的目标节点")
        if (node.packageName?.toString() != packageName || node.isPassword ||
            AccessibilityAutomationPolicy.containsSensitiveText(node.viewIdResourceName)
        ) return failure("ACCESSIBILITY_ACTION_REJECTED", "确认后目标节点不允许此操作")
        val success = when (request.action) {
            "click" -> node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "setText" -> if (!node.isEditable || request.text == null || AccessibilityAutomationPolicy.containsSensitiveText(request.text)) false
            else node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.text)
            })
            "scroll" -> node.isScrollable && node.performAction(
                if (request.direction == "forward") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            )
            else -> false
        }
        return if (success) {
            lastActionAt = now
            DeviceCommandResult(true, 0, "操作已执行", false, null)
        } else failure("ACCESSIBILITY_ACTION_REJECTED", "目标节点不允许此操作")
    }

    private fun findByViewId(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        val matches = root?.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
        return matches.singleOrNull()
    }

    private fun safeText(value: CharSequence?): String {
        val text = value?.toString().orEmpty()
        if (text.length > MAX_TEXT_CHARS) return text.take(MAX_TEXT_CHARS)
        return text
    }

    private fun containsSensitiveWindow(root: AccessibilityNodeInfo): Boolean {
        var found = false
        var scanned = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found) return
            if (depth > MAX_TREE_DEPTH) {
                found = true
                return
            }
            if (++scanned > MAX_SENSITIVE_SCAN_NODES) {
                // 无法在有界时间内确认窗口安全时按敏感窗口拒绝。
                found = true
                return
            }
            if (node.isPassword || AccessibilityAutomationPolicy.containsSensitiveText(node.viewIdResourceName) ||
                AccessibilityAutomationPolicy.containsSensitiveText(node.text) ||
                AccessibilityAutomationPolicy.containsSensitiveText(node.hintText) ||
                AccessibilityAutomationPolicy.containsSensitiveText(node.contentDescription)
            ) {
                found = true
                return
            }
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(root, 0)
        return found
    }

    private fun isLockedOrScreenOff(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        return keyguard?.isKeyguardLocked == true || power?.isInteractive == false
    }

    private fun failure(code: String, text: String): DeviceCommandResult = DeviceCommandResult(false, 1, text, false, code)

    companion object {
        private const val ACTION_INTERVAL_MS = 350L
        private const val MAX_TREE_NODES = 120
        private const val MAX_TREE_DEPTH = 24
        private const val MAX_SENSITIVE_SCAN_NODES = 320
        private const val MAX_TEXT_CHARS = 80
        private const val CONFIRMATION_TIMEOUT_MS = 30L
        private val CONFIRMATION_CANCEL_CODES = setOf(
            "ACCESSIBILITY_CONFIRM_REJECTED",
            "ACCESSIBILITY_CONFIRM_CANCELLED",
            "ACCESSIBILITY_CONFIRM_TIMEOUT",
            "ACCESSIBILITY_ACTION_CANCELLED",
            "ACCESSIBILITY_ACTION_TIMEOUT",
        )
        private val serviceRef = AtomicReference<DeepSeekAccessibilityService?>(null)

        fun current(): DeepSeekAccessibilityService? = serviceRef.get()
    }

    private enum class ConfirmationOutcome { APPROVED, REJECTED, TIMEOUT, CANCELLED }

    private class PendingConfirmation(
        val request: AccessibilityAutomationPolicy.ActionRequest,
    ) {
        val latch = OpenCountDownLatch()
        val approved = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        var windowManager: WindowManager? = null
        var panel: View? = null
    }

    /** CountDownLatch 没有公开判断是否已完成，这个包装避免重复点击改变审批结果。 */
    private class OpenCountDownLatch {
        private val delegate = CountDownLatch(1)
        private val open = AtomicBoolean(true)
        fun await(timeout: Long, unit: TimeUnit): Boolean = delegate.await(timeout, unit)
        fun countDownIfOpen(): Boolean = open.compareAndSet(true, false).also { if (it) delegate.countDown() }
    }
}
