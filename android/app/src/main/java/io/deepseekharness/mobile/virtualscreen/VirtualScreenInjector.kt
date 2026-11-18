package io.deepseekharness.mobile.virtualscreen

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject

/**
 * 副屏触摸注入。
 *
 * 优先走进程内注入：`android.hardware.input.InputManager.injectInputEvent` 与
 * `/system/bin/input -d <显示编号>` 用的是同一套系统隐藏接口，只是省掉了每个事件起一个
 * Java 进程的开销（实测 `input` 单次约 140 毫秒，进程内注入约 1 毫秒）。因此只有进程内通道
 * 能承载逐 16 毫秒采样的连续手势（按下 / 拖动 / 抬起逐事件直传）。
 *
 * 进程不可用时（隐藏接口被 ROM 拦掉等）[available] 为 false，[Gesture] 走离散通道：仍然逐事件注入
 * `input -d <显示编号> motionevent <动作> <x> <y>`（按下 → 若干移动 → 抬起），只是每个事件一次进程
 * 启动，所以先按 [VirtualScreenMotionPlan] 合并采样点、再在注入过程中丢弃过期的移动事件，把一次
 * 手势的事件数封顶；每个事件的命令结果都要检查，只要有一个没落地就如实报失败。改前这里是「整段
 * 合成一次 `input swipe`」——命令返回 0 不等于事件落到了副屏上，于是报成功但画面不动。
 * 通道在整段手势开始时一次性选定，不会出现「按下走了进程内、移动却落到命令通道」的撕裂状态。
 *
 * 本类只在 Shizuku 用户服务进程里运行；显示编号必须大于 0（拒绝主屏），坐标必须落在副屏范围内。
 */
object VirtualScreenInjector {
    /** 触摸阶段白名单；调用端与执行层共用同一份。 */
    val PHASES = setOf("down", "move", "up", "cancel")

    /** 一次手势内允许累积的移动采样点上限；超出后只保留最后一个（合并与丢弃由事件编排负责）。 */
    const val MAX_POINTS = 64

    /** 单次手势的最长时长；超过后拒绝继续，避免会话里留下永不结束的按下状态。 */
    const val MAX_GESTURE_MS = 30_000L

