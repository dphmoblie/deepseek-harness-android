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
    private lateinit var modes: LinearLayout
    private val modeButtons = mutableMapOf<String, Button>()
    private var preview: VirtualScreenPreview? = null
    private var selecting = true
    private var resumed = false
    private var launchPendingUntil = 0L
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
        super.attachBaseContext(AppThemePreference.palette(base))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
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
        header.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = getString(R.string.virtual_screen_title); textSize = 17f; setTextColor(ink) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        status = TextView(this).apply { setTextColor(ink); textSize = 12f; maxLines = if (shortScreen) 1 else 3 }
        root.addView(status)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        picker = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(picker, FrameLayout.LayoutParams(-1, -1))
        val query = EditText(this).apply {
            hint = "搜索应用名称或包名"; setSingleLine(); setTextColor(ink)
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
            if (!DeviceShellAccess.enabled(this)) { toast("请先在设置中开启 AI Shell 并连接 Shizuku"); return@setOnItemClickListener }
            if (Build.VERSION.SDK_INT < 29) { toast("副屏操作需要 Android 10 或更高版本"); return@setOnItemClickListener }
            try {
                launchPendingUntil = android.os.SystemClock.elapsedRealtime() + 2000
                ContextCompat.startForegroundService(this, Intent(this, VirtualScreenService::class.java)
                    .putExtra("component", selected.component)
                    // 规格走三个整型 extra；同一份 Intent 里仍带上旧的 landscape 布尔，
                    // 让「页面已更新、会话服务还是旧版」的升级中途组合也能正常起会话。
                    .putVirtualScreenSpec(VirtualScreenSpec.preset(landscape.isChecked), landscape.isChecked))
                status.text = "正在创建目标应用副屏…"
            } catch (_: Exception) { launchPendingUntil = 0; toast("无法启动副屏服务，请回到前台重试") }
        }
        val controls = LinearLayout(this)
        back = Button(this).apply { text = "应用返回"; setOnClickListener { sendBack() } }
        floating = Button(this).apply {
            text = "小窗"
            setOnClickListener {
                try { checkNotNull(VirtualScreenService.current).showOverlay(); finish() }
                catch (_: Exception) { toast("请先启动副屏，并在设置中授予悬浮窗权限") }
            }
        }
        stop = Button(this).apply { text = "结束副屏"; setOnClickListener { stopService(Intent(this@VirtualScreenActivity, VirtualScreenService::class.java)) } }
        listOf(back, floating, stop).forEach { controls.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        // 预览帧率切换：与 AI 侧的 mobile_virtual_screen_config 走同一条 config 动作，
        // 会话不重启；实际帧率受目标渲染与设备负载限制，状态行里的 fps 才是实测值。
        modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        listOf("limited" to "省电", "15fps" to "15fps", "30fps" to "30fps", "60fps" to "60fps", "120fps" to "120fps", "185fps" to "185fps").forEach { (mode, label) ->
            modeButtons[mode] = Button(this).apply {
                text = label; textSize = 12f
                setOnClickListener { selectMode(mode) }
            }
            modes.addView(modeButtons.getValue(mode), LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(modes, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        root.addView(controls)
        setContentView(root)
        // 档位行与底部按钮不受预览影响：底部按钮本来就独占 root 的一行（不与预览同层），
        // 档位行则是 content 里最后加的那个子控件，再抬一次确保它压在预览之上。
        modes.bringToFront()
        controls.bringToFront()
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
        val service = VirtualScreenService.current ?: return
        val request = JSONObject().put("sessionId", service.state().optString("sessionId")).put("action", "back")
        back.isEnabled = false
        worker.execute {
            runCatching { service.action(request) }.onFailure { main.post { if (!isDestroyed) toast("无法返回，请确认目标应用仍在副屏") } }
            main.post { if (!isDestroyed) back.isEnabled = true }
        }
    }

    /** 与 AI 侧的 mobile_virtual_screen_config 走同一条 config 动作，不重启会话。 */
    private fun selectMode(mode: String) {
        val service = VirtualScreenService.current ?: return
        val request = JSONObject().put("sessionId", service.state().optString("sessionId")).put("action", "config").put("previewMode", mode)
        worker.execute {
            runCatching { service.action(request) }
                .onFailure { main.post { if (!isDestroyed) toast("帧率未生效，请确认副屏仍在运行") } }
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            val service = VirtualScreenService.current
            val running = service?.state()?.optBoolean("active") == true
            val picking = service == null
            if (picking != selecting || (running && preview == null)) {
                selecting = picking
                picker.visibility = if (picking) View.VISIBLE else View.GONE
                if (!running) { preview?.let { content.removeView(it) }; preview = null }
                else if (preview == null) {
                    preview = VirtualScreenPreview(this@VirtualScreenActivity).also {
                        it.report = { text -> status.text = text }
                        // 预览第一个加进 content：它是最底层，档位行与底部按钮都画在它上面
                        // （规则见 VirtualScreenPolicy.foregroundOnTop）。
                        // 这里与小窗用的是同一个控件类，取帧判定与状态行文案也共用
                        // VirtualScreenPolicy.previewOutcome / previewLine，页面不会再单独走一套判定。
                        content.addView(it, FrameLayout.LayoutParams(-1, -1))
                        // 显式再抬一次档位行：预览刚加进来时子控件顺序变了，抬一次比假设顺序可靠。
                        modes.bringToFront()
                    }
                }
            }
            back.isEnabled = running; floating.isEnabled = running; stop.isEnabled = service != null
            modes.visibility = if (running) View.VISIBLE else View.GONE
            if (running) {
                // 状态里的标签反解回模式名，把当前生效的那个按钮置灰，避免重复点击造成误解。
                val active = service?.state()?.optString("previewMode").orEmpty()
                    .let { if (it == "limited-fps") "limited" else it.removePrefix("realtime-") }
                modeButtons.forEach { (mode, button) -> button.isEnabled = mode != active }
            }
            if (!running) status.text = when {
                service != null || android.os.SystemClock.elapsedRealtime() < launchPendingUntil -> "正在创建副屏，请稍候…"
                VirtualScreenService.lastError.isNotEmpty() -> VirtualScreenService.lastError
                else -> "选择要交给 AI 操作的目标应用。设备兼容性需要实测。"
            }
            main.postDelayed(this, 700)
        }
    }

    override fun onResume() { super.onResume(); resumed = true; VirtualScreenService.current?.hideOverlay(); main.post(refresh) }
    override fun onPause() {
        resumed = false; main.removeCallbacks(refresh)
        preview?.let { content.removeView(it) }; preview = null
        super.onPause()
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
