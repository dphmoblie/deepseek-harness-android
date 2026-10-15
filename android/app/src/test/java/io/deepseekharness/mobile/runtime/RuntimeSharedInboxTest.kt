package io.deepseekharness.mobile.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeSharedInboxTest {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    private fun manager(root: Path): RuntimeSharedInbox {
        Files.createDirectories(root.resolve("files"))
        Files.createDirectories(root.resolve("no-backup"))
        return RuntimeSharedInbox(root.resolve("files").toFile(), root.resolve("no-backup").toFile(), root.resolve("no-backup/runtime/current").toFile())
    }

    private fun supportsSecureStreams(root: Path) = Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> }
    private fun current(root: Path) = root.resolve("no-backup/runtime/current")
    private fun destination(root: Path) = current(root).resolve("root/1/inbox")

    @Test
    fun reclaimsOnlyAbandonedPendingRegularFiles() {
        val root = temporaryFolder.root.toPath()
        val inbox = manager(root)
        val staging = inbox.stagingDirectory().toPath()
        val orphan = Files.write(staging.resolve(".pending-${UUID.randomUUID()}"), "interrupted-copy".toByteArray())
        val published = Files.write(staging.resolve("shared.txt"), "keep-published".toByteArray())
        val unknown = Files.write(staging.resolve(".pending-user-file"), "keep-unknown".toByteArray())
        val directory = Files.createDirectory(staging.resolve(".pending-${UUID.randomUUID()}"))
        Files.write(directory.resolve("keep.txt"), "keep-child".toByteArray())

        manager(root).stagingDirectory()

        assertFalse(Files.exists(orphan))
        assertTrue(Files.exists(published))
        assertTrue(Files.exists(unknown))
        assertTrue(Files.exists(directory.resolve("keep.txt")))
    }

    @Test
    fun activeCopySurvivesCleanupFromAnotherInboxInstance() {
        val root = temporaryFolder.root.toPath()
        val inbox = manager(root)
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val active = AtomicReference<File>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                inbox.withPendingFile { pending ->
                    pending.writeText("copy-in-progress")
                    active.set(pending)
                    started.countDown()
                    assertTrue(resume.await(5, TimeUnit.SECONDS))
                    assertEquals("copy-in-progress", pending.readText())
                }
            } catch (error: Throwable) {
                failure.set(error)
                started.countDown()
            }
        }
        worker.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError(it) }
            manager(root).stagingDirectory()
            assertTrue(active.get().exists())
        } finally {
            resume.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertFalse(active.get().exists())
    }

    @Test
    fun failedCopyRemovesItsPendingFile() {
        val inbox = manager(temporaryFolder.root.toPath())
        lateinit var pending: File
        assertThrows(InterruptedException::class.java) {
            inbox.withPendingFile {
                pending = it
                it.writeText("partial")
                throw InterruptedException()
            }
        }
        assertFalse(pending.exists())
    }

    @Test
    fun pendingCleanupDoesNotFollowSymbolicLinks() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val inbox = manager(root)
        val outside = Files.write(root.resolve("outside.txt"), "keep-outside".toByteArray())
        val link = inbox.stagingDirectory().toPath().resolve(".pending-${UUID.randomUUID()}")
        Files.createSymbolicLink(link, outside)

        inbox.stagingDirectory()

        assertTrue(Files.isSymbolicLink(link))
        assertEquals("keep-outside", outside.toFile().readText())
    }

    @Test
    fun batchDeliversPublishedFileBeforeTheNextSourceFails() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val inbox = manager(root)
        Files.createDirectories(current(root))
        inbox.prepareWorkspace()
        val result = SharedFileImportBatch.run(listOf("first", "unreadable"), importOne = { _, name ->
            if (name == "unreadable") {
                assertEquals("complete", destination(root).resolve("first.txt").toFile().readText())
                SharedFileImportBatch.Status.PERMISSION_DENIED
            } else {
                inbox.withPendingFile { pending ->
                    pending.writeText("complete")
                    RuntimeSharedInbox.withLock {
                        Files.move(pending.toPath(), pending.parentFile.toPath().resolve("first.txt"), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    }
                }
                SharedFileImportBatch.Status.SUCCESS
            }
        }, deliver = inbox::deliver)

        assertEquals(SharedFileImportBatch.Status.PERMISSION_DENIED, result.status)
        assertEquals(1, result.acceptedCount)
        assertTrue(result.deliveryComplete)
        assertEquals("complete", destination(root).resolve("first.txt").toFile().readText())
        assertEquals(0L, Files.list(inbox.stagingDirectory().toPath()).use { it.count() })
    }

    @Test
    fun retainsPreInstallFilesAndDeliversAfterWorkspaceIsPrepared() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val inbox = manager(root)
        val source = File(inbox.stagingDirectory(), "shared.txt")
        source.writeText("before-install")
        assertFalse(inbox.deliver())
        assertTrue(source.exists())
        Files.createDirectories(current(root))
        inbox.prepareWorkspace()
        assertTrue(inbox.deliver())
        assertEquals("before-install", destination(root).resolve("shared.txt").toFile().readText())
        assertFalse(source.exists())
    }

    @Test
    fun unavailableSecureStreamsNeverConsumeTheSource() {
        val root = temporaryFolder.root.toPath()
        assumeFalse(supportsSecureStreams(root))
        val inbox = manager(root)
        val source = File(inbox.stagingDirectory(), "shared.txt")
        source.writeText("keep")
        Files.createDirectories(current(root))
        inbox.prepareWorkspace()
        assertFalse(inbox.deliver())
        assertEquals("keep", source.readText())
        assertFalse(Files.exists(destination(root).resolve("shared.txt")))
    }

    @Test
    fun doesNotOverwriteFilesAndDoesNotPublishIncompleteImports() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val inbox = manager(root)
        val staging = inbox.stagingDirectory().toPath()
        val source = Files.write(staging.resolve("shared.txt"), "new".toByteArray())
        val pending = Files.write(staging.resolve(".pending-interrupted"), "partial".toByteArray())
        Files.createDirectories(current(root))
        inbox.prepareWorkspace()
        val existing = Files.write(destination(root).resolve("shared.txt"), "existing".toByteArray())
        assertTrue(inbox.deliver())
        assertEquals("existing", existing.toFile().readText())
        assertFalse(Files.exists(source))
        assertTrue(Files.exists(pending))
        val copies = Files.list(destination(root)).use { paths -> paths.filter { it.fileName.toString() != "shared.txt" }.toList() }
        assertEquals(1, copies.size)
        assertEquals("new", copies.single().toFile().readText())
    }

    @Test
    fun refusesLinksInGuestParentsOrStagedFilesAndPreservesBothSides() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val inbox = manager(root)
        val staging = inbox.stagingDirectory().toPath()
        val source = Files.write(staging.resolve("shared.txt"), "keep-source".toByteArray())
        val outside = Files.createDirectory(root.resolve("outside"))
        val marker = Files.write(outside.resolve("shared.txt"), "keep-outside".toByteArray())
        Files.createDirectories(current(root))
        inbox.prepareWorkspace()
        Files.delete(destination(root))
        Files.createSymbolicLink(destination(root), outside)
        assertThrows(RuntimeFailure::class.java) { inbox.prepareWorkspace() }
        assertFalse(inbox.deliver())
        assertEquals("keep-source", source.toFile().readText())
        assertEquals("keep-outside", marker.toFile().readText())
        Files.delete(destination(root))
        inbox.prepareWorkspace()
        Files.createSymbolicLink(staging.resolve("linked.txt"), marker)
        assertFalse(inbox.deliver())
        assertTrue(Files.exists(staging.resolve("linked.txt"), LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep-outside", marker.toFile().readText())
    }

    @Test
    fun interruptedTransferKeepsTheOriginalAndCanBeRetried() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val inbox = manager(root)
        val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
        val source = Files.write(inbox.stagingDirectory().toPath().resolve("shared.bin"), bytes)
        Files.createDirectories(current(root))
        inbox.prepareWorkspace()
        try {
            Thread.currentThread().interrupt()
            assertFalse(inbox.deliver())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertArrayEquals(bytes, Files.readAllBytes(source))
        val failedCopy = destination(root).resolve("shared.bin")
        assertEquals(0L, Files.size(failedCopy))
        assertTrue(inbox.deliver())
        assertFalse(Files.exists(source))
        // 失败目标可能已被访客接管；重试另起新名字，不能删除或覆盖该目标。
        assertEquals(0L, Files.size(failedCopy))
        val retries = Files.list(destination(root)).use { paths -> paths.filter { it != failedCopy }.toList() }
        assertEquals(1, retries.size)
        assertArrayEquals(bytes, Files.readAllBytes(retries.single()))
    }

    @Test
    fun pendingImportIsDeliveredAfterAtomicPublication() {
        val root = temporaryFolder.root.toPath()
        assumeTrue(supportsSecureStreams(root))
        val inbox = manager(root)
        val staging = inbox.stagingDirectory().toPath()
        val pending = Files.write(staging.resolve(".pending-import"), "complete".toByteArray())
        Files.createDirectories(current(root))
        inbox.prepareWorkspace()
        assertTrue(inbox.deliver())
        assertTrue(Files.exists(pending))
        assertEquals(0L, Files.list(destination(root)).use { it.count() })
        val published = staging.resolve("shared.txt")
        RuntimeSharedInbox.withLock {
            Files.move(pending, published, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        assertTrue(inbox.deliver())
        assertFalse(Files.exists(published))
        assertEquals("complete", destination(root).resolve("shared.txt").toFile().readText())
    }
}
