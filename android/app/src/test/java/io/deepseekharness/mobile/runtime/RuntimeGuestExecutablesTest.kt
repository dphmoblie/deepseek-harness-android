package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class RuntimeGuestExecutablesTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private fun elfHeader(): ByteArray = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0x02, 0x01, 0x01, 0x00)

    private fun writeElf(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(elfHeader() + ByteArray(16))
    }

    private fun setMode(file: File, mode: Int) {
        Files.setPosixFilePermissions(file.toPath(), RuntimeGuestExecutables.fromMode(mode))
    }

    private fun modeOf(file: File): Int = RuntimeGuestExecutables.toMode(Files.getPosixFilePermissions(file.toPath()))

    /**
     * Windows 主机上的默认文件系统不支持 POSIX 权限位；这类用例在 CI 的 Linux 上才会真正执行。
     */
    private fun assumePosixPermissions() {
        try {
            val probe = temporaryFolder.newFile("posix-probe")
            Files.setPosixFilePermissions(probe.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        } catch (error: UnsupportedOperationException) {
            assumeNoException("POSIX file permissions are unavailable on this host", error)
        }
    }

    private fun assumeSymbolicLinks() {
        try {
            val target = temporaryFolder.newFile("link-target")
            Files.createSymbolicLink(temporaryFolder.newFolder("link-dir").toPath().resolve("link"), target.toPath())
        } catch (error: IOException) {
            assumeNoException("Symbolic links are unavailable on this host", error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable on this host", error)
        }
    }

    @Test
    fun recognisesElfAndShebangHeaders() {
        assertTrue(RuntimeGuestExecutables.looksExecutable(elfHeader(), 8))
        assertTrue(RuntimeGuestExecutables.looksExecutable("#!/bin/sh\nexit 0\n".toByteArray(), 17))
        assertFalse(RuntimeGuestExecutables.looksExecutable("plain text".toByteArray(), 10))
        assertFalse(RuntimeGuestExecutables.looksExecutable(byteArrayOf(), 0))
        assertFalse(RuntimeGuestExecutables.looksExecutable(byteArrayOf('#'.code.toByte()), 1))
        // 只够得着 ELF 前缀（缺第 4 个字节）时不能判定为可执行。
        assertFalse(RuntimeGuestExecutables.looksExecutable(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte()), 3))
    }

    @Test
    fun onlyAddsOwnerExecuteBit() {
        assertEquals(0x1c0, RuntimeGuestExecutables.repairedMode(0x180))
        assertEquals(0x1e4, RuntimeGuestExecutables.repairedMode(0x1a4))
        assertNull(RuntimeGuestExecutables.repairedMode(0x1c0))
        assertNull(RuntimeGuestExecutables.repairedMode(0x1ed))
        assertTrue(RuntimeGuestExecutables.needsOwnerExecute(0x124))
        assertFalse(RuntimeGuestExecutables.needsOwnerExecute(0x140))
    }

    @Test
    fun convertsPosixPermissionsBothWays() {
        assertEquals(0x1ed, RuntimeGuestExecutables.toMode(PosixFilePermissions.fromString("rwxr-xr-x")))
        assertEquals(0x1a4, RuntimeGuestExecutables.toMode(PosixFilePermissions.fromString("rw-r--r--")))
        assertEquals(0, RuntimeGuestExecutables.toMode(emptySet<PosixFilePermission>()))
        assertEquals(
            PosixFilePermissions.fromString("rwxr-xr-x"),
            RuntimeGuestExecutables.fromMode(0x1ed),
        )
        assertEquals(PosixFilePermissions.fromString("rw-------"), RuntimeGuestExecutables.fromMode(0x180))
        for (mode in listOf(0x000, 0x1a4, 0x1c0, 0x1ed, 0x1ff)) {
            assertEquals(mode, RuntimeGuestExecutables.toMode(RuntimeGuestExecutables.fromMode(mode)))
        }
    }

    @Test
    fun describesMissingAndDirectoryPaths() {
        val root = temporaryFolder.newFolder("describe-root")
        assertEquals(
            "path=lib/ld-linux-aarch64.so.1,kind=missing",
            RuntimeGuestExecutables.describe(root, "lib/ld-linux-aarch64.so.1"),
        )
        File(root, "usr/bin").mkdirs()
        assertTrue(RuntimeGuestExecutables.describe(root, "usr/bin").startsWith("path=usr/bin,kind=directory,size="))
    }

    @Test
    fun describesRegularFileModeAndExecutability() {
        assumePosixPermissions()
        val root = temporaryFolder.newFolder("describe-file-root")
        val file = File(root, "usr/bin/env")
        file.parentFile?.mkdirs()
        file.writeText("#!/bin/sh\n")
        setMode(file, 0x1a4)
        val description = RuntimeGuestExecutables.describe(root, "usr/bin/env")
        assertTrue(description.startsWith("path=usr/bin/env,kind=file,size="))
        assertTrue(description.contains(",mode=0644"))
        assertTrue(description.endsWith("execAccess=false"))
    }

    @Test
    fun repairsCopiedInterpreterThatLostOwnerExecute() {
        assumePosixPermissions()
        val root = temporaryFolder.newFolder("rootfs")
        val interpreter = File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        writeElf(interpreter)
        setMode(interpreter, 0x1a4)
        val marker = File(root, "root/.dsh-mobile/dsh-runner/.exec-repair.done")

        val repair = RuntimeExecutableRepair(root, marker)
        assertTrue(repair.isPending())
        val outcome = repair.run()

        assertTrue(outcome.ran)
        assertTrue(outcome.completed)
        assertEquals(1, outcome.scanned)
        assertEquals(1, outcome.repaired)
        assertEquals(0, outcome.failed)
        assertEquals(0x1e4, modeOf(interpreter))
        assertTrue(marker.isFile)
        assertFalse(repair.isPending())
    }

    @Test
    fun leavesExecutableAndPlainFilesUntouched() {
        assumePosixPermissions()
        val root = temporaryFolder.newFolder("keep-root")
        val executable = File(root, "usr/bin/bash")
        writeElf(executable)
        setMode(executable, 0x1ed)
        val data = File(root, "usr/share/readme.txt")
        data.parentFile?.mkdirs()
        data.writeText("plain text\n")
        setMode(data, 0x1a4)

        val outcome = RuntimeExecutableRepair(root, File(root, "marker")).run()

        assertTrue(outcome.completed)
        assertEquals(2, outcome.scanned)
        assertEquals(0, outcome.repaired)
        assertEquals(0x1ed, modeOf(executable))
        assertEquals(0x1a4, modeOf(data))
    }

    @Test
    fun skipsSymbolicLinks() {
        assumePosixPermissions()
        assumeSymbolicLinks()
        val root = temporaryFolder.newFolder("link-root")
        val target = File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        writeElf(target)
        setMode(target, 0x1a4)
        Files.createSymbolicLink(root.toPath().resolve("lib-link"), target.toPath())

        val outcome = RuntimeExecutableRepair(root, File(root, "marker")).run()

        assertEquals(1, outcome.repaired)
        assertEquals(0x1e4, modeOf(target))
        assertTrue(RuntimeGuestExecutables.describe(root, "lib-link").startsWith("path=lib-link,kind=symlink,target="))
    }

    @Test
    fun doesNotRescanAfterTheMarkerIsWritten() {
        assumePosixPermissions()
        val root = temporaryFolder.newFolder("marker-root")
        val marker = File(root, "marker")
        val first = File(root, "a/ld.so")
        writeElf(first)
        setMode(first, 0x1a4)
        val repair = RuntimeExecutableRepair(root, marker)
        assertEquals(1, repair.run().repaired)

        val second = File(root, "b/ld.so")
        writeElf(second)
        setMode(second, 0x1a4)
        val outcome = repair.runIfNeeded()

        assertFalse(outcome.ran)
        assertEquals(0, outcome.repaired)
        assertEquals(0x1a4, modeOf(second))
    }

    @Test
    fun keepsMarkerPendingWhenTheScanHitsItsLimit() {
        assumePosixPermissions()
        val root = temporaryFolder.newFolder("limit-root")
        for (name in listOf("a", "b", "c")) {
            val file = File(root, name + "/ld.so")
            writeElf(file)
            setMode(file, 0x1a4)
        }
        val marker = File(root, "marker")

        val outcome = RuntimeExecutableRepair(root, marker, maxScanned = 1).run()

        assertTrue(outcome.ran)
        assertFalse(outcome.completed)
        assertFalse(marker.exists())
        assertTrue(RuntimeExecutableRepair(root, marker).isPending())
    }
}
