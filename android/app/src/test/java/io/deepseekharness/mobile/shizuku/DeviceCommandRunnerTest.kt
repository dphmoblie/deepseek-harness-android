package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DeviceCommandRunnerTest {
    private val requestId = "12345678-1234-1234-1234-123456789abc"
    private val runner = DeviceCommandRunner { _, _ -> }

    @Test
    fun injectedScriptIsOneShellWordAroundAFixedTemplate() {
        val parameters = mapOf(
            DeviceCommand.SCREENSHOT to "",
            DeviceCommand.UI_DUMP to "",
            DeviceCommand.TAP to "10,20",
            DeviceCommand.INPUT_TEXT to "hello world",
        )
        for ((command, param) in parameters) {
            val input = runner.buildInput(requestId, command, param)
            val body = input.trimEnd('\n')
            assertTrue("注入文本必须是单行：$command", body.none { it == '\n' })
            assertTrue("必须走内层固定 shell：$command", input.startsWith("/system/bin/sh -c '"))
            assertTrue(input.endsWith("'\n"))
            // 内层脚本不含单引号：外层用单引号包裹，因此不存在引用歧义。
            val inner = body.removePrefix("/system/bin/sh -c '").removeSuffix("'")
            assertFalse("内层脚本不得含单引号：$command", inner.contains('\''))
            // 标记只以**未展开的字面量**出现；真实 token 由内层 shell 展开产生。
            assertTrue(input.contains("__DSH_B_${requestId}_\${dsh_nonce}__"))
            assertTrue(input.contains("__DSH_E_${requestId}_\${dsh_nonce}__:"))
            // 不再注入 stty，也不再依赖固定 sleep 清缓冲。
            assertFalse("不得再注入 stty：$command", input.contains("stty"))
        }
    }

    @Test
    fun simpleCommandsUseTheStatusOfTheCommandItself() {
        val screenshot = runner.buildInput(requestId, DeviceCommand.SCREENSHOT, "")
        assertTrue(screenshot.contains("screencap -p | toybox base64; echo \"__DSH_E_${requestId}_\${dsh_nonce}__:\$?\""))

        val tap = runner.buildInput(requestId, DeviceCommand.TAP, "12,34")
        assertTrue(tap.contains("input tap 12 34; echo \"__DSH_E_${requestId}_\${dsh_nonce}__:\$?\""))

        // 双引号包裹（参数校验已排除 $、"、反引号、反斜杠），内层脚本因此完全不含单引号。
        val inputText = runner.buildInput(requestId, DeviceCommand.INPUT_TEXT, "hello world")
        assertTrue(inputText.contains("input text \"hello world\""))
    }

    @Test
    fun uiDumpProbesToolKeepsStderrAndGradesFailures() {
        val input = runner.buildInput(requestId, DeviceCommand.UI_DUMP, "")
        val temporary = "/data/local/tmp/dsh-ui-$requestId.xml"
        val fallback = "/sdcard/dsh-ui-$requestId.xml"

        // 请求级临时文件；清理挂在 EXIT 上，内层 shell 退出即生效。
        assertTrue(input.contains("dsh_tmp=$temporary"))
        assertTrue(input.contains("dsh_fallback=$fallback"))
        assertTrue(input.contains("trap \"rm -f \$dsh_tmp \$dsh_fallback\" EXIT HUP INT TERM"))
        // 先探测工具是否存在。
        assertTrue(input.contains("command -v uiautomator >/dev/null 2>&1"))
        // 两次失败的 stderr 都保留（2>&1），不再被 cat 的 ENOENT 掩盖。
        assertTrue(input.contains("uiautomator dump --compressed \$dsh_tmp 2>&1"))
        assertTrue(input.contains("uiautomator dump \$dsh_fallback 2>&1"))
        assertTrue(input.contains("dsh_first=\$?"))
        assertTrue(input.contains("dsh_second=\$?"))
        // 两条路径的产物都必须非空。
        assertTrue(input.contains("[ -s \$dsh_tmp ]"))
        assertTrue(input.contains("[ -s \$dsh_fallback ]"))
        // 三类失败各自的退出码：无工具 3 / dump 失败 4 / 产物为空 5。
        assertTrue(input.contains("dsh_rc=3"))
        assertTrue(input.contains("dsh_rc=4"))
        assertTrue(input.contains("dsh_rc=5"))
        assertTrue(input.endsWith("__DSH_E_${requestId}_\${dsh_nonce}__:\$dsh_rc\"'\n"))
        assertFalse("不得再用 && cat 掩盖真实失败", input.contains("&& cat"))
    }

    @Test(expected = RuntimeFailure::class)
    fun uiDumpRejectsUntrustedRequestId() {
        runner.buildInput("../../shared", DeviceCommand.UI_DUMP, "")
    }

    @Test(expected = RuntimeFailure::class)
    fun tapRejectsOutOfRangeCoordinates() {
        runner.buildInput(requestId, DeviceCommand.TAP, "70000,1")
    }

    @Test(expected = RuntimeFailure::class)
    fun inputTextRejectsShellMetacharacters() {
        runner.buildInput(requestId, DeviceCommand.INPUT_TEXT, "a;reboot")
    }

    /**
     * 端到端（JVM 内）：注入文本被 mksh 行编辑器原样回显——含未展开的 `${dsh_nonce}` 与
     * 折行重绘插入的 `\r`、`<`、退格——解析仍必须给出干净的 payload 与真实退出码。
     * 这正是真机上 `tap` / `inputText` 必然超时、`screenshot` 被判非法的同一个输入形态。
     */
    @Test
    fun pollutedEchoStillYieldsACleanResult() {
        val sessionId = "session-clean"
        val injected = CompletableFuture<String>()
        val polling = DeviceCommandRunner { _, data ->
            injected.complete(String(Base64.getDecoder().decode(data), Charsets.UTF_8))
        }
        val result = CompletableFuture.supplyAsync {
            polling.execute(sessionId, DeviceCommand.INPUT_TEXT, "a", TimeUnit.SECONDS.toMillis(5))
        }
        val input = injected.get(5, TimeUnit.SECONDS)
        val generated = Regex("__DSH_B_([0-9a-f-]{36})_").find(input)?.groupValues?.get(1)
        assertTrue("注入文本必须包含请求标识", generated != null)

        val echoed = input.trimEnd('\n') + "          <\b\b\b\b\b\b\b\b\b\b\r\r\n"
        val stream = echoed +
            "__DSH_B_${generated}_777-888__\r\n" +
            "__DSH_E_${generated}_777-888__:0\r\n" +
            ":/ $ "
        polling.onOutput(sessionId, Base64.getEncoder().encodeToString(stream.toByteArray(Charsets.UTF_8)))

        // 两个真实标记之间只有 echo 自带的换行：回显、折行重绘、退格全部落在区间之外。
        assertEquals(DeviceCommandResult(true, 0, "\r\n", false, null), result.get(5, TimeUnit.SECONDS))
    }

    /** 「BEGIN 已出现但 END 永不到来」在会话结束时立刻收口为受控错误，而不是空等 60 秒。 */
    @Test
    fun sessionExitEndsAnIncompleteCommandWithAControlledError() {
        val sessionId = "session-lost"
        val injected = CompletableFuture<String>()
        val polling = DeviceCommandRunner { _, data ->
            injected.complete(String(Base64.getDecoder().decode(data), Charsets.UTF_8))
        }
        val result = CompletableFuture.supplyAsync {
            polling.execute(sessionId, DeviceCommand.UI_DUMP, "", TimeUnit.SECONDS.toMillis(30))
        }
        val input = injected.get(5, TimeUnit.SECONDS)
        val generated = Regex("__DSH_B_([0-9a-f-]{36})_").find(input)?.groupValues?.get(1)
        assertTrue(generated != null)

        polling.onOutput(
            sessionId,
            Base64.getEncoder()
                .encodeToString("__DSH_B_${generated}_777-888__\r\npartial output".toByteArray(Charsets.UTF_8)),
        )
        polling.onSessionExit(sessionId)

        val value = result.get(5, TimeUnit.SECONDS)
        assertFalse(value.ok)
        assertEquals(DeviceCommandProtocol.ERROR_PROTOCOL, value.errorCode)
    }

    /** uiDump 的退出码分级映射到独立错误码，不再统一成 DEVICE_COMMAND_FAILED。 */
    @Test
    fun uiDumpExitCodesMapToDistinctErrorCodes() {
        val codes = mapOf(3 to "UI_DUMP_NO_TOOL", 4 to "UI_DUMP_FAILED", 5 to "UI_DUMP_EMPTY", 9 to "DEVICE_COMMAND_FAILED")
        for ((exitCode, errorCode) in codes) {
            assertEquals(errorCode, runWithExitCode(DeviceCommand.UI_DUMP, exitCode).errorCode)
        }
        // 其它命令的非零退出码仍是通用失败码。
        assertEquals("DEVICE_COMMAND_FAILED", runWithExitCode(DeviceCommand.TAP, 3).errorCode)
        assertEquals(0, runWithExitCode(DeviceCommand.UI_DUMP, 0).exitCode)
    }

    private fun runWithExitCode(command: DeviceCommand, exitCode: Int): DeviceCommandResult {
        val sessionId = "session-exit-$command-$exitCode"
        val injected = CompletableFuture<String>()
        val polling = DeviceCommandRunner { _, data ->
            injected.complete(String(Base64.getDecoder().decode(data), Charsets.UTF_8))
        }
        val result = CompletableFuture.supplyAsync {
            polling.execute(sessionId, command, if (command == DeviceCommand.TAP) "1,1" else "", TimeUnit.SECONDS.toMillis(5))
        }
        val input = injected.get(5, TimeUnit.SECONDS)
        val generated = Regex("__DSH_B_([0-9a-f-]{36})_").find(input)?.groupValues?.get(1)
        val stream = "__DSH_B_${generated}_777-888__\r\nUI_DUMP_FAILED: stderr kept\r\n" +
            "__DSH_E_${generated}_777-888__:$exitCode\r\n"
        polling.onOutput(sessionId, Base64.getEncoder().encodeToString(stream.toByteArray(Charsets.UTF_8)))
        return result.get(5, TimeUnit.SECONDS)
    }
}
