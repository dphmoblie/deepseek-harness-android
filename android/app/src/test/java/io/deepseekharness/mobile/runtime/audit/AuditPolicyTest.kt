package io.deepseekharness.mobile.runtime.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AuditPolicyTest {
    @Test
    fun recordContainsOnlyTimestampAndFixedEnums() {
        val instant = Instant.parse("2026-08-16T12:34:56.789Z")
        val line = AuditPolicy.recordLine(instant, AuditEvent.RUNTIME_INSTALL, AuditResult.SUCCEEDED)

        assertEquals("2026-08-16T12:34:56.789Z|RUNTIME_INSTALL|SUCCEEDED\n", line)
        listOf(
            "https://downloads.example.invalid/private",
            "command-private-text",
            "credential-private-text",
            "terminal-private-text",
            "sessionId=00000000-0000-0000-0000-000000000000",
        ).forEach { sentinel -> assertFalse(line.contains(sentinel)) }

        val recordMethod = PrivateAuditLog::class.java.getDeclaredMethod(
            "record",
            AuditEvent::class.java,
            AuditResult::class.java,
            String::class.java,
            Instant::class.java,
        )
        assertEquals(
            listOf(AuditEvent::class.java, AuditResult::class.java, String::class.java, Instant::class.java),
            recordMethod.parameterTypes.toList(),
        )
        assertFalse(recordMethod.parameterTypes.any { type -> Throwable::class.java.isAssignableFrom(type) })
    }

    @Test
    fun detailAcceptsOnlyControlledErrorCodes() {
        val instant = Instant.parse("2026-08-16T12:34:56.789Z")

        val detailed = AuditPolicy.recordLine(
            instant,
            AuditEvent.RUNTIME_START,
            AuditResult.FAILED,
            "PROOT_GUEST_EXEC_FAILED",
        )
        assertEquals("2026-08-16T12:34:56.789Z|RUNTIME_START|FAILED|PROOT_GUEST_EXEC_FAILED\n", detailed)

        listOf(
            "proot error: execve /bin/bash",
            "https://downloads.example.invalid/private",
            "/data/user/0/io.deepseekharness.mobile/no_backup",
            "PROOT_GUEST_EXEC_FAILED\nsecond-line",
            "TOKEN=abc123",
            "internal error",
            "",
            "A".repeat(65),
        ).forEach { invalid ->
            assertEquals(
                "2026-08-16T12:34:56.789Z|RUNTIME_START|FAILED\n",
                AuditPolicy.recordLine(instant, AuditEvent.RUNTIME_START, AuditResult.FAILED, invalid),
            )
        }
    }

    @Test
    fun parsesOnlyStrictDailyAuditFileNames() {
        assertEquals(LocalDate.of(2024, 2, 29), AuditPolicy.parseFileDate("audit-2024-02-29.log"))
        listOf(
            "audit-2023-02-29.log",
            "audit-2026-8-16.log",
            "audit-2026-08-16.txt",
            "audit-2026-08-16.log.bak",
            "prefix-audit-2026-08-16.log",
            "AUDIT-2026-08-16.LOG",
            "../audit-2026-08-16.log",
            "audit-2026-08-16.log/child",
        ).forEach { fileName -> assertNull(AuditPolicy.parseFileDate(fileName)) }
    }

    @Test
    fun retainsNinetyDayBoundaryAndAllNewerFiles() {
        val today = LocalDate.of(2026, 8, 16)
        val oldestRetained = today.minusDays(AuditPolicy.RETENTION_DAYS)
        val older = oldestRetained.minusDays(1)

        val candidates = AuditPolicy.retentionCandidates(
            listOf(
                AuditPolicy.fileName(older),
                AuditPolicy.fileName(oldestRetained),
                AuditPolicy.fileName(today),
                AuditPolicy.fileName(today.plusDays(1)),
            ),
            today,
        )

        assertEquals(setOf(AuditPolicy.fileName(older)), candidates)
        assertFalse(AuditPolicy.fileName(oldestRetained) in candidates)
        assertTrue(AuditPolicy.RETENTION_DAYS >= 90)
    }

    @Test
    fun neverSelectsMalformedOrUnrecognizedFilesForDeletion() {
        val names = listOf(
            "notes.txt",
            "audit-current.log",
            "audit-2000-99-99.log",
            "audit-2000-01-01.log.bak",
            ".audit-2000-01-01.log",
        )

        assertTrue(AuditPolicy.retentionCandidates(names, LocalDate.of(2026, 8, 16)).isEmpty())
    }
}
