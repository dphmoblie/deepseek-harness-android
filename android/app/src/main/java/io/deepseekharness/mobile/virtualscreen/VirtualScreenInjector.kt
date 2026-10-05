package io.deepseekharness.mobile.virtualscreen

import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import org.json.JSONObject

/**
 * 副屏触摸注入。
 *
 * 优先走进程内注入：`android.hardware.input.InputManager.injectInputEvent` 与
 * `/system/bin/input -d <显示编号>` 用的是同一套系统隐藏接口，只是省掉了每个事件起一个
 * Java 进程的开销（实测 `input` 单次约 140 毫秒，进程内注入约 1 毫秒）。因此只有进程内通道
 * 能承载连续手势（按下 / 拖动 / 抬起逐事件直传），回落通道质量不足以做逐点直传。
 *
 * 进程不可用时（隐藏接口被 ROM 拦掉等）[available] 为 false，[Gesture] 会把整段手势累积起来，
 * 在抬起时合成一次 `tap` 或 `swipe` 交给系统 `input` 命令执行 —— 与改动前的行为一致，不会
 * 出现「按下走了进程内、移动却落到命令通道」这种半途换通道的撕裂状态。
 *
 * 本类只在 Shizuku 用户服务进程里运行；显示编号必须大于 0（拒绝主屏），坐标必须落在副屏范围内。
 */
object VirtualScreenInjector {
    /** 触摸阶段白名单；调用端与执行层共用同一份。 */
    val PHASES = setOf("down", "move", "up", "cancel")

    /** 一次手势内允许累积的移动采样点上限；超出后只保留首尾，回落通道不会无限增长。 */
    const val MAX_POINTS = 64

    /** 单次手势的最长时长；超过后拒绝继续，避免会话里留下永不结束的按下状态。 */
    const val MAX_GESTURE_MS = 30_000L

    /** 判定为滑动的最小位移（与预览界面的判定一致，都是像素）。 */
    const val SWIPE_THRESHOLD = 12

