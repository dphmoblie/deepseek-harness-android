package io.deepseekharness.mobile.runtime.audit

import android.content.Context
import android.system.Os
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDate

class PrivateAuditLog(context: Context) {
    private val auditDirectory = context.noBackupFilesDir.toPath().resolve(DIRECTORY_NAME)
    private var lastRetentionDate: LocalDate? = null

    fun record(event: AuditEvent, result: AuditResult, instant: Instant = Instant.now()) {
        synchronized(PROCESS_LOCK) {
            try {
                ensurePrivateDirectory()
                val today = AuditPolicy.utcDate(instant)
                pruneOncePerDay(today)
                append(AuditPolicy.fileName(today), AuditPolicy.recordLine(instant, event, result))
            } catch (_: Throwable) {
                // Audit storage must never expose details or change the runtime operation's outcome.
            }
        }
    }

    private fun ensurePrivateDirectory() {
        val attributes = try {
            Files.readAttributes(
                auditDirectory,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            try {
                Files.createDirectory(auditDirectory)
            } catch (_: FileAlreadyExistsException) {
                // Another in-process initialization may have created it.
            }
            Files.readAttributes(
                auditDirectory,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        }
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw IOException("Invalid audit directory")
        }
        Os.chmod(auditDirectory.toString(), DIRECTORY_MODE)
    }

    private fun pruneOncePerDay(today: LocalDate) {
        if (lastRetentionDate == today) return
        try {
            Files.newDirectoryStream(auditDirectory).use { entries ->
                val paths = entries.toList()
                val candidates = AuditPolicy.retentionCandidates(
                    paths.map { it.fileName.toString() },
                    today,
                )
                var completed = true
                paths.forEach { entry ->
                    if (entry.fileName.toString() !in candidates) return@forEach
                    try {
                        val attributes = Files.readAttributes(
                            entry,
                            BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                        if (attributes.isRegularFile && !attributes.isSymbolicLink) {
                            Files.deleteIfExists(entry)
                        }
                    } catch (_: Throwable) {
                        completed = false
                    }
                }
                if (completed) lastRetentionDate = today
            }
        } catch (_: Throwable) {
            // A retention failure keeps data longer and is retried on the next record.
        }
    }

    private fun append(fileName: String, line: String) {
        val path = auditDirectory.resolve(fileName)
        val channel = try {
            FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: FileAlreadyExistsException) {
            requireRegularFile(path)
            FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS,
            )
        }
        channel.use {
            Os.chmod(path.toString(), FILE_MODE)
            val buffer = ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8))
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
    }

    private fun requireRegularFile(path: Path) {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            throw IOException("Invalid audit file")
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "audit"
        const val DIRECTORY_MODE = 0b111_000_000 // 0700
        const val FILE_MODE = 0b110_000_000 // 0600
        val PROCESS_LOCK = Any()
    }
}
