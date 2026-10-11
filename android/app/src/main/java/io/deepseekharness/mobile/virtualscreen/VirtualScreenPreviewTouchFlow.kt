package io.deepseekharness.mobile.virtualscreen

/** 逐事件触摸直传时 MOVE 的最小发送间隔；屏幕采样可到 120 Hz，合并后只丢中间采样点，抬起前那一个点仍会补发。 */
internal const val MOVE_INTERVAL_MILLIS = 8L

/**
 * 副屏预览控件上的一次触摸（纯数据：坐标与时刻都由 `MotionEvent` 读好后传进来）。
 *
 * [at] 是**反算到画面像素**的坐标（视图坐标经 `imageMatrix` 逆变换、并确认落在画面内），
 * 手指不在画面上时为 null。这条 null 语义贯穿整条流：没有画面内的点，就没有可直传、可点击的位置。
 * [rawX]/[rawY] 是屏幕坐标：拖窗改的是窗口位置，用屏幕位移而不是视图内坐标，不受缩放矩阵与视图边界影响。
 */
internal sealed interface PreviewPointer {
    val at: FloatArray?

    /** 按下：[eventTimeMillis] 是 `MotionEvent.eventTime`，滑动的时长按它算。 */
    class Down(override val at: FloatArray?, val rawX: Float, val rawY: Float, val eventTimeMillis: Long) : PreviewPointer

    /** 移动：只决定「够不够格改判成拖窗」与两条节流的合并。 */
    class Move(override val at: FloatArray?, val rawX: Float, val rawY: Float) : PreviewPointer

    /** 抬起：离散点击/滑动的终点与时长都取这一笔。 */
    class Up(override val at: FloatArray?, val rawX: Float, val rawY: Float, val eventTimeMillis: Long) : PreviewPointer

    /** 手势被系统或第二根手指取消：窗口停在原地、不补发末位置，只把副屏那侧的按下状态收掉。 */
    class Cancel(override val at: FloatArray?) : PreviewPointer
}

/**
 * 触摸流要调用方去做的一件事。字段都是**已经算好的值**（坐标取整、时长夹取都在流里完成），
 * 调用方只负责把它变成 `sendTouch` / `performClick` / `post`，不再做任何判定。
 */
internal sealed interface PreviewTouchEffect {
    /** 抬手之后把悬浮小窗重新挂到最前；调用方必须 `post` 到主线程再执行（与改造前一致：不在触摸派发里重建窗口）。 */
    object BringToFront : PreviewTouchEffect

    /** 没有拖动的手势结束：补一次点击回调。 */
    object Click : PreviewTouchEffect

    /** 逐事件触摸直传。[version] 是发出这一刻的控件代号，投递时刻按 [previewTouchDeliveryAllowed] 判废。 */
    data class Stream(val phase: String, val session: String, val version: Int, val x: Int, val y: Int) : PreviewTouchEffect

    /** 抬起时的离散动作：[swipe] 为真发滑动（带终点与时长），否则是一次点击。 */
    data class Discrete(
        val session: String,
        val version: Int,
        val swipe: Boolean,
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Int,
    ) : PreviewTouchEffect
}

/**
 * 触摸直传在**投递时刻**的作废判定：代号变了就丢，唯独 `cancel` 永不作废 ——
 * 取消是「别让目标应用卡在按下状态」的收尾动作，代号变化（离开页面、重挂）恰恰是最需要它落地的时刻。
 */
internal fun previewTouchDeliveryAllowed(phase: String, capturedVersion: Int, currentVersion: Int): Boolean =
    capturedVersion == currentVersion || phase == "cancel"

/** 离散点击/滑动的作废判定：代号一变就丢，没有例外（一次点击的归属是那一拍会话与那一屏画面）。 */
internal fun previewDiscreteDeliveryAllowed(capturedVersion: Int, currentVersion: Int): Boolean =
    capturedVersion == currentVersion

