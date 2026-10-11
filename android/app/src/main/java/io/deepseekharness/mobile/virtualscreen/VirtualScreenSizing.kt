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

        /** 按可用屏幕比例生成受内存预算约束的规格；方向可自动或显式指定。 */
        fun adaptive(screenWidth: Int, screenHeight: Int, density: Float, orientation: String): VirtualScreenSpec {
            require(orientation in setOf("auto", "portrait", "landscape")) { "屏幕方向无效" }
            val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
            var w = screenWidth.coerceAtLeast(200).toDouble()
            var h = screenHeight.coerceAtLeast(200).toDouble()
            if ((orientation == "portrait" && w > h) || (orientation == "landscape" && w < h)) {
                val previous = w; w = h; h = previous
            }
            val scale = minOf(1.0, 1440.0 / w, 2560.0 / h, kotlin.math.sqrt(2_073_600.0 / (w * h)), 2.0 / safeDensity)
            return VirtualScreenSpec((w * scale).toInt().coerceAtLeast(200), (h * scale).toInt().coerceAtLeast(200),
                (safeDensity * 160 * scale).toInt().coerceIn(120, 640))
        }

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
 * 启动前的规格换算：把「本次请求 + 偏好 + 真实屏幕」收敛成一个一定过 [VirtualScreenPolicy.dimensions] 的规格。
 *
 * 为什么需要这一层：[VirtualScreenSpec] 自己的范围（200..4096 像素 / 120..640 dpi）比启动闸门松，
 * 而闸门是设备端建显示前的最后一道校验（宽 320..1440、高 320..2560、宽×高 ≤ 2_073_600、dpi 160..640）。
 * 自适应算出的规格在极端屏幕比例下会踩到闸门，直接投递只会在设备端抛「副屏尺寸超出范围」，
 * 所以这里先按闸门收紧，再按比例缩到像素预算内 —— **只缩不放**，不会把竖屏预设压扁。
 *
 * 优先级（从最具体到最宽松，[resolve] 的实现与之一一对应）：
 * 1. 请求里显式 `adaptive: true` → 按屏幕自适应；
 * 2. 请求里三项尺寸齐全 → 用它；
 * 3. 偏好里自适应开关为真 → 按屏幕自适应（**偏好里的旧尺寸不参与**，否则开关永远不生效）；
 * 4. 偏好里三项尺寸齐全 → 用它；
 * 5. 都没有 → [VirtualScreenSpec.preset]（与改造前行为一致）。
 */
internal object VirtualScreenStart {
    /** 启动闸门的下限，与 [VirtualScreenPolicy.dimensions] 逐字对应。 */
    const val MIN_GATE_EDGE = 320
    const val MAX_GATE_WIDTH = 1440
    const val MAX_GATE_HEIGHT = 2560
    const val MAX_GATE_PIXELS = 2_073_600L
    const val MIN_GATE_DPI = 160

    /** 本次会话的覆盖项写进 Intent 的 extra 名；`adaptive`/`orientation` 是这次改造新加的。 */
    const val EXTRA_ADAPTIVE = "adaptive"
    const val EXTRA_ORIENTATION = "orientation"

    /**
     * 三项尺寸齐全（在规格层范围内）才成立，否则视为「这次没给自定义尺寸」。
     * 与 [VirtualScreenSpec.fromFields] 的「全有或全无」规则一致，只是这里用 null 表达「没有」。
     */
    fun complete(widthPx: Int, heightPx: Int, densityDpi: Int): VirtualScreenSpec? =
        if (widthPx in VirtualScreenSpec.MIN_EDGE..VirtualScreenSpec.MAX_EDGE &&
            heightPx in VirtualScreenSpec.MIN_EDGE..VirtualScreenSpec.MAX_EDGE &&
            densityDpi in VirtualScreenSpec.MIN_DPI..VirtualScreenSpec.MAX_DPI
        ) {
            VirtualScreenSpec(widthPx, heightPx, densityDpi)
        } else {
            null
        }

