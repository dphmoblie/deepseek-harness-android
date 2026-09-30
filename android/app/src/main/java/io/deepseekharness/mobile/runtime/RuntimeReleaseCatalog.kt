package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject

/** 官方发布不等于已通过 Android 验证；仅为有受信任清单的构建提供下载入口。 */
class RuntimeReleaseCatalog {
    fun check(): JSONArray {
        val http = RuntimeHttp()
        val registry = JSONObject(http.catalogBytes("https://registry.npmjs.org/@deepseek-ai%2fdsh").toString(Charsets.UTF_8))
        val versions = registry.getJSONObject("versions").keys().asSequence()
            .filter { it.length <= 80 && Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$").matches(it) }
            .take(300).toList().reversed()
        val releases = JSONArray(http.catalogBytes("https://api.github.com/repos/dphmoblie/deepseek-harness-android/releases?per_page=30").toString(Charsets.UTF_8))
        val sources = mutableMapOf<String, JSONObject>()
        // 有界探测近期安卓构建；缺少清单摘要时不提供安装入口。
        for (index in 0 until minOf(releases.length(), 12)) {
            val assets = releases.getJSONObject(index).optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                if (asset.optString("name") != "runtime-manifest.json") continue
                val digest = asset.optString("digest").removePrefix("sha256:")
                if (!Regex("^[a-f0-9]{64}$").matches(digest)) continue
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("https://github.com/dphmoblie/deepseek-harness-android/releases/download/") || url.length > 1024) continue
                try {
                    val uri = RuntimeValidation.requireHttpsUri(url, rejectPrivateHost = true)
                    val manifest = RuntimeManifest.parse(http.downloadBytes(uri, digest, RuntimeLimits.MAX_MANIFEST_BYTES), uri.host)
                    val version = manifest.dshVersion ?: continue
                    if (version !in versions || version in sources) continue
                    sources[version] = JSONObject().put("manifestUrl", url).put("manifestSha256", digest)
                } catch (_: RuntimeFailure) { /* 不把无法验证的资产列为可安装。 */ }
            }
        }
        val output = JSONArray()
        versions.forEach { version ->
            output.put(JSONObject().put("version", version).put("source", sources[version] ?: JSONObject.NULL))
        }
        return output
    }
}
