package io.deepseekharness.mobile.runtime

import android.content.Context
import android.system.Os
import com.getcapacitor.JSObject
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** 不启动 Harness 的配置管理通道；不向管理进程传递模型或设备桥接凭据。 */
class RuntimePluginManager(context: Context, private val store: RuntimeStore) {
    private val appContext = context.applicationContext
    private val resolver = RuntimeLaunchResolver(context, store, includeCredentials = false)
    private val directory get() = File(store.currentRoot, "root/.dsh-mobile")

    /** 最近一次**成功**修复所对应的运行时代次指纹；见 [repairInstalledIfNeeded]。 */
    @Volatile
    private var repairedGeneration: String? = null

    /** 最近一次**失败**所对应的运行时代次；配合下面时刻构成失败冷却窗口。 */
    @Volatile
    private var repairFailureGeneration: String? = null

    /** 最近一次修复失败的时刻；从未失败时为 0。 */
    @Volatile
    private var repairFailureAtMillis: Long = 0L

    fun recoverIfNeeded() {
        if (File(directory, "plugin-manager/transaction.json").exists()) run("recover", null, null, null)
    }

    /**
     * 已安装插件的运行时链接修复（幂等自愈，不需要重新下载插件）。
     *
     * 需要它的两种坏形态：插件目录会被运行时升级保留，而里面指向运行时包的绝对链接带着旧
     * 版本号与 peer 哈希，新根目录里已经不存在（悬空链接）；早期安装留下的真实副本则会让
     * 运行时里出现第二份 dsh-tools —— 它的 Symbol 是每个物理模块副本各造一个，身份失配后
     * `ctx.tools[调度器 Symbol]` 取到 undefined，**每一次工具调用**都会失败。
     *
     * 同一运行时代次只做一次（指纹为 runtimeId + 版本 + rootfs 摘要）：没有换运行时就不会
     * 产生新的坏形态。失败不抛给调用方，并进入冷却窗口（判定见 [PluginRepairPolicy]）——
     * 插件列表与 Harness 启动不应因为一次自愈失败而反复中断。
     */
    fun repairInstalledIfNeeded() {
        val generation = store.installedManifest()?.let { "${it.runtimeId}|${it.version}|${it.rootfs.sha256}" } ?: return
        val now = System.currentTimeMillis()
        val attempt = PluginRepairPolicy.shouldAttempt(
            generation = generation,
            repairedGeneration = repairedGeneration,
            lastFailureGeneration = repairFailureGeneration,
            lastFailureAtMillis = repairFailureAtMillis,
            nowMillis = now,
        )
        if (!attempt) return
        try {
            val summary = run("repair", null, null, null)
            repairedGeneration = generation
            // 只记计数，让下次导出的诊断日志能直接回答「设备上到底有没有重复的运行时包」：
            //  - files = 修复动作实际扫到了多少个包条目；
            //  - count = 其中多少个被重新指向运行时实例（**count > 0 即确实存在重复副本**）；
            //  - result = denied 表示有包没能修复（拒绝或失败），需要人工介入。
            val linked = summary.optInt("linked", 0)
            val unfixed = summary.optInt("failed", 0) + summary.optInt("refused", 0)
            store.diagnostics.record(
                DiagnosticLevel.INFO,
                DiagnosticEvent.REPAIR,
                mapOf(
                    "result" to if (unfixed > 0) "denied" else "ok",
                    "count" to linked.toString(),
                    "files" to summary.optInt("scanned", 0).toString(),
                ),
            )
        } catch (_: Exception) {
            // run() 已把失败转成受控错误码；自愈是尽力而为，因此不抛出。
            // 记录失败时刻并进入冷却：失败通常是确定性的，立刻重试只会让下一次
            // 读取插件列表再白等一次完整超时。
            store.diagnostics.record(
                DiagnosticLevel.WARN,
                DiagnosticEvent.REPAIR,
                mapOf("result" to "failed", "count" to "0"),
            )
            repairFailureGeneration = generation
            repairFailureAtMillis = now
        }
    }