    /** 一次启动请求 + 一份偏好 + 真实屏幕参数 → 本次会话规格。 */
    fun resolve(
        request: VirtualScreenStartRequest,
        settings: VirtualScreenSettings,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
    ): VirtualScreenSpec {
        val orientation = request.orientation ?: settings.orientation
        val requested = complete(request.widthPx ?: 0, request.heightPx ?: 0, request.densityDpi ?: 0)
        val preferred = complete(settings.widthPx, settings.heightPx, settings.densityDpi)
        if (request.adaptive == true) return gate(VirtualScreenSpec.adaptive(screenWidth, screenHeight, density, orientation))
        if (requested != null) return gate(requested)
        if (settings.adaptive) return gate(VirtualScreenSpec.adaptive(screenWidth, screenHeight, density, orientation))
        if (preferred != null) return gate(preferred)
        return gate(VirtualScreenSpec.preset(landscape(screenWidth, screenHeight, orientation)))
    }

    /** 没有启动请求、只有偏好时的规格（设置页预览、桥的状态兜底）：等价于一次空请求。 */
    fun resolve(
        settings: VirtualScreenSettings,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
    ): VirtualScreenSpec = resolve(VirtualScreenStartRequest(target = ""), settings, screenWidth, screenHeight, density)

    /** `auto` 时按真实屏幕长边判断横竖；显式方向优先。 */
    private fun landscape(screenWidth: Int, screenHeight: Int, orientation: String): Boolean = when (orientation) {
        "portrait" -> false
        "landscape" -> true
        else -> screenWidth > screenHeight
    }

    /** 按最终规格判断这次会话是不是横屏（`auto` 时以规格长边为准，供小窗与旧版页面使用）。 */
    fun landscape(spec: VirtualScreenSpec, orientation: String): Boolean = when (orientation) {
        "portrait" -> false
        "landscape" -> true
        else -> spec.widthPx > spec.heightPx
    }

    /**
     * 把规格收进启动闸门：先夹边长与 dpi，再按像素预算等比缩小（dpi 同步缩小，
     * 与 [VirtualScreenSpec.adaptive] 的缩放口径一致：分辨率变了 dp 尺度也跟着变）。
     *
     * 只缩不放：预算内的小规格原样保留，避免「用户要一块 320×320 的小副屏」被放大成整屏。
     */
    fun gate(spec: VirtualScreenSpec): VirtualScreenSpec {
        var width = spec.widthPx.coerceIn(MIN_GATE_EDGE, MAX_GATE_WIDTH)
        var height = spec.heightPx.coerceIn(MIN_GATE_EDGE, MAX_GATE_HEIGHT)
        var densityDpi = spec.densityDpi.coerceIn(MIN_GATE_DPI, VirtualScreenSpec.MAX_DPI)
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_GATE_PIXELS) {
            val scale = kotlin.math.sqrt(MAX_GATE_PIXELS.toDouble() / pixels.toDouble())
            width = (width * scale).toInt().coerceIn(MIN_GATE_EDGE, MAX_GATE_WIDTH)
            height = (height * scale).toInt().coerceIn(MIN_GATE_EDGE, MAX_GATE_HEIGHT)
            densityDpi = (densityDpi * scale).toInt().coerceIn(MIN_GATE_DPI, VirtualScreenSpec.MAX_DPI)
            // 取整可能让乘积正好压在预算之上，逐像素退到预算内（两侧都到下限时不再退，闸门自身允许 320×320）。
            while (width.toLong() * height > MAX_GATE_PIXELS && height > MIN_GATE_EDGE) height--
            while (width.toLong() * height > MAX_GATE_PIXELS && width > MIN_GATE_EDGE) width--
        }
        return VirtualScreenSpec(width, height, densityDpi)
    }
}

/**
 * 把「本次会话规格 + 自适应开关 + 方向」一起写进 Intent，与 [VirtualScreenSpec.decode] / [putVirtualScreenSpec] 配对。
 *
 * 服务端按这些 extra 重建 [VirtualScreenStartRequest]，再走 [VirtualScreenStart.resolve]：
 * 也就是说，**换算只发生在服务侧一次**，页面不需要自己算尺寸，桥也不需要。
 */
fun Intent.putVirtualScreenStart(
    spec: VirtualScreenSpec,
    adaptive: Boolean = false,
    orientation: String = "auto",
): Intent = putVirtualScreenSpec(spec, VirtualScreenStart.landscape(spec, orientation))
    .putExtra(VirtualScreenStart.EXTRA_ADAPTIVE, adaptive)
    .putExtra(VirtualScreenStart.EXTRA_ORIENTATION, orientation)

