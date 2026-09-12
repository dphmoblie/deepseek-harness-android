package io.deepseekharness.mobile.runtime

import android.content.Context
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 进程级运行时持有者。
 *
 * 背景：`MobileRuntimePlugin.handleOnDestroy()` 会在 Activity 销毁时执行（划掉最近任务
 * 也会触发），而 PRoot→node 的 Harness 子进程不会随插件一起退出。若插件在销毁时直接
 * shutdown，后台保持就完全失去意义，还会把 Harness 留在无法管理的状态。因此
 * [MobileRuntimeController] 的实际持有者迁移到本对象，由「插件订阅者」与「前台服务」
 * 共同决定何时真正释放运行时。
 *
 * 释放规则：
 *  - 插件销毁（WebView 侧消失）：仅移除订阅者；前台服务仍在负责时保留运行时。
 *  - 前台服务销毁：仅在没有任何插件订阅者时释放运行时。
 *  - 两者都不再持有：调用 [MobileRuntimeController.shutdown]，语义与旧的插件销毁路径一致。
 *
 * 线程模型：本对象的全部状态变更都在 [lock] 内完成，回调与 shutdown 一律在锁外执行，
 * 避免阻塞式关停（最长数秒）与事件分发互相等待。
 */
object RuntimeHost {
    private val lock = ReentrantLock()
    private var applicationContext: Context? = null
    private var controller: MobileRuntimeController? = null
    private var foregroundServiceActive = false
    private val sinks = linkedSetOf<RuntimeEventSink>()

    /**
     * 事件分发器：先复制订阅者列表再在锁外回调。
     * 单个订阅者抛错不得影响运行时或其他订阅者。
     */
    private val fanOut = object : RuntimeEventSink {
        override fun onProgress(snapshot: RuntimeStateSnapshot) {
            dispatch { sink -> sink.onProgress(snapshot) }
        }

        override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) {
            dispatch { sink -> sink.onTerminalOutput(sessionId, dataBase64, suppressPublicOutput) }
        }

        override fun onTerminalExit(sessionId: String, exitCode: Int) {
            dispatch { sink -> sink.onTerminalExit(sessionId, exitCode) }
        }
    }

    private fun dispatch(action: (RuntimeEventSink) -> Unit) {
        val current = lock.withLock { sinks.toList() }
        current.forEach { sink ->
            try {
                action(sink)
            } catch (_: Throwable) {
                // 订阅者是 WebView 桥接层：其异常不得中断运行时状态机。
            }
        }
    }

    /**
     * 插件侧获取共享运行时，并登记事件订阅者。
     * 已存在（例如前台服务在插件销毁期间保留下来）时复用同一实例与同一套会话。
     */
    fun acquire(context: Context, sink: RuntimeEventSink): MobileRuntimeController = lock.withLock {
        sinks.add(sink)
        controller ?: createLocked(context)
    }

    /**
     * 插件（WebView 侧）销毁：移除订阅者，并在没有前台服务负责时释放运行时。
     * 返回值仅用于测试断言，调用方无需处理。
     */
    fun detachPluginSink(sink: RuntimeEventSink): Boolean {
        val released = lock.withLock {
            sinks.remove(sink)
            if (HarnessKeepAlivePolicy.shouldReleaseRuntimeOnPluginDetach(foregroundServiceActive)) {
                takeControllerLocked()
            } else {
                null
            }
        }
        released?.shutdown()
        return released != null
    }

    /** 前台服务进入前台：此后插件销毁不再释放运行时。 */
    fun attachForegroundService() = lock.withLock {
        foregroundServiceActive = true
    }

    /** 前台服务销毁：仅在插件已不再订阅时释放运行时。 */
    fun detachForegroundService(): Boolean {
        val released = lock.withLock {
            foregroundServiceActive = false
            if (sinks.isEmpty()) takeControllerLocked() else null
        }
        released?.shutdown()
        return released != null
    }

    /** 前台服务是否正在负责运行时（供状态快照与插件生命周期判断使用）。 */
    fun isForegroundServiceActive(): Boolean = lock.withLock { foregroundServiceActive }

    /** 当前共享运行时；从未创建或已释放时为 null。 */
    fun controllerOrNull(): MobileRuntimeController? = lock.withLock { controller }

    /** 进程级释放：仅供测试与完整拆除使用。 */
    fun shutdown() {
        val released = lock.withLock {
            sinks.clear()
            foregroundServiceActive = false
            takeControllerLocked()
        }
        released?.shutdown()
    }

    private fun createLocked(context: Context): MobileRuntimeController {
        val application = context.applicationContext
        applicationContext = application
        return MobileRuntimeController(application, fanOut).also { controller = it }
    }

    private fun takeControllerLocked(): MobileRuntimeController? {
        val current = controller ?: return null
        controller = null
        return current
    }
}
