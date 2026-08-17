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
    ): RuntimeSettings {
        val source = source(url, digest)
        if (terminalFontSize !in 11..24) {
            throw RuntimeFailure("SETTINGS_INVALID", "终端字号必须在 11 到 24 之间")
        }
        return RuntimeSettings(
            source.manifestUrl?.toASCIIString().orEmpty(),
            source.manifestSha256.orEmpty(),
            keepScreenAwake,
            terminalFontSize,
        )
    }

    fun requireSha256(value: String): String {
        val normalized = value.lowercase(Locale.ROOT)
        if (!sha256Pattern.matches(normalized)) {
            throw RuntimeFailure("DIGEST_INVALID", "SHA-256 必须是 64 位十六进制")
        }
        return normalized
    }

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