/**
 * 悬浮小窗与预览画面的几何换算：让可视区域宽高比等于当前虚拟屏宽高比，从而消掉黑边。
 *
 * 黑边的直接来源是预览视图的 `ScaleType.FIT_CENTER`（`VirtualScreenService.kt` 里 `VirtualScreenPreview` 的 init 段）：
 * 它按「等比缩放并居中」显示位图，视图与位图比例不一致时，多出来的那一轴就是黑边。
 * 所以消黑边不需要改预览视图，只需要让小窗的内容区比例等于虚拟屏比例。
 *
 * 这里的函数都是纯算术，单位由调用方决定（服务里一律传像素），单测用同一份实现覆盖边界。
 */
object VirtualScreenWindow {
    /** 纯画面小窗最多占可用屏幕的 94% 宽、84% 高。 */
    const val MAX_SCREEN_WIDTH_RATIO = 0.94
    const val MAX_SCREEN_HEIGHT_RATIO = 0.84

    /** 用户可调到的最小小窗宽度（dp）：再窄就看不清画面，也点不准。 */
    const val MIN_OVERLAY_WIDTH_DP = 160f

    /**
     * 桥与偏好层收宽度时用的像素边界。
     *
     * 比 [MIN_OVERLAY_WIDTH_DP] 松是有意的：真正的下限要按设备密度与屏幕算，偏好层只挡明显不合理的值，
     * 免得把「换到小屏手机后需要重夹」这种事变成写不进去。
     */
    const val MIN_OVERLAY_WIDTH_PX = 120
    const val MAX_OVERLAY_WIDTH_PX = 4096

    /**
     * 右下角缩放手柄的触摸区边长（dp）。
     *
     * 与悬浮球对话小窗的手柄同值（`OverlayConversationSizePolicy.HANDLE_TOUCH_SIZE_DP`）：
     * 44dp 是系统建议的最小触摸目标，低于它在真机上会明显「点不中」，而点不中的代价是
     * 用户以为缩放功能坏了。
     */
    const val RESIZE_HANDLE_DP = 44f

    /** 手柄距画面右下角的视觉留白（dp）：手柄贴着角放但内缩一点，免得与系统手势条重叠。 */
    const val RESIZE_HANDLE_INSET_DP = 4f

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

    /** 画面即窗口，无工具栏预算，也不裁切应用内容。 */
    fun overlaySize(spec: VirtualScreenSpec, screenWidthPx: Int, screenHeightPx: Int, density: Float): OverlaySize =
        overlaySize(spec, screenWidthPx, screenHeightPx, density, null)

