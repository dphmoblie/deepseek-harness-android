package io.deepseekharness.mobile.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputWriterTest {
    /**
     * `MAX_CHUNK_BYTES` 是**跨语言常量**：它必须等于 `pty_bridge.c` 里的 `MAX_IO_BYTES`
     * （原生侧多一个字节就整条拒收，少一个字节只是多切几刀）。这里直接读 C 源码对值——
     * 只改 Kotlin 或只改 C 一侧都会让这条用例变红，不用等真机上写终端才发现。
     */
    @Test
    fun chunkSizeStaysInSyncWithTheNativeLimit() {
        val declared = Regex("#define\\s+MAX_IO_BYTES\\s+\\(([0-9*\\s]+)\\)")
            .find(ptyBridgeSource().readText())
            ?.groupValues?.get(1)
        val native = declared
            ?.split('*')
            ?.map { it.trim().toInt() }
            ?.fold(1) { left, right -> left * right }
        assertEquals("pty_bridge.c 里读不到 MAX_IO_BYTES", 32768, native)
        assertEquals(native, TerminalInputWriter.MAX_CHUNK_BYTES)
    }

    /** 从单测工作目录向上找 `src/main/cpp/pty_bridge.c`（Gradle 单测的工作目录是模块目录）。 */
    private fun ptyBridgeSource(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "src/main/cpp/pty_bridge.c"),
                File(dir, "app/src/main/cpp/pty_bridge.c"),
            )) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 pty_bridge.c")
    }

    @Test
    fun writesAllBoundarySizesWithoutExceedingTheNativeLimit() {
        val chunkLimit = TerminalInputWriter.MAX_CHUNK_BYTES
        for (size in listOf(1, chunkLimit, chunkLimit + 1, 256 * 1024)) {
            val input = ByteArray(size) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            var calls = 0
            TerminalInputWriter.writeAll(input) { chunk ->
                assertTrue(chunk.size in 1..chunkLimit)
                output.write(chunk)
                calls++
                chunk.size
            }
            assertArrayEquals(input, output.toByteArray())
            assertEquals((size + chunkLimit - 1) / chunkLimit, calls)
        }
    }

    @Test
    fun shortWritesPreserveEveryByteInOrderAcrossChunkBoundaries() {
        val input = ByteArray(256 * 1024) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        TerminalInputWriter.writeAll(input) { chunk ->
            assertTrue(chunk.size <= TerminalInputWriter.MAX_CHUNK_BYTES)
            val written = minOf(chunk.size, 997)
            output.write(chunk, 0, written)
            written
        }
        assertArrayEquals(input, output.toByteArray())
    }

    @Test
    fun rejectsNativeWritesThatMakeNoProgressOrExceedTheRequest() {
        for (result in listOf(-1, 0, 9)) {
            var calls = 0
            val failure = assertThrows(RuntimeFailure::class.java) {
                TerminalInputWriter.writeAll(ByteArray(8)) { calls++; result }
            }
            assertEquals("TERMINAL_WRITE_FAILED", failure.code)
            assertEquals(1, calls)
        }
    }

    @Test
    fun interruptionStopsBeforeSendingMoreBytesAndKeepsTheInterruptFlag() {
        try {
            var calls = 0
            val failure = assertThrows(RuntimeFailure::class.java) {
                TerminalInputWriter.writeAll(ByteArray(TerminalInputWriter.MAX_CHUNK_BYTES + 1)) { chunk ->
                    calls++
                    Thread.currentThread().interrupt()
                    chunk.size
                }
            }
            assertEquals("TERMINAL_WRITE_INTERRUPTED", failure.code)
            assertEquals(1, calls)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
