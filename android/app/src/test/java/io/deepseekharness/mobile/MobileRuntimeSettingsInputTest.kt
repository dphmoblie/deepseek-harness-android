package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileRuntimeSettingsInputTest {
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
}