    /**
     * 同上，但可以带一个**用户指定的宽度**（像素）：`0` 或负数表示跟随屏幕最大。
     *
     * 为什么单独重载而不是给原函数加默认参数：改前那条签名被 20 个既有断言按位置调用，
     * 保留它可以让「没给宽度」这条路径与改造前逐字一致，缩放这条路走新函数。
     *
     * 指定的宽度仍要夹回 94% 屏宽：横竖屏切换后，上一次存的宽度可能已经超出新屏幕。
     * 夹完再走 [fitToAspect]，所以高度永远不超过 84% 屏高，比例也永远是画面比例（不产生黑边）。
     */
    fun overlaySize(spec: VirtualScreenSpec, screenWidthPx: Int, screenHeightPx: Int, density: Float, requestedWidthPx: Int?): OverlaySize {
        val maxWidth = (screenWidthPx * MAX_SCREEN_WIDTH_RATIO).toInt().coerceAtLeast(1)
        val maxHeight = (screenHeightPx * MAX_SCREEN_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        val target = requestedWidthPx?.takeIf { it > 0 }?.coerceAtMost(maxWidth)
        val content = if (target != null) fitToAspect(target, maxHeight, spec.aspectRatio)
        else fitToAspect(maxWidth, maxHeight, spec.aspectRatio)
        return OverlaySize(content.first, content.second, content.first, content.second)
    }

    /**
     * 拖动右下角手柄之后的画面宽度：只算宽度，高度由 [fitToAspect] 按画面比例反算，
     * 因此**缩放永远等比** —— 比例一变，`FIT_CENTER` 就会在画面四周补出黑边，触摸坐标也会跟着失真。
     *
     * 基准是**按下那一刻的宽度**而不是当前宽度：用当前宽度逐帧累加会把夹取结果也当成用户意图，
     * 手指拖回原处时窗口却回不到原大小（与悬浮球对话小窗的 `resized` 同一条理由）。
     *
     * 上限取三者最小值：94% 屏宽、按 84% 屏高反算出的宽度、[MAX_OVERLAY_WIDTH_PX]；
     * 下限 [minWidthPx] 与上限冲突时（超小屏、分屏里只剩几十像素）**上限优先** ——
     * 宁可窗口比 160dp 更窄，也不能让它有一半在屏幕外（那样连手柄都摸不到），
     * 但结果至少 1 像素，永远不会产生 0 尺寸窗口。
     */
    fun resizedOverlayWidth(
        startWidthPx: Int,
        deltaXPx: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        minWidthPx: Int,
        aspectRatio: Double,
    ): Int {
        val ratio = if (aspectRatio.isFinite() && aspectRatio > 0.0) aspectRatio else 1.0
        val widthCap = (screenWidthPx * MAX_SCREEN_WIDTH_RATIO).toInt().coerceAtLeast(1)
        val heightCap = (screenHeightPx * MAX_SCREEN_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        val widthFromHeight = Math.round(heightCap * ratio).toInt().coerceAtLeast(1)
        val upper = minOf(widthCap, widthFromHeight, MAX_OVERLAY_WIDTH_PX).coerceAtLeast(1)
        val lower = minWidthPx.coerceIn(1, upper)
        return (startWidthPx + deltaXPx).coerceIn(lower, upper)
    }

    /**
     * 长按进入拖动的时间门槛（毫秒）。**先长按再拖**：按下后的这段时间里所有触摸都照原样转发给副屏，
     * 于是副屏里的滚动、翻页不会在中途被抢成「拖窗」。
     */
    const val LONG_PRESS_DRAG_MILLIS = 350L

    /** 静置容差（dp）：约等于系统 `ViewConfiguration` 的 touch slop，手抖在这个范围内仍算「按住不动」。 */
    const val DRAG_SLOP_DP = 8f

    /**
     * 是否已经够格进入拖窗状态。两个条件缺一不可：
     * 1. 从按下到现在已达 [thresholdMillis]（长按）；
     * 2. 按下后的累计位移不超过 [slopPx]（按住不动，手抖允许）。
     *
     * 第 2 条是纯时间判定之外特意加的：慢速滚动一样会在 350ms 内超时，只看时间的话用户还在滚动、
     * 窗口就被抢走了。位移按直线距离判定（单轴比较会让斜向变迟钝）；条件不满足时这次手势仍然是一次
     * 普通触摸 —— 点击/滑动照旧转发给副屏。
     *
     * [heldMillis] 由调用方用单调时钟算好传进来（`SystemClock.uptimeMillis()` 之差），
     * 这里没有任何 Android 依赖，单测可以直接注入时间。[thresholdMillis] 为 0 或负数表示不要求长按。
     */
    fun dragArmed(
        heldMillis: Long,
        deltaX: Float,
        deltaY: Float,
        slopPx: Float,
        thresholdMillis: Long = LONG_PRESS_DRAG_MILLIS,
    ): Boolean {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return false
        if (slopPx <= 0f) return false
        if (kotlin.math.hypot(deltaX.toDouble(), deltaY.toDouble()) > slopPx) return false
        return heldMillis >= thresholdMillis.coerceAtLeast(0L)
    }

    /**
     * 拖动后的窗口左上角坐标：起点加位移，再夹进可用区域 ——
     * 左右不出屏，上不盖状态栏、下不盖导航栏，否则小窗会被拖到既看不见也点不到的位置。
     *
     * 可用区域比小窗还小时把上界折到 0（贴左上），不产生负边界；
     * 起点本身已在界外（换屏幕、旋转后窗口参数没刷新）时也会被这次夹取拉回界内。
     */
    fun dragPosition(
        startX: Int,
        startY: Int,
        deltaX: Int,
        deltaY: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        topInsetPx: Int,
        bottomInsetPx: Int,
        viewWidthPx: Int,
        viewHeightPx: Int,
    ): Pair<Int, Int> {
        val maxX = (screenWidthPx - viewWidthPx).coerceAtLeast(0)
        val minY = topInsetPx.coerceAtLeast(0)
        val maxY = (screenHeightPx - bottomInsetPx.coerceAtLeast(0) - viewHeightPx).coerceAtLeast(minY)
        return (startX + deltaX).coerceIn(0, maxX) to (startY + deltaY).coerceIn(minY, maxY)
    }

    /** [overlaySize] 的结果：小窗总尺寸与其中画面区的尺寸，四个值都是像素。 */
    data class OverlaySize(val widthPx: Int, val heightPx: Int, val contentWidthPx: Int, val contentHeightPx: Int)
}
