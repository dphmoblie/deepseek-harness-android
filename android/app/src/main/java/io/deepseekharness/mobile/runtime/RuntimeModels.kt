package io.deepseekharness.mobile.runtime

import android.os.Build
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

enum class RuntimePhase(val wireValue: String) {
    NOT_INSTALLED("not-installed"),
    PREPARING("preparing"),
    DOWNLOADING("downloading"),
    VERIFYING("verifying"),
    EXTRACTING("extracting"),
    READY("ready"),
    RUNNING("running"),
    STOPPING("stopping"),
    ERROR("error"),
}

enum class ModelProvider(val wireValue: String, val environmentVariable: String) {
    DEEPSEEK("deepseek", "DEEPSEEK_API_KEY"),
    OPENAI("openai", "OPENAI_API_KEY"),
    ANTHROPIC("anthropic", "ANTHROPIC_API_KEY"),
    GOOGLE("google", "GEMINI_API_KEY"),
    OPENROUTER("openrouter", "OPENROUTER_API_KEY"),
    GROQ("groq", "GROQ_API_KEY"),
    XAI("xai", "XAI_API_KEY"),
    MISTRAL("mistral", "MISTRAL_API_KEY"),
    ;

    companion object {
        fun parse(value: String): ModelProvider = entries.firstOrNull { it.wireValue == value }
            ?: throw RuntimeFailure("SETTINGS_INVALID", "模型供应商格式无效")
    }
}

enum class CustomProviderApi(val wireValue: String) {
    OPENAI_COMPLETIONS("openai-completions"),
    OPENAI_RESPONSES("openai-responses"),
    ANTHROPIC_MESSAGES("anthropic-messages"),
    ;

    companion object {
        fun parse(value: String): CustomProviderApi = entries.firstOrNull { it.wireValue == value }
            ?: throw RuntimeFailure("SETTINGS_INVALID", "自定义供应商 API 协议不支持")
    }
}

data class CustomProviderModel(
    val id: String,
    val name: String,
    val contextWindow: Int,
    val maxTokens: Int,
)

data class CustomModelProvider(
    val id: String,
    val name: String,
    val api: CustomProviderApi,
    val baseUrl: String,
    val models: List<CustomProviderModel>,
) {
    val environmentVariable: String
        get() = "DSH_CUSTOM_PROVIDER_${id.uppercase(Locale.ROOT).replace('-', '_')}_API_KEY"
}

data class RuntimeSource(
    val manifestUrl: URI?,
    val manifestSha256: String?,
) {
    val isBundled: Boolean get() = manifestUrl == null && manifestSha256 == null
}

data class RuntimeSettings(
    val manifestUrl: String,
    val manifestSha256: String,
    val keepScreenAwake: Boolean,
    val terminalFontSize: Int,
    val configuredModelProviders: Set<ModelProvider> = emptySet(),
    val customModelProviders: List<CustomModelProvider> = emptyList(),
    val configuredCustomModelProviders: Set<String> = emptySet(),
    val autoLaunch: Boolean = false,
    /**
     * 后台保持 Harness：运行时启动后启用前台服务，提升本应用进程的存活优先级。
     * 默认 false，旧版本配置没有该键时同样按 false 处理。
     * 该开关不能阻止 Android 或厂商系统杀死进程。
     */
    val keepRuntimeInBackground: Boolean = false,
)

data class RootfsArtifact(
    val url: URI,
    val sha256: String,
    val compressedBytes: Long,
    val extractedBytes: Long,
    val compression: RootfsCompression,
)

enum class RootfsCompression(val wireValue: String) {
    GZIP("gzip"),
    ;

    companion object {
        fun parse(value: String): RootfsCompression = entries.firstOrNull { it.wireValue == value }
            ?: throw RuntimeFailure("ARCHIVE_COMPRESSION_UNSUPPORTED", "运行时归档压缩格式不受支持")
    }
}