    /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`：不等注入完成，避免阻塞采集线程。 */
    private const val MODE_ASYNC = 0

    private class Access(val manager: Any, val inject: java.lang.reflect.Method, val setDisplayId: java.lang.reflect.Method)

    /**
     * 进程内注入所需的隐藏接口。任何一步失败都整体视为不可用，绝不在注入过程中反复试错：
     * 反射失败本身有系统进程日志代价，逐事件重试会放大成噪声。
     */
    private val access: Access? by lazy {
        runCatching {
            val type = Class.forName("android.hardware.input.InputManager")
            val manager = type.getMethod("getInstance").invoke(null) ?: return@runCatching null
            val inject = type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            val setDisplayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            Access(manager, inject, setDisplayId)
        }.getOrNull()
    }

    /** 进程内注入是否可用；不可用时触摸会退化成「抬起时合成 tap/swipe」。 */
    fun available(): Boolean = access != null

    /** 触摸通道名称，用于会话状态与诊断：`stream` 表示逐事件直传，`discrete` 表示抬起时合成。 */
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
     * [fallback] 接收 `/system/bin/input -d <显示编号>` 之后的参数（例如 `["tap", "10", "20"]`），
     * 由调用方补上可执行文件与 `-d`；只有离散通道会用到它。
     */
    class Gesture(
        // 以取值函数传入：创建会话时副屏显示器还没建好，只能在注入那一刻再取编号。
        // 这样既守住「绝不注入主屏」，也不会因为构造顺序把「显示器尚未创建」误判成参数错误。
        private val displayId: () -> Int,
        private val width: Int,
        private val height: Int,
        private val fallback: (List<String>) -> Unit,
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
         * 处理一个触摸阶段，返回描述本次实际动作的 JSON（`channel`、`phase`、`streamed`、坐标与
         * 采样点数），交给调用方拼进动作结果；异常只可能是参数问题，注入失败不抛异常。
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
                    if (!stream || !streamed) synthesize()
                    result.put("streamed", stream && streamed).put("moveIndex", moves)
                    reset()
                }
            }
            return result
        }

        /**
         * 整段手势直传（AI 的 `gesture` 动作用）：按下 → 按约 16 毫秒步进插值移动 → 抬起。
         *
         * 与 [handle] 的区别：这里一次拿到完整路径与总时长，插值与节拍都由本方法负责，因此要求
         * 「直传通道可用、且当前没有半截手势」。注入失败不抛异常（与 [handle] 同一约定）：失败时
         * 补发 ACTION_CANCEL 结束整段手势，并在返回里写 `streamed=false`、`aborted=true`，由调用方
         * 决定怎么报错——把按下事件留在系统里会让副屏一直处于「被按住」状态，绝不能静默报成功。
         * 直传通道不可用时退回离散通道，用一次 `input swipe`（首尾点 + 时长）近似整条路径，
         * 返回的 `approximated=true` 与夹取后的 `durationMs` 如实说明这是一次近似而不是逐点直传。
         */
        fun stroke(path: List<VirtualScreenPolicy.GesturePoint>, durationMillis: Int): JSONObject {
            require(displayId() > 0) { "副屏显示器尚未就绪" }
            require(!active) { "上一次副屏触摸手势尚未结束" }
            require(path.size >= 2) { "副屏手势至少需要两个坐标点" }
            val samples = VirtualScreenPolicy.gesturePath(path, durationMillis)
            val first = samples.first()
            val last = samples.last()
            val downTime = SystemClock.uptimeMillis()
            if (!available() || !send(MotionEvent.ACTION_DOWN, first.x, first.y, downTime)) {
                // 离散通道：整条路径只能用首尾点近似；时长按 input 的既有上限夹取，不假装还是原来的时长。
                val duration = durationMillis.coerceIn(100, 2000)
                fallback(
                    listOf(
                        "swipe", first.x.toString(), first.y.toString(),
                        last.x.toString(), last.y.toString(), duration.toString(),
                    ),
                )
                return strokeResult("discrete", streamed = false, aborted = false, approximated = true, samples = 0, moves = 0, durationMillis = duration)
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
            )
        }

        private fun strokeResult(
            channel: String,
            streamed: Boolean,
            aborted: Boolean,
            approximated: Boolean,
            samples: Int,
            moves: Int,
            durationMillis: Int,
        ): JSONObject = JSONObject()
            .put("channel", channel).put("streamed", streamed).put("aborted", aborted)
            .put("approximated", approximated).put("samples", samples).put("moves", moves)
            .put("durationMs", durationMillis)

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
         * 离散通道的整段手势：位移不超过 [SWIPE_THRESHOLD] 视为点击，否则按起止点与真实时长滑动。
         * 时长下限 100 毫秒、上限 2000 毫秒，与策略层对单次动作的限制保持一致。
         */
        private fun synthesize() {
            if (points.isEmpty()) return
            val duration = (SystemClock.uptimeMillis() - downTime).coerceIn(100L, 2000L).toInt()
            val distance = kotlin.math.hypot((lastX - startX).toDouble(), (lastY - startY).toDouble())
            if (distance <= SWIPE_THRESHOLD) fallback(listOf("tap", startX.toString(), startY.toString()))
            else fallback(listOf("swipe", startX.toString(), startY.toString(), lastX.toString(), lastY.toString(), duration.toString()))
        }

        /** 进程内注入一个事件；任何失败都返回 false，由调用方决定是回落还是提前结束整段手势。 */
        private fun send(action: Int, x: Int, y: Int, down: Long): Boolean {
            val a = access ?: return false
            // 显示器已释放（编号回到 -1）时不再注入，避免事件落到主屏。
            val id = displayId()
            if (id <= 0) return false
            val event = runCatching { MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0) }
                .getOrNull() ?: return false
            return try {
                a.setDisplayId.invoke(event, id)
                a.inject.invoke(a.manager, event, MODE_ASYNC) as? Boolean ?: false
            } catch (_: Exception) {
                false
            } finally {
                event.recycle()
            }
        }
    }
}
