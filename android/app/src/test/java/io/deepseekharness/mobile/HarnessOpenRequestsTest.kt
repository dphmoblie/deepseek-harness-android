package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HarnessOpenRequestsTest {
    @Test
    fun queueTimeCountsTowardDeadlineAndExpiredRequestsNeverReadAccess() {
        val now = AtomicLong(1_000)
        val requests = HarnessOpenRequests(now::get)
        val request = requests.accept()
        now.addAndGet(HarnessOpenRequests.TIMEOUT_MS)
        val failure = assertThrows(RuntimeFailure::class.java) {
            requests.launch(request, { error("expired request read access") }) { error("expired request dispatched") }
        }
        assertEquals("BRIDGE_TIMEOUT", failure.code)
    }

    @Test
    fun deadlineIsRecheckedAfterWaitingForAccessBeforeAnySideEffects() {
        val now = AtomicLong(0)
        val requests = HarnessOpenRequests(now::get)
        val request = requests.accept()
        val failure = assertThrows(RuntimeFailure::class.java) {
            requests.launch(request, {
                now.set(HarnessOpenRequests.TIMEOUT_MS)
                "access"
            }) { error("expired request dispatched") }
        }
        assertEquals("BRIDGE_TIMEOUT", failure.code)
    }

    @Test
    fun newerSessionWinsEvenWhenOlderAccessReadFinishesLast() {
        checkBlockedAccess(supersede = true)
    }

    @Test
    fun stopResetOrDestructionInvalidationPreventsDelayedDispatch() {
        checkBlockedAccess(supersede = false)
    }

    private fun checkBlockedAccess(supersede: Boolean) {
        val requests = HarnessOpenRequests { 0L }
        val old = requests.accept()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val opened = mutableListOf<String>()
        try {
            val pending = worker.submit<RuntimeFailure> {
                assertThrows(RuntimeFailure::class.java) {
                    requests.launch(old, {
                        reading.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        "old-session"
                    }) { opened.add(it) }
                }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            if (supersede) {
                // 不等旧读取释放锁，新请求就必须能完成授权和派发。
                requests.launch(requests.accept(), { "new-session" }) { opened.add(it) }
            } else {
                requests.invalidate()
            }
            release.countDown()
            assertEquals("HARNESS_OPEN_CANCELLED", pending.get(5, TimeUnit.SECONDS).code)
            assertEquals(if (supersede) listOf("new-session") else emptyList<String>(), opened)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun finalDispatchIsSerializedWithInvalidation() {
        val requests = HarnessOpenRequests { 0L }
        val request = requests.accept()
        val dispatching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidating = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val launch = workers.submit {
                requests.launch(request, { "access" }) {
                    dispatching.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(dispatching.await(5, TimeUnit.SECONDS))
            val cancellation = workers.submit {
                invalidating.countDown()
                requests.invalidate()
            }
            assertTrue(invalidating.await(5, TimeUnit.SECONDS))
            assertFalse(cancellation.isDone)
            release.countDown()
            launch.get(5, TimeUnit.SECONDS)
            cancellation.get(5, TimeUnit.SECONDS)
            assertThrows(RuntimeFailure::class.java) {
                requests.launch(request, { "access" }) { error("cancelled request dispatched") }
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