data class RuntimeManifest(
    val rawBytes: ByteArray,
    val runtimeId: String,
    val version: String,
    val architecture: String,
    val rootfs: RootfsArtifact,
    val shellArgv: List<String>,
    val harnessArgv: List<String>,
    val harnessUri: URI,
    val harnessPort: Int,
) {
    companion object {
        private val identifierPattern = Regex("^[A-Za-z0-9._-]{1,96}$")
        private val allowedShells = setOf(
            listOf("/bin/bash", "--login"),
            listOf("/bin/sh", "-l"),
        )

        fun parse(bytes: ByteArray, manifestHost: String? = null): RuntimeManifest {
            if (bytes.isEmpty() || bytes.size > RuntimeLimits.MAX_MANIFEST_BYTES) {
                throw RuntimeFailure("MANIFEST_SIZE_INVALID", "运行时清单大小无效")
            }

            val json = try {
                JSONObject(bytes.toString(Charsets.UTF_8))
            } catch (error: JSONException) {
                throw RuntimeFailure("MANIFEST_INVALID", "运行时清单格式无效", error)
            }

            val schemaVersion = json.opt("schemaVersion")
            if (schemaVersion !is Number || schemaVersion.toDouble() != 1.0) {
                throw RuntimeFailure("MANIFEST_SCHEMA_UNSUPPORTED", "不支持的运行时清单版本")
            }

            val runtimeId = requiredIdentifier(json, "runtimeId")
            val version = requiredIdentifier(json, "version")
            val architecture = requiredIdentifier(json, "architecture")
            if (architecture != "arm64-v8a" || !Build.SUPPORTED_ABIS.contains(architecture)) {
                throw RuntimeFailure("ARCHITECTURE_UNSUPPORTED", "运行时架构与设备不匹配")
            }

            val rootfsJson = requiredObject(json, "rootfs")
            val rootfsUri = RuntimeValidation.requireHttpsUri(requiredString(rootfsJson, "url"))
            if (manifestHost != null && !rootfsUri.host.equals(manifestHost, ignoreCase = true)) {
                throw RuntimeFailure("DOWNLOAD_HOST_NOT_ALLOWED", "运行时归档与清单必须使用同一下载主机")
            }
            val rootfs = RootfsArtifact(
                url = rootfsUri,
                sha256 = RuntimeValidation.requireSha256(requiredString(rootfsJson, "sha256")),
                compressedBytes = requiredLong(rootfsJson, "compressedBytes", 1, RuntimeLimits.MAX_COMPRESSED_BYTES),
                extractedBytes = requiredLong(rootfsJson, "extractedBytes", 1, RuntimeLimits.MAX_EXTRACTED_BYTES),
                compression = RootfsCompression.parse(rootfsJson.optString("compression", "gzip")),
            )

            val entrypoints = requiredObject(json, "entrypoints")
            val shellArgv = requiredArgv(entrypoints, "shell", 1, 4)
            if (shellArgv !in allowedShells) {
                throw RuntimeFailure("ENTRYPOINT_NOT_ALLOWED", "Ubuntu Shell 入口不在允许列表中")
            }

            val harnessArgv = requiredArgv(entrypoints, "harness", 6, 6)
            if (
                harnessArgv[0] != "/usr/local/bin/dsh" ||
                harnessArgv[1] != "web" ||
                harnessArgv[2] != "--host" ||
                harnessArgv[3] != "127.0.0.1" ||
                harnessArgv[4] != "--port"
            ) {
                throw RuntimeFailure("ENTRYPOINT_NOT_ALLOWED", "Harness 入口不在允许列表中")
            }
            val port = harnessArgv[5].toIntOrNull()
            if (port == null || port !in 1024..65535 || harnessArgv[5] != port.toString()) {
                throw RuntimeFailure("ENTRYPOINT_NOT_ALLOWED", "Harness 端口无效")
            }

            val harnessUri = validateHarnessUri(requiredString(json, "harnessUrl"), port)
            return RuntimeManifest(
                rawBytes = bytes.copyOf(),
                runtimeId = runtimeId,
                version = version,
                architecture = architecture,
                rootfs = rootfs,
                shellArgv = shellArgv,
                harnessArgv = harnessArgv,
                harnessUri = harnessUri,
                harnessPort = port,
            )
        }

        private fun requiredObject(parent: JSONObject, key: String): JSONObject = try {
            parent.getJSONObject(key)
        } catch (error: JSONException) {
            throw RuntimeFailure("MANIFEST_INVALID", "运行时清单缺少必要对象", error)
        }

        private fun requiredString(parent: JSONObject, key: String): String = try {
            parent.getString(key)
        } catch (error: JSONException) {
            throw RuntimeFailure("MANIFEST_INVALID", "运行时清单缺少必要字段", error)
        }.also {
            if (it.isEmpty() || it.length > RuntimeLimits.MAX_FIELD_CHARS || it.any(Char::isISOControl)) {
                throw RuntimeFailure("MANIFEST_INVALID", "运行时清单字段长度或字符无效")
            }
        }

        private fun requiredIdentifier(parent: JSONObject, key: String): String =
            requiredString(parent, key).also {
                if (!identifierPattern.matches(it)) {
                    throw RuntimeFailure("MANIFEST_INVALID", "运行时清单标识格式无效")
                }
            }

        private fun requiredLong(parent: JSONObject, key: String, minimum: Long, maximum: Long): Long {
            val raw = parent.opt(key)
            if (raw !is Number || !raw.toDouble().isFinite()) {
                throw RuntimeFailure("MANIFEST_INVALID", "运行时清单尺寸字段无效")
            }
            val value = raw.toLong()
            if (raw.toDouble() != value.toDouble()) throw RuntimeFailure("MANIFEST_INVALID", "运行时清单尺寸必须是整数")
            if (value !in minimum..maximum) {
                throw RuntimeFailure("MANIFEST_SIZE_INVALID", "运行时清单尺寸超过限制")
            }
            return value
        }

        private fun requiredArgv(parent: JSONObject, key: String, minimum: Int, maximum: Int): List<String> {
            val array: JSONArray = try {
                parent.getJSONArray(key)
            } catch (error: JSONException) {
                throw RuntimeFailure("MANIFEST_INVALID", "运行时入口格式无效", error)
            }
            if (array.length() !in minimum..maximum) {
                throw RuntimeFailure("ENTRYPOINT_NOT_ALLOWED", "运行时入口参数数量无效")
            }
            return (0 until array.length()).map { index ->
                val argument = try {
                    array.getString(index)
                } catch (error: JSONException) {
                    throw RuntimeFailure("ENTRYPOINT_NOT_ALLOWED", "运行时入口参数类型无效", error)
                }
                if (argument.isEmpty() || argument.length > 256 || argument.any { it == '\u0000' || it == '\r' || it == '\n' }) {
                    throw RuntimeFailure("ENTRYPOINT_NOT_ALLOWED", "运行时入口参数内容无效")
                }
                argument
            }
        }

        private fun validateHarnessUri(raw: String, expectedPort: Int): URI {
            val uri = try {
                URI(raw)
            } catch (error: URISyntaxException) {
                throw RuntimeFailure("HARNESS_URL_INVALID", "Harness 地址格式无效", error)
            }
            if (
                uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != expectedPort ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                (uri.rawPath != null && uri.rawPath != "" && uri.rawPath != "/")
            ) {
                throw RuntimeFailure("HARNESS_URL_INVALID", "Harness 必须使用固定回环地址和端口")
            }
            return URI("http", null, "127.0.0.1", expectedPort, "/", null, null)
        }
    }
}

