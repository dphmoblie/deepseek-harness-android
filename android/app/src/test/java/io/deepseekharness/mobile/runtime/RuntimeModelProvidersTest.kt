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
}
