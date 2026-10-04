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
        private val displayId: Int,
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
            require(displayId > 0) { "不能向主屏发送副屏输入" }
            require(width > 0 && height > 0) { "副屏尺寸无效" }
        }

        /**
         * 处理一个触摸阶段，返回描述本次实际动作的 JSON（`channel`、`phase`、`streamed`、坐标与
         * 采样点数），交给调用方拼进动作结果；异常只可能是参数问题，注入失败不抛异常。
         */
        fun handle(touch: Touch): JSONObject {
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
            val event = runCatching { MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0) }
                .getOrNull() ?: return false
            return try {
                a.setDisplayId.invoke(event, displayId)
                a.inject.invoke(a.manager, event, MODE_ASYNC) as? Boolean ?: false
            } catch (_: Exception) {
                false
            } finally {
                event.recycle()
            }
        }
    }
}
