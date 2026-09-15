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

    /**
     * 受控失败码的取值是**冻结契约**：补「人话说明」不得改变任何一个码。
     *
     * 每个码都要求三件事同时成立：码与原来一致、正文里出现可操作的简体中文指引（含「下一步：」）、
     * 设备原始输出仍原样保留。措辞不做绝对断言。
     */
    @Test
    fun controlledFailureCodesKeepTheirValuesAndCarryActionableGuidance() {
        val expected = mapOf(
            3 to ("UI_DUMP_NO_TOOL" to "uiautomator"),
            4 to ("UI_DUMP_FAILED" to "uiautomator dump"),
            5 to ("UI_DUMP_EMPTY" to "空壳"),
            9 to ("DEVICE_COMMAND_FAILED" to "非零退出码"),
        )
        for ((exitCode, expectation) in expected) {
            val (errorCode, keyword) = expectation
            val result = runWithExitCode(DeviceCommand.UI_DUMP, exitCode)
            assertEquals("受控码不得因补文案而改变", errorCode, result.errorCode)
            assertFalse(result.ok)
            assertTrue("$errorCode 的说明必须给出下一步", result.text.contains("下一步："))
            assertTrue("$errorCode 的说明必须点到关键事实（$keyword）", result.text.contains(keyword))
            assertTrue("$errorCode 必须保留设备原始输出", result.text.contains("UI_DUMP_FAILED: stderr kept"))
            assertFalse("$errorCode 的说明不得做绝对断言", result.text.contains("一定") || result.text.contains("永远"))
        }
    }

    /**
     * `UI_DUMP_EMPTY` 的说明要能直接用：说清是本机没有产出可用层级（含空壳 ROM 这一常见来源），
     * 给出替代观察手段（截图），并明确不会伪造层级；不承诺修好设备或 ROM 侧的东西。
     */
    @Test
    fun uiDumpEmptyGuidanceGivesTheNextStepWithoutPromises() {
        val result = runWithExitCode(DeviceCommand.UI_DUMP, 5)
        assertEquals("UI_DUMP_EMPTY", result.errorCode)
        assertTrue(result.text.contains("uiautomator"))
        assertTrue(result.text.contains("没有产出可读的层级文件"))
        assertTrue(result.text.contains("空壳"))
        assertTrue("必须给出替代手段", result.text.contains("mobile_device_screenshot"))
        assertTrue(result.text.contains("下一步："))
        assertFalse("不得做绝对断言", result.text.contains("一定") || result.text.contains("永远"))
        assertFalse("不得承诺修好设备侧", result.text.contains("保证") || result.text.contains("我们修"))
    }

    /** 成功时正文保持原样：截图 base64 之类的 payload 不得被任何说明污染。 */
    @Test
    fun successfulResultsKeepTheirPayloadUntouched() {
        val payload = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
        // payload 区间是「BEGIN 那行 echo 的换行 + 命令输出 + 其后的换行」，成功时逐字节保持原样。
        assertEquals(
            DeviceCommandResult(true, 0, "\r\n$payload\r\n", false, null),
            runWithExitCode(DeviceCommand.SCREENSHOT, 0, payload),
        )
        // 退出码 0 的 uiDump 同样不带说明。
        assertEquals(
            DeviceCommandResult(true, 0, "\r\nUI_DUMP_FAILED: stderr kept\r\n", false, null),
            runWithExitCode(DeviceCommand.UI_DUMP, 0),
        )
        assertFalse("成功正文不得出现说明", runWithExitCode(DeviceCommand.UI_DUMP, 0).text.contains("下一步："))
    }

    /** 超时（会话仍存活）保持 DEVICE_COMMAND_TIMEOUT，但说明要讲清「没有闭合」并给出重试建议。 */
    @Test
    fun timeoutKeepsItsCodeAndExplainsWhatHappened() {
        val result = DeviceCommandRunner { _, _ -> }
            .execute("session-timeout-guidance", DeviceCommand.TAP, "1,1", 1)
        assertEquals("DEVICE_COMMAND_TIMEOUT", result.errorCode)
        assertTrue(result.text.contains("超时"))
        assertTrue(result.text.contains("下一步："))
    }

    /** 会话结束时的两种收口都带说明：BEGIN 缺失 → SESSION_LOST，BEGIN 之后无 END → 协议失败。 */
    @Test
    fun sessionEndedFailuresKeepTheirCodesAndExplainTheNextStep() {
        for (withBegin in listOf(false, true)) {
            val sessionId = "session-guidance-$withBegin"
            val injected = CompletableFuture<String>()
            val polling = DeviceCommandRunner { _, data ->
                injected.complete(String(Base64.getDecoder().decode(data), Charsets.UTF_8))
            }
            val result = CompletableFuture.supplyAsync {
                polling.execute(sessionId, DeviceCommand.UI_DUMP, "", TimeUnit.SECONDS.toMillis(30))
            }
            val input = injected.get(5, TimeUnit.SECONDS)
            val generated = Regex("__DSH_B_([0-9a-f-]{36})_").find(input)?.groupValues?.get(1)
            assertTrue("注入文本必须包含请求标识", generated != null)
            if (withBegin) {
                polling.onOutput(
                    sessionId,
                    Base64.getEncoder()
                        .encodeToString("__DSH_B_${generated}_777-888__\r\npartial output".toByteArray(Charsets.UTF_8)),
                )
            }
            polling.onSessionExit(sessionId)

            val value = result.get(5, TimeUnit.SECONDS)
            assertEquals(
                if (withBegin) DeviceCommandProtocol.ERROR_PROTOCOL else DeviceCommandProtocol.ERROR_SESSION_LOST,
                value.errorCode,
            )
            assertFalse(value.ok)
            assertTrue("会话收口必须给出下一步", value.text.contains("下一步："))
        }
    }

    /** 运行时结束（cancelAll）仍报 PLUGIN_DESTROYED，但正文说明这次调用没有执行。 */
    @Test
    fun teardownKeepsPluginDestroyedAndExplainsTheNextStep() {
        val sessionId = "session-teardown-guidance"
        val injected = CompletableFuture<String>()
        val polling = DeviceCommandRunner { _, data ->
            injected.complete(String(Base64.getDecoder().decode(data), Charsets.UTF_8))
        }
        val result = CompletableFuture.supplyAsync {
            polling.execute(sessionId, DeviceCommand.UI_DUMP, "", TimeUnit.SECONDS.toMillis(30))
        }
        injected.get(5, TimeUnit.SECONDS)
        polling.cancelAll()

        val value = result.get(5, TimeUnit.SECONDS)
        assertEquals("PLUGIN_DESTROYED", value.errorCode)
        assertFalse(value.ok)
        assertTrue(value.text.contains("运行时已经结束"))
        assertTrue(value.text.contains("下一步："))
    }

    private fun runWithExitCode(
        command: DeviceCommand,
        exitCode: Int,
        payload: String = "UI_DUMP_FAILED: stderr kept",
    ): DeviceCommandResult {
        val sessionId = "session-exit-$command-$exitCode-${payload.length}"
        val injected = CompletableFuture<String>()
        val polling = DeviceCommandRunner { _, data ->
            injected.complete(String(Base64.getDecoder().decode(data), Charsets.UTF_8))
        }
        val result = CompletableFuture.supplyAsync {
            polling.execute(sessionId, command, if (command == DeviceCommand.TAP) "1,1" else "", TimeUnit.SECONDS.toMillis(5))
        }
        val input = injected.get(5, TimeUnit.SECONDS)
        val generated = Regex("__DSH_B_([0-9a-f-]{36})_").find(input)?.groupValues?.get(1)
        val stream = "__DSH_B_${generated}_777-888__\r\n$payload\r\n" +
            "__DSH_E_${generated}_777-888__:$exitCode\r\n"
        polling.onOutput(sessionId, Base64.getEncoder().encodeToString(stream.toByteArray(Charsets.UTF_8)))
        return result.get(5, TimeUnit.SECONDS)
    }
}
