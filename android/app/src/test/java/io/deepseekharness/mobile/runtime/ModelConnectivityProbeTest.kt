package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(result.message.contains("协议无效"))
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
}
