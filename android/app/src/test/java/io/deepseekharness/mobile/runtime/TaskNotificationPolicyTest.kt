package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知策略的判定表（登记册 §5.5）。
 *
 * 这里钉的是**文案分岔的依据**：退出前是否真的跑起来过，决定用户看到「已停止」
 * 还是「未能启动」。写反了不会崩，但会把「点了启动一直没反应」说成「运行中停了」，
 * 那种错误提示比没有提示更误导人。
 */
class TaskNotificationPolicyTest {
    @Test
    fun `运行中自行退出按已停止处理`() {
        assertEquals(
            TaskNotificationKind.HARNESS_STOPPED,
            TaskNotificationPolicy.forHarnessExit(RuntimePhase.RUNNING),
        )
    }

    @Test
    fun `还没跑起来就退出按启动失败处理`() {
        // 这些阶段都代表「用户按了启动但界面始终没出现」，与「运行中停掉」是两件事。
        for (phase in listOf(
            RuntimePhase.PREPARING,
            RuntimePhase.DOWNLOADING,
            RuntimePhase.VERIFYING,
            RuntimePhase.EXTRACTING,
            RuntimePhase.READY,
            RuntimePhase.STOPPING,
            RuntimePhase.ERROR,
            RuntimePhase.NOT_INSTALLED,
        )) {
            assertEquals(
                "阶段 $phase 应当按启动失败处理",
                TaskNotificationKind.HARNESS_EXITED_DURING_START,
                TaskNotificationPolicy.forHarnessExit(phase),
            )
        }
    }

    @Test
    fun `阶段未知时按启动失败处理而不是猜成已停止`() {
        // 拿不到阶段说明状态读取本身出了问题；此时说「已停止」是在断言一件没被证实的事，
        // 而说「未能启动」至少不会把用户已经用起来的会话说成还在跑。
        assertEquals(
            TaskNotificationKind.HARNESS_EXITED_DURING_START,
            TaskNotificationPolicy.forHarnessExit(null),
        )
    }

    @Test
    fun `安装完成按有没有旧运行时分成两条文案`() {
        assertEquals(
            TaskNotificationKind.RUNTIME_UPDATED,
            TaskNotificationPolicy.forInstallCompleted(hadRuntimeBefore = true),
        )
        assertEquals(
            TaskNotificationKind.RUNTIME_INSTALLED,
            TaskNotificationPolicy.forInstallCompleted(hadRuntimeBefore = false),
        )
    }
}
