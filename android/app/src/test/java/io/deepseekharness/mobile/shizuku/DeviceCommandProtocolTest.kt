package io.deepseekharness.mobile.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 双哨兵协议的解析行为。
 *
 * 这里的输入流复刻**真机实测**的形态：mksh 行编辑器的回显（`stty -echo` 关不掉）、
 * 折行重绘（`\r`、`<` 续行提示、退格擦除）、以及回显里的**未展开字面量**与真实标记的先后关系。
 * PTY 回显、mksh 行编辑器与 Shizuku UserService 的行为无法在 JVM 里复现（真机验收见
 * docs/mobile-acceptance-checklist.md），但「给定这样一段字节流，解析必须得到什么结果」
 * 可以在这里逐条固定下来。
 */
class DeviceCommandProtocolTest {
    private val requestId = "12345678-1234-1234-1234-123456789abc"
    private val token = "4321-5678"

    private fun begin(value: String = token) = "__DSH_B_${requestId}_${value}__"

    private fun end(value: String = token, exitCode: Int = 0) = "__DSH_E_${requestId}_${value}__:$exitCode\r\n"

    /** 回显里出现的永远是未展开的字面量：`${dsh_nonce}` 与 `$?`，都不含数字 token。 */
    private val echoedBegin = "__DSH_B_${requestId}_\${dsh_nonce}__"
    private val echoedEnd = "__DSH_E_${requestId}_\${dsh_nonce}__:\$?"

    /** 与运行期一致：head 是完整流，tail 是最后 512 字符的有界尾窗。 */
    private fun parse(text: String, streamEnded: Boolean = false): DeviceCommandParseResult {
        val tailLength = minOf(text.length, 512)
        val tailStart = text.length - tailLength
        return DeviceCommandProtocol.parse(
            requestId = requestId,
            head = text,
            tail = text.substring(tailStart),
            tailStart = tailStart.toLong(),
            streamEnded = streamEnded,
        )
    }

    private fun completed(result: DeviceCommandParseResult, exitCode: Int, payload: String): DeviceCommandParseResult.Completed {
        assertTrue("期望 Completed，实际是 $result", result is DeviceCommandParseResult.Completed)
        val parsed = result as DeviceCommandParseResult.Completed
        assertEquals("退出码", exitCode, parsed.exitCode)
        assertEquals("payload", payload, parsed.payload)
        return parsed
    }

    /**
     * `echo` 打印标记时自带一个换行，它位于两个标记之间，因此属于 payload。
     * 需要干净文本的调用方自行去空白（截图本来就要去掉 base64 的换行）。
     */
    private fun between(commandOutput: String) = "\r\n" + commandOutput

    @Test
    fun echoedLiteralMarkersAreNeverMistakenForRealOnes() {
        // 只有回显（字面量），没有任何真实标记：必须继续等待，绝不能被当成已完成。
        val echoed = "sh -c 'dsh_nonce=\$\$-\$RANDOM; echo \"$echoedBegin\"; input text \" \"; echo \"$echoedEnd\"'\r\r\n"
        assertEquals(DeviceCommandParseResult.Pending, parse(echoed))
        // 会话结束时仍只有字面量 -> 受控失败（不是超时，也不是成功）。
        assertEquals(
            DeviceCommandParseResult.Failed(DeviceCommandProtocol.ERROR_SESSION_LOST),
            parse(echoed, streamEnded = true),
        )
    }

    @Test
    fun literalDollarRFormsAreNotMarkers() {
        // 回显形态可能因 shell 而不同（`${dsh_nonce}` / `$dsh_nonce` / `${R}`），全都不含数字 token。
        val text = "echo __DSH_B_${requestId}_\$dsh_nonce__ __DSH_B_${requestId}_\${R}__ " +
            "__DSH_E_${requestId}_\$dsh_nonce__:\$? __DSH_E_${requestId}_\${R}__:\$?\r\n:/ $ "
        assertEquals(DeviceCommandParseResult.Pending, parse(text))
    }

