package io.deepseekharness.mobile.runtime

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class RuntimeHttp {
    data class DownloadResult(val finalUri: URI, val bytes: Long, val sha256: String)

    private val client = OkHttpClient.Builder()
        .dns(PublicOnlyDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun cancelAll() {
        client.dispatcher.cancelAll()
    }

    fun downloadBytes(source: URI, expectedSha256: String, maximumBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
        val result = withResponse(source, source.host) { response, input ->
            enforceContentLength(response, maximumBytes.toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            var count = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read.toLong()
                if (count > maximumBytes) throw RuntimeFailure("DOWNLOAD_TOO_LARGE", "清单下载超过大小限制")
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
            DownloadResult(URI(response.request.url.toString()), count, digest.hex())
        }
        if (!constantTimeDigestEquals(result.sha256, expectedSha256)) {
            throw RuntimeFailure("MANIFEST_DIGEST_MISMATCH", "运行时清单摘要校验失败")
        }
        return output.toByteArray()
    }

    fun downloadFile(
        source: URI,
        destination: File,
        expectedBytes: Long,
        expectedSha256: String,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadResult {
        if (expectedBytes !in 1..RuntimeLimits.MAX_COMPRESSED_BYTES) {
            throw RuntimeFailure("MANIFEST_SIZE_INVALID", "运行时归档大小无效")
        }
        val result = try {
            withResponse(source, source.host) { response, input ->
                enforceContentLength(response, expectedBytes)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                FileChannel.open(
                    destination.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    Channels.newOutputStream(channel).buffered().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read.toLong()
                            if (count > expectedBytes || count > RuntimeLimits.MAX_COMPRESSED_BYTES) {
                                throw RuntimeFailure("DOWNLOAD_SIZE_MISMATCH", "运行时归档超过声明大小")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            progress(count, expectedBytes)
                        }
                        output.flush()
                        channel.force(true)
                    }
                }
                DownloadResult(URI(response.request.url.toString()), count, digest.hex())
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        if (result.bytes != expectedBytes) {
            destination.delete()
            throw RuntimeFailure("DOWNLOAD_SIZE_MISMATCH", "运行时归档大小与清单不一致")
        }
        if (!constantTimeDigestEquals(result.sha256, expectedSha256)) {
            destination.delete()
            throw RuntimeFailure("ROOTFS_DIGEST_MISMATCH", "运行时归档摘要校验失败")
        }
        return result
    }

    private fun <T> withResponse(source: URI, allowedHost: String, consume: (Response, InputStream) -> T): T {
        var current = source
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            RuntimeValidation.requireHttpsUri(current.toASCIIString())
            if (!current.host.equals(allowedHost, ignoreCase = true)) {
                throw RuntimeFailure("DOWNLOAD_HOST_NOT_ALLOWED", "下载重定向离开了允许的主机")
            }
            val request = try {
                Request.Builder()
                    .url(current.toASCIIString())
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "DeepSeekHarnessMobile/0.1")
                    .get()
                    .build()
            } catch (error: IllegalArgumentException) {
                throw RuntimeFailure("URL_INVALID", "下载地址格式无效", error)
            }
            client.newCall(request).execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    if (redirectCount == MAX_REDIRECTS) {
                        throw RuntimeFailure("DOWNLOAD_REDIRECT_LIMIT", "下载重定向次数过多")
                    }
                    val location = response.header("Location")
                        ?: throw RuntimeFailure("DOWNLOAD_FAILED", "下载重定向缺少目标地址")
                    current = current.resolve(location)
                    RuntimeValidation.requireHttpsUri(current.toASCIIString())
                    return@repeat
                }
                if (!response.isSuccessful) {
                    throw RuntimeFailure("DOWNLOAD_HTTP_ERROR", "下载服务返回 HTTP ${response.code}")
                }
                val body = response.body ?: throw RuntimeFailure("DOWNLOAD_FAILED", "下载响应不包含内容")
                body.byteStream().use { input -> return consume(response, input) }
            }
        }
        throw RuntimeFailure("DOWNLOAD_REDIRECT_LIMIT", "下载重定向次数过多")
    }

    private fun enforceContentLength(response: Response, maximumBytes: Long) {
        val length = response.body?.contentLength() ?: -1L
        if (length > maximumBytes) {
            throw RuntimeFailure("DOWNLOAD_TOO_LARGE", "下载内容超过大小限制")
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private fun constantTimeDigestEquals(actualHex: String, expectedHex: String): Boolean {
        val actual = actualHex.toByteArray(Charsets.US_ASCII)
        val expected = expectedHex.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(actual, expected)
    }

    private object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (error: Exception) {
                throw RuntimeFailure("DOWNLOAD_HOST_UNRESOLVED", "无法解析下载主机", error)
            }
            RuntimeValidation.requirePublicAddresses(addresses)
            return addresses
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 4
        private const val CONNECT_TIMEOUT_SECONDS = 20L
        private const val READ_TIMEOUT_SECONDS = 60L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
