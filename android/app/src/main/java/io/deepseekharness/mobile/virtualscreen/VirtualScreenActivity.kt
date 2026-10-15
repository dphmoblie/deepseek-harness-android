package io.deepseekharness.mobile.virtualscreen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.deepseekharness.mobile.AppThemePreference
import io.deepseekharness.mobile.AppLanguage
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.shizuku.DeviceShellAccess
import org.json.JSONObject
import java.util.concurrent.Executors

/** 原生独立页面：选择目标应用、查看副屏和切换小窗。退出页面不结束副屏。 */
class VirtualScreenActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var picker: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var back: Button
    private lateinit var floating: Button
    private lateinit var stop: Button
    private lateinit var modes: HorizontalScrollView
    private val modeButtons = mutableMapOf<String, Button>()
    /**
     * 页面里的预览：与悬浮小窗同样是一个 [VirtualScreenFrame]（画面 + 右下角缩放手柄），
     * 尺寸也走同一条规则与同一条偏好键，于是「预览页看到的」与「小窗里的」永远是同一个东西。
     */
    private var preview: VirtualScreenFrame? = null
    private var selecting = true
    private var resumed = false
    private var actionPending = false
    private var launchPendingUntil = 0L
    private var createdLanguage = ""
    private var createdTheme = ""

    /** 连续刷新失败次数；一次成功即清零（重试节奏与提示见 [VirtualScreenPolicy]）。 */
    private var consecutiveRefreshFailures = 0
    private data class Entry(val label: String, val component: String)

    /**
     * 让本页面的颜色令牌与控件默认样式都按**已保存的应用主题**解析。
     *
     * `values/` 与 `values-night/` 的资源限定符认的是**系统**深色模式，而应用主题可以和系统不同：
     * 用户选了深色、系统还是浅色时，本页背景会自己判深浅画成深色，而 Button / CheckBox /
     * EditText / ListView 的默认样式仍由 `Theme.AppCompat.DayNight.NoActionBar` 按系统解析成
     * 浅色（浅底深字），叠在深色页面上就是「副屏这页没跟主题走」的样子。
     * 在 attachBaseContext 阶段覆写 `uiMode` 之后，`getColor(R.color.*)`、`?attr/colorAccent`
     * 与控件默认样式就统一到同一套主题上了。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppThemePreference.palette(AppLanguage.localizedContext(base)))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        createdLanguage = AppLanguage.current(this)
        createdTheme = AppThemePreference.current(this)
        // 这一页**不再加 FLAG_SECURE**：加了以后用户自己截不了图、远端也取不了证，
        // 排查「预览页读到白帧」这类问题时就只能靠猜。悬浮小窗（0.2.9 起）同样没加，两边行为一致。
        // 隐私提醒仍写在页面上与 docs/目标应用副屏.md 里：画面按需发送至当前模型服务，不要打开含隐私信息的页面。
        val shortScreen = resources.configuration.screenHeightDp < 480
        // 颜色一律取主题令牌（res/values{,-night}/colors.xml），不在这里写死十六进制色值：
        // surface 与 Web 侧 --bg 同值、ink 与 Web 侧 --ink 同值，深浅两套在资源里成对定义。
        val surface = AppThemePreference.color(this, R.color.harness_toolbar_background)
        val ink = AppThemePreference.color(this, R.color.harness_toolbar_foreground)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(12) + safe.left, safe.top, dp(12) + safe.right, safe.bottom)
            insets
        }
        val header = LinearLayout(this)
        header.addView(Button(this).apply { setText(R.string.virtual_screen_back); setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = getString(R.string.virtual_screen_title); textSize = 17f; setTextColor(ink) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        status = TextView(this).apply { setTextColor(ink); textSize = 12f; maxLines = if (shortScreen) 1 else 3 }
        root.addView(status)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        // 容器尺寸变了（旋转、分屏）就按新容器重算一次画面尺寸：宽度偏好不变，
        // 但「能放下的最大画面」变了，不重算就会有一个方向上被裁掉。
        // applyPagePreviewSize 内部只在尺寸真的变了时改布局参数，因此不会被这次布局再触发一次。
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPagePreviewSize() }
        picker = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(picker, FrameLayout.LayoutParams(-1, -1))
        val query = EditText(this).apply {
            hint = getString(R.string.virtual_screen_search); setSingleLine(); setTextColor(ink)
            filters = arrayOf(android.text.InputFilter.LengthFilter(160))
        }
        picker.addView(query)
        // 两档尺寸都从规格对象读：文案里的数字与真正建会话用的像素值同源，
        // 不会再出现「文案写 800 × 363、代码里却是 1280 × 580」这种两处各写一遍的漂移。
        val density = resources.displayMetrics.density
        val portraitSpec = VirtualScreenSpec.PORTRAIT
        val landscapeSpec = VirtualScreenSpec.LANDSCAPE
        val landscape = CheckBox(this).apply {
            text = getString(
                R.string.virtual_screen_orientation_landscape,
                landscapeSpec.dpWidth(density),
                landscapeSpec.dpHeight(density),
            )
            setTextColor(ink)
        }
        picker.addView(landscape)
        picker.addView(TextView(this).apply {
            text = getString(
                R.string.virtual_screen_orientation_hint,
                portraitSpec.dpWidth(density),
                portraitSpec.dpHeight(density),
            )
            setTextColor(ink); textSize = 12f
            visibility = if (shortScreen) View.GONE else View.VISIBLE
        })
        val list = ListView(this)
        picker.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        var entries = emptyList<Entry>()
        var shown = emptyList<Entry>()
        fun filter() {
            val value = query.text.toString().trim()
            shown = entries.filter { it.label.contains(value, true) || it.component.substringBefore('/').contains(value, true) }
            list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, shown.map { "${it.label}\n${it.component.substringBefore('/')}" }) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    super.getView(position, convertView, parent).also { (it as? TextView)?.setTextColor(ink) }
            }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            if (VirtualScreenService.current != null || android.os.SystemClock.elapsedRealtime() < launchPendingUntil) return@setOnItemClickListener
            val selected = shown.getOrNull(position) ?: return@setOnItemClickListener
            if (!DeviceShellAccess.enabled(this)) { toast(getString(R.string.virtual_screen_shell_required)); return@setOnItemClickListener }
            if (Build.VERSION.SDK_INT < 29) { toast(getString(R.string.virtual_screen_android_required)); return@setOnItemClickListener }
            try {
                launchPendingUntil = android.os.SystemClock.elapsedRealtime() + 2000
                ContextCompat.startForegroundService(this, Intent(this, VirtualScreenService::class.java)
                    .putExtra("component", selected.component)
                    // 页面的横竖屏勾选就是本次会话的方向；自适应开关沿用设置页里的偏好：
                    // 关自适应时三项尺寸仍取方向预设（与改造前逐字一致），开自适应时由服务侧
                    // 按真实屏幕尺寸算规格。规格仍走三个整型 extra，让「页面已更新、会话服务
                    // 还是旧版」的升级中途组合也能正常起会话。
                    .putVirtualScreenStart(
                        VirtualScreenSpec.preset(landscape.isChecked),
                        VirtualScreenPreferences.read(this).adaptive,
                        if (landscape.isChecked) "landscape" else "portrait"
                    ))
                status.setText(R.string.virtual_screen_creating)
            } catch (_: Exception) { launchPendingUntil = 0; toast(getString(R.string.virtual_screen_start_failed)) }
        }
        val controls = LinearLayout(this)
        back = Button(this).apply { setText(R.string.virtual_screen_app_back); setOnClickListener { sendBack() } }
        floating = Button(this).apply {
            setText(R.string.virtual_screen_floating)
            setOnClickListener {
                try { checkNotNull(VirtualScreenService.current).showOverlay(); finish() }
                catch (_: Exception) { toast(getString(R.string.virtual_screen_overlay_required)) }
            }
        }
        stop = Button(this).apply { setText(R.string.virtual_screen_stop); setOnClickListener { stopService(Intent(this@VirtualScreenActivity, VirtualScreenService::class.java)) } }
        listOf(back, floating, stop).forEach { controls.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        // 预览帧率切换：与 AI 侧的 mobile_virtual_screen_config 走同一条 config 动作，
        // 会话不重启；实际帧率受目标渲染与设备负载限制，状态行里的 fps 才是实测值。
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes = HorizontalScrollView(this).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = true
            addView(modeRow)
        }
        listOf("limited" to getString(R.string.virtual_screen_mode_limited), "15fps" to "15fps", "30fps" to "30fps", "60fps" to "60fps", "120fps" to "120fps", "185fps" to "185fps").forEach { (mode, label) ->
            modeButtons[mode] = Button(this).apply {
                text = label; textSize = 12f
                setOnClickListener { selectMode(mode) }
            }
            modeRow.addView(modeButtons.getValue(mode), LinearLayout.LayoutParams(dp(80), dp(48)))
        }
        // 独占一行并允许横向滚动：小屏不挤压按钮，也不挡住目标应用的底部内容。
        root.addView(modes, LinearLayout.LayoutParams(-1, -2))
        root.addView(controls)
        setContentView(root)
        // 传入与页面同一枚令牌：状态栏/导航栏与页面底色同源，不会在安全区露出一条色带。
        AppThemePreference.applySafely(this, surface)
        worker.execute {
            val found = runCatching {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0).mapNotNull { app ->
                    if (app.packageName == packageName || !app.enabled) return@mapNotNull null
                    val component = packageManager.getLaunchIntentForPackage(app.packageName)?.component?.flattenToString() ?: return@mapNotNull null
                    if (runCatching { VirtualScreenPolicy.component(component) }.isFailure) return@mapNotNull null
                    val label = packageManager.getApplicationLabel(app).toString()
                        .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }.take(160)
                    Entry(label, component)
                }.sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
            main.post { if (!isDestroyed) { entries = found; filter() } }
        }
    }

    private fun sendBack() {
        sendAction("back", getString(R.string.virtual_screen_back_failed))
    }

    /** 与 AI 侧的 mobile_virtual_screen_config 走同一条 config 动作，不重启会话。 */
    private fun selectMode(mode: String) {
        sendAction("config", getString(R.string.virtual_screen_mode_failed), mode)
    }

    private fun sendAction(action: String, failureMessage: String, mode: String? = null) {
        // 不把重复点击排成未来的操作；状态轮询也不能提前解除这道门禁。
        if (actionPending || !resumed) return
        val service = VirtualScreenService.current ?: return
        val snapshot = service.state()
        if (!snapshot.optBoolean("active")) return
        val request = JSONObject().put("sessionId", snapshot.optString("sessionId")).put("action", action)
        if (mode != null) request.put("previewMode", mode)
        actionPending = true
        updateControls(snapshot)
        worker.execute {
            val failed = runCatching { service.action(request) }.isFailure
            main.post {
                actionPending = false
                if (!isDestroyed) {
                    updateControls(VirtualScreenService.current?.state())
                    if (failed) toast(failureMessage)
                }
            }
        }
    }

    private fun updateControls(snapshot: JSONObject?) {
        val running = snapshot?.optBoolean("active") == true
        back.isEnabled = running && !actionPending
        floating.isEnabled = running
        stop.isEnabled = snapshot != null
        modes.visibility = if (running) View.VISIBLE else View.GONE
        val active = snapshot?.optString("previewMode").orEmpty()
            .let { if (it == "limited-fps") "limited" else it.removePrefix("realtime-") }
        modeButtons.forEach { (mode, button) -> button.isEnabled = running && !actionPending && mode != active }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            try {
                val service = VirtualScreenService.current
                val snapshot = service?.state()
                val running = snapshot?.optBoolean("active") == true
                val picking = service == null
                if (picking != selecting || (running && preview == null)) {
                    selecting = picking
                    picker.visibility = if (picking) View.VISIBLE else View.GONE
                    if (!running) { preview?.let { content.removeView(it) }; preview = null }
                    else if (preview == null) {
                        val frame = VirtualScreenFrame(this@VirtualScreenActivity).also {
                            it.preview.report = { text -> status.text = text }
                            it.installResize(pageResizeGesture)
                            // 与小窗同一排芯片：画面上的出口位置与样式两处一致（本页的其余控制仍在页面上）。
                            it.installControls(VirtualScreenChrome.page(onBack = { finish() }, backLabel = getString(R.string.virtual_screen_back)))
                            // 控制栏在 content 外；此处仅放画面，保留与小窗共用的缩放与坐标换算。
                            it.layoutParams = pagePreviewParams(pageWidthPreferencePx())
                            content.addView(it)
                        }
                        preview = frame
                    }
                }
                updateControls(snapshot)
                if (!running) status.text = when {
                    service != null || android.os.SystemClock.elapsedRealtime() < launchPendingUntil -> getString(R.string.virtual_screen_creating)
                    VirtualScreenService.lastError.isNotEmpty() -> VirtualScreenService.lastError
                    else -> getString(R.string.virtual_screen_select_hint)
                }
            } catch (_: Throwable) {
                /*
                 * 轮询跑在主线程，异常逃出去会直接终结进程，所以必须在这里接住；但「接住」不等于
                 * 「就此停摆」——原来的写法在续期之前 return，一次偶发异常（服务正在重建、快照刚好
                 * 读到半张状态）就让控制条与帧率芯片永久停在那一下，只有离开再回到本页才会恢复，
                 * 而这期间画面其实还在变。这里改成降频重试：提示只发一次（免得 700ms 一次的循环
                 * 刷成一串 toast），连续失败到上限才停止续期。判定与文案在 VirtualScreenPolicy 里，
                 * 可脱离设备单测。
                 */
                consecutiveRefreshFailures += 1
                VirtualScreenPolicy.previewRefreshNotice(consecutiveRefreshFailures)?.let { notice ->
                    runCatching { toast(getString(if (notice == VirtualScreenPolicy.PreviewRefreshNotice.RETRYING) R.string.virtual_screen_refresh_retry else R.string.virtual_screen_refresh_failed)) }
                }
                if (VirtualScreenPolicy.previewRefreshShouldStop(consecutiveRefreshFailures)) return
                main.postDelayed(this, VirtualScreenPolicy.previewRefreshDelayMillis(consecutiveRefreshFailures))
                return
            }
            consecutiveRefreshFailures = 0
            main.postDelayed(this, VirtualScreenPolicy.previewRefreshDelayMillis(consecutiveRefreshFailures))
        }
    }

    /**
     * 当前会话的画面比例。状态里读不到宽高时 [VirtualScreenSpec.decodeState] 会回退竖屏预设，
     * 因此这里不会出现除零或 0 尺寸。
     */
    private fun pageSpec(): VirtualScreenSpec = VirtualScreenSpec.decodeState(VirtualScreenService.current?.state())

    /** 页面预览与小窗共用同一条宽度偏好（`0` = 跟随可用区域最大）。 */
    private fun pageWidthPreferencePx(): Int = VirtualScreenPreferences.read(this).overlayWidthPx

    /**
     * 页面预览的布局参数：用与小窗同一套 [VirtualScreenWindow.overlaySize]（同一比例、同一 94%/84% 上限），
     * 只把「屏幕」换成页面里的 content 容器。于是「能放下的最大画面」在页面里就是容器的 94% × 84%，
     * 用户拖出来的宽度两处一致，切换页面 / 小窗不会看到两种尺寸。
     */
    private fun pagePreviewParams(widthPx: Int): FrameLayout.LayoutParams {
        val metrics = resources.displayMetrics
        val containerWidth = content.width.takeIf { it > 0 } ?: metrics.widthPixels
        val containerHeight = content.height.takeIf { it > 0 } ?: metrics.heightPixels
        val size = VirtualScreenWindow.overlaySize(pageSpec(), containerWidth, containerHeight, metrics.density, widthPx)
        return FrameLayout.LayoutParams(size.widthPx, size.heightPx, android.view.Gravity.CENTER)
    }

    /** 按当前偏好重算页面画面尺寸；只在真的变了时改布局参数，避免与布局回调互相触发。 */
    private fun applyPagePreviewSize(widthPx: Int = pageWidthPreferencePx()) {
        val frame = preview ?: return
        val params = pagePreviewParams(widthPx)
        if (frame.width == params.width && frame.height == params.height) return
        frame.layoutParams = params
    }

    /**
     * 页面里的缩放手柄：与悬浮小窗共用同一份手势逻辑，区别只在「改什么」——
     * 页面里的画面不占独立窗口，改的是布局参数；落盘走同一条偏好键。
     */
    private val pageResizeGesture = VirtualScreenResizeGesture(
        displayWidthPx = { content.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels },
        displayHeightPx = { content.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels },
        minWidthPx = { maxOf((VirtualScreenWindow.MIN_OVERLAY_WIDTH_DP * resources.displayMetrics.density).toInt(), VirtualScreenWindow.MIN_OVERLAY_WIDTH_PX) },
        aspectRatio = { pageSpec().aspectRatio },
        currentWidthPx = { preview?.width ?: 0 },
        applyWidth = { applyPagePreviewSize(it) },
        persistWidth = { VirtualScreenPreferences.saveOverlayWidth(this, it) },
    )

    override fun onResume() {
        super.onResume()
        if (createdLanguage != AppLanguage.current(this) || createdTheme != AppThemePreference.current(this)) {
            recreate()
            return
        }
        resumed = true
        VirtualScreenService.current?.hideOverlay()
        main.post(refresh)
    }
    override fun onPause() {
        resumed = false; main.removeCallbacks(refresh)
        if (isFinishing) {
            preview?.let { content.removeView(it) }; preview = null
        }
        super.onPause()
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
