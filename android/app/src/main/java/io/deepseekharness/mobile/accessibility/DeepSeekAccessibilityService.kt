package io.deepseekharness.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.deepseekharness.mobile.AppThemePreference
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import io.deepseekharness.mobile.virtualscreen.VirtualScreenTextPolicy
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

    /**
     * 事件驱动自动化的闸门状态（四层节流 + 配额 + 节点指纹 + 连续失败计数）。
     *
     * 只在主线程访问：所有读写都发生在 [onAccessibilityEvent] 与它派生的排期回调里（两者都跑在主线程），
     * 因此这里不需要额外加锁，也不该加——加锁会让「事件量大时按节流丢弃、不排队堆积」变成阻塞等待。
     */
    private val automationGate = AutomationGateState()

    /**
     * 已排期的「稍后重新判定」令牌（`wait` 动作或规则的 `matchDelayMs`）。
     *
     * 同时只允许一个在飞：每个新令牌都会替换掉旧的，旧令牌醒来后按身份比对自行放弃（见 [reevaluateAutomation]），
     * 所以密集事件不会堆积出一串待执行的排期任务。
     */
    private val automationReevalToken = AtomicReference<AutomationReevalToken?>(null)

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceRef.set(this)
        // 新连接 = 新的事件时间基准。上一次连接的动作时间戳/配额/指纹留着会让刚连上的服务被"冷却中"挡住。
        automationGate.reset()
        automationReevalToken.set(null)
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // 第 1 层节流：与闸门里的 EVENT_THROTTLE_MS 同源（配置里的 notificationTimeout 也写成 100）。
            notificationTimeout = AutomationRuleGate.EVENT_THROTTLE_MS
        }
    }

    /**
     * 事件入口。**只做一件事**：按第 1 层节流决定这次事件要不要评估，然后同步评估一次。
     *
     * 被节流的事件直接丢弃（不排队、不补做），异常一律吞掉（自动化评估不能让无障碍服务崩掉）。
     * 事件驱动的自动化与既有行为互不影响：既有链路是「外部请求 + 原生确认浮层」，准入/白名单/审计仍在
     * [executeChecked] 与 [performConfirmedAction] 里；自动化这条路自己实现后端
     * （[AndroidAutomationNodeBackend]），因为它执行的是规则，不是外部请求。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            handleAutomationEvent(event, SystemClock.elapsedRealtime())
        } catch (_: Throwable) {
            // 任何异常都当作"这次不动作"。
        }
    }

    override fun onInterrupt() = cancelPendingConfirmation()

    override fun onDestroy() {
        cancelPendingConfirmation()
        // 与 [AutomationReevalToken] 的身份比对配对：置空之后，已排期的重新判定会发现"令牌不是自己"而放弃。
        automationReevalToken.set(null)
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
        if (!AccessibilityAutomationStore.packageAllowed(this, packageName)) {
            return failure("ACCESSIBILITY_PACKAGE_DENIED", "当前应用不在无障碍自动化白名单中")
        }
        if (containsSensitiveWindow(root)) return failure("ACCESSIBILITY_SENSITIVE_WINDOW", "检测到密码、验证码、支付或权限窗口，已拒绝自动化")
        return when (command) {
            "accessibilityTree" -> if (param.isBlank()) tree(root, packageName)
            else failure("ACCESSIBILITY_ACTION_INVALID", "无障碍层级读取不接受参数")
            "accessibilityAction" -> action(root, packageName, param).also { if (it.ok) lastActionAt = now }
            "tap", "inputText" -> observedAction(root, packageName, command, param).also { if (it.ok) lastActionAt = now }
            // 事件驱动自动化的入口：报告规则统计并打开本进程的事件评估（详见 [automationRules]）。
            "automationRules" -> automationRules(packageName, param)
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
        // 这块面板是「盖在目标应用之上的原生浮层」，因此按 colors.xml 里 overlay_* 那组令牌跟随
        // **已保存的应用主题**，而不是写死白底黑字：写死的话，深色主题下这块白板会刺眼地糊在
        // 目标应用上，也和用户刚在设置里选好的配色对不上。
        // 控件默认样式也要跟主题走：AccessibilityService 里直接 new Button(this) 会按**系统**
        // 深浅解析 AppCompat 默认样式，沿用悬浮球那套 ContextThemeWrapper + palette() 即可。
        val ui: Context = ContextThemeWrapper(AppThemePreference.palette(this), R.style.AppTheme)
        val surface = AppThemePreference.color(this, R.color.overlay_panel_background)
        val foreground = AppThemePreference.color(this, R.color.overlay_panel_foreground)
        val muted = AppThemePreference.color(this, R.color.overlay_panel_muted_foreground)
        val panel = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 24)
            setBackgroundColor(surface)
        }
        val title = TextView(ui).apply {
            text = "确认无障碍动作"
            textSize = 19f
            setTextColor(foreground)
        }
        val details = TextView(ui).apply {
            val input = confirmation.request.text?.length?.toString() ?: "0"
            text = "应用：${confirmation.request.packageName}\n动作：${confirmation.request.action}\nviewId：${confirmation.request.viewId}\n输入长度：$input"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, 16, 0, 18)
        }
        val buttons = LinearLayout(ui).apply { gravity = Gravity.END }
        val reject = Button(ui).apply {
            text = "拒绝"
            setOnClickListener { finishConfirmation(confirmation, false) }
        }
        val approve = Button(ui).apply {
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
        if (packageName != request.packageName || !AccessibilityAutomationStore.packageAllowed(this, packageName)) {
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
    internal fun injectTextOnDisplay(displayId: Int, text: String): Boolean =
        writeTextOnDisplay(displayId, text).succeeded

    /**
     * 副屏文本写入的分级实现（回退链的后端）：无障碍直接写入 → 聚焦候选输入框后写入 → 剪贴板粘贴。
     *
     * 分工与 [VirtualScreenTextPolicy] 严格分开：**「该走哪几步、失败该报什么码」在策略层**，
     * 这里只负责真正碰 Android API，并把每一级的结果如实记账（[TextWriteResult]）。这里不吞异常、
     * 也不把「写不进去」说成「副屏不可用」——错误码原样交给上层。
     *
     * 刻意不放松的安全判定：
     * - 只作用于传入的 [displayId]（`0` 是主屏，一律拒绝）；
     * - 锁定/熄屏拒绝；敏感窗口（密码、验证码、支付、授权弹窗）整窗拒绝；
     * - `isPassword` 节点永不被写入；
     * - [ACTION_INTERVAL_MS] 节流，且**只有系统接受才推进计时**（被拒不算做过动作）。
     *
     * 剪贴板这一级的真实限制（**待真机验证**）：Android 10 起应用在后台读剪贴板受限，而目标应用属于
     * **另一个进程**。本应用写入剪贴板本身应当成功，但目标输入框能否读到那条 ClipData 由系统与目标应用
     * 决定；`ACTION_PASTE` 返回 false 时如实往下一级报，不假装粘贴成功。
     */
    @Synchronized
    internal fun writeTextOnDisplay(
        displayId: Int,
        text: String,
        submit: Boolean = false,
        clipboardAvailable: Boolean = false,
    ): TextWriteResult {
        VirtualScreenTextPolicy.requireInjectable(text)
        val now = SystemClock.elapsedRealtime()
        val resolved = resolveDisplayInput(displayId, now) ?: return TextWriteResult.denied(
            "ACCESSIBILITY_WINDOW_UNAVAILABLE",
        )
        // 候选输入框：已有聚焦可编辑节点时就是它；否则是第一个可聚焦且可编辑的节点（聚焦→再写入那一支）。
        val focusNode = resolved.focus ?: findFocusable(resolved.root, editableOnly = true)
        val target = resolved.focus
            ?: focusNode
            ?: return TextWriteResult.failure(
                VirtualScreenTextPolicy.TextMethod.UNSUPPORTED,
                VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                "副屏当前没有可编辑的输入框",
            ).also { resolved.root.recycle() }
        val hasFocusTarget = focusNode != null
        val attempted = if (resolved.focus != null) {
            VirtualScreenTextPolicy.TextMethod.SET_TEXT
        } else {
            VirtualScreenTextPolicy.TextMethod.FOCUS_THEN_SET_TEXT
        }
        // 「聚焦后写入」这一级的真实动作：没有聚焦节点时先取一次焦点（节点自己可点时再补一次点击），
        // 再写入。取焦点失败不提前放弃：SET_TEXT 仍然会试一次，成败由真实回执决定，不靠推测。
        if (resolved.focus == null) acquireFocus(target, displayId)
        val accepted = setText(target, text)
        if (accepted) lastActionAt = SystemClock.elapsedRealtime()
        val result = when {
            // 只有确实写进去了才按回车：写入被拒时绝不提交，否则会把上一次留在输入框里的内容提交出去。
            accepted -> TextWriteResult.success(
                attempted,
                hasFocusTarget,
                submit && pressImeEnter(displayId, target, text.length),
            )
            clipboardAvailable -> pasteInto(displayId, target, text, now, hasFocusTarget, submit)
            else -> TextWriteResult.failure(
                attempted,
                VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                if (hasFocusTarget) {
                    "输入框拒绝了写入，且剪贴板通道不可用${tapHint(target)}"
                } else {
                    "输入框拒绝了写入（未能取得焦点）"
                },
                hasFocusTarget,
            )
        }
        if (focusNode != null && focusNode !== resolved.focus && focusNode !== target) focusNode.recycle()
        if (target !== resolved.root) target.recycle()
        resolved.root.recycle()
        return result
    }

    /**
     * 「聚焦后写入」的焦点动作：先 `ACTION_FOCUS`，不被接受且节点自己可点时再补一次 `ACTION_CLICK`
     * （对 EditText 而言等同于点进输入框）。
     *
     * 刻意**不合成手势**：本方法跑在无障碍服务进程里，合成手势只能打到默认显示，副屏上的点击由
     * `VirtualScreenInjector` 那条独立通道负责（取不到焦点时失败回执里会带上 [tapHint] 给出的坐标）。
     *
     * 只有系统确实接受（或节点本来就已聚焦）才算一次动作，并推进写节流计时。
     */
    private fun acquireFocus(node: AccessibilityNodeInfo, displayId: Int): Boolean {
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || node.isFocused
        val clicked = !focused && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val acquired = focused || clicked
        if (acquired) lastActionAt = SystemClock.elapsedRealtime()
        PrivateAuditLog(this).record(
            AuditEvent.ACCESSIBILITY_ACTION,
            if (acquired) AuditResult.SUCCEEDED else AuditResult.DENIED,
            if (acquired) "display=$displayId ACTION_FOCUS" else "ACCESSIBILITY_FOCUS_REJECTED",
        )
        return acquired
    }

    /**
     * `submit=true` 的后半段：文字**确实写进去之后**，对同一个节点按下一次输入法回车
     * （搜索框等同于提交、聊天框等同于发送）。
     *
     * 只用 `ACTION_IME_ENTER`（API 30 起可用；副屏本身也要求 SDK≥30，这里的版本判断是防御性的，
     * 让低版本设备如实失败而不是崩在 lint/运行期），因此不依赖输入法本身。
     * 写入刚推进过节流，这里等满 [ACTION_INTERVAL_MS] 再发回车，避免两次动作被目标应用合并掉。
     * 返回 false 表示那一刻没送出去，调用方应如实把 `submit` 标成未完成，而不是宣称「已经发送」。
     */
    private fun pressImeEnter(displayId: Int, node: AccessibilityNodeInfo, chars: Int): Boolean {
        // `ACTION_IME_ENTER` 是 API 30（Android 11）才有的动作，而本应用的 minSdk 是 26。
        // 低版本没有任何等价的「让输入法回车」入口：这里如实拒绝并把原因记进审计，绝不按一个别的键
        // 或用别的手段假装提交成功——调用方据此把 submit 标成未完成。真机行为待真机验证。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            PrivateAuditLog(this).record(
                AuditEvent.ACCESSIBILITY_ACTION,
                AuditResult.DENIED,
                "ACCESSIBILITY_IME_ENTER_UNSUPPORTED display=$displayId api=${Build.VERSION.SDK_INT}",
            )
            return false
        }
        val waited = SystemClock.elapsedRealtime() - lastActionAt
        if (waited in 0 until ACTION_INTERVAL_MS) SystemClock.sleep(ACTION_INTERVAL_MS - waited)
        // 走 AccessibilityAction 常量取 id：ACTION_IME_ENTER 的顶层 int 别名并不在所有 compileSdk
        // 的公开存根里，而这个内部类常量从 API 30 起一直是公开的。
        val enterAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        val sent = node.performAction(enterAction)
        if (sent) lastActionAt = SystemClock.elapsedRealtime()
        PrivateAuditLog(this).record(
            AuditEvent.ACCESSIBILITY_ACTION,
            if (sent) AuditResult.SUCCEEDED else AuditResult.DENIED,
            if (sent) "display=$displayId submitted=$chars" else "ACCESSIBILITY_IME_ENTER_REJECTED",
        )
        return sent
    }

    /**
     * 候选输入框写不进去时，把它的屏幕中心坐标写进失败回执：上层（主进程）可以据此先向副屏注入
     * 一次真实点击让它取得焦点，再重试写入。取不到有效矩形时返回空串，不编造坐标。
     */
    private fun tapHint(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return if (bounds.isEmpty) {
            ""
        } else {
            "；可先向副屏注入一次点击 (${bounds.centerX()}, ${bounds.centerY()}) 让输入框取得焦点后重试"
        }
    }

    /**
     * 写路径的前置闸门：版本/编号 → 锁定 → 频率限制 → 取窗口 → 敏感窗口 → 解析聚焦/候选输入框。
     *
     * 返回 null 表示拒绝（审计与原因已由 [rejectWrite] 写下）；返回非 null 时 **调用方持有 [WriteScope.root]，
     * 用完必须 `recycle()`**；[WriteScope.focus] 是当前聚焦的可编辑非密码节点（可为 null），属于复用节点无需单独回收。
     */
    private fun resolveDisplayInput(displayId: Int, now: Long): WriteScope? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || displayId <= 0) {
            rejectWrite("ACCESSIBILITY_ACTION_INVALID")
            return null
        }
        if (isLockedOrScreenOff()) {
            rejectWrite("ACCESSIBILITY_DEVICE_LOCKED")
            return null
        }
        if (now - lastActionAt < ACTION_INTERVAL_MS) {
            rejectWrite("ACCESSIBILITY_RATE_LIMITED")
            return null
        }
        val root = displayWindowRoot(displayId) ?: run {
            rejectWrite("ACCESSIBILITY_WINDOW_UNAVAILABLE")
            return null
        }
        if (containsSensitiveWindow(root)) {
            root.recycle()
            rejectWrite("ACCESSIBILITY_SENSITIVE_WINDOW")
            return null
        }
        // 不在这里按下焦点：取焦点是**公开动作**（会推进节流并写审计），只能由真正要写的动作触发。
        // 这一步只如实报告「有没有一个可以写的节点」，把选择权留给调用方。
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }
        return WriteScope(root, focus)
    }

    /** 收到确认的聚焦/可编辑节点。 */
    private class WriteScope(val root: AccessibilityNodeInfo, val focus: AccessibilityNodeInfo?)

    /** 无障碍写入原语，单独抽出来便于对照 [VirtualScreenTextPolicy.TextMethod]。 */
    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean = node.performAction(
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        },
    )

    /** 剪贴板分级：本进程写入 ClipData，再对目标节点 `ACTION_PASTE`；失败如实返回码。 */
    private fun pasteInto(
        displayId: Int,
        target: AccessibilityNodeInfo,
        text: String,
        now: Long,
        hasFocusTarget: Boolean,
        submit: Boolean,
    ): TextWriteResult {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return TextWriteResult.failure(
                VirtualScreenTextPolicy.TextMethod.PASTE,
                VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                "本应用拿不到剪贴板服务",
                hasFocusTarget,
            )
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("DSH 副屏文本", text))
        } catch (error: Throwable) {
            // Android 10+ 对剪贴板写入有限制，失败要如实报，不能回退成「写成功了」。
            return TextWriteResult.failure(
                VirtualScreenTextPolicy.TextMethod.PASTE,
                VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                "剪贴板写入被系统拒绝（${error.javaClass.simpleName}）",
                hasFocusTarget,
            )
        }
        val pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) lastActionAt = now
        PrivateAuditLog(this).record(
            AuditEvent.ACCESSIBILITY_ACTION,
            if (pasted) AuditResult.SUCCEEDED else AuditResult.DENIED,
            if (pasted) "display=$displayId ACTION_PASTE" else "ACCESSIBILITY_PASTE_REJECTED",
        )
        return if (pasted) {
            // 粘贴同样只有确实贴进去之后才按回车。
            TextWriteResult.success(
                VirtualScreenTextPolicy.TextMethod.PASTE,
                hasFocusTarget,
                submit && pressImeEnter(displayId, target, text.length),
            )
        } else {
            TextWriteResult.failure(
                VirtualScreenTextPolicy.TextMethod.PASTE,
                VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                "目标输入框拒绝了剪贴板粘贴",
                hasFocusTarget,
            )
        }
    }

    /**
     * 深度优先找第一个可聚焦且（可选）可编辑的节点。
     *
     * 返回的节点**由调用方持有**，用完必须 `recycle()`；只返回一个节点，不做整棵树快照。
     */
    private fun findFocusable(node: AccessibilityNodeInfo, editableOnly: Boolean = false): AccessibilityNodeInfo? {
        if ((!editableOnly || node.isEditable) && node.isEnabled && node.isFocusable) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findFocusable(child, editableOnly)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
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

    /**
     * 副屏文本写入某一级的真实结果。
     *
     * [method] 是**实际走到**的那一级（不是计划里的那一级）：没有聚焦节点时是
     * [VirtualScreenTextPolicy.TextMethod.FOCUS_THEN_SET_TEXT]，被拒后改走剪贴板则是
     * [VirtualScreenTextPolicy.TextMethod.PASTE]。[code] 只在 [succeeded] 为 false 时有意义，
     * 取值来自 [VirtualScreenTextPolicy.FailureCode] 或 `ACCESSIBILITY_*` 审计码，原样交给上层。
     */
    internal class TextWriteResult(
        val succeeded: Boolean,
        val method: VirtualScreenTextPolicy.TextMethod,
        val code: String?,
        val detail: String,
        val focusedCandidate: Boolean,
        val submit: Boolean,
    ) {
        companion object {
            fun success(
                method: VirtualScreenTextPolicy.TextMethod,
                focusedCandidate: Boolean,
                submit: Boolean = false,
            ): TextWriteResult = TextWriteResult(true, method, null, "", focusedCandidate, submit)

            fun failure(
                method: VirtualScreenTextPolicy.TextMethod,
                code: String,
                detail: String,
                focusedCandidate: Boolean = false,
            ): TextWriteResult = TextWriteResult(false, method, code, detail, focusedCandidate, false)

            /**
             * 前置闸门（锁定、节流、无窗口、敏感窗口）的拒绝：选用的方法按「聚焦后写入」记账，
             * 因为下一级一定是先取焦点；真正的失败原因在 [code] 里。
             */
            fun denied(code: String): TextWriteResult = TextWriteResult(
                false,
                VirtualScreenTextPolicy.TextMethod.FOCUS_THEN_SET_TEXT,
                code,
                VirtualScreenTextPolicy.failureMessage(code),
                false,
                false,
            )
        }
    }

    // ------------------------------------------------------------------
    // 事件驱动的自动化：规则读取 → 判定 → 闸门 → 执行 → 回报
    // ------------------------------------------------------------------

    /**
     * 用户通过 `automationRules` 命令打开「自动化评估」之后，事件回调才会真的去读规则。
     *
     * 默认关闭，且不落盘：无障碍服务是常驻的，一个进程里第一次连接时不应该在用户还没看过规则的情况下
     * 就开始点界面。这个开关是**会话级**的，进程重启后回到关闭状态。
     */
    private var automationEnabled = false

    /**
     * 最近一次 `TYPE_WINDOW_STATE_CHANGED` 报上来的 Activity 名（也是配额窗口的一部分）。
     *
     * 只在这个事件类型上更新：`TYPE_WINDOW_CONTENT_CHANGED` 的 `className` 是**视图**类名
     * （`android.widget.FrameLayout` 之类），拿它当 Activity 会让配额窗口每个事件都变一次，
     * 等于把 `actionMaximum` 这道唯一的闸门废掉。缓存住之后窗口就是稳定的「包名/Activity」，
     * 规则的 `allowActivities` / `denyActivities` 也才有意义。
     */
    private var automationActivityName: String? = null
    private var automationActivityPackage: String? = null

    /**
     * 屏幕周期序号：`resetOn = "screen"` 的配额窗口靠它区分（见 [AutomationRuleExecutor.quotaWindowKey]）。
     *
     * 只在 `TYPE_WINDOW_STATE_CHANGED` 上递增：那是「窗口更新」这个信号本身（对话框弹出、同一 Activity
     * 内的页面切换都算）。`TYPE_WINDOW_CONTENT_CHANGED` 每次文本滚动都会来，拿它当周期会让
     * `screen` 口径退化成「每个事件都是新窗口」，也就是 `maxActions` 彻底失效。
     *
     * 它只重开**次数配额**，不动指纹（[AutomationGateState.syncIdentityWindow] 只认包名/Activity），
     * 所以「窗口更新 → 又点同一个按钮」仍然被指纹挡住，`screen` 不会变成自触发循环。
     */
    private var automationScreenSeq = 0L

    /** 「稍后重新判定」的令牌：只用来标识一次排期（见 [reevaluateAutomation] 的身份比对）。 */
    private class AutomationReevalToken(val dueAtMs: Long)

    /** 一次事件评估的共享上下文：树只读一次，规则判定与执行后端都用它。 */
    private class AutomationTreeSnapshot(
        val tree: AutomationNode?,
        val packageName: String,
    )

    private class AutomationEvaluation(
        val allowedPackages: Set<String>,
        val whitelistEnabled: Boolean,
        val tree: AutomationTreeSnapshot,
        val deviceLocked: Boolean,
        val sensitiveWindow: Boolean,
    )

    /**
     * 事件入口的主体：白名单/系统界面早筛 → 第 1 层事件节流 → 读树 → 读规则 → 判定与执行。
     *
     * 每一次事件都是「同步评估一次」，没有队列、没有重试：事件太密时前面的层会把它丢掉。
     */
    private fun handleAutomationEvent(event: AccessibilityEvent, nowMs: Long) {
        // enabled 标志只由 `automationRules` 命令改写；事件回调只读它。
        if (!automationEnabled) return
        val eventPackage = event.packageName?.toString().orEmpty()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            automationActivityPackage = eventPackage
            automationActivityName = event.className?.toString()
            automationScreenSeq += 1
            automationGate.beginMatchWindow(AutomationRuleExecutor.baseWindowKey(
                AutomationScreenInfo(eventPackage, event.className?.toString()),
            ))
        }
        if (!AccessibilityAutomationPolicy.validPackage(eventPackage)) return
        // 系统界面的窗口变化太频繁，也不该成为自动化的输入：它们只是"屏幕上多了个东西"。
        if (eventPackage == "android" || eventPackage == "com.android.systemui") return
        val allowed = AccessibilityAutomationStore.allowedPackages(this)
        val whitelistEnabled = AccessibilityAutomationStore.whitelistEnabled(this)
        // 执行前的第一道白名单：事件包名必须被允许。应用自身包名也在白名单里（策略如此），
        // 但闸门默认额外拒绝对自身执行动作（防自触发），所以这里不需要特殊处理。
        if (!AccessibilityAutomationPolicy.packageAllowed(eventPackage, allowed, whitelistEnabled)) return
        // 第 1 层：事件准入。被节流的事件直接丢弃，不排队、不补做。
        if (!AutomationRuleGate.admitEvent(automationGate, nowMs).allowed) return
        val root = rootInActiveWindow ?: return
        val snapshot = buildAutomationTree(root)
        val deviceLocked = isLockedOrScreenOff()
        // 锁屏时不读树、不读规则：拍板口径是"锁屏一律跳过"，连评估都不做。
        if (deviceLocked) return
        val sensitiveWindow = containsSensitiveWindow(root)
        val rules = loadAutomationRules(snapshot.packageName)
        if (rules.isEmpty()) return
        runAutomationEvaluation(
            AutomationEvaluation(
                allowedPackages = allowed,
                whitelistEnabled = whitelistEnabled,
                tree = snapshot,
                deviceLocked = false,
                sensitiveWindow = sensitiveWindow,
            ),
            rules,
            nowMs,
            apply = true,
            eventSequence = automationScreenSeq,
        )
    }

    /** 「稍后重新判定」：`wait` 动作与规则的 `matchDelayMs` 都排到这里；同时只允许一个在飞。 */
    private fun scheduleAutomationReevaluation(delayMs: Int) {
        if (delayMs <= 0) return
        val dueAtMs = SystemClock.elapsedRealtime() + delayMs
        if (automationReevalToken.get()?.dueAtMs?.let { it <= dueAtMs } == true) return
        val token = AutomationReevalToken(dueAtMs)
        automationReevalToken.set(token)
        mainHandler.postDelayed(
            {
                // 身份比对：被更新的排期顶掉、或服务已销毁（令牌被置空）时，这次唤醒什么都不做。
                if (automationReevalToken.get() !== token) return@postDelayed
                automationReevalToken.set(null)
                try {
                    reevaluateAutomation()
                } catch (_: Throwable) {
                    // 自动化的任何异常都不该冒泡到主线程消息循环。
                }
            },
            delayMs.toLong(),
        )
    }

    /**
     * 延迟时间到点后**重新读一遍界面再判定一次**（与事件回调走同一条评估链）。
     *
     * 不缓存事件里的树：`wait` 与 `matchDelayMs` 的语义就是等界面变化，用旧树判定等于没等。
     */
    private fun reevaluateAutomation() {
        if (!automationEnabled) return
        val root = rootInActiveWindow ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val allowed = AccessibilityAutomationStore.allowedPackages(this)
        val whitelistEnabled = AccessibilityAutomationStore.whitelistEnabled(this)
        if (!AutomationRuleGate.admitEvent(automationGate, nowMs).allowed) {
            scheduleAutomationReevaluation(AutomationRuleGate.EVENT_THROTTLE_MS.toInt())
            return
        }
        val snapshot = buildAutomationTree(root)
        if (!AccessibilityAutomationPolicy.packageAllowed(snapshot.packageName, allowed, whitelistEnabled)) return
        if (isLockedOrScreenOff()) return
        val rules = loadAutomationRules(snapshot.packageName)
        if (rules.isEmpty()) return
        runAutomationEvaluation(
            AutomationEvaluation(
                allowedPackages = allowed,
                whitelistEnabled = whitelistEnabled,
                tree = snapshot,
                deviceLocked = false,
                sensitiveWindow = containsSensitiveWindow(root),
            ),
            rules,
            nowMs,
            apply = true,
            // 重判定没有新事件，沿用最近一次窗口更新的周期：`resetOn="screen"` 的配额
            // 不会因为「等待到期」被白白重开一次（那会让 matchDelayMs 变成绕过 maxActions 的口子）。
            eventSequence = automationScreenSeq,
        )
    }

    /**
     * 把一次评估交给执行器，然后回报结果。
     *
     * `eventSequence` 是 [automationScreenSeq]：只在 `resetOn = "screen"` 的规则上起作用
     */
    private fun runAutomationEvaluation(
        evaluation: AutomationEvaluation,
        rules: List<AutomationRule>,
        nowMs: Long,
        apply: Boolean,
        eventSequence: Long,
    ) {
        val snapshot = evaluation.tree
        val root = snapshot.tree ?: return
        val report = AutomationRuleExecutor.execute(
            backend = AndroidAutomationNodeBackend(),
            root = root,
            screen = AutomationScreenInfo(
                packageName = snapshot.packageName,
                activityName = automationActivityName.takeIf { automationActivityPackage == snapshot.packageName },
            ),
            rules = rules,
            gate = automationGate,
            nowMs = nowMs,
            allowedPackages = evaluation.allowedPackages,
            whitelistEnabled = evaluation.whitelistEnabled,
            deviceLocked = evaluation.deviceLocked,
            sensitiveWindow = evaluation.sensitiveWindow,
            apply = apply,
            eventSequence = eventSequence,
        )
        handleAutomationReport(report, snapshot.packageName)
    }

    /** 回报：审计 → 落盘自动停用 → 提示用户 → 需要的重新判定排期。 */
    private fun handleAutomationReport(report: AutomationExecutionReport, packageName: String) {
        if (report.performed.isEmpty() && report.autoDisabledRuleIds.isEmpty() && report.notices.isEmpty()) {
            // 只挂了一个排期也要处理（wait / matchDelayMs）。
            report.nextEvaluationDelayMs?.let(::scheduleAutomationReevaluation)
            return
        }
        val audit = PrivateAuditLog(this)
        for (result in report.performed) {
            // 审计只记受控码与结果：不带包名、viewId、界面文本（AuditPolicy.detailPattern 也只接受这个形状）。
            audit.record(
                AuditEvent.ACCESSIBILITY_ACTION,
                if (result.ok) AuditResult.SUCCEEDED else AuditResult.FAILED,
                result.code,
            )
        }
        for (ruleId in report.autoDisabledRuleIds) {
            audit.record(AuditEvent.ACCESSIBILITY_CONFIG, AuditResult.SUCCEEDED, AutomationGateCodes.RULE_DISABLED)
            // 自动停用必须落盘：只在内存里停用的话，进程被系统回收后规则会"复活"，用户会看到它继续点错。
            if (!disableAutomationRule(packageName, ruleId)) {
                notifyAutomation("规则「$ruleId」已自动停用，但写盘失败；请到自动化设置里手动关掉它")
            }
        }
        if (report.notices.isNotEmpty()) notifyAutomation(report.notices.joinToString("；"))
        report.nextEvaluationDelayMs?.let(::scheduleAutomationReevaluation)
    }

    /**
     * 把自动停用的规则写盘（`enabled = false`）。
     *
     * 返回 false 只表示「找到了规则但没写成功」：这种情况必须提示用户，因为进程重启后规则会重新生效。
     */
    private fun disableAutomationRule(packageName: String, ruleId: String): Boolean {
        val store = AutomationRulePreferences.from(this)
        val read = AutomationRuleStore.readPackage(store, packageName)
        if (read !is AutomationRuleStore.PackageRead.Ok) return false
        val updated = read.rules.map { if (it.id == ruleId) it.copy(enabled = false) else it }
        return runCatching { AutomationRuleStore.savePackage(store, packageName, updated) }.isSuccess
    }

    /** 只读取当前应用规则；规则状态使用包名与 ID 的组合键。 */
    private fun loadAutomationRules(packageName: String): List<AutomationRule> {
        if (!AccessibilityAutomationStore.packageAllowed(this, packageName)) return emptyList()
        val read = AutomationRuleStore.readPackage(AutomationRulePreferences.from(this), packageName)
        if (read !is AutomationRuleStore.PackageRead.Ok) return emptyList()
        for (rule in read.rules) {
            if (rule.enabled) automationGate.clearAutoDisabled(rule.stateKey)
        }
        return read.rules
    }

    /**
     * `automationRules` 命令：报告某个包的规则统计，并把本进程的自动化评估置为开启。
     *
     * 参数留空时用当前前台包（由 [executeChecked] 解析后传入）。它**只读**规则文件，不改规则内容：
     * 改规则要走 AI 生成 + 用户在设置页确认的那条链路，不在本命令的范围里。
     */
    private fun automationRules(rootPackage: String, param: String?): DeviceCommandResult {
        val requested = param?.trim().orEmpty().ifEmpty { rootPackage }
        if (!AccessibilityAutomationPolicy.validPackage(requested)) {
            return failure("AUTOMATION_RULES_PACKAGE_INVALID", "包名不合法：$requested")
        }
        if (!AccessibilityAutomationStore.packageAllowed(this, requested)) {
            automationEnabled = false
            return failure("AUTOMATION_RULES_PACKAGE_DENIED", "应用 $requested 不在无障碍自动化白名单内")
        }
        val read = AutomationRuleStore.readPackage(AutomationRulePreferences.from(this), requested)
        return when (read) {
            is AutomationRuleStore.PackageRead.Ok -> {
                automationEnabled = true
                val disabled = read.rules.filter { !it.enabled }.map { it.id }
                val text = "包 $requested：规则 ${read.rules.size} 条，启用 ${read.rules.count { it.enabled }} 条" +
                    if (disabled.isEmpty()) "" else "；已停用：${disabled.joinToString("、")}"
                auditAutomationEnabled()
                DeviceCommandResult(true, 0, text, false, null)
            }
            is AutomationRuleStore.PackageRead.Corrupt ->
                failure(read.code, "读取规则失败：${read.message}")
        }
    }

    /** 打开自动化评估这件事本身要留痕；detail 只带受控码，不带包名。 */
    private fun auditAutomationEnabled() {
        runCatching {
            PrivateAuditLog(this).record(
                AuditEvent.ACCESSIBILITY_CONFIG,
                AuditResult.SUCCEEDED,
                "AUTOMATION_EVENT_DRIVER_ENABLED",
            )
        }
    }

    /** 回报用户：弹一条短提示。绝不让提示本身影响执行结果（弹不出来就算了）。 */
    private fun notifyAutomation(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    /**
     * 读取当前窗口的界面树，并把它拷成纯 JVM 的 [AutomationNode]。
     *
     * 为什么非要拷贝：`AccessibilityNodeInfo` 的对象会失效（`refresh()` 返回 false），而且在后台线程上
     * 根本不是安全 API；匹配器与执行器的判定又必须能在 JVM 单测里跑（那里没有 Android 框架）。
     * 所以约定是：**服务侧负责把树拷成不可变快照，判定与计数全部用快照**；真正要执行动作时再用
     * [AndroidAutomationNodeBackend.resolveNode] 按路径重新读一次真实节点（届时会再做一次身份校验）。
     */
    private fun buildAutomationTree(root: AccessibilityNodeInfo): AutomationTreeSnapshot {
        val packageName = root.packageName?.toString().orEmpty()
        val budget = intArrayOf(MAX_AUTOMATION_NODES)
        val tree = copyAutomationNode(root, 0, budget)
        return AutomationTreeSnapshot(tree = tree, packageName = packageName)
    }

    /**
     * 递归拷贝节点（深度与节点数都给上限，超出就截断）。
     *
     * 截断的取舍：走在前面的节点是树的"上半部分"（标题、按钮通常在上面），截断只会丢掉深处的节点；
     * 一旦在某条分支上触顶，同一分支的更深处不再访问，避免用无限递归去读一个正在变化的树。
     */
    private fun copyAutomationNode(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): AutomationNode? {
        if (depth > MAX_AUTOMATION_DEPTH || budget[0] <= 0) return null
        budget[0]--
        val rect = Rect().also(node::getBoundsInScreen)
        val children = ArrayList<AutomationNode>()
        val childCount = node.childCount
        for (index in 0 until childCount) {
            if (budget[0] <= 0) break
            val child = node.getChild(index) ?: continue
            children += copyAutomationNode(child, depth + 1, budget) ?: continue
        }
        return AutomationNode(
            className = node.className?.toString(),
            text = node.text?.toString(),
            viewId = node.viewIdResourceName,
            desc = node.contentDescription?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            editable = node.isEditable,
            bounds = AutomationBounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }

    /**
     * 把纯 JVM 的执行器接到真实的 `AccessibilityNodeInfo` 与手势上。
     *
     * 所有回调都在主线程（事件回调与排期回调都在主线程），所以这里不需要切线程；
     * 也正因为同线程，执行器那次 `resolveNode` 读到的节点几乎总是与判定时的同一个。
     */
    private inner class AndroidAutomationNodeBackend : AutomationNodeBackend {

        override fun resolveNode(path: List<Int>, bounds: AutomationBounds): AutomationNodeHandle? {
            var node: AccessibilityNodeInfo? = rootInActiveWindow ?: return null
            for (index in path) {
                val current = node ?: return null
                node = current.getChild(index) ?: return null
            }
            val resolved = node ?: return null
            return AutomationNodeHandle(resolved, snapshotOf(resolved))
        }

        override fun clickNode(node: Any): BackendOutcome {
            val info = node as? AccessibilityNodeInfo
                ?: return BackendOutcome.Refused("AUTOMATION_NODE_INVALID", "节点对象类型不对")
            if (!info.isClickable) {
                return BackendOutcome.Refused("AUTOMATION_NODE_NOT_CLICKABLE", "节点 clickable=false")
            }
            val performed = runCatching { info.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            if (!performed) {
                return BackendOutcome.Refused(
                    "AUTOMATION_NODE_ACTION_REFUSED",
                    "ACTION_CLICK 返回 false（节点已失效，或应用不在这个节点上处理点击）",
                )
            }
            return BackendOutcome.Done
        }

        override fun clickClickableAncestor(node: Any): BackendOutcome {
            val start = node as? AccessibilityNodeInfo
                ?: return BackendOutcome.Refused("AUTOMATION_NODE_INVALID", "节点对象类型不对")
            var parent = runCatching { start.parent }.getOrNull()
            var depth = 0
            while (parent != null && depth < MAX_ANCESTOR_DEPTH) {
                val current = parent
                if (current.isClickable) {
                    val performed = runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                    if (performed) return BackendOutcome.Done
                }
                parent = runCatching { current.parent }.getOrNull()
                depth++
            }
            return BackendOutcome.Refused(
                "AUTOMATION_ANCESTOR_MISSING",
                "向上 $MAX_ANCESTOR_DEPTH 层没有可点击的祖先节点，或可点击的祖先都拒绝了 ACTION_CLICK",
            )
        }

        override fun clickCenter(bounds: AutomationBounds): BackendOutcome {
            if (bounds.width <= 0 || bounds.height <= 0) {
                return BackendOutcome.Refused("AUTOMATION_BOUNDS_EMPTY", "命中节点没有可见区域（宽高为 0），无法派发点击")
            }
            val path = Path().apply {
                moveTo(bounds.centerX.toFloat(), bounds.centerY.toFloat())
            }
            return dispatch(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                    .build(),
            )
        }

        override fun longClickNode(node: Any): BackendOutcome {
            val info = node as? AccessibilityNodeInfo
                ?: return BackendOutcome.Refused("AUTOMATION_NODE_INVALID", "节点对象类型不对")
            val rect = Rect().also(info::getBoundsInScreen)
            if (rect.isEmpty) {
                return BackendOutcome.Refused("AUTOMATION_BOUNDS_EMPTY", "命中节点没有可见区域，无法派发长按")
            }
            // 优先节点语义动作；平台拒绝时才回退到坐标手势。
            if (runCatching { info.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)) {
                return BackendOutcome.Done
            }
            val path = Path().apply {
                moveTo(rect.exactCenterX(), rect.exactCenterY())
            }
            return dispatch(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS))
                    .build(),
            )
        }

        override fun pressBack(): BackendOutcome {
            val performed = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
            if (!performed) {
                return BackendOutcome.Refused("AUTOMATION_GLOBAL_ACTION_REFUSED", "GLOBAL_ACTION_BACK 返回 false")
            }
            return BackendOutcome.Done
        }

        override fun swipe(direction: String, durationMs: Int): BackendOutcome {
            val screen = rootInActiveWindow?.let { Rect().also(it::getBoundsInScreen) } ?: Rect()
            val metrics = resources.displayMetrics
            val width = if (screen.width() > 0) screen.width() else metrics.widthPixels
            val height = if (screen.height() > 0) screen.height() else metrics.heightPixels
            if (width <= 0 || height <= 0) {
                return BackendOutcome.Refused("AUTOMATION_BOUNDS_EMPTY", "当前窗口没有可用的屏幕区域，无法滑动")
            }
            val left = screen.left
            val top = screen.top
            val insetX = (width * SWIPE_INSET_RATIO).toInt().coerceAtLeast(1)
            val insetY = (height * SWIPE_INSET_RATIO).toInt().coerceAtLeast(1)
            val centerX = left + width / 2f
            val centerY = top + height / 2f
            val path = Path()
            // direction 是**手指移动方向**：`up` = 手指从下往上滑（内容向上滚动）。
            when (direction) {
                "up" -> {
                    path.moveTo(centerX, (top + height - insetY).toFloat())
                    path.lineTo(centerX, (top + insetY).toFloat())
                }
                "down" -> {
                    path.moveTo(centerX, (top + insetY).toFloat())
                    path.lineTo(centerX, (top + height - insetY).toFloat())
                }
                "left" -> {
                    path.moveTo((left + width - insetX).toFloat(), centerY)
                    path.lineTo((left + insetX).toFloat(), centerY)
                }
                "right" -> {
                    path.moveTo((left + insetX).toFloat(), centerY)
                    path.lineTo((left + width - insetX).toFloat(), centerY)
                }
                else -> return BackendOutcome.Refused("AUTOMATION_SWIPE_DIRECTION_INVALID", "不支持的滑动方向：$direction")
            }
            val duration = durationMs.coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS)
            return dispatch(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, duration.toLong()))
                    .build(),
            )
        }

        override fun sendKey(keyCode: Int): BackendOutcome {
            val name = AutomationKeyRouting.globalActionName(keyCode)
                ?: return BackendOutcome.Refused("AUTOMATION_KEY_UNROUTABLE", "按键码 $keyCode 没有对应的无障碍全局动作")
            val dpadReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val action = when (name) {
                AutomationKeyRouting.BACK -> GLOBAL_ACTION_BACK
                AutomationKeyRouting.DPAD_UP -> if (dpadReady) GLOBAL_ACTION_DPAD_UP else -1
                AutomationKeyRouting.DPAD_DOWN -> if (dpadReady) GLOBAL_ACTION_DPAD_DOWN else -1
                AutomationKeyRouting.DPAD_LEFT -> if (dpadReady) GLOBAL_ACTION_DPAD_LEFT else -1
                AutomationKeyRouting.DPAD_RIGHT -> if (dpadReady) GLOBAL_ACTION_DPAD_RIGHT else -1
                AutomationKeyRouting.DPAD_CENTER -> if (dpadReady) GLOBAL_ACTION_DPAD_CENTER else -1
                else -> -1
            }
            if (action < 0) {
                return BackendOutcome.Refused(
                    "AUTOMATION_KEY_UNAVAILABLE",
                    "按键 $name 需要 Android 12 及以上（当前 API ${Build.VERSION.SDK_INT}）",
                )
            }
            val performed = runCatching { performGlobalAction(action) }.getOrDefault(false)
            if (!performed) {
                return BackendOutcome.Refused("AUTOMATION_KEY_REFUSED", "按键 $name 的全局动作返回 false")
            }
            return BackendOutcome.Done
        }

        override fun launch(component: String?, uri: String?): BackendOutcome {
            val service = this@DeepSeekAccessibilityService
            val intent = try {
                when {
                    // 两个都给时 uri 优先：它比「包名/类名」更明确（能直接落到某个页面或商店详情页）。
                    !uri.isNullOrEmpty() -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    !component.isNullOrEmpty() -> {
                        val target = ComponentName.unflattenFromString(component)
                            ?: return BackendOutcome.Refused("AUTOMATION_LAUNCH_TARGET_INVALID", "component 不合法")
                        val className = target.className
                        val expanded = if (className.startsWith(".")) {
                            ComponentName(target.packageName, target.packageName + className)
                        } else {
                            target
                        }
                        Intent().setComponent(expanded)
                    }
                    else -> return BackendOutcome.Refused("AUTOMATION_LAUNCH_TARGET_MISSING", "launch 动作缺少 component 与 uri")
                }
            } catch (error: Throwable) {
                return BackendOutcome.Refused("AUTOMATION_LAUNCH_TARGET_INVALID", "启动目标不合法：${error.javaClass.simpleName}")
            }
            // 安全校验实际接收应用，并绑定组件，防止默认处理程序变化后绕过白名单。
            val resolved = intent.resolveActivity(service.packageManager)
                ?: return BackendOutcome.Refused("AUTOMATION_LAUNCH_TARGET_INVALID", "无法解析启动目标")
            if (!AccessibilityAutomationStore.packageAllowed(service, resolved.packageName)) {
                return BackendOutcome.Refused(AutomationGateCodes.PACKAGE_NOT_ALLOWED, "启动目标不在自动化白名单内")
            }
            intent.component = resolved
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                service.startActivity(intent)
                BackendOutcome.Done
            } catch (error: Throwable) {
                BackendOutcome.Refused("AUTOMATION_LAUNCH_FAILED", "启动失败：${error.javaClass.simpleName}")
            }
        }

        /** 手势派发：`dispatchGesture` 返回 false 就说明这条路走不通（最常见的原因是服务没有声明手势能力）。 */
        private fun dispatch(gesture: GestureDescription): BackendOutcome {
            val dispatched = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
            if (!dispatched) {
                return BackendOutcome.Refused(
                    "AUTOMATION_GESTURE_UNAVAILABLE",
                    "dispatchGesture 返回 false（服务当前声明里没有 canPerformGestures 能力，或系统拒绝了这次手势）",
                )
            }
            return BackendOutcome.Done
        }

        /** 执行时的节点快照：文本传**原始值**，归一化只在执行器的身份计算里做一次。 */
        private fun snapshotOf(node: AccessibilityNodeInfo): AutomationNodeSnapshot {
            val rect = Rect().also(node::getBoundsInScreen)
            return AutomationNodeSnapshot(
                className = node.className?.toString(),
                text = node.text?.toString(),
                viewId = node.viewIdResourceName,
                clickable = node.isClickable,
                bounds = AutomationBounds(rect.left, rect.top, rect.right, rect.bottom),
            )
        }
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

        /** 事件评估里界面树拷贝的节点上限（比只读快照的 MAX_TREE_NODES 大一些，但仍是硬上限）。 */
        private const val MAX_AUTOMATION_NODES = 400

        /** 事件评估里界面树拷贝的深度上限。 */
        private const val MAX_AUTOMATION_DEPTH = 24

        /** `clickCenter` 降级链向上找可点击祖先的最大层数。 */
        private const val MAX_ANCESTOR_DEPTH = 8

        /** 一次点击手势的按压时长（毫秒）。 */
        private const val TAP_DURATION_MS = 50L

        /** 长按手势的按压时长（毫秒）：低于系统长按阈值（约 500 毫秒）不会被识别成长按。 */
        private const val LONG_PRESS_DURATION_MS = 600L

        /** 滑动手势时长下限（毫秒）：与规则模型里 swipe 允许的 100..2000 对齐。 */
        private const val MIN_SWIPE_DURATION_MS = 100

        /** 滑动手势时长上限（毫秒）：与规则模型里 swipe 允许的 100..2000 对齐。 */
        private const val MAX_SWIPE_DURATION_MS = 2_000

        /** 滑动起止点距屏幕边缘的比例：避免从边缘起手被系统手势区吃掉。 */
        private const val SWIPE_INSET_RATIO = 0.1f

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
