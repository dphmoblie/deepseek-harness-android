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

    /**
     * 外置浏览器入口：令牌放查询串，由访客预载脚本兑换成 HttpOnly Cookie 后 302 去掉。
     *
     * 令牌是**浏览器专用**的那一枚，不是 Basic 密码；片段做不到这件事（片段不会发给服务器）。
     *
     * [sessionId] 放在**片段**里而不是查询串：查询串会被 dsh 自己的 `?token=` 登录跳转 303
     * 到 `./` 时整段丢掉，片段不会。前端适配脚本（dsh-mobile-session.js）在启动前把它写进
     * DSH 的 `dsh.sessions.current`，外置浏览器才会停在与应用内相同的会话页，顶栏和右上角
     * 面板入口也才存在。非法值按「没有会话」处理，不阻断打开。
     */
    fun browserLaunchUrl(raw: String, browserToken: String, sessionId: String? = null): URI? {
        val entry = parseLaunchUrl(raw) ?: return null
        if (!TOKEN_PATTERN.matches(browserToken)) return null
        val fragment = sessionId?.takeIf { SESSION_ID.matches(it) }
            ?.let { "$SESSION_FRAGMENT=$it" }
        return URI(entry.scheme, null, entry.host, entry.port, "/", "dshMobileToken=$browserToken", fragment)
    }

    /**
     * 从 WebView Cookie 里取出前端镜像的当前会话键。
     *
     * Cookie 头属于外部输入：只认精确的 Cookie 名与严格的会话键格式，重复项取第一个合法值。
     * 值里不含需要解码的字符（前端只写 `[A-Za-z0-9._-]`），因此不做 URL 解码，避免多一层歧义。
     */
    fun sessionFromCookieHeader(header: String?): String? {
        if (header.isNullOrEmpty()) return null
        for (part in header.split(';')) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            if (part.substring(0, index).trim() != SESSION_COOKIE) continue
            val value = part.substring(index + 1).trim()
            if (SESSION_ID.matches(value)) return value
        }
        return null
    }

    const val SESSION_COOKIE = "dsh_mobile_last_session"
    const val SESSION_FRAGMENT = "dshMobileSession"
    // 与 harness-web/dsh-mobile-session.js 的 SESSION_ID 保持同一口径。
    private val SESSION_ID = Regex("session-[A-Za-z0-9._-]{1,190}")
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{43}")
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
