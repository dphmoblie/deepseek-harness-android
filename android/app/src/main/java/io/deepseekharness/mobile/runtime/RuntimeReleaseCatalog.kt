package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行时可用版本目录。
 *
 * 只认 GitHub 发布里的 `runtime-manifest.json`。这里**不再查 npm registry**：
 * 安装对象是 Android 运行时镜像（清单 + rootfs + 自定义入口），npm 上的 `@deepseek-ai/dsh`
 * 包版本装不进这台设备；两个源混在一起时界面会列出一些「点不动」的版本
 * （dsh 有版本号，却没有对应的安卓镜像可下），所以只留能真正下载并校验的那一个源。
 *
 * 官方发布不等于已通过 Android 验证；这里只为「有受校验清单」的构建提供下载入口。
 * `manifestUrl` / `manifestSha256` 是**同源摘要校验**（GitHub 为资产提供的 digest）：
 * 它证明下载到的清单与这次发布的资产一致，**不是供应链签名**，也不证明发布者是谁。
 */
class RuntimeReleaseCatalog(private val http: RuntimeHttp = RuntimeHttp()) {
    /**
     * 可用版本条目，新的在前（GitHub 按发布时间倒序返回），最多 [MAX_ENTRIES] 条。
     *
     * 失败策略：目录取不到、目录不是 JSON 数组，一律抛 [RuntimeFailure] 让界面显示真实的失败；
     * 单个发布的清单**内容**不合格只跳过那一个发布。「取不到」和「没有可用版本」必须能区分开：
     * 把前者显示成后者，就是在编造一个让人以为已经查过了的事实。
     */
    fun list(): JSONArray {
        val releases = GitHubReleases.parse(http.catalogBytes(GitHubReleases.URL))
        val entries = JSONArray()
        val seen = mutableSetOf<String>()
        for (release in releases) {
            if (entries.length() >= MAX_ENTRIES) break
            val asset = GitHubReleases.asset(release, MANIFEST_ASSET) ?: continue
            val manifest = try {
                val uri = RuntimeValidation.requireHttpsUri(asset.url, rejectPrivateHost = true)
                RuntimeManifest.parse(http.downloadBytes(uri, asset.sha256, RuntimeLimits.MAX_MANIFEST_BYTES), uri.host)
            } catch (failure: RuntimeFailure) {
                // 清单内容不合格＝这个发布不可安装，跳过它并继续看下一个。
                if (failure.code !in CONTENT_REJECT_CODES) throw failure
                continue
            }
            // 清单里的 version 已被 RuntimeManifest.parse 钉成 `^[A-Za-z0-9._-]{1,96}$`（标识形态），
            // 兜底的 tag 侧也过同样的校验，所以条目里的 version 一定落在前端 IDENTIFIER_PATTERN 内。
            val version = if (manifest.version.isNotBlank()) {
                manifest.version
            } else {
                GitHubReleases.versionFromTag(release.tag) ?: continue
            }
            if (!seen.add(version)) continue
            val entry = JSONObject().put("version", version)
            manifest.dshVersion?.let { entry.put("dshVersion", it) }
            // 清单地址与摘要必须成对出现；缺一个，界面就会把这条当成「只能看、不能装」。
            entry.put("manifestUrl", asset.url).put("manifestSha256", asset.sha256)
            entries.put(entry)
        }
        return entries
    }

    companion object {
        const val MANIFEST_ASSET = "runtime-manifest.json"

        /** 列表上限：目录再长，界面也不该一次拿到无限多条。 */
        const val MAX_ENTRIES = 40

        /**
         * 「内容不合格」的失败码：跳过这一个发布即可。
         *
         * 其余 [RuntimeFailure]（网络、HTTP、TLS、重定向、文件系统）一律上抛——那些是「取不到」。
         */
        private val CONTENT_REJECT_CODES = setOf(
            "MANIFEST_INVALID",
            "MANIFEST_SIZE_INVALID",
            "MANIFEST_SCHEMA_UNSUPPORTED",
            "MANIFEST_DIGEST_MISMATCH",
            "ARCHITECTURE_UNSUPPORTED",
            "ENTRYPOINT_NOT_ALLOWED",
            "DOWNLOAD_HOST_NOT_ALLOWED",
            "DOWNLOAD_TOO_LARGE",
            "DIGEST_INVALID",
            "URL_INVALID",
            "URL_HOST_NOT_ALLOWED",
        )
    }
}
