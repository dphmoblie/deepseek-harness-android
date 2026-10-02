package io.deepseekharness.mobile.overlay

/**
 * 二级悬浮球与对话小窗尺寸的**纯决策逻辑**：不依赖 Android API，便于单元测试。
 *
 * 与 [OverlayBallPolicy] 同一条硬约束：悬浮球与二级球都由前台服务承载，
 * 只能提高本应用进程被系统回收的优先级，**不能阻止** Android 或厂商系统在内存压力、
 * 电量策略或后台限制下结束进程 —— 本策略不做也无法做保活承诺，球可能随时随进程一起消失。
 *
 * 本文件里的所有长度单位都是**传入的像素值**（由调用方用自己的 density 换算），
 * 因此这里不需要 Resources，也就没有「单测里跑不起来」的 Android 依赖。
 */
object OverlayBallSecondaryPolicy {

    /** 二级球之间的间距（与主球的间距同用此值）。 */
    const val GAP_DP = 8

    /** 二级球的**可点击**直径下限：低于它就不是一个可用的触摸目标。 */
    const val MIN_TOUCH_SIZE_DP = 40

    /** 二级球的可视直径。 */
    const val VISUAL_SIZE_DP = 36

    /** 二级球实际占用的窗口边长：取视觉尺寸与最小可点击尺寸的较大者。 */
    const val BUTTON_SIZE_DP = VISUAL_SIZE_DP

    /** 二级球与屏幕可用区域边缘的最小留白。 */
    const val SAFE_MARGIN_DP = 8

    /** 二级球的父窗口尺寸（覆盖全屏的透明层，仅用于承载按钮，不拦截触摸）。 */
    const val LAYER_SIZE_DP = 0

    /**
     * 二级球相对主球的位置。
     *
     * 全部是相对偏移而不是绝对坐标：主球拖动时按键位复用同一组偏移量重算即可，
     * 不需要缓存每个按钮的位置（缓存会与拖动中的坐标更新并发，产生「球动了、按钮没动」）。
     *
     * @param anchorX 按钮相对于父窗口左边缘的 x（父窗口与主球同宽同 x）。
     * @param firstY 第一个按钮相对父窗口顶边的 y。
     * @param stepY 相邻按钮之间的步进。
     * @param stacked 是否向下排列（false 表示向上，即屏幕底部放不下时的翻转）。
     */
    data class Layout(
        val anchorX: Int,
        val firstY: Int,
        val stepY: Int,
        val stacked: Boolean,
    )

    /**
     * 计算二级球的布局。
     *
     * 策略：
     *  - **横向**：主球在左半屏时按钮排在球的右侧，否则排在左侧；两侧都放不下时收进屏幕内。
     *    与 [OverlayBallPolicy.menuPosition] 同一条思路，只是把「菜单」换成了「一列按钮」。
     *  - **纵向**：默认从主球顶边开始**向下**排；下方剩余空间不够时整体**向上**排，
     *    仍然不够则夹取，保证至少第一个按钮完整可见。
     *  - 全组按钮再按 [SAFE_MARGIN_DP] 做一次夹取，避免压到状态栏 / 手势条区域。
     *
     * @param reserveTop 顶部不可用高度（状态栏等），像素。
     * @param reserveBottom 底部不可用高度（手势条 / 导航栏），像素。
     */
    fun layout(
        ballX: Int,
        ballY: Int,
        ballSize: Int,
        buttonSize: Int,
        count: Int,
        screenWidth: Int,
        screenHeight: Int,
        gap: Int,
        reserveTop: Int,
        reserveBottom: Int,
    ): Layout {
        val step = buttonSize + gap
        val right = ballX + ballSize + gap
        val left = ballX - gap - buttonSize
        val anchorX = when {
            right + buttonSize <= screenWidth -> right
            left >= 0 -> left
            else -> (screenWidth - buttonSize).coerceAtLeast(0)
        }
        val usedHeight = totalHeight(buttonSize, count, gap)
        val safeTop = reserveTop.coerceAtLeast(0)
        val safeBottom = (screenHeight - reserveBottom.coerceAtLeast(0)).coerceAtLeast(safeTop)
        val downFits = ballY + usedHeight <= safeBottom
        val upStart = ballY - gap - usedHeight
        val upFits = upStart >= safeTop
        return when {
            // 优先向下：手指从左上方滑入时，向下排列更符合「贴着球展开」的直觉。
            downFits -> Layout(anchorX, ballY, step, stacked = true)
            upFits -> Layout(anchorX, upStart, step, stacked = false)
            // 两侧都放不下（小屏或主球贴底）：仍然向下排，交给 clampButton 把整组夹进安全区。
            else -> Layout(anchorX, clampWithin(ballY, safeTop, safeBottom - usedHeight), step, stacked = true)
        }
    }

