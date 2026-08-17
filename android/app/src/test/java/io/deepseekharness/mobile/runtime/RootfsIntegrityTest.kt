package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class RootfsIntegrityTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private data class FixtureLink(val path: String, val target: String)

    private val expectedLinks = listOf(
        FixtureLink("bin", "usr/bin"),
        FixtureLink("lib", "usr/lib"),
        FixtureLink("sbin", "usr/sbin"),
        FixtureLink("usr/bin/sh", "dash"),
        FixtureLink("etc/mtab", "../proc/self/mounts"),
        FixtureLink("etc/os-release", "../usr/lib/os-release"),
        FixtureLink("etc/localtime", "/usr/share/zoneinfo/Etc/UTC"),
        FixtureLink("usr/local/bin/node", "../../../opt/node/bin/node"),
    )

    @Test
    fun passesWhenAllRequiredLinksAreIntact() {
        val root = temporaryFolder.newFolder("rootfs").toPath()
        expectedLinks.forEach { link ->
            Files.createDirectories(root.resolve(parentOf(link.path)))
            createSymbolicLinkOrSkip(root.resolve(link.path), link.target)
        }

        RootfsIntegrity.verifyLinks(root.toFile(), "RUNTIME_CORRUPTED")
    }

    @Test
    fun rejectsRegularFileInPlaceOfSymlink() {
        val root = temporaryFolder.newFolder("rootfs").toPath()
        expectedLinks.forEach { link ->
            Files.createDirectories(root.resolve(parentOf(link.path)))
            createSymbolicLinkOrSkip(root.resolve(link.path), link.target)
        }
        val binPath = root.resolve("bin")
        Files.delete(binPath)
        Files.write(binPath, ByteArray(0))

        val failure = assertThrows(RuntimeFailure::class.java) {
            RootfsIntegrity.verifyLinks(root.toFile(), "RUNTIME_CORRUPTED")
        }

        assertEquals("RUNTIME_CORRUPTED", failure.code)
    }

    @Test
    fun passesWhenAbsoluteTargetIsMaterializedAsEquivalentRelativeForm() {
        val root = temporaryFolder.newFolder("rootfs").toPath()
        expectedLinks.forEach { link ->
            Files.createDirectories(root.resolve(parentOf(link.path)))
            val materializedTarget = when (link.path) {
                "etc/localtime" -> "../usr/share/zoneinfo/Etc/UTC"
                else -> link.target
            }
            createSymbolicLinkOrSkip(root.resolve(link.path), materializedTarget)
        }

        RootfsIntegrity.verifyLinks(root.toFile(), "RUNTIME_CORRUPTED")
    }

    @Test
    fun rejectsMissingRequiredLink() {
        val root = temporaryFolder.newFolder("rootfs").toPath()
        expectedLinks.filter { it.path != "bin" }.forEach { link ->
            Files.createDirectories(root.resolve(parentOf(link.path)))
            createSymbolicLinkOrSkip(root.resolve(link.path), link.target)
        }

        val failure = assertThrows(RuntimeFailure::class.java) {
            RootfsIntegrity.verifyLinks(root.toFile(), "ROOTFS_LINKS_CORRUPTED")
        }

        assertEquals("ROOTFS_LINKS_CORRUPTED", failure.code)
    }

    @Test
    fun rejectsUnexpectedLinkTarget() {
        val root = temporaryFolder.newFolder("rootfs").toPath()
        expectedLinks.forEach { link ->
            Files.createDirectories(root.resolve(parentOf(link.path)))
            createSymbolicLinkOrSkip(root.resolve(link.path), link.target)
        }
        val shPath = root.resolve("usr/bin/sh")
        Files.delete(shPath)
        createSymbolicLinkOrSkip(shPath, "bash")

        val failure = assertThrows(RuntimeFailure::class.java) {
            RootfsIntegrity.verifyLinks(root.toFile(), "RUNTIME_CORRUPTED")
        }

        assertEquals("RUNTIME_CORRUPTED", failure.code)
    }

    private fun parentOf(relative: String): String {
        val parts = relative.split('/')
        return parts.dropLast(1).joinToString("/")
    }

    private fun createSymbolicLinkOrSkip(link: Path, target: String) {
        try {
            Files.createSymbolicLink(link, Path.of(target))
        } catch (error: Throwable) {
            if (error !is IOException && error !is UnsupportedOperationException && error !is SecurityException) {
                throw error
            }
            assumeNoException("Symbolic links are unavailable on this host", error)
        }
    }
}
