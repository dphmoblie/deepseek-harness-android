package io.deepseekharness.mobile.virtualscreen

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 通过设备桥主动打开副屏的受控入口。
 *
 * 目标必须由 AI 明确提供包名，尺寸和权限仍沿用用户在副屏设置页保存的配置；
 * 默认同步等待副屏就绪（最多 8 秒，可经 `waitReadyMs` 调整），一次性返回可用的
 * `sessionId`、`displayId`、`treeSupported` 及首屏语义控件摘要（无障碍可用时），
 * 让 AI 不必反复轮询状态即可直接进入无截图、无 OCR 的副屏交互。
 */
object VirtualScreenAgentStarter {
    internal const val DEFAULT_WAIT_READY_MILLIS = 8_000
    internal const val MAX_WAIT_READY_MILLIS = 15_000
    private const val POLL_INTERVAL_MILLIS = 100L
    private const val INITIAL_TREE_WAIT_MILLIS = 400L

    fun start(context: Context, param: String): DeviceCommandResult {
        return try {
            require(Build.VERSION.SDK_INT >= 29) { "VIRTUAL_SCREEN_UNSUPPORTED" }
            val data = JSONObject(param)
            val packageName = data.optString("packageName").trim()
            require(packageName.length in 1..192 && VirtualScreenPreferences.targetKind(packageName) == VirtualScreenTargetKind.PACKAGE) {
                "VIRTUAL_SCREEN_INVALID"
            }
            val allowSystemApps = io.deepseekharness.mobile.shizuku.HighRiskPermissionStore
                .status(context)
                .isAllowed(io.deepseekharness.mobile.shizuku.HighRiskCategory.ACCESSIBILITY_SYSTEM_APPS)
            VirtualScreenPolicy.requireAgentTargetAllowed(packageName, allowSystemApps)
            val waitReadyMs = if (data.has("waitReadyMs")) {
                val raw = data.opt("waitReadyMs")
                require(raw is Int || raw is Long) { "VIRTUAL_SCREEN_INVALID" }
                val value = (raw as Number).toInt()
                require(value in 0..MAX_WAIT_READY_MILLIS) { "VIRTUAL_SCREEN_INVALID" }
                value
            } else {
                DEFAULT_WAIT_READY_MILLIS
            }
            val switchIfActive = data.optBoolean("switchIfActive", false)
            val showOverlay = data.optBoolean("showOverlay", false)

            VirtualScreenService.current?.let { running ->
                val state = running.state()
                if (state.optBoolean("stopping")) {
                    throw RuntimeFailure("VIRTUAL_SCREEN_BUSY", "副屏正在结束，请稍后重试")
                }
                if (state.optBoolean("starting") && !state.optBoolean("active")) {
                    val settled = awaitReady(waitReadyMs)
                    if (settled != null) {
                        if (showOverlay) requestOverlayBestEffort(settled.first)
                        return readyResult(settled.second, packageName, switched = false, alreadyActive = false)
                    }
                    return DeviceCommandResult(
                        true,
                        0,
                        JSONObject()
                            .put("action", "start")
                            .put("state", "starting")
                            .put("ready", false)
                            .put("targetPackage", packageName)
                            .put("treeSupported", VirtualScreenTree.available())
                            .toString(),
                        false,
                        null,
                    )
                }
                val currentPackage = state.optString("targetPackage", state.optString("packageName"))
                val sessionId = state.optString("sessionId")
                if (switchIfActive && sessionId.isNotEmpty() && currentPackage != packageName) {
                    running.action(
                        JSONObject()
                            .put("sessionId", sessionId)
                            .put("action", "target")
                            .put("packageName", packageName),
                    )
                    val refreshed = running.freshState()
                    if (showOverlay) requestOverlayBestEffort(running)
                    return readyResult(refreshed, packageName, switched = true, alreadyActive = true)
                }
                if (showOverlay) requestOverlayBestEffort(running)
                return readyResult(state, currentPackage.ifEmpty { packageName }, switched = false, alreadyActive = true)
            }

            val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component
                ?.flattenToShortString()
                ?: throw RuntimeFailure(VirtualScreenPolicy.LAUNCH_UNRESOLVED_CODE, "没有找到目标应用的可启动入口")
            val settings = VirtualScreenPreferences.read(context)
            val display = context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val metrics = android.util.DisplayMetrics()
            runCatching { display?.getRealMetrics(metrics) }
            val width = metrics.widthPixels.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            val height = metrics.heightPixels.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
            val density = metrics.density.takeIf { it > 0f } ?: context.resources.displayMetrics.density
            val request = VirtualScreenStartRequest(target = packageName)
            val spec = VirtualScreenStart.resolve(request, settings, width, height, density)
            VirtualScreenService.clearLastError()
            ContextCompat.startForegroundService(
                context,
                Intent(context, VirtualScreenService::class.java)
                    .putExtra("component", component)
                    .putVirtualScreenStart(spec, settings.adaptive, settings.orientation),
            )
            if (waitReadyMs > 0) {
                val settled = awaitReady(waitReadyMs)
                if (settled != null) {
                    if (showOverlay) requestOverlayBestEffort(settled.first)
                    return readyResult(
                        state = settled.second,
                        targetPackage = packageName,
                        switched = false,
                        alreadyActive = false,
                        component = component,
                        spec = spec,
                    )
                }
            }
            DeviceCommandResult(
                true,
                0,
                JSONObject()
                    .put("action", "start")
                    .put("state", "starting")
                    .put("ready", false)
                    .put("targetPackage", packageName)
                    .put("component", component)
                    .put("widthPx", spec.widthPx)
                    .put("heightPx", spec.heightPx)
                    .put("densityDpi", spec.densityDpi)
                    .put("treeSupported", VirtualScreenTree.available())
                    .toString(),
                false,
                null,
            )
        } catch (error: Throwable) {
            val code = when (error) {
                is RuntimeFailure -> error.code
                else -> when (error.message) {
                    "VIRTUAL_SCREEN_UNSUPPORTED" -> "VIRTUAL_SCREEN_UNSUPPORTED"
                    "VIRTUAL_SCREEN_INVALID" -> "VIRTUAL_SCREEN_INVALID"
                    else -> VirtualScreenPolicy.errorCode(error)
                }
            }
            val message = (error as? RuntimeFailure)?.message?.takeIf { it.isNotBlank() }
                ?: "副屏启动未完成，请检查目标应用与 Shizuku 状态"
            DeviceCommandResult(false, 1, message, false, code)
        }
    }

