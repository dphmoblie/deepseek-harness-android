package io.deepseekharness.mobile.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * 运行时自检：把「沙箱不可用 / 真实 confine exec 失败 / PTY（node-pty）本身失败」一次切开。
 *
 * 背景：设备上 dsh 的 `bash` 工具持续报 `PTY shell exited during startup`，而应用自己的
 * Ubuntu 终端（同一条 PRoot 启动、不经 PTY、不挂沙箱）是正常的；dsh 又用 `landlock-run --probe`
 * 探测沙箱，探测通过不代表真实执行能成 —— 所以三种可能必须各测一次，而不是靠推断。
 *
 * 分工：访客侧脚本（[SCRIPT_PATH]）负责一切需要访客环境的事（探针、真实 exec、两组 PTY 冒烟、
 * 目录可写性、rg 执行位），宿主侧只补两件访客回答不了的事（可用空间、dsh 版本），
 * 并把脚本的一行 JSON 再做一遍白名单校验。
 *
 * 安全边界：
 *  - 解析器**绝不含凭据**（`includeCredentials = false`）：自检只跑 node 脚本与探针；
 *  - 回传给 WebView 的只有封闭枚举与计数，**路径、文件内容与原始报错文本一律不外传**；
 *  - `dshVersion` 只回传通过 semver 形态校验的版本号，读不到就是 null。
 */
class RuntimeSelfCheck(context: Context, private val store: RuntimeStore) {
    private val appContext = context.applicationContext

    /** 绝不含凭据：自检进程不需要模型 API Key，最小权限。 */
    private val resolver = RuntimeLaunchResolver(context, store, includeCredentials = false)

    private val directory get() = File(store.currentRoot, "root/.dsh-mobile")

