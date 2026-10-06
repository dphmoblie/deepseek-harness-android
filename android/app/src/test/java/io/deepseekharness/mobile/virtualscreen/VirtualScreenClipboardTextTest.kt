package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非 ASCII 文本在**设备 Shell 侧**（没有无障碍服务实例）的处置：尽量把剪贴板通道打通，打不通就诚实失败。
 *
 * 这里只覆盖纯函数（探测结果翻译 + 失败文案）。真机上「文本进了剪贴板、粘贴键也发了，
 * 但目标输入框没聚焦」这一类只能靠截图/节点树复核，代码里用 `verified=false` + 一句复核提示如实标注。
 */
class VirtualScreenClipboardTextTest {
    @Test fun `探测结果三态各自的判定边界`() {
        assertEquals("available", VirtualScreenPolicy.ClipboardProbe.AVAILABLE.wire)
        assertEquals("failed", VirtualScreenPolicy.ClipboardProbe.FAILED.wire)
        assertEquals("absent", VirtualScreenPolicy.ClipboardProbe.ABSENT.wire)
        assertEquals(3, setOf(
            VirtualScreenPolicy.ClipboardProbe.AVAILABLE.label,
            VirtualScreenPolicy.ClipboardProbe.FAILED.label,
            VirtualScreenPolicy.ClipboardProbe.ABSENT.label,
        ).size)

        // 命令抛异常 / 非零退出 = 命令不存在或被系统拒绝。
        assertEquals(
            VirtualScreenPolicy.ClipboardProbe.ABSENT,
            VirtualScreenPolicy.clipboardProbe(succeeded = false, output = "", failure = "command not found"),
        )
        assertEquals(
            VirtualScreenPolicy.ClipboardProbe.ABSENT,
            VirtualScreenPolicy.clipboardProbe(succeeded = true, output = "Ok", failure = "SecurityException"),
        )
        // 输出里带报错字样同样是不可用（命令在，但这次没成）。
        for (output in listOf("Error: no clipboard service", "java.lang.Exception: denied", "Unknown command: clipboard", "not found")) {
            assertEquals(
                "$output 应判为不可用",
                VirtualScreenPolicy.ClipboardProbe.ABSENT,
                VirtualScreenPolicy.clipboardProbe(succeeded = true, output = output, failure = ""),
            )
        }
        // 剪贴板服务的确认标记 ⇒ 可用。
        assertEquals(
            VirtualScreenPolicy.ClipboardProbe.AVAILABLE,
            VirtualScreenPolicy.clipboardProbe(succeeded = true, output = "clipboard set-primary-clip", failure = ""),
        )
        assertEquals(
            VirtualScreenPolicy.ClipboardProbe.AVAILABLE,
            VirtualScreenPolicy.clipboardProbe(succeeded = true, output = "Clipboard service: enabled", failure = ""),
        )
        // 命令成功了但什么也没证明（空输出）⇒「未能确认」，不能算可用：拿不到证据就说成功，
        // 会把「没打通」伪装成「已写好」，用户看到的失败会变成「发送成功但输入框是空的」。
        assertEquals(
            VirtualScreenPolicy.ClipboardProbe.FAILED,
            VirtualScreenPolicy.clipboardProbe(succeeded = true, output = "", failure = ""),
        )
    }

    @Test fun `读回核对的优先级高于报错正则`() {
        // 用户要输入的文本里可能就带 error 这类英文单词：读回输出命中它时不能把通道判成不可用。
        val text = "error：这条是中文说明"
        assertEquals(
            VirtualScreenPolicy.ClipboardProbe.AVAILABLE,
            VirtualScreenPolicy.clipboardProbe(
                succeeded = true,
                output = "current primary clip: $text",
                failure = "",
                expected = text,
            ),
        )
        // 读回对不上就退回原来的判据。
        assertEquals(
            VirtualScreenPolicy.ClipboardProbe.ABSENT,
            VirtualScreenPolicy.clipboardProbe(succeeded = true, output = "Error: denied", failure = "", expected = text),
        )
        assertEquals(16, VirtualScreenPolicy.CLIPBOARD_VERIFY_CHARS)
    }

    @Test fun `非 ASCII 失败码恒为文本不支持且提示可操作`() {
        for (probe in VirtualScreenPolicy.ClipboardProbe.values()) {
            val (code, message) = VirtualScreenPolicy.nonAsciiTextFailure(probe)
            assertEquals(VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED, code)
            // 不能报成兜底码：副屏会话本身是好的，写不进去是目标输入框的限制。
            assertNotEquals("VIRTUAL_SCREEN_UNAVAILABLE", code)
            assertTrue("$probe 的提示必须指路无障碍", message.contains(VirtualScreenPolicy.CLIPBOARD_HINT_ACCESSIBILITY))
            assertTrue("$probe 的提示必须说明副屏正常", message.contains("副屏会话本身正常"))
        }
        // 剪贴板可用时说的是「已经写进剪贴板了，请手动粘贴」，并顺带给出去以后不用再手动的出路（开无障碍）。
        // 注意 AVAILABLE 这一支在设备 Shell 的调用路径上到不了：探测可用就直接去发粘贴键了
        // （ShellVirtualScreen.nonAsciiTextAction 只在 probe != AVAILABLE 时才走 nonAsciiTextFailure），
        // 所以这里钉的是纯函数自身的契约，不是线上会出现的那句话。
        assertEquals(
            "${VirtualScreenPolicy.CLIPBOARD_HINT_MANUAL_PASTE}；${VirtualScreenPolicy.CLIPBOARD_HINT_ACCESSIBILITY}" +
                "。设备 Shell 进程拿不到无障碍服务实例，副屏会话本身正常。",
            VirtualScreenPolicy.nonAsciiTextFailure(VirtualScreenPolicy.ClipboardProbe.AVAILABLE).second,
        )
        assertTrue(VirtualScreenPolicy.nonAsciiTextFailure(VirtualScreenPolicy.ClipboardProbe.FAILED).second.contains("剪贴板命令存在"))
        assertEquals(
            "${VirtualScreenPolicy.CLIPBOARD_HINT_UNAVAILABLE}。设备 Shell 进程拿不到无障碍服务实例，副屏会话本身正常。",
            VirtualScreenPolicy.nonAsciiTextFailure(VirtualScreenPolicy.ClipboardProbe.ABSENT).second,
        )
        // 复核提示要求调用方别把「发了粘贴键」当成「已经粘上了」。
        assertTrue(VirtualScreenPolicy.CLIPBOARD_VERIFY_NOTE.contains("复核"))
        assertTrue(VirtualScreenPolicy.CLIPBOARD_HINT_PASTE_FAILED.contains("长按选择「粘贴」"))
    }

    @Test fun `文本长度与可注入性仍沿用既有文本策略`() {
        // 剪贴板通道不放松既有约束：长度与可注入性还是 VirtualScreenTextPolicy 说了算。
        assertEquals(512, VirtualScreenTextPolicy.MAX_TEXT_CHARS)
        VirtualScreenTextPolicy.requireInjectable("中文")
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenTextPolicy.requireInjectable("") }
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenTextPolicy.requireInjectable("有\u0000控制字符") }
    }
}
