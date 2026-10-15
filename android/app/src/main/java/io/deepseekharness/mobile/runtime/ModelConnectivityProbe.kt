package io.deepseekharness.mobile.runtime

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class ModelConnectivityProbeResult(
    val ok: Boolean,
    val status: Int,
    val latencyMs: Long,
    val message: String,
)

object ModelConnectivityProbe {
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    fun probe(
        providerId: String,
        baseUrl: String?,
        apiKey: String?,
        api: String?,
        savedApiKey: String?,
    ): ModelConnectivityProbeResult {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: savedApiKey?.trim()?.takeIf { it.isNotEmpty() }

        if (key.isNullOrEmpty()) {
            return ModelConnectivityProbeResult(
                ok = false,
                status = 0,
                latencyMs = 0L,
                message = "未提供 API Key 且本机未保存该供应商凭据",
            )
        }

        val requestUrl: String
        val headers = mutableMapOf<String, String>()

        val builtIn = ModelProvider.entries.firstOrNull { it.wireValue == providerId }
        if (builtIn != null) {
            when (builtIn) {
                ModelProvider.DEEPSEEK -> {
                    requestUrl = "https://api.deepseek.com/models"
                    headers["Authorization"] = "Bearer $key"
                }
                ModelProvider.OPENAI -> {
                    requestUrl = "https://api.openai.com/v1/models"
                    headers["Authorization"] = "Bearer $key"
                }
                ModelProvider.ANTHROPIC -> {
                    requestUrl = "https://api.anthropic.com/v1/models"
                    headers["x-api-key"] = key
                    headers["anthropic-version"] = "2023-06-01"
                }
                ModelProvider.GOOGLE -> {
                    requestUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
                }
                ModelProvider.OPENROUTER -> {
                    requestUrl = "https://openrouter.ai/api/v1/models"
                    headers["Authorization"] = "Bearer $key"
                }
                ModelProvider.GROQ -> {
                    requestUrl = "https://api.groq.com/openai/v1/models"
                    headers["Authorization"] = "Bearer $key"
                }
                ModelProvider.XAI -> {
                    requestUrl = "https://api.x.ai/v1/models"
                    headers["Authorization"] = "Bearer $key"
                }
                ModelProvider.MISTRAL -> {
                    requestUrl = "https://api.mistral.ai/v1/models"
                    headers["Authorization"] = "Bearer $key"
                }
            }
        } else {
            val base = baseUrl?.trim()?.removeSuffix("/")
            if (base.isNullOrEmpty()) {
                return ModelConnectivityProbeResult(
                    ok = false,
                    status = 0,
                    latencyMs = 0L,
                    message = "Base URL 不能为空",
                )
            }
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                return ModelConnectivityProbeResult(
                    ok = false,
                    status = 0,
                    latencyMs = 0L,
                    message = "Base URL 协议无效，须以 http:// 或 https:// 开头",
                )
            }
            if (api == "anthropic-messages") {
                requestUrl = "$base/models"
                headers["x-api-key"] = key
                headers["anthropic-version"] = "2023-06-01"
            } else {
                requestUrl = "$base/models"
                headers["Authorization"] = "Bearer $key"
            }
        }

        return try {
            val url = URL(requestUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                headers.forEach { (header, value) -> setRequestProperty(header, value) }
            }

            val start = System.currentTimeMillis()
            connection.connect()
            val code = connection.responseCode
            val latency = System.currentTimeMillis() - start

            when (code) {
                in 200..299 -> ModelConnectivityProbeResult(
                    ok = true,
                    status = code,
                    latencyMs = latency,
                    message = "✓ 连接成功 (${latency}ms)",
                )
                401 -> ModelConnectivityProbeResult(
                    ok = false,
                    status = 401,
                    latencyMs = latency,
                    message = "401 密钥无效或未授权 (${latency}ms)",
                )
                403 -> ModelConnectivityProbeResult(
                    ok = false,
                    status = 403,
                    latencyMs = latency,
                    message = "403 访问被拒绝或无权限 (${latency}ms)",
                )
                404 -> ModelConnectivityProbeResult(
                    ok = false,
                    status = 404,
                    latencyMs = latency,
                    message = "404 目标端点不存在 (${latency}ms)",
                )
                429 -> ModelConnectivityProbeResult(
                    ok = false,
                    status = 429,
                    latencyMs = latency,
                    message = "429 请求频率超限或配额耗尽 (${latency}ms)",
                )
                else -> ModelConnectivityProbeResult(
                    ok = false,
                    status = code,
                    latencyMs = latency,
                    message = "HTTP $code (${latency}ms)",
                )
            }
        } catch (_: SocketTimeoutException) {
            ModelConnectivityProbeResult(
                ok = false,
                status = 0,
                latencyMs = CONNECT_TIMEOUT_MS.toLong(),
                message = "网络连接超时 (${CONNECT_TIMEOUT_MS}ms)",
            )
        } catch (_: UnknownHostException) {
            ModelConnectivityProbeResult(
                ok = false,
                status = 0,
                latencyMs = 0L,
                message = "无法解析主机名，请检查网络或域名",
            )
        } catch (_: SSLException) {
            ModelConnectivityProbeResult(
                ok = false,
                status = 0,
                latencyMs = 0L,
                message = "SSL/TLS 证书校验失败",
            )
        } catch (e: IOException) {
            ModelConnectivityProbeResult(
                ok = false,
                status = 0,
                latencyMs = 0L,
                message = "网络连接失败: ${e.message ?: "未知网络错误"}",
            )
        } catch (e: Throwable) {
            ModelConnectivityProbeResult(
                ok = false,
                status = 0,
                latencyMs = 0L,
                message = "检测失败: ${e.message ?: "未知异常"}",
            )
        }
    }
}
