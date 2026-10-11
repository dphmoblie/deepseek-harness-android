package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件修复调度策略的纯逻辑测试。
 *
 * 这层的价值在于两条容易写反的语义：**同一代次成功后不再重复修复**，
 * 以及**换了运行时代次即使上次失败过也必须立即重试** —— 后者写错会让一次
 * 失败把自愈永久堵死，而自愈正是修复「工具调用全部失败」的手段。
 *
 * 第三条语义是**用户主动修复不受闸门约束**：闸门与冷却都是为自愈设的节流，
 * 人工重试必须绕开它们，否则用户在设置页点「立即修复插件」会毫无反应。
 */
class PluginRepairPolicyTest {
    private val generationA = "ubuntu-24.04-arm64|0.1.19-preview|aaaa"
    private val generationB = "ubuntu-24.04-arm64|0.1.20-preview|bbbb"
    private val cooldown = PluginRepairPolicy.FAILURE_COOLDOWN_MILLIS

    private fun attempt(
        generation: String = generationA,
        repairedGeneration: String? = null,
        lastFailureGeneration: String? = null,
        lastFailureAtMillis: Long = 0L,
        nowMillis: Long = 1_000_000L,
        explicit: Boolean = false,
    ): Boolean = PluginRepairPolicy.shouldAttempt(
        generation = generation,
        repairedGeneration = repairedGeneration,
        lastFailureGeneration = lastFailureGeneration,
        lastFailureAtMillis = lastFailureAtMillis,
        nowMillis = nowMillis,
        explicit = explicit,
    )

    @Test
    fun `从未尝试过时应当尝试`() {
        assertTrue(attempt())
    }

    @Test
    fun `同一代次成功后不再重复修复`() {
        // 成功即按代次记账：没有换运行时就不会产生新的坏形态。
        assertFalse(attempt(generation = generationA, repairedGeneration = generationA))
    }

    @Test
    fun `换了运行时代次后重新尝试`() {
        // 换了运行时代次（rootfs 被替换）必须重新自愈。
        assertTrue(attempt(generation = generationB, repairedGeneration = generationA))
    }

    @Test
    fun `失败后在冷却窗口内退避`() {
        val failedAt = 5_000_000L
        // 失败后立刻再次读取插件列表：不能再等一次完整超时。
        assertFalse(
            attempt(
                lastFailureGeneration = generationA,
                lastFailureAtMillis = failedAt,
                nowMillis = failedAt + 1,
            ),
        )
        // 冷却窗口内的任意时刻都不重试。
        assertFalse(
            attempt(
                lastFailureGeneration = generationA,
                lastFailureAtMillis = failedAt,
                nowMillis = failedAt + cooldown - 1,
            ),
        )
    }

    @Test
    fun `冷却窗口结束后可以重试`() {
        val failedAt = 5_000_000L
        // 到点即可重试：偶发失败（运行时目录短暂不可读）不应被长期压制。
        assertTrue(
            attempt(
                lastFailureGeneration = generationA,
                lastFailureAtMillis = failedAt,
                nowMillis = failedAt + cooldown,
            ),
        )
        assertTrue(
            attempt(
                lastFailureGeneration = generationA,
                lastFailureAtMillis = failedAt,
                nowMillis = failedAt + cooldown * 10,
            ),
        )
    }

    @Test
    fun `换代次立刻绕过冷却窗口`() {
        // 关键语义：换代次后即使刚失败过也必须立即重试，否则一次失败会永久堵住自愈。
        val failedAt = 5_000_000L
        assertTrue(
            attempt(
                generation = generationB,
                lastFailureGeneration = generationA,
                lastFailureAtMillis = failedAt,
                nowMillis = failedAt + 1,
            ),
        )
    }

    @Test
    fun `先失败后成功时以成功记账为准`() {
        // 先失败、冷却过后成功：此后同代次不再尝试，失败记录不应把它拉回重试。
        assertFalse(
            attempt(
                repairedGeneration = generationA,
                lastFailureGeneration = generationA,
                lastFailureAtMillis = 5_000_000L,
                nowMillis = 5_000_000L + cooldown * 10,
            ),
        )
    }

    @Test
    fun `用户主动修复时同一代次成功后仍然重试`() {
        // 人工重试的语义就是「再试一次」：成功记账只对自愈生效。
        assertTrue(
            attempt(
                generation = generationA,
                repairedGeneration = generationA,
                explicit = true,
            ),
        )
    }

    @Test
    fun `用户主动修复时冷却窗口内也立即重试`() {
        // 用户刚点过修复又点一次、或上一轮自愈刚失败，都不应该让按钮点下去毫无反应。
        val failedAt = 5_000_000L
        assertTrue(
            attempt(
                lastFailureGeneration = generationA,
                lastFailureAtMillis = failedAt,
                nowMillis = failedAt + 1,
                explicit = true,
            ),
        )
    }

    @Test
    fun `不传 explicit 时自愈闸门照旧生效`() {
        // 反向自检：explicit 只关掉闸门与冷却，默认调用路径的判定一条都不能放松 ——
        // 否则上面两个「主动修复」用例可能只是被一个恒真的实现骗过去的。
        val failedAt = 5_000_000L
        assertTrue(attempt(generation = generationA))
        assertFalse(attempt(generation = generationA, repairedGeneration = generationA, explicit = false))
        assertFalse(
            attempt(
                lastFailureGeneration = generationA,
                lastFailureAtMillis = failedAt,
                nowMillis = failedAt + 1,
                explicit = false,
            ),
        )
    }
}
