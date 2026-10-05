package io.deepseekharness.mobile.virtualscreen

import android.content.Intent
import org.json.JSONObject

/**
 * 副屏尺寸规格：把原先散落在三处的硬编码像素宽高与 dpi 收成一份不可变数据。
 *
 * 为什么要有这一层：
 * 1. 创建虚拟显示时的三个数字（`ShellVirtualScreen.kt:105` 的 `createVirtualDisplay(…, w, h, dpi, …)`）
 *    原先是在 `VirtualScreenService.kt:81` 用一个三元表达式现场拼出来的，读 Intent、建会话、校验
 *    三处各自持有一份「什么是合法尺寸」的隐含知识，改一档就要动几处；
 * 2. 悬浮小窗此前写死 320×480 dp（`VirtualScreenService.kt:439-440`），与虚拟屏的真实宽高比无关，
 *    竖屏副屏 726×1600 放进 320×480 的小窗里必然上下留黑边。小窗要按虚拟屏比例显示，就得有一个
 *    「当前副屏多大」的明确来源，这就是本对象。
 *
 * 本文件**只用 Kotlin 标准库**（Intent 相关部分只碰 `android.content.Intent` 的整型 get/put），
 * 不读资源、不碰 WindowManager，因此可以在 JVM 单测里直接覆盖。
 *
 * 与 [VirtualScreenPolicy.dimensions] 的分工（两者都在真机上生效，不是重复校验）：
 * - 本对象是**规格层**：把外部给进来的三个整数收敛成一份可用的规格，超范围一律夹取而不是抛异常，
 *   因为「副屏多大」是可以安全夹取的量，没必要为它中断一次用户操作；
 * - [VirtualScreenPolicy.dimensions] 是**启动前闸门**：值收敛之后仍要过它，它还会额外限制
 *   `宽 ≤ 1440`、`高 ≤ 2560`、`宽 × 高 ≤ 2_073_600` 这类与 ImageReader 缓冲区有关的硬上限，
 *   真越界时由它抛 `副屏尺寸超出范围`，让调用方看到明确失败而不是悄悄换了个尺寸。
 *   两个预设都满足它的上限（单测里有断言盯着这一点）。
 */
data class VirtualScreenSpec(val widthPx: Int, val heightPx: Int, val densityDpi: Int) {

    /** 虚拟屏宽 / 高。调用方（小窗）用它换算面板尺寸，不必自己再判一遍除零。 */
    val aspectRatio: Double get() = widthPx.toDouble() / heightPx

    /** 以当前设备的 density 换算的两个 dp 边长，只用于界面文案；不参与任何校验。 */
    fun dpWidth(density: Float): Int = Math.round(widthPx / density)

    fun dpHeight(density: Float): Int = Math.round(heightPx / density)

    /** 界面与日志共用的一行读数，例如 `726 × 1600 像素 / 320 dpi`。 */
    fun label(): String = "$widthPx × $heightPx 像素 / $densityDpi dpi"

