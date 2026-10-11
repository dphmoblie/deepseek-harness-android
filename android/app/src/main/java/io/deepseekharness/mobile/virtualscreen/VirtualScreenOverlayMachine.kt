package io.deepseekharness.mobile.virtualscreen

/**
 * 悬浮小窗的生命周期状态。它是一份**派生读数**，不是分支依据：
 * 真正的判定用「窗口在不在、是不是在重建、显示过没有、在不在结束」四件事（见 [VirtualScreenOverlayMachine.state]）。
 *
 * 之所以还要把它报出来：改造前这四件事只以 `overlay != null` / `ending.get()` 的形式散在服务字段里，
 * 「重复显示被吞掉」「置前重建中间态」「收起之后还能再显示」这些语义没有名字，只能靠读代码推。
 */
internal enum class OverlayState {
    /** 没有窗口：从未显示过，或显示失败回滚了。 */
    ABSENT,

    /** 窗口在窗口队列里。 */
    SHOWN,

    /** 已经摘下来、马上会重新挂上：`retainFrameLoopOnDetach` 的保留窗口就是这一段。 */
    SUSPENDED,

    /** 这次会话显示过，现在被主动收起（会话仍在，可以再显示）。 */
    HIDDEN,

    /** 会话已在结束：不再接受显示请求（真正的闸门是注入的会话判定，这里只记账）。 */
    STOPPING,

    /** 服务已销毁。 */
    DEAD,
}

/**
 * 悬浮小窗当前可用的屏幕区域（像素）：宽高是整屏，上下留出状态栏与导航栏。
 *
 * [sizingWidthPx]/[sizingHeightPx] 是**算小窗尺寸时用的那块区域**（整屏扣掉系统栏与刘海）：
 * 缩放要按同一块区域夹取，否则旋转后重新用 `displayMetrics` 算出来的边界会与当初建窗时不一致，
 * 手柄一拖就可能跳到一个当初不被允许的尺寸。
 */
internal data class OverlayArea(
    val widthPx: Int,
    val heightPx: Int,
    val topInsetPx: Int,
    val bottomInsetPx: Int,
    val sizingWidthPx: Int,
    val sizingHeightPx: Int,
)

/** 悬浮小窗的几何：位置 + 尺寸 + 可用区域。拖动与缩放改的是前四个值，夹取用的是 [area]。 */
internal data class OverlayLayout(
    val x: Int,
    val y: Int,
    val widthPx: Int,
    val heightPx: Int,
    val area: OverlayArea,
)

/**
 * 窗口生命周期要调用方去做的一件事。窗口本身（`View` / `WindowManager` / `LayoutParams`）留在这里，
 * 机器只决定「做什么、按什么顺序做、失败之后做什么」。
 */
internal sealed interface OverlayEffect {
    /** 建窗。由 `showOverlay` 专门消费（要建 `VirtualScreenFrame` 与窗口参数），通用执行器遇到它是空操作。 */
    data class AddView(val layout: OverlayLayout) : OverlayEffect

    /** 摘窗（`WindowManager.removeView`）；异常由调用方吞掉 —— 拖到一半被系统移除是收尾竞态，不该崩。 */
    object RemoveView : OverlayEffect

    /**
     * 摘下来立刻重挂（`removeViewImmediate` + `addView`）：同层窗口只按「谁后 addView」分层，
     * 这两步必须在同一帧里完成，否则会看到一次空窗。失败时调用方回报 [VirtualScreenOverlayMachine.viewReAddFailed]。
     */
    object RemoveAndReAddView : OverlayEffect

    /** 改窗口参数（`updateViewLayout`）。 */
    data class UpdateLayout(val x: Int, val y: Int, val widthPx: Int, val heightPx: Int) : OverlayEffect

    /** 置位「保留取帧循环」（`VirtualScreenPreview.retainFrameLoopOnDetach = true`）。 */
    object KeepFrameLoop : OverlayEffect

    /** 复位「保留取帧循环」（`VirtualScreenPreview.retainFrameLoopOnDetach = false`）。 */
    object ReleaseFrameLoop : OverlayEffect

    /**
     * 显式释放已摘下控件的帧循环：对应 `VirtualScreenPreview.releaseOnDetach(retain = false)`
     * （先把未完成的触摸补一个 cancel，再停拍子、关线程池、清画面）。
     *
     * 只有「重挂失败」这一支需要它：那次 `removeViewImmediate` 之后 `addView` 抛异常，视图已经摘下，
     * 不会再收到 detach 回调，不显式释放就会一直留着取帧线程与调度。
     */
    object ReleaseDetachedFrameLoop : OverlayEffect
}

