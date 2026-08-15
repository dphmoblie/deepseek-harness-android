package io.deepseekharness.mobile

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import com.getcapacitor.BridgeActivity
import io.deepseekharness.mobile.runtime.audit.AuditEvent
import io.deepseekharness.mobile.runtime.audit.AuditResult
import io.deepseekharness.mobile.runtime.audit.PrivateAuditLog

class MainActivity : BridgeActivity() {
    private lateinit var auditLog: PrivateAuditLog
    private var credentialPromptActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(MobileRuntimePlugin::class.java)
        super.onCreate(savedInstanceState)
        auditLog = PrivateAuditLog(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setManagementVisible(false)
    }

    override fun onResume() {
        super.onResume()
        if (AppAuthenticationState.isManagementAuthenticated()) {
            setManagementVisible(true)
        } else if (!credentialPromptActive) {
            requestDeviceAuthentication()
        }
    }

    override fun onStop() {
        if (AppAuthenticationState.isManagementAuthenticated() && !isChangingConfigurations) {
            AppAuthenticationState.revokeManagement()
            setManagementVisible(false)
        }
        super.onStop()
    }

    @Deprecated("Uses the platform device-credential activity for Android 8 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DEVICE_CREDENTIAL_REQUEST) return
        credentialPromptActive = false
        if (resultCode == RESULT_OK) {
            AppAuthenticationState.grantManagement()
            auditLog.record(AuditEvent.APP_AUTH, AuditResult.SUCCEEDED)
            bridge.webView.reload()
            setManagementVisible(true)
        } else {
            auditLog.record(AuditEvent.APP_AUTH, AuditResult.DENIED)
            finishAndRemoveTask()
        }
    }

    private fun requestDeviceAuthentication() {
        auditLog.record(AuditEvent.APP_AUTH, AuditResult.STARTED)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (!keyguardManager.isDeviceSecure) {
            showSecureLockRequired()
            return
        }
        val credentialIntent = keyguardManager.createConfirmDeviceCredentialIntent(
            getString(R.string.auth_title),
            getString(R.string.auth_description),
        )
        if (credentialIntent == null) {
            showSecureLockRequired()
            return
        }
        credentialPromptActive = true
        try {
            startActivityForResult(credentialIntent, DEVICE_CREDENTIAL_REQUEST)
        } catch (_: Throwable) {
            credentialPromptActive = false
            showSecureLockRequired()
        }
    }

    private fun showSecureLockRequired() {
        credentialPromptActive = true
        auditLog.record(AuditEvent.APP_AUTH, AuditResult.DENIED)
        AlertDialog.Builder(this)
            .setTitle(R.string.auth_required_title)
            .setMessage(R.string.auth_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.open_security_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                finishAndRemoveTask()
            }
            .setNegativeButton(R.string.exit_app) { _, _ -> finishAndRemoveTask() }
            .show()
    }

    private fun setManagementVisible(visible: Boolean) {
        bridge.webView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private companion object {
        const val DEVICE_CREDENTIAL_REQUEST = 8042
    }
}
