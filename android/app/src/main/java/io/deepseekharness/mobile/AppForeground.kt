package io.deepseekharness.mobile

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用是否处于前台（登记册 §5.5：应用在前台时不该弹任务通知）。
 *
 * **为什么用计数器而不是布尔标志**：本应用有两个 Activity（`MainActivity` 外壳与
 * `HarnessActivity` 控制台），而且后者会叠在前者之上——用布尔值的话，控制台关闭时会把
 * 「仍在前台」误判成「已切后台」，于是一次正常的返回操作就会弹出一条「Harness 已停止」。
 * 计数器在 `onStart` 加、`onStop` 减，只有两者都停了才算后台。
 *
 * **为什么不引入生命周期库**：`androidx.lifecycle.ProcessLifecycleOwner` 会多一个依赖与初始化时机
 * 问题，而这里需要的语义只有「有没有任何一个 Activity 处于 started 状态」，
 * 计数器的行为可以被 JVM 单测完整覆盖。
 *
 * **不做的事**：不问「屏幕是否点亮」，也不问「用户是否正在看」——那些问题没有可靠答案，
 * 猜错会让本该发出的通知被吞掉。判据只有「有没有 Activity 处于 started」。
 */
internal object AppForeground {
    private val startedActivities = AtomicInteger(0)
    private val resumedActivity = AtomicReference<WeakReference<Activity>?>(null)

    fun onActivityStarted() {
        startedActivities.incrementAndGet()
    }

    /**
     * 减到 0 就不再往下减：生命周期回调本应成对，但配置变化、进程恢复等路径下
     * 少一次 `onStart` 或多一次 `onStop` 都可能出现；计数变负会让 `isForeground` 永远为假，
     * 通知就再也发不出来了——宁可把它夹在 0。
     */
    fun onActivityStopped() {
        startedActivities.updateAndGet { current -> if (current <= 0) 0 else current - 1 }
    }

    fun onActivityResumed(activity: Activity) {
        resumedActivity.set(WeakReference(activity))
    }

    fun onActivityPaused(activity: Activity) {
        val current = resumedActivity.get()
        if (current?.get() === activity) {
            resumedActivity.compareAndSet(current, null)
        }
    }

    /** 当前正在前台交互的 Activity（弱引用，不阻止回收）；无可用前台页面时返回 null。 */
    fun currentActivity(): Activity? {
        val activity = resumedActivity.get()?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return activity
    }

    fun isForeground(): Boolean = startedActivities.get() > 0
}
