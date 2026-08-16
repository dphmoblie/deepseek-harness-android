package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

class RuntimeFilesTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectsDeletionOutsideTheImmediateAllowedParent() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        val allowedParent = Files.createDirectory(sandbox.resolve("runtime"))
        val outside = Files.createDirectory(sandbox.resolve("outside"))

        val failure = assertThrows(RuntimeFailure::class.java) {
            RuntimeFiles.deleteTreeNoFollow(outside.toFile(), allowedParent.toFile())
        }

        assertEquals("RESET_SCOPE_INVALID", failure.code)
        assertTrue(Files.isDirectory(outside, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun deletesNestedTreeWhenSecureDirectoryStreamsAreAvailable() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        assumeSecureDirectoryStreams(sandbox)
        val allowedParent = Files.createDirectory(sandbox.resolve("runtime"))
        val target = Files.createDirectories(allowedParent.resolve("target/nested"))
            .parent
        Files.write(target.resolve("nested/payload.txt"), "payload".toByteArray())

        RuntimeFiles.deleteTreeNoFollow(target.toFile(), allowedParent.toFile())

        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(allowedParent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun missingTargetIsIdempotentWhenSecureDirectoryStreamsAreAvailable() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        assumeSecureDirectoryStreams(sandbox)
        val allowedParent = Files.createDirectory(sandbox.resolve("runtime"))

        RuntimeFiles.deleteTreeNoFollow(allowedParent.resolve("missing").toFile(), allowedParent.toFile())

        assertTrue(Files.isDirectory(allowedParent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun deletesSymbolicLinkWithoutFollowingItsTarget() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        assumeSecureDirectoryStreams(sandbox)
        val allowedParent = Files.createDirectory(sandbox.resolve("runtime"))
        val target = Files.createDirectory(allowedParent.resolve("target"))
        val external = Files.createDirectory(sandbox.resolve("external"))
        val marker = Files.write(external.resolve("keep.txt"), "keep".toByteArray())
        createSymbolicLinkOrSkip(target.resolve("external-link"), external)

        RuntimeFiles.deleteTreeNoFollow(target.toFile(), allowedParent.toFile())

        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun rejectsSymbolicLinkAsAllowedParent() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        assumeSecureDirectoryStreams(sandbox)
        val realParent = Files.createDirectory(sandbox.resolve("real-runtime"))
        val target = Files.createDirectory(realParent.resolve("target"))
        val allowedLink = sandbox.resolve("runtime-link")
        createSymbolicLinkOrSkip(allowedLink, realParent)

        val failure = assertThrows(RuntimeFailure::class.java) {
            RuntimeFiles.deleteTreeNoFollow(allowedLink.resolve("target").toFile(), allowedLink.toFile())
        }

        assertEquals("RESET_SCOPE_INVALID", failure.code)
        assertTrue(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun failsClosedWhenTheDefaultProviderIsNotSecure() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        assumeFalse("Default provider supports secure directory streams", supportsSecureDirectoryStreams(sandbox))
        val allowedParent = Files.createDirectory(sandbox.resolve("runtime"))
        val target = Files.createDirectory(allowedParent.resolve("target"))

        val failure = assertThrows(RuntimeFailure::class.java) {
            RuntimeFiles.deleteTreeNoFollow(target.toFile(), allowedParent.toFile())
        }

        assertEquals("FILESYSTEM_SECURE_DELETE_UNAVAILABLE", failure.code)
        assertTrue(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
    }

    private fun assumeSecureDirectoryStreams(path: Path) {
        assumeTrue("Default provider does not support secure directory streams", supportsSecureDirectoryStreams(path))
    }

    private fun supportsSecureDirectoryStreams(path: Path): Boolean =
        Files.newDirectoryStream(path).use { it is SecureDirectoryStream<*> }

    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (error: Throwable) {
            if (error !is IOException && error !is UnsupportedOperationException && error !is SecurityException) {
                throw error
            }
            assumeNoException("Symbolic links are unavailable on this host", error)
        }
    }
}
