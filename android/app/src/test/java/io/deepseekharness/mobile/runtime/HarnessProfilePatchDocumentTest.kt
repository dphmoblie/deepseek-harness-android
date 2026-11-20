package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class HarnessProfilePatchDocumentTest {

    private fun sampleCustomProvider(
        id: String = "custom-1",
        input: List<String>? = listOf("text", "image"),
        baseUrl: String = "https://api.example.com/v1",
    ): CustomModelProvider = CustomModelProvider(
        id = id,
        name = "Example Gateway",
        api = CustomProviderApi.OPENAI_COMPLETIONS,
        baseUrl = baseUrl,
        models = listOf(
            CustomProviderModel(
                id = "deepseek-v4",
                name = "DeepSeek V4",
                contextWindow = 262144,
                maxTokens = 32768,
                input = input,
            ),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun parseYamlList(text: String): List<Map<String, Any?>> =
        Load(LoadSettings.builder().build()).loadFromString(text) as List<Map<String, Any?>>

    @Test
    fun `empty state with no file does not request write`() {
        val outcome = HarnessProfilePatchDocument.mergeLlmPiAi(
            existingText = null,
            appProviders = emptyMap(),
            previousDigests = emptyMap(),
        )
        assertFalse(outcome.changed)
        assertEquals(emptyMap<String, String>(), outcome.managedDigests)
    }

    @Test
    fun `creates llm-pi-ai entry with image input modality in profile patch`() {
        val appProviders = HarnessProfilePatchDocument.buildAppProviders(
            enabledBuiltIn = listOf(ModelProvider.OPENAI),
            customProviders = listOf(sampleCustomProvider()),
        )
        val outcome = HarnessProfilePatchDocument.mergeLlmPiAi(
            existingText = null,
            appProviders = appProviders,
            previousDigests = emptyMap(),
        )
        assertTrue(outcome.changed)
        val yaml = outcome.yamlText
        assertNotNull(yaml)

        val rows = parseYamlList(yaml!!)
        assertEquals(1, rows.size)
        assertEquals("llm-pi-ai", rows[0]["id"])
        assertEquals("@deepseek-ai/dsh-llm-pi-ai", rows[0]["name"])
        @Suppress("UNCHECKED_CAST")
        val providers = (rows[0]["config"] as Map<String, Any?>)["providers"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val openai = providers["openai"] as Map<String, Any?>
        assertEquals("OPENAI_API_KEY", openai["apiKeyEnv"])

        @Suppress("UNCHECKED_CAST")
        val custom = providers["custom-1"] as Map<String, Any?>
        assertEquals("DSH_CUSTOM_PROVIDER_CUSTOM_1_API_KEY", custom["apiKeyEnv"])
        assertEquals("Example Gateway", custom["displayName"])
        assertEquals("openai-completions", custom["api"])
        assertEquals("https://api.example.com/v1", custom["baseURL"])
        @Suppress("UNCHECKED_CAST")
        val models = custom["models"] as List<Map<String, Any?>>
        assertEquals(1, models.size)
        assertEquals(listOf("text", "image"), models[0]["input"])
    }

    @Test
    fun `preserves Harness Web UI edits when App config is unchanged`() {
        val appProviders = HarnessProfilePatchDocument.buildAppProviders(
            enabledBuiltIn = emptyList(),
            customProviders = listOf(sampleCustomProvider(input = null)),
        )
        val initial = HarnessProfilePatchDocument.mergeLlmPiAi(null, appProviders, emptyMap())
        assertTrue(initial.changed)

        // 用户在 Harness Web UI 里给同一个 custom-1 勾选了图片输入与推理等级，并新建了另一个供应商 web-only。
        val userEditedYaml = """
            - id: agent-default-model
              name: "@deepseek-ai/dsh-agent-default-model"
              config:
                provider: custom-1
                model: deepseek-v4
            - id: llm-pi-ai
              name: "@deepseek-ai/dsh-llm-pi-ai"
              config:
                providers:
                  custom-1:
                    apiKeyEnv: DSH_CUSTOM_PROVIDER_CUSTOM_1_API_KEY
                    displayName: Example Gateway
                    api: openai-completions
                    baseURL: https://api.example.com/v1
                    models:
                      - id: deepseek-v4
                        name: DeepSeek V4
                        contextWindow: 262144
                        maxTokens: 32768
                        input:
                          - text
                          - image
                        reasoningEfforts:
                          high: high
                  web-only:
                    apiKeyEnv: WEB_ONLY_KEY
                    displayName: Web Only
                    api: openai-completions
                    baseURL: https://web.example.com/v1
                    models:
                      - id: vision-1
                        input:
                          - text
                          - image
        """.trimIndent() + "\n"

        // 下次启动时 App 侧未改过 custom-1，不得覆盖用户在 Web UI 里的编辑。
        val rerun = HarnessProfilePatchDocument.mergeLlmPiAi(
            existingText = userEditedYaml,
            appProviders = appProviders,
            previousDigests = initial.managedDigests,
        )
        assertFalse(rerun.changed)
        assertEquals(userEditedYaml, rerun.yamlText)
    }

    @Test
    fun `updates App-managed fields on edit while keeping Web UI extra fields and providers`() {
        val v1 = HarnessProfilePatchDocument.buildAppProviders(
            enabledBuiltIn = emptyList(),
            customProviders = listOf(sampleCustomProvider(input = listOf("text"))),
        )
        val initial = HarnessProfilePatchDocument.mergeLlmPiAi(null, v1, emptyMap())

        val userEditedYaml = """
            - id: llm-pi-ai
              name: "@deepseek-ai/dsh-llm-pi-ai"
              config:
                providers:
                  custom-1:
                    apiKeyEnv: DSH_CUSTOM_PROVIDER_CUSTOM_1_API_KEY
                    displayName: Example Gateway
                    api: openai-completions
                    baseURL: https://api.example.com/v1
                    headers:
                      X-Custom: keep-me
                    models:
                      - id: deepseek-v4
                        name: DeepSeek V4
                        contextWindow: 262144
                        maxTokens: 32768
                        input:
                          - text
                        reasoningEfforts:
                          medium: medium
                  web-only:
                    apiKeyEnv: WEB_ONLY_KEY
                    api: openai-completions
                    baseURL: https://web.example.com/v1
        """.trimIndent() + "\n"

        // 用户在 App 侧勾选了图片识别：应当合并更新 input，同时保留 headers、reasoningEfforts 和 web-only。
        val v2 = HarnessProfilePatchDocument.buildAppProviders(
            enabledBuiltIn = emptyList(),
            customProviders = listOf(sampleCustomProvider(input = listOf("text", "image"))),
        )
        val updated = HarnessProfilePatchDocument.mergeLlmPiAi(
            existingText = userEditedYaml,
            appProviders = v2,
            previousDigests = initial.managedDigests,
        )
        assertTrue(updated.changed)
        val rows = parseYamlList(updated.yamlText!!)
        @Suppress("UNCHECKED_CAST")
        val providers = (rows[0]["config"] as Map<String, Any?>)["providers"] as Map<String, Any?>
        assertTrue(providers.containsKey("web-only"))
        @Suppress("UNCHECKED_CAST")
        val custom = providers["custom-1"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        assertEquals("keep-me", (custom["headers"] as Map<String, Any?>)["X-Custom"])
        @Suppress("UNCHECKED_CAST")
        val model = (custom["models"] as List<Map<String, Any?>>).single()
        assertEquals(listOf("text", "image"), model["input"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("medium", (model["reasoningEfforts"] as Map<String, Any?>)["medium"])
    }

    @Test
    fun `removes deleted App provider without touching Web UI providers and cleans empty row`() {
        val appProviders = HarnessProfilePatchDocument.buildAppProviders(
            enabledBuiltIn = emptyList(),
            customProviders = listOf(sampleCustomProvider()),
        )
        val initial = HarnessProfilePatchDocument.mergeLlmPiAi(null, appProviders, emptyMap())

        // 只剩 App 托管的供应商时删除：整个 llm-pi-ai 行被清干净，文档回到空数组。
        val cleared = HarnessProfilePatchDocument.mergeLlmPiAi(
            existingText = initial.yamlText,
            appProviders = emptyMap(),
            previousDigests = initial.managedDigests,
        )
        assertTrue(cleared.changed)
        assertEquals("[]\n", cleared.yamlText)
        assertEquals(emptyMap<String, String>(), cleared.managedDigests)
    }

    @Test
    fun `round-trips !!js expressions in sibling entries intact`() {
        val existingWithJs = """
            - id: custom-row
              config:
                root: !!js "dshHomePath('sessions')"
        """.trimIndent() + "\n"

        val appProviders = HarnessProfilePatchDocument.buildAppProviders(
            enabledBuiltIn = listOf(ModelProvider.ANTHROPIC),
            customProviders = emptyList(),
        )
        val outcome = HarnessProfilePatchDocument.mergeLlmPiAi(existingWithJs, appProviders, emptyMap())
        assertTrue(outcome.changed)
        assertNotNull(outcome.yamlText)
        assertTrue(outcome.yamlText!!.contains("!!js"))
        assertTrue(outcome.yamlText!!.contains("dshHomePath('sessions')"))
        assertTrue(outcome.yamlText!!.contains("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `fails closed on corrupted or non-list profile patch`() {
        val appProviders = HarnessProfilePatchDocument.buildAppProviders(
            enabledBuiltIn = listOf(ModelProvider.OPENAI),
            customProviders = emptyList(),
        )
        val nonList = HarnessProfilePatchDocument.mergeLlmPiAi("foo: bar\n", appProviders, emptyMap())
        assertNull(nonList.yamlText)
        assertFalse(nonList.changed)

        val brokenYaml = HarnessProfilePatchDocument.mergeLlmPiAi("- [unclosed", appProviders, emptyMap())
        assertNull(brokenYaml.yamlText)
        assertFalse(brokenYaml.changed)
    }
}
