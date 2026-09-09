package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalOutputDispatchTest {
    @Test
    fun suppressedOutputStillReachesCommandParserOnly() {
        val commandOutput = mutableListOf<Pair<String, String>>()
        val publicOutput = mutableListOf<Pair<String, String>>()

        dispatchTerminalOutput(
            "session",
            "data",
            suppressPublicOutput = true,
            onDeviceCommandOutput = { sessionId, data -> commandOutput += sessionId to data },
            onPublicOutput = { sessionId, data -> publicOutput += sessionId to data },
        )

        assertEquals(listOf("session" to "data"), commandOutput)
        assertEquals(emptyList<Pair<String, String>>(), publicOutput)
    }

    @Test
    fun publicOutputReachesBothConsumers() {
        val commandOutput = mutableListOf<Pair<String, String>>()
        val publicOutput = mutableListOf<Pair<String, String>>()

        dispatchTerminalOutput(
            "session",
            "data",
            suppressPublicOutput = false,
            onDeviceCommandOutput = { sessionId, data -> commandOutput += sessionId to data },
            onPublicOutput = { sessionId, data -> publicOutput += sessionId to data },
        )

        assertEquals(listOf("session" to "data"), commandOutput)
        assertEquals(listOf("session" to "data"), publicOutput)
    }
}
