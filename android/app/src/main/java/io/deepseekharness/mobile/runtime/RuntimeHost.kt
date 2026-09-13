package io.deepseekharness.mobile.runtime

import android.content.Context
import io.deepseekharness.mobile.shizuku.DeviceCommandRunner
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 与运行时同生命周期的进程级资源（当前为设备桥）。
 *
 * 由 [RuntimeHost] 统一持有与拆除：这类资源既不能随 Activity 重建而重复创建，
 * 也不能在 Activity 销毁时被提前拆掉。
 */
interface RuntimeScopedResource {
    /** 幂等拆除；重复调用不抛异常。 */
    fun stop()
}

/**
 * 进程级运行时持有者。
 *
 * 背景：`MobileRuntimePlugin.handleOnDestroy()` 会在 Activity 销毁时执行（划掉最近任务
 * 也会触发），而 PRoot→node 的 Harness 子进程不会随插件一起退出。若插件在销毁时直接
 * shutdown，后台保持就完全失去意义，还会把 Harness 留在无法管理的状态。因此
 * [MobileRuntimeController] 的实际持有者迁移到本对象，由「插件订阅者」与「前台服务」
 * 共同决定何时真正释放运行时。
 *
 * 设备桥与设备命令同样是进程级资源。保活生效时 Harness 进程仍在运行：Activity 重建
 * 既不能重建桥（`RuntimeSupervisor.configureDeviceBridge` 会以 `RUNTIME_BUSY` 拒绝
 * 重复配置），也不能拆桥（guest 仍在用它）。因此这里与运行时同生命周期地持有它们。
 *
 * 释放规则：
 *  - 插件销毁（WebView 侧消失）：仅移除订阅者；前台服务仍在负责时保留运行时与设备桥。
 *  - 前台服务销毁：仅在没有任何插件订阅者时释放运行时与设备桥。
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
     * 设备桥实例。桥由 app 包构造并配置，本对象只负责「整个进程只创建一次」
     * 与「运行时释放时一并拆除」这两件事。
     */
    private var deviceBridge: RuntimeScopedResource? = null
    private var deviceCommands: DeviceCommandRunner? = null

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

    /**
     * 取得进程级设备桥，或按 [create] 构建一次。
     *
     * [create] 必须完成「启动桥 + `controller.configureDeviceBridge(...)`」，
     * 且只在真正创建时执行：Harness 已经在跑时重复配置会被 supervisor 拒绝，
     * 而旧实现正是在 Activity 重建时重复配置才导致插件注册失败。
     */
    fun acquireDeviceBridge(create: () -> RuntimeScopedResource): RuntimeScopedResource = lock.withLock {
        deviceBridge ?: create().also { deviceBridge = it }
    }

    /** 设备桥实例；未创建时为 null（调用方自行决定是否降级）。 */
    fun deviceBridgeOrNull(): RuntimeScopedResource? = lock.withLock { deviceBridge }

    /**
     * 进程级设备命令执行器。
     *
     * 写入目标在调用时解析，因此运行时被释放并以新实例重建后依然指向当前运行时。
     */
    fun deviceCommands(): DeviceCommandRunner = lock.withLock {
        deviceCommands ?: DeviceCommandRunner { sessionId, dataBase64 ->
            val current = controller
                ?: throw RuntimeFailure("RUNTIME_CLOSED", "本机运行时正在关闭")
            current.writeTerminal(sessionId, dataBase64)
        }.also { deviceCommands = it }
    }

    /** 设备命令执行器；未创建时为 null。事件分发等热路径用它避免顺手创建执行器。 */
    fun deviceCommandsOrNull(): DeviceCommandRunner? = lock.withLock { deviceCommands }

    /** 结束本插件实例发起中的设备命令（插件销毁时调用；不拆除进程级资源）。 */
    fun cancelDeviceCommands() {
        deviceCommandsOrNull()?.cancelAll()
    }

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

    /**
     * 摘除运行时，并一并拆除设备桥与设备命令。
     * 桥必须在 harness 停止之后再拆：guest 会在运行时存活期间持续使用它。
     */
    private fun takeControllerLocked(): MobileRuntimeController? {
        val current = controller ?: return null
        // 运行时释放时清空输出尾部登记，避免已结束会话继续驻留内存。
        HarnessOutputTailSource.clear()
        controller = null
        releaseDeviceResourcesLocked()
        return current
    }

    private fun releaseDeviceResourcesLocked() {
        deviceCommands?.cancelAll()
        deviceCommands = null
        val bridge = deviceBridge ?: return
        deviceBridge = null
        try {
            bridge.stop()
        } catch (_: Throwable) {
            // 进程级资源拆除失败不阻断运行时释放。
        }
    }
}
