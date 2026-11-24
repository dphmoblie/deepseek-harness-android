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
    fun `classifies an exited runner with no output as execution failure rather than timeout`() {
        val failure = RuntimeDiagnostics.prootFailure(ProcessProbeResult(139, false, ""))

        assertEquals("PROOT_GUEST_EXEC_FAILED", failure?.code)
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
    fun `does not misclassify cordis plugin loader stack traces as PRoot execution failures`() {
        val cordisPluginWarning = """
            dsh: warning: 1 entry did not activate
            mobile-shizuku-tools (@deepseek-harness/dsh-mobile-shizuku): Error: cannot get property "workingDirectory" without inject
                at bindService (file:///opt/dsh/plugins/dsh-mobile-shizuku/lib/pty-guard.js:335:12)
                at Fiber.execute (file:///opt/dsh/node_modules/.pnpm/@deepseek-ai+cordis@4.0.5_@deepseek-ai+cordis-plugin-include@1.0.10_@deepseek-ai+cordis-plugin-loader@1.0.6/node_modules/@deepseek-ai/cordis/lib/index.js:1068:24)
            dsh web: http://127.0.0.1:3080/?token=redacted
        """.trimIndent()

        assertEquals("HARNESS_EXITED", RuntimeDiagnostics.harnessFailure(cordisPluginWarning).code)
        assertNull(RuntimeDiagnostics.prootFailure(ProcessProbeResult(1, false, cordisPluginWarning)))
        assertEquals(
            "PROOT_GUEST_EXEC_FAILED",
            RuntimeDiagnostics.harnessFailure("proot error: loader was not found").code,
        )
        assertEquals(
            "PROOT_GUEST_EXEC_FAILED",
            RuntimeDiagnostics.harnessFailure("cannot execute libdsh_proot_loader.so: Permission denied").code,
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