    companion object {
        /**
         * 竖屏预设：726 × 1600 像素、320 dpi（按常见 density=2.0 即 363 × 800 dp）。
         * 数值与改造前 `VirtualScreenService.kt:81` 的竖屏分支逐字一致，**不得调整**。
         */
        val PORTRAIT = VirtualScreenSpec(726, 1600, 320)

        /**
         * 横屏预设：1280 × 580 像素、256 dpi（约 800 × 363 dp）。
         * 数值与改造前 `VirtualScreenService.kt:81` 的横屏分支逐字一致，**不得调整**。
         */
        val LANDSCAPE = VirtualScreenSpec(1280, 580, 256)

        /** 触发横屏预设的布尔，与旧 Intent 的 `landscape` 字段同义。 */
        fun preset(landscape: Boolean): VirtualScreenSpec = if (landscape) LANDSCAPE else PORTRAIT

        fun of(landscape: Boolean): VirtualScreenSpec = preset(landscape)

        /** 边长下限：比它更小的副屏连一次 `input tap` 都难落准，也没有主流应用能正常布局。 */
        const val MIN_EDGE = 200

        /** 边长上限：与 [VirtualScreenPolicy.dimensions] 的 1440 × 2560 相比留了余量，这里是「明显不合法」的界线。 */
        const val MAX_EDGE = 4096

        /** dpi 下限：120 约等于 ldpi，低于此值的虚拟屏字体会大到无法布局。 */
        const val MIN_DPI = 120

        /** dpi 上限：640 约等于 xxxhdpi，高于此值目标应用会按超小密度渲染。 */
        const val MAX_DPI = 640

        /** Intent 里的三个整型字段名。只放整数，不放序列化对象：老版本读到未知 extra 会直接忽略。 */
        const val EXTRA_WIDTH = "widthPx"
        const val EXTRA_HEIGHT = "heightPx"
        const val EXTRA_DPI = "densityDpi"

        /** 旧 Intent 的布尔字段。仍然支持，缺省为竖屏。 */
        const val EXTRA_LANDSCAPE = "landscape"

        /** 宿主侧状态 JSON 里表示副屏像素宽高的字段名（`ShellVirtualScreen.state()` 写入）。 */
        const val STATE_WIDTH = "width"
        const val STATE_HEIGHT = "height"

        /**
         * 严格取值校验 + 夹取：**不抛异常**，把任意三个整数收敛成一份落在 [MIN_EDGE]..[MAX_EDGE]
         * 与 [MIN_DPI]..[MAX_DPI] 内的规格。
         *
         * 规则（有意与下面的 [decode] 不同，两处都写在 KDoc 里免得后来者以为其中一处是笔误）：
         * - 传进来的值超出上限/下限 → 夹到边界（例如 5000 → 4096、100 → 200、dpi 1000 → 640）；
         * - 无符号意义的非正数（0 与负数）→ 按该字段的下限处理，因为「0 像素宽」没有任何可解释的结果。
         */
        fun normalize(widthPx: Int, heightPx: Int, densityDpi: Int): VirtualScreenSpec = VirtualScreenSpec(
            widthPx = widthPx.coerceIn(MIN_EDGE, MAX_EDGE),
            heightPx = heightPx.coerceIn(MIN_EDGE, MAX_EDGE),
            densityDpi = densityDpi.coerceIn(MIN_DPI, MAX_DPI),
        )

        /** 「读不到或读到非法值就回退预设」这一条的实现，见 [decode]。 */
        private fun valid(value: Int, min: Int, max: Int): Boolean = value in min..max

        /**
         * Intent 解码的**纯逻辑**部分：三个整型字段（读不到时调用方传 0）与旧版 `landscape` 布尔进来，
         * 出这次会话该用的规格。**读不到或任何一项非法都整体回退方向预设**，不抛异常。
         *
         * 为什么这里不是夹取：三个字段来自一次跨进程投递，缺项或越界说明这次投递本身已经不可信
         * （老版本服务、被系统截断的 Intent、手工伪造的 extra）。此时「换一份恰好能用的尺寸」比
         * 「按不可信的输入建一块副屏」更安全，也仍然给用户一块可用的副屏。
         *
         * 向后兼容：旧 Intent 只有 `landscape` 布尔 → 三个字段一个都读不到（都是 0）→ 走同一个回退分支，
         * 于是 `landscape=true` 时回退到[横屏预设][LANDSCAPE]，与改造前行为完全一致。
         *
         * 把这一段单独拿出来是因为 `Intent` 的 getter/putter 在 JVM 单测里是 not mocked 的空壳
         * （`:app:testDebugUnitTest` 用 mockable android.jar，且本模块没有 Robolectric），
         * 取值与回退的规则只有放在纯函数里才能在单测里真正跑到。
         */
        fun fromFields(widthPx: Int, heightPx: Int, densityDpi: Int, landscape: Boolean): VirtualScreenSpec {
            val strict = valid(widthPx, MIN_EDGE, MAX_EDGE) && valid(heightPx, MIN_EDGE, MAX_EDGE) && valid(densityDpi, MIN_DPI, MAX_DPI)
            if (!strict) {
                // 三个字段有一个不合法：整体丢弃，按旧版唯一的布尔字段决定预设。
                return preset(landscape)
            }
            return VirtualScreenSpec(widthPx, heightPx, densityDpi)
        }

        /**
         * 从 Intent 读回规格。取值规则全部在 [fromFields] 里，这里只负责从 `Intent` 取四个 extra。
         * 真机/仪器测试才会走这里；JVM 单测覆盖的是 [fromFields]。
         */
        fun decode(intent: Intent?): VirtualScreenSpec {
            if (intent == null) return PORTRAIT
            return fromFields(
                widthPx = intent.getIntExtra(EXTRA_WIDTH, 0),
                heightPx = intent.getIntExtra(EXTRA_HEIGHT, 0),
                densityDpi = intent.getIntExtra(EXTRA_DPI, 0),
                landscape = intent.getBooleanExtra(EXTRA_LANDSCAPE, false),
            )
        }

        /**
         * 从宿主侧状态（`ShellVirtualScreen.state()` 的 JSON，字段 `width`/`height`）取本次会话的宽高，
         * 供悬浮小窗换算比例使用。状态里没有 dpi，此时带上竖屏预设的 dpi 只为凑齐规格对象，
         * 小窗的几何换算只用宽高，不读取这个值。
         *
         * 状态尚未就绪（服务刚起来、会话已结束）或宽高非法时回退竖屏预设：
         * 副屏绝大多数时间是竖屏，此时小窗的比例是对的；真读不到读数时也不该让比例换算除零。
         */
        fun decodeState(state: JSONObject?): VirtualScreenSpec {
            val width = state?.optInt(STATE_WIDTH, 0) ?: 0
            val height = state?.optInt(STATE_HEIGHT, 0) ?: 0
            if (!valid(width, MIN_EDGE, MAX_EDGE) || !valid(height, MIN_EDGE, MAX_EDGE)) return PORTRAIT
            return VirtualScreenSpec(width, height, PORTRAIT.densityDpi)
        }
    }
}

