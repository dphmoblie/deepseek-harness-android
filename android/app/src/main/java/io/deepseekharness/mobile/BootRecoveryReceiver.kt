package io.deepseekharness.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.provider.Settings
import io.deepseekharness.mobile.runtime.RuntimeStore

/**
 * 系统启动与用户解锁后的轻量恢复入口。
 *
 * 接收器不启动 Activity，也不读取或记录任何凭据；它只在用户已经解锁后，
 * 根据持久化的非敏感开关恢复后台服务与悬浮球。Android 的后台启动限制仍由系统决定，
 * 被拒绝时服务会自然降级，用户下次打开应用仍可手动启动。
 */
class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_USER_UNLOCKED) return

        val appContext = context.applicationContext
        val userManager = appContext.getSystemService(UserManager::class.java)
        // BOOT_COMPLETED 在部分 ROM 上可能早于用户解锁；延后到 USER_UNLOCKED，
        // 避免在凭据加密存储不可用时误读为默认关闭。
        if (userManager != null && !userManager.isUserUnlocked) return

        val store = try {
            RuntimeStore(appContext)
        } catch (_: Throwable) {
            return
        }

        // 悬浮球只依赖用户开关与系统授权，服务自身会再次校验权限并处理竞态。
        if (store.overlayBallEnabled() && Settings.canDrawOverlays(appContext)) {
            OverlayBallService.start(appContext)
        }

        // Harness 恢复由前台服务读取运行意图并创建新的会话凭据；
        // 接收器不直接触碰运行时控制器，减少广播主线程工作量。
        if (store.keepRuntimeInBackground()) {
            HarnessKeepAliveService.startAfterBoot(appContext)
        }
    }
}
