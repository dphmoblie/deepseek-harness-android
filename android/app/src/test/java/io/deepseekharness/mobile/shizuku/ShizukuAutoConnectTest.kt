package io.deepseekharness.mobile.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class ShizukuAutoConnectTest {
    private class QueuedExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        var reject = false

        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException()
            tasks.addLast(command)
        }

        fun runNext() = tasks.removeFirst().run()
    }

    @Test
    fun startupAndResumeShareOneQueuedTask() {
        val executor = QueuedExecutor()
        var connections = 0
        var currentValid = false
        val connector = ShizukuAutoConnect(executor, { true }) { current ->
            currentValid = current()
            connections += 1
        }

        repeat(3) { connector.request() }
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(1, connections)
        assertTrue(currentValid)
        assertTrue(executor.tasks.isEmpty())
    }

    @Test
    fun callbackDuringDetectionRequestsOneMoreAttemptWithoutParallelTasks() {
        val executor = QueuedExecutor()
        var connections = 0
        var queuedDuringCallback = false
        lateinit var connector: ShizukuAutoConnect
        connector = ShizukuAutoConnect(executor, { true }) {
            connections += 1
            if (connections == 1) repeat(3) { connector.request() }
            queuedDuringCallback = executor.tasks.isEmpty()
        }

        connector.request()
        executor.runNext()
        assertEquals(2, connections)
        assertTrue(queuedDuringCallback)
    }

    @Test
    fun destroyPreventsQueuedAndLaterRequests() {
        val executor = QueuedExecutor()
        var active = true
        var connections = 0
        val connector = ShizukuAutoConnect(executor, { active }) { connections += 1 }

        connector.request()
        active = false
        executor.runNext()
        connector.request()
        assertEquals(0, connections)
        assertTrue(executor.tasks.isEmpty())
    }

    @Test
    fun failedDetectionAndRejectedSubmissionAllowLaterRetry() {
        val executor = QueuedExecutor()
        var connections = 0
        val connector = ShizukuAutoConnect(executor, { true }) {
            connections += 1
            if (connections == 1) throw IllegalStateException("unavailable")
        }

        executor.reject = true
        connector.request()
        executor.reject = false
        connector.request()
        executor.runNext()
        connector.request()
        executor.runNext()
        assertEquals(2, connections)
    }

    @Test
    fun newBinderCanConnectWhileOldDetectionIsStuckAndOldCompletionKeepsNewTask() {
        val executor = QueuedExecutor()
        var connections = 0
        var oldCurrent: (() -> Boolean)? = null
        var newTaskQueued = false
        var newCurrentValid = false
        lateinit var connector: ShizukuAutoConnect
        connector = ShizukuAutoConnect(executor, { true }) { current ->
            connections += 1
            if (connections == 1) {
                // 在旧检测返回前模拟新 Binder 到达并投递任务；无需依赖线程时序。
                oldCurrent = current
                connector.request(binderChanged = true)
                newTaskQueued = executor.tasks.size == 1
            } else {
                newCurrentValid = current()
            }
        }

        connector.request()
        executor.runNext()
        // 旧任务退出不能清除新代次的 scheduled；普通前台回调仍只复用新任务。
        connector.request()
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(2, connections)
        assertTrue(newTaskQueued)
        assertTrue(newCurrentValid)
        assertFalse(oldCurrent!!())
    }

    @Test
    fun destroyInvalidatesPermissionCheckAlreadyInProgress() {
        val executor = QueuedExecutor()
        var active = true
        var current: (() -> Boolean)? = null
        val connector = ShizukuAutoConnect(executor, { active }) { current = it }

        connector.request()
        executor.runNext()
        assertTrue(current!!())
        active = false
        assertFalse(current!!())
    }
}
