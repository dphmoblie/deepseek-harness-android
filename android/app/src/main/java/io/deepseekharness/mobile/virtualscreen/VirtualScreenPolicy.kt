package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONException
import org.json.JSONObject

/** 副屏协议校验独立于系统接口，调用端与特权服务均执行校验。 */
object VirtualScreenPolicy {
    fun component(value: String): String {
        require(value.length <= 320 && Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+").matches(value)) { "目标应用入口无效" }
        return value
    }

    fun dimensions(width: Int, height: Int, dpi: Int) {
        require(width in 320..1440 && height in 320..2560 && width.toLong() * height <= 2_073_600 && dpi in 160..640) { "副屏尺寸超出范围" }
    }

    fun coordinate(value: Int, limit: Int): Int {
        require(value in 0 until limit) { "坐标超出副屏范围" }
        return value
    }

    fun session(value: String): String {
        require(Regex("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}").matches(value)) { "副屏会话标识无效" }
        return value
    }

    /**
     * 副屏输入动作白名单。
     * 执行层（ShellVirtualScreen.action）必须按同一个集合分发：此前出现过策略层已经支持
     * long_press/keyevent/text、执行层的 when 却只认 tap/swipe/back 的漏接线，真机上这三个动作会
     * 直接落到「不支持的副屏操作」并被映射成 VIRTUAL_SCREEN_UNAVAILABLE。
     */
    val INPUT_ACTIONS = setOf("tap", "swipe", "long_press", "keyevent", "text", "back")

    /** 必须指定本次会话拥有的非主屏编号；数值必须是真实整数，不接受字符串或小数截断。 */
    fun inputArguments(p: JSONObject, displayId: Int, width: Int, height: Int): List<String> {
        require(displayId > 0) { "不能向主屏发送副屏输入" }
        fun integer(key: String): Int {
            val value = p.get(key)
            require(value is Int || value is Long) { "副屏参数必须为整数" }
            val number = (value as Number).toLong()
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return number.toInt()
        }
        fun x(key: String) = coordinate(integer(key), width).toString()
        fun y(key: String) = coordinate(integer(key), height).toString()
        fun keyCode(): String {
            val value = p.getString("key")
            return mapOf(
                "BACK" to "4", "ENTER" to "66", "DEL" to "67", "TAB" to "61",
                "DPAD_UP" to "19", "DPAD_DOWN" to "20", "DPAD_LEFT" to "21", "DPAD_RIGHT" to "22",
                "DPAD_CENTER" to "23", "SPACE" to "62", "ESC" to "111",
            )[value] ?: throw IllegalArgumentException("副屏按键不在允许列表")
        }
        fun inputText(): String {
            val value = p.optString("text", "")
            require(value.isNotEmpty() && value.length <= 512) { "副屏文本长度无效" }
            require(value.all { it.code in 0x20..0x7e }) { "副屏文本仅支持可打印 ASCII" }
            // Android input text 用 %s 表示空格；百分号必须先编码，避免被 input 解析器误读。
            return value.replace("%", "%25").replace(" ", "%s")
        }
        val action = p.getString("action")
        require(action in INPUT_ACTIONS) { "不支持的副屏操作" }
        val arguments = when (action) {
            "tap" -> listOf("tap", x("x"), y("y"))
            "swipe" -> {
                val duration = integer("durationMs")
                require(duration in 100..2000) { "滑动时间必须为 100～2000 毫秒" }
                listOf("swipe", x("x"), y("y"), x("endX"), y("endY"), duration.toString())
            }
            "long_press" -> {
                val duration = integer("durationMs")
                require(duration in 500..3000) { "长按时间必须为 500～3000 毫秒" }
                listOf("swipe", x("x"), y("y"), x("x"), y("y"), duration.toString())
            }
            "keyevent" -> listOf("keyevent", keyCode())
            "text" -> listOf("text", inputText())
            "back" -> listOf("keyevent", "4")
            else -> error("不支持的副屏操作")
        }
        return listOf("/system/bin/input", "-d", displayId.toString()) + arguments
    }

    /** 只接受系统明确标记为该副屏已恢复的目标应用；未知 ROM 格式拒绝操作。 */
    fun targetResumed(dump: String, displayId: Int, packageName: String): Boolean {
        if (displayId <= 0) return false
        var current = -1
        var displayIndent = -1
        for (line in dump.lineSequence()) {
            if (line.isBlank()) continue
            val indent = line.takeWhile { it.isWhitespace() }.length
            val header = Regex("^\\s*Display #(\\d+)(?:\\s|$|:)").find(line)
            if (header != null) {
                current = header.groupValues[1].toIntOrNull() ?: -1
                displayIndent = indent
                continue
            }
            // 根容器末尾的全局摘要不属于最后一个副屏，不能沿用上一个编号。
            if (indent <= displayIndent) current = -1
            // 裸 ResumedActivity 是跨屏全局摘要，缩进也可能落在末尾副屏之内，必须忽略。
            if (current == displayId && Regex("\\b(?:mResumedActivity|topResumedActivity)[:=]").containsMatchIn(line) &&
                Regex("(?:\\s|\\{)" + Regex.escape(packageName) + "/").containsMatchIn(line)) return true
        }
        return false
    }

    /**
     * 会话判定（纯函数，供宿主与单测共用）：把「副屏正在启动」与「会话已切换或已结束」区分开，
     * 避免把可重试的瞬时情形一律报成「副屏不可用」。
     * - 宿主还没有会话：副屏正在启动，应等待后重新观察 → `VIRTUAL_SCREEN_BUSY`；
     * - 请求的会话不是当前会话：会话已切换（用户重新开始副屏）或已结束，重新读取状态后可重试 → `VIRTUAL_SCREEN_STOPPED`；
     * - 一致：返回 null，调用方继续执行。
     */
    fun sessionFailure(current: String, requested: String): RuntimeFailure? = when {
        current.isEmpty() -> RuntimeFailure("VIRTUAL_SCREEN_BUSY", "副屏正在启动，等待后重新观察")
        requested != current -> RuntimeFailure("VIRTUAL_SCREEN_STOPPED", "副屏会话已切换，请重新读取状态")
        else -> null
    }

    /**
     * 异常到副屏错误码的映射（纯函数，供命令层与单测共用）。
     * [RuntimeFailure] 保留自身 code（`VIRTUAL_SCREEN_STOPPED`、`VIRTUAL_SCREEN_BUSY` 等）；
     * 参数校验与 JSON 解析失败属于调用方输入问题；只有其余未知异常才归为 `VIRTUAL_SCREEN_UNAVAILABLE`。
     */
    fun errorCode(error: Throwable): String = when (error) {
        is RuntimeFailure -> error.code
        is IllegalArgumentException, is JSONException -> "VIRTUAL_SCREEN_INVALID"
        else -> "VIRTUAL_SCREEN_UNAVAILABLE"
    }

    /** 空白帧采样线数量：横向与纵向各取这么多条线，覆盖首尾但不遍历整帧。 */
    const val FRAME_SAMPLE_LINES = 3

    /** 单条采样线上的采样点上限；配合 [FRAME_SAMPLE_LINES] 把总采样点限制在数百以内。 */
    const val FRAME_SAMPLE_LINE_POINTS = 48

    /** 采样点各颜色通道允许的最大极差，不超过它视为「几乎同色」。 */
    const val FRAME_BLANK_TOLERANCE = 8

    /**
     * 采样线规则：在 `0..extent-1` 上均分取最多 [FRAME_SAMPLE_LINES] 条线，且包含首尾。
     * 宿主采集采样行与 [blankFrame] 共用这一份规则，保证单测结论与真机一致。
     * `extent` 非正时返回空数组，`extent == 1` 时只有第 0 条，不产生除零。
     */
    fun frameSampleLines(extent: Int): IntArray {
        if (extent <= 0) return IntArray(0)
        val count = minOf(FRAME_SAMPLE_LINES, extent)
        if (count <= 1) return intArrayOf(0)
        return IntArray(count) { index -> index * (extent - 1) / (count - 1) }
    }

    /**
     * 空白帧判定（纯函数，不依赖 Android 类，可在 JVM 单测中覆盖）。
     *
     * [pixels] 为按行排列的 ARGB 像素，长度至少 `width * height`。判定只按固定步长采样：
     * 横向与纵向各取 [FRAME_SAMPLE_LINES] 条线，每条线最多取 [FRAME_SAMPLE_LINE_POINTS] 个点，
     * 总采样点不超过 `2 * FRAME_SAMPLE_LINES * FRAME_SAMPLE_LINE_POINTS`，不做整帧遍历。
     * 采样点各颜色通道极差都不超过 [FRAME_BLANK_TOLERANCE] 时判为空白帧（白帧、黑帧或纯色未渲染画面）。
     *
     * 这是启发式判定：目标应用确实有内容、但恰好采样处同色的画面也会被判为空白。
     * 调用方应结合截图内容与 `frameBlank` 一起判断，不要把它当成绝对结论。
     * `width`/`height` 非正或 [pixels] 为空时无从判定，返回 false；采样点越界时按已有像素判定，不抛异常。
     */
    fun blankFrame(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return false
        var sampled = 0
        var minRed = 0xFF
        var maxRed = 0
        var minGreen = 0xFF
        var maxGreen = 0
        var minBlue = 0xFF
        var maxBlue = 0
        fun accept(index: Int) {
            if (index < 0 || index >= pixels.size) return
            val color = pixels[index]
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            if (red < minRed) minRed = red
            if (red > maxRed) maxRed = red
            if (green < minGreen) minGreen = green
            if (green > maxGreen) maxGreen = green
            if (blue < minBlue) minBlue = blue
            if (blue > maxBlue) maxBlue = blue
            sampled++
        }
        val stepX = maxOf(1, width / FRAME_SAMPLE_LINE_POINTS)
        for (row in frameSampleLines(height)) {
            var column = 0
            var points = 0
            while (column < width && points < FRAME_SAMPLE_LINE_POINTS) {
                accept(row * width + column)
                column += stepX
                points++
            }
        }
        val stepY = maxOf(1, height / FRAME_SAMPLE_LINE_POINTS)
        for (column in frameSampleLines(width)) {
            var row = 0
            var points = 0
            while (row < height && points < FRAME_SAMPLE_LINE_POINTS) {
                accept(row * width + column)
                row += stepY
                points++
            }
        }
        if (sampled == 0) return false
        return maxOf(maxRed - minRed, maxGreen - minGreen, maxBlue - minBlue) <= FRAME_BLANK_TOLERANCE
    }
}
