package io.deepseekharness.mobile.virtualscreen

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * 副屏画面的统一外壳：一枚 [VirtualScreenPreview] + 右下角的缩放手柄。
 *
 * 为什么要有这一层：
 * 1. 悬浮小窗与原生预览页此前各自往自家容器里塞一个 `VirtualScreenPreview`：控件类相同，
 *    但「多大、能不能调」两处行为不同 —— 用户看到的就是两个不一样的东西。收成一个外壳之后，
 *    两处只是把这个外壳放进不同宿主（窗口 / 页面），画面尺寸与缩放手势从此完全一致。
 * 2. 手柄**作为外壳的子视图**，而不是再来一个悬浮窗：同 type 的悬浮窗之间只按「谁后 addView」
 *    分层，多一个手柄窗口就要额外处理它自己的置顶、跟随拖动、失效清理；作为子视图这些问题都不存在。
 *
 * 代价说明（有意取舍）：手柄占住画面右下角 [VirtualScreenWindow.RESIZE_HANDLE_DP] 见方的一小块
 * （内缩 [VirtualScreenWindow.RESIZE_HANDLE_INSET_DP]），那块区域的触摸不再透传给副屏里的应用。
 * 这与悬浮球对话小窗的手柄取舍一致；不想让出这块区域时，可以在设置页改宽度。
 */
internal class VirtualScreenFrame(context: Context) : FrameLayout(context) {

    /** 画面控件：取帧、上屏、触摸透传与「长按后再拖」的判定都在它里面。 */
    val preview = VirtualScreenPreview(context)

    private val density = resources.displayMetrics.density
    private val handleSizePx = (VirtualScreenWindow.RESIZE_HANDLE_DP * density).toInt().coerceAtLeast(24)
    private val handleInsetPx = (VirtualScreenWindow.RESIZE_HANDLE_INSET_DP * density).toInt()

    private val handle = View(context).apply {
        background = GradientDrawable().apply {
            setColor(HANDLE_COLOR)
            cornerRadius = handleSizePx / 3f
        }
        contentDescription = "拖动调整副屏画面大小"
    }

    init {
        // 画面铺满外壳，手柄最后加入 ⇒ 在右下角压在画面之上，只吃它自己那一小块触摸。
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(handle, LayoutParams(handleSizePx, handleSizePx, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = handleInsetPx
            bottomMargin = handleInsetPx
        })
    }

    /** 装载手柄的拖动逻辑；每个宿主只调一次（服务与预览页各自一份手势）。 */
    fun installResize(gesture: VirtualScreenResizeGesture) {
        handle.setOnTouchListener { _, event -> gesture.onTouch(event) }
    }

    private companion object {
        /** 手柄底色：半透明白，压在画面上仍看得见，又不至于挡住内容。 */
        const val HANDLE_COLOR = 0x59FFFFFF
    }
}

/**
 * 缩放手柄的公共手势逻辑：按住手柄 → 按「按下时的宽度 + 水平位移」算出新宽度 → 抬手落盘。
 *
 * 宿主只需要提供四个读数与两个动作（套用宽度、落盘宽度），因此悬浮小窗（改窗口参数）与
 * 预览页（改布局参数）能共用同一份手势；换算本身在 [VirtualScreenWindow.resizedOverlayWidth] 里，
 * 是纯函数，JVM 单测直接覆盖它。
 *
 * 全程不抛异常：屏幕读数、比例、当前宽度都可能因为会话正在收尾而拿到异常值，
 * 这里一律按「没有可调的东西」处理，绝不把一次手势变成崩溃。
 */
internal class VirtualScreenResizeGesture(
    private val displayWidthPx: () -> Int,
    private val displayHeightPx: () -> Int,
    private val minWidthPx: () -> Int,
    private val aspectRatio: () -> Double,
    private val currentWidthPx: () -> Int,
    private val applyWidth: (Int) -> Unit,
    private val persistWidth: (Int) -> Unit,
) {
    private var active = false
    private var downRawX = 0f
    private var startWidthPx = 0
    private var lastWidthPx = 0

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startWidthPx = runCatching { currentWidthPx() }.getOrDefault(0).coerceAtLeast(1)
                lastWidthPx = startWidthPx
                downRawX = event.rawX
                active = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return false
                val ratio = runCatching { aspectRatio() }.getOrDefault(1.0)
                val next = runCatching {
                    VirtualScreenWindow.resizedOverlayWidth(
                        startWidthPx,
                        (event.rawX - downRawX).toInt(),
                        displayWidthPx(),
                        displayHeightPx(),
                        minWidthPx(),
                        ratio,
                    )
                }.getOrDefault(lastWidthPx)
                if (next == lastWidthPx) return true
                lastWidthPx = next
                runCatching { applyWidth(next) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!active) return false
                active = false
                // 只在真的改过宽度时落盘：点一下手柄不该覆盖用户上一次的尺寸。
                if (lastWidthPx != startWidthPx) runCatching { persistWidth(lastWidthPx) }
                return true
            }
        }
        return active
    }
}
