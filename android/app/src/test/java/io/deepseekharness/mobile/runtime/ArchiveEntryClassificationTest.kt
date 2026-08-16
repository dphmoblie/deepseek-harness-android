package io.deepseekharness.mobile.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 锁定 commons-compress 的类型判定行为：
 * 1.27.1 的 TarArchiveEntry.isFile() 对符号链接、硬链接、设备节点与 FIFO
 * 均返回 true（仅目录与以 "/" 结尾的名字返回 false），因此 classifyEntry
 * 必须把 isLink/isSymbolicLink 放在 isFile 之前，否则符号链接会被物化成
 * 0 字节 0777 普通文件。
 */
class ArchiveEntryClassificationTest {
    private enum class EntryType(val flag: Byte) {
        FILE('0'.code.toByte()),
        HARD_LINK('1'.code.toByte()),
        SYMLINK('2'.code.toByte()),
        CHAR_DEVICE('3'.code.toByte()),
        BLOCK_DEVICE('4'.code.toByte()),
        DIRECTORY('5'.code.toByte()),
        FIFO('6'.code.toByte()),
    }

    @Test
    fun classifiesSymbolicLinkBeforeFile() {
        val entries = parseTar(
            entry("bin", EntryType.SYMLINK, linkName = "usr/bin"),
            entry("usr/bin/sh", EntryType.SYMLINK, linkName = "dash"),
            entry("etc", EntryType.DIRECTORY),
            entry("etc/os-release", EntryType.FILE, data = "NAME=Ubuntu\n".toByteArray()),
            entry("hard", EntryType.HARD_LINK, linkName = "etc/os-release"),
            entry("dev/null", EntryType.CHAR_DEVICE),
            entry("dev/loop0", EntryType.BLOCK_DEVICE),
            entry("dev/fifo", EntryType.FIFO),
        )

        assertEquals(
            listOf(
                ArchiveEntryKind.SYMBOLIC_LINK,
                ArchiveEntryKind.SYMBOLIC_LINK,
                ArchiveEntryKind.DIRECTORY,
                ArchiveEntryKind.FILE,
                ArchiveEntryKind.HARD_LINK,
                ArchiveEntryKind.UNSUPPORTED,
                ArchiveEntryKind.UNSUPPORTED,
                ArchiveEntryKind.UNSUPPORTED,
            ),
            entries.map { classifyEntry(it) },
        )
    }

    @Test
    fun commonsCompressIsFileReturnsTrueForSymlinks() {
        val symlink = parseTar(entry("bin", EntryType.SYMLINK, linkName = "usr/bin")).single()

        assertTrue(symlink.isSymbolicLink)
        assertTrue(symlink.isFile)
        assertEquals("usr/bin", symlink.linkName)
        assertEquals(0L, symlink.size)
    }

    private fun entry(
        name: String,
        type: EntryType,
        linkName: String = "",
        data: ByteArray = ByteArray(0),
    ): ByteArray {
        val header = ByteArray(512)
        writeName(header, name)
        header[100] = '0'.code.toByte(); header[101] = '7'.code.toByte()
        header[102] = '7'.code.toByte(); header[103] = '7'.code.toByte(); header[104] = 0
        header[108] = '0'.code.toByte(); header[109] = 0 // uid
        header[116] = '0'.code.toByte(); header[117] = 0 // gid
        val size = String.format("%011o", data.size)
        size.forEachIndexed { index, char -> header[124 + index] = char.code.toByte() }
        header[135] = 0
        header[136] = '0'.code.toByte(); header[137] = 0 // mtime 占位
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = type.flag
        writeName(header, linkName, offset = 157, length = 100)
        header[257] = 'u'.code.toByte(); header[258] = 's'.code.toByte()
        header[259] = 't'.code.toByte(); header[260] = 'a'.code.toByte(); header[261] = 'r'.code.toByte()
        header[262] = ' '.code.toByte(); header[263] = ' '.code.toByte(); header[264] = 0
        header[265] = '0'.code.toByte(); header[266] = '0'.code.toByte()
        writeChecksum(header)

        val output = ByteArrayOutputStream()
        output.write(header)
        if (data.isNotEmpty()) {
            output.write(data)
            val padding = (512 - data.size % 512) % 512
            output.write(ByteArray(padding))
        }
        return output.toByteArray()
    }

    private fun writeChecksum(header: ByteArray) {
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0L
        for (i in header.indices) {
            sum += if (i in 148 until 156) ' '.code else header[i].toInt() and 0xFF
        }
        val digits = String.format("%06o", sum).toByteArray(StandardCharsets.US_ASCII)
        for (i in digits.indices) header[148 + i] = digits[i]
        header[154] = 0
        header[155] = ' '.code.toByte()
    }

    private fun writeName(header: ByteArray, name: String, offset: Int = 0, length: Int = 100) {
        val bytes = name.toByteArray(StandardCharsets.US_ASCII)
        for (i in 0 until length) {
            header[offset + i] = if (i < bytes.size) bytes[i] else 0
        }
    }

    private fun parseTar(vararg entries: ByteArray): List<org.apache.commons.compress.archivers.tar.TarArchiveEntry> {
        val archive = ByteArrayOutputStream()
        entries.forEach(archive::write)
        archive.write(ByteArray(1024))
        val result = ArrayList<org.apache.commons.compress.archivers.tar.TarArchiveEntry>()
        TarArchiveInputStream(ByteArrayInputStream(archive.toByteArray())).use { input ->
            while (true) {
                result.add(input.nextEntry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry ?: break)
            }
        }
        return result
    }
}
