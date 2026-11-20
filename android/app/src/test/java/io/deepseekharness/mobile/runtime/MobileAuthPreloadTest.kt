package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class MobileAuthPreloadTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installsAndReplacesTheInstalledRuntimePreload() {
        val root = runtimeRoot()
        val target = root.resolve("usr/local/lib/dsh-mobile-auth.cjs")
        Files.write(target, "old".toByteArray())

        MobileAuthPreload.install(root.toFile()) { ByteArrayInputStream("new-preload".toByteArray()) }

        assertArrayEquals("new-preload".toByteArray(), Files.readAllBytes(target))
        assertTrue(Files.isRegularFile(target))
        var leftovers = 0
        Files.newDirectoryStream(target.parent).use { entries ->
            for (entry in entries) {
                if (entry.fileName.toString().startsWith(".dsh-mobile-support.")) leftovers += 1
            }
        }
        assertEquals("temporary file must be removed", 0, leftovers)
    }

    @Test
    fun skipsRewriteWhenInstalledPreloadAlreadyMatchesAssetContent() {
        val root = runtimeRoot()
        val target = root.resolve("usr/local/lib/dsh-mobile-auth.cjs")
        val content = "same-preload-content".toByteArray()
        Files.write(target, content)
        val initialModifiedTime = java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L)
        Files.setLastModifiedTime(target, initialModifiedTime)

        MobileAuthPreload.install(root.toFile()) { ByteArrayInputStream(content) }

        assertArrayEquals(content, Files.readAllBytes(target))
        assertEquals(initialModifiedTime, Files.getLastModifiedTime(target))
    }

    @Test
    fun failsClosedWhenTheRuntimeLayoutIsMissing() {
        val root = temporaryFolder.newFolder("root").toPath()

        val failure = assertThrows(RuntimeFailure::class.java) {
            MobileAuthPreload.install(root.toFile()) { ByteArrayInputStream("preload".toByteArray()) }
        }

        assertEquals("RUNTIME_CONFIG_FAILED", failure.code)
    }

    @Test
    fun rejectsANonRegularTargetWithoutReplacingIt() {
        val root = runtimeRoot()
        val target = root.resolve("usr/local/lib/dsh-mobile-auth.cjs")
        Files.createDirectory(target)

        val failure = assertThrows(RuntimeFailure::class.java) {
            MobileAuthPreload.install(root.toFile()) { ByteArrayInputStream("preload".toByteArray()) }
        }

        assertEquals("RUNTIME_CONFIG_FAILED", failure.code)
        assertTrue(Files.isDirectory(target))
    }

    @Test
    fun rejectsOversizedAssetsWithoutTouchingTheExistingPreload() {
        val root = runtimeRoot()
        val target = root.resolve("usr/local/lib/dsh-mobile-auth.cjs")
        Files.write(target, "old".toByteArray())

        val failure = assertThrows(RuntimeFailure::class.java) {
            MobileAuthPreload.install(root.toFile()) { ByteArrayInputStream(ByteArray(300 * 1024)) }
        }

        assertEquals("RUNTIME_CONFIG_FAILED", failure.code)
        assertArrayEquals("old".toByteArray(), Files.readAllBytes(target))
    }

    @Test
    fun keepsTheOldPreloadWhenTheAssetCannotBeRead() {
        val root = runtimeRoot()
        val target = root.resolve("usr/local/lib/dsh-mobile-auth.cjs")
        Files.write(target, "old".toByteArray())

        val failure = assertThrows(RuntimeFailure::class.java) {
            MobileAuthPreload.install(root.toFile()) { throw IllegalStateException("asset unavailable") }
        }

        assertEquals("RUNTIME_CONFIG_FAILED", failure.code)
        assertArrayEquals("old".toByteArray(), Files.readAllBytes(target))
    }

    @Test
    fun refreshesTheInstalledTurnSignalAndPreservesItOnReadFailure() {
        val root = runtimeRoot()
        val target = root.resolve(MobileAuthPreload.TURN_SIGNAL_GUEST_PATH.removePrefix("/"))
        Files.createDirectories(target.parent)
        Files.write(target, "old-signal".toByteArray())

        MobileAuthPreload.installTurnSignal(root.toFile()) { ByteArrayInputStream("new-signal".toByteArray()) }
        assertArrayEquals("new-signal".toByteArray(), Files.readAllBytes(target))
        assertThrows(RuntimeFailure::class.java) {
            MobileAuthPreload.installTurnSignal(root.toFile()) { throw IllegalStateException("asset unavailable") }
        }
        assertArrayEquals("new-signal".toByteArray(), Files.readAllBytes(target))
        Files.newDirectoryStream(target.parent).use { entries ->
            assertEquals(listOf(target), entries.toList())
        }
    }

    @Test
    fun refusesToRefreshTurnSignalsThroughLinkedDirectories() {
        val root = runtimeRoot()
        val outside = temporaryFolder.newFolder("outside").toPath()
        Files.createDirectories(root.resolve("opt/dsh/plugins"))
        try {
            Files.createSymbolicLink(root.resolve("opt/dsh/plugins/dsh-mobile-shizuku"), outside)
        } catch (failure: Exception) {
            assumeNoException(failure)
        }

        assertThrows(RuntimeFailure::class.java) {
            MobileAuthPreload.installTurnSignal(root.toFile()) { ByteArrayInputStream("signal".toByteArray()) }
        }
        Files.newDirectoryStream(outside).use { assertTrue(it.none()) }
    }

    @Test
    fun refreshesInstalledPtyGuardAndDevicePluginIndexAtomically() {
        val root = runtimeRoot()
        val guardTarget = root.resolve(MobileAuthPreload.PTY_GUARD_GUEST_PATH.removePrefix("/"))
        val indexTarget = root.resolve(MobileAuthPreload.DEVICE_PLUGIN_INDEX_GUEST_PATH.removePrefix("/"))
        Files.createDirectories(guardTarget.parent)
        Files.write(indexTarget, "old-index".toByteArray())

        MobileAuthPreload.installPtyGuard(root.toFile()) { ByteArrayInputStream("new-pty-guard".toByteArray()) }
        MobileAuthPreload.installDevicePluginIndex(root.toFile()) { ByteArrayInputStream("new-index".toByteArray()) }

        assertArrayEquals("new-pty-guard".toByteArray(), Files.readAllBytes(guardTarget))
        assertArrayEquals("new-index".toByteArray(), Files.readAllBytes(indexTarget))
    }

    private fun runtimeRoot(): Path {
        val root = temporaryFolder.newFolder("runtime").toPath()
        Files.createDirectories(root.resolve("usr/local/lib"))
        return root
    }
}
