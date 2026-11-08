package io.deepseekharness.mobile.virtualscreen

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import org.json.JSONObject

/**
 * AI 通过设备桥启动副屏的受控入口。
 *
 * 目标必须由 AI 明确提供包名，尺寸和权限仍沿用用户在副屏设置页保存的配置；
 * 这样 AI 可以主动开始一次副屏会话，但不能替用户选择任意显示器或绕过设置。
 */
object VirtualScreenAgentStarter {
    fun start(context: Context, param: String): DeviceCommandResult {
        return try {
            require(Build.VERSION.SDK_INT >= 29) { "VIRTUAL_SCREEN_UNSUPPORTED" }
            val data = JSONObject(param)
            val packageName = data.optString("packageName").trim()
            require(packageName.length in 1..192 && VirtualScreenPreferences.targetKind(packageName) == VirtualScreenTargetKind.PACKAGE) {
                "VIRTUAL_SCREEN_INVALID"
            }
            if (VirtualScreenService.current != null) {
                val state = VirtualScreenService.current?.state() ?: JSONObject().put("active", false)
                if (state.optBoolean("stopping")) {
                    throw RuntimeFailure("VIRTUAL_SCREEN_BUSY", "副屏正在结束，请稍后重试")
                }
                // startForegroundService 返回后，目标应用还需要几百毫秒才能拿到 sessionId。
                // 这时要如实返回 starting；把空 session 报成 already_active 会让 AI 立刻拿
                // 空编号去截图或操作，得到误导性的会话错误。
                if (state.optBoolean("starting") && !state.optBoolean("active")) {
                    return DeviceCommandResult(true, 0, JSONObject()
                        .put("action", "start")
                        .put("state", "starting")
                        .put("targetPackage", packageName)
                        .toString(), false, null)
                }
                return DeviceCommandResult(true, 0, JSONObject()
                    .put("action", "start")
                    .put("state", "already_active")
                    .put("sessionId", state.optString("sessionId"))
                    .put("displayId", state.optInt("displayId", -1))
                    .put("targetPackage", state.optString("targetPackage", state.optString("packageName")))
                    .toString(), false, null)
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
            ContextCompat.startForegroundService(
                context,
                Intent(context, VirtualScreenService::class.java)
                    .putExtra("component", component)
                    .putVirtualScreenStart(spec, settings.adaptive, settings.orientation),
            )
            DeviceCommandResult(true, 0, JSONObject()
                .put("action", "start")
                .put("state", "starting")
                .put("targetPackage", packageName)
                .put("component", component)
                .put("widthPx", spec.widthPx)
                .put("heightPx", spec.heightPx)
                .put("densityDpi", spec.densityDpi)
                .toString(), false, null)
        } catch (error: Throwable) {
            val code = when (error) {
                is RuntimeFailure -> error.code
                else -> when (error.message) {
                    "VIRTUAL_SCREEN_UNSUPPORTED" -> "VIRTUAL_SCREEN_UNSUPPORTED"
                    "VIRTUAL_SCREEN_INVALID" -> "VIRTUAL_SCREEN_INVALID"
                    else -> VirtualScreenPolicy.errorCode(error)
                }
            }
            DeviceCommandResult(false, 1, "副屏启动未完成，请检查目标应用与 Shizuku 状态", false, code)
        }
    }
}