    fun run(operation: String, id: String?, enabled: Boolean?, childId: String?): JSObject {
        if (operation !in setOf("list", "enable", "child", "update", "recover", "repair")) invalid()
        if (operation !in setOf("list", "recover", "repair")) {
            if (id == null || id.length !in 1..214 || !PACKAGE.matches(id) || ".." in id) invalid()
            if (operation in setOf("enable", "child") && enabled == null) invalid()
            if (operation == "child" && (childId == null || !ENTRY.matches(childId))) invalid()
        }
        if (store.installedManifest() == null) throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "请先安装 Ubuntu 运行时")
        prepareScript()
        val argv = mutableListOf("/opt/node/bin/node", "/root/.dsh-mobile/plugin-manager.cjs", operation)
        if (id != null) argv.add(id)
        if (enabled != null) argv.add(enabled.toString())
        if (childId != null) argv.add(childId)
        val timeoutSeconds = when (operation) {
            "update" -> 250L
            // 修复要遍历已安装的插件目录，给足时间但仍是有限等待。
            "repair" -> 90L
            else -> 30L
        }
        val result = ProcessProbe.run(resolver.launch(argv), store.currentRoot, timeoutSeconds, outputLimit = 256 * 1024)
        val payload = try {
            // 运行器可能输出诊断行；只接受最后一行的有界 JSON，不回传原始日志。
            val line = result.output.trimEnd().lineSequence().lastOrNull().orEmpty()
            if (line.length > 220000) throw IllegalArgumentException()
            JSObject(line)
        } catch (_: Exception) {
            throw RuntimeFailure("PLUGIN_OPERATION_FAILED", "插件操作失败，请重试")
        }
        if (!result.succeeded || payload.has("error")) {
            val code = payload.optString("error").takeIf { it in ERROR_CODES } ?: "PLUGIN_OPERATION_FAILED"
            // 受控详情：只接受包名/版本/范围字符集。它来自 guest 脚本，必须在此再校验一次，
            // 否则路径或异常原文会随消息一路回到 WebView。字符集允许 `/`（作用域包名需要），
            // 因此额外拒绝以 `/`、`~`、`.` 开头以及含 `//` 的取值，挡住路径形态。
            val detail = payload.optString("detail").takeIf { candidate ->
                DETAIL.matches(candidate) &&
                    !candidate.startsWith("/") && !candidate.startsWith("~") && !candidate.startsWith(".") &&
                    !candidate.contains("//")
            }
            throw RuntimeFailure(code, detail ?: "插件操作失败，请检查运行时状态后重试")
        }
        if (operation in setOf("list", "enable", "child", "update") && payload.optJSONArray("plugins") == null) {
            throw RuntimeFailure("PLUGIN_OPERATION_FAILED", "插件返回数据无效")
        }
        // 修复只回传计数（处理了多少个包、失败多少个），没有插件清单。
        if (operation == "repair" && payload.optInt("scanned", -1) < 0) {
            throw RuntimeFailure("PLUGIN_OPERATION_FAILED", "插件返回数据无效")
        }
        return payload
    }

    private fun prepareScript() {
        try {
            // 安全校验：固定目录逐层拒绝符号链接；脚本来自 APK，临时文件独占创建。
            for (folder in listOf(File(store.currentRoot, "root"), directory)) {
                if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(folder.toPath())
                if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) invalid()
            }
            Os.chmod(directory.absolutePath, 0x1c0)
            val target = File(directory, "plugin-manager.cjs")
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) invalid()
            val pending = Files.createTempFile(directory.toPath(), ".plugin-manager-", ".tmp")
            try {
                Os.chmod(pending.toString(), 0x180)
                appContext.assets.open("support/plugin-manager.cjs").use { input ->
                    Files.newOutputStream(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output -> input.copyTo(output) }
                }
                Os.rename(pending.toString(), target.absolutePath)
            } finally { Files.deleteIfExists(pending) }
        } catch (failure: RuntimeFailure) { throw failure }
        catch (error: Exception) { throw RuntimeFailure("PLUGIN_OPERATION_FAILED", "无法准备插件管理器", error) }
    }

    private fun invalid(): Nothing = throw RuntimeFailure("PLUGIN_INPUT_INVALID", "插件参数或路径无效")

    private companion object {
        val PACKAGE = Regex("^(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")
        val ENTRY = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        /** 受控详情：包名、版本号与 semver 范围字符集；拒绝引号、反斜杠、冒号与控制字符。 */
        val DETAIL = Regex("^[A-Za-z0-9@/._+, =!<>~^|()\\-]{1,300}$")
        val ERROR_CODES = setOf("PLUGIN_INPUT_INVALID", "PLUGIN_PATH_INVALID", "PLUGIN_CONFIG_INVALID", "PLUGIN_NOT_FOUND", "PLUGIN_PROTECTED", "PLUGIN_RECOVERY_FAILED", "PLUGIN_UPDATER_MISSING", "PLUGIN_UPDATE_FAILED", "PLUGIN_LINK_UNSUPPORTED", "PLUGIN_DEPENDENCY_UNSUPPORTED", "PLUGIN_ENGINE_UNSUPPORTED", "PLUGIN_GROUP_DISABLED", "PLUGIN_OPERATION_FAILED")
    }
}
