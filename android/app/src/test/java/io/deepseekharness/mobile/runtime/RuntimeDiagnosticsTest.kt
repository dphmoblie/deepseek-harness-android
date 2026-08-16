package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeDiagnosticsTest {
    @Test
    fun `classifies ptrace denial without exposing process output`() {
        val failure = RuntimeDiagnostics.prootFailure(
            ProcessProbeResult(
                exitCode = 1,
                timedOut = false,
                output = "proot error: ptrace(TRACEME): Operation not permitted /private/path",
            ),
        )

        assertEquals("PROOT_PTRACE_DENIED", failure?.code)
        assertEquals("系统内核拒绝 PRoot 所需的 ptrace 操作", failure?.message)
    }

    @Test
    fun `does not classify a successful probe`() {
        assertNull(RuntimeDiagnostics.prootFailure(ProcessProbeResult(0, false, "ignored")))
    }

    @Test
    fun `retries without seccomp only for an explicit incompatibility`() {
        val explicitSeccompFailure = ProcessProbeResult(
            exitCode = 1,
            timedOut = false,
            output = "proot error: execve(\"/bin/bash\"): Operation not permitted\n" +
                "To workaround it, set the env. variable PROOT_NO_SECCOMP to 1.",
        )
        val unrelatedFailure = ProcessProbeResult(
            exitCode = 1,
            timedOut = false,
            output = "proot error: loader was not found",
        )

        assertEquals(true, RuntimeDiagnostics.shouldRetryWithoutSeccomp(explicitSeccompFailure))
        assertEquals(false, RuntimeDiagnostics.shouldRetryWithoutSeccomp(unrelatedFailure))
        assertEquals(false, RuntimeDiagnostics.shouldRetryWithoutSeccomp(ProcessProbeResult(null, true, "seccomp")))
    }

    @Test
    fun `uses a fixed error for required bind failures`() {
        val failure = RuntimeDiagnostics.requiredBindFailure()

        assertEquals("PROOT_REQUIRED_BIND_FAILED", failure.code)
        assertEquals("PRoot 无法挂载 Ubuntu 必需的系统路径", failure.message)
    }

    @Test
    fun `classifies bounded harness startup failures`() {
        assertEquals(
            "HARNESS_PORT_IN_USE",
            RuntimeDiagnostics.harnessFailure("Error: listen EADDRINUSE 127.0.0.1").code,
        )
        assertEquals(
            "HARNESS_MODULE_MISSING",
            RuntimeDiagnostics.harnessFailure("Error [ERR_MODULE_NOT_FOUND]").code,
        )
        assertEquals(
            "HARNESS_EXITED",
            RuntimeDiagnostics.harnessFailure("unknown startup failure at /private/path").code,
        )
    }

    @Test
    fun `classifies the preferred compatibility attempt first`() {
        val failure = RuntimeDiagnostics.guestFailure(
            listOf(
                ProcessProbeResult(1, false, "proot error: loader was not found"),
                ProcessProbeResult(1, false, "proot error: seccomp operation not permitted"),
            ),
            "NODE_RUNTIME_FAILED",
            "内置 Node.js 无法在当前设备运行",
        )

        assertEquals("PROOT_GUEST_EXEC_FAILED", failure.code)
    }

    @Test
    fun `does not let the original seccomp error hide a fallback CPU failure`() {
        val failure = RuntimeDiagnostics.guestFailure(
            listOf(
                ProcessProbeResult(132, false, "Illegal instruction"),
                ProcessProbeResult(1, false, "proot error: seccomp operation not permitted"),
            ),
            "NODE_RUNTIME_FAILED",
            "内置 Node.js 无法在当前设备运行",
        )

        assertEquals("NODE_CPU_UNSUPPORTED", failure.code)
    }

    @Test
    fun `classifies unsupported Node CPU without exposing output`() {
        val failure = RuntimeDiagnostics.guestFailure(
            listOf(ProcessProbeResult(132, false, "Illegal instruction at /private/path")),
            "NODE_RUNTIME_FAILED",
            "内置 Node.js 无法在当前设备运行",
        )

        assertEquals("NODE_CPU_UNSUPPORTED", failure.code)
        assertEquals("设备 CPU 无法执行内置 Node.js", failure.message)
    }
}