/**
 * 副屏预览的触摸手势流（纯 Kotlin：时钟、密度、现场事实与 effect 交付都是注入点，假时钟 + 假拖动端口即可覆盖）。
 *
 * 为什么抽出来：按下 / 移动 / 抬起 / 取消四个分支里的十几个字段（按下点、直传开关、两条节流队列、拖窗判定）
 * 原先散在 `setOnTouchListener` 的闭包里，和 `MotionEvent`、`WindowManager`、取帧线程的代号校验缠在一起，
 * 只能靠真机观察。抽出来之后，「什么时候直传、什么时候合并、什么时候改判成拖窗、抬起时补发哪一个点」
 * 可以逐事件喂进去断言。
 *
 * 边界（这里只做判定与交付顺序，不做别的）：
 * - 不读 `MotionEvent`、不碰 `Bitmap`/`Matrix`（画面内坐标由调用方算好，null 表示手指不在画面上）；
 * - 不投递到线程池、不碰 `View`：要做的动作通过 [dispatch] 当场派发，拖窗通过 [dragHost] 当场调用；
 * - 不读系统时钟：[uptimeMillis] / [realtimeMillis] 由调用方注入。
 *
 * [dispatch] 是**当场按原顺序**调用的，不是先攒后放：改造前 `sendTouch(cancel)` 排在 `drag.drag(...)`
 * 之前，这个相对顺序必须保持，所以 effect 不能在返回之后才交付。拖动端口同理 —— 它的返回值（窗口能不能拖）
 * 决定同一次 MOVE 里走哪条分支，因此只能是同步调用，不能做成 effect。
 *
 * @param uptimeMillis 单调时钟（真机传 `SystemClock.uptimeMillis`），长按门槛按它算。
 * @param realtimeMillis 另一个单调时钟（真机传 `SystemClock.elapsedRealtime`），两条节流按它算 —— 与改造前用的就是两个不同的钟。
 * @param density 显示密度：按下时按它把 [VirtualScreenWindow.DRAG_SLOP_DP] 换算成像素。
 * @param observedSession 判定核心当前的会话号：直传的会话号取「按下时的会话」，为空时才回落到它。
 * @param streamable 按下这一刻能不能逐事件直传。调用方实现为「服务与触摸执行器都在手、设备端报告 stream 通道、
 *   会话可观察、画面新鲜（不早于 3 秒）」。**只在按下时问一次**：一旦开了直传，后续 MOVE 不再复查。
 * @param discreteReady 抬起这一刻能不能发离散点击/滑动。参数是**按下时**的会话号：调用方实现为
 *   「会话与按下时一致、服务与执行器都在手、可观察、画面新鲜，并且已经占住 inputBusy」——
 *   占位是一次 CAS，返回 true 就意味着调用方必须在投递结束后放开它。
 * @param generation 控件代号（真机传 `VirtualScreenPreviewLifecycle.generation`）：写进 effect，投递时刻比对。
 * @param dragHost 拖动端口（悬浮小窗装着，预览页没有）；返回 null 表示当前没有可拖的窗口。
 * @param dispatch effect 的交付口：真机在这里发直传、补点击、`post` 置前。
 */
