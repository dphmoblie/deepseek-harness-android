package io.deepseekharness.mobile.runtime

import java.net.URI

/** Validates the private DSH browser entry point before it can reach a WebView. */
internal object HarnessWebAuth {
    private val ENTRY = Regex("http://127\\.0\\.0\\.1:([1-9][0-9]{3,4})/\\?token=[A-Za-z0-9_-]{43}")

    fun parseLaunchUrl(raw: String, expectedPort: Int? = null): URI? {
        // Security: exact ASCII syntax excludes encoded hosts, duplicate queries and fragments.
        if (raw.length > 128) return null
        val match = ENTRY.matchEntire(raw) ?: return null
        val port = match.groupValues[1].toIntOrNull() ?: return null
        if (port !in 1024..65535 || expectedPort != null && port != expectedPort) return null
        return URI(raw)
    }
}

/** Captures a complete startup line independently of the bounded diagnostic tail. */
internal class HarnessWebAuthCapture(private val port: Int) {
    private val line = StringBuilder()
    private var oversized = false
    private var launchUrl: String? = null

    @Synchronized
    fun append(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size) { "Invalid output byte count" }
        if (launchUrl != null) return
        for (index in 0 until count) {
            val character = (bytes[index].toInt() and 0xff).toChar()
            if (character == '\n') {
                if (!oversized) {
                    val complete = line.toString().removeSuffix("\r")
                    if (complete.startsWith(PREFIX)) {
                        launchUrl = HarnessWebAuth.parseLaunchUrl(complete.removePrefix(PREFIX), port)?.toASCIIString()
                    }
                }
                line.setLength(0)
                oversized = false
                if (launchUrl != null) return
            } else if (!oversized) {
                // Bound memory even if a child emits an unterminated or malformed line.
                if (line.length >= MAX_LINE_LENGTH) {
                    line.setLength(0)
                    oversized = true
                } else {
                    line.append(character)
                }
            }
        }
    }

    @Synchronized
    fun url(): String? = launchUrl

    @Synchronized
    fun clear() {
        launchUrl = null
        line.setLength(0)
        oversized = false
    }

    companion object {
        private const val PREFIX = "dsh web: "
        private const val MAX_LINE_LENGTH = 512
    }
}
