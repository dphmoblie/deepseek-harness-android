package io.deepseekharness.mobile.runtime

import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * 一条已经解析好的模型端点：请求地址与应当带上的鉴权头。
 *
 * 「连通性测试」与「获取模型列表」问的是同一个问题（这家供应商的模型端点在哪、怎么鉴权），
 * 因此共用这一份解析：两处各写一遍的话，任何一家新增供应商都可能只在其中一处生效，
 * 用户看到的就是「连接测试通过、取列表却 404」。
 */
internal class ModelEndpoint(
    val url: String,
    val headers: Map<String, String>,
) {
    fun applyTo(connection: HttpURLConnection) {
        headers.forEach { (header, value) -> connection.setRequestProperty(header, value) }
    }
}

/** 端点解析的两种结果：可用，或一句能直接展示给用户的原因。 */
internal sealed interface ModelEndpointResolution {
    data class Ready(val endpoint: ModelEndpoint) : ModelEndpointResolution

    /** [message] 已经是面向用户的中文提示，不再二次包装。 */
    data class Rejected(val message: String) : ModelEndpointResolution
}

internal object ModelEndpoints {
    fun resolve(
        providerId: String,
        baseUrl: String?,
        key: String,
        api: String?,
    ): ModelEndpointResolution {
        val builtIn = ModelProvider.entries.firstOrNull { it.wireValue == providerId }
        if (builtIn != null) {
            val (url, headers) = when (builtIn) {
                ModelProvider.DEEPSEEK -> "https://api.deepseek.com/models" to bearer(key)
                ModelProvider.OPENAI -> "https://api.openai.com/v1/models" to bearer(key)
                ModelProvider.ANTHROPIC -> "https://api.anthropic.com/v1/models" to anthropicHeaders(key)
                ModelProvider.GOOGLE -> googleModelsRequestUrl(key) to emptyMap()
                ModelProvider.OPENROUTER -> "https://openrouter.ai/api/v1/models" to bearer(key)
                ModelProvider.GROQ -> "https://api.groq.com/openai/v1/models" to bearer(key)
                ModelProvider.XAI -> "https://api.x.ai/v1/models" to bearer(key)
                ModelProvider.MISTRAL -> "https://api.mistral.ai/v1/models" to bearer(key)
            }
            return ModelEndpointResolution.Ready(ModelEndpoint(url, headers))
        }

        val rawBase = baseUrl?.trim()
        if (rawBase.isNullOrEmpty()) return ModelEndpointResolution.Rejected("Base URL 不能为空")
        val base = try {
            RuntimeValidation.requireProviderBaseUrl(rawBase)
        } catch (error: RuntimeFailure) {
            return ModelEndpointResolution.Rejected(error.message ?: "Base URL 格式无效")
        }
        val headers = when (runCatching { CustomProviderApi.parse(api.orEmpty()) }.getOrNull()) {
            CustomProviderApi.ANTHROPIC_MESSAGES -> anthropicHeaders(key)
            CustomProviderApi.OPENAI_COMPLETIONS,
            CustomProviderApi.OPENAI_RESPONSES -> bearer(key)
            null -> return ModelEndpointResolution.Rejected("自定义供应商 API 协议不支持")
        }
        return ModelEndpointResolution.Ready(ModelEndpoint("$base/models", headers))
    }

    /**
     * Gemini 的模型列表把密钥放在查询串里，且必须转义：密钥里可能出现 `#`、`&` 这类保留字符，
     * 原样拼进去会被当成片段或第二个参数，请求实际发到一个没有鉴权的地址上。
     */
    fun googleModelsRequestUrl(apiKey: String): String =
        "https://generativelanguage.googleapis.com/v1beta/models?key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}"

    private fun bearer(key: String): Map<String, String> = mapOf("Authorization" to "Bearer $key")

    private fun anthropicHeaders(key: String): Map<String, String> = mapOf(
        "x-api-key" to key,
        "anthropic-version" to "2023-06-01",
    )
}
