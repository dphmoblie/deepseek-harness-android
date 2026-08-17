package io.deepseekharness.mobile.runtime

object NativePty {
    init {
        System.loadLibrary("dsh_pty")
    }

    external fun spawn(
        argv: Array<String>,
        environment: Array<String>,
        columns: Int,
        rows: Int,
    ): LongArray

    external fun read(fileDescriptor: Int, buffer: ByteArray): Int
    external fun write(fileDescriptor: Int, data: ByteArray): Int
    external fun resize(fileDescriptor: Int, columns: Int, rows: Int)
    external fun signal(processId: Int, signal: Int)
    external fun waitFor(processId: Int): Int
    external fun close(fileDescriptor: Int)
}
