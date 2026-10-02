package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台保持策略的纯逻辑测试。
 *
 * 服务生命周期本身依赖 Android 框架，这里只覆盖可以脱离设备验证的决策：
 * 何时启动/停止前台服务、插件销毁时是否释放运行时、何时必须提示重新连接。
 * 设备侧的锁屏、返回桌面、划掉最近任务属于人工验收项（见 docs/后台保持与恢复.md）。
 */
class HarnessKeepAlivePolicyTest {
    @Test
    fun startsServiceOnlyWhenEnabledAndRunning() {
        assertTrue(HarnessKeepAlivePolicy.shouldRunService(keepRuntimeInBackground = true, runtimeRunning = true))
        // 设置关闭时不提升优先级。
        assertFalse(HarnessKeepAlivePolicy.shouldRunService(keepRuntimeInBackground = false, runtimeRunning = true))
        // 默认值（未安装、未启动、启动失败）都不留下空转通知。
        assertFalse(HarnessKeepAlivePolicy.shouldRunService(keepRuntimeInBackground = true, runtimeRunning = false))
        assertFalse(HarnessKeepAlivePolicy.shouldRunService(keepRuntimeInBackground = false, runtimeRunning = false))
    }

    @Test
    fun recoversAfterBootOnlyForExplicitRunningIntent() {
        assertTrue(
            HarnessKeepAlivePolicy.shouldRecoverAfterBoot(
                keepRuntimeInBackground = true,
                lastIntent = RuntimeIntent.RUNNING,
            ),
        )
        // 后台保持关闭或用户已停止时，不因系统广播擅自启动运行时。
        assertFalse(
            HarnessKeepAlivePolicy.shouldRecoverAfterBoot(
                keepRuntimeInBackground = false,
                lastIntent = RuntimeIntent.RUNNING,
            ),
        )
        assertFalse(
            HarnessKeepAlivePolicy.shouldRecoverAfterBoot(
                keepRuntimeInBackground = true,
                lastIntent = RuntimeIntent.STOPPED,
            ),
        )
        assertFalse(
            HarnessKeepAlivePolicy.shouldRecoverAfterBoot(
                keepRuntimeInBackground = true,
                lastIntent = RuntimeIntent.UNKNOWN,
            ),
        )
    }

    @Test
    fun keepsRuntimeWhileForegroundServiceIsResponsible() {
        // 划掉最近任务：前台服务仍在负责运行时时，插件销毁不得 shutdown。
        assertFalse(
            HarnessKeepAlivePolicy.shouldReleaseRuntimeOnPluginDetach(
                foregroundServiceActive = true,
                hasOtherSubscribers = false,
            ),
        )
        // 没有前台服务时保持旧行为：最后一个订阅者退出即释放运行时。
        assertTrue(
            HarnessKeepAlivePolicy.shouldReleaseRuntimeOnPluginDetach(
                foregroundServiceActive = false,
                hasOtherSubscribers = false,
            ),
        )
    }

    @Test
    fun keepsRuntimeWhileAnotherPluginInstanceSubscribes() {
        // 新插件实例已经接管运行时（划掉最近任务后立刻重进应用），旧实例收尾时
        // 保活尚未恢复也不能释放：否则新实例手里的控制器被关闭，之后每次启动都
        // 只能得到 RUNTIME_CLOSED（真机 0.2.2 日志中出现过三连该错误码）。
        assertFalse(
            HarnessKeepAlivePolicy.shouldReleaseRuntimeOnPluginDetach(
                foregroundServiceActive = false,
                hasOtherSubscribers = true,
            ),
        )
        // 前台服务与另一个订阅者都在：同样保留运行时（两个条件都满足才允许释放）。
        assertFalse(
            HarnessKeepAlivePolicy.shouldReleaseRuntimeOnPluginDetach(
                foregroundServiceActive = true,
                hasOtherSubscribers = true,
            ),
        )
    }

    @Test
    fun stopsServiceWhenNoRuntimeCanBeManaged() {
        // 进程被系统回收后重启：旧认证凭据已丢失，服务立即结束而不是空转。
        assertTrue(HarnessKeepAlivePolicy.shouldStopServiceWithoutController(controllerAvailable = false))
        assertFalse(HarnessKeepAlivePolicy.shouldStopServiceWithoutController(controllerAvailable = true))
    }

    @Test
    fun requiresReconnectOnlyWhenNoOwnedRunningRuntimeExists() {
        // 本进程持有正在运行的 Harness：不需要重新连接。
        assertFalse(
            HarnessKeepAlivePolicy.requiresReconnect(
                runtimeOwnedRunning = true,
                residualProcess = true,
                lastIntent = RuntimeIntent.RUNNING,
            ),
        )
        // 残留进程无法复用旧凭据：需要重新连接。
        assertTrue(
            HarnessKeepAlivePolicy.requiresReconnect(
                runtimeOwnedRunning = false,
                residualProcess = true,
                lastIntent = RuntimeIntent.RUNNING,
            ),
        )
        // 没有残留但上次意图是运行中：进程被回收，同样需要重新连接。
        assertTrue(
            HarnessKeepAlivePolicy.requiresReconnect(
                runtimeOwnedRunning = false,
                residualProcess = false,
                lastIntent = RuntimeIntent.RUNNING,
            ),
        )
        // 用户已显式停止：不提示重新连接。
        assertFalse(
            HarnessKeepAlivePolicy.requiresReconnect(
                runtimeOwnedRunning = false,
                residualProcess = false,
                lastIntent = RuntimeIntent.STOPPED,
            ),
        )
        // 从未记录：不提示，避免首次安装就出现告警。
        assertFalse(
            HarnessKeepAlivePolicy.requiresReconnect(
                runtimeOwnedRunning = false,
                residualProcess = false,
                lastIntent = RuntimeIntent.UNKNOWN,
            ),
        )
    }

    @Test
    fun parsesPersistedIntentSafely() {
        assertEquals(RuntimeIntent.RUNNING, RuntimeIntent.parse("running"))
        assertEquals(RuntimeIntent.STOPPED, RuntimeIntent.parse("stopped"))
        // 旧配置缺失、内容损坏或未来新增取值都降级为 unknown。
        assertEquals(RuntimeIntent.UNKNOWN, RuntimeIntent.parse(null))
        assertEquals(RuntimeIntent.UNKNOWN, RuntimeIntent.parse(""))
        assertEquals(RuntimeIntent.UNKNOWN, RuntimeIntent.parse("paused"))
        assertEquals(RuntimeIntent.UNKNOWN, RuntimeIntentRecord.EMPTY.intent)
        assertEquals(null, RuntimeIntentRecord.EMPTY.phase)
        assertEquals(0L, RuntimeIntentRecord.EMPTY.updatedAtMillis)
    }

    @Test
    fun recoveryRecordCarriesNoProcessOrCredentialDetail() {
        // 恢复记录只保留意图、阶段与时间三个实例字段：任何新增字段都必须先确认
        // 它不携带地址、凭据、进程号或终端内容。
        val fields = RuntimeIntentRecord::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()
        assertEquals(listOf("intent", "phase", "updatedAtMillis"), fields)
        val record = RuntimeIntentRecord(RuntimeIntent.RUNNING, RuntimePhase.RUNNING, 1_700_000_000_000L)
        assertEquals(RuntimeIntent.RUNNING, record.intent)
        assertEquals(RuntimePhase.RUNNING, record.phase)
        assertEquals(1_700_000_000_000L, record.updatedAtMillis)
    }
}
