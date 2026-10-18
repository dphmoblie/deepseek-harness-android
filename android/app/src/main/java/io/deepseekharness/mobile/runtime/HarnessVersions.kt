package io.deepseekharness.mobile.runtime

import android.content.Context
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 独立 Harness 版本库在 rootfs 外保存，更新 Ubuntu 不会删除它。
 * 所有修改由控制器生命周期锁串行化；目录只接受精确版本号，不接受路径或下载链接。
 */
internal class HarnessVersions(private val context: Context, private val store: RuntimeStore) {
    private val home get() = File(store.runtimeParent, "harness-versions")
    private val versions get() = File(home, "versions")
    private val selection get() = File(home, "selection.json")
    private val resolver = RuntimeLaunchResolver(context, store, includeCredentials = false, selectHarness = false)

    fun list(): JSONObject {
        // 选择文件损坏时也要能把列表显示出来，否则用户没有入口切回内置版本。
        val current = selectedOrBundled(store)
        val entries = JSONArray()
        versions.listFiles()?.sortedByDescending { it.name }?.forEach { directory ->
            if (HarnessPackageCatalog.VERSION.matches(directory.name) && directory.name.length <= 96) {
                val ready = ready(directory)
                if (ready != null) entries.put(JSONObject()
                    .put("version", directory.name)
                    .put("bytes", ready.optLong("bytes", 0))
                    .put("compatible", true)
                    .put("active", current == directory.name))
            }
        }
        return JSONObject().put("active", current).put("installed", entries)
            .put("bundledVersion", store.installedManifest()?.dshVersion ?: JSONObject.NULL)
    }

    /**
     * 安装一个上游 npm 版本。
     *
     * [onProgress] 收到的是阶段进度（阶段名 + 该阶段完成度），不是字节进度：npm 解析依赖、
     * 下 tarball 时不吐可用百分比，界面按阶段画条并显示已用时间。
     */
    fun install(version: String, onProgress: (String, Int) -> Unit = { _, _ -> }) {
        HarnessPackageCatalog.requireVersion(version)
        if (ready(File(versions, version)) != null) {
            onProgress("verify", 90)
            verify(version)
            onProgress("done", 100)
            return
        }
        val entry = HarnessPackageCatalog.list().firstOrNull { it.version == version }
            ?: throw RuntimeFailure("HARNESS_VERSION_UNAVAILABLE", "该版本没有官方 npm 安装包")
        run("install", version, entry.integrity, onProgress = onProgress)
        val directory = File(versions, version)
        val metadata = ready(directory) ?: throw RuntimeFailure("HARNESS_PACKAGE_INVALID", "安装结果不完整")
        write(File(directory, "ready.json"), metadata)
    }

    fun verify(version: String) {
        HarnessPackageCatalog.requireVersion(version)
        val metadata = ready(File(versions, version)) ?: throw RuntimeFailure("HARNESS_PACKAGE_INVALID", "Harness 包缺失或未安装完成")
        run("verify", version)
    }

    /** 先记旧默认值再切换；进程意外退出时下一次启动先恢复旧值。 */
    fun beginSwitch(version: String) {
        if (version != BUNDLED) verify(version)
        prepareDirectories(store)
        write(selection, JSONObject().put("active", version).put("previous", active(store)).put("pending", true))
    }

    fun commitSwitch() {
        val state = read(selection) ?: throw RuntimeFailure("HARNESS_PACKAGE_INVALID", "Harness 切换记录缺失")
        write(selection, JSONObject().put("active", state.getString("active")))
    }

    fun rollbackSwitch(): Boolean {
        val state = read(selection) ?: return false
        if (!state.optBoolean("pending")) return false
        val previous = state.optString("previous", BUNDLED)
        write(selection, JSONObject().put("active", if (previous == BUNDLED) BUNDLED else HarnessPackageCatalog.requireVersion(previous)))
        return true
    }

    fun remove(version: String) {
        HarnessPackageCatalog.requireVersion(version)
        if (active(store) == version) throw RuntimeFailure("HARNESS_VERSION_BUSY", "不能删除默认 Harness 版本")
        run("remove", version)
    }

    /** 重建 profile 对当前核心包的链接，用户插件实体和配置保留。 */
    fun prepareProfile() { run("prepare", selected = true) }

