package io.deepseekharness.mobile.virtualscreen

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
        val arguments = when (p.getString("action")) {
            "tap" -> listOf("tap", x("x"), y("y"))
            "swipe" -> {
                val duration = integer("durationMs")
                require(duration in 100..2000) { "滑动时间必须为 100～2000 毫秒" }
                listOf("swipe", x("x"), y("y"), x("endX"), y("endY"), duration.toString())
            }
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
}
