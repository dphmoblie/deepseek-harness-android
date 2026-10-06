package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.runtime.RuntimeFailure

/**
 * 副屏 Shell 直通通道的纯策略层。
 *
 * 为什么需要它：副屏是 Shizuku（shell uid）创建的 VirtualDisplay，逻辑 displayId 取自
 * `dumpsys display` 的 mViewports（实测形如 38 / 49，726×1600）。用户在真机上确认
 * `input -d <逻辑 id> tap 622 880` 能作用到这块屏上；而既有的 `mobile_device_shell` 里跑
 * `input tap …` 只会打在主屏——AI 因此没法在副屏上注入输入。本通道把会话副屏的 displayId
 * 注入脚本环境（`export DISPLAY_ID=<id>;`），让 AI 写 `input -d "$DISPLAY_ID" …` 与
 * `am start --display "$DISPLAY_ID" …` 就能确定地落到那块屏上。
 *
 * 本对象只做三件事，全部是纯函数（无 Android 依赖，可在 JVM 单测里直接跑）：
 * 1. 校验脚本与 displayId，拒绝时给出**可区分**的错误码；
 * 2. 生成确定性的「前缀 + 脚本」命令文本（同一输入必然得到同一文本，便于单测与复现）；
 * 3. 明确「本通道只服务副屏」：显式传 0（主屏）一律拒绝。
 *
 * 不碰渲染链路：截图 / 预览仍由 App 自己的副屏通道负责（`screencap -d` 只认 SurfaceFlinger
 * 的 64 位 id，与本通道的逻辑 id 不是一回事）。
 */
object VirtualScreenShellPolicy {
    /** 设备端一次性脚本的字节上限，与 `DeviceCommandRunner.MAX_SHELL_SCRIPT_CHARS` 保持一致。 */
    const val MAX_SCRIPT_BYTES = 16 * 1024

    /** 桥命令名：AI 工具面经 `callBridge` 走这个名字（见 `DeviceBridgeServer` 的分派）。 */
    const val COMMAND = "virtualScreenShell"

    /** 脚本本身不合法（缺失 / 空 / 过长 / 含控制字符），与既有 `mobile_device_shell` 语义一致。 */
    const val SCRIPT_INVALID_CODE = "DEVICE_COMMAND_INVALID"

    /** 用户没有在 Shizuku 设置里开启 AI Shell。 */
    const val SHELL_DISABLED_CODE = "DEVICE_SHELL_DISABLED"

    /** 没有正在运行的副屏会话，或会话尚未取得显示编号。 */
    const val UNAVAILABLE_CODE = "VIRTUAL_SCREEN_UNAVAILABLE"

    /** 显式传入的 displayId 不是本会话的副屏。 */
    const val DISPLAY_INVALID_CODE = "VIRTUAL_SCREEN_DISPLAY_INVALID"

    /** 一次调用的完整执行计划：生效的副屏编号，以及要交给设备端 sh 的最终脚本文本。 */
    data class Plan(val displayId: Int, val command: String)

    /**
     * 一次调用的全部判定入口：校验脚本、确认会话副屏、解析 displayId、拼出最终脚本。
     *
     * 校验顺序刻意是「脚本 → 会话 → displayId」：脚本是否合法属于调用方的输入契约，
     * 不该随副屏开没开而变——同一个坏脚本在任何时刻都必须报同一个错误码，
     * 模型才学得到稳定规则，而不是把「脚本写错」误读成「副屏不可用」。
     */
    fun plan(script: String?, requestedDisplayId: Int?, active: Boolean, sessionDisplayId: Int): Plan {
        val body = requireScript(script)
        val displayId = resolveDisplayId(requireSessionDisplayId(active, sessionDisplayId), requestedDisplayId)
        return Plan(displayId, command(body, displayId))
    }

    /** 校验并原样返回脚本；空、超过 16 KiB（UTF-8 字节）、含 NUL 或 CR 都拒绝。 */
    fun requireScript(script: String?): String {
        val value = script ?: throw RuntimeFailure(SCRIPT_INVALID_CODE, "Shell 脚本缺失")
        if (value.isBlank()) {
            throw RuntimeFailure(SCRIPT_INVALID_CODE, "Shell 脚本为空")
        }
        if (value.any { it == '\u0000' || it == '\r' }) {
            throw RuntimeFailure(SCRIPT_INVALID_CODE, "Shell 脚本包含非法控制字符")
        }
        if (value.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) {
            throw RuntimeFailure(SCRIPT_INVALID_CODE, "Shell 脚本超过 16 KiB")
        }
        return value
    }

