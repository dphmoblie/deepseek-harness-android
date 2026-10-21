package io.deepseekharness.mobile.virtualscreen

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.deepseekharness.mobile.AppLanguage
import io.deepseekharness.mobile.R

/**
 * 画面上的一个控制芯片：标签 + 点一下做什么。外壳不解释动作，只负责画与转发点击，
 * 因此「小窗上有哪些按钮、点了是什么」这件事留在宿主里，视图层不需要知道会话状态。
 */
internal data class VirtualScreenControl(val label: String, val onTap: () -> Unit)

/**
 * 两个宿主各自的芯片清单。做成纯函数是为了能在 JVM 单测里断言「谁在、点什么、什么顺序」——
 * View 层没有 Robolectric，装不上测试，芯片的语义只能靠这一层兜住。
 *
 * 小窗给两枚：「收起」保留会话（目标应用继续跑，只是把画面收掉），「结束」真的停会话；
 * 预览页只给一枚「返回」：页面自己的控制栏已经有「应用返回」「小窗」「结束副屏」与帧率档位，
 * 再重复一遍反而更难找。两处**共用**的是画面、缩放手柄与这排芯片的样式与位置。
 */
internal object VirtualScreenChrome {
    fun overlay(onCollapse: () -> Unit, onStop: () -> Unit, collapseLabel: String = "收起", endLabel: String = "结束"): List<VirtualScreenControl> =
        listOf(VirtualScreenControl(collapseLabel, onCollapse), VirtualScreenControl(endLabel, onStop))

    fun page(onBack: () -> Unit, backLabel: String = "返回"): List<VirtualScreenControl> = listOf(VirtualScreenControl(backLabel, onBack))
}

