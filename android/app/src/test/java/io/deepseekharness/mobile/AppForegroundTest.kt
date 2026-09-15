package io.deepseekharness.mobile

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「应用是否在前台」的计数语义（登记册 §5.5 的前台抑制）。
 *
 * 判错的两种后果都不小：**误判为前台** → 该发的通知被吞掉（用户切走后再也不知道跑完了没有）；
 * **误判为后台** → 用户正看着界面时被弹一条说他已经在看的事，而且这条会一直留到手动划掉。
 * 本类的 `AppForeground` 是单例，因此每个用例前后都要把计数清回 0。
 */
class AppForegroundTest {
    @After
    fun resetCount() {
        // 把计数减回 0：单例状态会跨用例泄漏，泄漏的后果是后面用例结论随机。
        while (AppForeground.isForeground()) AppForeground.onActivityStopped()
    }

    @Test
    fun `没有任何 Activity 时是后台`() {
        assertFalse(AppForeground.isForeground())
    }

    @Test
    fun `有 Activity 处于 started 时是前台`() {
        AppForeground.onActivityStarted()
        assertTrue(AppForeground.isForeground())
    }

    @Test
    fun `两个 Activity 叠加时关闭上层仍算前台`() {
        // 这正是不能用布尔标志的原因：控制台（HarnessActivity）叠在外壳（MainActivity）之上，
        // 关掉控制台时若直接把标志置假，一次正常的返回就会弹出一条「Harness 已停止」。
        AppForeground.onActivityStarted()
        AppForeground.onActivityStarted()
        assertTrue(AppForeground.isForeground())

        AppForeground.onActivityStopped()
        assertTrue("关掉上层 Activity 后仍应算前台", AppForeground.isForeground())

        AppForeground.onActivityStopped()
        assertFalse("两个都停了才算切到后台", AppForeground.isForeground())
    }

    @Test
    fun `多减不会把计数压成负数`() {
        // 生命周期回调本应成对，但配置变化等路径下可能多一次 onStop。
        // 计数变负会让 isForeground 永远为假 —— 通知就再也发不出来，且这种状态无法自愈。
        AppForeground.onActivityStopped()
        AppForeground.onActivityStopped()
        AppForeground.onActivityStarted()
        assertTrue("多减之后再加一次仍必须回到前台", AppForeground.isForeground())
    }
}