/**
 * 把规格写进 Intent 的三个整型 extra，与 [VirtualScreenSpec.decode] 配对。
 *
 * 只放整数、不放序列化对象：跨进程投递的字段越简单越不容易在版本之间错位，
 * 旧版本收到不认识的 extra 会直接忽略，仍按它自己的 `landscape` 逻辑工作。
 * 这里同时放上旧字段，是为了让「装了新版页面、会话服务还是旧版」这种升级中途的组合也能用：
 * 新版读三个整型字段，旧版读布尔字段，两个来源不会互相打架。
 */
fun Intent.putVirtualScreenSpec(spec: VirtualScreenSpec, landscape: Boolean = false): Intent =
    putExtra(VirtualScreenSpec.EXTRA_WIDTH, spec.widthPx)
        .putExtra(VirtualScreenSpec.EXTRA_HEIGHT, spec.heightPx)
        .putExtra(VirtualScreenSpec.EXTRA_DPI, spec.densityDpi)
        .putExtra(VirtualScreenSpec.EXTRA_LANDSCAPE, landscape)

/**
 * 悬浮小窗与预览画面的几何换算：让可视区域宽高比等于当前虚拟屏宽高比，从而消掉黑边。
 *
 * 黑边的直接来源是预览视图的 `ScaleType.FIT_CENTER`（`VirtualScreenService.kt:501` 起）：
 * 它按「等比缩放并居中」显示位图，视图与位图比例不一致时，多出来的那一轴就是黑边。
 * 所以消黑边不需要改预览视图，只需要让小窗的内容区比例等于虚拟屏比例。
 *
 * 这里的函数都是纯算术，单位由调用方决定（服务里一律传像素），单测用同一份实现覆盖边界。
 */
object VirtualScreenWindow {
    /** 小窗宽度上限：屏幕宽度的 90%。改造前就是这个比例（`VirtualScreenService.kt:439`），保持不变。 */
    const val MAX_SCREEN_WIDTH_RATIO = 0.9

    /** 小窗高度上限：屏幕高度的 70%。改造前就是这个比例（`VirtualScreenService.kt:440`），保持不变。 */
    const val MAX_SCREEN_HEIGHT_RATIO = 0.7

    /**
     * 小窗的标称宽度上限（320 dp）。改造前小窗宽度是 `min(320dp, 90% 屏宽)`，
     * 这里保留这个「设计尺寸」，让极端宽比例副屏不会把小窗拉满整个屏幕宽度。
     */
    const val NOMINAL_WIDTH_DP = 320

    /**
     * 小窗的标称高度上限（480 dp）。改造前小窗高度是 `min(480dp, 70% 屏高)`；
     * 按比例换算后一般用不到它，只在极端高比例副屏上兜底。
     */
    const val NOMINAL_HEIGHT_DP = 480

    /** 面板左右内边距（各 12dp），与 `VirtualScreenService.showOverlay()` 的 `setPadding(12, …)` 对齐。 */
    const val PANEL_PADDING_DP = 24

    /** 工具栏一行的高度预算（含「目标应用 · 拖动」与两个按钮）。 */
    const val TOOLBAR_HEIGHT_DP = 48

    /** 档位按钮行的高度预算。 */
    const val MODE_ROW_HEIGHT_DP = 36

    /**
     * 状态行的高度预算。这里按**渲染前**的预算取值，所以是 0：
     * 面板固定高度按「画面的高度 + 这些预算」算出来时，状态行会分走画面区的高度，
     * 而状态行自己又需要一行的高度。宁可把预算计成 0，让画面区略大于比例值（FIT_CENTER 会
     * 用黑边补掉那点差值，观感上仍是完整画面），也不要让状态行把画面压扁、甚至在窄屏上被裁掉。
     */
    const val STATUS_ROW_HEIGHT_DP = 0

