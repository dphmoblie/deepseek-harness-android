package io.deepseekharness.mobile.virtualscreen

import java.util.Locale

/**
 * 无设备副屏模拟器：**固定帧 + 事件流 + 可推进的虚拟时钟**，在 JVM 上把副屏预览的四个纯逻辑类跑起来，
 * 产出一条可读、可断言的时间线。
 *
 * 为什么要它：`VirtualScreenPreviewCore`（上屏判定）、`VirtualScreenPreviewPump`（拍子调度）、
 * `VirtualScreenPreviewTouchFlow`（触摸手势流）、`VirtualScreenOverlayMachine`（悬浮窗状态机）各自都有单测，
 * 但真机上它们是**串在一条时间轴上**的：一拍取帧会改观测状态，观测状态决定触摸能不能直传，
 * 触摸抬起会把小窗置前，置前会摘下重挂（代号不动、拍子不停），重挂失败会把整条取帧循环释放掉。
 * 只看单个类，看不见这些**跨类的因果与顺序**。本模拟器就是那个「调用方」：它持有一份假的主线程队列、
 * 一份假的窗口与一份虚拟时钟，按真机 `VirtualScreenPreview` / `VirtualScreenService` 的同一套顺序去驱动它们，
 * 于是「第几拍之后发生了什么、按什么顺序发生」变成一行行文字。
 *
 * 边界（一定要看清，否则会把模拟器的结论当成了真机的结论）：
 * - 只驱动**纯逻辑**：不碰 `View` / `WindowManager` / `Bitmap` / `HardwareBuffer` / `MotionEvent`，
 *   所以它验证不了真实窗口的可见性、叠放、闪屏、输入法层级，也验证不了真实 fps 与缓冲泄漏；
 * - 模拟器自己**就是调用方**，因此它必须照抄真机调用方的三条处置：在途帧的控件代号校验、
 *   硬件缓冲所有权的转移、会话可观察性判定。这三条在上面的纯类里刻意没有（见 `VirtualScreenPreviewCore` 的边界注释）；
 *   模拟器的账本（[closedBuffers]）验证的是「判定给出的动作 → 调用方该做的处置」这一映射，
 *   真正的 `close()` 调用点由 `scripts/virtual-screen-preview-contract.test.mjs` 的源码切片看着；
 * - 时间全部来自 [uptimeMillis] / [realtimeMillis] 两个可推进的假钟，没有任何 `Thread.sleep`、
 *   没有随机数、没有哈希顺序依赖，因此同一段脚本跑两次的时间线**逐字相同**。
 *
 * @param screenWidthPx 屏幕宽（像素），只用于窗口几何。
 * @param screenHeightPx 屏幕高（像素）。
 * @param topInsetPx 状态栏边距（像素）：小窗上边界与拖动的夹取上界。
 * @param bottomInsetPx 导航栏边距（像素）。
 * @param density 显示密度：长按改判拖窗的静置容差按它换算像素。
 * @param spec 副屏规格：小窗按它换算画面比例。
 * @param initialSession 初始副屏会话号。
 * @param initialMode 初始档位（必须是 `VirtualScreenPolicy.FRAME_MODES` 里的一档）。
 * @param defaultCostMillis 一拍的取帧 + 上屏默认耗时；脚本里可以逐帧覆盖。
 */
