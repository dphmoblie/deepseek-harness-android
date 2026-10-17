package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject

/** 官方 npm 全版本目录，与 Ubuntu 镜像的发布目录各自独立。 */
internal object HarnessPackageCatalog {
    const val URL = "https://registry.npmjs.org/@deepseek-ai%2Fdsh"
    const val MAX_VERSIONS = 1000
    val VERSION = Regex("^(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?$")

    fun requireVersion(value: String?): String = value?.takeIf { it.length <= 96 && VERSION.matches(it) }
        ?: throw RuntimeFailure("HARNESS_VERSION_INVALID", "Harness 版本号无效")

    data class Entry(val version: String, val integrity: String, val tags: List<String>) {
        fun toJson(): JSONObject = JSONObject().put("version", version).put("tags", JSONArray(tags))
    }

    fun list(http: RuntimeHttp = RuntimeHttp()): List<Entry> = parse(http.catalogBytes(URL))

    /** 安全校验：包身份、精确 tarball 路径、SHA-512 和版本数量均校验，不接收用户下载地址。 */
    fun parse(bytes: ByteArray): List<Entry> {
        val root = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (error: Exception) {
            throw RuntimeFailure("HARNESS_CATALOG_INVALID", "上游 Harness 目录格式无效", error)
        }
        val versions = root.optJSONObject("versions")
            ?: throw RuntimeFailure("HARNESS_CATALOG_INVALID", "上游 Harness 目录缺少版本")
        if (versions.length() > MAX_VERSIONS || root.optString("name") != "@deepseek-ai/dsh") {
            throw RuntimeFailure("HARNESS_CATALOG_INVALID", "上游 Harness 目录超限或包名不符")
        }
        val tags = root.optJSONObject("dist-tags") ?: JSONObject()
        return versions.keys().asSequence().mapNotNull { version ->
            if (version.length > 96 || !VERSION.matches(version)) return@mapNotNull null
            val manifest = versions.optJSONObject(version) ?: return@mapNotNull null
            if (manifest.optString("version") != version || manifest.optString("name") != "@deepseek-ai/dsh") return@mapNotNull null
            val dist = manifest.optJSONObject("dist") ?: return@mapNotNull null
            val expected = "https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-$version.tgz"
            if (dist.optString("tarball") != expected) return@mapNotNull null
            val integrity = dist.optString("integrity")
            if (!Regex("^sha512-[A-Za-z0-9+/]{86}==$" ).matches(integrity)) return@mapNotNull null
            Entry(version, integrity, listOf("latest", "next", "alpha", "beta").filter { tags.optString(it) == version })
        }.toList().sortedWith { a, b -> compareVersions(b.version, a.version) }
    }

    /** 按 SemVer 排序：正式版高于同号预发布；预发布数字以数值比较。 */
    internal fun compareVersions(a: String, b: String): Int {
        val left = a.split('-', limit = 2)
        val right = b.split('-', limit = 2)
        val lcore = left[0].split('.').map(String::toInt)
        val rcore = right[0].split('.').map(String::toInt)
        for (i in 0..2) if (lcore[i] != rcore[i]) return lcore[i].compareTo(rcore[i])
        if (left.size != right.size) return right.size.compareTo(left.size)
        if (left.size == 1) return 0
        val lp = left[1].split('.')
        val rp = right[1].split('.')
        for (i in 0 until minOf(lp.size, rp.size)) {
            if (lp[i] == rp[i]) continue
            val ln = lp[i].toLongOrNull()
            val rn = rp[i].toLongOrNull()
            return when {
                ln != null && rn != null -> ln.compareTo(rn)
                ln != null -> -1
                rn != null -> 1
                else -> lp[i].compareTo(rp[i])
            }
        }
        return lp.size.compareTo(rp.size)
    }
}
