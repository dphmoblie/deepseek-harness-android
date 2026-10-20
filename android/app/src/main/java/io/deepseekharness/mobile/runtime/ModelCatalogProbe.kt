package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 上游模型列表里的一项。
 *
 * 只带 id 与显示名：上下文长度、最大输出这类数字各家口径不一（有的按 token，有的按字符，
 * 有的干脆不返回），拿不到就由用户在原表单里填默认值，不在这里猜。
 */
data class ModelCatalogEntry(
    val id: String,
    val name: String,
)

data class ModelCatalogProbeResult(
    val ok: Boolean,
    val status: Int,
    val latencyMs: Long,
    val message: String,
    val models: List<ModelCatalogEntry>,
)

/**
 * 读取供应商的模型列表（GET {base}/models）。
 *
 * 端点与鉴权解析与连通性测试共用 [ModelEndpoints]：用户点「获取模型」时看到的失败原因，
 * 与点「测试连接」时应一致。这里**读响应体**，但仍沿用「不跟随重定向」的策略——
 * 带着用户密钥的 GET 不能被一次 302 送到第二个主机去。
 */
object ModelCatalogProbe {
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000
    /** 响应体上限：模型清单通常几十 KB，封顶防止被一个异常端点拖着读。 */
    private const val MAX_BODY_CHARS = 512 * 1024
    private const val MAX_ENTRIES = 500
    private const val MAX_ID_CHARS = 200
    private const val MAX_NAME_CHARS = 100

    /**
     * 明显不是对话模型的 id 片段。
     *
     * 「自动选最靠前一个」的前提是那份列表里第一项真能拿来对话：OpenAI 的 /models 会把
     * 向量化、语音、图像、审核模型混在同一个列表里，按返回顺序盲选很可能选中
     * `text-embedding-3-small` 这种根本不能聊的条目。这里只是把「明显不是对话模型」的
     * 条目从候选里去掉，顺序仍与上游一致；过滤后为空就原样返回，不做二次猜测。
     */
    private val NON_CHAT_HINTS = listOf(
        "embedding",
        "embed-",
        "whisper",
        "tts",
        "dall-e",
        "moderation",
        "rerank",
        "guard",
        "aqa",
        "imagen",
        "veo",
    )