    @Test
    fun shortCommandEchoedBeforeRealMarkersStillParses() {
        // 复刻报告证据 2（inputText 单字符必然超时）：短命令的回显完整保留且排在真实输出之前。
        val echoed = "sh -c 'dsh_nonce=\$\$-\$RANDOM; echo \"$echoedBegin\"; input text \" \"; echo \"$echoedEnd\"'\r\r\n"
        assertEquals(DeviceCommandParseResult.Pending, parse(echoed))
        val full = echoed + begin() + "\r\n" + end(exitCode = 0) + ":/ $ "
        completed(parse(full), 0, between(""))
    }

    @Test
    fun wrappedEchoWithCarriageReturnPromptAndBackspacesStillParses() {
        // 复刻报告证据 3：回显中的哨兵被折行重绘打断（\r + < 续行提示 + 退格擦除）。
        val wrapped = "sh -c 'dsh_nonce=\$\$-\$RANDOM; echo \"__DSH_B_${requestId}_\$" +
            "\r" + "{dsh_nonce}__\"; input text \"1060,960\"; echo \"__DSH_E_${requestId}_\$" +
            "\r{dsh_nonce}__:\$?\"'                    <\b\b\b\b\b\b\b\b\b\b\r\r\n"
        val full = wrapped + begin() + "\r\n" + end(exitCode = 0) + ":/ $ "
        completed(parse(full), 0, between(""))
    }

    @Test
    fun screenshotEchoPollutionNeverEntersThePayload() {
        // 复刻报告证据 1：payload 头部曾是 195 字符回显污染（含 - | ; _ < \b : $ ?），
        // 完好的 PNG 因此被判 DEVICE_SCREENSHOT_INVALID。修复后 payload 只含真实输出。
        val echoed = "sh -c 'dsh_nonce=\$\$-\$RANDOM; echo \"__DSH_B_${requestId}_\$" +
            "\rx base64; echo \"__DSH_E_${requestId}_\${dsh_nonce}__:\$?\"'          <\b\b\b\b\b\b\b\b\r\r\n"
        val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
        val wrappedBase64 = pngBase64.chunked(10).joinToString("\r\n") + "\r\n"
        val full = echoed + begin() + "\r\n" + wrappedBase64 + end(exitCode = 0) + ":/ $ "

        val parsed = completed(parse(full), 0, between(wrappedBase64))
        assertFalse("payload 不得残留退格", parsed.payload.contains('\b'))
        assertFalse("payload 不得残留续行提示", parsed.payload.contains('<'))
        assertFalse("payload 不得残留回显", parsed.payload.contains("sh -c"))
        assertFalse(parsed.payloadTruncated)

        // 插件端的校验链路：去掉空白后必须是纯 base64，且解码后是 PNG。
        val encoded = parsed.payload.replace(Regex("\\s"), "")
        assertTrue(Regex("^[A-Za-z0-9+/]*={0,2}$").matches(encoded))
        val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val decoded = Base64.getDecoder().decode(encoded)
        assertTrue(decoded.size > pngSignature.size)
        assertTrue(pngSignature.indices.all { decoded[it] == pngSignature[it] })
    }

    @Test
    fun payloadContainingEndLikeTextIsKeptIntact() {
        val payload = "before __DSH_E_ text without a request id __DSH_E_ and $echoedEnd after"
        val full = begin() + "\r\n" + payload + end(exitCode = 0)
        completed(parse(full), 0, between(payload))
    }

    @Test
    fun payloadWithEndShapedTextStillUsesTheLastMatchingEnd() {
        // 正文里出现了「形状正确但 token 不同」的 END：真实 END 在最后，必须取最后一个。
        val payload = "junk __DSH_E_${requestId}_0-0__:7\r\n more junk "
        val full = begin() + "\r\n" + payload + end(exitCode = 0)
        completed(parse(full), 0, between(payload))
    }

    @Test
    fun nestedSentinelPairsUseTheLastBegin() {
        val full = begin("1111-2222") + "outer" +
            begin("3333-4444") + "inner" + end("3333-4444", 0) +
            end("1111-2222", 1)
        completed(parse(full), 0, "inner")
    }

    @Test
    fun replayedSentinelPairsUseTheLastPair() {
        val full = begin("1111-2222") + "first" + end("1111-2222", 1) +
            begin("3333-4444") + "second" + end("3333-4444", 0)
        completed(parse(full), 0, "second")
    }

    @Test
    fun beginWithoutEndWaitsWhileStreaming() {
        assertEquals(DeviceCommandParseResult.Pending, parse(begin() + "\r\npartial output"))
    }

