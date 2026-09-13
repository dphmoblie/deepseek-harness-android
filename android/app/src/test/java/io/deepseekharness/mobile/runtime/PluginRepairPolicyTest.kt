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
    ): Boolean = PluginRepairPolicy.shouldAttempt(
        generation = generation,
        repairedGeneration = repairedGeneration,
        lastFailureGeneration = lastFailureGeneration,
        lastFailureAtMillis = lastFailureAtMillis,
        nowMillis = nowMillis,
    )

    @Test
    fun attemptsWhenNothingWasTriedYet() {
        assertTrue(attempt())
    }

    @Test
    fun skipsAfterSuccessForTheSameGeneration() {
        // 成功即按代次记账：没有换运行时就不会产生新的坏形态。
        assertFalse(attempt(generation = generationA, repairedGeneration = generationA))
    }

    @Test
    fun attemptsAgainAfterTheRuntimeGenerationChanges() {
        // 换了运行时代次（rootfs 被替换）必须重新自愈。
        assertTrue(attempt(generation = generationB, repairedGeneration = generationA))
    }

    @Test
    fun backsOffWithinTheCooldownAfterAFailure() {
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
    fun retriesOnceTheCooldownElapsed() {
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
    fun newGenerationBypassesTheCooldownImmediately() {
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
    fun successWinsOverAnEarlierFailureOfTheSameGeneration() {
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
}