    fun list(
        providerId: String,
        baseUrl: String?,
        apiKey: String?,
        api: String?,
        savedApiKey: String?,
    ): ModelCatalogProbeResult {
        val rawKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: savedApiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (rawKey.isNullOrEmpty()) {
            return rejected("未提供 API Key 且本机未保存该供应商凭据")
        }
        val key = try {
            RuntimeValidation.requireProviderApiKey(rawKey)
        } catch (error: RuntimeFailure) {
            return rejected(error.message ?: "模型凭据格式无效")
        }
        val endpoint = when (val resolution = ModelEndpoints.resolve(providerId, baseUrl, key, api)) {
            is ModelEndpointResolution.Rejected -> return rejected(resolution.message)
            is ModelEndpointResolution.Ready -> resolution.endpoint
        }

        var connection: HttpURLConnection? = null
        return try {
            val http = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                endpoint.applyTo(this)
            }
            connection = http
            val start = System.currentTimeMillis()
            http.connect()
            val code = http.responseCode
            val latency = System.currentTimeMillis() - start
            when {
                code == 401 -> failed(code, latency, "401 密钥无效或未授权 (${latency}ms)")
                code == 403 -> failed(code, latency, "403 访问被拒绝或无权限 (${latency}ms)")
                code == 404 -> failed(code, latency, "404 目标端点不存在 (${latency}ms)")
                code == 429 -> failed(code, latency, "429 请求频率超限或配额耗尽 (${latency}ms)")
                code in 300..399 -> failed(
                    code,
                    latency,
                    ModelConnectivityProbe.redirectMessage(code, http.getHeaderField("Location"), latency),
                )
                code !in 200..299 -> failed(code, latency, "HTTP $code (${latency}ms)")
                else -> {
                    val body = readBody(http)
                    val models = parseModels(body)
                    if (models.isEmpty()) {
                        ModelCatalogProbeResult(
                            ok = false,
                            status = code,
                            latencyMs = latency,
                            message = "连接成功，但响应里没有可识别的模型列表 (${latency}ms)",
                            models = emptyList(),
                        )
                    } else {
                        ModelCatalogProbeResult(
                            ok = true,
                            status = code,
                            latencyMs = latency,
                            message = "已获取 ${models.size} 个模型 (${latency}ms)",
                            models = models,
                        )
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            rejected("网络连接超时 (${CONNECT_TIMEOUT_MS}ms)")
        } catch (_: UnknownHostException) {
            rejected("无法解析主机名，请检查网络或域名")
        } catch (_: SSLException) {
            rejected("SSL/TLS 证书校验失败")
        } catch (e: IOException) {
            rejected("网络连接失败: ${e.message ?: "未知网络错误"}")
        } catch (e: Throwable) {
            rejected("获取模型列表失败: ${e.message ?: "未知异常"}")
        } finally {
            // 读完了响应体也要 disconnect：连接池不回收，反复点「获取模型」会累积句柄。
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 解析各家模型列表的响应。
     *
     * 兼容三种最常见形状：
     * - `{"data":[{id, ...}]}`：OpenAI 系（DeepSeek、OpenAI、Groq、xAI、Mistral、OpenRouter）
     * - `{"models":[{name, displayName}]}`：Gemini
     * - 顶层数组：部分自建网关
     *
     * 解析的是**结构**而不是某家的固定字段：id 取 `id`/`name`（Gemini 的 `name` 形如
     * `models/gemini-2.5-pro`，去掉前缀才是调用时使用的 id），显示名取 `display_name` /
     * `displayName`，都没有时用 id。
     */
    internal fun parseModels(body: String): List<ModelCatalogEntry> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = runCatching {
            when (trimmed.first()) {
                '[' -> JSONArray(trimmed)
                '{' -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("data")
                        ?: root.optJSONArray("models")
                        ?: root.optJSONArray("result")
                        ?: JSONArray()
                }
                else -> JSONArray()
            }
        }.getOrNull() ?: return emptyList()

        val seen = linkedSetOf<String>()
        val chat: MutableList<ModelCatalogEntry> = mutableListOf()
        val other: MutableList<ModelCatalogEntry> = mutableListOf()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val rawId = (item.optString("id").takeIf { it.isNotBlank() }
                ?: item.optString("name").takeIf { it.isNotBlank() }
                ?: item.optString("model").takeIf { it.isNotBlank() }
                ?: continue).trim().removePrefix("models/")
            if (rawId.isEmpty() || rawId.length > MAX_ID_CHARS) continue
            if (!MODEL_ID_PATTERN.matches(rawId)) continue
            if (!seen.add(rawId)) continue
            val rawName = item.optString("display_name").takeIf { it.isNotBlank() }
                ?: item.optString("displayName").takeIf { it.isNotBlank() }
                ?: item.optString("name").takeIf { it.isNotBlank() && it != rawId && !it.startsWith("models/") }
                ?: rawId
            val name = rawName.trim().take(MAX_NAME_CHARS).ifEmpty { rawId }
            val entry = ModelCatalogEntry(rawId, name)
            if (isNonChat(rawId)) other.add(entry) else chat.add(entry)
            if (chat.size >= MAX_ENTRIES) break
        }
        val models = chat.ifEmpty { other }.take(MAX_ENTRIES)
        return models
    }

    private fun isNonChat(id: String): Boolean {
        val normalized = id.lowercase()
        return NON_CHAT_HINTS.any(normalized::contains)
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = connection.inputStream ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val builder = StringBuilder()
            val buffer = CharArray(8 * 1024)
            while (builder.length < MAX_BODY_CHARS) {
                val read = reader.read(buffer)
                if (read <= 0) break
                builder.append(buffer, 0, read)
            }
            builder.toString()
        }
    }

    /** 与 `RuntimeValidation.customModelIdPattern` 同一套规则，原生侧先挡一次再交给前端。 */
    private val MODEL_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,199}$")

    private fun rejected(message: String) = ModelCatalogProbeResult(
        ok = false,
        status = 0,
        latencyMs = 0L,
        message = message,
        models = emptyList(),
    )

    private fun failed(status: Int, latencyMs: Long, message: String) = ModelCatalogProbeResult(
        ok = false,
        status = status,
        latencyMs = latencyMs,
        message = message,
        models = emptyList(),
    )
}