    @Test
    fun beginWithoutEndAfterStreamEndIsControlledFailure() {
        // 不是超时，也不是成功：会话已结束，END 永远不会来。
        assertEquals(
            DeviceCommandParseResult.Failed(DeviceCommandProtocol.ERROR_PROTOCOL),
            parse(begin() + "\r\npartial output", streamEnded = true),
        )
    }

    @Test
    fun mismatchedEndTokenIsControlledFailure() {
        val full = begin("1111-2222") + "output" + end("3333-4444", 0)
        assertEquals(
            DeviceCommandParseResult.Failed(DeviceCommandProtocol.ERROR_PROTOCOL),
            parse(full),
        )
        assertEquals(
            DeviceCommandParseResult.Failed(DeviceCommandProtocol.ERROR_PROTOCOL),
            parse(full, streamEnded = true),
        )
    }

    @Test
    fun endBeforeBeginIsIgnored() {
        val full = end("3333-4444", 0) + begin("1111-2222") + "output"
        assertEquals(DeviceCommandParseResult.Pending, parse(full))
    }

    @Test
    fun missingMarkersAfterStreamEndReportSessionLost() {
        assertEquals(
            DeviceCommandParseResult.Failed(DeviceCommandProtocol.ERROR_SESSION_LOST),
            parse("cat: /data/local/tmp/dsh-ui-x.xml: No such file or directory\r\n", streamEnded = true),
        )
    }

    @Test
    fun exitCodesWithMultipleDigitsAreParsed() {
        for (exitCode in listOf(0, 1, 7, 42, 100, 255)) {
            val full = begin() + "\r\noutput" + end(exitCode = exitCode)
            assertEquals("退出码 $exitCode", exitCode, completed(parse(full), exitCode, between("output")).exitCode)
        }
    }

    @Test
    fun splitExitCodeIsNotParsedBeforeItsNewline() {
        val withoutNewline = begin() + "\r\noutput" + "__DSH_E_${requestId}_${token}__:1"
        assertEquals(DeviceCommandParseResult.Pending, parse(withoutNewline))
        completed(parse("$withoutNewline" + "00\r\n"), 100, between("output"))
    }

    @Test
    fun payloadBeyondTheHeadWindowIsReportedAsTruncated() {
        val payload = "x".repeat(4096)
        val full = begin() + "\r\n" + payload + end(exitCode = 0)
        val head = full.substring(0, 64)
        val tailStart = full.length - 128
        val parsed = DeviceCommandProtocol.parse(
            requestId = requestId,
            head = head,
            tail = full.substring(tailStart),
            tailStart = tailStart.toLong(),
            streamEnded = false,
        )
        // 窗口在 payload 中途截断：payload 只覆盖到窗口末尾，且必须上报截断。
        val result = completed(parsed, 0, head.substring(begin().length))
        assertTrue("正文超出窗口时必须上报截断", result.payloadTruncated)
    }

    @Test
    fun endMarkerIsFoundInsideTheBoundedTailWindow() {
        // 真实 END 是流末尾倒数第二段（其后只有交互提示符），必须落在 512 字符尾窗里。
        val full = begin() + "\r\n" + "payload" + end(exitCode = 0) + ":/ $ "
        val tailLength = minOf(full.length, 512)
        val tail = full.substring(full.length - tailLength)
        assertTrue(DeviceCommandProtocol.appearsComplete(requestId, tail))
        completed(parse(full), 0, between("payload"))
    }

    @Test
    fun completionGateIgnoresEchoedLiterals() {
        // 廉价门只认「token 已展开」的 END：回显里的字面量不触发全量解析。
        val echoed = "sh -c 'echo \"$echoedBegin\"; $echoedEnd'\r\r\n"
        assertFalse(DeviceCommandProtocol.appearsComplete(requestId, echoed))
        assertTrue(DeviceCommandProtocol.appearsComplete(requestId, begin() + "\r\n" + end(exitCode = 0)))
        // 别的请求标识的标记同样不触发。
        assertFalse(DeviceCommandProtocol.appearsComplete(requestId, "__DSH_E_00000000-0000-0000-0000-000000000000_1-2__:0\r\n"))
    }
}
