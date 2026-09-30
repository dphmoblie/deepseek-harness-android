package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject
import java.util.Base64

/**
 * Shizuku 文件通道的纯策略与请求解析。
 *
 * 文件通道只允许访问投递区的两个固定根目录，不能把它当作任意 Shell 或任意路径
 * 读写接口。路径是相对根目录的 POSIX 路径，文件内容通过有界 Base64 传输。
 */
internal object DeviceFilePolicy {
    const val MAX_PATH_CHARS = 240
    const val MAX_PATH_DEPTH = 32
    const val MAX_COMPONENT_CHARS = 128
    const val MAX_FILE_BYTES = 128 * 1024
    const val MAX_BASE64_CHARS = 4 * ((MAX_FILE_BYTES + 2) / 3) + 4
    const val MAX_PARAM_CHARS = MAX_BASE64_CHARS + MAX_PATH_CHARS + 256
    const val MAX_LIST_ENTRIES = 256

    private val BASE64_PATTERN = Regex("^[A-Za-z0-9+/]*={0,2}$")
    private val FORBIDDEN_PATH_CHARS = setOf(
        '\u0000', '\r', '\n', '\t', '\'', '"', '`', '\\', ';', '|', '&', '$',
        '<', '>', '*', '?', '(', ')', '{', '}', '[', ']', '!', '~',
    )

    /** 只允许将文件通道绑定到投递区；真实目录仍由设备侧脚本做 NoFollow 检查。 */
    enum class Root(val wireValue: String, val absolutePath: String) {
        INBOX("inbox", "/storage/emulated/0/Documents/DSH/inbox"),
        OUTBOX("outbox", "/storage/emulated/0/Documents/DSH/outbox"),
        ;

        companion object {
            fun parse(value: Any?): Root = values().firstOrNull { it.wireValue == value }
                ?: throw invalid("文件根目录不在投递区白名单")
        }
    }

    data class Request(
        val root: Root,
        val path: String,
        val contentBase64: String?,
        val overwrite: Boolean,
    )

    /** 严格解析文件命令参数，拒绝未知字段与错误类型，避免宽松转换。 */
    fun parse(param: String, requireContent: Boolean = false): Request {
        if (param.length !in 2..MAX_PARAM_CHARS) throw invalid("文件请求参数长度无效")
        val json = try {
            JSONObject(param)
        } catch (error: Throwable) {
            throw RuntimeFailure("DEVICE_FILE_INVALID", "文件请求不是合法 JSON", error)
        }
        val allowed = setOf("root", "path", "contentBase64", "overwrite")
        val keys = json.keys()
        while (keys.hasNext()) {
            if (keys.next() !in allowed) throw invalid("文件请求包含未知字段")
        }
        val root = Root.parse(json.opt("root"))
        val rawPath = json.opt("path")
        if (rawPath !is String) throw invalid("文件相对路径格式无效")
        validatePath(rawPath, allowEmpty = true)
        val content = if (json.has("contentBase64")) {
            val raw = json.opt("contentBase64")
            if (raw !is String) throw invalid("文件内容格式无效")
            validateBase64(raw)
            raw
        } else {
            null
        }
        if (requireContent && content == null) throw invalid("文件内容缺失")
        if (json.has("overwrite") && json.opt("overwrite") !is Boolean) {
            throw invalid("文件覆盖选项格式无效")
        }
        return Request(
            root = root,
            path = rawPath,
            contentBase64 = content,
            overwrite = json.optBoolean("overwrite", false),
        )
    }

    fun validatePath(path: String, allowEmpty: Boolean = false) {
        if (path.isEmpty()) {
            if (!allowEmpty) throw invalid("文件相对路径不能为空")
            return
        }
        if (path.length > MAX_PATH_CHARS || path.startsWith('/') || path.endsWith('/') || path.contains("//")) {
            throw invalid("文件相对路径格式无效")
        }
        if (path.any { it.isISOControl() || it in FORBIDDEN_PATH_CHARS }) {
            throw invalid("文件相对路径包含非法字符")
        }
        val components = path.split('/')
        if (components.size > MAX_PATH_DEPTH || components.any { it.isEmpty() || it == "." || it == ".." }) {
            throw invalid("文件相对路径越界")
        }
        if (components.any { it.length > MAX_COMPONENT_CHARS }) throw invalid("文件名过长")
    }

    fun validateBase64(value: String): ByteArray {
        if (value.length > MAX_BASE64_CHARS || !BASE64_PATTERN.matches(value) || value.length % 4 == 1) {
            throw invalid("文件内容不是受支持的 Base64")
        }
        val bytes = try {
            // 先过正则再解码，避免宽松解码器悄悄忽略非法字符。
            Base64.getDecoder().decode(value)
        } catch (error: Throwable) {
            throw RuntimeFailure("DEVICE_FILE_INVALID", "文件内容不是有效 Base64", error)
        }
        if (bytes.size > MAX_FILE_BYTES) throw invalid("文件内容超过 128 KiB 限额")
        return bytes
    }

    private fun invalid(message: String): RuntimeFailure = RuntimeFailure("DEVICE_FILE_INVALID", message)
}