    /** 整组按钮在纵向上占用的总高度（n 个按钮 + n-1 个间距）。 */
    fun totalHeight(buttonSize: Int, count: Int, gap: Int): Int {
        if (count <= 0) return 0
        return count * buttonSize + (count - 1) * gap
    }

    /**
     * 某个按钮相对屏幕的绝对左上角坐标。
     *
     * 与 [layout] 分开，是为了让「拖动主球」这条热路径只做一次 [layout] 加 N 次本函数，
     * 而不是每次都重新推导排列方向。
     */
    fun buttonPosition(
        layout: Layout,
        index: Int,
        layerX: Int,
        layerY: Int,
        buttonSize: Int,
        screenWidth: Int,
        screenHeight: Int,
        reserveTop: Int,
        reserveBottom: Int,
    ): Pair<Int, Int> {
        val x = layerX + layout.anchorX
        val y = layerY + layout.firstY + index * layout.stepY
        return clampButton(
            x, y, buttonSize, screenWidth, screenHeight, reserveTop, reserveBottom,
        )
    }

    /**
     * 单个按钮的夹取：保证完全落在「安全可用区域」内。
     *
     * 屏幕比按钮还小时收敛到安全区左上角而不是负数 —— 负的 LayoutParams 坐标会让
     * WindowManager 抛异常（与 [OverlayBallPolicy.clampPosition] 同一个理由）。
     */
    fun clampButton(
        x: Int,
        y: Int,
        buttonSize: Int,
        screenWidth: Int,
        screenHeight: Int,
        reserveTop: Int,
        reserveBottom: Int,
    ): Pair<Int, Int> {
        val maxX = (screenWidth - buttonSize).coerceAtLeast(0)
        val safeTop = reserveTop.coerceAtLeast(0)
        val maxY = (screenHeight - reserveBottom - buttonSize).coerceAtLeast(safeTop)
        return x.coerceIn(0, maxX) to y.coerceIn(safeTop, maxY)
    }

    /**
     * 二级球上的动作。
     *
     * 放在策略里而不是服务里，是为了让「按钮顺序 → 动作」这条映射能被单测钉住：
     * 顺序错一位不会崩，但会把「隐藏悬浮球」和「关闭无障碍」换位置，
     * 后者是一个用户很难自己恢复的系统设置变更。
     */
    enum class Choice {
        /** 回到应用（走 [io.deepseekharness.mobile.KeepAliveEntryActivity] 转发入口）。 */
        RETURN_TO_APP,

        /** 隐藏悬浮球：关闭开关并停止服务。 */
        HIDE_BALL,

        /** 关闭无障碍服务；未开启时只反馈、不做任何动作。 */
        DISABLE_ACCESSIBILITY,
    }

    /** 二级球从内到外的固定顺序。 */
    val CHOICE_ORDER: List<Choice> = listOf(
        Choice.RETURN_TO_APP,
        Choice.HIDE_BALL,
        Choice.DISABLE_ACCESSIBILITY,
    )

    /**
     * 长按主球时应当做什么。
     *
     * 这是二级球展开/收起的**全部状态机**：没有独立的 `expanded` 布尔字段，
     * 「是否展开」就等于「窗口里有没有二级球视图」。多一个字段就多一种自相矛盾的可能
     * （字段说展开、视图被系统移除了），而视图的存在性是唯一可信来源。
     */
    enum class Decision { EXPAND, COLLAPSE }

    fun toggleChoice(expanded: Boolean): Decision =
        if (expanded) Decision.COLLAPSE else Decision.EXPAND