    internal sealed interface PollDecision {
        data object Continue : PollDecision
        data class Ready(val state: JSONObject) : PollDecision
        data class Failed(val code: String, val message: String) : PollDecision
    }

    internal fun evaluatePoll(
        state: JSONObject?,
        lastErrorCode: String,
        lastErrorMessage: String,
    ): PollDecision {
        if (state != null) {
            val sessionId = state.optString("sessionId")
            val displayId = state.optInt("displayId", -1)
            if (state.optBoolean("active") && sessionId.isNotEmpty() && displayId > 0) {
                return PollDecision.Ready(state)
            }
            if (state.optBoolean("stopping")) {
                val code = lastErrorCode.ifEmpty { "VIRTUAL_SCREEN_STOPPED" }
                return PollDecision.Failed(code, lastErrorMessage.ifEmpty { "副屏启动中断" })
            }
            return PollDecision.Continue
        }
        if (lastErrorCode.isNotEmpty()) {
            return PollDecision.Failed(lastErrorCode, lastErrorMessage.ifEmpty { "副屏启动失败" })
        }
        return PollDecision.Continue
    }

    /** 等待副屏服务完成异步启动；若中途启动失败则立即抛出真实错误码而非假报 starting。 */
    private fun awaitReady(waitReadyMs: Int): Pair<VirtualScreenService, JSONObject>? {
        val deadline = SystemClock.elapsedRealtime() + waitReadyMs
        while (true) {
            val service = VirtualScreenService.current
            when (
                val decision = evaluatePoll(
                    state = service?.state(),
                    lastErrorCode = VirtualScreenService.lastErrorCode,
                    lastErrorMessage = VirtualScreenService.lastError,
                )
            ) {
                is PollDecision.Ready -> if (service != null) return service to decision.state
                is PollDecision.Failed -> throw RuntimeFailure(decision.code, decision.message)
                PollDecision.Continue -> Unit
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return null
            SystemClock.sleep(minOf(POLL_INTERVAL_MILLIS, remaining))
        }
    }

    private fun readyResult(
        state: JSONObject,
        targetPackage: String,
        switched: Boolean,
        alreadyActive: Boolean,
        component: String = "",
        spec: VirtualScreenSpec? = null,
    ): DeviceCommandResult {
        val displayId = state.optInt("displayId", -1)
        val payload = JSONObject()
            .put("action", "start")
            .put("state", if (alreadyActive && !switched) "already_active" else "active")
            .put("ready", true)
            .put("switched", switched)
            .put("sessionId", state.optString("sessionId"))
            .put("displayId", displayId)
            .put("targetPackage", state.optString("targetPackage", state.optString("packageName", targetPackage)))
            .put("widthPx", state.optInt("width", spec?.widthPx ?: 0))
            .put("heightPx", state.optInt("height", spec?.heightPx ?: 0))
            .put("treeSupported", VirtualScreenTree.available())
        if (component.isNotEmpty()) payload.put("component", component)
        if (spec != null) payload.put("densityDpi", spec.densityDpi)
        if (displayId > 0 && VirtualScreenTree.available()) {
            val initial = readInitialSemanticTree(displayId)
            if (initial != null) {
                val elements = JSONArray()
                initial.elements.take(VirtualScreenTreeDiff.MAX_PAGE_ELEMENTS).forEach {
                    elements.put(it.toCompactJson())
                }
                payload.put("elementCount", initial.elements.size)
                payload.put("elements", elements)
                if (initial.imeText.isNotEmpty()) payload.put("imeText", initial.imeText)
            }
        }
        return DeviceCommandResult(true, 0, payload.toString(), false, null)
    }

    /** 刚启动时窗口可能还差 ~150ms 才出现在无障碍窗口表里，短等一次即可随启动回执带回首屏语义列表。 */
    private fun readInitialSemanticTree(displayId: Int): VirtualScreenTreeDiff.SemanticSnapshot? {
        val first = VirtualScreenTree.dumpSemantic(displayId)
        if (first != null && first.elements.isNotEmpty()) return first
        SystemClock.sleep(INITIAL_TREE_WAIT_MILLIS)
        return VirtualScreenTree.dumpSemantic(displayId) ?: first
    }

    private fun requestOverlayBestEffort(service: VirtualScreenService) {
        Handler(Looper.getMainLooper()).post {
            runCatching { service.showOverlay() }
        }
    }
}
