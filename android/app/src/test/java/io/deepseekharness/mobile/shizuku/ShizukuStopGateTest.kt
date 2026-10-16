package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ShizukuStopGateTest {
    @Test(timeout = 5_000)
    fun 旧实例清理超时后新实例仍被挡住直到旧解绑完成() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val oldCalls = AtomicInteger(0)
        val newCalls = AtomicInteger(0)
        val oldGate = ShizukuStopGate {
            reader.set(Thread.currentThread())
            oldCalls.incrementAndGet()
            entered.countDown()
            release.await()
        }
        val newGate = ShizukuStopGate { newCalls.incrementAndGet(); Unit }

        try {
            try {
                oldGate.await(25)
                fail("旧清理应明确超时")
            } catch (failure: RuntimeFailure) {
                assertEquals("SHIZUKU_UNBIND_TIMEOUT", failure.code)
            }
            assertTrue("旧清理尚未开始", entered.await(1, TimeUnit.SECONDS))
            assertTrue("新控制器也必须禁止重新绑定", newGate.isStopping())
            try {
                newGate.await(25)
                fail("新实例不能绕过同一 UserService 的在途清理")
            } catch (failure: RuntimeFailure) {
                assertEquals("SHIZUKU_UNBIND_TIMEOUT", failure.code)
            }
            assertEquals("跨实例仍仅有一个清理任务", 1, oldCalls.get())
            assertEquals("旧解绑完成前不能开始新实例清理", 0, newCalls.get())
        } finally {
            release.countDown()
            reader.get()?.join(1_000)
        }

        assertFalse("旧解绑实际完成才放行新实例", newGate.isStopping())
        newGate.await(1_000)
        assertEquals("完成后新实例可以管理自己的连接", 1, newCalls.get())
    }

    @Test(timeout = 5_000)
    fun 挂起的清理按预算返回且重复停止复用同一任务() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val gate = ShizukuStopGate {
            calls.incrementAndGet()
            entered.countDown()
            release.await()
        }
        try {
            repeat(2) {
                try {
                    gate.await(50)
                    fail("挂起清理应明确超时")
                } catch (failure: RuntimeFailure) {
                    assertEquals("SHIZUKU_UNBIND_TIMEOUT", failure.code)
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertTrue("超时后旧清理仍在途，不能重新绑定", gate.isStopping())
            }
            assertEquals("重复停止不能累积工作线程", 1, calls.get())
        } finally {
            release.countDown()
            gate.await(1_000)
        }
        assertFalse("旧清理实际完成才放行", gate.isStopping())
    }

    @Test
    fun 成功后可开始下一次清理() {
        val calls = AtomicInteger(0)
        val gate = ShizukuStopGate { calls.incrementAndGet(); Unit }
        gate.await()
        gate.await()
        assertEquals(2, calls.get())
        assertFalse(gate.isStopping())
    }

    @Test
    fun 清理失败保留原错误码且放开闸门() {
        val gate = ShizukuStopGate { throw RuntimeFailure("VIRTUAL_SCREEN_CLOSE_FAILED", "无法关闭副屏") }
        try {
            gate.await()
            fail("失败不能伪装成停止成功")
        } catch (failure: RuntimeFailure) {
            assertEquals("VIRTUAL_SCREEN_CLOSE_FAILED", failure.code)
        }
        assertFalse(gate.isStopping())
    }

    @Test
    fun 中断调用方不取消旧清理() {
        val release = CountDownLatch(1)
        val gate = ShizukuStopGate { release.await(); Unit }
        try {
            Thread.currentThread().interrupt()
            try {
                gate.await()
                fail("中断应明确返回")
            } catch (failure: RuntimeFailure) {
                assertEquals("SHIZUKU_UNBIND_INTERRUPTED", failure.code)
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue(gate.isStopping())
            }
        } finally {
            Thread.interrupted()
            release.countDown()
            gate.await(1_000)
        }
    }
}