/**
 * 副屏悬浮小窗的生命周期状态机（纯 Kotlin：时钟、权限、会话、几何读数都是注入点，假时钟 + 假窗口读数即可覆盖）。
 *
 * 为什么抽出来：`showOverlay` / `bringOverlayToFront` / `applyOverlayWidth` / `hideOverlay` 四个方法里的
 * 幂等判定、节流、失败回滚与 effect 顺序，原先和 `WindowManager`、`View`、`LayoutParams` 缠在一起，
 * 只能靠真机观察。抽出来之后，「重复显示被吞掉」「置前 1200 毫秒节流」「重挂失败必须显式释放帧循环」
 * 「收起的顺序是先复位保留开关再摘窗」这些语义可以逐事件断言，且**不改变任何时序**。
 *
 * 边界（这里只做判定与顺序，不做别的）：
 * - 不碰 `View` / `WindowManager` / `LayoutParams`：窗口句柄与窗口参数留在服务里，按 effect 落地；
 * - 几何的权威在这一侧（[layout]）：服务把 effect 里的值写回那个 `LayoutParams` 实例，不再自己做夹取判断；
 * - 不读系统时钟、不读设置、不读 SharedPreferences：全部由调用方注入；
 * - 窗口是否真的可见、叠放顺序对不对，这里验不了（那是系统的事），这里只保证「发出去的 effect 顺序」对。
 *
 * 与调用方的契约（两处都必须回报，否则状态会停在中间态）：
 * - [show] 之后必须回一个 [viewAdded] 或 [viewAddFailed]；
 * - [bringToFront] 之后必须回一个 [viewReAdded] 或 [viewReAddFailed]。
 *
 * @param uptimeMillis 单调时钟（真机传 `SystemClock.uptimeMillis`），置前节流按它算。
 * @param canDrawOverlays 悬浮窗权限（真机传 `Settings.canDrawOverlays(this)`）。
 * @param sessionReady 会话是否就绪（真机传 `session.isNotEmpty() && !ending.get()`）。
 * @param density 显示密度（真机传 `resources.displayMetrics.density`），缩放按它算最小宽度与像素尺寸。
 * @param spec 本次会话的副屏规格（真机传 `VirtualScreenSpec.decodeState(snapshot)`）：小窗按它换算画面比例。
 * @param viewWidthPx 视图当前量出来的宽度；还没量出来时返回 0，夹取会退回窗口参数里的尺寸（与改造前一致）。
 * @param viewHeightPx 同上，高度。
 * @param frontThrottleMillis 两次「重新挂到最前」之间的最小间隔。
 */
