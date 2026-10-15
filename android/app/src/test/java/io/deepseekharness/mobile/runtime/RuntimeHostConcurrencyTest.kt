package io.deepseekharness.mobile.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用真实 Host 事件出口与生命周期锁固定交错；等待可中断，回归失败也不会遗留死锁线程。 */
class RuntimeHostConcurrencyTest {
    @Test
    fun progressAndShutdownDoNotWaitOnEachOthersLocks() {
        withSubscriber { fanOut, sink ->
            assertEventDoesNotBlockShutdown { fanOut.onProgress(snapshot) }
            assertEquals(1, sink.progress)
        }
    }

    @Test
    fun terminalCallbacksCanReadCommandRunnerWhileShutdownOwnsHostLock() {
        withSubscriber { fanOut, sink ->
            assertEventDoesNotBlockShutdown {
                fanOut.onTerminalOutput("session", "eA==", true)
                fanOut.onTerminalExit("session", 0)
            }
            assertEquals(1, sink.output)
            assertEquals(1, sink.exit)
        }
    }

    @Test
    fun oneSubscriberFailureDoesNotPreventOtherSubscribersFromReceivingEvents() {
        withSubscriber { fanOut, sink ->
            val subscribers = subscribers()
            val failing = object : RuntimeEventSink {
                override fun onProgress(snapshot: RuntimeStateSnapshot) = error("subscriber closed")
                override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
                override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
            }
            subscribers.clear()
            subscribers.add(failing)
            subscribers.add(sink)
            try {
                fanOut.onProgress(snapshot)
                assertEquals(1, sink.progress)
            } finally {
                subscribers.remove(failing)
            }
        }
    }

    private fun assertEventDoesNotBlockShutdown(dispatch: () -> Unit) {
        val hostLock = field("lock") as ReentrantLock
        val supervisorLock = ReentrantLock()
        val supervisorHeld = CountDownLatch(1)
        val shutdownOwnsHost = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val event = Thread({
            try {
                supervisorLock.withLock {
                    supervisorHeld.countDown()
                    check(shutdownOwnsHost.await(3, TimeUnit.SECONDS))
                    dispatch()
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                completed.countDown()
            }
        }, "test-runtime-event").apply { isDaemon = true }
        val shutdown = Thread({
            try {
                check(supervisorHeld.await(3, TimeUnit.SECONDS))
                hostLock.withLock {
                    shutdownOwnsHost.countDown()
                    // 对应 shutdown 在持 Host 锁时等待 supervisor；失败后可中断以释放测试资源。
                    supervisorLock.lockInterruptibly()
                    supervisorLock.unlock()
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                completed.countDown()
            }
        }, "test-runtime-shutdown").apply { isDaemon = true }
        event.start()
        shutdown.start()
        val finished = completed.await(3, TimeUnit.SECONDS)
        try {
            assertTrue("事件与关停不能因 Host 与 supervisor 的锁顺序互相等待", finished)
            assertNull(failure.get())
        } finally {
            shutdown.interrupt()
            event.interrupt()
            shutdown.join(3_000)
            event.join(3_000)
        }
    }

    private fun withSubscriber(check: (RuntimeEventSink, RecordingSink) -> Unit) {
        val hostLock = field("lock") as ReentrantLock
        val subscribers = subscribers()
        val sink = RecordingSink()
        val previous = hostLock.withLock { subscribers.toList().also { subscribers.clear(); subscribers.add(sink) } }
        try {
            check(field("fanOut") as RuntimeEventSink, sink)
        } finally {
            hostLock.withLock { subscribers.clear(); subscribers.addAll(previous) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun subscribers() = field("sinks") as MutableSet<RuntimeEventSink>

    private fun field(name: String): Any = RuntimeHost::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(RuntimeHost)

    private class RecordingSink : RuntimeEventSink {
        var progress = 0
        var output = 0
        var exit = 0
        override fun onProgress(snapshot: RuntimeStateSnapshot) { progress++ }
        override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) {
            RuntimeHost.deviceCommandsOrNull()
            output++
        }
        override fun onTerminalExit(sessionId: String, exitCode: Int) { exit++ }
    }

    private val snapshot = RuntimeStateSnapshot(RuntimePhase.READY, "arm64-v8a", null, false, 0, 0, false, null, null)
}