    private fun run(
        operation: String,
        version: String? = null,
        integrity: String? = null,
        selected: Boolean = false,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ) {
        if (store.installedManifest() == null) throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "请先安装 Ubuntu 运行时")
        prepareDirectories(store)
        val scripts = File(store.currentRoot, "root/.dsh-mobile")
        val script = File(scripts, "harness-packages.cjs")
        requirePlainPath(store.currentRoot, script, allowMissing = true)
        val pending = Files.createTempFile(scripts.toPath(), ".harness-packages-", ".tmp").toFile()
        try {
            Os.chmod(pending.path, 0x180)
            context.assets.open("support/harness-packages.cjs").use { source -> pending.outputStream().use { source.copyTo(it) } }
            Os.rename(pending.path, script.path)
        } finally { pending.delete() }
        val argv = listOf("/opt/node/bin/node", "/root/.dsh-mobile/harness-packages.cjs", operation) + listOfNotNull(version, integrity)
        val launcher = if (selected) RuntimeLaunchResolver(context, store, includeCredentials = false) else resolver
        val result = ProcessProbe.run(
            spec = launcher.launch(argv),
            workingDirectory = store.currentRoot,
            timeoutSeconds = if (operation == "install") 1200 else 180,
            outputLimit = 16384,
        ) { line -> progressEvent(line)?.let { event -> onProgress(event.first, event.second) } }
        val payload = runCatching { JSONObject(result.output.trimEnd().lineSequence().lastOrNull().orEmpty()) }.getOrNull()
        if (!result.succeeded || payload == null || payload.has("error")) {
            val code = payload?.optString("error").takeIf { it in ERRORS } ?: "HARNESS_PACKAGE_FAILED"
            val stage = payload?.optString("stage").orEmpty()
            val message = when (code) {
                "HARNESS_ABI_MISMATCH" -> "该 Harness 版本与当前 Node.js 不兼容"
                "HARNESS_DEPENDENCY_MISMATCH" -> "上游核心依赖版本不一致，未激活此版本"
                "HARNESS_INTEGRITY_INVALID" -> "Harness 包完整性校验失败"
                "HARNESS_PROFILE_CONFLICT" -> "会话配置目录存在独立核心包，请先修复插件依赖"
                else -> "Harness 包操作失败，原版本仍保留；请查看运行时诊断后重试"
            }
            throw RuntimeFailure(if (result.timedOut) "HARNESS_PACKAGE_TIMEOUT" else code, STAGE_LABELS[stage]?.let { "停在「$it」阶段：$message" } ?: message)
        }
    }

    companion object {
        const val BUNDLED = "bundled"
        const val GUEST_HOME = "/root/.dsh-mobile/harness-versions"
        private val ERRORS = setOf("HARNESS_ABI_MISMATCH", "HARNESS_DEPENDENCY_MISMATCH", "HARNESS_INTEGRITY_INVALID", "HARNESS_PROFILE_CONFLICT", "HARNESS_INSTALL_FAILED", "HARNESS_PACKAGE_FAILED", "HARNESS_PATH_INVALID", "HARNESS_PACKAGE_INVALID", "HARNESS_PACKAGE_TOO_LARGE", "HARNESS_VERSION_INVALID")

        fun active(store: RuntimeStore): String {
            val home = File(store.runtimeParent, "harness-versions")
            val selected = read(File(home, "selection.json"))?.optString("active", BUNDLED) ?: return BUNDLED
            if (selected == BUNDLED) return BUNDLED
            HarnessPackageCatalog.requireVersion(selected)
            val metadata = ready(File(home, "versions/$selected"))
                ?: throw RuntimeFailure("HARNESS_PACKAGE_INVALID", "默认 Harness 包不可用，请切回内置版本")
            return selected
        }

        /**
         * 宽松读取：只给启动、代次指纹和列表用。选择文件损坏、或选中的包目录已被外部删除时退回内置，
         * 否则一个坏 JSON 会同时挡住 Harness 启动和「切回内置」的设置页入口。
         * 版本管理操作（切换、删除、重建 profile）仍走严格的 [active]。
         */
        fun selectedOrBundled(store: RuntimeStore): String = try {
            active(store)
        } catch (error: Exception) {
            // 元数据损坏时顺手把选择退回内置，让严格的版本管理入口重新可用。
            if (error is RuntimeFailure && error.code == "HARNESS_PACKAGE_INVALID") {
                runCatching { fallBackToBundled(store) }
            }
            BUNDLED
        }

        /** 选中的包已不可用（环境变了或目录被删）时把默认值退回内置，不改动任何包目录。 */
        fun fallBackToBundled(store: RuntimeStore) {
            write(File(File(store.runtimeParent, "harness-versions"), "selection.json"), JSONObject().put("active", BUNDLED))
        }


        private const val PROGRESS_PREFIX = "HARNESS-PROGRESS "

        /** 访客脚本打了哪几个阶段就认哪几个；陌生阶段名一律丢弃，界面不会显示未翻译的键。 */
        private val STAGE_LABELS = mapOf(
            "prepare" to "准备安装目录",
            "resolve" to "解析依赖版本",
            "lock" to "校验依赖锁定",
            "fetch" to "下载并安装依赖",
            "bridge" to "复制安卓桥接插件",
            "native" to "准备原生模块",
            "smoke" to "冒烟测试",
            "record" to "校验并记录指纹",
            "verify" to "复核已安装版本",
            "done" to "完成",
        )

        /** 解析一行阶段输出；其余输出（npm 日志、最终 JSON）返回 null。 */
        internal fun progressEvent(line: String): Pair<String, Int>? {
            if (!line.startsWith(PROGRESS_PREFIX)) return null
            val payload = runCatching { JSONObject(line.removePrefix(PROGRESS_PREFIX)) }.getOrNull() ?: return null
            val stage = payload.optString("stage").takeIf { it in STAGE_LABELS } ?: return null
            return stage to payload.optInt("percent", 0).coerceIn(0, 100)
        }

        fun prepareDirectories(store: RuntimeStore) {
            for (base in listOf(File(store.runtimeParent, "harness-versions"), File(store.runtimeParent, "harness-versions/versions"),
                File(store.currentRoot, "root/.dsh-mobile"), File(store.currentRoot, "root/.dsh-mobile/harness-versions"), File(store.currentRoot, "opt/dsh-bundled"))) {
                val boundary = if (base.toPath().startsWith(store.currentRoot.toPath())) store.currentRoot else store.runtimeParent
                requirePlainPath(boundary, base, allowMissing = true)
                if (!base.isDirectory && !base.mkdirs()) throw RuntimeFailure("HARNESS_PATH_INVALID", "无法准备 Harness 版本目录")
                Os.chmod(base.path, 0x1c0)
            }
        }

        fun mounts(store: RuntimeStore, selected: Boolean): List<ProotBindMount> {
            val home = File(store.runtimeParent, "harness-versions")
            val result = mutableListOf(ProotBindMount(home.absolutePath, GUEST_HOME), ProotBindMount(File(store.currentRoot, "opt/dsh").absolutePath, "/opt/dsh-bundled"))
            if (selected) {
                val version = selectedOrBundled(store)
                if (version != BUNDLED) {
                    val directory = File(home, "versions/$version")
                    requirePlainPath(home, directory)
                    result += ProotBindMount(directory.absolutePath, "/opt/dsh")
                }
            }
            return result
        }

        private fun ready(directory: File): JSONObject? {
            if (Files.isSymbolicLink(directory.toPath())) return null
            return read(File(directory, "ready.json"))?.takeIf { it.optString("version") == directory.name && it.optLong("bytes", -1) >= 0 }
        }

        private fun read(file: File): JSONObject? {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            val parent = file.parentFile ?: throw RuntimeFailure("HARNESS_PATH_INVALID", "Harness 元数据路径无效")
            requirePlainPath(parent.parentFile ?: parent, file)
            if (!file.isFile || file.length() > 65536) throw RuntimeFailure("HARNESS_PACKAGE_INVALID", "Harness 元数据不可用")
            return try { JSONObject(file.readText()) } catch (error: Exception) {
                throw RuntimeFailure("HARNESS_PACKAGE_INVALID", "Harness 元数据损坏", error)
            }
        }

        private fun write(file: File, value: JSONObject) {
            val parent = file.parentFile ?: throw RuntimeFailure("HARNESS_PATH_INVALID", "Harness 状态路径无效")
            requirePlainPath(parent.parentFile ?: parent, file, allowMissing = true)
            if (!parent.isDirectory && !parent.mkdirs()) throw RuntimeFailure("HARNESS_PATH_INVALID", "无法创建 Harness 状态目录")
            val pending = Files.createTempFile(parent.toPath(), ".harness-state-", ".tmp").toFile()
            try {
                Os.chmod(pending.path, 0x180)
                java.io.FileOutputStream(pending).use { it.write(value.toString().toByteArray()); it.fd.sync() }
                Os.rename(pending.path, file.path)
            } finally { pending.delete() }
        }

        private fun requirePlainPath(root: File, target: File, allowMissing: Boolean = false) {
            val boundary = root.toPath().toAbsolutePath().normalize()
            val path = target.toPath().toAbsolutePath().normalize()
            if (!path.startsWith(boundary)) throw RuntimeFailure("HARNESS_PATH_INVALID", "Harness 路径无效")
            var cursor = boundary
            for (part in boundary.relativize(path)) {
                cursor = cursor.resolve(part)
                if (Files.isSymbolicLink(cursor) || (!allowMissing && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS))) {
                    throw RuntimeFailure("HARNESS_PATH_INVALID", "Harness 路径不可用")
                }
            }
        }
    }
}
