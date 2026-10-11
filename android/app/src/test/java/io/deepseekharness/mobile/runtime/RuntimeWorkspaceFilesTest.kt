package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption

class RuntimeWorkspaceFilesTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private fun manager(root: Path) = RuntimeWorkspaceFiles(
        root.resolve("runtime/current/root/1").toFile(),
        root.resolve("cache").toFile(),
        root.toFile(),
    )

    private fun supportsSecureStreams(root: Path): Boolean =
        Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> }

    private fun workspace(root: Path): Path = Files.createDirectories(root.resolve("runtime/current/root/1"))

    @Test
    fun rejectsInvalidRelativePathsBeforeAccess() {
        val files = manager(temporaryFolder.root.toPath())
        for (path in listOf("", "/file", "../file", "nested/../file", "nested//file", "nested/./file", "nested\\file", "bad\u0000file", "bad\nfile", "x".repeat(241))) {
            assertEquals("WORKSPACE_PATH_INVALID", assertThrows(RuntimeFailure::class.java) { files.delete(path) }.code)
            assertEquals("WORKSPACE_PATH_INVALID", assertThrows(RuntimeFailure::class.java) { files.copyForSharing(path) }.code)
        }
    }

    @Test
    fun reportsWhenWorkspaceListingIsTruncated() {
        val root = temporaryFolder.root.toPath()
        val workspace = workspace(root)
        Files.write(workspace.resolve("b.txt"), "b".toByteArray())
        Files.write(workspace.resolve("a.txt"), "a".toByteArray())
        val snapshot = manager(root).list(maxFiles = 1)
        assertEquals(listOf("a.txt"), snapshot.files)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun refusesUnsafeFallbackWhenSecureStreamsAreUnavailable() {
        val root = temporaryFolder.root.toPath()
        assumeFalse(supportsSecureStreams(root))
        val source = Files.write(workspace(root).resolve("file.txt"), "keep".toByteArray())
        val files = manager(root)
        assertEquals("WORKSPACE_SECURE_ACCESS_UNAVAILABLE", assertThrows(RuntimeFailure::class.java) { files.delete("file.txt") }.code)
        assertEquals("WORKSPACE_SECURE_ACCESS_UNAVAILABLE", assertThrows(RuntimeFailure::class.java) { files.copyForSharing("file.txt") }.code)
        assertTrue(Files.exists(source))
    }

    @Test
    fun copiesAndDeletesOnlyTheSelectedWorkspaceFile() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val source = Files.write(Files.createDirectories(workspace(root).resolve("nested")).resolve("file.txt"), "payload".toByteArray())
        val files = manager(root)
        val exported = files.copyForSharing("nested/file.txt")
        assertEquals("payload", exported.readText())
        assertTrue(Files.exists(source))
        files.delete("nested/file.txt")
        assertFalse(Files.exists(source))
        assertEquals("payload", exported.readText())
    }

    @Test
    fun refusesSymbolicLinksAtEveryGuestControlledParent() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val outside = Files.createDirectory(root.resolve("outside"))
        val marker = Files.write(outside.resolve("file.txt"), "keep".toByteArray())
        val workspace = workspace(root)
        Files.createSymbolicLink(workspace.resolve("linked"), outside)
        val files = manager(root)
        assertThrows(RuntimeFailure::class.java) { files.copyForSharing("linked/file.txt") }
        assertThrows(RuntimeFailure::class.java) { files.delete("linked/file.txt") }
        Files.move(workspace, workspace.resolveSibling("saved"))
        Files.createSymbolicLink(workspace, outside)
        assertThrows(RuntimeFailure::class.java) { files.copyForSharing("file.txt") }
        assertThrows(RuntimeFailure::class.java) { files.delete("file.txt") }
        assertEquals("keep", String(Files.readAllBytes(marker)))
    }

    @Test
    fun refusesSymbolicLinkAsFinalFile() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val marker = Files.write(root.resolve("outside.txt"), "keep".toByteArray())
        Files.createSymbolicLink(workspace(root).resolve("file.txt"), marker)
        assertThrows(RuntimeFailure::class.java) { manager(root).copyForSharing("file.txt") }
        assertThrows(RuntimeFailure::class.java) { manager(root).delete("file.txt") }
        assertEquals("keep", String(Files.readAllBytes(marker)))
    }

    @Test
    fun openedParentCannotBeRedirectedBeforeReadingOrDeleting() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val workspace = workspace(root)
        val directory = Files.createDirectory(workspace.resolve("nested"))
        Files.write(directory.resolve("file.txt"), "inside".toByteArray())
        val outside = Files.createDirectory(root.resolve("outside"))
        val marker = Files.write(outside.resolve("file.txt"), "outside".toByteArray())
        val saved = workspace.resolve("saved")
        manager(root).withFile("nested/file.txt") { parent, name ->
            Files.move(directory, saved)
            Files.createSymbolicLink(directory, outside)
            val content = Channels.newInputStream(parent.newByteChannel(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
                .use { String(it.readBytes()) }
            assertEquals("inside", content)
            parent.deleteFile(name)
        }
        assertFalse(Files.exists(saved.resolve("file.txt")))
        assertEquals("outside", String(Files.readAllBytes(marker)))
    }
}
