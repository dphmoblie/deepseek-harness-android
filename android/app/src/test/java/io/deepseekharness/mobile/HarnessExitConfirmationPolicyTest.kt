package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessExitConfirmationPolicyTest {
    @Test
    fun firstBackAtEntryPageRequestsConfirmation() {
        val policy = HarnessExitConfirmationPolicy()

        assertEquals(
            HarnessExitConfirmationPolicy.Decision.PROMPT,
            policy.onBackPressed(canGoBack = false, nowMs = 1_000L),
        )
    }

    @Test
    fun confirmingSecondBackWithinWindowAllowsExit() {
        val policy = HarnessExitConfirmationPolicy()

        policy.onBackPressed(canGoBack = false, nowMs = 1_000L)

        assertEquals(
            HarnessExitConfirmationPolicy.Decision.EXIT,
            policy.onBackPressed(canGoBack = false, nowMs = 2_000L),
        )
    }

    @Test
    fun expiredConfirmationRequiresAnotherPrompt() {
        val policy = HarnessExitConfirmationPolicy(confirmationWindowMs = 2_000L)

        policy.onBackPressed(canGoBack = false, nowMs = 1_000L)

        assertEquals(
            HarnessExitConfirmationPolicy.Decision.PROMPT,
            policy.onBackPressed(canGoBack = false, nowMs = 3_001L),
        )
    }

    @Test
    fun webHistoryBackNeverRequestsExitConfirmation() {
        val policy = HarnessExitConfirmationPolicy()

        assertEquals(
            HarnessExitConfirmationPolicy.Decision.WEB_BACK,
            policy.onBackPressed(canGoBack = true, nowMs = 1_000L),
        )
        assertEquals(
            HarnessExitConfirmationPolicy.Decision.PROMPT,
            policy.onBackPressed(canGoBack = false, nowMs = 1_100L),
        )
    }

    @Test
    fun resetClearsPendingConfirmation() {
        val policy = HarnessExitConfirmationPolicy()

        policy.onBackPressed(canGoBack = false, nowMs = 1_000L)
        policy.reset()

        assertEquals(
            HarnessExitConfirmationPolicy.Decision.PROMPT,
            policy.onBackPressed(canGoBack = false, nowMs = 1_100L),
        )
    }
}
