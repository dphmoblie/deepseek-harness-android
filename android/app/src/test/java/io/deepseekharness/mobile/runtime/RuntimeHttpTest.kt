package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.io.File
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest

class RuntimeHttpTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun interruptedDownloadsKeepWrittenBytesAndMappedErrorCodes() {
        for ((error, code) in listOf(
            ProtocolException("truncated response") to "DOWNLOAD_NETWORK_UNAVAILABLE",
            SocketTimeoutException("read timed out") to "DOWNLOAD_TIMEOUT",
            IOException("connection closed") to "DOWNLOAD_NETWORK_UNAVAILABLE",
        )) {
            val destination = temporary.newFile()
            val http = http { request -> response(request, byteArrayOf(1, 2, 3, 4), 10, error) }
            val failure = assertThrows(RuntimeFailure::class.java) {
                http.downloadFile(source, destination, 10, "0".repeat(64)) { _, _ -> }
            }
            assertEquals(code, failure.code)
            assertEquals(mapOf("expected_bytes" to "10", "actual_bytes" to "4"), failure.details)
            assertEquals(4L, destination.length())
        }
    }

    @Test
    fun prematureEofIncludesTheExistingResumeOffset() {
        val destination = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val http = http { request ->
            assertEquals("bytes=3-", request.header("Range"))
            response(request, byteArrayOf(4, 5), 7, code = 206, range = "bytes 3-9/10")
        }
        val failure = assertThrows(RuntimeFailure::class.java) {
            http.downloadFile(source, destination, 10, "0".repeat(64)) { _, _ -> }
        }
        assertEquals("DOWNLOAD_INCOMPLETE", failure.code)
        assertEquals(mapOf("expected_bytes" to "10", "actual_bytes" to "5"), failure.details)
        assertEquals(5L, destination.length())
    }

    @Test
    fun restartAfterIgnoredRangeCountsOnlyTheNewResponse() {
        val destination = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var calls = 0
        val http = http { request ->
            calls++
            assertEquals(if (calls == 1) "bytes=3-" else null, request.header("Range"))
            response(request, byteArrayOf(8, 9), 10, SocketTimeoutException())
        }
        val failure = assertThrows(RuntimeFailure::class.java) {
            http.downloadFile(source, destination, 10, "0".repeat(64)) { _, _ -> }
        }
        assertEquals(2, calls)
        assertEquals("DOWNLOAD_TIMEOUT", failure.code)
        assertEquals("2", failure.details["actual_bytes"])
        assertEquals(2L, destination.length())
    }

    @Test
    fun progressCancellationKeepsItsCodeAndConfirmedWrites() {
        val destination = temporary.newFile()
        val http = http { request -> response(request, byteArrayOf(1, 2, 3), 10) }
        val failure = assertThrows(RuntimeFailure::class.java) {
            http.downloadFile(source, destination, 10, "0".repeat(64)) { count, _ ->
                if (count > 0) throw RuntimeFailure("INSTALL_CANCELLED", "cancelled")
            }
        }
        assertEquals("INSTALL_CANCELLED", failure.code)
        assertEquals("3", failure.details["actual_bytes"])
    }

    @Test
    fun successfulDownloadStillVerifiesTheDigest() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val destination = temporary.newFile()
        val http = http { request -> response(request, bytes, bytes.size.toLong()) }
        val result = http.downloadFile(source, destination, bytes.size.toLong(), digest) { _, _ -> }
        assertEquals(bytes.size.toLong(), result.bytes)
        assertEquals(digest, result.sha256)
        assertTrue(bytes.contentEquals(destination.readBytes()))
    }

    @Test
    fun digestFailureSamplesSpaceBeforeDiscardingTheArchive() {
        val destination = temporary.newFile()
        val http = http(availableBytes = { 10 - destination.length() }) { request ->
            response(request, byteArrayOf(1, 2, 3, 4), 4)
        }
        val failure = assertThrows(RuntimeFailure::class.java) {
            http.downloadFile(source, destination, 4, "0".repeat(64)) { _, _ -> }
        }
        assertEquals("ROOTFS_DIGEST_MISMATCH", failure.code)
        assertEquals("6", failure.details["free_bytes"])
        assertFalse(destination.exists())
    }

    @Test
    fun invalidRangePreservesResumeEvidenceBeforeDiscardingThePartial() {
        val destination = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val http = http(availableBytes = { 10 - destination.length() }) { request ->
            response(request, byteArrayOf(4, 5), 7, code = 206, range = "bytes 4-9/10")
        }
        val failure = assertThrows(RuntimeFailure::class.java) {
            http.downloadFile(source, destination, 10, "0".repeat(64)) { _, _ -> }
        }
        assertEquals("DOWNLOAD_RANGE_INVALID", failure.code)
        assertEquals(mapOf("expected_bytes" to "10", "actual_bytes" to "3", "free_bytes" to "7"), failure.details)
        assertFalse(destination.exists())
    }

    @Test
    fun acceptsExactRemainingContentRange() {
        assertTrue(RuntimeHttp.contentRangeMatches("bytes 128-999/1000", 128, 1000))
        assertTrue(RuntimeHttp.contentRangeMatches("bytes 0-999/1000", 0, 1000))
    }

    @Test
    fun rejectsMismatchedOrPartialContentRanges() {
        listOf(
            "bytes 127-999/1000",
            "bytes 128-998/1000",
            "bytes 128-999/1001",
            "bytes */1000",
            "bytes 128-999/*",
            "bytes 9223372036854775808-999/1000",
            null,
        ).forEach { value ->
            assertFalse(RuntimeHttp.contentRangeMatches(value, 128, 1000))
        }
    }

    @Test
    fun allowsPinnedReleaseDownloadsToRedirectToAPublicCdn() {
        val redirected = RuntimeHttp.resolveRedirect(
            URI("https://github.com/example/project/releases/download/v1/rootfs.bundle"),
            "https://release-assets.githubusercontent.com/github-production-release-asset/rootfs.bundle",
        )

        assertEquals("release-assets.githubusercontent.com", redirected.host)
    }

    @Test
    fun rejectsRedirectsToLocalOrCleartextDestinations() {
        val source = URI("https://downloads.example.invalid/rootfs.bundle")

        assertThrows(RuntimeFailure::class.java) {
            RuntimeHttp.resolveRedirect(source, "https://127.0.0.1/rootfs.bundle")
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeHttp.resolveRedirect(source, "http://downloads.example.invalid/rootfs.bundle")
        }
    }

    private val source = URI("https://downloads.example.invalid/rootfs.bundle")

    // An application interceptor exercises the real file/error path without DNS or a server.
    private fun http(
        availableBytes: (File) -> Long? = { null },
        respond: (Request) -> Response,
    ): RuntimeHttp = RuntimeHttp(
        OkHttpClient.Builder().addInterceptor { chain -> respond(chain.request()) }.build(),
        availableBytes,
    )

    private fun response(
        request: Request,
        bytes: ByteArray,
        declaredBytes: Long,
        failure: IOException? = null,
        code: Int = 200,
        range: String? = null,
    ): Response {
        val input = object : Source {
            val remaining = Buffer().write(bytes)
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining.size > 0) return remaining.read(sink, byteCount)
                if (failure != null) throw failure
                return -1
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = declaredBytes
            override fun source(): BufferedSource = input
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
            .message("test").body(body).also { if (range != null) it.header("Content-Range", range) }.build()
    }
}