    /**
     * 无障碍按钮被点时的结论。
     *
     * 只有「服务真的连着」才动手：`disableSelf()` 只在当前进程拥有该服务时有效，
     * 已经关掉时再调用什么都不会发生 —— 那就是用户说的「点了没反应」，
     * 因此这一分支必须由调用方给出明确反馈（提示语在 `overlay_ball_secondary_accessibility_disabled_toast`）。
     */
    enum class AccessibilityAction { DISABLE_SELF, ALREADY_OFF }

    fun accessibilityState(serviceConnected: Boolean): AccessibilityAction =
        if (serviceConnected) AccessibilityAction.DISABLE_SELF else AccessibilityAction.ALREADY_OFF

    /**
     * 系统设置页对「服务是否开启」的读数是**静态**的([io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService.current])，
     * 而外壳每 5 秒轮询一次；`disableSelf()` 之后到外壳刷新之间存在一段不一致窗口。
     * 这里刻意只回答「按钮此刻该显示什么」，不去装作已经同步。
     */
    fun showsDisabledState(serviceConnected: Boolean): Boolean = !serviceConnected

    /**
     * 命中区是否达标：视觉尺寸小的时候必须靠 padding / touch delegate 补足到
     * [MIN_TOUCH_SIZE_DP]，否则按钮在真机上「点不中」。
     */
    fun isHitTargetEnough(hitSize: Int, minTouchSize: Int): Boolean = hitSize >= minTouchSize

    /**
     * 二级球的可视背景尺寸（居中放在 [BUTTON_SIZE_DP] 的触摸目标里）。
     */
    fun visualSize(buttonSize: Int, minTouchSize: Int, visualSizePx: Int): Int =
        minOf(visualSizePx, buttonSize).coerceAtMost(minTouchSize)

    private fun clampWithin(value: Int, min: Int, max: Int): Int =
        if (max < min) min else value.coerceIn(min, max)
}

/**
 * 对话小窗尺寸的纯策略。
 *
 * 尺寸以**像素**为单位存盘并由调用方换算，理由见类注释；这里只关心三件事：
 * 夹取、手柄几何、以及「存过的那一组尺寸在当前屏幕下还算不算数」。
 *
 * 一条刻意选择的取舍：**最大尺寸用 90% 的比例，但最小值是硬下限**。
 * 屏幕极小（分屏、平板自由窗口、外接小屏）时二者会冲突，此时下限优先 ——
 * 一个 90% 之下的窗口装不下对话内容，而一个略超比例但可用的窗口是用户要的。
 * 冲突的下限只有 [MIN_WIDTH_DP] / [MIN_HEIGHT_DP] 两条，因此不会失控。
 */
object OverlayConversationSizePolicy {

    /** 最小可用的宽度（dp）。低于它对话正文基本没法读。 */
    const val MIN_WIDTH_DP = 240

    /** 最小可用的高度（dp）。 */
    const val MIN_HEIGHT_DP = 360

    /** 在未指定自定义尺寸时的默认值（dp），与改动前的固定尺寸一致。 */
    const val DEFAULT_WIDTH_DP = 360

    const val DEFAULT_HEIGHT_DP = 560

    /** 窗口最多占屏幕可用区域的比例。 */
    const val MAX_SCREEN_RATIO = 0.9f

    /**
     * 缩放手柄的可点击边长（dp）。
     *
     * 等于二级球的下限：低于 40dp 在真机上会明显「点不中」，而手柄是拖动起点，
     * 点不中的代价是用户以为这个功能坏了。
     */
    const val HANDLE_TOUCH_SIZE_DP = 44

    /** 手柄的可视圆点直径（dp），居中放在 44dp 的触摸目标里。 */
    const val HANDLE_VISUAL_SIZE_DP = 28

    /** 手柄距面板右下角的视觉留白（dp）。 */
    const val HANDLE_INSET_DP = 4

    /** 存盘的尺寸是否还配得上当前屏幕。 */
    enum class Stored {
        /** 可用：调用方应当直接采用并夹取。 */
        USABLE,

        /** 没有存过（或存了半截）：用默认值。 */
        ABSENT,

        /** 存过，但屏幕换了（旋转、分屏、外接屏）：用默认值，避免一张「横屏时的宽窗」竖屏变成怪形状。 */
        STALE,
    }

