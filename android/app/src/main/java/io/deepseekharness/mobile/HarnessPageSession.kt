package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.HarnessWebAuth
import java.net.URI

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
    private val ROOT_URL = Regex("http://127\\.0\\.0\\.1:([1-9][0-9]{3,4})/")
    private val APP_VERSION = Regex("[A-Za-z0-9._-]{1,64}")
    private val RUNTIME_VERSION = Regex("[A-Za-z0-9._-]{1,96}")

    const val APP_VERSION_PARAM = "appVersion"
    const val RUNTIME_VERSION_PARAM = "runtimeVersion"

    /**
     * 运行时版本未知（未安装、清单不可读或不合法）时的固定占位值。
     *
     * 不省略参数、而是固定取值：URL 就是入口页的缓存身份，未知态也必须有一个确定的身份，
     * 否则同一台设备会因为清单读取时机不同拼出不同 URL，事后无法判断命中的是哪一份入口页，
     * 也就无法为未知态定一条明确规则（见 HarnessPageCache：未知态一律不缓存）。
     */
    const val UNKNOWN_RUNTIME_VERSION = "none"

    fun parseEntryUrl(raw: String?): URI? {
        if (raw.isNullOrEmpty() || raw.length > 128) return null
        HarnessWebAuth.parseLaunchUrl(raw)?.let { return it }
        // Security: legacy entries accept only the canonical loopback root without a query.
        val port = ROOT_URL.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (port !in 1024..65535) return null
        return URI(raw)
    }

    fun withAppVersion(entryUrl: String, appVersion: String): String {
        return withVersions(entryUrl, appVersion, null)
    }

    /**
     * 给入口 URL 补上两维版本键。
     *
     * 前端产物按内容哈希命名，可以长缓存；唯一没有哈希的就是入口 index.html，所以它的缓存键
     * 必须同时覆盖 APK 版本与已安装运行时版本：任一一维变化都会换出一个新 URL，WebView 才
     * 不会把旧前端当成同一条入口复用（用户在线更新运行时后正是靠这一维生效）。
     */
    fun withVersions(entryUrl: String, appVersion: String, runtimeVersion: String?): String {
        val entry = requireNotNull(parseEntryUrl(entryUrl)) { "Harness entry URL has an invalid format" }
        require(APP_VERSION.matches(appVersion)) { "Application version has an invalid format" }
        // 运行时版本来自设备上已安装的清单，属于外部输入：读不到（null / 空串）就回退到固定
        // 占位值而不是抛异常——这是设备状态，不是调用方错误，启动路径不该为一个读不到的清单失败。
        // 非空的非法值仍然抛错：那是调用方的契约错误，必须让测试当场暴露，不能悄悄降级成未知态。
        val runtimeKey = if (runtimeVersion.isNullOrEmpty()) {
            UNKNOWN_RUNTIME_VERSION
        } else {
            require(RUNTIME_VERSION.matches(runtimeVersion)) { "Runtime version has an invalid format" }
            runtimeVersion
        }
        // Hashed assets may use WebView's HTTP cache. These version keys invalidate the HTML entry
        // whenever either the APK shell or independently updated runtime frontend changes.
        val query = listOfNotNull(
            entry.rawQuery,
            "$APP_VERSION_PARAM=$appVersion",
            "$RUNTIME_VERSION_PARAM=$runtimeKey",
        ).joinToString("&")
        return URI(entry.scheme, null, entry.host, entry.port, entry.path, query, null).toASCIIString()
    }

    /**
     * 取出入口 URL 上的两维版本键；缺任一维或取值不合规时返回 null。
     *
     * 只做取值提取，不判断「能不能缓存」：判断的是 URL 是否唯一标识某个 APK 与某个运行时前端，
     * 因此不关心参数的书写顺序（占位值的取舍见 HarnessPageCache）。
     */
    fun versionCacheKey(entryUrl: String): Pair<String, String>? {
        val query = runCatching { URI(entryUrl).rawQuery }.getOrNull() ?: return null
        val values = query.split('&').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
        }.toMap()
        val app = values[APP_VERSION_PARAM]?.takeIf { APP_VERSION.matches(it) } ?: return null
        val runtime = values[RUNTIME_VERSION_PARAM]?.takeIf { RUNTIME_VERSION.matches(it) } ?: return null
        return app to runtime
    }
}
