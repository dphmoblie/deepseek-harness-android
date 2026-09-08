package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeModelProvidersTest {
    @Test
    fun providerUpdateRejectsUnknownProvidersAndCommandSeparators() {
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.providerApiKeyUpdates(JSONObject().put("unexpected", "placeholder"))
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.requireProviderApiKey("placeholder\nINJECTED=value")
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.requireProviderApiKey("x".repeat(201))
        }
    }

    @Test
    fun providerUpdatesPreserveMultipleProvidersWithoutReturningUnknownFields() {
        val updates = RuntimeValidation.providerApiKeyUpdates(
            JSONObject().put("openai", " example-placeholder ").put("google", "another-placeholder"),
        )
        assertEquals(setOf(ModelProvider.OPENAI, ModelProvider.GOOGLE), updates.keys)
        assertEquals("example-placeholder", updates[ModelProvider.OPENAI])
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.clearedProviderApiKeys(JSONArray().put("openai").put("openai"))
        }
    }

    @Test
    fun launcherOverlayIsASeparateArgumentAndKeepsManifestPort() {
        val entrypoint = listOf("/usr/local/bin/dsh", "web", "--host", "127.0.0.1", "--port", "3080")
        assertEquals(entrypoint, RuntimeCommand.withProviderPatch(entrypoint, null))
        assertEquals(
            listOf("/usr/local/bin/dsh", "web", "--patch", RuntimeCommand.PROVIDER_PATCH_GUEST_PATH,
                "--host", "127.0.0.1", "--port", "3080"),
            RuntimeCommand.withProviderPatch(entrypoint, RuntimeCommand.PROVIDER_PATCH_GUEST_PATH),
        )
        assertThrows(RuntimeFailure::class.java) {
            RuntimeCommand.withProviderPatch(entrypoint, "/tmp/untrusted.json")
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeCommand.withProviderPatch(listOf("/bin/sh"), RuntimeCommand.PROVIDER_PATCH_GUEST_PATH)
        }
    }

    @Test
    fun validatesCompleteCustomProviderAndRejectsUnsafeValues() {
        val model = JSONObject().put("id", "org/model").put("name", "Model")
            .put("contextWindow", 32768).put("maxTokens", 4096)
        val provider = JSONObject().put("id", "gateway").put("name", "Gateway")
            .put("api", "openai-completions").put("baseUrl", "https://api.example.com/v1/")
            .put("models", JSONArray().put(model))
        val parsed = RuntimeValidation.customModelProviders(JSONArray().put(provider))
        assertEquals("gateway", parsed.single().id)
        assertEquals("https://api.example.com/v1", parsed.single().baseUrl)
        assertEquals("DSH_CUSTOM_PROVIDER_GATEWAY_API_KEY", parsed.single().environmentVariable)

        val loopback = JSONObject(provider.toString()).put("baseUrl", "HTTP://LOCALHOST:8080/v1/")
        assertEquals(
            "HTTP://LOCALHOST:8080/v1",
            RuntimeValidation.customModelProviders(JSONArray().put(loopback)).single().baseUrl,
        )

        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.customModelProviders(JSONArray().put(JSONObject(provider.toString()).put("id", "openai")))
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.customModelProviders(JSONArray().put(JSONObject(provider.toString()).put("baseUrl", "http://api.example.com")))
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.customModelProviders(JSONArray().put(JSONObject(provider.toString()).put("baseUrl", "https://api.example.com:0/v1")))
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.customProviderApiKeyUpdates(JSONObject().put("unknown", "placeholder"), setOf("gateway"))
        }
    }
}
