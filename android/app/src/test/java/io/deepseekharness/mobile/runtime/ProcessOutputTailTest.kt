package io.deepseekharness.mobile.runtime

import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 安装阶段进度的两处边界：
 *  1. [ProcessOutputTail] 的按行回调必须在任意分片下拼回完整行 —— 管道一次读多少字节没有承诺；
 *  2. [HarnessVersions.progressEvent] 只认脚本真的会打的阶段，陌生名字一律丢弃，
 *     否则界面会显示一个没有文案的键。
 */
class ProcessOutputTailTest {
    /** 每次最多吐 [chunk] 字节，模拟管道分片。 */
    private class ChunkedStream(text: String, private val chunk: Int) : InputStream() {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        private var position = 0

        override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xFF

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, chunk, bytes.size - position)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
    }

    private class FakeProcess(private val stream: InputStream) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = stream
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }

    @Test
    fun `按行回调把跨分片的阶段行拼回完整 JSON`() {
        val lines = mutableListOf<String>()
        val text = "noise-1\r\nHARNESS-PROGRESS {\"stage\":\"fetch\",\"percent\":35}\n\n" +
            "HARNESS-PROGRESS {\"stage\":\"done\",\"percent\":100}\n"
        val tail = ProcessOutputTail.drain(FakeProcess(ChunkedStream(text, 7)), "test", onLine = { lines += it })
        tail.awaitClosed(1000)

        assertEquals(
            listOf(
                "noise-1",
                "HARNESS-PROGRESS {\"stage\":\"fetch\",\"percent\":35}",
                "HARNESS-PROGRESS {\"stage\":\"done\",\"percent\":100}",
            ),
            lines,
        )
        // 按行回调不影响尾部缓冲：日志面板仍然拿到完整输出。
        assertEquals(text, tail.snapshot())
        tail.close()
    }

    @Test
    fun `没有回调时也照常收尾部缓冲`() {
        val text = "a\r\nb\n"
        val tail = ProcessOutputTail.drain(FakeProcess(ChunkedStream(text, 3)), "test")
        tail.awaitClosed(1000)

        assertEquals(text, tail.snapshot())
        tail.close()
    }

    @Test
    fun `阶段行只接受已知阶段与 0 到 100 的完成度`() {
        assertEquals("fetch" to 35, HarnessVersions.progressEvent("HARNESS-PROGRESS {\"stage\":\"fetch\",\"percent\":35}"))
        // npm 日志与最终 JSON 都只是普通输出
        assertNull(HarnessVersions.progressEvent("added 1287 packages in 2m"))
        assertNull(HarnessVersions.progressEvent("{\"ok\":true}"))
        // 未知阶段：不显示没有文案的键
        assertNull(HarnessVersions.progressEvent("HARNESS-PROGRESS {\"stage\":\"download\",\"percent\":10}"))
        // 越界的完成度被夹到 0..100，而不是让界面画出超过 100% 的条
        assertEquals("fetch" to 100, HarnessVersions.progressEvent("HARNESS-PROGRESS {\"stage\":\"fetch\",\"percent\":140}"))
        assertEquals("fetch" to 0, HarnessVersions.progressEvent("HARNESS-PROGRESS {\"stage\":\"fetch\",\"percent\":-5}"))
        assertNull(HarnessVersions.progressEvent("HARNESS-PROGRESS 不是 JSON"))
    }
}
