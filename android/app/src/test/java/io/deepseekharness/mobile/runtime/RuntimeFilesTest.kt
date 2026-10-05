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

    /**
     * 兜底删除器是真机上唯一能把 `stale-*` 残留真正回收掉的路径（严格删除器在真机上会失败），
     * 它不依赖 [SecureDirectoryStream]，所以在任何宿主机上都应该可用。
     */
    @Test
    fun fallbackDeletesNestedTreeWithoutSecureDirectoryStreams() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        val allowedParent = Files.createDirectory(sandbox.resolve("runtime"))
        val target = Files.createDirectories(allowedParent.resolve("target/nested"))
            .parent
        Files.write(target.resolve("nested/payload.txt"), "payload".toByteArray())
        Files.createDirectories(target.resolve("empty"))

        RuntimeFiles.deleteTreeNoFollowFallback(target.toFile(), allowedParent.toFile())

        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(allowedParent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun fallbackIsIdempotentForMissingTarget() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        val allowedParent = Files.createDirectory(sandbox.resolve("runtime"))

        RuntimeFiles.deleteTreeNoFollowFallback(allowedParent.resolve("missing").toFile(), allowedParent.toFile())

        assertTrue(Files.isDirectory(allowedParent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun fallbackRejectsDeletionOutsideTheImmediateAllowedParent() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        val allowedParent = Files.createDirectory(sandbox.resolve("runtime"))
        val outside = Files.createDirectory(sandbox.resolve("outside"))

        val failure = assertThrows(RuntimeFailure::class.java) {
            RuntimeFiles.deleteTreeNoFollowFallback(outside.toFile(), allowedParent.toFile())
        }

        assertEquals("RESET_SCOPE_INVALID", failure.code)
        assertTrue(Files.isDirectory(outside, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun fallbackDeletesSymbolicLinkWithoutFollowingItsTarget() {
        val sandbox = temporaryFolder.newFolder("sandbox").toPath()
        val allowedParent = Files.createDirectory(sandbox.resolve("runtime"))
        val target = Files.createDirectory(allowedParent.resolve("target"))
        val external = Files.createDirectory(sandbox.resolve("external"))
        val marker = Files.write(external.resolve("keep.txt"), "keep".toByteArray())
        createSymbolicLinkOrSkip(target.resolve("external-link"), external)

        RuntimeFiles.deleteTreeNoFollowFallback(target.toFile(), allowedParent.toFile())

        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
    }

    private fun assumeSecureDirectoryStreams(path: Path) {
        assumeTrue("Default provider does not support secure directory streams", supportsSecureDirectoryStreams(path))
    }

    /**
     * 根内 loader 拷贝的判定依据：长度不同立刻判否；任一文件读不到同样判否（调用方会重建而不是报错）。
     */
    @Test
    fun comparesContentOnlyWhenBothFilesAreReadable() {
        val first = temporaryFolder.newFile("first.bin")
        val second = temporaryFolder.newFile("second.bin")
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        first.writeBytes(payload)
        second.writeBytes(payload)

        assertTrue(RuntimeFiles.sameContent(first, second))

        second.writeBytes(payload.copyOf(payload.size - 1))
        assertFalse(RuntimeFiles.sameContent(first, second))

        val flipped = payload.copyOf()
        flipped[payload.size / 2] = (flipped[payload.size / 2] + 1).toByte()
        second.writeBytes(flipped)
        assertFalse(RuntimeFiles.sameContent(first, second))

        assertFalse(RuntimeFiles.sameContent(first, temporaryFolder.root.toPath().resolve("missing.bin").toFile()))
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
