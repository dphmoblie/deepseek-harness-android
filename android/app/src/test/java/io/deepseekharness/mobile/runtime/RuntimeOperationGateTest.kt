package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    @Test
    fun startLockIsReleasedAfterSuccessAndFailure() {
        val gate = RuntimeOperationGate()
        val lock = ReentrantLock()

        assertEquals("started", gate.withStartLock(lock) { "started" })
        assertFalse(lock.isLocked)
        assertThrows(IllegalStateException::class.java) {
            gate.withStartLock(lock) { throw IllegalStateException("boom") }
        }
        assertFalse(lock.isLocked)
    }

    @Test
    fun longOperationWinningTheLockAfterIdleCheckDoesNotQueueStart() {
        val gate = RuntimeOperationGate()
        val startReachedLock = CountDownLatch(1)
        val allowStartLock = CountDownLatch(1)
        val longHoldsLock = CountDownLatch(1)
        val releaseLong = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val lock = object : ReentrantLock() {
            override fun tryLock(timeout: Long, unit: TimeUnit): Boolean {
                startReachedLock.countDown()
                assertTrue(allowStartLock.await(5, TimeUnit.SECONDS))
                return super.tryLock(timeout, unit)
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val start = workers.submit<RuntimeFailure> {
                assertThrows(RuntimeFailure::class.java) { gate.withStartLock(lock) { started.set(true) } }
            }
            assertTrue(startReachedLock.await(5, TimeUnit.SECONDS))
            val long = workers.submit {
                gate.run {
                    lock.withLock {
                        longHoldsLock.countDown()
                        assertTrue(releaseLong.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(longHoldsLock.await(5, TimeUnit.SECONDS))
            allowStartLock.countDown()

            assertEquals(RuntimeOperationGate.BUSY_CODE, start.get(5, TimeUnit.SECONDS).code)
            assertFalse(started.get())
            assertTrue("长操作结束前，启动请求就应返回", lock.isLocked)
            releaseLong.countDown()
            long.get(5, TimeUnit.SECONDS)
        } finally {
            allowStartLock.countDown()
            releaseLong.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun longOperationRegisteredAfterIdleCheckIsRejectedEvenWhenStartGetsTheLock() {
        val gate = RuntimeOperationGate()
        val startReachedLock = CountDownLatch(1)
        val allowStartLock = CountDownLatch(1)
        val longRegistered = CountDownLatch(1)
        val releaseLong = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val lock = object : ReentrantLock() {
            override fun tryLock(timeout: Long, unit: TimeUnit): Boolean {
                startReachedLock.countDown()
                assertTrue(allowStartLock.await(5, TimeUnit.SECONDS))
                return super.tryLock(timeout, unit)
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val start = workers.submit<RuntimeFailure> {
                assertThrows(RuntimeFailure::class.java) { gate.withStartLock(lock) { started.set(true) } }
            }
            assertTrue(startReachedLock.await(5, TimeUnit.SECONDS))
            val long = workers.submit {
                gate.run {
                    longRegistered.countDown()
                    assertTrue(releaseLong.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(longRegistered.await(5, TimeUnit.SECONDS))
            allowStartLock.countDown()

            assertEquals(RuntimeOperationGate.BUSY_CODE, start.get(5, TimeUnit.SECONDS).code)
            assertFalse(started.get())
            assertFalse("锁内复查失败也必须解锁", lock.isLocked)
            releaseLong.countDown()
            long.get(5, TimeUnit.SECONDS)
        } finally {
            allowStartLock.countDown()
            releaseLong.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun interruptedStartPreservesTheInterruptWithoutUnlockingAnotherThread() {
        val gate = RuntimeOperationGate()
        val startReachedLock = CountDownLatch(1)
        val allowStartLock = CountDownLatch(1)
        val workerThread = AtomicReference<Thread>()
        val lock = object : ReentrantLock() {
            override fun tryLock(timeout: Long, unit: TimeUnit): Boolean {
                startReachedLock.countDown()
                assertTrue(allowStartLock.await(5, TimeUnit.SECONDS))
                return super.tryLock(timeout, unit)
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            val result = worker.submit<Pair<RuntimeFailure, Boolean>> {
                workerThread.set(Thread.currentThread())
                val failure = assertThrows(RuntimeFailure::class.java) { gate.withStartLock(lock) { error("不应启动") } }
                failure to Thread.currentThread().isInterrupted
            }
            assertTrue(startReachedLock.await(5, TimeUnit.SECONDS))
            workerThread.get().interrupt()

            val (failure, interrupted) = result.get(5, TimeUnit.SECONDS)
            assertEquals(RuntimeOperationGate.BUSY_CODE, failure.code)
            assertTrue(failure.cause is InterruptedException)
            assertTrue(interrupted)
            assertEquals(1, lock.holdCount)
        } finally {
            allowStartLock.countDown()
            lock.unlock()
            worker.shutdownNow()
        }
    }
}
