package io.deepseekharness.mobile.runtime

/**
 * 任务通知的**纯策略**：哪种状态变化值得打扰用户（登记册 §5.5，设计见 `docs/任务通知.md`）。
 *
 * 设计里最重要的一条是「**拿不到的内容就不显示**」，所以这里刻意只覆盖**内容确定**的状态，
 * 并且只返回「原因码」而不是文案——文案由原生侧查字符串资源（可 i18n），策略本身不碰 Android API，
 * 因此可以在 JVM 单测里穷举。
 *
 * **不做什么**（与设计文档一致）：
 *  - 不报安装/解压/下载的中间进度：那些是「正在发生」而不是「已发生」，报出来必然要编百分比；
 *  - 不在用户主动停止时通知：用户刚按下按钮，他不需要被告知；
 *  - 不把通知当保活手段：进程被系统强制停止后通知也可能消失，恢复路径是重新连接。
 */
internal enum class TaskNotificationKind {
    /** 运行中的 Harness 自行退出：用户可能已经切到别的应用，需要知道它停了。 */
    HARNESS_STOPPED,

    /** 还没真正跑起来就退出了（启动阶段失败）：与「运行中自行退出」是两件事，文案必须不同。 */
    HARNESS_EXITED_DURING_START,

    /** 首次安装运行环境完成。 */
    RUNTIME_INSTALLED,

    /** 已有运行环境被换成新版本（安装耗时最长的一种，用户几乎必然切走）。 */
    RUNTIME_UPDATED,
}

internal object TaskNotificationPolicy {
    /**
     * 自行退出的收尾该发哪一条通知。
     *
     * 区分依据是**退出前所处的阶段**：`RUNNING` 说明它确实跑起来过（用户可能正在用它），
     * 其余阶段说明它压根没起来（用户按了启动却一直没看到界面）——后者更需要一句明确的失败提示，
     * 否则用户只会觉得「点了没反应」。
     *
     * 调用点天然只在**自行退出**时触发：用户主动停止会先把进程引用清空，
     * 观察者拿不到当前进程就直接返回了（见 `RuntimeSupervisor.onHarnessExited`），
     * 因此这里不需要再判断「是不是用户停的」。
     */
    fun forHarnessExit(previousPhase: RuntimePhase?): TaskNotificationKind =
        if (previousPhase == RuntimePhase.RUNNING) {
            TaskNotificationKind.HARNESS_STOPPED
        } else {
            TaskNotificationKind.HARNESS_EXITED_DURING_START
        }

    /**
     * 安装/更新完成该发哪一条。
     *
     * **判定依据必须在安装之前取**：装完之后两者都是「已安装」，再判断就只能靠猜。
     * 这在文案上是实打实的差别——首次安装的用户需要知道「可以开始用了」，
     * 刚更新完的用户需要知道「原来那套还在，只是换了版本」。装错了他会以为数据丢了。
     */
    fun forInstallCompleted(hadRuntimeBefore: Boolean): TaskNotificationKind =
        if (hadRuntimeBefore) {
            TaskNotificationKind.RUNTIME_UPDATED
        } else {
            TaskNotificationKind.RUNTIME_INSTALLED
        }
}
