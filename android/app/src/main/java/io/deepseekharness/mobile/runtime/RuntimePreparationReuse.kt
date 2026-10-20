package io.deepseekharness.mobile.runtime

/**
 * 一次启动内「运行时准备」的去重。
 *
 * 为什么需要：`verifyGuest`（自检）与 `launch`（拉起 Harness）各自都要先把运行时准备好
 * （DNS/hosts 落盘、沙箱脚本刷新、运行器链接、loader 实测执行、访客执行位巡检）。
 * 一次启动因此把同一份工作做两遍：两次带 `fsync` 的小文件写入、一次 loader 实测 `exec`、
 * 一遍访客执行位巡检。第二遍发生在第一遍之后几秒内，环境不可能在中间自行变好或变坏。
 *
 * 去重口径刻意收得很窄，宁可少省一点也不引入「准备好了又过期」的窗口：
 *  - 只在**一次启动事务内**（[begin]/[end]，由 `RuntimeSupervisor.startHarness` 包住整次启动）
 *    生效；事务外（终端、插件管理、运行时自检这些单独调用 `resolver.launch` 的路径）保持
 *    原样，每次重新准备。
 *  - 只有指纹完全一致才复用：指纹包含运行时根路径、`runtimeId`、`version` 与 rootfs `sha256`，
 *    安装/切换版本会让它失配。
 *  - 事务结束即丢弃：下一次启动一定重新写 DNS/hosts —— 网络可能在两次启动之间变化，跨启动
 *    复用会拿一份过期的 resolver 配置去启动 PRoot。
 *
 * 线程模型：调用方（`RuntimeLaunchResolver`）始终在自身的锁内使用，这里不再加锁。
 */
internal class RuntimePreparationReuse {
    private var active = false
    private var fingerprint: String? = null

    /** 启动事务开始：丢弃上一次残留的指纹。 */
    fun begin() {
        active = true
        fingerprint = null
    }

    /** 启动事务结束：无论成功、失败还是取消，都不再复用本次准备结果。 */
    fun end() {
        active = false
        fingerprint = null
    }

    /** 本次启动是否已经用同一个指纹准备过。 */
    fun canReuse(candidate: String): Boolean = active && fingerprint == candidate

    /** 记下准备完成的指纹；不在启动事务内时忽略（事务外的调用不产生缓存）。 */
    fun markPrepared(candidate: String) {
        if (active) fingerprint = candidate
    }
}
