package io.deepseekharness.mobile

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.deepseekharness.mobile.shizuku.RootApprovalCoordinator
import io.deepseekharness.mobile.shizuku.RootCommandGuard

/**
 * 原生 App 级高危 / 极危 Root 命令警告与确认页。
 *
 * 不可导出（`android:exported="false"`）：只能由宿主进程内 [RootApprovalCoordinator] 拉起。
 * 职责：
 *  1. 对 Tier 3（不可逆变砖/数据销毁红线）展示醒目红色极危警告，对 Tier 2（整机级高危）展示琥珀色警示；
 *  2. 完整呈现待执行脚本、命中风险类别、沙箱降级事实与（标注为不可信文本的）AI 理由；
 *  3. 强制勾选风险确认框，再通过系统生物识别/锁屏凭据（无锁屏设备降级为二次弹窗确认）后才放行；
 *  4. 返回键、拒绝按钮或页面异常销毁一律按拒绝/取消结单（fail-closed），不挂死设备桥线程。
 */
class RootCommandApprovalActivity : AppCompatActivity() {

    private var requestId: String? = null
    private var settled = false
    private var pendingBiometricPrompt: BiometricPrompt? = null

    private val credentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            settleAndFinish(RootApprovalCoordinator.Outcome.APPROVED)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 禁止截屏与副屏录制，防止 AI 通过视觉通道抓取或诱导点击高危 Root 确认页。
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        AppOrientationPreference.apply(this)
        AppThemePreference.applySafely(this)

