package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class HarnessCredentialStatusTest {
    @Test
    fun reportsKnownProviderReferencesWithoutReturningValues() {
        val status = parse(
            """
            version: 1
            refs:
              DEEPSEEK_API_KEY: unit-test-secret
              OPENAI_API_KEY: another-unit-test-secret
              UNRELATED_TOKEN: ignored-unit-test-secret
            """.trimIndent(),
        )

        assertEquals(setOf(ModelProvider.DEEPSEEK, ModelProvider.OPENAI), status.modelProviders)
        assertEquals(emptySet<String>(), status.customProviderIds)
    }

    @Test
    fun mapsOnlyDeclaredCustomProviderReferences() {
        val customProvider = CustomModelProvider(
            id = "gateway-1",
            name = "Gateway",
            api = CustomProviderApi.OPENAI_COMPLETIONS,
            baseUrl = "https://api.example.com/v1",
            models = listOf(CustomProviderModel("org/model", "Model", 32768, 4096)),
        )
        val status = parse(
            """
            version: 1
            refs:
              DSH_CUSTOM_PROVIDER_GATEWAY_1_API_KEY: unit-test-secret
              UNKNOWN_API_KEY: ignored-unit-test-secret
            """.trimIndent(),
            listOf(customProvider),
        )

        assertEquals(setOf("gateway-1"), status.customProviderIds)
    }

    @Test
    fun malformedOrUnsupportedDocumentsFailClosed() {
        val cases = listOf(
            "version: 2\nrefs:\n  DEEPSEEK_API_KEY: value",
            "version: 1\nunknown: value\nrefs:\n  DEEPSEEK_API_KEY: value",
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: ''",
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: [value]",
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: first\n  DEEPSEEK_API_KEY: second",
        )

        cases.forEach { yaml ->
            assertEquals(HarnessCredentialStatus(), parse(yaml))
        }
    }

    private fun parse(
        yaml: String,
        customProviders: List<CustomModelProvider> = emptyList(),
    ): HarnessCredentialStatus = try {
        HarnessCredentialStatusReader.parse(
            ByteArrayInputStream(yaml.toByteArray(Charsets.UTF_8)),
            customProviders,
        )
    } catch (_: Exception) {
        HarnessCredentialStatus()
    }
}
