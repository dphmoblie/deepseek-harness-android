package io.deepseekharness.mobile.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class ShizukuBindDispatcherTest {
    private class QueuedExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        var reject = false

        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException()
            tasks.addLast(command)
        }
    }

    @Test
    fun bindingRunsOnlyAfterBackgroundExecutorAcceptsTask() {
        val executor = QueuedExecutor()
        val dispatcher = ShizukuBindDispatcher(executor)
        var bindings = 0

        assertTrue(dispatcher.submit({ true }, { bindings += 1 }, {}))
        assertEquals(0, bindings)
        executor.tasks.removeFirst().run()
        assertEquals(1, bindings)
    }

    @Test
    fun staleGenerationIsCancelledBeforeBinderCall() {
        val executor = QueuedExecutor()
        val dispatcher = ShizukuBindDispatcher(executor)
        var current = true
        var bindings = 0
        var failures = 0

        assertTrue(dispatcher.submit({ current }, { bindings += 1 }, { failures += 1 }))
        current = false
        executor.tasks.removeFirst().run()

        assertEquals(0, bindings)
        assertEquals(1, failures)
    }

    @Test
    fun shutdownPreventsNewBindingAndRejectedExecutorIsReported() {
        val executor = QueuedExecutor()
        val dispatcher = ShizukuBindDispatcher(executor)
        var failures = 0

        dispatcher.shutdown()
        assertFalse(dispatcher.submit({ true }, {}, { failures += 1 }))
        assertEquals(0, failures)

        val rejected = ShizukuBindDispatcher(QueuedExecutor().also { it.reject = true })
        assertFalse(rejected.submit({ true }, {}, { failures += 1 }))
        assertEquals(0, failures)
    }
}
