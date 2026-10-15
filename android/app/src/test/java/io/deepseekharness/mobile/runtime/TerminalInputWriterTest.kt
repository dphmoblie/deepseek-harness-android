package io.deepseekharness.mobile.runtime

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputWriterTest {
    @Test
    fun writesAllBoundarySizesWithoutExceedingTheNativeLimit() {
        for (size in listOf(1, 32 * 1024, 32 * 1024 + 1, 256 * 1024)) {
            val input = ByteArray(size) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            var calls = 0
            TerminalInputWriter.writeAll(input) { chunk ->
                assertTrue(chunk.size in 1..(32 * 1024))
                output.write(chunk)
                calls++
                chunk.size
            }
            assertArrayEquals(input, output.toByteArray())
            assertEquals((size + 32 * 1024 - 1) / (32 * 1024), calls)
        }
    }

    @Test
    fun shortWritesPreserveEveryByteInOrderAcrossChunkBoundaries() {
        val input = ByteArray(256 * 1024) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        TerminalInputWriter.writeAll(input) { chunk ->
            assertTrue(chunk.size <= 32 * 1024)
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
                TerminalInputWriter.writeAll(ByteArray(32 * 1024 + 1)) { chunk ->
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
