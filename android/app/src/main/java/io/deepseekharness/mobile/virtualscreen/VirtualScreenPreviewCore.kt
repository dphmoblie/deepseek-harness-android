package io.deepseekharness.mobile.virtualscreen

/**
 * 副屏预览一拍取到的帧样本：上屏判定需要知道的全部事实都在这里，不含任何 Android 类型。
 *
 * 之所以做成纯数据：判定与状态行原先夹在 [VirtualScreenPreview.tick] 的取帧线程与主线程 `post` 之间，
 * 只能靠真机观察验证。把它拆成「样本进来、结论出去」之后，固定帧序列可以在 JVM 单测里逐拍喂入。
 *
 * @param sessionId 这一帧属于哪个副屏会话（判定「会话是否还可观察」用）。
 * @param channel 设备端报告的触摸通道；状态行的触摸段由它决定（`stream` 之外一律按离散点击/滑动描述）。
 * @param mode 本拍读到的档位；续排延迟与档位标题都按它算。
 * @param fps 与这一帧同一拍读到的帧率读数（不能拿 health 的旧快照贴帧率）。
 * @param decoded 这一拍是否解码出了画面（硬件路径即 `wrapHardwareBuffer` 是否成功）。
 * @param samples 这一拍的亮度采样；null 表示这一拍没拿到可解码的画面（不等于「画面是空的」）。
 * @param hardwareFrame 是否走硬件帧路径：硬件帧没有 CPU 采样，内容判定只认解码结果。
 */
internal class PreviewSample(
    val sessionId: String,
    val channel: String,
    val mode: String,
    val fps: Double,
    val decoded: Boolean,
    val samples: IntArray?,
    val hardwareFrame: Boolean,
)

/**
 * 一拍的结论：怎么处置画面、状态行写什么，以及这一拍对观测状态做了什么改动。
 *
 * [refreshObservedAt] / [updateObservedChannel] 如实报告状态改动，让「只有 SHOW 才刷新画面时刻」这类
 * 不变量可以直接断言，不必去猜核心内部发生了什么。
 */
internal class PreviewResult(
    val action: VirtualScreenPolicy.PreviewFrameAction,
    val line: String,
    /** 这一拍是否前移了画面时刻（只有 SHOW 会）。 */
    val refreshObservedAt: Boolean,
    /** 这一拍是否更新了触摸通道（通道为空或清屏时不更新）。 */
    val updateObservedChannel: Boolean,
    /** 这一拍结束时状态行是否在说「等待副屏画面」：还没显示过任何画面时为真。 */
    val showWaiting: Boolean,
)

/**
 * 副屏预览的上屏判定核心（纯 Kotlin：时间由参数传入，能直接在 JVM 单测里逐拍覆盖）。
 *
 * 为什么抽出来：这一小段「拿到一帧之后决定 SHOW / KEEP / CLEAR」的判定原先嵌在
 * [VirtualScreenPreview.tick] 的取帧线程与主线程 `post` 之间，和 HardwareBuffer 的所有权转移、
 * 线程代号校验缠在一起，只能靠真机观察。抽出来之后，固定帧序列（有内容 / 纯色 / 与上帧相同 /
 * 读不到帧 / 会话失效）可以直接断言动作与观测状态，缓冲所有权与线程行为仍留在控件里。
 *
 * 边界（这里只做判定，不做别的）：
 * - 不取帧、不解码、不碰 Bitmap / HardwareBuffer —— 那些留在控件里；
 * - 不核对在途帧是否作废（线程代号在主线程入口处由控件核对）；
 * - 不读系统时钟：本拍时刻由 [accept] 的 `nowMillis` 传入。
 *
 * 判定权威在客户端：宿主那个只看固定采样线的 `frameBlank` 只作提示，不参与这里
 * （0.2.9 的真机黑屏就是把它当成了「不能显示」）。
 *
 * @param sessionAlive 会话是否仍可观察（已切换、已释放都算不可观察）；这是唯一允许清屏的情形。
 * @param modeTitle 档位标题的本地化文案（真机上传 `context.getString(modeTitleRes(mode))`）。
 */
