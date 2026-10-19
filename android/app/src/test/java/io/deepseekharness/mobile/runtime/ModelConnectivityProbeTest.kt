package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ModelConnectivityProbeTest {

    @Test
    fun probeRejectsMissingApiKey() {
        val result = ModelConnectivityProbe.probe(
            providerId = "deepseek",
            baseUrl = null,
            apiKey = null,
            api = null,
            savedApiKey = null,
        )
        assertFalse(result.ok)
        assertEquals(0, result.status)
        assertEquals(0L, result.latencyMs)
        assertTrue(result.message.contains("未提供 API Key"))
    }

    @Test
    fun probeRejectsBlankApiKey() {
        val result = ModelConnectivityProbe.probe(
            providerId = "openai",
            baseUrl = null,
            apiKey = "   ",
            api = null,
            savedApiKey = "",
        )
        assertFalse(result.ok)
        assertEquals(0, result.status)
        assertTrue(result.message.contains("未提供 API Key"))
    }

    @Test
    fun probeRejectsControlCharactersInApiKey() {
        val result = ModelConnectivityProbe.probe(
            providerId = "openai",
            baseUrl = null,
            apiKey = "sk-test\nforged-header",
            api = null,
            savedApiKey = null,
        )
        assertFalse(result.ok)
        assertEquals(0, result.status)
        assertTrue(result.message.contains("非法字符"))
    }

    @Test
    fun probeRejectsCustomProviderWithoutBaseUrl() {
        val result = ModelConnectivityProbe.probe(
            providerId = "custom-one",
            baseUrl = "   ",
            apiKey = "sk-test-key",
            api = "openai-completions",
            savedApiKey = null,
        )
        assertFalse(result.ok)
        assertEquals(0, result.status)
        assertTrue(result.message.contains("Base URL 不能为空"))
    }

    @Test
    fun probeRejectsCustomProviderWithInvalidProtocol() {
        val result = ModelConnectivityProbe.probe(
            providerId = "custom-one",
            baseUrl = "ftp://api.example.com",
            apiKey = "sk-test-key",
            api = "openai-completions",
            savedApiKey = null,
        )
        assertFalse(result.ok)
        assertEquals(0, result.status)
        assertTrue(result.message.contains("HTTPS"))
    }

    @Test
    fun probeRejectsCredentialBearingCustomBaseUrlBeforeNetworkRequest() {
        val result = ModelConnectivityProbe.probe(
            providerId = "custom-one",
            baseUrl = "https://user:secret@example.com/v1",
            apiKey = "sk-test-key",
            api = "openai-completions",
            savedApiKey = null,
        )
        assertFalse(result.ok)
        assertEquals(0, result.status)
        assertTrue(result.message.contains("不能包含凭据"))
    }

    @Test
    fun googleApiKeyIsQueryEncoded() {
        val url = ModelConnectivityProbe.googleModelsRequestUrl("key#with&reserved")
        assertTrue(url.endsWith("key=key%23with%26reserved"))
    }

    @Test
    fun probeCatchesUnreachableHostGracefullyWithoutThrowing() {
        val result = ModelConnectivityProbe.probe(
            providerId = "custom-mock",
            baseUrl = "http://invalid-hostname-that-does-not-exist-1234567.test",
            apiKey = "sk-mock-key",
            api = "openai-completions",
            savedApiKey = null,
        )
        assertFalse(result.ok)
        assertEquals(0, result.status)
        assertTrue(result.message.isNotEmpty())
    }

    @Test
    fun probeReportsSuccessOn2xx() {
        TinyHttpServer("HTTP/1.1 200 OK").use { server ->
            val result = ModelConnectivityProbe.probe(
                providerId = "custom-local",
                baseUrl = "http://127.0.0.1:${server.port}",
                apiKey = "sk-local-key",
                api = "openai-completions",
                savedApiKey = null,
            )
            assertTrue(result.message, result.ok)
            assertEquals(200, result.status)
            assertTrue(result.message.contains("连接成功"))
            assertEquals(1, server.hits.get())
        }
    }

    /**
     * 重定向不跟随：探测请求带着 API Key，跟随重定向等于把它重放到第二个主机（`HttpURLConnection`
     * 会原样重放请求头）。这里用两个本地服务端把两件事都钉死——密钥确实发给了原地址，
     * 而重定向目标**一个请求都没收到**。
     */
    @Test
    fun probeDoesNotFollowRedirectAndNeverSendsKeyToRedirectTarget() {
        TinyHttpServer("HTTP/1.1 200 OK").use { target ->
            TinyHttpServer(
                statusLine = "HTTP/1.1 302 Found",
                extraHeaders = listOf("Location: http://127.0.0.1:${target.port}/models"),
            ).use { redirector ->
                val result = ModelConnectivityProbe.probe(
                    providerId = "custom-redirect",
                    baseUrl = "http://127.0.0.1:${redirector.port}",
                    apiKey = "sk-redirect-secret",
                    api = "openai-completions",
                    savedApiKey = null,
                )

                // 原地址确实收到了带密钥的请求，否则下面的「没跟过去」就没有意义。
                assertEquals(1, redirector.hits.get())
                assertTrue(redirector.requestHeaders.any { it.startsWith("Authorization: Bearer sk-redirect-secret") })

                assertFalse(result.ok)
                assertEquals(302, result.status)
                assertTrue(result.message, result.message.contains("不会自动跟随"))
                assertEquals("重定向目标不该收到任何请求", 0, target.hits.get())
            }
        }
    }

    /**
     * 一个只回一条固定响应、并记录请求头的最小 HTTP 服务端：端口由系统分配（只绑回环），
     * 响应写完就关连接，够探测这种「一问一答」用，也不依赖任何外部网络。
     */
    private class TinyHttpServer(
        private val statusLine: String,
        private val extraHeaders: List<String> = emptyList(),
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val hits = AtomicInteger(0)
        val requestHeaders: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        init {
            Thread {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            hits.incrementAndGet()
                            client.soTimeout = 2_000
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                requestHeaders.add(line)
                            }
                            val body = "{}".toByteArray(Charsets.US_ASCII)
                            val head = buildString {
                                append(statusLine).append("\r\n")
                                extraHeaders.forEach { append(it).append("\r\n") }
                                append("Content-Length: ").append(body.size).append("\r\n")
                                append("Connection: close").append("\r\n\r\n")
                            }.toByteArray(Charsets.US_ASCII)
                            client.getOutputStream().apply {
                                write(head)
                                write(body)
                                flush()
                            }
                        }
                    } catch (_: Exception) {
                        // 测试收尾时 accept 抛错属正常路径。
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() {
            socket.close()
        }
    }
}
