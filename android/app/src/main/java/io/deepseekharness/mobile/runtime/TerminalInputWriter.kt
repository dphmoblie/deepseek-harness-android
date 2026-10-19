package io.deepseekharness.mobile.runtime

/**
 * 与 `pty_bridge.c` 的 `MAX_IO_BYTES` 保持一致，短写后继续投递未写入的字节。
 *
 * 「一致」不是靠注释维持的：`TerminalInputWriterTest.chunkSizeStaysInSyncWithTheNativeLimit`
 * 会读 C 源码对值，只改一侧就红。
 */
internal object TerminalInputWriter {
    const val MAX_CHUNK_BYTES = 32 * 1024

    fun writeAll(data: ByteArray, write: (ByteArray) -> Int) {
        var offset = 0
        while (offset < data.size) {
            if (Thread.currentThread().isInterrupted) {
                throw RuntimeFailure("TERMINAL_WRITE_INTERRUPTED", "终端输入已中断")
            }
            val end = minOf(data.size, offset + MAX_CHUNK_BYTES)
            val chunk = if (offset == 0 && end == data.size) data else data.copyOfRange(offset, end)
            val written = write(chunk)
            if (written !in 1..chunk.size) {
                throw RuntimeFailure("TERMINAL_WRITE_FAILED", "无法写入终端会话")
            }
            offset += written
        }
    }
}
