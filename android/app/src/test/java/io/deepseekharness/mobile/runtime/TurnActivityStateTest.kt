package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnActivityStateTest {
    private val session = "0123456789abcdef"
    private val otherSession = "fedcba9876543210"

    @Test
    fun `重复和旧轮信号不改变活跃任务`() {
        val state = TurnActivityState()
        assertTrue(state.record(session, 2, true, 100))
        assertFalse(state.record(session, 2, true, 200))
        assertFalse(state.record(session, 1, false, 300))
        assertFalse(state.record(session, 1, true, 400))
        assertEquals(TurnActivityState.Snapshot(1, 100), state.snapshot(500))
        assertTrue(state.record(session, 2, false, 600))
        assertFalse(state.record(session, 2, false, 700))
        assertFalse(state.record(session, 2, true, 800))
        assertEquals(TurnActivityState.Snapshot(0, null), state.snapshot(900))
    }

    @Test
    fun `收口先到时同轮迟到的开始不能复活任务`() {
        val state = TurnActivityState()
        assertTrue(state.record(session, 1, false, 100))
        assertFalse(state.record(session, 1, true, 200))
        assertEquals(TurnActivityState.Snapshot(0, null), state.snapshot(200))
        assertTrue(state.record(session, 2, true, 300))
        assertEquals(TurnActivityState.Snapshot(1, 300), state.snapshot(300))
    }

    @Test
    fun `新轮替换旧轮且一个会话结束不影响其它会话`() {
        val state = TurnActivityState()
        state.record(session, 1, true, 100)
        state.record(otherSession, 1, true, 200)
        assertEquals(TurnActivityState.Snapshot(2, 100), state.snapshot(200))
        state.record(session, 2, true, 300)
        assertEquals(TurnActivityState.Snapshot(2, 200), state.snapshot(300))
        assertFalse(state.record(session, 1, false, 400))
        assertTrue(state.record(otherSession, 1, false, 500))
        assertEquals(TurnActivityState.Snapshot(1, 300), state.snapshot(500))
    }

    @Test
    fun `会话超限淘汰最早状态且活跃数量有上限`() {
        val state = TurnActivityState()
        repeat(TurnActivityState.MAX_SESSIONS + 1) { index ->
            assertTrue(state.record(index.toString(16).padStart(16, '0'), 1, true, index.toLong()))
        }
        assertEquals(
            TurnActivityState.Snapshot(TurnActivityState.MAX_SESSIONS, 1),
            state.snapshot(TurnActivityState.MAX_SESSIONS.toLong()),
        )
        // 第一条已淘汰，同轮信号能重新进入；表和活跃数仍然有界。
        assertTrue(state.record("0000000000000000", 1, true, 300))
        assertEquals(TurnActivityState.MAX_SESSIONS, state.snapshot(300).activeCount)
        assertEquals(2L, state.snapshot(300).startedAtMs)
    }

    @Test
    fun `六小时无新信号清除活跃但保留轮次防止迟到开始`() {
        val state = TurnActivityState()
        state.record(session, 1, true, 100)
        state.record(otherSession, 1, true, 200)
        val deadline = 100 + TurnActivityState.STALE_AFTER_MS
        assertEquals(TurnActivityState.Snapshot(2, 100), state.snapshot(deadline - 1))
        assertEquals(TurnActivityState.Snapshot(1, 200), state.snapshot(deadline))
        assertFalse(state.record(session, 1, true, deadline + 1))
        assertEquals(TurnActivityState.Snapshot(0, null), state.snapshot(deadline + 100))
        assertTrue(state.record(session, 2, true, deadline + 200))
        assertEquals(TurnActivityState.Snapshot(1, deadline + 200), state.snapshot(deadline + 200))
    }

    @Test
    fun `清空同时重置活跃和轮次去重`() {
        val state = TurnActivityState()
        state.record(session, 5, true, 100)
        state.record(otherSession, 5, false, 200)
        state.clear()
        assertEquals(TurnActivityState.Snapshot(0, null), state.snapshot(300))
        assertTrue(state.record(session, 1, true, 400))
        assertTrue(state.record(otherSession, 1, true, 500))
        assertEquals(TurnActivityState.Snapshot(2, 400), state.snapshot(500))
    }
}
