package io.deepseekharness.mobile.runtime

import android.content.Context
import android.system.Os
import com.getcapacitor.JSObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** 不启动 Harness 的配置管理通道；不向管理进程传递模型或设备桥接凭据。 */
class RuntimePluginManager(context: Context, private val store: RuntimeStore) {
    private val appContext = context.applicationContext
    private val resolver = RuntimeLaunchResolver(context, store, includeCredentials = false)
    private val directory get() = File(store.currentRoot, "root/.dsh-mobile")

    fun recoverIfNeeded() {
        if (File(directory, "plugin-manager/transaction.json").exists()) run("recover", null, null, null)
    }

    fun run(operation: String, id: String?, enabled: Boolean?, childId: String?): JSObject {
        if (operation !in setOf("list", "enable", "child", "update", "recover")) invalid()
        if (operation !in setOf("list", "recover")) {
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
        val result = ProcessProbe.run(resolver.launch(argv), store.currentRoot, if (operation == "update") 250 else 30, outputLimit = 256 * 1024)
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
            throw RuntimeFailure(code, "插件操作失败，请检查运行时状态后重试")
        }
        if (operation != "recover" && payload.optJSONArray("plugins") == null) {
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
        val ERROR_CODES = setOf("PLUGIN_INPUT_INVALID", "PLUGIN_PATH_INVALID", "PLUGIN_CONFIG_INVALID", "PLUGIN_NOT_FOUND", "PLUGIN_PROTECTED", "PLUGIN_RECOVERY_FAILED", "PLUGIN_UPDATER_MISSING", "PLUGIN_UPDATE_FAILED", "PLUGIN_LINK_UNSUPPORTED", "PLUGIN_DEPENDENCY_UNSUPPORTED", "PLUGIN_GROUP_DISABLED", "PLUGIN_OPERATION_FAILED")
    }
}