/**
 * 副屏画面的统一外壳：一枚 [VirtualScreenPreview] + 右下角的缩放手柄 + 左上角的控制芯片。
 *
 * 为什么要有这一层：
 * 1. 悬浮小窗与原生预览页此前各自往自家容器里塞一个 `VirtualScreenPreview`：控件类相同，
 *    但「多大、能不能调」两处行为不同 —— 用户看到的就是两个不一样的东西。收成一个外壳之后，
 *    两处只是把这个外壳放进不同宿主（窗口 / 页面），画面尺寸与缩放手势从此完全一致。
 * 2. 手柄**作为外壳的子视图**，而不是再来一个悬浮窗：同 type 的悬浮窗之间只按「谁后 addView」
 *    分层，多一个手柄窗口就要额外处理它自己的置顶、跟随拖动、失效清理；作为子视图这些问题都不存在。
 * 3. 控制芯片同理收进外壳：小窗此前**没有任何出口**，只能从设置页或通知里收起，用户在小窗上
 *    无路可走；芯片挂在画面上之后，两个宿主给出的出口位置与样式一致。
 *
 * 代价说明（有意取舍）：手柄占住画面右下角 [VirtualScreenWindow.RESIZE_HANDLE_DP] 见方的一小块
 * （内缩 [VirtualScreenWindow.RESIZE_HANDLE_INSET_DP]），芯片占住画面左上角一条，那块区域的触摸
 * 不再透传给副屏里的应用。这与悬浮球对话小窗的手柄取舍一致；不想让出这块区域时，可以在设置页改宽度。
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
            // 描边 + 深底色：之前是 35% 白，压在浅色应用（白底列表）上几乎看不见，
            // 用户会以为「没有缩放功能」。描边让它在深浅两种画面上都有轮廓。
            setStroke((1.5f * density).toInt().coerceAtLeast(1), HANDLE_BORDER_COLOR)
        }
        contentDescription = AppLanguage.localizedContext(context).getString(R.string.virtual_screen_resize)
    }

    private val controlRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }

    private val chromeHeightPx = (VirtualScreenWindow.CHROME_HEIGHT_DP * density).toInt().coerceAtLeast(1)

    /**
     * 顶栏：拖动条 + 出口芯片。
     *
     * 它占的是窗口自己的一条高度，不压在画面上——芯片此前盖住目标应用的顶部按钮，
     * 也没给用户一个「按住这里就能挪窗口」的明确入口。
     */
    private val chromeBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(CHROME_COLOR)
        setPadding(handleInsetPx, 0, handleInsetPx, 0)
    }

    init {
        // 画面从顶栏下面开始；手柄最后加入 ⇒ 在右下角压在画面之上，只吃它自己那一小块触摸。
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = chromeHeightPx
        })
        chromeBar.addView(controlRow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(chromeBar, LayoutParams(LayoutParams.MATCH_PARENT, chromeHeightPx, Gravity.TOP))
        addView(handle, LayoutParams(handleSizePx, handleSizePx, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = handleInsetPx
            bottomMargin = handleInsetPx
        })
    }

    /** 顶栏拖动：按住顶栏即可移动窗口，不必先长按画面（画面区域的触摸仍透传）。 */
    fun installDrag(gesture: VirtualScreenDragGesture) {
        chromeBar.setOnTouchListener { _, event -> gesture.onTouch(event) }
    }

    /** 装载手柄的拖动逻辑；每个宿主只调一次（服务与预览页各自一份手势）。 */
    fun installResize(gesture: VirtualScreenResizeGesture) {
        handle.setOnTouchListener { _, event -> gesture.onTouch(event) }
    }

    fun refreshLanguage() {
        handle.contentDescription = AppLanguage.localizedContext(context).getString(R.string.virtual_screen_resize)
        preview.contentDescription = AppLanguage.localizedContext(context).getString(R.string.virtual_screen_picture_description)
    }

    /**
     * 装载画面上的控制芯片；每个宿主只调一次。传空清单 = 这处画面不给出口（芯片整排收起）。
     *
     * 芯片是外壳的子视图而不是独立窗口，所以点击不会被拖动手势吃掉：拖动手势挂在 [preview] 上，
     * 芯片只吃自己那一小块矩形。
     */
    fun installControls(controls: List<VirtualScreenControl>) {
        controlRow.removeAllViews()
        controls.forEach { control ->
            controlRow.addView(TextView(context).apply {
                text = control.label
                textSize = 12f
                setTextColor(CONTROL_TEXT_COLOR)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply {
                    setColor(CONTROL_BACKGROUND_COLOR)
                    cornerRadius = dp(14).toFloat()
                }
                isClickable = true
                contentDescription = AppLanguage.localizedContext(context).getString(R.string.virtual_screen_control_description, control.label)
                setOnClickListener { control.onTap() }
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        controlRow.visibility = if (controls.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        /** 手柄底色：半透明黑，压在浅色画面上也有轮廓；再配一道白描边。 */
        const val HANDLE_COLOR = 0x66000000
        const val HANDLE_BORDER_COLOR = 0xCCFFFFFF.toInt()

        /** 芯片底色：半透明黑 + 白字，深浅两种画面上都能看清。 */
        const val CONTROL_BACKGROUND_COLOR = 0x8C000000.toInt()
        const val CONTROL_TEXT_COLOR = 0xFFFFFFFF.toInt()

        /** 顶栏底色：半透明黑，既能看清芯片，也不至于像一条实心黑边。 */
        const val CHROME_COLOR = 0x4D000000
    }
}

/**
 * 顶栏拖动的手势：按下即开始拖，不需要长按。
 *
 * 与画面触摸流分开：画面区域继续把触摸透传给副屏，只有顶栏这一条是「挪窗口」。
 * 全程不抛异常；宿主未就绪（没有窗口可拖）时按未开始处理。
 */
internal class VirtualScreenDragGesture(private val host: () -> VirtualScreenDragHost?) {
    private var active = false
    private var downRawX = 0f
    private var downRawY = 0f

    fun onTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            downRawX = event.rawX
            downRawY = event.rawY
            active = runCatching { host()?.beginDrag() == true }.getOrDefault(false)
            true
        }
        MotionEvent.ACTION_MOVE -> {
            if (active) {
                runCatching { host()?.drag((event.rawX - downRawX).toInt(), (event.rawY - downRawY).toInt()) }
            }
            active
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            if (active) {
                runCatching { host()?.endDrag((event.rawX - downRawX).toInt(), (event.rawY - downRawY).toInt()) }
            }
            active = false
            true
        }
        else -> false
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