        val id = savedInstanceState?.getString(EXTRA_REQUEST_ID)
            ?: intent.getStringExtra(EXTRA_REQUEST_ID)
        val request = id?.let(RootApprovalCoordinator::currentRequest)
        if (id == null || request == null) {
            settled = true
            finish()
            return
        }
        requestId = id

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                settleAndFinish(RootApprovalCoordinator.Outcome.REJECTED)
            }
        })

        setContentView(buildContentView(request))
    }

    override fun onStart() {
        super.onStart()
        AppForeground.onActivityStarted()
    }

    override fun onResume() {
        super.onResume()
        AppForeground.onActivityResumed(this)
        val id = requestId
        if (!settled && (id == null || RootApprovalCoordinator.currentRequest(id) == null)) {
            activeApprovalWindowOpen = false
            settled = true
            finish()
            return
        }
        activeApprovalWindowOpen = !settled
    }

    override fun onPause() {
        activeApprovalWindowOpen = false
        AppForeground.onActivityPaused(this)
        super.onPause()
    }

    override fun onStop() {
        activeApprovalWindowOpen = false
        AppForeground.onActivityStopped()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        requestId?.let { outState.putString(EXTRA_REQUEST_ID, it) }
    }

    override fun onDestroy() {
        activeApprovalWindowOpen = false
        pendingBiometricPrompt?.cancelAuthentication()
        pendingBiometricPrompt = null
        if (!settled && !isChangingConfigurations) {
            requestId?.let { RootApprovalCoordinator.resolve(it, RootApprovalCoordinator.Outcome.CANCELLED) }
            settled = true
        }
        super.onDestroy()
    }

    private fun buildContentView(request: RootApprovalCoordinator.HostApprovalRequest): View {
        val isCritical = request.assessment.route == RootCommandGuard.ApprovalRoute.APP_WARNING
        val nightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val pageBg = if (nightMode) Color.parseColor("#111315") else Color.parseColor("#F6F7F9")
        val cardBg = if (nightMode) Color.parseColor("#1B1F23") else Color.WHITE
        val codeBg = if (nightMode) Color.parseColor("#0D1013") else Color.parseColor("#F0F2F5")
        val primaryText = if (nightMode) Color.parseColor("#F3F4F6") else Color.parseColor("#111827")
        val secondaryText = if (nightMode) Color.parseColor("#D1D5DB") else Color.parseColor("#374151")
        val mutedText = if (nightMode) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280")
        val accentColor = if (isCritical) Color.parseColor("#DC2626") else Color.parseColor("#D97706")
        val bannerBg = if (isCritical) {
            if (nightMode) Color.parseColor("#450A0A") else Color.parseColor("#FEF2F2")
        } else {
            if (nightMode) Color.parseColor("#451A03") else Color.parseColor("#FFFBEB")
        }
        val bannerText = if (isCritical) {
            if (nightMode) Color.parseColor("#FCA5A5") else Color.parseColor("#991B1B")
        } else {
            if (nightMode) Color.parseColor("#FCD34D") else Color.parseColor("#92400E")
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            filterTouchesWhenObscured = true
            setBackgroundColor(pageBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // 1. 顶部风险横幅
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(bannerBg, accentColor, dp(12), dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        banner.addView(TextView(this).apply {
            text = getString(
                if (isCritical) R.string.root_approval_critical_title
                else R.string.root_approval_high_title,
            )
            setTextColor(bannerText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
        })
        banner.addView(TextView(this).apply {
            text = getString(
                if (isCritical) R.string.root_approval_critical_warning
                else R.string.root_approval_high_warning,
            )
            setTextColor(bannerText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, 0)
        })
        container.addView(banner)

        // 2. 命中风险规则卡片
        val rulesCard = card(cardBg, if (nightMode) Color.parseColor("#2B3138") else Color.parseColor("#E5E7EB"))
        rulesCard.addView(sectionTitle(getString(R.string.root_approval_rules_heading), primaryText))
        val rulesText = buildString {
            request.assessment.actions.forEachIndexed { index, action ->
                if (index > 0) append('\n')
                append("• ")
                append(action)
            }
            if (request.assessment.snippet.isNotEmpty()) {
                append('\n')
                append(getString(R.string.root_approval_matched_snippet, request.assessment.snippet))
            }
        }
        rulesCard.addView(TextView(this).apply {
            text = rulesText
            setTextColor(secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, 0)
        })
        if (request.degraded) {
            rulesCard.addView(TextView(this).apply {
                text = getString(
                    R.string.root_approval_degraded_notice,
                    request.degradations.joinToString(", "),
                )
                setTextColor(accentColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        container.addView(rulesCard, cardLayoutParams())

        // 3. AI 理由卡片（明确标注不可信文本）
        if (request.justification.isNotEmpty()) {
            val reasonCard = card(cardBg, if (nightMode) Color.parseColor("#2B3138") else Color.parseColor("#E5E7EB"))
            reasonCard.addView(sectionTitle(getString(R.string.root_approval_ai_reason_heading), primaryText))
            reasonCard.addView(TextView(this).apply {
                text = request.justification
                setTextColor(secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(6), 0, 0)
            })
            container.addView(reasonCard, cardLayoutParams())
        }

        // 4. 完整待执行脚本卡片
        val (displayScript, strippedBidi) = RootApprovalCoordinator.sanitizeScriptForDisplay(request.script)
        val scriptCard = card(cardBg, if (nightMode) Color.parseColor("#2B3138") else Color.parseColor("#E5E7EB"))
        scriptCard.addView(sectionTitle(getString(R.string.root_approval_script_heading), primaryText))
        if (strippedBidi) {
            scriptCard.addView(TextView(this).apply {
                text = getString(R.string.root_approval_bidi_stripped_notice)
                setTextColor(accentColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        val codeBox = TextView(this).apply {
            text = displayScript
            typeface = Typeface.MONOSPACE
            setTextColor(primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextIsSelectable(true)
            background = roundedRect(codeBg, Color.TRANSPARENT, dp(8), 0)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        scriptCard.addView(codeBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        scriptCard.addView(TextView(this).apply {
            text = getString(R.string.root_approval_uid0_note)
            setTextColor(mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
        })
        container.addView(scriptCard, cardLayoutParams())

        scroll.addView(container)
        root.addView(scroll)

        // 5. 底部确认栏：勾选框 + 拒绝/执行按钮
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardBg)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        val acknowledge = CheckBox(this).apply {
            text = getString(
                if (isCritical) R.string.root_approval_critical_acknowledge
                else R.string.root_approval_high_acknowledge,
            )
            setTextColor(primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        footer.addView(acknowledge)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        val rejectButton = Button(this).apply {
            text = getString(R.string.root_approval_reject)
            setOnClickListener { settleAndFinish(RootApprovalCoordinator.Outcome.REJECTED) }
        }
        val confirmButton = Button(this).apply {
            text = getString(
                if (isCritical) R.string.root_approval_critical_confirm
                else R.string.root_approval_high_confirm,
            )
            isEnabled = false
            setTextColor(Color.WHITE)
            background = roundedRect(accentColor, Color.TRANSPARENT, dp(8), 0)
            alpha = 0.45f
            setOnClickListener { startFinalVerification(isCritical) }
        }
        acknowledge.setOnCheckedChangeListener { _, checked ->
            confirmButton.isEnabled = checked
            confirmButton.alpha = if (checked) 1f else 0.45f
        }

        actionsRow.addView(rejectButton, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { marginEnd = dp(8) })
        actionsRow.addView(confirmButton, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { marginStart = dp(8) })
        footer.addView(actionsRow)
        root.addView(footer)

        return root
    }

    /**
     * 最终验证：优先走系统生物识别或锁屏凭据（与电源动作一致）；
     * 若测试/极客设备未配置锁屏密码，降级为明确告知风险的二次弹窗确认。
     */
    private fun startFinalVerification(isCritical: Boolean) {
        val id = requestId
        if (settled || id == null || RootApprovalCoordinator.currentRequest(id) == null) {
            settled = true
            finish()
            return
        }
        val title = getString(
            if (isCritical) R.string.root_approval_critical_title
            else R.string.root_approval_high_title,
        )
        val subtitle = getString(
            if (isCritical) R.string.root_approval_biometric_critical_subtitle
            else R.string.root_approval_biometric_high_subtitle,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = BiometricManager.from(this)
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            if (manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
                pendingBiometricPrompt?.cancelAuthentication()
                val prompt = BiometricPrompt(
                    this,
                    ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            pendingBiometricPrompt = null
                            settleAndFinish(RootApprovalCoordinator.Outcome.APPROVED)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            pendingBiometricPrompt = null
                        }
                    },
                )
                pendingBiometricPrompt = prompt
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(authenticators)
                    .build()
                if (runCatching { prompt.authenticate(info) }.isSuccess) return
                pendingBiometricPrompt = null
            }
        } else {
            @Suppress("DEPRECATION")
            val intent = getSystemService(KeyguardManager::class.java)
                ?.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent != null && runCatching { credentialLauncher.launch(intent) }.isSuccess) {
                return
            }
        }

        // 设备未设置锁屏密码/生物识别：不静默跳过，改为二次弹窗显式确认。
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(getString(R.string.root_approval_no_lockscreen_confirm))
            .setNegativeButton(R.string.root_approval_reject, null)
            .setPositiveButton(
                if (isCritical) R.string.root_approval_critical_confirm
                else R.string.root_approval_high_confirm,
            ) { _, _ ->
                settleAndFinish(RootApprovalCoordinator.Outcome.APPROVED)
            }
            .show()
    }

    private fun settleAndFinish(outcome: RootApprovalCoordinator.Outcome) {
        if (settled) return
        settled = true
        activeApprovalWindowOpen = false
        requestId?.let { RootApprovalCoordinator.resolve(it, outcome) }
        finish()
    }

    internal fun finishIfStale(targetRequestId: String? = null) {
        runOnUiThread {
            if (settled || isFinishing || isDestroyed) return@runOnUiThread
            val id = requestId
            if (targetRequestId == null || id == null || id == targetRequestId || RootApprovalCoordinator.currentRequest(id) == null) {
                settled = true
                pendingBiometricPrompt?.cancelAuthentication()
                pendingBiometricPrompt = null
                finish()
            }
        }
    }

    private fun card(bgColor: Int, borderColor: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedRect(bgColor, borderColor, dp(12), dp(1))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(12) }

    private fun sectionTitle(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun roundedRect(fill: Int, stroke: Int, radiusPx: Int, strokePx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val EXTRA_REQUEST_ID = "io.deepseekharness.mobile.root.APPROVAL_REQUEST_ID"

        /** 当前是否正展示 Root 高危命令警告确认页：供无障碍服务硬拦截，彻底阻断 AI 自我点击确认。 */
        @Volatile
        internal var activeApprovalWindowOpen: Boolean = false

        fun createIntent(context: Context, requestId: String): Intent =
            Intent(context, RootCommandApprovalActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
    }
}