    /**
     * 面板里除画面以外的竖向占用（dp），用于把「画面高度」还原成「小窗总高度」。
     *
     * 这里用的是**预算**而不是真实测量值：`showOverlay()` 必须在 `addView` 之前就把窗口尺寸定下来
     * （窗口尺寸参与拖动边界夹取，事后 `updateViewLayout` 改高度会让夹取用错基准），
     * 所以只能在布局前给出保守估计。偏差的方向是「预留略多」，后果是小窗比画面略大一圈，
     * 不会把画面裁掉。
     */
    const val CHROME_HEIGHT_DP = PANEL_PADDING_DP + TOOLBAR_HEIGHT_DP + MODE_ROW_HEIGHT_DP + STATUS_ROW_HEIGHT_DP

    /**
     * 在 `maxWidth × maxHeight` 的矩形内求一个宽高比等于 [aspectRatio] 的最大内容尺寸。
     *
     * 换算顺序：先按宽度顶满算出高度，若高度超出上限再反过来按高度顶满算宽度。
     * 结果保证 `宽 ≤ maxWidth`、`高 ≤ maxHeight`，且两个边长都不小于 1（避免出现 0 尺寸视图）。
     * 整数取整会让实际比例与目标比例存在不到 1 像素的误差，调用方按整数像素使用即可。
     */
    fun fitToAspect(maxWidth: Int, maxHeight: Int, aspectRatio: Double): Pair<Int, Int> {
        val widthLimit = maxWidth.coerceAtLeast(1)
        val heightLimit = maxHeight.coerceAtLeast(1)
        if (!aspectRatio.isFinite() || aspectRatio <= 0.0) return widthLimit to heightLimit
        val byWidth = widthLimit
        val heightFromWidth = Math.round(byWidth / aspectRatio).toInt()
        if (heightFromWidth <= heightLimit && heightFromWidth >= 1) return byWidth to heightFromWidth
        val byHeight = heightLimit
        val widthFromHeight = Math.round(byHeight * aspectRatio).toInt()
        return widthFromHeight.coerceIn(1, widthLimit) to byHeight
    }

    /**
     * 由虚拟屏规格与屏幕尺寸算出小窗的总尺寸与画面（可视区域）尺寸。
     *
     * 约束按这个顺序施加，后一条不会推翻前一条：
     * 1. 屏幕上限：宽 ≤ 90% 屏宽、高 ≤ 70% 屏高（改造前就有的硬上限，保留）；
     * 2. 标称上限：宽 ≤ 320dp、高 ≤ 480dp（改造前的设计尺寸，避免极端比例把小窗拉满屏）；
     * 3. 比例：画面区宽高比 == [VirtualScreenSpec.aspectRatio]，由 [fitToAspect] 在扣掉
     *    [CHROME_HEIGHT_DP] 之后剩下的高度里求最大解；
     * 4. 小窗总高度 = 画面高 + [CHROME_HEIGHT_DP]；总高度若因取整超出上限，把画面高度再收 1 像素。
     *
     * 返回的画面尺寸就是预览视图的可用区域；比例等于虚拟屏比例时，FIT_CENTER 不再产生黑边。
     */
    fun overlaySize(spec: VirtualScreenSpec, screenWidthPx: Int, screenHeightPx: Int, density: Float): OverlaySize {
        val screenWidthCap = (screenWidthPx * MAX_SCREEN_WIDTH_RATIO).toInt()
        val screenHeightCap = (screenHeightPx * MAX_SCREEN_HEIGHT_RATIO).toInt()
        val panelWidth = minOf((NOMINAL_WIDTH_DP * density).toInt(), screenWidthCap).coerceAtLeast(1)
        val panelHeightCap = minOf((NOMINAL_HEIGHT_DP * density).toInt(), screenHeightCap).coerceAtLeast(1)
        val chrome = (CHROME_HEIGHT_DP * density).toInt().coerceAtLeast(0)
        val content = fitToAspect(panelWidth, (panelHeightCap - chrome).coerceAtLeast(1), spec.aspectRatio)
        val panelHeight = (content.second + chrome).coerceAtMost(panelHeightCap)
        // 取整可能让「画面高 + 预算」正好顶出上限 1 像素，此时如实回报最终能分给画面的高度，
        // 不谎报一个比实际可用空间更大的画面：预览视图仍按 FIT_CENTER 等比缩放，观感只差这 1 像素。
        val contentHeight = (panelHeight - chrome).coerceIn(1, content.second)
        return OverlaySize(panelWidth, panelHeight, content.first, contentHeight)
    }

    /** [overlaySize] 的结果：小窗总尺寸与其中画面区的尺寸，四个值都是像素。 */
    data class OverlaySize(val widthPx: Int, val heightPx: Int, val contentWidthPx: Int, val contentHeightPx: Int)
}