internal class VirtualScreenPreviewCore(
    private val sessionAlive: (String) -> Boolean,
    private val modeTitle: (String) -> String,
) {
    /** 是否已经显示过画面：决定状态行说「等待副屏画面」还是报帧率。 */
    var displayed = false
        private set

    /** 最近一帧真画面的时刻：KEEP 一律不动它，触摸与「画面新鲜」判定才能沿用真正的最后一帧。 */
    var observedAtMillis = 0L
        private set

    /** 最近一帧真画面属于哪个会话。 */
    var observedSession = ""
        private set

    /** 最近一次状态里的触摸通道：`stream` 表示可逐事件直传，其它值退回离散点击/滑动。 */
    var observedChannel = ""
        private set

    /** 上一张已显示画面的亮度采样，用来判断「画面暂无变化」；null 表示还没显示过画面。 */
    private var previousSamples: IntArray? = null

    /**
     * 清空画面与观测状态：息屏、离开页面、会话不可观察共用这一条路径。
     *
     * 触摸通道刻意不清 —— 与原来 [VirtualScreenPreview.clearFrame] 逐字一致。通道只用于
     * 「通道是 stream 且画面新鲜」同时成立的直传闸门，清屏已经把画面时刻归零、把会话清空，
     * 闸门本就关上；多清一个字段反而会改变设备端不报通道时的回落路径。
     */
    fun clear() {
        displayed = false
        observedSession = ""
        observedAtMillis = 0L
        previousSamples = null
    }

    /**
     * 喂入一拍，返回动作与状态行，并把这一拍的观测状态改动落在本类内部。
     *
     * 顺序与原来的 tick 逐字一致：先按「硬件帧只认解码结果、PNG 走采样启发式」算出内容与变化，
     * 再交给 [VirtualScreenPolicy.previewOutcome]；状态行在状态落地之后才生成 ——
     * SHOW 已经把画面画上去，就不该再说「等待副屏画面」。
     */
    fun accept(sample: PreviewSample, nowMillis: Long): PreviewResult {
        val hardwareFrame = sample.hardwareFrame
        val decoded = sample.decoded
        val samples = sample.samples
        val alive = sessionAlive(sample.sessionId)
        // HardwareBuffer 已由 Bitmap.wrapHardwareBuffer 成功解码，但不会生成 CPU samples；
        // 也不能拿尚未做 CPU 采样的 frameBlank=true 把有效硬件帧误判成纯色。
        // 硬件路径的内容判定交给解码结果，PNG 路径继续使用采样启发式。
        val hasContent = if (hardwareFrame) decoded
            else samples != null && VirtualScreenPolicy.frameHasContent(samples)
        val changed = if (hardwareFrame) decoded
            else samples == null || VirtualScreenPolicy.frameChanged(previousSamples, samples)
        val outcome = VirtualScreenPolicy.previewOutcome(alive, decoded, !hasContent, changed)
        val show = outcome.action == VirtualScreenPolicy.PreviewFrameAction.SHOW
        val clear = outcome.action == VirtualScreenPolicy.PreviewFrameAction.CLEAR
        if (show) {
            // 只有真帧才刷新画面时刻：不能让触摸与「画面新鲜」判定误以为刚更新过。
            displayed = true
            observedSession = sample.sessionId
            observedAtMillis = nowMillis
            // 只有真正显示过的画面才算「上一帧」，状态行的「画面暂无变化」才有所指。
            previousSamples = samples
        }
        if (clear) clear()
        if (sample.channel.isNotEmpty() && !clear) observedChannel = sample.channel
        // 状态行按「状态已经落地」的那一刻读数。文案统一由 VirtualScreenPolicy 生成：
        // 页面大预览与悬浮小窗共用同一份规则，两侧不会各自拼串后漂移。
        val showWaiting = !displayed
        val line = if (clear) VirtualScreenPolicy.PREVIEW_GONE_MESSAGE
        else VirtualScreenPolicy.previewLine(sample.channel, outcome.pause, sample.fps, modeTitle(sample.mode), showWaiting)
        return PreviewResult(outcome.action, line, show, sample.channel.isNotEmpty() && !clear, showWaiting)
    }
}