    /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`：不等注入完成，避免阻塞采集线程。 */
    private const val MODE_ASYNC = 0

    private class Access(val manager: Any, val inject: java.lang.reflect.Method, val setDisplayId: java.lang.reflect.Method)

    /**
     * 进程内注入所需的隐藏接口。Android 14（API 34）起 `InputManager.getInstance()` 迁到了
     * `InputManagerGlobal.getInstance()`，这里按新到旧依次尝试；任何一步失败都整体视为不可用，
     * 绝不在注入过程中反复试错。
     */
    private val access: Access? by lazy { resolveAccess() }

    /** 系统明确抛出 SecurityException 拒绝进程内注入时切回离散通道，避免每次再试。 */
    @Volatile private var streamBlocked = false

    private fun resolveAccess(): Access? {
        val setDisplayId = runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return null
        for (className in listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
            val resolved = runCatching {
                val type = Class.forName(className)
                val manager = type.getMethod("getInstance").invoke(null) ?: return@runCatching null
                val inject = type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                Access(manager, inject, setDisplayId)
            }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }

    /** 进程内注入是否可用；不可用时触摸走离散通道，逐事件调用 `input` 命令。 */
    fun available(): Boolean = access != null && !streamBlocked

    /** 触摸通道名称，用于会话状态与诊断：`stream` 为进程内逐事件直传，`discrete` 为逐事件走 `input` 命令。 */
    fun channel(): String = if (available()) "stream" else "discrete"

    /** 校验一次触摸请求。坐标与显示编号非法时抛 [IllegalArgumentException]，由上层映射成参数错误。 */
    fun request(p: JSONObject, width: Int, height: Int): Touch {
        val phase = p.getString("phase")
        require(phase in PHASES) { "副屏触摸阶段不在允许列表" }
        val x = integer(p, "x")
        val y = integer(p, "y")
        require(x in 0 until width && y in 0 until height) { "坐标超出副屏范围" }
        return Touch(phase, x, y)
    }

    private fun integer(p: JSONObject, key: String): Int {
        val value = p.get(key)
        require(value is Int || value is Long) { "副屏参数必须为整数" }
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return number.toInt()
    }

    data class Touch(val phase: String, val x: Int, val y: Int)

    /**
     * 单次手势的注入状态。逐个副屏会话各持一个，只能从创建它的服务线程调用（与系统命令通道同一个线程）。
     *
     * [discrete] 接收 `/system/bin/input -d <显示编号>` 之后的参数（例如
     * `["motionevent", "MOVE", "10", "20"]`），由调用方补上可执行文件与 `-d`，并返回该命令的输出：
     * 离散通道每个事件一次进程启动，命令退出码为 0 也不代表事件落到了副屏上，要拿输出一起判失败。
     */
    class Gesture(
        // 以取值函数传入：创建会话时副屏显示器还没建好，只能在注入那一刻再取编号。
        // 这样既守住「绝不注入主屏」，也不会因为构造顺序把「显示器尚未创建」误判成参数错误。
        private val displayId: () -> Int,
        private val width: Int,
        private val height: Int,
        private val discrete: (List<String>) -> String,
    ) {
        private var active = false
        private var stream = false
        private var downTime = 0L
        private var startedAt = 0L
        private var startX = 0
        private var startY = 0
        private var lastX = 0
        private var lastY = 0
        private var moves = 0
        private val points = ArrayList<IntArray>()

        init {
            require(width > 0 && height > 0) { "副屏尺寸无效" }
        }

        /**
         * 处理一个触摸阶段，返回描述本次实际动作的 JSON（`channel`、`phase`、`streamed`、坐标、采样点数，
         * 以及离散通道的 `events`/`merged`/`dropped`/`millis` 实测读数），交给调用方拼进动作结果；
         * 异常只可能是参数问题，注入本身不抛异常，而是在返回里写非空的 `failure`（配 `failedAt`）
         * 让调用方报错——**绝不在事件没落地时报成功**。
         */
        fun handle(touch: Touch): JSONObject {
            require(displayId() > 0) { "副屏显示器尚未就绪" }
            val now = SystemClock.uptimeMillis()
            if (!active) {
                if (touch.phase != "down" && touch.phase != "move") require(false) { "副屏触摸必须先按下" }
                active = true
                startedAt = now
                downTime = now
                startX = touch.x; startY = touch.y
                moves = 0
                stream = available() && send(MotionEvent.ACTION_DOWN, touch.x, touch.y, downTime)
                if (!stream) points.add(intArrayOf(touch.x, touch.y))
            } else {
                require(now - startedAt <= MAX_GESTURE_MS) { "副屏触摸手势超时，请重新按下" }
            }
            lastX = touch.x; lastY = touch.y
            val result = JSONObject()
                .put("channel", if (stream) "stream" else "discrete")
                .put("phase", touch.phase)
                .put("x", touch.x).put("y", touch.y)
            when (touch.phase) {
                "down" -> result.put("streamed", stream)
                "move" -> {
                    moves++
                    result.put("streamed", stream).put("moveIndex", moves)
                    if (stream) send(MotionEvent.ACTION_MOVE, touch.x, touch.y, downTime)
                    else if (points.size < MAX_POINTS) points.add(intArrayOf(touch.x, touch.y))
                    else points[points.size - 1] = intArrayOf(touch.x, touch.y)
                }
                "up", "cancel" -> {
                    val action = if (touch.phase == "up") MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL
                    val streamed = stream && send(action, touch.x, touch.y, downTime)
                    if (!stream || !streamed) {
                        // 离散通道：把累积的点逐事件注入。改前这里是抬起时合成一次 input tap/swipe，
                        // 命令返回 0 就当成功，于是出现「报成功但画面不动」。
                        val discreteResult = synthesize()
                        if (discreteResult != null) {
                            for (key in discreteResult.keys()) result.put(key, discreteResult.get(key))
                        } else {
                            // 直传通道已经把按下事件送进去了，抬起却没被接受：这同样是一次没落地的注入，
                            // 不能只留一个 streamed=false。补一个取消事件尽力收尾，并如实报失败。
                            send(MotionEvent.ACTION_CANCEL, lastX, lastY, downTime)
                            // 直传通道的移动事件不逐个判落地，`events` 记的是已交给系统的事件数
                            // （按下 + 已发出的移动），异常点只有「抬起没被接受」这一个。
                            result.put("failureKind", "Rejected")
                                .put("events", 1 + moves).put("millis", SystemClock.uptimeMillis() - downTime)
                                .put("failedAt", touch.phase).put(
                                    "failure",
                                    "直传通道未接受抬起事件（副屏可能仍处于被按下状态，已补发取消）",
                                )
                        }
                    }
                    result.put("streamed", stream && streamed).put("moveIndex", moves)
                    reset()
                }
            }
            return result
        }

        /**
         * 整段手势（AI 的 `gesture` 动作用）：按下 → 移动 → 抬起。
         *
         * 进程内通道可用时按约 16 毫秒步进插值直传；不可用（或按下就没被接受）时走离散通道
         * [discreteSequence]，逐事件调用 `input motionevent`，按 [VirtualScreenMotionPlan] 合并采样点、
         * 丢弃过期移动。两条通道遵守同一约定：注入失败不抛异常，而是在返回里写非空的 `failure`
         * （配 `failedAt`）让调用方报错；失败时补发 CANCEL，既不把按下事件留在系统里，也绝不把
         * 没落地的事件报成成功（改前的离散分支只发一次 `input swipe` 就返回成功，那正是问题所在）。
         */
        fun stroke(path: List<VirtualScreenPolicy.GesturePoint>, durationMillis: Int): JSONObject =
            runStroke(path, durationMillis) { samples, first, last ->
                // 离散通道：逐事件注入（按下 → 合并后的移动 → 抬起），改前只发一次 input swipe 就当成功。
                // 时长按 input 的既有上限夹取，不假装还是原来的时长；首尾点写进返回，便于核对走了哪一段。
                val duration = durationMillis.coerceIn(100, 2000)
                discreteSequence(samples, duration)
                    .put("startX", first.x).put("startY", first.y)
                    .put("endX", last.x).put("endY", last.y)
            }

        /**
         * 单次点击：优先走进程内直传（DOWN → 短按 25ms → UP），不可用时退回 `input tap`。
         * 直传只在按下与抬起之间停留 25ms，比每次起一次 Java 进程（~140ms～1s）快一个量级。
         */
        fun tap(x: Int, y: Int): JSONObject =
            holdTap(25L, x, y, "tap", listOf("tap", x.toString(), y.toString()))

        /** 长按：优先走进程内直传（DOWN → 等待 durationMs → UP），不可用时退回 `input swipe`。 */
        fun longPress(x: Int, y: Int, durationMillis: Int): JSONObject = holdTap(
            durationMillis.toLong(),
            x,
            y,
            "long_press",
            listOf("swipe", x.toString(), y.toString(), x.toString(), y.toString(), durationMillis.toString()),
        )

        /** 滑动：优先走进程内插值直传；不可用时退回单次 `input swipe`（避免慢设备上逐点起十几个进程）。 */
        fun swipe(x: Int, y: Int, endX: Int, endY: Int, durationMillis: Int): JSONObject {
            val result = runStroke(
                listOf(VirtualScreenPolicy.GesturePoint(x, y), VirtualScreenPolicy.GesturePoint(endX, endY)),
                durationMillis,
            ) { _, _, _ ->
                discreteCommand(
                    "swipe",
                    listOf("swipe", x.toString(), y.toString(), endX.toString(), endY.toString(), durationMillis.toString()),
                    durationMillis,
                )
            }
            if (result.optBoolean("aborted") && result.optString("failure").isEmpty()) {
                result.put("failureKind", "Rejected")
                    .put("failedAt", "swipe")
                    .put("failure", "直传通道滑动中途被取消（已补发取消）")
            }
            return result
        }

        /** 按键：优先进程内注入 KeyEvent，失败时退回 `input keyevent`。 */
        fun keyEvent(keyCode: Int): JSONObject {
            require(displayId() > 0) { "副屏显示器尚未就绪" }
            val startedAt = SystemClock.uptimeMillis()
            if (available() && sendKey(KeyEvent.ACTION_DOWN, keyCode, startedAt)) {
                val upOk = sendKey(KeyEvent.ACTION_UP, keyCode, startedAt)
                val millis = SystemClock.uptimeMillis() - startedAt
                if (!upOk) {
                    sendKey(KeyEvent.ACTION_UP, keyCode, startedAt, KeyEvent.FLAG_CANCELED)
                    return JSONObject()
                        .put("channel", "stream").put("streamed", false)
                        .put("events", 1).put("merged", 0).put("dropped", 0)
                        .put("millis", millis)
                        .put("failureKind", "Rejected").put("failedAt", "up")
                        .put("failure", "直传通道未接受按键抬起事件（已补发取消）")
                }
                return JSONObject()
                    .put("channel", "stream").put("streamed", true)
                    .put("events", 2).put("merged", 0).put("dropped", 0)
                    .put("millis", millis)
                    .put("failedAt", "").put("failure", "")
            }
            return discreteCommand("keyevent", listOf("keyevent", keyCode.toString()))
        }

        private fun runStroke(
            path: List<VirtualScreenPolicy.GesturePoint>,
            durationMillis: Int,
            fallback: (
                samples: List<VirtualScreenPolicy.GesturePoint>,
                first: VirtualScreenPolicy.GesturePoint,
                last: VirtualScreenPolicy.GesturePoint,
            ) -> JSONObject,
        ): JSONObject {
            require(displayId() > 0) { "副屏显示器尚未就绪" }
            require(!active) { "上一次副屏触摸手势尚未结束" }
            require(path.size >= 2) { "副屏手势至少需要两个坐标点" }
            val samples = VirtualScreenPolicy.gesturePath(path, durationMillis)
            val first = samples.first()
            val last = samples.last()
            val downTime = SystemClock.uptimeMillis()
            // 用于诊断的注入耗时：从按下那一刻起算，两条通道都填进 `millis`。
            val startedAt = downTime
            if (!available() || !send(MotionEvent.ACTION_DOWN, first.x, first.y, downTime)) {
                return fallback(samples, first, last)
            }
            // 已经按下：从这里开始无论成功还是失败，都必须发出抬起或取消。
            active = true
            stream = true
            this.downTime = downTime
            startX = first.x; startY = first.y
            lastX = first.x; lastY = first.y
            var injected = 0
            var aborted = false
            try {
                for (index in 1 until samples.size) {
                    SystemClock.sleep(VirtualScreenPolicy.GESTURE_STEP_MILLIS.toLong())
                    val sample = samples[index]
                    if (!send(MotionEvent.ACTION_MOVE, sample.x, sample.y, downTime)) {
                        aborted = true
                        break
                    }
                    lastX = sample.x; lastY = sample.y; injected++
                }
                if (!aborted && !send(MotionEvent.ACTION_UP, lastX, lastY, downTime)) aborted = true
                // 中途失败要么已经补过（移动到一半）要么现在补：抬起/取消必须成对出现。
                if (aborted) send(MotionEvent.ACTION_CANCEL, lastX, lastY, downTime)
            } finally {
                reset()
            }
            return strokeResult(
                channel = "stream",
                streamed = !aborted,
                aborted = aborted,
                approximated = false,
                samples = samples.size,
                moves = injected,
                durationMillis = durationMillis,
                // 落地事件数：按下 + 被接受的移动 + 被接受的抬起（中途失败时补发的取消是收尾，不计入）。
                events = 1 + injected + if (aborted) 0 else 1,
                millis = SystemClock.uptimeMillis() - startedAt,
            )
        }

        private fun holdTap(
            holdMillis: Long,
            x: Int,
            y: Int,
            actionLabel: String,
            discreteArgs: List<String>,
        ): JSONObject {
            require(displayId() > 0) { "副屏显示器尚未就绪" }
            require(!active) { "上一次副屏触摸手势尚未结束" }
            val startedAt = SystemClock.uptimeMillis()
            if (available() && send(MotionEvent.ACTION_DOWN, x, y, startedAt)) {
                if (holdMillis > 0L) SystemClock.sleep(holdMillis)
                val upOk = send(MotionEvent.ACTION_UP, x, y, startedAt)
                val millis = SystemClock.uptimeMillis() - startedAt
                if (!upOk) {
                    send(MotionEvent.ACTION_CANCEL, x, y, startedAt)
                    return JSONObject()
                        .put("channel", "stream").put("streamed", false)
                        .put("events", 1).put("merged", 0).put("dropped", 0)
                        .put("millis", millis)
                        .put("failureKind", "Rejected").put("failedAt", "up")
                        .put("failure", "直传通道未接受抬起事件（副屏可能仍处于被按下状态，已补发取消）")
                }
                return JSONObject()
                    .put("channel", "stream").put("streamed", true)
                    .put("events", 2).put("merged", 0).put("dropped", 0)
                    .put("millis", millis)
                    .put("failedAt", "").put("failure", "")
            }
            return discreteCommand(actionLabel, discreteArgs)
        }

        private fun discreteCommand(actionLabel: String, args: List<String>, durationMillis: Int = 0): JSONObject {
            val startedAt = SystemClock.uptimeMillis()
            var exception = ""
            val rejected = runCatching {
                if (displayId() <= 0) true else VirtualScreenMotionPlan.rejected(discrete(args))
            }.getOrElse { error ->
                exception = error.javaClass.simpleName
                true
            }
            val millis = SystemClock.uptimeMillis() - startedAt
            val result = JSONObject()
                .put("channel", "discrete").put("streamed", false)
                .put("events", if (rejected) 0 else 1)
                .put("merged", 0).put("dropped", 0)
                .put("millis", millis)
            if (durationMillis > 0) result.put("durationMs", durationMillis)
            if (!rejected) return result.put("failedAt", "").put("failure", "")
            val kind = if (exception.isEmpty()) "Rejected" else exception
            val detail = if (exception.isEmpty()) "被系统拒绝" else "命令失败（$exception）"
            return result
                .put("failureKind", kind)
                .put("failedAt", actionLabel)
                .put("failure", "离散通道 $actionLabel 注入未完成：$detail")
        }

        private fun strokeResult(
            channel: String,
            streamed: Boolean,
            aborted: Boolean,
            approximated: Boolean,
            samples: Int,
            moves: Int,
            durationMillis: Int,
            events: Int,
            millis: Long,
        ): JSONObject = JSONObject()
            .put("channel", channel).put("streamed", streamed).put("aborted", aborted)
            .put("approximated", approximated).put("samples", samples).put("moves", moves)
            .put("durationMs", durationMillis)
            // events/millis 与离散通道同名字段对齐：状态里的 injectionEvents/injectionMillis 两条通道都填得上。
            .put("events", events).put("millis", millis)

        /** 会话切换或服务停止时必须调用：进程内通道要补一个取消事件，离散通道要丢掉半截手势。 */
        fun abort() {
            if (!active) return
            if (stream) send(MotionEvent.ACTION_CANCEL, lastX, lastY, downTime)
            reset()
        }

        private fun reset() {
            active = false; stream = false; moves = 0; points.clear()
        }

        /**
         * 离散通道的收尾注入：把 `touch` 累积下来的点逐事件注入（按下 → 合并后的移动 → 抬起）。
         *
         * 时间轴用 [VirtualScreenMotionPlan.TOUCH_TIMELINE_MILLIS] 而不是真实拖动时长：手指已经抬起了，
         * 再按原速把两秒的轨迹慢放一遍只会让人更觉得卡；真实时长仍写进 `durationMs` 供诊断。
         * 没有累积到点（直传通道已经在按下时把事件送进去了）时返回 null，调用方不必再判失败。
         */
        private fun synthesize(): JSONObject? {
            if (points.isEmpty()) return null
            val path = points.map { VirtualScreenPolicy.GesturePoint(it[0], it[1]) }
            val actual = (SystemClock.uptimeMillis() - downTime).coerceIn(100L, 2000L).toInt()
            val timeline = minOf(actual, VirtualScreenMotionPlan.TOUCH_TIMELINE_MILLIS)
            return discreteSequence(path, timeline, actual)
                .put("startX", startX).put("startY", startY)
                .put("endX", lastX).put("endY", lastY)
        }

        /**
         * 离散通道逐事件注入一段完整手势：`input -d <显示编号> motionevent <动作> <x> <y>`。
         *
         * 每个事件一次进程启动（约 140 毫秒），所以先用 [VirtualScreenMotionPlan.plan] 合并采样点，
         * 再用 [VirtualScreenMotionPlan.Cursor] 丢掉落后计划太久的移动事件，把整段手势封顶在一个有界的
         * 时间里。返回里的 `events`/`moves`/`merged`/`dropped`/`millis` 是这次注入的实测读数；只要有
         * 一个事件没落地就写非空的 `failedAt` 与 `failure`（只记动作名、计数与异常类名，不写命令输出、
         * 不写路径），由调用方翻译成错误码——**绝不在没落地时报成功**。
         */
        private fun discreteSequence(
            path: List<VirtualScreenPolicy.GesturePoint>,
            timelineMillis: Int,
            durationMillis: Int = timelineMillis,
        ): JSONObject {
            val plan = VirtualScreenMotionPlan.plan(path, timelineMillis)
            val cursor = VirtualScreenMotionPlan.Cursor(plan.steps)
            val startedAt = SystemClock.uptimeMillis()
            var injected = 0
            var moves = 0
            var failedAt = ""
            var exception = ""
            var refused = false
            while (true) {
                val step = cursor.next(SystemClock.uptimeMillis() - startedAt) ?: break
                val notLanded = runCatching { sendDiscrete(step) }.getOrElse { error ->
                    exception = error.javaClass.simpleName
                    true
                }
                if (notLanded) {
                    failedAt = step.action
                    refused = exception.isEmpty()
                    break
                }
                injected++
                if (step.action == VirtualScreenMotionPlan.MOVE) moves++
            }
            val millis = SystemClock.uptimeMillis() - startedAt
            val result = JSONObject()
                .put("channel", "discrete").put("streamed", false).put("approximated", true)
                .put("samples", plan.samples).put("merged", plan.merged).put("dropped", cursor.dropped)
                .put("events", injected).put("moves", moves).put("millis", millis)
                .put("durationMs", durationMillis)
            if (failedAt.isEmpty()) return result.put("failedAt", "").put("failure", "")
            // 已经落过事件就必须收尾：补一次 CANCEL 结束系统里的按下状态（补发失败不改变失败结论）。
            if (injected > 0) {
                val tail = path.last()
                runCatching {
                    sendDiscrete(VirtualScreenMotionPlan.Step(VirtualScreenMotionPlan.CANCEL, tail.x, tail.y, 0L))
                }
            }
            val reason = if (refused) "被系统拒绝" else "命令失败（$exception）"
            return result
                // failureKind 是给状态诊断用的机器可读标签（如 `discrete:move:Rejected`），
                // 只含枚举值或异常类名，不含命令输出、路径。
                .put("failureKind", if (refused) "Rejected" else exception)
                .put("failedAt", failedAt).put(
                    "failure",
                    "离散通道逐事件注入未完成：${VirtualScreenMotionPlan.label(failedAt)}事件$reason" +
                        "（已落地 $injected 个事件，丢弃 ${cursor.dropped} 个过期移动，合并 ${plan.merged} 个采样点）",
                )
        }

        /**
         * 注入一个离散事件；返回 true 表示这次事件没落地（命令执行失败，或输出里出现系统拒绝的字样）。
         * `input` 被拒绝时会把原因打在输出里，但仍可能以 0 退出，所以不能只看退出码。
         */
        private fun sendDiscrete(step: VirtualScreenMotionPlan.Step): Boolean {
            // 显示器已释放（编号回到 -1）时不再注入，避免事件落到主屏。
            if (displayId() <= 0) return true
            val output = discrete(
                listOf(
                    "motionevent", VirtualScreenMotionPlan.argument(step.action),
                    step.x.toString(), step.y.toString(),
                ),
            )
            return VirtualScreenMotionPlan.rejected(output)
        }

        /** 进程内注入一个触摸事件；任何失败都返回 false，由调用方决定是回落还是提前结束整段手势。 */
        private fun send(action: Int, x: Int, y: Int, down: Long): Boolean {
            val now = if (action == MotionEvent.ACTION_DOWN) down else SystemClock.uptimeMillis()
            val pointerProps = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
            val pointerCoords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x.toFloat()
                    this.y = y.toFloat()
                    pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
                    size = 1f
                },
            )
            val event = runCatching {
                MotionEvent.obtain(
                    down, now, action, 1, pointerProps, pointerCoords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
            }.getOrNull() ?: return false
            return try {
                injectEvent(event)
            } finally {
                event.recycle()
            }
        }

        private fun sendKey(action: Int, keyCode: Int, down: Long, extraFlags: Int = 0): Boolean {
            val now = if (action == KeyEvent.ACTION_DOWN) down else SystemClock.uptimeMillis()
            val event = KeyEvent(
                down, now, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or extraFlags,
                InputDevice.SOURCE_KEYBOARD,
            )
            return injectEvent(event)
        }

        private fun injectEvent(event: InputEvent): Boolean {
            val a = access ?: return false
            // 显示器已释放（编号回到 -1）时不再注入，避免事件落到主屏。
            val id = displayId()
            if (id <= 0) return false
            return try {
                a.setDisplayId.invoke(event, id)
                a.inject.invoke(a.manager, event, MODE_ASYNC) as? Boolean ?: false
            } catch (error: java.lang.reflect.InvocationTargetException) {
                if (error.cause is SecurityException) streamBlocked = true
                false
            } catch (_: SecurityException) {
                streamBlocked = true
                false
            } catch (_: Exception) {
                false
            }
        }
    }
}