internal class VirtualScreenOverlayMachine(
    private val uptimeMillis: () -> Long,
    private val canDrawOverlays: () -> Boolean,
    private val sessionReady: () -> Boolean,
    private val density: () -> Float,
    private val spec: () -> VirtualScreenSpec,
    private val viewWidthPx: () -> Int,
    private val viewHeightPx: () -> Int,
    private val frontThrottleMillis: Long,
) {
    /** 当前窗口几何；null 表示没有窗口（未显示、显示失败、已收起、已销毁）。 */
    private var window: OverlayLayout? = null

    /** [show] 已经算好、等 [viewAdded] 落地的布局。 */
    private var pending: OverlayLayout? = null

    /** 置前重建是否在进行中：这一段里窗口对象还在，但已经不在窗口队列里。 */
    private var suspended = false

    /** 这次会话是否显示过（收起之后仍为真）：只为区分 ABSENT 与 HIDDEN。 */
    private var shown = false

    /** 会话是否已在结束（镜像服务的 `ending`）：只为报 STOPPING，闸门仍由 [sessionReady] 把关。 */
    private var stopping = false

    /** 服务是否已销毁。 */
    private var destroyed = false

    /**
     * 上一次把悬浮小窗重新挂到窗口队列末端的时间（[uptimeMillis]）。
     * 重挂会带来一次可见的重建，所以只允许「用户点了一下小窗」这种低频动作触发，并且节流。
     */
    private var lastFrontAtMs = 0L

    /** 拖动起点与最近一次生效的位置：位移换算成绝对坐标时用，避免把节流丢掉的中间位移累计错。 */
    private var dragOriginX = 0
    private var dragOriginY = 0
    private var dragLastX = 0
    private var dragLastY = 0
    private var dragging = false

    /** 生命周期状态读数，见 [OverlayState]：终止态 > 中间态 > 窗口在不在 > 显示过没有。 */
    val state: OverlayState
        get() = when {
            destroyed -> OverlayState.DEAD
            stopping -> OverlayState.STOPPING
            suspended -> OverlayState.SUSPENDED
            window != null -> OverlayState.SHOWN
            shown -> OverlayState.HIDDEN
            else -> OverlayState.ABSENT
        }

    /** 当前窗口几何；没有窗口时为 null。 */
    val layout: OverlayLayout?
        get() = window

    /**
     * 显示请求。[layout] 是**推迟求值**的布局：只在三道闸门都过了之后才算 ——
     * 与改造前「先 `check` 权限、再 `check` 会话、再 `if (overlay != null) return`、最后才算尺寸」逐字同序，
     * 重复显示时连一次屏幕读数都不会发生。
     *
     * 两道闸门的文案与异常类型都照原样：调用方（悬浮球、设置页）就是靠它们给用户提示的。
     */
    fun show(layout: () -> OverlayLayout): List<OverlayEffect> {
        check(canDrawOverlays()) { "请先授予悬浮窗权限" }
        check(sessionReady()) { "副屏尚未就绪" }
        // 幂等只认「窗口在不在」：原来就是 `if (overlay != null) return`，重挂中间态里窗口对象也在。
        if (window != null) return emptyList()
        val computed = layout()
        pending = computed
        return listOf(OverlayEffect.AddView(computed))
    }

    /** [OverlayEffect.AddView] 落地：窗口真的进了队列，置前节流随之归零（原成功分支的最后一句）。 */
    fun viewAdded() {
        window = pending
        pending = null
        shown = true
        lastFrontAtMs = 0L
    }

    /**
     * [OverlayEffect.AddView] 失败：回滚到「没有窗口」。
     *
     * 与改造前一致：那时 `overlay` 还没赋值，catch 里只把 `preview` 清掉；节流计时也**不动**
     * （原 catch 里没有 `lastFrontAtMs = 0`，只是这条路上它本来就已经是 0）。
     */
    fun viewAddFailed() {
        pending = null
    }

    /**
     * 收起小窗。顺序与改造前一致：**先撤掉「保留取帧循环」的开关再摘窗口** ——
     * 否则这一次 `removeView` 触发的 detach 不会关线程，小窗收起来之后取帧线程与调度会一直活着。
     *
     * 摘窗与复位都只在这个窗口真的存在时才有意义；节流计时无论有没有窗口都归零（与原来一样无条件清）。
     */
    fun hide(): List<OverlayEffect> {
        val effects = mutableListOf<OverlayEffect>()
        if (window != null) {
            effects += OverlayEffect.ReleaseFrameLoop
            effects += OverlayEffect.RemoveView
        }
        window = null
        pending = null
        suspended = false
        dragging = false
        lastFrontAtMs = 0L
        return effects
    }

    /**
     * 把副屏悬浮窗重新放到窗口队列末端，让刚点过的那个窗口成为同层里最后加入的那个。
     *
     * 节流窗口内的请求直接吞掉；**被吞掉时也不做任何记账**（与改造前一致：`return` 在 `lastFrontAtMs = now` 之前）。
     * 通过节流之后即使预览控件不在手（原来紧接着的 `preview ?: return`），这次节流窗口照样被吃掉 ——
     * 那是改造前的既有行为，这里照搬。
     */
    fun bringToFront(): List<OverlayEffect> {
        if (window == null) return emptyList()
        val now = uptimeMillis()
        if (now - lastFrontAtMs < frontThrottleMillis) return emptyList()
        lastFrontAtMs = now
        suspended = true
        // 保留开关先置位、重挂、再在 finally 里复位：取帧线程与调度因此活过这次 detach。
        return listOf(OverlayEffect.KeepFrameLoop, OverlayEffect.RemoveAndReAddView, OverlayEffect.ReleaseFrameLoop)
    }

    /** [OverlayEffect.RemoveAndReAddView] 成功：回到「窗口在队列里」。 */
    fun viewReAdded() {
        suspended = false
    }

    /**
     * [OverlayEffect.RemoveAndReAddView] 失败：等价于改造前的
     * `catch { hideOverlay(); image.releaseOnDetach(retain = false) } finally { image.retainFrameLoopOnDetach = false }`。
     *
     * 四条 effect 的顺序就是原来的执行顺序：复位保留开关（hideOverlay 第一句）→ 摘窗 → 显式释放帧循环 →
     * finally 再复位一次。调用方必须用**重挂开始时那个**预览控件的引用去执行它们（`hideOverlay` 会把
     * `preview` 字段清空，抓晚了就释放不到真正需要释放的对象）。
     */
    fun viewReAddFailed(): List<OverlayEffect> {
        suspended = false
        return hide() + OverlayEffect.ReleaseDetachedFrameLoop + OverlayEffect.ReleaseFrameLoop
    }

    /**
     * 按新宽度等比缩放悬浮小窗（设置页改宽度、拖手柄都走这里）。
     *
     * 只认宽度，高度由 [VirtualScreenWindow.overlaySize] 按画面比例反算 —— 比例一变，
     * `FIT_CENTER` 就会在画面四周补黑边，触摸坐标也会跟着失真。
     * 位置保持不动，只做一次夹取：缩小后可能离边缘更远（无需处理），放大后才可能越界。
     * 尺寸没变时什么都不做（原来就是这一步 `return`）。
     */
    fun resize(widthPx: Int): List<OverlayEffect> {
        val current = window ?: return emptyList()
        val area = current.area
        val size = VirtualScreenWindow.overlaySize(spec(), area.sizingWidthPx, area.sizingHeightPx, density(), widthPx)
        if (current.widthPx == size.widthPx && current.heightPx == size.heightPx) return emptyList()
        val position = VirtualScreenWindow.dragPosition(
            current.x,
            current.y,
            0,
            0,
            area.widthPx,
            area.heightPx,
            area.topInsetPx,
            area.bottomInsetPx,
            size.widthPx,
            size.heightPx,
        )
        window = current.copy(x = position.first, y = position.second, widthPx = size.widthPx, heightPx = size.heightPx)
        return listOf(OverlayEffect.UpdateLayout(position.first, position.second, size.widthPx, size.heightPx))
    }

    /** 手势被判定为拖动；返回 false 表示当前没有可拖的窗口，这次手势继续按原语义透传。 */
    fun beginDrag(): Boolean {
        val current = window ?: return false
        dragOriginX = current.x
        dragOriginY = current.y
        dragLastX = current.x
        dragLastY = current.y
        dragging = true
        return true
    }

    /**
     * 拖动过程（调用方已按 [MOVE_INTERVAL_MILLIS] 节流）：位移一律相对**按下那一刻的窗口原点**换算，
     * 而不是累加上一次的位置 —— 这样被节流丢掉的中间点不会让窗口越拖越偏。
     */
    fun dragBy(deltaX: Int, deltaY: Int): List<OverlayEffect> {
        if (!dragging) return emptyList()
        val current = window ?: return emptyList()
        val area = current.area
        // 视图还没量出尺寸时退回窗口参数里的尺寸，避免用 0 去算夹取上界。
        val viewWidth = viewWidthPx().let { if (it > 0) it else current.widthPx }
        val viewHeight = viewHeightPx().let { if (it > 0) it else current.heightPx }
        val (x, y) = VirtualScreenWindow.dragPosition(
            dragOriginX,
            dragOriginY,
            deltaX,
            deltaY,
            area.widthPx,
            area.heightPx,
            area.topInsetPx,
            area.bottomInsetPx,
            viewWidth,
            viewHeight,
        )
        if (x == dragLastX && y == dragLastY) return emptyList()
        dragLastX = x
        dragLastY = y
        window = current.copy(x = x, y = y)
        return listOf(OverlayEffect.UpdateLayout(x, y, current.widthPx, current.heightPx))
    }

    /** 拖动结束；参数是最后一次位移，用于补发被节流丢掉的末位置（没在拖动时什么都不做）。 */
    fun endDrag(deltaX: Int, deltaY: Int): List<OverlayEffect> {
        val effects = if (dragging) dragBy(deltaX, deltaY) else emptyList()
        dragging = false
        return effects
    }

    /**
     * 会话开始结束（对应 `requestStop()` 里 `ending.set(true)`）。
     *
     * 只记账：真正拦住显示请求的是注入的 [sessionReady]，窗口本身也**不摘** ——
     * 改造前 `requestStop` 只置位，小窗会一直留到 `onDestroy` 才收起。
     */
    fun stop() {
        stopping = true
    }

    /**
     * 服务销毁（对应 `onDestroy` 里 `ending.set(true)` + `removeCallbacks(health)` + `hideOverlay()`）。
     * 返回收起窗口要执行的 effect，顺序与 [hide] 相同。
     */
    fun destroy(): List<OverlayEffect> {
        stopping = true
        destroyed = true
        return hide()
    }
}