internal class VirtualScreenPreviewTouchFlow(
    private val uptimeMillis: () -> Long,
    private val realtimeMillis: () -> Long,
    private val density: () -> Float,
    private val observedSession: () -> String,
    private val streamable: () -> Boolean,
    private val discreteReady: (String) -> Boolean,
    private val generation: () -> Int,
    private val dragHost: () -> VirtualScreenDragHost?,
    private val dispatch: (PreviewTouchEffect) -> Unit,
) {
    /** 按下点（画面内坐标）；手指不在画面上时是 null。 */
    private var down: FloatArray? = null

    /** 按下那一刻的会话号：直传与离散动作都认它，抬起时还用它跟当前会话比对。 */
    private var downSession = ""

    /** 按下时刻（`MotionEvent.eventTime`），滑动的时长按它算。 */
    private var downAt = 0L

    /** 是否已经在逐事件直传：`true` 时 MOVE/UP/CANCEL 各走直传分支。 */
    private var streaming = false

    /** 上一次真的发出 MOVE 的时刻（节流窗口内只记最后一个点）。 */
    private var lastMoveAt = 0L

    /** 被节流合并掉、等下一次发或有抬起/取消时补发的那个点。 */
    private var queuedMove: FloatArray? = null

    /** 按下时的屏幕坐标：拖窗位移一律相对它换算。 */
    private var downRawX = 0f
    private var downRawY = 0f

    /** 这次手势是否已经改判为拖窗。 */
    private var dragActive = false

    /** 上一次真的发出拖动位置更新的时刻。 */
    private var dragLastAt = 0L

    /** 被节流合并掉、等抬手时补发的拖动末位置（相对按下点的屏幕位移）。 */
    private var queuedDrag: FloatArray? = null

    /** 长按计时的起点与静置容差（像素）：只有「按住不动够久」才允许改判成拖窗。 */
    private var downHeldAt = 0L
    private var dragSlopPx = 0f

    /** 喂入一个触摸事件。判定与交付都在这一趟里完成，调用方不需要再读任何状态。 */
    fun accept(pointer: PreviewPointer) {
        when (pointer) {
            is PreviewPointer.Down -> onDown(pointer)
            is PreviewPointer.Move -> onMove(pointer)
            is PreviewPointer.Up -> onUp(pointer)
            is PreviewPointer.Cancel -> onCancel(pointer)
        }
    }

    /**
     * 清屏时丢掉按下点（原 `clearFrame` 的第一行）。
     *
     * 刻意**不动**直传开关与被合并的移动点：与改造前逐字一致 —— 清屏已经让画面与观测状态归零，
     * 后续的 UP 也只会因为 `down == null` 落到「什么都不发」这一支。
     */
    fun clearDownPoint() {
        down = null
    }

    /**
     * 控件被摘下时的收尾：还有手势在直传就补一个 `cancel`（原 `releaseOnDetach` 的第一段）。
     *
     * 返回 null 表示没有在途手势。与改造前一致：不改被合并的点、不改按下点，只把直传开关关掉。
     */
    fun cancelForDetach(): PreviewTouchEffect.Stream? {
        if (!streaming) return null
        streaming = false
        val at = queuedMove ?: down ?: floatArrayOf(0f, 0f)
        return stream(at, "cancel")
    }

    private fun onDown(pointer: PreviewPointer.Down) {
        down = pointer.at
        downSession = observedSession()
        downAt = pointer.eventTimeMillis
        streaming = false
        queuedMove = null
        lastMoveAt = 0L
        downRawX = pointer.rawX
        downRawY = pointer.rawY
        dragActive = false
        queuedDrag = null
        dragLastAt = 0L
        dragSlopPx = VirtualScreenWindow.DRAG_SLOP_DP * density()
        // 长按计时的起点：只有「按住不动够久」才允许改判成拖窗。
        downHeldAt = uptimeMillis()
        // 只有设备端报告 stream 通道、且会话与画面都新鲜时才逐事件直传；
        // 否则保持原先的「抬起时发一个 tap/swipe」行为。
        if (pointer.at == null || !streamable()) return
        streaming = true
        dispatch(stream(pointer.at, "down"))
    }

    private fun onMove(pointer: PreviewPointer.Move) {
        val drag = dragHost()
        // 先长按再拖：按住不动 LONG_PRESS_DRAG_MILLIS 之后才允许改判成拖窗，
        // 这之前的移动一律照常透传给副屏（所以滑动、翻页不会被吞掉）。
        if (!dragActive && drag != null && VirtualScreenWindow.dragArmed(
                uptimeMillis() - downHeldAt,
                pointer.rawX - downRawX,
                pointer.rawY - downRawY,
                dragSlopPx,
            )) {
            dragActive = drag.beginDrag()
            if (dragActive) {
                // 手势在这一刻改判为拖动：副屏那边已经收到 down，必须补一个 cancel，
                // 否则目标应用会一直停在「按下」状态；之后的 MOVE 都归拖动，不再透传。
                if (streaming) {
                    streaming = false
                    (queuedMove ?: down ?: pointer.at)?.let { dispatch(stream(it, "cancel")) }
                }
                queuedMove = null
            }
        }
        if (dragActive && drag != null) {
            // 位置更新按与触摸直传相同的最短间隔节流：屏幕采样可到 120 Hz，
            // 每个采样都调一次 updateViewLayout 会白白占用合成器。
            val now = realtimeMillis()
            if (now - dragLastAt < MOVE_INTERVAL_MILLIS) {
                queuedDrag = floatArrayOf(pointer.rawX - downRawX, pointer.rawY - downRawY)
            } else {
                dragLastAt = now
                queuedDrag = null
                drag.drag((pointer.rawX - downRawX).toInt(), (pointer.rawY - downRawY).toInt())
            }
        } else if (streaming && pointer.at != null) {
            // 直传的 MOVE 按最短间隔合并：屏幕采样可能到 120 Hz，逐个转发会压满 Binder，
            // 合并后只丢中间采样点，抬起前那一个点仍会补发。
            val now = realtimeMillis()
            if (now - lastMoveAt < MOVE_INTERVAL_MILLIS) {
                queuedMove = pointer.at
            } else {
                lastMoveAt = now
                queuedMove = null
                dispatch(stream(pointer.at, "move"))
            }
        }
    }

    private fun onUp(pointer: PreviewPointer.Up) {
        // 手势结束后再提升层级：不会取消本次触摸，也不会打断目标应用的输入。
        // 只在「点一下」之后触发（拖动结束不触发），节流在 [VirtualScreenOverlayMachine] 里。
        if (!dragActive) {
            dispatch(PreviewTouchEffect.BringToFront)
            dispatch(PreviewTouchEffect.Click)
        }
        val start = down
        down = null
        if (dragActive) {
            // 补发被节流丢掉的末位置（手指停下的地方才是用户要的位置），
            // 不补发 up/tap/swipe —— 副屏那侧在 beginDrag 时已经收到 cancel。
            val lastX = queuedDrag?.get(0) ?: (pointer.rawX - downRawX)
            val lastY = queuedDrag?.get(1) ?: (pointer.rawY - downRawY)
            dragActive = false
            queuedDrag = null
            dragHost()?.endDrag(lastX.toInt(), lastY.toInt())
            return
        }
        if (streaming) {
            streaming = false
            (queuedMove ?: pointer.at)?.let { dispatch(stream(it, "up")) }
            queuedMove = null
            return
        }
        if (start == null || pointer.at == null || !discreteReady(downSession)) return
        val swipe = kotlin.math.hypot(pointer.at[0] - start[0], pointer.at[1] - start[1]) > 12
        dispatch(
            PreviewTouchEffect.Discrete(
                session = downSession,
                version = generation(),
                swipe = swipe,
                startX = start[0].toInt(),
                startY = start[1].toInt(),
                endX = pointer.at[0].toInt(),
                endY = pointer.at[1].toInt(),
                // 只有滑动才有意义：时长是「从按下到抬起」的墙上时间，按手势通道的边界夹到 100～2000 毫秒。
                durationMs = if (swipe) (pointer.eventTimeMillis - downAt).coerceIn(100, 2000).toInt() else 0,
            ),
        )
    }

    private fun onCancel(pointer: PreviewPointer.Cancel) {
        // 手势被取消（第二根手指按下、系统抢走手势）：窗口停在当前位置，不补发末位置。
        dragActive = false
        queuedDrag = null
        if (streaming) {
            streaming = false
            // 与改造前逐字一致：取的是「被合并的那个点，否则这一笔事件的点」，**没有**按下点兜底 ——
            // 手指不在画面上时这一笔就没有坐标，什么都不发。
            (queuedMove ?: pointer.at)?.let { dispatch(stream(it, "cancel")) }
            queuedMove = null
        }
        down = null
    }

    /** 直传事件：会话号取「按下时的会话」，为空才回落到判定核心当前认的会话（与改造前一致）。 */
    private fun stream(at: FloatArray, phase: String) = PreviewTouchEffect.Stream(
        phase = phase,
        session = downSession.ifEmpty { observedSession() },
        version = generation(),
        x = at[0].toInt(),
        y = at[1].toInt(),
    )
}
