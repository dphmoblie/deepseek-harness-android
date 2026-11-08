package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长操作闸门的语义：起停必须先问、长操作之间互斥、异常结束也要复位。
 *
 * 这组断言对应真机上的一个具体故障：npm 安装 Harness 期间点「打开对话」，
 * `startHarness` 排在生命周期锁后面，界面停住十几分钟，安装完又自己把 Harness 拉起来。
 */
class RuntimeOperationGateTest {
    @Test
    fun idleGateAllowsStart() {
        val gate = RuntimeOperationGate()

        assertFalse(gate.isActive())
        gate.requireIdle()
    }

    @Test
    fun activeLongOperationRejectsStartBeforeItCanQueue() {
        val gate = RuntimeOperationGate()

        val failure = assertThrows(RuntimeFailure::class.java) {
            gate.run {
                assertTrue(gate.isActive())
                gate.requireIdle()
            }
        }

        assertEquals(RuntimeOperationGate.BUSY_CODE, failure.code)
    }

    @Test
    fun longOperationsDoNotStackUp() {
        val gate = RuntimeOperationGate()

        gate.run {
            val nested = assertThrows(RuntimeFailure::class.java) { gate.run { } }
            assertEquals(RuntimeOperationGate.BUSY_CODE, nested.code)
            assertTrue("内层被拒绝后，外层仍必须保持占用", gate.isActive())
        }

        assertFalse(gate.isActive())
        gate.requireIdle()
    }

    @Test
    fun failedLongOperationStillReleasesTheGate() {
        val gate = RuntimeOperationGate()

        assertThrows(IllegalStateException::class.java) {
            gate.run { throw IllegalStateException("boom") }
        }

        assertFalse(gate.isActive())
        gate.requireIdle()
    }
}
