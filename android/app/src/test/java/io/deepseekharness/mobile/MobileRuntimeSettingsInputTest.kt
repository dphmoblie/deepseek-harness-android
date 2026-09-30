package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.runtime.HarnessPermissionMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileRuntimeSettingsInputTest {
    @Test
    fun validatesPermissionUpdatesWithoutCoercingOrResettingOmittedValues() {
        assertNull(optionalHarnessPermissionMode(JSONObject()))
        HarnessPermissionMode.entries.forEach { mode ->
            assertEquals(mode, optionalHarnessPermissionMode(JSONObject().put("harnessPermissionMode", mode.wireValue)))
        }
        listOf(JSONObject.NULL, true, 1, "", "read-only", "danger-full-access\n", "x".repeat(4096)).forEach { value ->
            assertThrows(RuntimeFailure::class.java) {
                optionalHarnessPermissionMode(JSONObject().put("harnessPermissionMode", value))
            }
        }
    }

    @Test
    fun parsesOmittedAndExplicitOverlayBallUpdates() {
        assertNull(optionalOverlayBallEnabled(JSONObject()))
        assertEquals(true, optionalOverlayBallEnabled(JSONObject().put("overlayBallEnabled", true)))
        assertEquals(false, optionalOverlayBallEnabled(JSONObject().put("overlayBallEnabled", false)))
    }

    @Test
    fun rejectsNonBooleanOverlayBallUpdates() {
        listOf(JSONObject.NULL, "true", 1).forEach { invalidValue ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                optionalOverlayBallEnabled(JSONObject().put("overlayBallEnabled", invalidValue))
            }
            assertEquals("SETTINGS_INVALID", failure.code)
        }
    }

    @Test
    fun treatsOmittedAndBlankMailboxSubdirectoryAsTheWholeWorkspace() {
        assertNull(optionalMailboxSubdirectory(JSONObject()))
        assertNull(optionalMailboxSubdirectory(JSONObject().put("subdirectory", JSONObject.NULL)))
        assertEquals("proj/src", optionalMailboxSubdirectory(JSONObject().put("subdirectory", "proj/src")))
    }

    @Test
    fun rejectsNonStringMailboxSubdirectory() {
        listOf(true, 1, listOf("proj"), JSONObject()).forEach { invalidValue ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                optionalMailboxSubdirectory(JSONObject().put("subdirectory", invalidValue))
            }
            assertEquals("MAILBOX_INPUT_INVALID", failure.code)
        }
    }

    @Test
    fun validatesMailboxRootWithoutCoercion() {
        assertEquals("inbox", requiredMailboxRoot(JSONObject().put("root", "inbox")))
        assertEquals("outbox", requiredMailboxRoot(JSONObject().put("root", "outbox")))
        listOf(JSONObject.NULL, true, 1, "INBOX", "../inbox").forEach { invalidValue ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                requiredMailboxRoot(JSONObject().put("root", invalidValue))
            }
            assertEquals("MAILBOX_ROOT_INVALID", failure.code)
        }
    }

    @Test
    fun validatesOptionalMailboxDestinationDirectoryWithoutCoercion() {
        assertNull(optionalMailboxDirectory(JSONObject(), "destinationDirectory"))
        assertNull(optionalMailboxDirectory(JSONObject().put("destinationDirectory", JSONObject.NULL), "destinationDirectory"))
        assertEquals("exports/weekly", optionalMailboxDirectory(JSONObject().put("destinationDirectory", "exports/weekly"), "destinationDirectory"))
        listOf(true, 1, JSONObject()).forEach { invalidValue ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                optionalMailboxDirectory(JSONObject().put("destinationDirectory", invalidValue), "destinationDirectory")
            }
            assertEquals("MAILBOX_PATH_INVALID", failure.code)
        }
    }
}
