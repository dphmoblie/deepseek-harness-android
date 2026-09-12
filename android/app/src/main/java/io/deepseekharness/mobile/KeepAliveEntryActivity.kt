package io.deepseekharness.mobile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 常驻通知的点击入口。
 *
 * 存在的唯一理由：`MainActivity` 是 `launchMode="singleTask"`，直接把通知指向它会在任务栈
 * 为 `[MainActivity, HarnessActivity]` 时触发 clear-top，把正在显示的 `HarnessActivity`
 * 直接销毁；而 `HarnessActivity.onDestroy()` 会撤销一次性会话凭据，用户看到的就是
 * 「对话界面闪一下就没了，还回不去」。
 *
 * 本 Activity 全透明、无动画，只做一次转发后立即 `finish()`：
 *  - 一次性会话凭据仍然有效（`HarnessActivity` 还活着）→ 直接把已有的对话界面带回前台；
 *  - 否则（进程被回收、用户已退出对话）→ 回到管理界面。
 *
 * 刻意不指向 `singleTask` 的 `MainActivity`，因此不会发生 clear-top。
 * 本 Activity 不导出，只有本应用的 PendingIntent 能启动它。
 */
class KeepAliveEntryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不设置布局：本 Activity 只负责转发，不应出现任何可见界面。
        val target = if (AppAuthenticationState.isHarnessAuthenticated()) {
            HarnessActivity::class.java
        } else {
            MainActivity::class.java
        }
        try {
            startActivity(
                Intent(this, target).addFlags(
                    // NEW_TASK：从通知启动时本 Activity 可能不在任何任务栈中。
                    // SINGLE_TOP：目标已在栈顶时复用，不产生重复实例。
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        } catch (_: Throwable) {
            // 转发失败时保持静默：通知入口不能把应用带崩。
        }
        finish()
    }
}