object RuntimeLimits {
    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MAX_FIELD_CHARS = 2048
    const val MAX_COMPRESSED_BYTES = 1_610_612_736L
    const val MAX_EXTRACTED_BYTES = 6_442_450_944L
    const val MAX_ARCHIVE_ENTRIES = 250_000
    const val MAX_ARCHIVE_PATH_CHARS = 4096
    const val MAX_ARCHIVE_COMPONENT_CHARS = 255
    const val MAX_TERMINAL_INPUT_BYTES = 256 * 1024
}

object RuntimeValidation {
    private val sha256Pattern = Regex("^[a-f0-9]{64}$")
    private val customProviderIdPattern = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
    private val customModelIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,199}$")
    private val reservedProviderIds = ModelProvider.entries.mapTo(mutableSetOf()) { it.wireValue }
        .apply { addAll(listOf("constructor", "prototype", "__proto__")) }

    fun source(url: String?, digest: String?): RuntimeSource {
        val normalizedUrl = url?.trim().orEmpty()
        val normalizedDigest = digest?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalizedUrl.isEmpty() && normalizedDigest.isEmpty()) {
            return RuntimeSource(null, null)
        }
        if (normalizedUrl.isEmpty() || normalizedDigest.isEmpty()) {
            throw RuntimeFailure("SOURCE_INCOMPLETE", "运行时清单地址与 SHA-256 必须同时填写或同时留空")
        }
        if (normalizedUrl.length > RuntimeLimits.MAX_FIELD_CHARS) {
            throw RuntimeFailure("URL_INVALID", "运行时清单地址长度无效")
        }
        return RuntimeSource(requireHttpsUri(normalizedUrl, rejectPrivateHost = true), requireSha256(normalizedDigest))
    }

    fun settings(
        url: String?,
        digest: String?,
        keepScreenAwake: Boolean,
        terminalFontSize: Int,
        autoLaunch: Boolean = true,
        keepRuntimeInBackground: Boolean = false,
    ): RuntimeSettings {
        val source = source(url, digest)
        if (terminalFontSize !in 11..24) {
            throw RuntimeFailure("SETTINGS_INVALID", "终端字号必须在 11 到 24 之间")
        }
        return RuntimeSettings(
            manifestUrl = source.manifestUrl?.toASCIIString().orEmpty(),
            manifestSha256 = source.manifestSha256.orEmpty(),
            keepScreenAwake = keepScreenAwake,
            terminalFontSize = terminalFontSize,
            autoLaunch = autoLaunch,
            keepRuntimeInBackground = keepRuntimeInBackground,
        )
    }

    fun providerApiKeyUpdates(value: JSONObject?): Map<ModelProvider, String> {
        if (value == null) return emptyMap()
        if (value.length() > ModelProvider.entries.size) {
            throw RuntimeFailure("SETTINGS_INVALID", "模型凭据更新数量无效")
        }
        val result = linkedMapOf<ModelProvider, String>()
        val names = value.keys()
        while (names.hasNext()) {
            val name = names.next()
            val provider = ModelProvider.parse(name)
            val rawKey = value.opt(name)
            if (rawKey !is String) throw RuntimeFailure("SETTINGS_INVALID", "模型凭据必须是字符串")
            result[provider] = requireProviderApiKey(rawKey)
        }
        return result
    }

    fun clearedProviderApiKeys(value: JSONArray?): Set<ModelProvider> {
        if (value == null) return emptySet()
        if (value.length() > ModelProvider.entries.size) {
            throw RuntimeFailure("SETTINGS_INVALID", "模型凭据清除列表格式无效")
        }
        val result = linkedSetOf<ModelProvider>()
        for (index in 0 until value.length()) {
            val rawProvider = value.opt(index)
            if (rawProvider !is String) throw RuntimeFailure("SETTINGS_INVALID", "模型供应商格式无效")
            if (!result.add(ModelProvider.parse(rawProvider))) {
                throw RuntimeFailure("SETTINGS_INVALID", "模型凭据清除列表包含重复供应商")
            }
        }
        return result
    }

    fun requireProviderApiKey(value: String): String {
        val normalized = value.trim()
        if (!providerApiKeyPattern.matches(normalized)) {
            throw RuntimeFailure("SETTINGS_INVALID", "模型凭据包含非法字符或长度无效")
        }
        return normalized
    }

    fun customModelProviders(value: JSONArray?): List<CustomModelProvider> {
        if (value == null) return emptyList()
        if (value.length() > MAX_CUSTOM_PROVIDERS) throw RuntimeFailure("SETTINGS_INVALID", "最多可配置 16 个自定义供应商")
        val ids = linkedSetOf<String>()
        return (0 until value.length()).map { index ->
            val item = value.optJSONObject(index)
                ?: throw RuntimeFailure("SETTINGS_INVALID", "自定义供应商格式无效")
            val id = requireCustomProviderId(item.opt("id"))
            if (!ids.add(id)) throw RuntimeFailure("SETTINGS_INVALID", "自定义供应商标识不能重复")
            val modelsJson = item.optJSONArray("models")
                ?: throw RuntimeFailure("SETTINGS_INVALID", "自定义模型列表格式无效")
            if (modelsJson.length() !in 1..MAX_CUSTOM_MODELS) {
                throw RuntimeFailure("SETTINGS_INVALID", "每个自定义供应商需要 1 到 32 个模型")
            }
            val modelIds = linkedSetOf<String>()
            val models = (0 until modelsJson.length()).map { modelIndex ->
                val model = modelsJson.optJSONObject(modelIndex)
                    ?: throw RuntimeFailure("SETTINGS_INVALID", "自定义模型格式无效")
                val modelId = model.opt("id") as? String
                if (modelId == null || !customModelIdPattern.matches(modelId) || !modelIds.add(modelId)) {
                    throw RuntimeFailure("SETTINGS_INVALID", "自定义模型 ID 格式无效或重复")
                }
                val contextWindow = requireTokenCount(model.opt("contextWindow"), "模型上下文长度")
                val maxTokens = requireTokenCount(model.opt("maxTokens"), "模型最大输出长度")
                if (maxTokens > contextWindow) throw RuntimeFailure("SETTINGS_INVALID", "模型最大输出长度不能超过上下文长度")
                CustomProviderModel(modelId, requireDisplayName(model.opt("name"), 100, "模型名称"), contextWindow, maxTokens)
            }
            CustomModelProvider(
                id,
                requireDisplayName(item.opt("name"), 80, "自定义供应商名称"),
                CustomProviderApi.parse(item.optString("api")),
                requireProviderBaseUrl(item.opt("baseUrl")),
                models,
            )
        }
    }

    fun customProviderApiKeyUpdates(value: JSONObject?, allowedIds: Set<String>): Map<String, String> {
        if (value == null) return emptyMap()
        if (value.length() > MAX_CUSTOM_PROVIDERS) throw RuntimeFailure("SETTINGS_INVALID", "自定义模型凭据更新数量无效")
        val result = linkedMapOf<String, String>()
        val names = value.keys()
        while (names.hasNext()) {
            val id = requireCustomProviderId(names.next())
            if (id !in allowedIds) throw RuntimeFailure("SETTINGS_INVALID", "凭据更新包含不存在的自定义供应商")
            val rawKey = value.opt(id) as? String ?: throw RuntimeFailure("SETTINGS_INVALID", "自定义模型凭据必须是字符串")
            result[id] = requireProviderApiKey(rawKey)
        }
        return result
    }

    fun clearedCustomProviderApiKeys(value: JSONArray?, allowedIds: Set<String>): Set<String> {
        if (value == null) return emptySet()
        if (value.length() > MAX_CUSTOM_PROVIDERS) throw RuntimeFailure("SETTINGS_INVALID", "自定义模型凭据清除列表格式无效")
        val result = linkedSetOf<String>()
        for (index in 0 until value.length()) {
            val id = requireCustomProviderId(value.opt(index))
            if (id !in allowedIds || !result.add(id)) throw RuntimeFailure("SETTINGS_INVALID", "自定义模型凭据清除列表无效")
        }
        return result
    }

    private fun requireCustomProviderId(value: Any?): String {
        val id = value as? String
        if (id == null || id.length > 48 || !customProviderIdPattern.matches(id) || id in reservedProviderIds) {
            throw RuntimeFailure("SETTINGS_INVALID", "自定义供应商标识格式无效或与内置供应商重复")
        }
        return id
    }

    private fun requireDisplayName(value: Any?, maximum: Int, label: String): String {
        val normalized = (value as? String)?.trim().orEmpty()
        if (normalized.isEmpty() || normalized.length > maximum || normalized.any { it.isISOControl() || it == '<' || it == '>' }) {
            throw RuntimeFailure("SETTINGS_INVALID", "$label 包含非法字符或长度无效")
        }
        return normalized
    }

    private fun requireTokenCount(value: Any?, label: String): Int {
        val number = value as? Number ?: throw RuntimeFailure("SETTINGS_INVALID", "$label 格式无效")
        val integer = number.toInt()
        if (number.toDouble() != integer.toDouble() || integer !in 1..10_000_000) {
            throw RuntimeFailure("SETTINGS_INVALID", "$label 必须为 1 到 10000000 之间的整数")
        }
        return integer
    }

    private fun requireProviderBaseUrl(value: Any?): String {
        val raw = (value as? String)?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > RuntimeLimits.MAX_FIELD_CHARS || raw.any { it.isISOControl() || it == '\\' }) {
            throw RuntimeFailure("SETTINGS_INVALID", "自定义供应商 Base URL 包含非法字符或长度无效")
        }
        val uri = try { URI(raw).normalize() } catch (error: URISyntaxException) {
            throw RuntimeFailure("SETTINGS_INVALID", "自定义供应商 Base URL 格式无效", error)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if ((scheme != "https" && !(scheme == "http" && loopback)) || host.isNullOrEmpty() ||
            uri.port !in -1..65535 || uri.port == 0 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
        ) throw RuntimeFailure("SETTINGS_INVALID", "Base URL 必须使用 HTTPS（本机回环可用 HTTP），且不能包含凭据、查询参数或片段")
        return uri.toASCIIString().removeSuffix("/")
    }

    private const val MAX_CUSTOM_PROVIDERS = 16
    private const val MAX_CUSTOM_MODELS = 32

    fun requireSha256(value: String): String {
        val normalized = value.lowercase(Locale.ROOT)
        if (!sha256Pattern.matches(normalized)) {
            throw RuntimeFailure("DIGEST_INVALID", "SHA-256 必须是 64 位十六进制")
        }
        return normalized
    }

    private val providerApiKeyPattern = Regex("^[\\x21-\\x7E]{1,200}$")

    fun requireHttpsUri(value: String, rejectPrivateHost: Boolean = false): URI {
        if (value.isEmpty() || value.length > RuntimeLimits.MAX_FIELD_CHARS || value.any(Char::isISOControl)) {
            throw RuntimeFailure("URL_INVALID", "下载地址长度或字符无效")
        }
        val uri = try {
            URI(value).normalize()
        } catch (error: URISyntaxException) {
            throw RuntimeFailure("URL_INVALID", "下载地址格式无效", error)
        }
        val host = uri.host
        if (
            !uri.scheme.equals("https", ignoreCase = true) || host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawFragment != null || (uri.port != -1 && uri.port !in 1..65535)
        ) {
            throw RuntimeFailure("URL_INVALID", "下载地址必须是不含凭据和片段的 HTTPS 地址")
        }
        val asciiHost = try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (error: IllegalArgumentException) {
            throw RuntimeFailure("URL_INVALID", "下载主机名无效", error)
        }
        if (asciiHost.length > 253 || (rejectPrivateHost && isPrivateOrLocal(asciiHost))) {
            throw RuntimeFailure("URL_HOST_NOT_ALLOWED", "下载地址不能指向本机、私网或链路本地地址")
        }
        return uri
    }

    fun requirePublicDestination(host: String) {
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (error: Exception) {
            throw RuntimeFailure("DOWNLOAD_HOST_UNRESOLVED", "无法解析下载主机", error)
        }
        requirePublicAddresses(addresses.asList())
    }

    fun requirePublicAddresses(addresses: List<InetAddress>) {
        if (addresses.isEmpty() || addresses.any { isPrivateOrLocal(it) }) {
            throw RuntimeFailure("URL_HOST_NOT_ALLOWED", "下载主机解析到了本机、私网或链路本地地址")
        }
    }

    private fun isPrivateOrLocal(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        val literal = parseIpLiteral(host) ?: return false
        return isPrivateOrLocal(literal)
    }

    private fun isPrivateOrLocal(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        if (address is Inet6Address) return (address.address[0].toInt() and 0xfe) == 0xfc
        if (address is Inet4Address) {
            val octets = address.address.map { it.toInt() and 0xff }
            return (octets[0] == 100 && octets[1] in 64..127) ||
                (octets[0] == 198 && octets[1] in 18..19) || octets[0] >= 224
        }
        return true
    }

    private fun parseIpLiteral(host: String): InetAddress? {
        val looksIpv4 = host.all { it.isDigit() || it == '.' } && host.contains('.')
        val looksIpv6 = host.contains(':')
        if (!looksIpv4 && !looksIpv6) return null
        return try {
            val parsed = InetAddress.getByName(host)
            if (looksIpv4 && parsed !is Inet4Address) null else parsed
        } catch (_: Exception) {
            null
        }
    }
}
