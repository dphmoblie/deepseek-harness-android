package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelProbeInputsTest {
    private val saved = CustomModelProvider(
        id = "gateway",
        name = "Saved gateway",
        api = CustomProviderApi.OPENAI_COMPLETIONS,
        baseUrl = "https://saved.example/v1",
        models = listOf(CustomProviderModel("saved-model", "Saved model", 32768, 4096)),
    )

    @Test
    fun currentDraftEndpointWinsWhenSavedKeyIsReused() {
        assertEquals("https://draft.example/v1", draftOrSavedBaseUrl(" https://draft.example/v1 ", saved))
        assertEquals("openai-responses", draftOrSavedApi(" openai-responses ", saved))
    }

    @Test
    fun blankDraftEndpointRemainsBlankForValidation() {
        assertEquals("", draftOrSavedBaseUrl("", saved))
        assertEquals("", draftOrSavedApi("", saved))
    }

    @Test
    fun missingDraftEndpointFallsBackToSavedProvider() {
        assertEquals("https://saved.example/v1", draftOrSavedBaseUrl(null, saved))
        assertEquals("openai-completions", draftOrSavedApi(null, saved))
    }
}