    /**
     * 取会话副屏的 displayId（只读）。
     *
     * 会话不存在、未激活、还没拿到显示编号，统一归为「副屏不可用」（与既有
     * `VirtualScreenPolicy.errorCode` 对无法归类异常的兜底语义一致），而不是伪装成脚本错误。
     */
    fun requireSessionDisplayId(active: Boolean, sessionDisplayId: Int): Int {
        if (!active || sessionDisplayId <= 0) {
            throw RuntimeFailure(UNAVAILABLE_CODE, "副屏会话未运行或尚未取得显示编号，请先启动目标应用副屏")
        }
        return sessionDisplayId
    }

    /**
     * 解析本次生效的 displayId。
     *
     * - 缺省（null）：用会话副屏 id，这是常态。
     * - 显式传入：必须**等于**会话副屏 id。理由不是洁癖，而是这条通道的全部价值就是
     *   「把输入落到当前会话的那块虚拟屏上」；若允许任意 id，AI 就无法区分自己点的是哪块屏，
     *   一次误传就会把输入打到别的显示上，且错误在副屏上完全看不出来。
     * - 显式传 0（主屏）：无论会话状态如何都一律拒绝，错误码与「不一致」相同（调用方只需认识
     *   一个码），但消息写清原因——主屏请继续用 `mobile_device_shell` / `mobile_device_tap`
     *   等既有工具，它们本来就打在主屏上。
     */
    fun resolveDisplayId(sessionDisplayId: Int, requestedDisplayId: Int?): Int {
        if (requestedDisplayId == null) return sessionDisplayId
        if (requestedDisplayId == 0) {
            throw RuntimeFailure(DISPLAY_INVALID_CODE, "displayId=0 是主屏；本通道只服务副屏，主屏请使用既有设备工具")
        }
        if (requestedDisplayId < 0) {
            throw RuntimeFailure(DISPLAY_INVALID_CODE, "displayId 不能为负数")
        }
        if (requestedDisplayId != sessionDisplayId) {
            throw RuntimeFailure(
                DISPLAY_INVALID_CODE,
                "displayId=$requestedDisplayId 不是当前会话副屏（$sessionDisplayId）",
            )
        }
        return sessionDisplayId
    }

    /**
     * 注入 displayId 的确定性前缀。
     *
     * 幂等有两层含义，两层都成立：(1) 纯函数——同一个 displayId 永远得到同一段文本；
     * (2) 重复执行不改变结果——`export` 每次都把 `DISPLAY_ID` 固定成同一个值。
     * 前缀里的注释行是给 AI 与人工排障看的：直接说明 `input -d "$DISPLAY_ID"` 与
     * `am start --display "$DISPLAY_ID"` 的用法。
     *
     * 脚本跑在设备端**全新的** `/system/bin/sh -s` 进程里，因此这里的 export 只影响本次脚本的后续命令。
     */
    fun prefix(displayId: Int): String = buildString {
        append("# DSH 副屏通道：本次 Shell 在副屏 displayId=").append(displayId).append(" 上执行。\n")
        append("# 注入输入：input -d \"\$DISPLAY_ID\" tap 622 880（等价于 /system/bin/input -d \"\$DISPLAY_ID\" …）\n")
        append("# 启动界面：am start --display \"\$DISPLAY_ID\" -n <包名>/<Activity>\n")
        append("export DISPLAY_ID=").append(displayId).append(";\n")
    }

    /**
     * 前缀 + 脚本。
     *
     * 长度按整段 param 计（设备端限制的就是这一段）：脚本本身 16 KiB 已经够用，
     * 但加上前缀后若超过上限，这里提前拒绝，避免把必然失败的脚本发到设备上。
     */
    fun command(script: String, displayId: Int): String {
        val text = prefix(displayId) + script
        if (text.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) {
            throw RuntimeFailure(SCRIPT_INVALID_CODE, "脚本加上通道前缀后超过 16 KiB")
        }
        return text
    }
}
