package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
                if (entry.fileName.toString().startsWith(".dsh-mobile-auth.")) leftovers += 1
            }
        }
        assertEquals("temporary file must be removed", 0, leftovers)
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

    private fun runtimeRoot(): Path {
        val root = temporaryFolder.newFolder("runtime").toPath()
        Files.createDirectories(root.resolve("usr/local/lib"))
        return root
    }
}
