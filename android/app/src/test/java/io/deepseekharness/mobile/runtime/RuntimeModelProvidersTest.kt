package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeModelProvidersTest {

    @Test
    fun harnessEntrypointAlwaysDisablesTheExternalBrowser() {
        val entrypoint = listOf("/usr/local/bin/dsh", "web", "--host", "127.0.0.1", "--port", "3080")
        val pinned = RuntimeCommand.withNoOpen(entrypoint)
        assertEquals(listOf("/usr/local/bin/dsh", "web", "--host", "127.0.0.1", "--port", "3080", "--no-open"), pinned)
        assertEquals(pinned, RuntimeCommand.withNoOpen(pinned))
        val terminal = listOf("/bin/bash", "--login")
        assertEquals(terminal, RuntimeCommand.withNoOpen(terminal))
    }

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
            listOf("/usr/local/bin/dsh", "--profile", "web", "--patch", RuntimeCommand.PROVIDER_PATCH_GUEST_PATH,
                "--host", "127.0.0.1", "--port", "3080"),
            RuntimeCommand.withProviderPatch(entrypoint, RuntimeCommand.PROVIDER_PATCH_GUEST_PATH),
        )
        assertThrows(RuntimeFailure::class.java) {
            RuntimeCommand.withProviderPatch(entrypoint, "/tmp/untrusted.json")
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeCommand.withProviderPatch(listOf("/bin/sh"), RuntimeCommand.PROVIDER_PATCH_GUEST_PATH)
        }

        // provider + plugin 两个补丁叠加时 --profile 只能出现一次，--patch 必须有两条。
        val providerPatched = RuntimeCommand.withProviderPatch(
            entrypoint, RuntimeCommand.PROVIDER_PATCH_GUEST_PATH,
        )
        val stacked = RuntimeCommand.withPatch(providerPatched, "/root/.dsh-mobile/launcher-plugins.patch.json")
        assertEquals(1, stacked.count { it == "--profile" })
        assertEquals(2, stacked.count { it == "--patch" })
        assertEquals("--no-open", RuntimeCommand.withNoOpen(stacked).last())
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
        assertEquals(
            "http://api.example.com",
            RuntimeValidation.customModelProviders(
                JSONArray().put(JSONObject(provider.toString()).put("baseUrl", "http://api.example.com/")),
            ).single().baseUrl,
        )
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.customModelProviders(JSONArray().put(JSONObject(provider.toString()).put("baseUrl", "https://api.example.com:0/v1")))
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.customProviderApiKeyUpdates(JSONObject().put("unknown", "placeholder"), setOf("gateway"))
        }

        // 模型输入模态：支持 text / image，拒绝空数组、重复项与非法模态。
        val visionModel = JSONObject(model.toString()).put("input", JSONArray().put("text").put("image"))
        val visionProvider = JSONObject(provider.toString()).put("models", JSONArray().put(visionModel))
        assertEquals(
            listOf("text", "image"),
            RuntimeValidation.customModelProviders(JSONArray().put(visionProvider)).single().models.single().input,
        )
        assertEquals(null, parsed.single().models.single().input)
        assertThrows(RuntimeFailure::class.java) {
            val bad = JSONObject(provider.toString()).put("models", JSONArray().put(JSONObject(model.toString()).put("input", JSONArray())))
            RuntimeValidation.customModelProviders(JSONArray().put(bad))
        }
        assertThrows(RuntimeFailure::class.java) {
            val bad = JSONObject(provider.toString()).put("models", JSONArray().put(JSONObject(model.toString()).put("input", JSONArray().put("text").put("audio"))))
            RuntimeValidation.customModelProviders(JSONArray().put(bad))
        }
        assertThrows(RuntimeFailure::class.java) {
            val bad = JSONObject(provider.toString()).put("models", JSONArray().put(JSONObject(model.toString()).put("input", JSONArray().put("image").put("image"))))
            RuntimeValidation.customModelProviders(JSONArray().put(bad))
        }
    }
}
