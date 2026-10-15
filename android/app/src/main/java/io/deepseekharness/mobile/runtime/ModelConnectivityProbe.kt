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

        /*
         * 不跟随重定向（`instanceFollowRedirects = false`）。
         *
         * `HttpURLConnection` 跟随重定向时会把本次请求头**原样重放**到新地址，而这里带着
         * `Authorization: Bearer <key>` / `x-api-key`：一个第三方或配置错的网关只要回一次
         * 302，就能把用户的密钥收到自己那儿去。探测请求的功能只是「问一句通不通」，
         * 因此宁可把重定向当成结果说明白，也不替用户把凭据送到第二个主机。
         * 真需要那个地址的用户，按返回的提示把 Base URL 直接写成重定向后的地址即可。
         */
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(requestUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
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
                in 300..399 -> ModelConnectivityProbeResult(
                    ok = false,
                    status = code,
                    latencyMs = latency,
                    message = redirectMessage(code, connection?.getHeaderField("Location"), latency),
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
        } finally {
            /*
             * 探测是「问一次就完事」的短请求：不读响应体，也就不会有人替我们关连接。
             * 不 disconnect 的话未关闭的流会挂在连接池里（Android 上表现为连接与句柄缓慢累积），
             * 在「反复点检测」这种最正常的用法下最容易堆起来。
             */
            runCatching { connection?.disconnect() }
        }
    }

    /** 3xx 只解释、不跟随：把「为什么没自动跳」说清楚，并给出用户能自己走的路。 */
    private fun redirectMessage(code: Int, location: String?, latencyMs: Long): String {
        val host = location
            ?.let { runCatching { URL(it).host }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val where = if (host != null) "重定向到 $host" else "响应重定向"
        return "$code $where：不会自动跟随（避免把 API Key 送到别的主机），" +
            "需要就走这个地址，请直接把 Base URL 填成它 (${latencyMs}ms)"
    }
}