internal class VirtualScreenHeadlessSimulator(
    val screenWidthPx: Int = 1080,
    val screenHeightPx: Int = 1920,
    val topInsetPx: Int = 60,
    val bottomInsetPx: Int = 90,
    val density: Float = 2f,
    val spec: VirtualScreenSpec = VirtualScreenSpec.PORTRAIT,
    initialSession: String = "s1",
    initialMode: String = "30fps",
    var defaultCostMillis: Long = 0L,
) {
    // ------------------------------------------------------------------
    // 虚拟时钟：两个读数由一个时钟一起推进（真机上是 uptimeMillis 与 elapsedRealtime 两个不同的钟）
    // ------------------------------------------------------------------

    /** 单调时钟读数，对应 `SystemClock.uptimeMillis()`：长按门槛按它算。 */
    var uptimeMillis = 0L
        private set

    /** 另一个单调时钟读数，对应 `SystemClock.elapsedRealtime()`：两条节流与画面新鲜度按它算。 */
    var realtimeMillis = SIM_REALTIME_BASE
        private set

    /** 推进虚拟时钟；两个读数一起走，差值保持 [SIM_REALTIME_BASE]（与既有触摸单测的范式一致）。 */
    fun advance(millis: Long) {
        require(millis >= 0L) { "虚拟时钟只能向前推进" }
        uptimeMillis += millis
        realtimeMillis += millis
    }

    // ------------------------------------------------------------------
    // 会话与控件代号
    // ------------------------------------------------------------------

    /** 当前副屏会话号。 */
    var session = initialSession
        private set

    /** 会话是否仍可观察（已切换、已释放都算不可观察）：判定核心只认这一个读数。 */
    var alive = true
        private set

    /** 控件代号（真机传 `VirtualScreenPreviewLifecycle.generation`）：在途帧与触摸 effect 都按它判废。 */
    var generation = 1
        private set

    /** 会话切换：旧会话立刻不可观察，在途帧会在落地时被判成 CLEAR。 */
    fun switchSession(next: String) {
        session = next
        alive = true
        record(SimEvent.KIND_SESSION, "会话切换到 $next")
    }

    /** 会话丢失（副屏被系统收回、宿主断连）：会话号不变，但不再可观察。 */
    fun loseSession(reason: String = "副屏会话已失效") {
        alive = false
        record(SimEvent.KIND_SESSION, "会话丢失：$reason")
    }

    /**
     * 推进控件代号（真机传 `VirtualScreenPreviewLifecycle.attach/detach`）。
     *
     * 代号一变，在途帧与排队中的触摸 effect 就都作废了；**唯独 `cancel` 永不作废**（见 `previewTouchDeliveryAllowed`）。
     */
    fun bumpGeneration(reason: String = "重新挂载") {
        generation++
        record(SimEvent.KIND_PREVIEW, "$reason：控件代号 → $generation")
    }

    // ------------------------------------------------------------------
    // 固定帧脚本
    // ------------------------------------------------------------------

    /** 固定帧脚本；跑完一轮之后从头循环取用，保证任意拍数的脚本都是确定的。 */
    private var frames: List<SimFrame> = emptyList()
    private var cursor = 0

    /** 最近一次被判成 SHOW 的那一帧采样：判定核心内部的 `previousSamples` 也只在 SHOW 那一支更新。 */
    private var lastDisplayedSamples: IntArray? = null

    /** 设置固定帧脚本（按顺序循环取用）。 */
    fun script(vararg frames: SimFrame): VirtualScreenHeadlessSimulator = script(frames.toList())

    /** 设置固定帧脚本（列表版，便于用档位表批量生成）。 */
    fun script(frames: List<SimFrame>): VirtualScreenHeadlessSimulator {
        require(frames.isNotEmpty()) { "固定帧脚本不能为空" }
        this.frames = frames.toList()
        cursor = 0
        record(SimEvent.KIND_FRAME, "帧脚本就位：${frames.size} 帧（${frames.joinToString("、") { it.kind.label }}）")
        return this
    }

    /** 当前档位；换档之后下一拍立刻按新档位算续排延迟（真机上档位改完运行中立即生效）。 */
    var mode = initialMode
        private set

    /** 换档：档位名必须合法（非法档位由 `VirtualScreenPolicy.frameInterval` 抛错）。 */
    fun changeMode(next: String) {
        VirtualScreenPolicy.frameInterval(next)
        mode = next
        record(SimEvent.KIND_FRAME, "档位切换到 $next（采集间隔 ${VirtualScreenPolicy.previewPullInterval(next)}ms）")
    }

    // ------------------------------------------------------------------
    // 判定核心与拍子
    // ------------------------------------------------------------------

    /** 上屏判定核心：会话可观察由模拟器的会话表回答，档位标题用纯函数生成（真机传本地化文案）。 */
    private val core = VirtualScreenPreviewCore(
        sessionAlive = { id -> alive && id == session },
        modeTitle = { mode -> VirtualScreenPolicy.frameModeLabel(mode) },
    )

    /** 假主线程队列：`Handler` 里同一实例只留一条，所以用「有没有排队 + 排队延迟」两个读数表达。 */
    private var queued = false
    private var queuedDelay = 0L

    /** 每次投递的续排延迟（首拍是 0，之后每拍一个），供「延迟 = 档位间隔 − 本拍耗时」逐拍断言。 */
    val pumpDelays = mutableListOf<Long>()

    /** 拍子投递的那一个 Runnable（`Handler.removeCallbacks` 按实例匹配，必须是稳定实例）。 */
    private val beatToken = Runnable { }

    private val pump = VirtualScreenPreviewPump(
        now = { realtimeMillis },
        postDelayed = { delay, _ ->
            queued = true
            queuedDelay = delay
            pumpDelays += delay
        },
        removeCallbacks = { queued = false },
        beat = beatToken,
    )

    /** 拍子是否在跑（镜像 [startPump] / [stopPump] 这两个动作本身）。 */
    var pumpRunning = false
        private set

    /** 队列里排着几拍：正常时永远是 1（一拍只续排一次），慢帧被丢弃时是 0。 */
    val queuedBeats: Int get() = if (queued) 1 else 0

    /** 已经落地的拍数。 */
    var beatCount = 0
        private set

    /** 启动拍子；只有首次返回 true（重挂或重复调用不会复制出第二条拍子）。 */
    fun startPump(): Boolean {
        val started = pump.start()
        if (started) pumpRunning = true
        record(SimEvent.KIND_PUMP, if (started) "启动（档位 $mode，首拍延迟 0）" else "已经在跑：重复启动被吞掉")
        return started
    }

    /** 停止拍子：取消排队中的那一拍，之后本拍落地不再续排。 */
    fun stopPump() {
        val wasRunning = pumpRunning
        pump.stop()
        pumpRunning = false
        record(SimEvent.KIND_PUMP, if (wasRunning) "停止（取消排队中的那一拍）" else "未在跑：停止是空操作")
    }

    /** 在途帧：取帧那一刻的全部事实，落地时按它判废、喂判定核心、转移缓冲所有权。 */
    private class InFlight(
        val frame: SimFrame,
        val samples: IntArray?,
        val sessionId: String,
        val generation: Int,
        val buffer: SimBuffer?,
        val startedAtMillis: Long,
        val costMillis: Long,
        val index: Int,
    )

    private var inFlight: InFlight? = null

    /**
     * 开始一拍：把排队中的那一拍取出来（虚拟时钟前移到投递时刻），记下在途帧，并把时钟推进本拍耗时。
     *
     * 与真机 `tick` 同序：`pump.fetchStarted()` 记下起始时刻 → 取帧与上屏花掉耗时 → 落地时按档位续排。
     * 拍子没在跑（没排队）时什么都不做并返回 false。
     */
    fun fetch(): Boolean {
        if (frames.isEmpty()) {
            record(SimEvent.KIND_PUMP, "没有帧脚本：这一拍没有可喂的样本")
            return false
        }
        if (!queued) {
            record(SimEvent.KIND_PUMP, "拍子没在跑：这一拍没有排上")
            return false
        }
        check(inFlight == null) { "上一拍的在途帧还没落地" }
        queued = false
        advance(queuedDelay)
        val frame = frames[cursor++ % frames.size]
        val startedAt = pump.fetchStarted()
        val cost = frame.costMillis ?: defaultCostMillis
        inFlight = InFlight(
            frame = frame,
            samples = frame.kind.samples(lastDisplayedSamples),
            // 会话号取**取帧那一刻**的会话：真机上就是 `PreviewSample(sessionId = id)` 里的那个 id。
            sessionId = frame.sessionId ?: session,
            generation = generation,
            buffer = if (frame.kind.hardwareFrame) SimBuffer(++bufferSequence) else null,
            startedAtMillis = startedAt,
            costMillis = cost,
            index = beatCount + 1,
        )
        advance(cost)
        return true
    }

    /**
     * 一拍落地：核对控件代号 → 交给判定核心 → 按动作转移硬件缓冲所有权 → 按档位续排下一拍。
     *
     * 代号已经变了（重挂、离开页面）时**不调 `fetchLanded`**：真机上那一支是直接 `return@post`，
     * 于是这一拍的丢失也带走了下一拍的投递 —— 拍子要靠另一次挂载重新 `start`。
     */
    fun land(): PreviewResult? {
        val flight = inFlight
        if (flight == null) {
            record(SimEvent.KIND_PUMP, "没有在途帧：这一拍无可落地")
            return null
        }
        inFlight = null
        beatCount++
        val landedMode = flight.frame.mode ?: mode
        if (flight.generation != generation) {
            val note = closeInFlight(flight)
            discards++
            record(
                SimEvent.KIND_PUMP,
                beatRow(
                    index = flight.index,
                    frameLabel = flight.frame.kind.label,
                    action = "丢弃在途帧",
                    refresh = false,
                    channelChanged = false,
                    costMillis = flight.costMillis,
                    delay = null,
                    buffer = note,
                    line = "无（没有交给判定核心）",
                    note = "原因=控件代号已变(${flight.generation}→$generation)",
                ),
            )
            return null
        }
        val result = core.accept(
            PreviewSample(
                sessionId = flight.sessionId,
                channel = flight.frame.channel,
                mode = landedMode,
                fps = flight.frame.fps,
                decoded = flight.frame.kind.decoded,
                samples = flight.samples,
                hardwareFrame = flight.frame.kind.hardwareFrame,
            ),
            realtimeMillis,
        )
        var bufferNote = "无"
        when (result.action) {
            VirtualScreenPolicy.PreviewFrameAction.SHOW -> {
                shows++
                // 真机是 `displayedHardware = fetchedHardware` 之后 `oldHardware?.close()`：
                // 换帧的那一刻旧缓冲才被关，KEEP 与丢弃从不碰已显示的那一块。
                lastDisplayedSamples = flight.samples
                val buffer = flight.buffer
                if (buffer != null) {
                    val old = displayedBuffer
                    displayedBuffer = buffer
                    old?.let { release(it) }
                    bufferNote = "显示#${buffer.id}" + (old?.let { "（释放旧#${it.id}）" } ?: "")
                }
            }
            VirtualScreenPolicy.PreviewFrameAction.KEEP -> {
                keeps++
                // 真机是 `fetchedHardware?.close(); fetchedHardware = null`：只释放在途那一块。
                closeInFlight(flight)?.let { bufferNote = it }
            }
            VirtualScreenPolicy.PreviewFrameAction.CLEAR -> {
                clears++
                val notes = mutableListOf<String>()
                closeInFlight(flight)?.let { notes += it }
                closeDisplayed()?.let { notes += it }
                lastDisplayedSamples = null
                if (notes.isNotEmpty()) bufferNote = notes.joinToString(" ")
            }
        }
        val delay = pump.fetchLanded(landedMode)
        record(
            SimEvent.KIND_PUMP,
            beatRow(
                index = flight.index,
                frameLabel = flight.frame.kind.label,
                action = result.action.name,
                refresh = result.refreshObservedAt,
                channelChanged = result.updateObservedChannel,
                costMillis = flight.costMillis,
                delay = delay,
                buffer = bufferNote,
                line = result.line,
            ),
        )
        return result
    }

    /** 取帧 + 落地一步到位（真机上两者之间隔着一次 `main.post`，要拆开时用 [fetch] / [land]）。 */
    fun beat(): PreviewResult? = if (fetch()) land() else null

    /** 连跑 [count] 拍。 */
    fun beats(count: Int) {
        repeat(count) { beat() }
    }

    // ------------------------------------------------------------------
    // 硬件缓冲账本（模拟器自己扮演调用方，照抄 VirtualScreenPreview 的三条处置）
    // ------------------------------------------------------------------

    /** 硬件缓冲：真机上是 `HardwareBuffer`，这里只记它的身份与有没有被释放。 */
    private class SimBuffer(val id: Int) {
        var released = false
            private set

        fun release() {
            released = true
        }
    }

    private var bufferSequence = 0
    private var displayedBuffer: SimBuffer? = null

    /** 已经被释放的硬件缓冲 id，按释放顺序；用来断言「换帧关旧缓冲 / KEEP 关在途缓冲」。 */
    val closedBuffers = mutableListOf<Int>()

    private fun release(buffer: SimBuffer) {
        if (buffer.released) return
        buffer.release()
        closedBuffers += buffer.id
    }

    /** 释放在途缓冲（KEEP 与「在途帧被丢弃」共用）；没有缓冲时返回 null。 */
    private fun closeInFlight(flight: InFlight): String? {
        val buffer = flight.buffer ?: return null
        release(buffer)
        return "释放在途#${buffer.id}"
    }

    /** 释放已显示的缓冲（清屏那一支）；没有缓冲时返回 null。 */
    private fun closeDisplayed(): String? {
        val buffer = displayedBuffer ?: return null
        displayedBuffer = null
        release(buffer)
        return "清掉已显示#${buffer.id}"
    }

    // ------------------------------------------------------------------
    // 触摸事件流：effect 先进触摸工作线程队列，投递时刻才按代号判废
    // ------------------------------------------------------------------

    private var pointerRawX = 100f
    private var pointerRawY = 200f

    /** 触摸工作线程的队列（真机是 `touchExecutor.execute`）。 */
    private val pendingTouch = ArrayDeque<PreviewTouchEffect>()

    /** 离散动作的占位（真机是 `AtomicBoolean inputBusy`）：一次 CAS，投递结束后必须放开。 */
    private var inputBusy = false

    /** 试着占住离散动作的位子：已经有离散动作在跑时返回 false（真机是 `compareAndSet(false, true)`）。 */
    private fun takeDiscreteSlot(): Boolean {
        if (inputBusy) return false
        inputBusy = true
        return true
    }

    private val dragHost = object : VirtualScreenDragHost {
        override fun beginDrag(): Boolean {
            val started = machine.beginDrag()
            recordTouch(SimEvent.KIND_TOUCH, "拖动", "开始（可拖=$started）")
            return started
        }

        override fun drag(deltaX: Int, deltaY: Int) {
            applyOverlayEffects(machine.dragBy(deltaX, deltaY))
            recordTouch(SimEvent.KIND_TOUCH, "拖动", "位置更新 +$deltaX,+$deltaY")
        }

        override fun endDrag(deltaX: Int, deltaY: Int) {
            applyOverlayEffects(machine.endDrag(deltaX, deltaY))
            recordTouch(SimEvent.KIND_TOUCH, "拖动", "结束 +$deltaX,+$deltaY")
        }
    }

    private val touchFlow = VirtualScreenPreviewTouchFlow(
        uptimeMillis = { uptimeMillis },
        realtimeMillis = { realtimeMillis },
        density = { density },
        observedSession = { core.observedSession },
        streamable = {
            // 真机闸门：服务与触摸执行器都在手、设备端报告 stream 通道、会话可观察、画面新鲜（不早于 3 秒）。
            overlayServiceReady && touchExecutorReady && core.observedChannel == "stream" && alive &&
                realtimeMillis - core.observedAtMillis < VirtualScreenPolicy.FRAME_STALE_AFTER_MILLIS
        },
        discreteReady = { requested ->
            requested == core.observedSession && overlayServiceReady && touchExecutorReady && alive &&
                realtimeMillis - core.observedAtMillis < VirtualScreenPolicy.FRAME_STALE_AFTER_MILLIS &&
                takeDiscreteSlot()
        },
        generation = { generation },
        dragHost = { if (dragHostAvailable) dragHost else null },
        dispatch = { effect -> enqueueTouch(effect) },
    )

    /** 服务与触摸执行器是否都在手（真机上是 `VirtualScreenService.current != null && touchExecutor != null`）。 */
    var overlayServiceReady = true
    var touchExecutorReady = true

    /** 预览控件有没有装拖动端口：悬浮小窗装了，原生预览页没装。 */
    var dragHostAvailable = true

    /** 按下：`at` 是画面内坐标（null 表示手指不在画面上），raw 是屏幕坐标（拖窗改的是窗口位置）。 */
    fun down(
        at: FloatArray? = floatArrayOf(10f, 20f),
        rawX: Float = pointerRawX,
        rawY: Float = pointerRawY,
        eventTimeMillis: Long = uptimeMillis,
    ) {
        pointerRawX = rawX
        pointerRawY = rawY
        recordTouch(SimEvent.KIND_TOUCH, "事件", "按下 画面=${pointLabel(at)} 屏幕=(${rawX.toInt()},${rawY.toInt()})")
        touchFlow.accept(PreviewPointer.Down(at, rawX, rawY, eventTimeMillis))
    }

    /** 移动：屏幕坐标就是拖窗位移的基准（相对按下点换算）。 */
    fun move(at: FloatArray? = floatArrayOf(10f, 20f), rawX: Float = pointerRawX, rawY: Float = pointerRawY) {
        pointerRawX = rawX
        pointerRawY = rawY
        recordTouch(SimEvent.KIND_TOUCH, "事件", "移动 画面=${pointLabel(at)} 屏幕=(${rawX.toInt()},${rawY.toInt()})")
        touchFlow.accept(PreviewPointer.Move(at, rawX, rawY))
    }

    /** 抬起：离散点击/滑动的终点与时长都取这一笔。 */
    fun up(
        at: FloatArray? = floatArrayOf(10f, 20f),
        rawX: Float = pointerRawX,
        rawY: Float = pointerRawY,
        eventTimeMillis: Long = uptimeMillis,
    ) {
        pointerRawX = rawX
        pointerRawY = rawY
        recordTouch(SimEvent.KIND_TOUCH, "事件", "抬起 画面=${pointLabel(at)} 屏幕=(${rawX.toInt()},${rawY.toInt()})")
        touchFlow.accept(PreviewPointer.Up(at, rawX, rawY, eventTimeMillis))
    }

    /** 手势被系统或第二根手指取消：窗口停在原地、不补发末位置。 */
    fun cancel(at: FloatArray? = floatArrayOf(10f, 20f)) {
        recordTouch(SimEvent.KIND_TOUCH, "事件", "取消 画面=${pointLabel(at)}")
        touchFlow.accept(PreviewPointer.Cancel(at))
    }

    /** effect 进触摸工作线程队列（真机是 `worker.execute`）：投递时机由 [flushTouch] 决定。 */
    private fun enqueueTouch(effect: PreviewTouchEffect) {
        pendingTouch += effect
        recordTouch(SimEvent.KIND_TOUCH, "触发", touchLabel(effect))
        if (effect is PreviewTouchEffect.Discrete) recordTouch(SimEvent.KIND_TOUCH, "占位", "inputBusy=true")
    }

    /**
     * 把队列里的触摸 effect 按原顺序投递掉，返回真的投递了几条。
     *
     * 投递时刻才判废：`stream` 比控件代号，代号变了就丢，唯独 `cancel` 永不作废；
     * 离散点击/滑动一次也不能丢代号，并且无论投递成败都要放开 `inputBusy`（真机的 finally）。
     */
    fun flushTouch(): Int {
        var delivered = 0
        while (pendingTouch.isNotEmpty()) {
            val effect = pendingTouch.removeFirst()
            val allowed = when (effect) {
                PreviewTouchEffect.BringToFront -> {
                    // 真机是 `main.post { bringOverlayToFront() }`：抬手之后才把窗口重新挂到最前。
                    bringOverlayToFront()
                    true
                }
                PreviewTouchEffect.Click -> true
                is PreviewTouchEffect.Stream ->
                    previewTouchDeliveryAllowed(effect.phase, effect.version, generation)
                is PreviewTouchEffect.Discrete -> {
                    val permitted = previewDiscreteDeliveryAllowed(effect.version, generation)
                    if (inputBusy) inputBusy = false
                    permitted
                }
            }
            recordTouch(SimEvent.KIND_TOUCH, if (allowed) "投递" else "丢弃", touchLabel(effect) + if (allowed) "" else " 原因=控件代号已变")
            if (allowed) {
                delivered++
                deliveredTouch += touchLabel(effect)
            }
        }
        return delivered
    }

    /** 真的投递出去的触摸 effect（按顺序），供「节流与补发顺序」这类断言直接用。 */
    val deliveredTouch = mutableListOf<String>()

    /** 离散动作的占位是否还占着：投递结束必须放开，否则第二次点击再也发不出去。 */
    val discreteBusy: Boolean get() = inputBusy

    // ------------------------------------------------------------------
    // 窗口事件流：模拟器扮演 VirtualScreenService 的四个入口
    // ------------------------------------------------------------------

    /** 悬浮窗权限（真机传 `Settings.canDrawOverlays`）。 */
    var overlayPermission = true

    /** 会话是否就绪（真机传 `session.isNotEmpty() && !ending.get()`）。 */
    var overlaySessionReady = true

    /** 这次建窗会不会失败（真机上是 `WindowManager.addView` 抛异常）。 */
    var overlayAddWorks = true

    /** 这次重挂会不会失败（真机上是 `removeViewImmediate` 之后的 `addView` 抛异常）。 */
    var overlayReAddWorks = true

    /** 置前的最小间隔；真机上由服务传 `FRONT_THROTTLE_MILLIS`。 */
    var frontThrottleMillis = 1200L

    private val area = OverlayArea(
        widthPx = screenWidthPx,
        heightPx = screenHeightPx,
        topInsetPx = topInsetPx,
        bottomInsetPx = bottomInsetPx,
        sizingWidthPx = screenWidthPx,
        sizingHeightPx = screenHeightPx - topInsetPx - bottomInsetPx,
    )

    private val machine = VirtualScreenOverlayMachine(
        uptimeMillis = { uptimeMillis },
        canDrawOverlays = { overlayPermission },
        sessionReady = { overlaySessionReady },
        density = { density },
        spec = { spec },
        viewWidthPx = { viewWidthPx },
        viewHeightPx = { viewHeightPx },
        frontThrottleMillis = frontThrottleMillis,
    )

    /** 视图量出来的尺寸；0 表示还没量出来（夹取退回窗口参数里的尺寸）。 */
    var viewWidthPx = 0
    var viewHeightPx = 0

    /** 窗口几何被算过几次：幂等与闸门顺序靠它断言「连屏幕读数都没发生」。 */
    var layoutCalls = 0
        private set

    /** 真发出去的建窗请求次数。 */
    var addViewCalls = 0
        private set

    /** 窗口在不在窗口队列里（真机上由 `addView` / `removeView` 的成败决定）。 */
    private var viewAttached = false

    /** 预览控件的生命周期是否在跑；`retain = true` 的那次摘挂不停它。 */
    private var previewRunning = false

    /** 摘挂时是否保留取帧循环（真机是 `retainFrameLoopOnDetach`）。 */
    private var retainFrameLoop = false

    /** 悬浮窗状态读数。 */
    val overlayState: OverlayState get() = machine.state

    /** 当前窗口几何；没有窗口时为 null。 */
    val overlayLayout: OverlayLayout? get() = machine.layout

    /** 显示小窗：三道闸门 → 建窗 → 成功挂载 / 失败回滚。返回这次有没有真的建出一扇窗。 */
    fun showOverlay(): Boolean {
        val effects = try {
            machine.show { layoutCalls++; overlayWindowLayout() }
        } catch (error: IllegalStateException) {
            record(SimEvent.KIND_WINDOW, "显示被拒绝：${error.message}")
            return false
        }
        val add = effects.filterIsInstance<OverlayEffect.AddView>().firstOrNull()
        if (add == null) {
            record(SimEvent.KIND_WINDOW, "显示被吞掉（幂等：窗口已经在手）")
            return false
        }
        addViewCalls++
        record(
            SimEvent.KIND_WINDOW,
            "建窗 位置=(${add.layout.x},${add.layout.y}) 尺寸=${add.layout.widthPx}×${add.layout.heightPx}",
        )
        if (!overlayAddWorks) {
            // 真机的 catch：`preview = null; overlayMachine.viewAddFailed(); throw e`。
            // 窗口从未进过队列，预览控件也被丢掉 —— 这条取帧循环的归宿就是停掉。
            machine.viewAddFailed()
            record(SimEvent.KIND_WINDOW, "建窗失败：回滚到 ${machine.state}，丢弃预览控件")
            stopPump()
            core.clear()
            lastDisplayedSamples = null
            closeDisplayed()
            return false
        }
        machine.viewAdded()
        viewAttached = true
        record(SimEvent.KIND_WINDOW, "建窗成功：${machine.state}")
        attachPreview()
        return true
    }

    /** 收起小窗：先撤掉「保留取帧循环」的开关再摘窗口（顺序由状态机给）。 */
    fun hideOverlay() {
        applyOverlayEffects(machine.hide())
        forgetOverlayWindow()
        record(SimEvent.KIND_WINDOW, "收起结束：${machine.state}")
    }

    /**
     * 把悬浮窗重新挂到窗口队列末端（真机是一条 `main.post`）。
     *
     * 中间态是 [OverlayState.SUSPENDED]：已经摘下来、马上重新挂上，取帧循环靠 `KeepFrameLoop` 活过这次摘挂。
     * 返回这次重挂有没有成功；失败那一支等价于真机的
     * `catch { hideOverlay(); image.releaseOnDetach(retain = false) }`。
     */
    fun bringOverlayToFront(): Boolean {
        val effects = machine.bringToFront()
        if (effects.isEmpty()) {
            record(SimEvent.KIND_WINDOW, "置前被吞掉（没有窗口，或还在 ${frontThrottleMillis} 毫秒节流里）")
            return false
        }
        record(SimEvent.KIND_WINDOW, "置前：摘下来重挂（中间态 ${machine.state}）")
        val applied = applyOverlayEffects(effects)
        if (applied) machine.viewReAdded() else forgetOverlayWindow()
        record(SimEvent.KIND_WINDOW, "置前结束：${machine.state}")
        return applied
    }

    /** 按新宽度等比缩放小窗（只认宽度，高度由画面比例反算）。 */
    fun resizeOverlay(widthPx: Int) {
        applyOverlayEffects(machine.resize(widthPx))
    }

    /** 服务销毁：收起窗口并记到 DEAD，此后不再接受显示请求（真机 `onDestroy` 里 `ending.set(true)`）。 */
    fun destroyService() {
        applyOverlayEffects(machine.destroy())
        forgetOverlayWindow()
        overlaySessionReady = false
        alive = false
        record(SimEvent.KIND_WINDOW, "服务销毁：${machine.state}")
    }

    /** 息屏：取帧停止、画面清空（与真机 `ACTION_SCREEN_OFF` 的清屏 + 离开页面时的 `releaseOnDetach` 同效）。 */
    fun screenOff() {
        record(SimEvent.KIND_SESSION, "息屏")
        detachPreview(retain = false)
    }

    /** 亮屏恢复：控件重新挂载（代号推进、拍子重启），画面从下一拍重新建立。 */
    fun screenOn() {
        record(SimEvent.KIND_SESSION, "亮屏恢复")
        attachPreview()
    }

    /**
     * 控件挂上窗口（真机 `onAttachedToWindow`）：只有新一轮挂载才推进代号并启动拍子。
     * 重挂时 `lifecycle.attach()` 返回 false —— 代号不动、拍子不重启，在途帧负责续排。
     */
    private fun attachPreview() {
        if (previewRunning) {
            record(SimEvent.KIND_PREVIEW, "重复挂载：代号与拍子都不动")
            return
        }
        previewRunning = true
        generation++
        record(SimEvent.KIND_PREVIEW, "挂载：控件代号 → $generation")
        startPump()
    }

    /**
     * 控件摘下来（真机 `releaseOnDetach(retain)`）：`retain = true` 的那次摘挂什么都不做，
     * 于是取帧循环与代号都活过置前重建；否则补一个 cancel、停拍子、清画面。
     */
    private fun detachPreview(retain: Boolean) {
        if (retain || !previewRunning) {
            record(SimEvent.KIND_PREVIEW, if (retain) "摘挂（保留取帧循环）：不推代号、不停拍子" else "已经摘挂过：不推代号")
            return
        }
        previewRunning = false
        generation++
        record(SimEvent.KIND_PREVIEW, "摘挂：控件代号 → $generation")
        touchFlow.cancelForDetach()?.let { enqueueTouch(it) }
        stopPump()
        core.clear()
        lastDisplayedSamples = null
        closeDisplayed()
        record(SimEvent.KIND_PREVIEW, "停拍子并清屏")
    }

    /** 窗口句柄与窗口参数只有这里会清（真机 `forgetOverlayWindow`）。 */
    private fun forgetOverlayWindow() {
        viewAttached = false
    }

    /**
     * 执行窗口生命周期 effect；返回 false 表示「重挂失败，已经就地收尾」。
     *
     * 与真机 `applyOverlayEffects` 同序：`RemoveAndReAddView` 失败时**立刻**跑
     * `viewReAddFailed()` 那四条 effect 并返回，原始列表里排在它后面的 `ReleaseFrameLoop` 不会再执行。
     */
    private fun applyOverlayEffects(effects: List<OverlayEffect>): Boolean {
        for (effect in effects) {
            recordWindowEffect(effect)
            when (effect) {
                OverlayEffect.KeepFrameLoop -> retainFrameLoop = true
                OverlayEffect.ReleaseFrameLoop -> retainFrameLoop = false
                OverlayEffect.ReleaseDetachedFrameLoop -> detachPreview(retain = false)
                OverlayEffect.RemoveView ->
                    if (viewAttached) {
                        viewAttached = false
                        detachPreview(retainFrameLoop)
                    } else {
                        record(SimEvent.KIND_WINDOW, "窗口已经不在队列里：removeView 什么都不做（异常被吞掉）")
                    }
                OverlayEffect.RemoveAndReAddView -> {
                    // 摘下来立刻重挂：这两步必须在同一帧里完成，否则会看到一次空窗。
                    viewAttached = false
                    detachPreview(retainFrameLoop)
                    if (!overlayReAddWorks) {
                        record(SimEvent.KIND_WINDOW, "重挂失败：addView 抛异常（视图已摘下，不会再收到 detach 回调）")
                        applyOverlayEffects(machine.viewReAddFailed())
                        return false
                    }
                    viewAttached = true
                    attachPreview()
                }
                is OverlayEffect.UpdateLayout -> record(
                    SimEvent.KIND_WINDOW,
                    "窗口几何 → 位置=(${effect.x},${effect.y}) 尺寸=${effect.widthPx}×${effect.heightPx}",
                )
                is OverlayEffect.AddView -> Unit
            }
        }
        return true
    }

    /** 真机 `overlayWindowLayout()`：整屏扣掉系统栏，尺寸按画面比例，位置居中。 */
    private fun overlayWindowLayout(): OverlayLayout {
        val size = VirtualScreenWindow.overlaySize(spec, area.sizingWidthPx, area.sizingHeightPx, density)
        return OverlayLayout(
            x = (screenWidthPx - size.widthPx) / 2,
            y = (screenHeightPx - topInsetPx - bottomInsetPx - size.heightPx) / 2,
            widthPx = size.widthPx,
            heightPx = size.heightPx,
            area = area,
        )
    }

    // ------------------------------------------------------------------
    // 时间线与读数
    // ------------------------------------------------------------------

    private val events = mutableListOf<SimEvent>()

    /** 窗口 effect 的落地序列（按顺序，只记真的跑过的那些）。 */
    val windowEffects = mutableListOf<String>()

    /** 触摸 effect 的触发 / 拖动端口调用序列（按顺序），供「先补 cancel 再拖窗」这类顺序断言。 */
    val touchEffects = mutableListOf<String>()

    private var shows = 0
    private var keeps = 0
    private var clears = 0
    private var discards = 0

    /** 拍数 / 各动作的计数 / 丢弃的在途帧数，供汇总行与断言。 */
    val showCount: Int get() = shows
    val keepCount: Int get() = keeps
    val clearCount: Int get() = clears
    val discardCount: Int get() = discards

    /** 画面是否已经显示过（判定核心的 `displayed`）。 */
    val displayed: Boolean get() = core.displayed

    /** 最近一帧真画面的时刻（判定核心的 `observedAtMillis`）：只有 SHOW 会前移它。 */
    val observedAtMillis: Long get() = core.observedAtMillis

    /** 最近一帧真画面属于哪个会话。 */
    val observedSession: String get() = core.observedSession

    /** 最近一次状态里的触摸通道；`stream` 才可能直传。 */
    val observedChannel: String get() = core.observedChannel

    /** 拍子排队中那一拍的延迟；没有排队时为 null。 */
    val queuedDelayMillis: Long? get() = if (queued) queuedDelay else null

    private fun record(kind: String, text: String) {
        events += SimEvent(realtimeMillis, kind, text)
    }

    private fun recordTouch(kind: String, stage: String, text: String) {
        val label = "$stage $text"
        touchEffects += "[t=${pad(realtimeMillis)}ms] $label"
        record(kind, label)
    }

    private fun recordWindowEffect(effect: OverlayEffect) {
        val label = overlayEffectLabel(effect)
        windowEffects += "[t=${pad(uptimeMillis)}ms] $label"
        record(SimEvent.KIND_WINDOW, "effect=$label")
    }

    private fun beatRow(
        index: Int,
        frameLabel: String,
        action: String,
        refresh: Boolean,
        channelChanged: Boolean,
        costMillis: Long,
        delay: Long?,
        buffer: String?,
        line: String,
        note: String? = null,
    ): String = buildString {
        append("拍#$index 帧=$frameLabel 动作=$action")
        append(" 刷新=${yesNo(refresh)} 换通道=${yesNo(channelChanged)} 等待=${yesNo(!core.displayed)}")
        append(" 耗时=${costMillis}ms 续排=${delay?.let { "${it}ms" } ?: "未续排"}")
        append(" 缓冲=${buffer ?: "无（这一拍没有硬件缓冲）"} 行=$line")
        if (note != null) append(" $note")
    }

    /** 时间线（一行一件事，按发生顺序）。 */
    fun timeline(): List<String> = events.map { "[t=${pad(it.atMillis)}ms] ${it.kind} ${it.text}" }

    /**
     * 把整条时间线渲染成给人读的文本：拍子与事件 → 窗口 effect 序列 → 触摸 effect 序列 → 汇总。
     *
     * 不需要设备、不需要断言，打印出来就能看懂这一串拍子与事件各自做了什么。
     */
    fun render(): String = buildString {
        appendLine("==== 无设备副屏模拟器时间线 ====")
        appendLine("档位=$mode 会话=$session 会话可观察=${yesNo(alive)} 控件代号=$generation")
        appendLine("虚拟时钟：uptime=${uptimeMillis}ms realtime=${realtimeMillis}ms（起点错开 ${SIM_REALTIME_BASE}ms）")
        appendLine("窗口：${overlayState} 几何=${overlayLayout?.let { "(${it.x},${it.y}) ${it.widthPx}×${it.heightPx}" } ?: "无"}")
        appendLine("---- 拍子与事件（${events.size} 行）----")
        appendLine("列说明：拍#N 帧=<形状> 动作=<SHOW/KEEP/CLEAR/丢弃在途帧> 刷新=是否前移画面时刻 换通道=是否更新触摸通道")
        appendLine("        等待=状态行是否在说「等待副屏画面」 耗时=本拍取帧与上屏 续排=下一拍延迟 缓冲=硬件缓冲归属 行=状态行文本")
        timeline().forEach { appendLine(it) }
        appendLine("---- 窗口 effect 序列（${windowEffects.size} 条）----")
        windowEffects.forEach { appendLine(it) }
        appendLine("---- 触摸 effect 序列（${touchEffects.size} 条）----")
        touchEffects.forEach { appendLine(it) }
        appendLine("---- 汇总 ----")
        appendLine("拍=$beatCount SHOW=$shows KEEP=$keeps CLEAR=$clears 丢弃在途帧=$discards 拍子在跑=${yesNo(pumpRunning)}")
        appendLine("已释放硬件缓冲=${if (closedBuffers.isEmpty()) "无" else closedBuffers.joinToString("、") { "#$it" }}")
        appendLine("投递出去的触摸事件=${if (deliveredTouch.isEmpty()) "无" else deliveredTouch.joinToString("、")}")
    }

    /** 时间线的一行：发生时刻 + 类别 + 文本。 */
    internal class SimEvent(val atMillis: Long, val kind: String, val text: String) {
        companion object {
            const val KIND_PUMP = "拍子"
            const val KIND_FRAME = "帧"
            const val KIND_TOUCH = "触摸"
            const val KIND_WINDOW = "窗口"
            const val KIND_PREVIEW = "预览"
            const val KIND_SESSION = "会话"
        }
    }
}

/** 一秒的毫秒数没有直接关系：这里只是让两个假钟的起点错开，与真机上两个不同的单调时钟一致。 */
private const val SIM_REALTIME_BASE = 1_000L

/** 有内容的采样：亮度极差 200，远超 [VirtualScreenPolicy.PREVIEW_CONTENT_SPREAD]。 */
private val SIM_CONTENT_SAMPLES = intArrayOf(0, 200, 8, 12)

/** 接近纯色的采样（全黑、纯色未渲染画面这一类低信息量帧）：亮度极差 2，不超过内容阈值。 */
private val SIM_FLAT_SAMPLES = intArrayOf(10, 12, 11, 10)

/** 「与上一帧不同」改的下标：改完必与上一帧相差 64 或 192，稳定超过 [VirtualScreenPolicy.PREVIEW_SAMPLE_DELTA]。 */
private const val SIM_CHANGED_INDEX = 1

/**
 * 固定帧脚本里一帧的形状：脚本里的一句「这一拍设备端报了什么」。
 *
 * 七种形状覆盖了真机上出现过的全部取帧结果：正常画面、画面没变、画面变了、纯色/黑帧、
 * 这一拍读不到帧、没有 CPU 采样的硬件帧，以及**取到了硬件缓冲却没解出画面**的那一种。
 */
internal enum class SimFrameKind(val label: String) {
    /** 有内容的画面：SHOW。 */
    CONTENT("有内容"),

    /** 与最近一次显示过的画面逐点相同：仍是 SHOW，状态行补「画面暂无变化」。 */
    SAME("与上帧相同"),

    /** 与最近一次显示过的画面有一处超阈值差异：SHOW，不补任何暂停说明。 */
    CHANGED("与上帧不同"),

    /** 全黑/纯色这一类低信息量帧：KEEP，状态行说「当前帧接近纯色」，画面时刻不前移。 */
    BLANK("接近纯色"),

    /** 这一拍没解出画面（取帧忙、锁屏）：KEEP，状态行说「未读到新帧」。 */
    UNREADABLE("未解出画面"),

    /** 硬件帧：没有 CPU 采样，内容判定只认解码结果。 */
    HARDWARE("硬件帧"),

    /**
     * 拿到了硬件缓冲但没解出画面（`wrapHardwareBuffer` 失败这一类）：KEEP。
     *
     * 与 [UNREADABLE] 的区别正是硬件缓冲账本要断言的那一点：**这一拍手里确实有一块在途缓冲**，
     * KEEP 必须把它关掉；而 [UNREADABLE] 的 PNG 路径手里什么都没有。
     */
    HARDWARE_UNREADABLE("硬件帧读不到"),
    ;

    /** 这一拍有没有解出画面：只有两种「读不到」是没解出来的（硬件路径也要求 `wrapHardwareBuffer` 成功）。 */
    val decoded: Boolean get() = this != UNREADABLE && this != HARDWARE_UNREADABLE

    /** 是否走硬件帧路径。 */
    val hardwareFrame: Boolean get() = this == HARDWARE || this == HARDWARE_UNREADABLE

    /**
     * 这一拍的采样。
     *
     * [previous] 是**最近一次被判成 SHOW** 的那一帧采样：判定核心内部的 `previousSamples` 只在 SHOW 时更新，
     * 脚本跟着它走，[SAME] 才会真的判成「画面暂无变化」。硬件帧与未解出的帧没有采样（null）。
     */
    fun samples(previous: IntArray?): IntArray? = when (this) {
        UNREADABLE, HARDWARE, HARDWARE_UNREADABLE -> null
        BLANK -> SIM_FLAT_SAMPLES
        SAME -> previous ?: SIM_CONTENT_SAMPLES
        CONTENT -> SIM_CONTENT_SAMPLES
        CHANGED -> (previous ?: SIM_CONTENT_SAMPLES).copyOf().also {
            // 这一个采样点在 32 / 224 之间来回切：不管上一帧它是哪个值，都同时满足
            // 「与上帧不同」（差值远超 PREVIEW_SAMPLE_DELTA = 12）与「有内容」（极差远超 PREVIEW_CONTENT_SPREAD = 24）。
            // 曾经写成 (值 + 64) % 256，200 + 64 会绕回 8，把极差从 200 压到 12，
            // 于是这一帧被判成「接近纯色」走 KEEP——「换了新画面 → SHOW」这条路就再也走不到了。
            it[SIM_CHANGED_INDEX] = if (it[SIM_CHANGED_INDEX] >= 128) 32 else 224
        }
    }
}

/**
 * 固定帧脚本里的一拍：形状 + 这一拍随帧带来的读数。
 *
 * [mode] / [sessionId] / [costMillis] 为 null 表示跟随模拟器当前的档位 / 会话 / 默认取帧耗时。
 */
internal class SimFrame(
    val kind: SimFrameKind,
    /** 与这一帧同一拍读到的帧率读数（不能拿旧快照贴帧率）。 */
    val fps: Double = 30.0,
    /** 设备端报告的触摸通道：`stream` 之外一律按离散点击/滑动描述。 */
    val channel: String = "stream",
    val mode: String? = null,
    val sessionId: String? = null,
    val costMillis: Long? = null,
)

/** 触摸 effect 的短标签：拖动端口调用与 effect 共用一条序列，交错顺序才能被断言。 */
private fun touchLabel(effect: PreviewTouchEffect): String = when (effect) {
    PreviewTouchEffect.BringToFront -> "front"
    PreviewTouchEffect.Click -> "click"
    is PreviewTouchEffect.Stream -> "stream:${effect.phase}@${effect.x},${effect.y}#${effect.version}"
    is PreviewTouchEffect.Discrete ->
        if (effect.swipe) "swipe@${effect.startX},${effect.startY}->${effect.endX},${effect.endY}ms${effect.durationMs}#${effect.version}"
        else "tap@${effect.startX},${effect.startY}#${effect.version}"
}

/** 窗口 effect 的中文说明：人工读时间线时不必再回去翻枚举。 */
private fun overlayEffectLabel(effect: OverlayEffect): String = when (effect) {
    is OverlayEffect.AddView -> "AddView（建窗）"
    OverlayEffect.RemoveView -> "RemoveView（摘窗）"
    OverlayEffect.RemoveAndReAddView -> "RemoveAndReAddView（摘下来立刻重挂）"
    is OverlayEffect.UpdateLayout -> "UpdateLayout（改窗口参数）"
    OverlayEffect.KeepFrameLoop -> "KeepFrameLoop（保留取帧循环）"
    OverlayEffect.ReleaseFrameLoop -> "ReleaseFrameLoop（复位保留取帧循环）"
    OverlayEffect.ReleaseDetachedFrameLoop -> "ReleaseDetachedFrameLoop（显式释放已摘下的帧循环）"
}

private fun yesNo(value: Boolean): String = if (value) "是" else "否"

private fun pointLabel(at: FloatArray?): String = if (at == null) "画面外" else "(${at[0].toInt()},${at[1].toInt()})"

private fun pad(millis: Long): String = String.format(Locale.US, "%6d", millis)
