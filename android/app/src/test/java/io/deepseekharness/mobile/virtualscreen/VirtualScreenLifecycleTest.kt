package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生命周期动作（start / restart / reconnect）：动作集只有 stop 时，调用方没有任何「不重建整条链路
 * 就把副屏救回来」的手段，只能让用户回设置页重开。
 *
 * 这里钉的是**语义边界**，不是实现：restart 只承诺「会话还在时按原规格重建」，reconnect 只承诺
 * 「会话还在时重挂 binder 死亡回调」；会话已经没了就如实返回 *_UNSUPPORTED，绝不假装成功。
 */
class VirtualScreenLifecycleTest {
    private val current = "3f1a5c2e-9d4b-4f7a-8e11-2b6c0d9a7e34"
    private val other = "8b7c1d40-2e59-4a3f-9c88-5d1e6f0a2b47"

    @Test fun `生命周期动作与输入动作是两个不相交的动作集`() {
        assertEquals(setOf("start", "restart", "reconnect"), VirtualScreenPolicy.LIFECYCLE_ACTIONS)
        assertTrue(
            "生命周期动作不能混进输入白名单：输入动作会走 /system/bin/input，分发顺序一旦重叠就会把 start 当成按键注入",
            VirtualScreenPolicy.LIFECYCLE_ACTIONS.intersect(VirtualScreenPolicy.INPUT_ACTIONS).isEmpty(),
        )
        // 非生命周期动作必须被拒绝，而不是悄悄按 start 处理。
        assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.lifecycleOutcome("stop", 4, current, current)
        }
    }

    @Test fun `start 在会话已存活时报告已激活而不是重建`() {
        val outcome = VirtualScreenPolicy.lifecycleOutcome("start", 4, current, current)
        assertTrue(outcome.startable)
        assertEquals("already_active", outcome.state)
        assertNull("活着的会话不需要重建，也就没有失败", outcome.failure)
    }

    @Test fun `start 在没有会话时返回会话失效码`() {
        val outcome = VirtualScreenPolicy.lifecycleOutcome("start", 0, current, current)
        assertFalse(outcome.startable)
        assertEquals("no_session", outcome.state)
        assertEquals(VirtualScreenPolicy.SESSION_DEAD_CODE, outcome.failure?.code)
        assertEquals(VirtualScreenPolicy.SESSION_DEAD_MESSAGE, outcome.failure?.message)
    }

    @Test fun `restart 在会话存活时按原规格重建`() {
        val outcome = VirtualScreenPolicy.lifecycleOutcome("restart", 4, current, current)
        assertTrue(outcome.startable)
        assertEquals("rebuild", outcome.state)
        assertNull(outcome.failure)
        // 会话标识被换掉（用户重新开始副屏）时不能再拿旧会话去重建。
        assertFalse(VirtualScreenPolicy.lifecycleOutcome("restart", 4, current, other).startable)
    }

    @Test fun `restart 在没有会话时诚实返回不支持的重建码`() {
        val outcome = VirtualScreenPolicy.lifecycleOutcome("restart", 0, current, current)
        assertFalse("会话已经没了，原规格也无从取得，不能假装重建成功", outcome.startable)
        assertEquals("no_session", outcome.state)
        assertEquals(VirtualScreenPolicy.RESTART_UNSUPPORTED_CODE, outcome.failure?.code)
        assertTrue(outcome.failure!!.message.orEmpty().contains("重新开始"))
        assertTrue(outcome.failure!!.message.orEmpty().contains("原规格"))
    }

    @Test fun `reconnect 在会话存活时重挂连接 没有会话时返回不支持`() {
        val alive = VirtualScreenPolicy.lifecycleOutcome("reconnect", 4, current, current)
        assertTrue(alive.startable)
        assertEquals("rebound", alive.state)
        assertNull(alive.failure)

        val released = VirtualScreenPolicy.lifecycleOutcome("reconnect", 0, current, current)
        assertFalse("虚拟显示器已经释放，重新绑定 binder 也救不回来", released.startable)
        assertEquals("display_released", released.state)
        assertEquals(VirtualScreenPolicy.RECONNECT_UNSUPPORTED_CODE, released.failure?.code)
        assertTrue(released.failure!!.message.orEmpty().contains("重新开始副屏会话"))
        // 两个不支持码必须不同：调用方的处置分别是「重开会话」与「回设置页重开」。
        assertNotEquals(VirtualScreenPolicy.RESTART_UNSUPPORTED_CODE, released.failure.code)
    }

    @Test fun `生命周期结论必须自洽`() {
        for (action in VirtualScreenPolicy.LIFECYCLE_ACTIONS) {
            for (displayId in listOf(0, 4)) {
                val outcome = VirtualScreenPolicy.lifecycleOutcome(action, displayId, current, current)
                assertTrue("$action 必须给出非空 state", outcome.state.isNotEmpty())
                if (outcome.startable) {
                    assertNull("$action 可执行时不能同时带失败", outcome.failure)
                } else {
                    assertNotNull("$action 不可执行时必须说明原因", outcome.failure)
                    assertTrue("$action 的失败码不能为空", outcome.failure!!.code.isNotEmpty())
                    assertTrue("$action 的失败说明不能为空", outcome.failure!!.message.orEmpty().isNotEmpty())
                }
                // 只要会话活着，三个动作都不该被拒绝——拒绝会让调用方以为副屏已经死了。
                if (displayId == 4) assertTrue("$action 在存活会话上必须可执行", outcome.startable)
            }
        }
    }
}
