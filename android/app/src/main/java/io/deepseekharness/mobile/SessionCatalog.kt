package io.deepseekharness.mobile

import android.content.Context
import android.system.Os
import com.getcapacitor.JSObject
import io.deepseekharness.mobile.runtime.ProcessProbe
import io.deepseekharness.mobile.runtime.RuntimeLaunchResolver
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * 壳页「会话工作区」的元数据载荷：把访客脚本的输出折算成回传 WebView 的**最小集合**。
 *
 * 这一层是白名单闸门（`SessionCatalogTest` 逐条钉住）：只有 `id`、`title`、`updatedAt`
 * 三个字段能出去，其余键（`cwd`、路径、正文、snippet、事件对象……）**结构性地**不存在于输出里，
 * 而不是「靠过滤掉」。载荷不可信时返回 `null`，由调用方折算成受控错误码，
 * **绝不**回一个空列表——那会让界面把一次失败显示成「真的没有会话」。
 *
 * 纯逻辑、不碰 Android API，因此可以在本机 JVM 单测里直接覆盖。
 */
internal object SessionCatalogPayload {
    /** 回传条数上限：与访客脚本的 `MAX_LIMIT` 一致。 */
    const val MAX_SESSIONS = 50

    /** 标题上限（UTF-16 字符数）：宿主侧最长 80 字节，留足余量即可判定为不可信。 */
    const val MAX_TITLE_CHARS = 200

    /** 最大载荷字符数：访客脚本自身限 64 KiB，超出即视为不可信。 */
    const val MAX_PAYLOAD_CHARS = 64 * 1024

    /** 时间戳上限（毫秒，2100-01-01）：明显越界的取值视为不可信。 */
    const val MAX_TIMESTAMP_MILLIS = 4_102_444_800_000L

    /** 通用失败：脚本没跑起来、输出不可解析、载荷不可信。 */
    const val FAILED = "SESSION_CATALOG_FAILED"

    /** 读取超时：**单独一个码**，界面才能把「读得太慢」与「读不了」区分开。 */
    const val TIMEOUT = "SESSION_CATALOG_TIMEOUT"

    /** 运行时尚未安装：这不是失败，是「还没有可读的会话目录」。 */
    const val NOT_INSTALLED = "RUNTIME_NOT_INSTALLED"

    /**
     * 会话标识的形态约束：拒绝控制字符、斜杠、反斜杠与空白，长度 1..200，且不以 `.` 开头。
     * 这是**危险字符**约束而不是窄字符集白名单：会话 id 由 dsh 生成，形态可能变化，
     * 过窄的白名单会让真实会话整条消失；而上述字符一旦进入 id，它就有了路径或注入的形态。
     */
    private val FORBIDDEN_IN_IDENTIFIER = Regex("[\\u0000-\\u001F\\u007F/\\\\\\s]")

    /** 标题里的控制字符：出现即丢掉整条标题（不截断、不转义，宁可显示「未命名会话」）。 */
    private val CONTROL_IN_TITLE = Regex("[\\u0000-\\u001F\\u007F]")

    /** 受控错误字段：只有错误码，没有原始文本，也没有语言相关的文案（文案由壳页决定）。 */
    fun unavailable(code: String): JSONObject = JSONObject().put("error", code)

    /**
     * 访客进程的结果 → 回传字段。
     *
     * 失败一律折算成受控错误码；`succeeded` 与 `timedOut` 的判定口径与
     * `RuntimePluginManager`/`RuntimeSelfCheck` 一致（只接受最后一行的有界 JSON）。
     */
    fun fromProbe(succeeded: Boolean, timedOut: Boolean, output: String): JSONObject {
        if (timedOut) return unavailable(TIMEOUT)
        if (!succeeded) return unavailable(FAILED)
        val line = output.trimEnd().lineSequence().lastOrNull().orEmpty()
        if (line.isEmpty() || line.length > MAX_PAYLOAD_CHARS) return unavailable(FAILED)
        val payload = try {
            JSONObject(line)
        } catch (_: Exception) {
            return unavailable(FAILED)
        }
        if (payload.has("error")) return unavailable(FAILED)
        return sanitize(payload) ?: unavailable(FAILED)
    }

    /**
     * 载荷白名单投影；返回 `null` 表示载荷不可信（缺 `sessions`、类型不对、条数超上限、
     * 或**一条都没能通过校验**）。
     */
    fun sanitize(payload: JSONObject): JSONObject? {
        val raw = payload.optJSONArray("sessions") ?: return null
        val sessions = JSONArray()
        var index = 0
        while (index < raw.length() && index < MAX_SESSIONS) {
            val entry = raw.optJSONObject(index)
            index += 1
            if (entry == null) continue
            val id = readIdentifier(entry) ?: continue
            val updatedAt = readTimestamp(entry) ?: continue
            sessions.put(
                JSONObject()
                    .put("id", id)
                    .put("title", readTitle(entry))
                    .put("updatedAt", updatedAt),
            )
        }
        // 有会话却一条都没通过校验 → 载荷与约定不符，按「读不到」处理，不能显示成「没有会话」。
        if (raw.length() > 0 && sessions.length() == 0) return null
        return JSONObject()
            .put("sessions", sessions)
            .put("truncated", payload.optBoolean("truncated", false) || raw.length() > sessions.length())
    }

    private fun readIdentifier(entry: JSONObject): String? {
        if (!entry.has("id")) return null
        val id = entry.optString("id")
        if (id.isEmpty() || id.length > 200) return null
        if (id.startsWith(".")) return null
        if (FORBIDDEN_IN_IDENTIFIER.containsMatchIn(id)) return null
        return id
    }

