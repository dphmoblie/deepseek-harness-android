package io.deepseekharness.mobile.virtualscreen

import android.app.Activity
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
    private var preview: VirtualScreenPreview? = null
    private var selecting = true
    private var resumed = false
    private var launchPendingUntil = 0L
    private data class Entry(val label: String, val component: String)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val dark = AppThemePreference.isDark(AppThemePreference.current(this), AppThemePreference.systemNight(this))
        val shortScreen = resources.configuration.screenHeightDp < 480
        val ink = if (dark) 0xffedf1f7.toInt() else 0xff19202a.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) AppThemePreference.DARK_BAR_COLOR else AppThemePreference.LIGHT_BAR_COLOR)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(12) + safe.left, safe.top, dp(12) + safe.right, safe.bottom)
            insets
        }
        val header = LinearLayout(this)
        header.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = "目标应用副屏 · 实验功能"; textSize = 17f; setTextColor(ink) }, LinearLayout.LayoutParams(0, -2, 1f))
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
        val landscape = CheckBox(this).apply { text = "使用横屏副屏（800 × 363 dp）"; setTextColor(ink) }
        picker.addView(landscape)
        picker.addView(TextView(this).apply {
            text = "默认竖屏 363 × 800 dp。启动后可在页面或小窗操作，AI 使用副屏专用工具。画面按需发送至当前模型服务；请勿打开包含隐私信息的页面。"
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
                    .putExtra("component", selected.component).putExtra("landscape", landscape.isChecked))
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
        root.addView(controls)
        setContentView(root)
        AppThemePreference.applySafely(this)
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
                        content.addView(it, FrameLayout.LayoutParams(-1, -1))
                    }
                }
            }
            back.isEnabled = running; floating.isEnabled = running; stop.isEnabled = service != null
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
