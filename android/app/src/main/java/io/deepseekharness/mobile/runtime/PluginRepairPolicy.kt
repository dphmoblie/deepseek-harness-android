package io.deepseekharness.mobile.runtime

/**
 * 「已安装插件运行时链接修复」的尝试调度策略（**纯逻辑**，便于在 JVM 单测里穷举）。
 *
 * 需要调度的原因：修复动作要遍历已安装插件目录，单次上限 90 秒（见 `RuntimePluginManager`）；
 * 而它的失败通常是**确定性**的（权限不足、运行时锚点解析不到、目录形态异常），
 * 重试一次就会同样失败一次。若不加约束，每次读取插件列表都会白等满超时，
 * 用户每打开一次插件设置页就要卡一次。
 *
 * 两条语义必须同时成立：
 *  - **成功即按代次记账**：同一运行时代次只修复一次 —— 没有换运行时就不会产生新的坏形态
 *    （新坏形态的两个来源是运行时升级与插件安装/更新，二者都会让代次或插件目录内容变化）。
 *  - **换代次必须立即重试**：哪怕上次失败过，换了运行时代次也要重新尝试，
 *    否则一次失败会把自愈永久堵死。
 */
object PluginRepairPolicy {
    /**
     * 失败后的冷却窗口。
     *
     * 取 5 分钟：既避免确定性失败在用户连续操作设置页时反复阻塞，
     * 又不至于让一次偶发失败（例如运行时目录短暂不可读）长时间压制自愈。
     */
    const val FAILURE_COOLDOWN_MILLIS = 5 * 60 * 1000L

    /**
     * 本次是否应当尝试修复。
     *
     * @param generation 当前运行时代次指纹（runtimeId + 版本 + rootfs 摘要）
     * @param repairedGeneration 最近一次**成功**修复所对应的代次
     * @param lastFailureGeneration 最近一次**失败**所对应的代次
     * @param lastFailureAtMillis 最近一次失败的时刻；从未失败时为 0
     * @param nowMillis 当前时刻
     */
    fun shouldAttempt(
        generation: String,
        repairedGeneration: String?,
        lastFailureGeneration: String?,
        lastFailureAtMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (generation == repairedGeneration) return false
        // 代次不同（含从未失败过）时立即尝试：换代次意味着运行时被替换，必须重新自愈。
        if (generation != lastFailureGeneration) return true
        return nowMillis - lastFailureAtMillis >= FAILURE_COOLDOWN_MILLIS
    }
}
