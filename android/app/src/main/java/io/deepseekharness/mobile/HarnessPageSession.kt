package io.deepseekharness.mobile

internal enum class CookieLoadDecision {
    LOAD,
    REJECT,
    IGNORE,
}

/** Ensures an asynchronous CookieManager callback can trigger at most one live page load. */
internal class HarnessPageLoadGate {
    private var active = true
    private var handled = false

    @Synchronized
    fun onCookieStored(accepted: Boolean): CookieLoadDecision {
        if (!active || handled) return CookieLoadDecision.IGNORE
        handled = true
        return if (accepted) CookieLoadDecision.LOAD else CookieLoadDecision.REJECT
    }

    @Synchronized
    fun cancel() {
        active = false
    }
}

internal object HarnessSessionCookie {
    private val TOKEN = Regex("[A-Za-z0-9_-]{43}")

    fun origin(port: Int): String {
        require(port in 1024..65535) { "Harness port is outside the allowed range" }
        return "http://127.0.0.1:$port"
    }

    fun authenticated(token: String): String {
        require(TOKEN.matches(token)) { "Harness token has an invalid format" }
        return "${HarnessActivity.AUTH_TOKEN_COOKIE}=$token; Path=/; HttpOnly; SameSite=Strict"
    }

    fun expired(): String =
        "${HarnessActivity.AUTH_TOKEN_COOKIE}=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"
}

internal object HarnessPageUrl {
    private val ROOT_URL = Regex("http://127\\.0\\.0\\.1:([0-9]{4,5})/")
    private val APP_VERSION = Regex("[A-Za-z0-9._-]{1,64}")

    fun withAppVersion(rootUrl: String, appVersion: String): String {
        val port = ROOT_URL.matchEntire(rootUrl)?.groupValues?.get(1)?.toIntOrNull()
        require(port != null && port in 1024..65535) { "Harness root URL has an invalid format" }
        require(APP_VERSION.matches(appVersion)) { "Application version has an invalid format" }
        return "${rootUrl}?appVersion=$appVersion"
    }
}