    /** 夹取后的宽度：不小于最小宽度，也不超过可用区域的 [MAX_SCREEN_RATIO]。 */
    fun clampWidth(
        width: Int,
        availableWidth: Int,
        minWidth: Int = MIN_WIDTH_DP,
        maxRatio: Float = MAX_SCREEN_RATIO,
    ): Int {
        val ratioMax = (availableWidth * maxRatio).toInt()
        val max = if (ratioMax < minWidth) minWidth else ratioMax
        return width.coerceIn(minWidth, max)
    }

    /** 夹取后的高度，规则同 [clampWidth]。 */
    fun clampHeight(
        height: Int,
        availableHeight: Int,
        minHeight: Int = MIN_HEIGHT_DP,
        maxRatio: Float = MAX_SCREEN_RATIO,
    ): Int {
        val ratioMax = (availableHeight * maxRatio).toInt()
        val max = if (ratioMax < minHeight) minHeight else ratioMax
        return height.coerceIn(minHeight, max)
    }

    /**
     * 手柄的左上角坐标（相对面板窗口）。
     *
     * 放在右下角：那是「放大」方向最自然的起点，也是用户对缩放手柄的既有预期。
     * [HANDLE_INSET_DP] 让它稍微内缩，避免与系统手势条区域重叠。
     */
    fun handlePosition(
        panelWidth: Int,
        panelHeight: Int,
        handleSize: Int,
        inset: Int,
    ): Pair<Int, Int> {
        val x = (panelWidth - handleSize - inset).coerceAtLeast(0)
        val y = (panelHeight - handleSize - inset).coerceAtLeast(0)
        return x to y
    }

    /**
     * 手柄是否落在某个点附近（用于判断一次按下是否起手于手柄）。
     *
     * 用中心点 + 半径而不是矩形包含：手柄是圆点，矩形判定会让「贴着圆点外侧一点」
     * 也算命中，而那里其实是面板内容。
     */
    fun hitsHandle(
        touchX: Float,
        touchY: Float,
        handleX: Int,
        handleY: Int,
        handleSize: Int,
        tolerance: Float = 0f,
    ): Boolean {
        val centerX = handleX + handleSize / 2f
        val centerY = handleY + handleSize / 2f
        val radius = handleSize / 2f + tolerance
        val dx = touchX - centerX
        val dy = touchY - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    /**
     * 拖动后的新尺寸：以「按下时的尺寸 + 手指位移」计算，并夹取到合法范围。
     *
     * 以**按下时的尺寸**而不是当前尺寸为基准，是为了让拖动可逆：
     * 用当前尺寸逐帧累加会把夹取结果也当成用户意图，手指回到原处时窗口却回不到原大小。
     */
    fun resized(
        startWidth: Int,
        startHeight: Int,
        deltaX: Int,
        deltaY: Int,
        availableWidth: Int,
        availableHeight: Int,
        minWidth: Int = MIN_WIDTH_DP,
        minHeight: Int = MIN_HEIGHT_DP,
        maxRatio: Float = MAX_SCREEN_RATIO,
    ): Pair<Int, Int> = clampWidth(startWidth + deltaX, availableWidth, minWidth, maxRatio) to
        clampHeight(startHeight + deltaY, availableHeight, minHeight, maxRatio)

    /**
     * 判定存盘的尺寸在当前屏幕下还算不算数。
     *
     * 判定依据是**存盘时记录的屏幕宽高**是否与当前一致：一致就直接用（用户上次调好的大小
     * 就是他想要的），不一致就退回默认值（那多半是旋转或分屏，硬套过去会得到怪形状）。
     */
    fun storedState(
        storedWidth: Int?,
        storedHeight: Int?,
        storedScreenWidth: Int?,
        storedScreenHeight: Int?,
        screenWidth: Int,
        screenHeight: Int,
    ): Stored {
        if (storedWidth == null || storedHeight == null) return Stored.ABSENT
        if (storedWidth <= 0 || storedHeight <= 0) return Stored.ABSENT
        if (storedScreenWidth == null || storedScreenHeight == null) return Stored.STALE
        return if (storedScreenWidth == screenWidth && storedScreenHeight == screenHeight) {
            Stored.USABLE
        } else {
            Stored.STALE
        }
    }
}
