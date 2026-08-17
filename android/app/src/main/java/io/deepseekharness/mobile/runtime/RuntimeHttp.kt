package io.deepseekharness.mobile.runtime

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class RuntimeHttp {
    data class DownloadResult(val finalUri: URI, val bytes: Long, val sha256: String)

    private data class PartialDownload(val bytes: Long)
    private class RangeNotSupported : Exception()
    private class InvalidRangeResponse(message: String) : Exception(message)

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
        val result = withResponse(source) { response, input ->
            if (response.code != 200) {
                throw RuntimeFailure("DOWNLOAD_HTTP_ERROR", "清单服务返回 HTTP ${response.code}")
            }
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

        var partial = inspectPartial(destination, expectedBytes)
        if (partial.bytes == 0L) {
            // A failed first response may leave an empty file; restart with CREATE_NEW semantics.
            discardPartial(destination)
        }
        progress(partial.bytes, expectedBytes)
        if (partial.bytes == expectedBytes) {
            val existingDigest = digestFile(destination, expectedBytes)
            if (constantTimeDigestEquals(existingDigest, expectedSha256)) {
                return DownloadResult(source, expectedBytes, existingDigest)
            }
            discardPartial(destination)
            partial = emptyPartial()
            progress(0, expectedBytes)
        }

        val result = try {
            try {
                downloadFileResponse(source, destination, expectedBytes, partial, progress)
            } catch (_: RangeNotSupported) {
                discardPartial(destination)
                progress(0, expectedBytes)
                downloadFileResponse(source, destination, expectedBytes, emptyPartial(), progress)
            }
        } catch (error: InvalidRangeResponse) {
            discardPartial(destination)
            throw RuntimeFailure("DOWNLOAD_RANGE_INVALID", "下载服务返回了无效的断点响应", error)
        }

        if (result.bytes != expectedBytes) {
            throw RuntimeFailure("DOWNLOAD_INCOMPLETE", "运行时归档下载尚未完成，可再次安装继续下载")
        }
        if (!constantTimeDigestEquals(result.sha256, expectedSha256)) {
            discardPartial(destination)
            throw RuntimeFailure("ROOTFS_DIGEST_MISMATCH", "运行时归档摘要校验失败")
        }
        return result
    }

    private fun downloadFileResponse(
        source: URI,
        destination: File,
        expectedBytes: Long,
        partial: PartialDownload,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadResult {
        val offset = partial.bytes
        val options: Array<OpenOption> = if (offset == 0L) {
            arrayOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } else {
            arrayOf(
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        }
        return try {
            FileChannel.open(destination.toPath(), *options).use { channel ->
                if (channel.size() != offset) {
                    throw RuntimeFailure("DOWNLOAD_PART_CHANGED", "断点文件在下载期间发生变化")
                }
                channel.position(offset)
                withResponse(source, if (offset == 0L) null else offset) { response, input ->
                    val declaredSegmentBytes = validateDownloadResponse(response, offset, expectedBytes)
                    var count = offset
                    var segmentBytes = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (count > expectedBytes - read) {
                            throw InvalidRangeResponse("response exceeds the declared archive size")
                        }
                        val bytes = ByteBuffer.wrap(buffer, 0, read)
                        try {
                            while (bytes.hasRemaining()) channel.write(bytes)
                        } catch (error: IOException) {
                            throw RuntimeFailure("FILESYSTEM_ERROR", "无法写入断点文件", error)
                        }
                        count += read.toLong()
                        segmentBytes += read.toLong()
                        progress(count, expectedBytes)
                    }
                    try {
                        channel.force(true)
                    } catch (error: IOException) {
                        throw RuntimeFailure("FILESYSTEM_ERROR", "无法同步断点文件", error)
                    }
                    if (declaredSegmentBytes != null && segmentBytes != declaredSegmentBytes) {
                        throw RuntimeFailure("DOWNLOAD_INCOMPLETE", "下载连接提前结束，可再次安装继续下载")
                    }
                    val finalDigest = digestChannel(channel, count)
                    DownloadResult(URI(response.request.url.toString()), count, finalDigest)
                }
            }
        } catch (error: RuntimeFailure) {
            throw error
        } catch (error: RangeNotSupported) {
            throw error
        } catch (error: InvalidRangeResponse) {
            throw error
        } catch (error: IOException) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法打开断点文件", error)
        }
    }

    private fun validateDownloadResponse(response: Response, offset: Long, expectedBytes: Long): Long? {
        if (offset == 0L) {
            if (!response.isSuccessful) {
                throw RuntimeFailure("DOWNLOAD_HTTP_ERROR", "下载服务返回 HTTP ${response.code}")
            }
            if (response.code == 206) {
                val range = response.header("Content-Range")
                if (!contentRangeMatches(range, 0, expectedBytes)) {
                    throw InvalidRangeResponse("unexpected initial content range")
                }
                return expectedBytes
            }
            enforceContentLength(response, expectedBytes)
            return response.body?.contentLength()?.takeIf { it >= 0 }
        }

        if (response.code == 200 || response.code == 416) throw RangeNotSupported()
        if (response.code != 206) {
            throw RuntimeFailure("DOWNLOAD_HTTP_ERROR", "断点下载服务返回 HTTP ${response.code}")
        }
        val range = response.header("Content-Range")
        if (!contentRangeMatches(range, offset, expectedBytes)) {
            throw InvalidRangeResponse("unexpected resume content range")
        }
        val remaining = expectedBytes - offset
        enforceContentLength(response, remaining)
        return remaining
    }

    private fun inspectPartial(destination: File, expectedBytes: Long): PartialDownload {
        if (!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyPartial()
        if (!Files.isRegularFile(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw RuntimeFailure("DOWNLOAD_PART_INVALID", "断点路径不是常规文件")
        }
        val bytes = try {
            FileChannel.open(
                destination.toPath(),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val size = channel.size()
                if (size > expectedBytes) {
                    discardPartial(destination)
                    return emptyPartial()
                }
                size
            }
        } catch (error: RuntimeFailure) {
            throw error
        } catch (error: Exception) {
            throw RuntimeFailure("DOWNLOAD_PART_INVALID", "无法读取断点文件", error)
        }
        return PartialDownload(bytes)
    }

    private fun emptyPartial(): PartialDownload = PartialDownload(0)

    private fun digestFile(file: File, expectedBytes: Long): String {
        return try {
            FileChannel.open(
                file.toPath(),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel -> digestChannel(channel, expectedBytes) }
        } catch (error: RuntimeFailure) {
            throw error
        } catch (error: IOException) {
            throw RuntimeFailure("DOWNLOAD_PART_INVALID", "无法校验断点文件", error)
        }
    }

    private fun digestChannel(channel: FileChannel, expectedBytes: Long): String {
        if (channel.size() != expectedBytes) {
            throw RuntimeFailure("DOWNLOAD_PART_CHANGED", "断点文件在校验期间发生变化")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        var counted = 0L
        channel.position(0)
        while (true) {
            buffer.clear()
            val read = try {
                channel.read(buffer)
            } catch (error: IOException) {
                throw RuntimeFailure("DOWNLOAD_PART_INVALID", "无法读取断点文件", error)
            }
            if (read < 0) break
            if (read == 0) continue
            counted += read.toLong()
            if (counted > expectedBytes) {
                throw RuntimeFailure("DOWNLOAD_PART_CHANGED", "断点文件在校验期间发生变化")
            }
            buffer.flip()
            digest.update(buffer)
        }
        if (counted != expectedBytes || channel.size() != expectedBytes) {
            throw RuntimeFailure("DOWNLOAD_PART_CHANGED", "断点文件在校验期间发生变化")
        }
        return digest.hex()
    }

    private fun discardPartial(destination: File) {
        try {
            Files.deleteIfExists(destination.toPath())
        } catch (error: Exception) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法清理无效断点文件", error)
        }
    }

    private fun <T> withResponse(
        source: URI,
        rangeStart: Long? = null,
        consume: (Response, InputStream) -> T,
    ): T {
        var current = source
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            RuntimeValidation.requireHttpsUri(current.toASCIIString(), rejectPrivateHost = true)
            val request = try {
                Request.Builder()
                    .url(current.toASCIIString())
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "DeepSeekHarnessMobile/0.1.7")
                    .also { builder -> rangeStart?.let { builder.header("Range", "bytes=$it-") } }
                    .get()
                    .build()
            } catch (error: IllegalArgumentException) {
                throw RuntimeFailure("URL_INVALID", "下载地址格式无效", error)
            }
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code in REDIRECT_CODES) {
                        if (redirectCount == MAX_REDIRECTS) {
                            throw RuntimeFailure("DOWNLOAD_REDIRECT_LIMIT", "下载重定向次数过多")
                        }
                        val location = response.header("Location")
                            ?: throw RuntimeFailure("DOWNLOAD_FAILED", "下载重定向缺少目标地址")
                        current = resolveRedirect(current, location)
                        return@repeat
                    }
                    val body = response.body ?: throw RuntimeFailure("DOWNLOAD_FAILED", "下载响应不包含内容")
                    body.byteStream().use { input -> return consume(response, input) }
                }
            } catch (error: RuntimeFailure) {
                throw error
            } catch (error: SocketTimeoutException) {
                throw RuntimeFailure("DOWNLOAD_TIMEOUT", "下载连接或读取超时，可稍后继续", error)
            } catch (error: SSLException) {
                throw RuntimeFailure("DOWNLOAD_TLS_FAILED", "下载服务 TLS 校验失败", error)
            } catch (error: IOException) {
                throw RuntimeFailure("DOWNLOAD_NETWORK_UNAVAILABLE", "网络不可用或下载连接已中断，可稍后继续", error)
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
        private val CONTENT_RANGE_PATTERN = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$")

        internal fun contentRangeMatches(value: String?, expectedStart: Long, expectedTotal: Long): Boolean {
            val match = value?.let(CONTENT_RANGE_PATTERN::matchEntire) ?: return false
            val start = match.groupValues[1].toLongOrNull() ?: return false
            val end = match.groupValues[2].toLongOrNull() ?: return false
            val total = match.groupValues[3].toLongOrNull() ?: return false
            return start == expectedStart && total == expectedTotal && end == expectedTotal - 1 && end >= start
        }

        /**
         * Release hosts commonly redirect to a separate CDN. The content digest pins both the
         * manifest and archive, while PublicOnlyDns and this validation keep every hop on public
         * HTTPS and prevent redirects to loopback/private destinations.
         */
        internal fun resolveRedirect(current: URI, location: String): URI {
            val resolved = try {
                current.resolve(location)
            } catch (error: IllegalArgumentException) {
                throw RuntimeFailure("URL_INVALID", "下载重定向地址格式无效", error)
            }
            return RuntimeValidation.requireHttpsUri(resolved.toASCIIString(), rejectPrivateHost = true)
        }
    }
}
