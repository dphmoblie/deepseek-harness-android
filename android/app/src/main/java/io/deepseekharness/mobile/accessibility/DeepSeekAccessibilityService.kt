package io.deepseekharness.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
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
import android.view.accessibility.AccessibilityWindowInfo
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
        val event = if (command in setOf("accessibilityAction", "tap", "inputText")) AuditEvent.ACCESSIBILITY_ACTION else AuditEvent.ACCESSIBILITY_READ
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
        if (now - lastActionAt < ACTION_INTERVAL_MS && command in setOf("accessibilityAction", "tap", "inputText")) {
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
            "tap", "inputText" -> observedAction(root, packageName, command, param).also { if (it.ok) lastActionAt = now }
            else -> failure("DEVICE_COMMAND_INVALID", "无障碍命令不受支持")
        }
    }

    /** 坐标及输入工具复用节点动作：白名单、敏感窗口和设备端确认均不能被绕过。 */
    private fun observedAction(root: AccessibilityNodeInfo, packageName: String, command: String, param: String): DeviceCommandResult {
        val node = if (command == "inputText") {
            if (param.length !in 1..512 || param.any { it.code < 32 || it.code == 127 }) {
                return failure("ACCESSIBILITY_ACTION_INVALID", "输入长度或格式无效")
            }
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } else {
            if (!Regex("^[0-9]{1,5},[0-9]{1,5}$").matches(param)) return failure("ACCESSIBILITY_ACTION_INVALID", "坐标格式无效")
            val coordinates = param.split(',').map(String::toInt)
            val nodes = ArrayList<AccessibilityNodeInfo>()
            fun collect(current: AccessibilityNodeInfo?, depth: Int) {
                if (current == null || depth > MAX_TREE_DEPTH || nodes.size >= MAX_TREE_NODES) return
                nodes.add(current)
                for (index in 0 until current.childCount) collect(current.getChild(index), depth + 1)
            }
            collect(root, 0)
            nodes.lastOrNull { candidate ->
                candidate.isVisibleToUser && candidate.isClickable && !candidate.viewIdResourceName.isNullOrEmpty() &&
                    Rect().also(candidate::getBoundsInScreen).contains(coordinates[0], coordinates[1])
            }
        } ?: return failure("ACCESSIBILITY_NODE_NOT_FOUND", "目标位置或输入框没有可用的控件标识，请重新读取节点树")
        val id = node.viewIdResourceName ?: return failure("ACCESSIBILITY_NODE_NOT_FOUND", "目标控件没有稳定的资源标识")
        val request = JSONObject().put("packageName", packageName)
            .put("action", if (command == "tap") "click" else "setText")
            .put("selector", JSONObject().put("viewId", id))
        if (command == "inputText") request.put("text", param)
        return action(root, packageName, request.toString())
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
        // 用户已在系统设置中开启无障碍服务，并将目标应用加入 DSH 白名单；
        // 在这个授权边界内允许连续操作，用于自动跳过开屏广告。
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

    /**
     * 副屏（虚拟显示器）读路径的闸门 + 窗口根节点：只匹配传入的显示编号，**绝不**回退到当前主屏窗口
     * （`rootInActiveWindow`）。这是副屏读取的唯一入口，调用方是 `VirtualScreenTree.dump`。
     *
     * 与既有主屏自动化路径的三点刻意差异（其余闸门共用同一份实现，没有为副屏放宽）：
     * 1. **不查无障碍自动化包白名单**：白名单拦的是「本应用替用户在别处乱点」，而副屏会话是用户在原生
     *    页面里明确选择、并且此刻正看着画面的那块屏；再要求用户把自己的目标应用抄进白名单，只是把同一次
     *    选择做两遍，换不来额外安全。
     * 2. **不做动作频率限制**：这是读路径，本来就不限频（与既有的 accessibilityTree 一致）。
     * 3. **不加文本敏感词拦截**：那是主屏 `inputText` 的事，副屏只写用户自己看得见的那个输入框。
     *
     * 没有放宽的是：锁定/熄屏、敏感窗口（[containsSensitiveWindow]）、以及**只认传入的显示编号**——
     * 0（主屏）与负数一律拒绝，避免这里成为绕过包白名单去读主屏的后门。
     *
     * 返回的 [DisplayAccess.root] 由调用方持有，读完必须 `recycle()`。
     */
    internal fun displayRootFor(displayId: Int): DisplayAccess {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return denied("ACCESSIBILITY_WINDOW_UNAVAILABLE", "系统版本低于 Android 11，没有按显示编号取窗口的公开 API")
        }
        if (displayId <= 0) {
            return denied("ACCESSIBILITY_WINDOW_UNAVAILABLE", "显示编号 $displayId 无效：副屏编号必须大于 0（0 是主屏）")
        }
        if (isLockedOrScreenOff()) {
            return denied("ACCESSIBILITY_DEVICE_LOCKED", "设备已锁定或屏幕未交互")
        }
        val root = displayWindowRoot(displayId)
            ?: return denied("ACCESSIBILITY_WINDOW_UNAVAILABLE", "显示编号 $displayId 上没有可读取的窗口")
        if (containsSensitiveWindow(root)) {
            root.recycle()
            return denied("ACCESSIBILITY_SENSITIVE_WINDOW", "检测到密码、验证码、支付或权限窗口，已拒绝读取")
        }
        PrivateAuditLog(this).record(AuditEvent.ACCESSIBILITY_READ, AuditResult.SUCCEEDED, "display=$displayId")
        return DisplayAccess(root, null, null)
    }

    /**
     * 按显示编号取窗口根节点；找不到返回 null。
     *
     * 只从 `getWindows()` 里挑 `displayId` 相等的窗口：拿不到就返回 null，**不会**退化成「当前主屏窗口」
     * （宁可让上层如实报「没有可读取的窗口」，也不能悄悄读到别的屏）。
     *
     * 同一显示编号上可能同时存在多个窗口（应用主窗口、弹窗、输入法）。优先级：非输入法且 `isActive` 的窗口
     * → 其它非输入法窗口 → 剩下的（输入法）。`AccessibilityWindowInfo.recycle()` 与它给出的根节点是两回事：
     * 取到根节点后就回收窗口信息，落选的根节点也一并回收，避免在旧版本上把节点池耗光。
     */
    private fun displayWindowRoot(displayId: Int): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val windows = try {
            getWindows()
        } catch (_: Throwable) {
            null
        } ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestRank = Int.MAX_VALUE
        for (window in windows) {
            val sameDisplay = window.displayId == displayId
            val root = if (sameDisplay) window.root else null
            val rank = when {
                root == null -> Int.MAX_VALUE
                window.isActive && window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD -> 0
                window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD -> 1
                else -> 2
            }
            window.recycle()
            if (root == null) continue
            if (rank < bestRank) {
                val previous = best
                best = root
                bestRank = rank
                if (previous != null && previous !== root) previous.recycle()
            } else {
                root.recycle()
            }
        }
        return best
    }

    /**
     * 副屏（虚拟显示器）写路径：把文本注入 [displayId] 上窗口里当前聚焦的可编辑节点。
     *
     * 顺序固定且不放松：锁定/熄屏 → 频率限制（[ACTION_INTERVAL_MS]，只有成功才记账）→ 按显示编号取窗口 →
     * 敏感窗口 → `findFocus(FOCUS_INPUT)` 且可编辑。**不查包白名单**的理由见 [displayRootFor]。
     *
     * 刻意不做的一件事：**不按文本内容做敏感词拦截**。既有主屏 `inputText` 会因为文本里出现「密码」「验证码」
     * 这类词而在 `AccessibilityAutomationPolicy.parseAction` 里被整体拒绝，那对副屏是误伤——用户可能就是要
     * 在聊天框里输入这些词。真正拦住敏感输入的是整窗拒绝（[containsSensitiveWindow]）与节点上的 `isPassword`。
     *
     * `ACTION_SET_TEXT` 是远程输入，中文等任意 Unicode 都不需要输入法；这正是 `input text`（只支持可打印
     * ASCII）做不到的那一步。
     */
    @Synchronized
    internal fun injectTextOnDisplay(displayId: Int, text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || displayId <= 0) {
            return rejectWrite("ACCESSIBILITY_ACTION_INVALID")
        }
        val now = SystemClock.elapsedRealtime()
        if (isLockedOrScreenOff()) return rejectWrite("ACCESSIBILITY_DEVICE_LOCKED")
        if (now - lastActionAt < ACTION_INTERVAL_MS) return rejectWrite("ACCESSIBILITY_RATE_LIMITED")
        val root = displayWindowRoot(displayId) ?: return rejectWrite("ACCESSIBILITY_WINDOW_UNAVAILABLE")
        if (containsSensitiveWindow(root)) {
            root.recycle()
            return rejectWrite("ACCESSIBILITY_SENSITIVE_WINDOW")
        }
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val accepted = when {
            node == null -> false
            !node.isEditable || node.isPassword -> false
            else -> node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                },
            )
        }
        // 审计详情必须在回收之前算：回收后读 node 的属性在旧版本上不可靠。
        val detail = when {
            accepted -> "display=$displayId"
            node != null && node.isEditable && !node.isPassword -> "ACCESSIBILITY_ACTION_REJECTED"
            else -> "ACCESSIBILITY_NODE_NOT_FOUND"
        }
        if (node !== null && node !== root) node.recycle()
        root.recycle()
        if (accepted) lastActionAt = now
        PrivateAuditLog(this).record(
            AuditEvent.ACCESSIBILITY_ACTION,
            if (accepted) AuditResult.SUCCEEDED else AuditResult.DENIED,
            detail,
        )
        return accepted
    }

    /** 拒绝结果同时写审计（沿用既有事件类别，不新增枚举）。 */
    private fun denied(code: String, reason: String): DisplayAccess {
        PrivateAuditLog(this).record(AuditEvent.ACCESSIBILITY_READ, AuditResult.DENIED, code)
        return DisplayAccess(null, code, reason)
    }

    /** 写路径的拒绝：写审计后返回 false，调用方不需要再关心错误码。 */
    private fun rejectWrite(code: String): Boolean {
        PrivateAuditLog(this).record(AuditEvent.ACCESSIBILITY_ACTION, AuditResult.DENIED, code)
        return false
    }

    /**
     * 副屏定向访问的判定结果：允许时 [root] 非空（调用方持有，用完必须 `recycle()`）；
     * 否则 [root] 为 null，[code] 是写进审计的稳定错误码，[reason] 是给用户看的中文原因。
     */
    internal class DisplayAccess(val root: AccessibilityNodeInfo?, val code: String?, val reason: String?)

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
