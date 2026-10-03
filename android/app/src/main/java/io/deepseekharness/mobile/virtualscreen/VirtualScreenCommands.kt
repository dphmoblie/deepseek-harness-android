package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import org.json.JSONObject

/** 复用已鉴权的本机设备桥，只操作用户在原生页面创建的副屏会话。 */
object VirtualScreenCommands {
    fun execute(command: String, param: String): DeviceCommandResult {
        try {
            require(param.length <= 4096) { "副屏参数过长" }
            val service = VirtualScreenService.current
            val text = if (command == "virtualScreenState") {
                require(param.isEmpty())
                (service?.state() ?: JSONObject().put("active", false).put("starting", false)
                    .put("error", VirtualScreenService.lastError)).toString()
            } else {
                if (service == null) throw RuntimeFailure("VIRTUAL_SCREEN_NOT_STARTED", "请先在设置的目标应用副屏页面选择应用")
                val request = JSONObject(param)
                VirtualScreenPolicy.session(request.getString("sessionId"))
                when (command) {
                    "virtualScreenCapture" -> service.screenshotEnvelope(request.getString("sessionId")).toString()
                    "virtualScreenAction" -> service.action(request).toString()
                    else -> throw IllegalArgumentException("副屏命令不支持")
                }
            }
            return DeviceCommandResult(true, 0, text, false, null)
        } catch (e: Exception) {
            // 映射规则与单测共用同一份纯函数：会话切换等可重试情形必须保留自己的错误码。
            val code = VirtualScreenPolicy.errorCode(e)
            return DeviceCommandResult(false, 1, "副屏请求未完成，请检查会话与目标应用状态", false, code)
        }
    }
}