    private fun readTimestamp(entry: JSONObject): Long? {
        if (!entry.has("updatedAt")) return null
        val updatedAt = entry.optLong("updatedAt", -1L)
        return updatedAt.takeIf { it in 0..MAX_TIMESTAMP_MILLIS }
    }

    /** 标题取不到时回空串：界面据此显示「未命名会话」，而不是拿 id 或时间冒充标题。 */
    private fun readTitle(entry: JSONObject): String {
        if (!entry.has("title")) return ""
        val title = entry.optString("title")
        if (title.isEmpty() || title.length > MAX_TITLE_CHARS) return ""
        if (CONTROL_IN_TITLE.containsMatchIn(title)) return ""
        return title
    }
}

/**
 * 会话目录「只读元数据」通道：宿主在访客里跑一段只读脚本，取回会话的 id / 标题 / 最近更新时间。
 *
 * 为什么不是宿主直接读会话文件：会话日志默认 zstd 压缩（`dsh-base` 未配 `compression`），
 * 宿主 Kotlin 侧读不了；而 `node:zlib` 自带 zstd，与后端同源。所以这里复用
 * `RuntimeSelfCheck`/`RuntimePluginManager` 已有的**同一条投递与执行通道**
 * （APK 资产 → 访客 `/root/.dsh-mobile/` → `/opt/node/bin/node` → 一行 JSON），
 * 不新造沙箱、不新造明文通道、也不把凭据交给这个进程（`includeCredentials = false`）。
 *
 * 隐私边界：脚本只回元数据，标题来自日志里最后一条 `session/title` 事件；正文、消息片段、
 * `cwd` 与会话文件路径都不进 WebView（见 [SessionCatalogPayload] 与该脚本头部注释）。
 *
 * 失败语义：**读不到就报读不到**。运行时未安装 → `RUNTIME_NOT_INSTALLED`；
 * 超时 → `SESSION_CATALOG_TIMEOUT`；其余 → `SESSION_CATALOG_FAILED`。
 * 壳页据此显示空态 + 一句诚实说明，而不是把失败显示成「暂无会话」。
 *
 * 不写审计也不写诊断：这是只读查询，且现有的 `DiagnosticEvent` 枚举没有对应语义
 * （不新增枚举是为了不动 `runtime` 包）。
 */
class SessionCatalog(context: Context, private val store: RuntimeStore) {
    private val appContext = context.applicationContext

    /** 绝不含凭据：这段脚本只需要读会话目录。 */
    private val resolver = RuntimeLaunchResolver(context, store, includeCredentials = false)
    private val directory get() = File(store.currentRoot, "root/.dsh-mobile")

    /**
     * 读取会话清单。
     *
     * 返回成功载荷 `{sessions:[{id,title,updatedAt}],truncated}` 或受控错误 `{error:"<码>"}`；
     * 两种情况都走 `resolve`（不是 `reject`），壳页才能在不依赖异常通道的前提下
     * 把「读不到」与「没有会话」区分开。
     */
    fun list(): JSObject {
        if (store.installedManifest() == null) {
            return JSObject(SessionCatalogPayload.unavailable(SessionCatalogPayload.NOT_INSTALLED).toString())
        }
        return try {
            prepareScript()
            // 上限 50 条、总预算 3 秒（脚本内）＋硬超时 6 秒（这里）；有界输出，取最后一行 JSON。
            val result = ProcessProbe.run(
                resolver.launch(listOf(NODE_BINARY, SCRIPT_PATH, MAX_SESSIONS.toString())),
                store.currentRoot,
                TIMEOUT_SECONDS,
                outputLimit = OUTPUT_LIMIT,
            )
            JSObject(SessionCatalogPayload.fromProbe(result.succeeded, result.timedOut, result.output).toString())
        } catch (_: Exception) {
            // 投递失败、解析器起不来、路径不可信……都折算成同一个受控码：不回原始异常文本。
            JSObject(SessionCatalogPayload.unavailable(SessionCatalogPayload.FAILED).toString())
        }
    }

    /**
     * 把 APK 资产里的脚本投放到访客的固定目录（与 `RuntimeSelfCheck`/`RuntimePluginManager` 同款）：
     * 逐层拒绝符号链接、临时文件独占创建、`Os.rename` 原子替换，每次都覆盖（脚本必须与宿主同版本）。
     */
    private fun prepareScript() {
        for (folder in listOf(File(store.currentRoot, "root"), directory)) {
            if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(folder.toPath())
            if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("会话目录不可用")
            }
        }
        Os.chmod(directory.absolutePath, 0x1c0)
        val target = File(directory, SCRIPT_NAME)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("会话脚本位置不可用")
        }
        val pending = Files.createTempFile(directory.toPath(), ".session-catalog-", ".tmp")
        try {
            Os.chmod(pending.toString(), 0x180)
            appContext.assets.open("support/$SCRIPT_NAME").use { input ->
                Files.newOutputStream(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output -> input.copyTo(output) }
            }
            Os.rename(pending.toString(), target.absolutePath)
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    private companion object {
        /** 访客内的 Node 与脚本路径：与既有资产脚本同一套约定。 */
        const val NODE_BINARY = "/opt/node/bin/node"
        const val SCRIPT_NAME = "session-catalog.cjs"
        const val SCRIPT_PATH = "/root/.dsh-mobile/$SCRIPT_NAME"
        const val MAX_SESSIONS = SessionCatalogPayload.MAX_SESSIONS

        /** 硬超时（秒）：脚本内预算 3 秒，这里留一倍余量给 proot 启动与 zstd 解压。 */
        const val TIMEOUT_SECONDS = 6L

        /** 输出上限（字节）：脚本自身限 64 KiB，取同量级，超出的尾部不会进入解析。 */
        const val OUTPUT_LIMIT = 64 * 1024
    }
}
