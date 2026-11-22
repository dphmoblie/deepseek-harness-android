package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.TaskLivePromotionPolicy.ActiveNotificationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLivePromotionPolicyTest {
    @Test
    fun activeChannelMigratesAwayFromLegacyLowImportanceChannel() {
        assertNotEquals(
            TaskLivePromotionPolicy.LEGACY_ACTIVE_CHANNEL_ID,
            TaskLivePromotionPolicy.ACTIVE_CHANNEL_ID,
        )
        assertEquals("dsh_active_tasks", TaskLivePromotionPolicy.LEGACY_ACTIVE_CHANNEL_ID)
    }

    @Test
    fun selectStrategiesPrioritizesProgressStyleThenStandardOnAndroid16Plus() {
        assertEquals(
            listOf(ActiveNotificationStrategy.COMPAT_PROGRESS),
            TaskLivePromotionPolicy.selectStrategies(35),
        )
        assertEquals(
            listOf(
                ActiveNotificationStrategy.FRAMEWORK_PROGRESS_STYLE,
                ActiveNotificationStrategy.FRAMEWORK_STANDARD,
                ActiveNotificationStrategy.COMPAT_PROGRESS,
            ),
            TaskLivePromotionPolicy.selectStrategies(36),
        )
        assertEquals(
            listOf(
                ActiveNotificationStrategy.FRAMEWORK_PROGRESS_STYLE,
                ActiveNotificationStrategy.FRAMEWORK_STANDARD,
                ActiveNotificationStrategy.COMPAT_PROGRESS,
            ),
            TaskLivePromotionPolicy.selectStrategies(37),
        )
    }

    @Test
    fun frameworkCandidateAcceptanceRejectsExplicitFalseOnly() {
        assertTrue(TaskLivePromotionPolicy.shouldAcceptFrameworkCandidate(true))
        assertTrue(TaskLivePromotionPolicy.shouldAcceptFrameworkCandidate(null))
        assertFalse(TaskLivePromotionPolicy.shouldAcceptFrameworkCandidate(false))
    }

    @Test
    fun promotedPermissionStateMapsPlatformAvailability() {
        assertEquals("unsupported", TaskLivePromotionPolicy.promotedPermissionState(35, true))
        assertEquals("unsupported", TaskLivePromotionPolicy.promotedPermissionState(35, false))
        assertEquals("unsupported", TaskLivePromotionPolicy.promotedPermissionState(36, null))
        assertEquals("granted", TaskLivePromotionPolicy.promotedPermissionState(36, true))
        assertEquals("prompt", TaskLivePromotionPolicy.promotedPermissionState(36, false))
    }

    @Test
    fun diagnosticReasonReportsOnlyActionablePromotionBlocks() {
        assertEquals(
            "live_notification_unpromotable",
            TaskLivePromotionPolicy.diagnosticReason("granted", false),
        )
        assertEquals(
            "live_notification_unpromotable",
            TaskLivePromotionPolicy.diagnosticReason("prompt", false),
        )
        assertEquals(
            "live_notification_denied",
            TaskLivePromotionPolicy.diagnosticReason("prompt", true),
        )
        assertEquals(
            "live_notification_denied",
            TaskLivePromotionPolicy.diagnosticReason("prompt", null),
        )
        assertNull(TaskLivePromotionPolicy.diagnosticReason("granted", true))
        assertNull(TaskLivePromotionPolicy.diagnosticReason("unsupported", null))
    }

    @Test
    fun normalizeShortStatusTrimsOuterWhitespace() {
        assertEquals("运行中", TaskLivePromotionPolicy.normalizeShortStatus("  运行中 \n"))
    }

    @Test
    fun shouldRecordDiagnosticDeduplicatesAndReTriggersAfterRecovery() {
        assertTrue(TaskLivePromotionPolicy.shouldRecordDiagnostic(null, "live_notification_denied"))
        assertFalse(TaskLivePromotionPolicy.shouldRecordDiagnostic("live_notification_denied", "live_notification_denied"))
        assertTrue(TaskLivePromotionPolicy.shouldRecordDiagnostic("live_notification_denied", "live_notification_unpromotable"))
        assertFalse(TaskLivePromotionPolicy.shouldRecordDiagnostic("live_notification_denied", null))
        assertTrue(TaskLivePromotionPolicy.shouldRecordDiagnostic(null, "live_notification_denied"))
    }
}
