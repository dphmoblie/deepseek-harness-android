package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
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

    /** 预览动作字段名：宿主 config 动作与工具面、设置页共用的稳定字段标识。 */
    const val PREVIEW_FIELD = "previewMode"

    /** 目标应用动作字段名：宿主 target 动作与工具面、设置页共用的稳定字段标识。 */
    const val TARGET_FIELD = "component"

    /**
     * 预览模式到采集间隔（毫秒）的映射；模式名是工具面与设置页共用的稳定标识。
     * 顺序与取值是调用端与宿主共用的稳定契约，不要调整：`limited` 保持限帧（180 毫秒，历史硬编码值），
     * 其余模式分别对应 15/30/60 帧每秒的采集间隔。
     */
    val FRAME_MODES: Map<String, Int> = mapOf("limited" to 180, "15fps" to 66, "30fps" to 33, "60fps" to 16)

    /** 校验预览模式并返回采集间隔；未知模式抛 [IllegalArgumentException]。 */
    fun frameInterval(mode: String): Int =
        FRAME_MODES[mode] ?: throw IllegalArgumentException("副屏预览模式不在允许列表")

    /** 预览模式对应的状态标签：limited → "limited-fps"，其余 → "realtime-<模式名>"（例如 "realtime-30fps"）。 */
    fun frameModeLabel(mode: String): String = if (mode == "limited") "limited-fps" else "realtime-$mode"

    /**
     * 把 [frameModeLabel] 写出去的状态值反解回档位，供「显示当前档位」「重发 config」使用。
     *
     * 认不出来时回落到 `limited`：这是最省电、也最不可能让用户觉得「怎么突然很烫」的一档。
     */
    fun frameModeOf(label: String): String = when {
        label == "limited-fps" -> "limited"
        label.startsWith("realtime-") -> label.removePrefix("realtime-").takeIf { it in FRAME_MODES } ?: "limited"
        label in FRAME_MODES -> label
        else -> "limited"
    }

    /**
     * 预览窗口拉取一帧 PNG 的间隔：跟随当前档位，但不下探到 [PREVIEW_PULL_FLOOR_MILLIS]。
     *
     * 采集与出帧始终按 [frameInterval] 跑；这里限制的只是「截图 → 解码 PNG → 贴图」这条更贵的链路，
     * 否则 60fps 档位会把主线程压满，用户看到的反而更卡。
     */
    const val PREVIEW_PULL_FLOOR_MILLIS = 120

    fun previewPullInterval(mode: String): Int = maxOf(PREVIEW_PULL_FLOOR_MILLIS, frameInterval(mode))

    /** 从请求里取出并校验目标应用入口（字段名 [TARGET_FIELD]），复用 [component] 的规则。 */
    fun targetRequest(p: JSONObject): String = component(p.getString(TARGET_FIELD))

    /** 目标应用切换的确认预算（毫秒）：`am start -W` 返回只代表命令派发完，最多再等这么久确认它真的在前台。 */
    const val TARGET_SWITCH_TIMEOUT_MILLIS = 3000L

    /** 目标切换确认的轮询间隔（毫秒）：每次确认都要起一个 `dumpsys` 进程，太密会拖慢切换后的系统。 */
    const val TARGET_SWITCH_POLL_INTERVAL_MILLIS = 200L

    /** 目标切换未能在预算内确认的错误码：语义是「这一次切换没确认成功」，不是「副屏不可用」。 */
    const val TARGET_SWITCH_TIMEOUT_CODE = "VIRTUAL_SCREEN_TARGET_TIMEOUT"

    /** 目标切换超时的中文提示；调用层与单测共用同一份文案。 */
    const val TARGET_SWITCH_TIMEOUT_MESSAGE = "目标应用未能在副屏上进入前台，请稍后重试或改用原生入口切换"

    /** 目标切换超时的失败对象：必须抛它而不是 [IllegalStateException]，否则会被 [errorCode] 的兜底吞成「副屏不可用」。 */
    fun targetSwitchFailure(): RuntimeFailure = RuntimeFailure(TARGET_SWITCH_TIMEOUT_CODE, TARGET_SWITCH_TIMEOUT_MESSAGE)

    /**
     * 有界轮询 [visible] 直到为 true 或预算用尽（纯函数，时钟与等待都是注入点，可在 JVM 单测里表驱动覆盖）。
     *
     * 真机传 `SystemClock.elapsedRealtime` 与 `SystemClock.sleep`，单测传假时钟，不必真的等 3 秒。
     * 契约：
     * - 最多询问 `timeoutMillis / intervalMillis + 1` 次，整体耗时不超过 [timeoutMillis]，不会无限等；
     * - [visible] 抛出的异常按「这一刻问不出来」计（例如并发的 `dumpsys` 抖动），仍在预算内继续重试，
     *   不能让它把已实现的切换判成功能不存在；
     * - 超时返回 false，不抛异常：报什么错由调用层决定（见 [targetSwitchFailure]）。
     */
    fun waitUntilVisible(
        timeoutMillis: Long = TARGET_SWITCH_TIMEOUT_MILLIS,
        intervalMillis: Long = TARGET_SWITCH_POLL_INTERVAL_MILLIS,
        elapsedMillis: () -> Long,
        sleepMillis: (Long) -> Unit,
        visible: () -> Boolean,
    ): Boolean {
        require(timeoutMillis > 0 && intervalMillis > 0) { "副屏轮询参数无效" }
        val deadline = elapsedMillis() + timeoutMillis
        while (true) {
            if (runCatching { visible() }.getOrDefault(false)) return true
            val now = elapsedMillis()
            if (now >= deadline) return false
            // 最后一次等待不超过剩余预算，总耗时始终有界。
            sleepMillis(minOf(intervalMillis, deadline - now))
        }
    }

    /**
     * 文本输入的分流判据：只有可打印 ASCII 能交给设备 Shell 的 `input text` 命令，
     * 含中文、emoji 或任何其它 Unicode 字符时必须改走无障碍服务的定向注入。
     * 这个判断是纯函数，真机行为与单测共用同一份规则。
     */
    fun needsAccessibilityText(value: String): Boolean = value.any { it.code < 0x20 || it.code > 0x7e }

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
     *
     * 教训（不要重犯）：`else` 兜底只留给「确实没有可用实现」的场合，它不该用来表达等待与超时。
     * 已经实现的路径遇到可重试情形（等待、忙碌、超时）必须自己抛带明确 code 的 [RuntimeFailure]：
     * 此前 `target` 切换在 `am start -W` 后只查一次可见性，失败时 `check` 抛出的 IllegalStateException
     * 落到这里被映射成 `VIRTUAL_SCREEN_UNAVAILABLE`，真机上表现为「副屏不可用」，而会话其实一切正常；
     * 现在那条路径抛 [targetSwitchFailure]（`VIRTUAL_SCREEN_TARGET_TIMEOUT`）。同类漏接线教训见 [INPUT_ACTIONS]。
     */
    fun errorCode(error: Throwable): String = when (error) {
        is RuntimeFailure -> error.code
        is IllegalArgumentException, is JSONException -> "VIRTUAL_SCREEN_INVALID"
        else -> "VIRTUAL_SCREEN_UNAVAILABLE"
    }

    /** 宿主采集的采样行数：按行取样，覆盖首尾但不遍历整帧。 */
    const val FRAME_SAMPLE_ROWS = 24

    /** 单行内的采样点上限：行内按步长取样，避免整行遍历。 */
    const val FRAME_SAMPLE_ROW_POINTS = 128

    /** 预览内容统计的采样列数上限。 */
    const val PREVIEW_SAMPLE_COLUMNS = 128

    /**
     * 预览内容统计的采样行数上限。
     * 真机副屏是 726×1600，这个密度下相邻采样行只隔十几像素，几十像素高的文字必然落在多行采样上，
     * 不会像 0.2.9 之前那样只取 3 行、正好从文字上下穿过去。
     */
    const val PREVIEW_SAMPLE_ROWS = 96

    /** 采样点各颜色通道允许的最大极差，不超过它视为「几乎同色」。 */
    const val FRAME_BLANK_TOLERANCE = 8

    /** 预览判「这一帧有可显示内容」的亮度极差阈值，取值依据见 [frameHasContent]。 */
    const val PREVIEW_CONTENT_SPREAD = 24

    /** 与上一帧比较时的单点亮度差阈值，取值依据见 [frameChanged]。 */
    const val PREVIEW_SAMPLE_DELTA = 12

    /**
     * 均分取点规则：在 `0..extent-1` 上最多取 `count` 个下标，且包含首尾。
     * `extent` 或 `count` 非正时返回空数组；只剩一个点时只返回 0，不产生除零。
     */
    private fun sampleAxis(extent: Int, count: Int): IntArray {
        if (extent <= 0 || count <= 0) return IntArray(0)
        val size = minOf(count, extent)
        if (size <= 1) return intArrayOf(0)
        return IntArray(size) { index -> index * (extent - 1) / (size - 1) }
    }

    /**
     * 采样行规则：宿主采集采样行与 [blankFrame] 共用这一份规则，保证单测结论与真机一致。
     * 0.2.9 之前只取 3 行，真机上大片白底的页面被误判成空白帧的概率很高；现在取 [FRAME_SAMPLE_ROWS] 行，
     * 仍然只按行取样、不遍历整帧。
     */
    fun frameSampleRows(extent: Int): IntArray = sampleAxis(extent, FRAME_SAMPLE_ROWS)

    /** 预览采样行规则：比宿主侧更密，用来判断「这一帧有没有可显示的内容」。 */
    fun previewSampleRows(extent: Int): IntArray = sampleAxis(extent, PREVIEW_SAMPLE_ROWS)

    /** 预览采样列规则。 */
    fun previewSampleColumns(extent: Int): IntArray = sampleAxis(extent, PREVIEW_SAMPLE_COLUMNS)

    /**
     * 宿主侧的空白帧判定（纯函数，不依赖 Android 类，可在 JVM 单测中覆盖）。
     *
     * [pixels] 是宿主按 [frameSampleRows] 取出的若干整行像素、逐行首尾相接；[height] 是行数。
     * 判定在每行内按 `max(1, width / FRAME_SAMPLE_ROW_POINTS)` 的步长取样，不做整行或整帧遍历。
     * 采样点各颜色通道极差都不超过 [FRAME_BLANK_TOLERANCE] 时判为空白帧（白帧、黑帧或纯色未渲染画面）。
     *
     * 这是启发式判定：目标应用确实有内容、但恰好采样处同色的画面也会被判为空白，所以它只是给 AI 的提示信号。
     * 预览要不要清屏不再依赖它（见 [previewOutcome] 与 [frameHasContent]）。
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
        val stepX = maxOf(1, width / FRAME_SAMPLE_ROW_POINTS)
        for (row in 0 until height) {
            var column = 0
            while (column < width) {
                accept(row * width + column)
                column += stepX
            }
        }
        if (sampled == 0) return false
        return maxOf(maxRed - minRed, maxGreen - minGreen, maxBlue - minBlue) <= FRAME_BLANK_TOLERANCE
    }

    /** ARGB 像素的亮度（0..255）：内容统计只需要相对变化，整数近似足够。 */
    fun luminance(color: Int): Int {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    /** 采样点亮度极差；空数组按 0 处理。 */
    fun frameSpread(samples: IntArray): Int {
        if (samples.isEmpty()) return 0
        var minimum = samples[0]
        var maximum = samples[0]
        for (value in samples) {
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
        }
        return maximum - minimum
    }

    /**
     * 这一帧有没有可显示的内容（纯函数，表驱动单测覆盖）。
     *
     * 判据是 [previewSampleRows]×[previewSampleColumns] 采样网格上的亮度极差是否超过 [PREVIEW_CONTENT_SPREAD]：
     * - 没渲染的帧（纯白、纯黑、全透明）在采样网格上极差只有 0–3，判成「接近纯色」；
     * - 只要有一行文字、一个图标或一条进度条，极差通常远超 24，判成「有内容」；
     * - 阈值取在两者之间，并且**宁可承认「接近纯色」也不把没渲染的画面当有效画面显示**，所以不取更小；
     * - 反过来也不取更大：0.2.9 之前只取 3 行采样、极差阈值 8，真机上大片白底的页面被误判成空白帧，
     *   预览就一直是黑的；网格采样 + 这个阈值正是为了消掉那类误判。
     */
    fun frameHasContent(samples: IntArray): Boolean = frameSpread(samples) > PREVIEW_CONTENT_SPREAD

    /**
     * 这一帧相对上一帧有没有变化（纯函数，表驱动单测覆盖）。
     *
     * 首帧（[previous] 为空）或两次采样规模不同一律算「变了」，保证第一帧一定被显示出来。
     * 此后只要有一个采样点的亮度差超过 [PREVIEW_SAMPLE_DELTA] 就算「变了」：这个差值已经滤掉渲染抖动，
     * 不再叠加「差异比例阈值」——再要求一定比例的点变化，会把「只多了一行字」这种真实变化说成「画面暂无变化」。
     */
    fun frameChanged(previous: IntArray?, current: IntArray): Boolean {
        if (previous == null || previous.size != current.size) return true
        for (index in current.indices) {
            val delta = current[index] - previous[index]
            if (delta > PREVIEW_SAMPLE_DELTA || delta < -PREVIEW_SAMPLE_DELTA) return true
        }
        return false
    }

    /** 预览对取回帧的处置：只有会话确实不可观察时才允许清屏。 */
    enum class PreviewFrameAction { SHOW, KEEP, CLEAR }

    /** 状态行里的画面状态；暂停必须写明原因，不能拿旧帧冒充新画面。 */
    enum class PreviewPause { NONE, BLANK, UNCHANGED, UNREADABLE }

    /** 预览一拍的结论：怎么处置画面 + 状态行怎么标。 */
    data class PreviewOutcome(val action: PreviewFrameAction, val pause: PreviewPause)

    /** 会话不可观察时的预览提示：画面已清空，不能继续拿旧帧展示。 */
    const val PREVIEW_GONE_MESSAGE = "等待副屏画面；锁屏、断连或应用离开副屏时暂停显示"

    /**
     * 预览一拍的决策（纯函数，供预览组件与单测共用）。
     *
     * 真机现象（0.2.9：点「小窗」后悬浮面板出现，但预览长时间全黑）：预览页处于前台时会读到空白帧，
     * 而只看「这一拍能不能显示」的处置方式会一路保持黑底，用户永远等不到画面。契约：
     * - 会话不可观察（已切换、已释放）→ [PreviewFrameAction.CLEAR]：这时保留旧帧才是假画面；
     * - 这一拍读不到帧（动作繁忙、锁屏等）但会话仍在 → [PreviewFrameAction.KEEP] 并标 [PreviewPause.UNREADABLE]：
     *   一次读取失败不等于画面失效，不该把已经显示的画面清黑；
     * - 新帧接近纯色（[frameHasContent] 为 false，由调用方按解码结果算出来）→ [PreviewFrameAction.KEEP]
     *   并标 [PreviewPause.BLANK]：**不把它当有效画面显示**，继续显示最近一张非空白画面；
     * - 新帧与上一张显示的采样相同 → [PreviewFrameAction.SHOW] 并标 [PreviewPause.UNCHANGED]：画面确实没变，
     *   照实显示这一帧并在状态行写明「画面暂无变化」；
     * - 其余 → [PreviewFrameAction.SHOW] + [PreviewPause.NONE]。
     *
     * [blank] 是客户端按解码后的画面算出的「接近纯色」，不是宿主那个只看固定采样线的 `frameBlank`：
     * 后者继续原样进 AI 状态，不再决定预览清不清屏（0.2.9 的误判正是这么让用户看着黑底的）。
     * 这**不是**恢复「静止页面复用最近一帧」：取帧仍按档位持续进行，状态行也会如实写「画面暂无变化」／
     * 「当前帧接近纯色」，AI 侧 `frameBlank`/`frameReused` 的语义完全不变。
     */
    fun previewOutcome(sessionAlive: Boolean, decoded: Boolean, blank: Boolean, changed: Boolean): PreviewOutcome = when {
        !sessionAlive -> PreviewOutcome(PreviewFrameAction.CLEAR, PreviewPause.UNREADABLE)
        !decoded -> PreviewOutcome(PreviewFrameAction.KEEP, PreviewPause.UNREADABLE)
        blank -> PreviewOutcome(PreviewFrameAction.KEEP, PreviewPause.BLANK)
        !changed -> PreviewOutcome(PreviewFrameAction.SHOW, PreviewPause.UNCHANGED)
        else -> PreviewOutcome(PreviewFrameAction.SHOW, PreviewPause.NONE)
    }

    /** 预览状态行的触摸通道段，与状态字段 `touchChannel` 同一套取值。 */
    fun previewTouchLabel(channel: String): String = if (channel == "stream") "触摸直传" else "点击或滑动操作"

    /**
     * 预览状态行的画面段。
     *
     * [waiting] 表示这一拍之前还没有任何可显示画面：这时不能假称「画面暂无变化」，只能按 [pause] 说明在等什么
     * （例如「等待副屏画面（当前帧接近纯色）」）。已经显示过画面时：正常出帧报实测帧率，没有读数就如实说
     * 「帧率待测」；[PreviewPause.UNCHANGED] 报帧率并补「画面暂无变化」；[PreviewPause.BLANK] 报帧率并补
     * 「当前帧接近纯色」；[PreviewPause.UNREADABLE] 只说「未读到新帧」，不报帧率——那一拍并没有读到新画面。
     */
    fun previewFrameLabel(pause: PreviewPause, fps: Double, waiting: Boolean = false): String {
        if (waiting) {
            return when (pause) {
                PreviewPause.UNREADABLE -> "等待副屏画面（未读到新帧）"
                PreviewPause.BLANK -> "等待副屏画面（当前帧接近纯色）"
                else -> "等待副屏画面"
            }
        }
        val rate = if (fps > 0.0) String.format(java.util.Locale.US, "%.1f fps", fps) else "帧率待测"
        return when (pause) {
            PreviewPause.NONE -> rate
            PreviewPause.UNCHANGED -> "$rate · 画面暂无变化"
            PreviewPause.BLANK -> "$rate · 当前帧接近纯色"
            PreviewPause.UNREADABLE -> "未读到新帧"
        }
    }

    /**
     * 预览状态行：触摸通道 + 画面状态 + 档位；档位标题由调用方传入本地化文案。
     * [waiting] 表示还没有任何可显示画面，含义见 [previewFrameLabel]。
     */
    fun previewStatusLine(touchChannel: String, pause: PreviewPause, fps: Double, modeTitle: String, waiting: Boolean = false): String =
        "副屏预览 · ${previewTouchLabel(touchChannel)} · ${previewFrameLabel(pause, fps, waiting)} · 档位 $modeTitle"

    // ------------------------------------------------------------------
    // AI 手势直传：action = "gesture"
    // ------------------------------------------------------------------

    /** 手势路径点上下限：少于两点构不成手势，超过 64 点也没有更细的真机注入粒度。 */
    const val GESTURE_MIN_POINTS = 2
    const val GESTURE_MAX_POINTS = 64

    /** 手势总时长（毫秒）的范围与默认值。 */
    const val GESTURE_MIN_DURATION_MILLIS = 50
    const val GESTURE_MAX_DURATION_MILLIS = 5000
    const val GESTURE_DEFAULT_DURATION_MILLIS = 300

    /** 直传插值步进：约 16 毫秒一个移动事件（60Hz 一帧一个点）。 */
    const val GESTURE_STEP_MILLIS = 16

    /**
     * 触摸/手势注入这次没送进去（[VirtualScreenInjector] 的直传与离散兜底都失败，或被中途取消）。
     *
     * **刻意不复用 `VIRTUAL_SCREEN_UNAVAILABLE`**：按 [errorCode] 上方的约定，那个码的含义是「副屏功能本身
     * 不可用」，而这里副屏会话、显示器、目标应用都还正常，只是这一笔注入没落地——AI 应当据此重试或改用
     * `tap`/`swipe`，而不是向用户宣称设备不支持副屏。
     */
    const val INJECTION_FAILED_CODE = "VIRTUAL_SCREEN_INJECTION_FAILED"

    /** 手势路径上的一个屏幕像素点。 */
    data class GesturePoint(val x: Int, val y: Int)

    /**
     * 手势时长（纯函数）：缺省 [GESTURE_DEFAULT_DURATION_MILLIS]，超出范围按边界夹取，类型不对直接拒绝。
     * 夹取而非报错：AI 常给 8000 这类超范围值，夹到上限仍然是一次可用手势；
     * 但 3.5 或 "300" 这种类型错误必须拒绝——静默取整会让 AI 以为自己给的参数被原样接受。
     */
    fun gestureDuration(p: JSONObject): Int {
        if (p.isNull("durationMs")) return GESTURE_DEFAULT_DURATION_MILLIS
        val value = p.get("durationMs")
        require(value is Int || value is Long) { "手势时长必须为整数毫秒" }
        val millis = (value as Number).toLong()
        return millis.coerceIn(GESTURE_MIN_DURATION_MILLIS.toLong(), GESTURE_MAX_DURATION_MILLIS.toLong()).toInt()
    }

    /**
     * 手势路径校验（纯函数）：2～64 个 `{x,y}`，坐标必须是落在副屏尺寸内的整数。
     * 拒绝缺失/空数组/非数组、数组里的非对象元素、非数字坐标与越界坐标。
     * 越界坐标绝不夹取：把「点到了别的地方」当成成功返回，比直接报错危险得多。
     */
    fun gestureRequest(p: JSONObject, width: Int, height: Int): List<GesturePoint> {
        val points = p.opt("points")
        require(points is JSONArray) { "手势必须提供 points 数组" }
        require(points.length() in GESTURE_MIN_POINTS..GESTURE_MAX_POINTS) {
            "手势点数必须为 $GESTURE_MIN_POINTS～$GESTURE_MAX_POINTS 个"
        }
        return (0 until points.length()).map { index ->
            val point = points.opt(index)
            require(point is JSONObject) { "手势坐标必须是 {x,y} 对象" }
            GesturePoint(gestureCoordinate(point, "x", width), gestureCoordinate(point, "y", height))
        }
    }

    /** 手势坐标必须是真实整数：字符串 "10" 与小数 10.5 一律拒绝，不做截断。 */
    private fun gestureCoordinate(point: JSONObject, key: String, limit: Int): Int {
        val value = point.get(key)
        require(value is Int || value is Long) { "手势坐标必须为整数" }
        val number = (value as Number).toLong()
        require(number in 0 until limit.toLong()) { "坐标超出副屏范围" }
        return number.toInt()
    }

    /**
     * 把折线路径按总时长重采样成逐帧注入序列（纯函数）。
     * 采样数 = 时长 / [GESTURE_STEP_MILLIS]（至少 1 步），沿累计弧长等距取点，首尾点原样保留；
     * 起点终点重合（原地长按）时只返回起点，由注入器按「按下 → 抬起」处理。
     */
    fun gesturePath(points: List<GesturePoint>, durationMillis: Int): List<GesturePoint> {
        if (points.size < GESTURE_MIN_POINTS) return points
        val cumulative = DoubleArray(points.size)
        for (index in 1 until points.size) {
            val dx = (points[index].x - points[index - 1].x).toDouble()
            val dy = (points[index].y - points[index - 1].y).toDouble()
            cumulative[index] = cumulative[index - 1] + kotlin.math.hypot(dx, dy)
        }
        val total = cumulative.last()
        if (total <= 0.0) return listOf(points.first())
        val steps = (durationMillis / GESTURE_STEP_MILLIS).coerceAtLeast(1)
        val samples = ArrayList<GesturePoint>(steps + 1)
        samples.add(points.first())
        var segment = 1
        for (step in 1..steps) {
            val distance = total * step / steps
            while (segment < points.size - 1 && cumulative[segment] < distance) segment++
            val from = points[segment - 1]
            val to = points[segment]
            val span = cumulative[segment] - cumulative[segment - 1]
            val ratio = if (span <= 0.0) 0.0 else (distance - cumulative[segment - 1]) / span
            samples.add(
                GesturePoint(
                    Math.round(from.x + (to.x - from.x) * ratio).toInt(),
                    Math.round(from.y + (to.y - from.y) * ratio).toInt(),
                ),
            )
        }
        // 末点必须是用户给的终点：浮点误差不能让手势停在半路。
        samples[samples.size - 1] = points.last()
        return samples
    }

    // ------------------------------------------------------------------
    // 跳转应用：action = "launch"；跟随目标应用内部跳转：action = "follow"
    // ------------------------------------------------------------------

    /** launch 放行的 URI scheme：只要通用浏览器入口与市场链接，应用私有 scheme 请改用 component/package。 */
    val LAUNCH_URI_SCHEMES = setOf("http", "https", "market")

    /** 链接长度上限。 */
    const val MAX_LAUNCH_URI_CHARS = 2048

    /**
     * 链接里一律拒绝的字符：空白、引号、shell 元字符与控制字符。
     * 命令参数始终以 argv 形式传递、不拼 shell 字符串，这里是第二道防线：
     * 放行这些字符只会让真机上的失败原因变得难以解释。
     */
    private val LAUNCH_URI_FORBIDDEN: Set<Char> = " \t\r\n\"'`;|\$\\<>".toSet() + ('\u0000'..'\u001f').toSet() + '\u007f'

    /** 包名规则与 [component] 的包名段一致。 */
    private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** 组件规则与 [component] 完全一致，供设备输出的容错解析使用。 */
    private val COMPONENT_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+")

    /** 设备输出里「该显示器最前台」的标记；裸 `ResumedActivity` 是跨屏全局摘要，不在其中。 */
    private val RESUMED_MARKER = Regex("\\b(?:mResumedActivity|topResumedActivity)[:=]")

    /** 缩进文本里的组件 token；前后必须是空白或括号，避免把 `//域名/路径` 误读成组件。 */
    private val COMPONENT_TOKEN = Regex("(?:^|[\\s{,])([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+)(?=$|[\\s},])")

    /** 解析不出启动入口（包名没有可启动 Activity / 链接没有可处理的应用）：这是输入或设备状态问题。 */
    const val LAUNCH_UNRESOLVED_CODE = "VIRTUAL_SCREEN_LAUNCH_UNRESOLVED"

    /** `am start` 自身失败（组件不存在、系统拒绝启动）：同样不能报成「副屏不可用」。 */
    const val LAUNCH_FAILED_CODE = "VIRTUAL_SCREEN_LAUNCH_FAILED"

    /** follow 没有可跟随对象时的结果码：这是一次正常结论，作为结果返回，不抛异常。 */
    const val FOLLOW_NONE_CODE = "VIRTUAL_SCREEN_FOLLOW_NONE"

    /** follow 的通用空结果说明。 */
    const val FOLLOW_NONE_MESSAGE = "没有需要跟随的应用：主屏最前台不是可跟随的目标应用"

    /** 跟随判定里永远排除的系统壳包（桌面另由 [homePackage] 判定）。 */
    val FOLLOW_EXCLUDED_PACKAGES = setOf("com.android.systemui", "com.android.shell")

    /**
     * 宿主注入的本应用包名字段：Shizuku 用户服务进程里拿不到宿主包名（本类只在 shell 进程运行，
     * 没有可用的 Context），而 follow 必须排除本应用自己，因此由宿主在动作参数里带上。
     * 缺失时按空串处理，只是少一层自我保护，不影响其余判定。
     */
    const val SELF_PACKAGE_FIELD = "selfPackage"

    /** 解析分支的失败码工厂：`package` 解不出启动入口时用（不是「副屏不可用」，是包本身没有入口）。 */
    fun launchUnresolvedFailure(packageName: String): RuntimeFailure = RuntimeFailure(
        LAUNCH_UNRESOLVED_CODE,
        "未能在设备上解析到 $packageName 的启动入口：应用未安装或没有可启动的 Activity",
    )

    /** 启动分支的失败码工厂：`am start` 被系统拒绝时用，链接情形也能给出目标文案。 */
    fun launchFailedFailure(target: String): RuntimeFailure = RuntimeFailure(
        LAUNCH_FAILED_CODE,
        "在副屏上启动 $target 失败：系统拒绝了这次启动，请确认组件或链接在设备上可用",
    )

    /** 跳转失败的错误码工厂，供执行层按 target 的文案复用同一种语义。 */
    fun launchFailure(packageName: String?, target: String): RuntimeFailure =
        if (packageName == null) launchFailedFailure(target) else launchUnresolvedFailure(packageName)

    /**
     * 链接校验（纯函数）：非空且有长度上限、scheme 在白名单内、不含 [LAUNCH_URI_FORBIDDEN] 里的字符。
     * scheme 大小写不敏感（`HTTPS://` 放行），返回值原样交给 `am start -d`。
     */
    fun launchUri(value: String): String {
        require(value.length in 1..MAX_LAUNCH_URI_CHARS) { "链接长度必须在 1～$MAX_LAUNCH_URI_CHARS 个字符内" }
        require(value.none { it in LAUNCH_URI_FORBIDDEN }) { "链接不能包含空格、引号、分号、竖线、反引号或换行等字符" }
        require(value.substringBefore(':', "").lowercase() in LAUNCH_URI_SCHEMES) {
            "只支持 http/https/market 链接，应用私有 scheme 请改用 component 或 package"
        }
        return value
    }

    /** 只判定不抛异常的包名检查，供跟随候选过滤与输入法列表清洗使用。 */
    fun validPackageName(value: String): Boolean =
        value.isNotEmpty() && value.length <= 160 && PACKAGE_NAME_PATTERN.matches(value)

    /** 包名校验（纯函数）：与 [component] 的包名段同一套规则。 */
    fun launchPackage(value: String): String {
        require(validPackageName(value)) { "包名格式无效" }
        return value
    }

    /** launch 的三种入口：三者互斥，必须且只能给一个。 */
    sealed class LaunchRequest {
        data class Component(val component: String) : LaunchRequest()
        data class Package(val packageName: String) : LaunchRequest()
        data class Uri(val uri: String) : LaunchRequest()
    }

    /**
     * launch 入参校验（纯函数）：`component`、`package`、`uri` 必须且只能给一个。
     * 同时给两个也拒绝：无法判断 AI 想要哪一个，猜一个等于替调用方做决定。
     */
    fun launchRequest(p: JSONObject): LaunchRequest {
        val fields = listOf("component", "package", "uri").filter { !p.isNull(it) }
        require(fields.size == 1) { "launch 必须且只能提供 component、package、uri 中的一个" }
        return when (val field = fields.single()) {
            "component" -> LaunchRequest.Component(component(p.getString(field)))
            "package" -> LaunchRequest.Package(launchPackage(p.getString(field)))
            else -> LaunchRequest.Uri(launchUri(p.getString(field)))
        }
    }

    /**
     * 从设备输出里取组件名（纯函数，供 launch/follow 共用）：只认三种形态——
     * 独占一行的组件、`cmp=<组件>`（Intent 摘要）、`Activity: <组件>`（`am start -W` 的输出）。
     *
     * 2026-10-05 真机实测（HONOR AAP-AN00 / Android 17）：`cmd package resolve-activity --brief <包名>`
     * **先打印一行 key=value 元信息、再打印组件行**，例如
     * `priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true` 与
     * `com.microsoft.emmx/com.microsoft.ruby.Main`。因此含 `=` 的行一律按元信息跳过（它们只可能通过
     * `cmp=`／`Activity:` 标记交出组件），并且**取最后一个候选**：多行候选时以最后一行为准，
     * 避免被前缀摘要（`Starting: Intent { ... }`、`priority=… isDefault=true`）里的第一个 `包名/类名` 骗走。
     * 整段都不像组件（`No activity found` / 只有元信息行）返回 null，由调用方翻译成「没有启动入口」，
     * **不能**当成解析成功。不做「行内任意位置出现 包名/类名 就算」的宽松匹配：那会把 URL 里的 `域名/路径` 当成启动入口。
     */
    fun resolvedComponent(output: String): String? {
        var found: String? = null
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            for (marker in listOf("cmp=", "Activity:")) {
                val index = line.indexOf(marker)
                if (index < 0) continue
                val token = line.substring(index + marker.length).trim().takeWhile { !it.isWhitespace() && it != '}' }
                componentOrNull(token)?.let { found = it }
            }
            if (!trimmed.contains('=')) componentOrNull(trimmed)?.let { found = it }
        }
        return found
    }

    /** 容错版组件校验：只为解析设备输出服务，不改变 [component] 的严格语义。 */
    private fun componentOrNull(value: String): String? =
        if (value.isNotEmpty() && value.length <= 320 && COMPONENT_PATTERN.matches(value)) value else null

    /**
     * 从 `dumpsys activity activities` 里取某个显示器最前台的组件（纯函数）。
     * 分段规则与 [targetResumed] 完全一致：按 `Display #<编号>` 分段、缩进回退即结束该段，
     * 只认 [RESUMED_MARKER]，**裸 `ResumedActivity` 忽略**（它是跨屏全局摘要，缩进也可能落在末尾副屏里）。
     * 2026-10-05 真机实测（HONOR AAP-AN00 / Android 17）：同一次 dump 里 `topResumedActivity=` **每个显示器一行**
     * （主屏是 `com.aliothmoon.maafw.maaend`，另两行分别是 `com.hypergryph.endfield` 与
     * `com.hypergryph.arknights` 的虚拟屏），所以候选必须由 displayId 绑定段来决定，**不能取第一个匹配**。
     * 取不到返回 null：调用方要把它翻译成「没有需要跟随的应用」，不能当成失败。
     * displayId 允许为 0（主屏）：follow 正是要看主屏最前台。
     */
    fun resumedComponent(dump: String, displayId: Int): String? {
        if (displayId < 0) return null
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
            if (indent <= displayIndent) current = -1
            if (current != displayId || !RESUMED_MARKER.containsMatchIn(line)) continue
            COMPONENT_TOKEN.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * 从 `dumpsys activity` 里取桌面（Home）包名（纯函数）。
     * 系统用 `mHomeProcess: ProcessRecord{... 12345:com.android.launcher3/u0a123}` 标注桌面进程；
     * 跟随判定必须排除桌面：目标应用跳出去又立刻回来时，主屏最前台会瞬间变成桌面，
     * 把桌面拉到副屏等于把用户的目标应用直接盖掉。
     *
     * 2026-10-05 真机实测（HONOR AAP-AN00 / Android 17）：这一行**不在** `dumpsys activity activities` 里
     * （零命中），只在**不带 `activities`** 的 `dumpsys activity` 里；调用方因此要按
     * 「当前 dump → `dumpsys activity` → HOME 意图解析」的顺序取桌面，本函数对两种输入都适用。
     */
    fun homePackage(dump: String): String? {
        val line = dump.lineSequence().firstOrNull { it.contains("mHomeProcess") } ?: return null
        return Regex("[:\\s]([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)/").find(line)?.groupValues?.get(1)
    }

    /**
     * 跟随候选判定（纯函数）：只跟随真实应用包，并排除本应用、输入法、桌面与系统壳包。
     * 本应用必须排除：副屏预览界面就跑在本应用里，把自己拉到副屏等于把会话自己盖掉。
     */
    fun followable(packageName: String, selfPackage: String, inputMethods: Set<String>, homePackage: String?): Boolean =
        validPackageName(packageName) && packageName != selfPackage && packageName != homePackage &&
            packageName !in inputMethods && packageName !in FOLLOW_EXCLUDED_PACKAGES

    /** follow 未跟随时的中文原因（纯函数）：说清是哪一类候选被排除，避免 AI 反复重试同一个空结果。 */
    fun followNoneReason(packageName: String?, selfPackage: String, inputMethods: Set<String>, homePackage: String?): String = when {
        packageName.isNullOrEmpty() -> "$FOLLOW_NONE_MESSAGE（主屏没有解析到最前台应用）"
        !validPackageName(packageName) -> "$FOLLOW_NONE_MESSAGE（主屏包名格式无法识别）"
        packageName == selfPackage -> "$FOLLOW_NONE_MESSAGE（主屏最前台是本应用自己）"
        packageName == homePackage -> "$FOLLOW_NONE_MESSAGE（主屏最前台是桌面）"
        packageName in inputMethods -> "$FOLLOW_NONE_MESSAGE（主屏最前台是输入法）"
        packageName in FOLLOW_EXCLUDED_PACKAGES -> "$FOLLOW_NONE_MESSAGE（主屏最前台是系统界面）"
        else -> FOLLOW_NONE_MESSAGE
    }
}
