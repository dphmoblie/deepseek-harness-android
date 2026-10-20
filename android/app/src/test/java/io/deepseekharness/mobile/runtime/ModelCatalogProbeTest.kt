package io.deepseekharness.mobile.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游模型列表的解析规则。
 *
 * 只测纯函数部分（响应体 → 条目列表）：`list()` 要真联网，交给连通性探测那份测试的本地
 * ServerSocket 思路；这里把各家结构、去重、过滤与上限逐条钉死，避免哪天换成某个 SDK 时
 * 悄悄把这些行为丢掉。
 */
class ModelCatalogProbeTest {

    @Test
    fun parsesOpenAiStyleListAndPushesNonChatModelsToTheEnd() {
        val body = """
            {"object":"list","data":[
              {"id":"text-embedding-3-small","object":"model"},
              {"id":"gpt-4o","object":"model"},
              {"id":"whisper-1","object":"model"},
              {"id":"gpt-4o-mini","object":"model"}
            ]}
        """.trimIndent()
        val models = ModelCatalogProbe.parseModels(body)
        // 明显不是对话模型的条目被剔掉：自动选第一个时不会选中向量化或语音模型。
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), models.map { it.id })
    }

    @Test
    fun parsesAnthropicDisplayName() {
        val models = ModelCatalogProbe.parseModels(
            """{"data":[{"type":"model","id":"claude-3-5-sonnet","display_name":"Claude 3.5 Sonnet"}]}""",
        )
        assertEquals(1, models.size)
        assertEquals("claude-3-5-sonnet", models[0].id)
        assertEquals("Claude 3.5 Sonnet", models[0].name)
    }

    @Test
    fun parsesGeminiModelNamesAndStripsTheResourcePrefix() {
        val models = ModelCatalogProbe.parseModels(
            """{"models":[
              {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro","inputTokenLimit":1048576},
              {"name":"models/embedding-001","displayName":"Embedding 001"}
            ]}""",
        )
        assertEquals("gemini-2.5-pro", models[0].id)
        assertEquals("Gemini 2.5 Pro", models[0].name)
        // 只服务向量化的模型不当成可对话模型，从候选里剔掉。
        assertEquals(listOf("gemini-2.5-pro"), models.map { it.id })
    }

    @Test
    fun parsesTopLevelArrayAndDeduplicates() {
        val models = ModelCatalogProbe.parseModels(
            """[{"id":"custom-a"},{"id":"custom-a"},{"id":"custom-b","name":"Custom B"}]""",
        )
        assertEquals(listOf("custom-a", "custom-b"), models.map { it.id })
        assertEquals("custom-a", models[0].name)
        assertEquals("Custom B", models[1].name)
    }

    @Test
    fun skipsInvalidAndOverlongIdentifiers() {
        val longId = "m".repeat(201)
        val models = ModelCatalogProbe.parseModels(
            """{"data":[{"id":"has space"},{"id":"-leading-dash"},{"id":"$longId"},{"id":"ok-model"}]}""",
        )
        assertEquals(listOf("ok-model"), models.map { it.id })
    }

    @Test
    fun fallsBackToEveryEntryWhenNothingLooksLikeAChatModel() {
        val models = ModelCatalogProbe.parseModels("""{"data":[{"id":"local-embedding-model"}]}""")
        assertEquals(listOf("local-embedding-model"), models.map { it.id })
    }

    @Test
    fun returnsEmptyForBlankOrForeignBodies() {
        assertEquals(emptyList<ModelCatalogEntry>(), ModelCatalogProbe.parseModels(""))
        assertEquals(emptyList<ModelCatalogEntry>(), ModelCatalogProbe.parseModels("<html>gateway</html>"))
        // 有 JSON 但没有列表字段：不猜字段名，直接当没拿到。
        assertEquals(emptyList<ModelCatalogEntry>(), ModelCatalogProbe.parseModels("""{"error":"unauthorized"}"""))
    }

    @Test
    fun keepsTheDefaultModelTableOrderAndRejectsUnknownProviders() {
        assertEquals(
            linkedMapOf(ModelProvider.DEEPSEEK to "deepseek-chat", ModelProvider.OPENAI to "gpt-4o"),
            RuntimeValidation.defaultModelIds(
                JSONObject().put("openai", "gpt-4o").put("deepseek", "deepseek-chat"),
            ),
        )
        assertEquals(emptyMap<ModelProvider, String>(), RuntimeValidation.defaultModelIds(null))
        assertTrue(
            runCatching { RuntimeValidation.defaultModelIds(JSONObject().put("not-a-provider", "gpt-4o")) }.isFailure,
        )
        assertTrue(
            runCatching { RuntimeValidation.defaultModelIds(JSONObject().put("openai", "has space")) }.isFailure,
        )
    }
}