    /**
     * 执行一次自检并返回可直接回传 WebView 的载荷。
     *
     * `check` 只读；`repair` 只补可执行位与创建附件目录。运行时未安装时直接报
     * `RUNTIME_NOT_INSTALLED`，不去猜一份不存在的根目录。
     */
    fun run(operation: String): JSObject {
        val selected = RuntimeSelfCheckPolicy.operation(operation)
            ?: throw RuntimeFailure("SELF_CHECK_INPUT_INVALID", "自检操作无效")
        if (store.installedManifest() == null) {
            throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "请先安装 Ubuntu 运行时")
        }
        prepareScript()
        return try {
            val payload = execute(selected)
            if (selected == RuntimeSelfCheckPolicy.CHECK) checkPayload(payload) else repairPayload(payload)
        } catch (failure: RuntimeFailure) {
            // 失败也要留痕：脚本崩了、超时了、载荷不合法，都只能靠这条受控码在设备上区分。
            recordDenied(failure.code)
            throw failure
        } catch (error: Throwable) {
            recordDenied(DENIED_CODE)
            throw RuntimeFailure(DENIED_CODE, "运行时自检失败，请稍后重试", error)
        }
    }

    /** 跑一次访客脚本并取**最后一行**的有界 JSON；运行器可能附带诊断行，那些一律不回传。 */
    private fun execute(operation: String): JSObject {
        val argv = listOf(NODE_BINARY, SCRIPT_PATH, operation)
        val result = ProcessProbe.run(
            resolver.launch(argv),
            store.currentRoot,
            TIMEOUT_SECONDS,
            outputLimit = OUTPUT_LIMIT,
        )
        val payload = try {
            val line = result.output.trimEnd().lineSequence().lastOrNull().orEmpty()
            if (line.length > MAX_PAYLOAD_CHARS) throw IllegalArgumentException()
            JSObject(line)
        } catch (_: Exception) {
            throw RuntimeFailure(DENIED_CODE, "运行时自检失败，请稍后重试")
        }
        // 脚本用 `{"error":"<受控码>"}` 表达自己的失败；无论哪种失败，对界面都是同一句可重试的提示。
        if (!result.succeeded || payload.has("error")) {
            throw RuntimeFailure(DENIED_CODE, "运行时自检失败，请稍后重试")
        }
        return payload
    }

    /** 自检载荷：`checks` 逐条过白名单，宿主侧再补可用空间与 dsh 版本。 */
    private fun checkPayload(payload: JSObject): JSObject {
        val checks = RuntimeSelfCheckPolicy.sanitize(rawChecks(payload))
        // 一条合法条目都没有说明载荷整体不可信：宁可报一次可重试的失败，也不回一张空表。
        if (checks.isEmpty()) throw RuntimeFailure(DENIED_CODE, "运行时自检失败，请稍后重试")
        val summary = RuntimeSelfCheckPolicy.summarize(checks)
        val fields = mutableMapOf(
            "result" to if (summary.failedCount > 0) "failed" else "ok",
            "count" to summary.failedCount.toString(),
        )
        summary.firstFailureCode?.let { code -> fields["code"] = code }
        store.diagnostics.record(
            if (summary.failedCount > 0) DiagnosticLevel.WARN else DiagnosticLevel.INFO,
            DiagnosticEvent.SELF_CHECK,
            fields,
        )
        val array = JSArray()
        checks.forEach { item ->
            array.put(
                JSObject().apply {
                    put("id", item.id)
                    put("status", item.status)
                    item.code?.let { code -> put("code", code) }
                },
            )
        }
        return JSObject()
            .put("operation", RuntimeSelfCheckPolicy.CHECK)
            // 仅报告启动默认值，不能据此断言当前会话的权限；会话可覆盖它。
            .put("harnessPermissionMode", store.harnessPermissionMode().wireValue)
            .put("availableBytes", availableBytes())
            // 显式回 null（而不是省略键）：界面据此区分「读不到版本」与「字段缺失」。
            .put("dshVersion", dshVersion() ?: JSONObject.NULL)
            .put("checks", array)
    }

    /** 修复载荷：只回计数。计数不可信就整次失败，绝不把编造的数字写进界面。 */
    private fun repairPayload(payload: JSObject): JSObject {
        val repaired = if (payload.has("repaired")) payload.optInt("repaired", -1) else null
        val candidates = if (payload.has("candidates")) payload.optInt("candidates", -1) else null
        val summary = RuntimeSelfCheckPolicy.repairSummary(repaired, candidates)
            ?: throw RuntimeFailure(DENIED_CODE, "运行时自检失败，请稍后重试")
        store.diagnostics.record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.SELF_CHECK,
            // repair 的 count 是实际改动数（不是失败项数），枚举注释里写明了这个区别。
            mapOf("result" to "ok", "count" to summary.repaired.toString()),
        )
        return JSObject()
            .put("operation", RuntimeSelfCheckPolicy.REPAIR)
            .put("availableBytes", availableBytes())
            .put("repaired", summary.repaired)
            .put("candidates", summary.candidates)
    }

    /** 把脚本回的 `checks` 读成未校验条目；结构不对的行直接丢掉，交给策略层判定。 */
    private fun rawChecks(payload: JSObject): List<RuntimeSelfCheckPolicy.RawCheck> {
        val array = payload.optJSONArray("checks") ?: return emptyList()
        val limit = minOf(array.length(), RuntimeSelfCheckPolicy.MAX_CHECKS)
        return (0 until limit).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            RuntimeSelfCheckPolicy.RawCheck(
                id = row.optString("id", ""),
                status = row.optString("status", ""),
                // 必须显式区分「没有 code 键」与「code 是 null」：org.json 的 optString 会把
                // JSON null 读成字符串 "null"，那会被策略层当成一个非法码处理。
                code = if (row.has("code") && !row.isNull("code")) row.optString("code", "") else null,
            )
        }
    }

    /**
     * 宿主侧可用空间：直接读运行时根所在文件系统，不需要访客配合。
     * 读不到就回 0（界面显示为未知），不回退到任何路径或异常文本。
     */
    private fun availableBytes(): Long = try {
        StatFs(store.currentRoot.absolutePath).availableBytes
    } catch (_: Throwable) {
        0L
    }

    /**
     * 访客里实际安装的 dsh 版本：读 `package.json` 的 `version`，**只有通过 semver 形态校验才回传**，
     * 否则回 null。**绝不回传路径**，也不回传未校验的原文。
     *
     * 读取规则与运行时版本列表共用 [RuntimeDshVersion]，两处对「什么算读到了版本」必须完全一致。
     */
    private fun dshVersion(): String? = RuntimeDshVersion.read(store.currentRoot)

    /**
     * 把 APK 资产里的自检脚本安全地放到访客根下（`root/.dsh-mobile/runtime-self-check.cjs`），
     * 做法与插件管理器一致：固定目录逐层拒绝符号链接、临时文件独占创建、`Os.rename` 原子替换。
     * 每次调用都会覆盖：脚本必须与当前 APK 里的 Kotlin 侧白名单同版本，不存在「沿用旧脚本」的情形。
     */
    private fun prepareScript() {
        try {
            for (folder in listOf(File(store.currentRoot, "root"), directory)) {
                if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(folder.toPath())
                if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) invalid()
            }
            Os.chmod(directory.absolutePath, 0x1c0)
            val target = File(directory, SCRIPT_NAME)
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) invalid()
            val pending = Files.createTempFile(directory.toPath(), ".runtime-self-check-", ".tmp")
            try {
                Os.chmod(pending.toString(), 0x180)
                appContext.assets.open("support/$SCRIPT_NAME").use { input ->
                    Files.newOutputStream(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                        input.copyTo(output)
                    }
                }
                Os.rename(pending.toString(), target.absolutePath)
            } finally {
                Files.deleteIfExists(pending)
            }
        } catch (failure: RuntimeFailure) {
            throw failure
        } catch (error: Exception) {
            throw RuntimeFailure(DENIED_CODE, "无法准备运行时自检脚本", error)
        }
    }

    /** 失败留痕：`result=denied` 与「检查项确实失败」（`result=failed`）在日志里必须能分开读。 */
    private fun recordDenied(code: String) {
        store.diagnostics.record(
            DiagnosticLevel.WARN,
            DiagnosticEvent.SELF_CHECK,
            mapOf(
                "result" to "denied",
                "code" to (code.takeIf { CONTROLLED_CODE.matches(it) } ?: DENIED_CODE),
                "count" to "0",
            ),
        )
    }

    private fun invalid(): Nothing = throw RuntimeFailure(DENIED_CODE, "无法准备运行时自检脚本")

    private companion object {
        /** 访客里的固定位置：node 与脚本都在访客根下，与插件管理器的调用形态一致。 */
        const val NODE_BINARY = "/opt/node/bin/node"
        const val SCRIPT_NAME = "runtime-self-check.cjs"
        const val SCRIPT_PATH = "/root/.dsh-mobile/$SCRIPT_NAME"

        /** dsh 安装清单的读取规则（路径与上限）统一在 [RuntimeDshVersion]，这里不再重复定义。 */

        /** 自检要跑探针与两组 PTY 冒烟（各 5 秒上限），给足时间但仍是有限等待。 */
        const val TIMEOUT_SECONDS = 45L
        const val OUTPUT_LIMIT = 256 * 1024

        /** 单行载荷上限：自检结果只有枚举与计数，超过这个长度就不是我们的脚本。 */
        const val MAX_PAYLOAD_CHARS = 64 * 1024

        const val DENIED_CODE = "SELF_CHECK_FAILED"

        /** 与 DiagnosticPolicy 的 code 字段同形，保证写入的诊断字段一定合法。 */
        val CONTROLLED_CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")
    }
}
