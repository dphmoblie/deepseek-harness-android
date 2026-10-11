package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用（APK）升级迁移判定策略的纯逻辑测试。
 *
 * 这层的价值在于三条容易写错的语义：
 *  1. **从未迁移过必须跑一次** —— 否则「新增的核对步骤」在已经装过插件的设备上永不生效；
 *  2. **版本号没变、运行时代次也没变时不许反复跑** —— 迁移里含一次 repair（最多 90 秒），
 *     每次启动都跑会把「启动 Harness」变成一件碰运气的事；
 *  3. **降级也要跑** —— 降级后盘上的状态可能是更新版本的应用写下的，核对一次是幂等的，
 *     而不跑却把记账写成新版本号等于谎报「这一步做过了」。
 */
class PluginMigrationPolicyTest {
    private fun decide(
        lastMigratedVersionCode: Int?,
        currentVersionCode: Int = 35,
        generationChanged: Boolean = false,
    ): PluginMigrationPolicy.Decision =
        PluginMigrationPolicy.decide(lastMigratedVersionCode, currentVersionCode, generationChanged)

    @Test
    fun `从未迁移过时立即迁移`() {
        val decision = decide(lastMigratedVersionCode = null)
        assertTrue(decision.migrate)
        assertEquals(PluginMigrationPolicy.REASON_FIRST_RUN, decision.reason)
    }

    @Test
    fun `同一版本且运行时代次没变时不重复迁移`() {
        val decision = decide(lastMigratedVersionCode = 35)
        assertFalse(decision.migrate)
        assertEquals(PluginMigrationPolicy.REASON_CURRENT, decision.reason)
    }

    @Test
    fun `应用升级后迁移一次`() {
        val decision = decide(lastMigratedVersionCode = 34, currentVersionCode = 35)
        assertTrue(decision.migrate)
        assertEquals(PluginMigrationPolicy.REASON_APP_UPGRADED, decision.reason)
    }

    @Test
    fun `应用降级后同样迁移一次`() {
        // 盘上状态可能由更新版本的应用写下；迁移幂等，而"不跑却记账"会是谎报。
        val decision = decide(lastMigratedVersionCode = 36, currentVersionCode = 35)
        assertTrue(decision.migrate)
        assertEquals(PluginMigrationPolicy.REASON_APP_DOWNGRADED, decision.reason)
    }

    @Test
    fun `版本号没变但运行时代次变了要迁移`() {
        // 与既有自愈闸门正交：运行时升级后即使应用没换版本，也要按账本核对一次。
        val decision = decide(lastMigratedVersionCode = 35, generationChanged = true)
        assertTrue(decision.migrate)
        assertEquals(PluginMigrationPolicy.REASON_RUNTIME_CHANGED, decision.reason)
    }

    @Test
    fun `升级与代次变化同时发生时按升级记账`() {
        // 原因只用于诊断与记账，判据优先级要稳定：版本变化优先于代次变化。
        val decision = decide(lastMigratedVersionCode = 34, currentVersionCode = 35, generationChanged = true)
        assertEquals(PluginMigrationPolicy.REASON_APP_UPGRADED, decision.reason)
    }

    @Test
    fun `取不到版本号时不做特殊处理：两边一致就不迁移`() {
        // BuildConfig.VERSION_CODE 正常恒为正；这里钉住"非正值不会把迁移永久关掉"这一点：
        // 从未迁移过（为 null）仍然跑，两边一致（0 与 0）时与正数同判。
        assertTrue(decide(lastMigratedVersionCode = null, currentVersionCode = 0).migrate)
        assertFalse(decide(lastMigratedVersionCode = 0, currentVersionCode = 0).migrate)
        assertTrue(decide(lastMigratedVersionCode = 0, currentVersionCode = 1).migrate)
    }
}
