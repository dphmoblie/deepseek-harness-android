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
    /** 开启「设备文件通道解除投递区目录与大小限制」后的单文件传输上限（4 MiB）。 */
    const val UNRESTRICTED_MAX_FILE_BYTES = 4 * 1024 * 1024
    const val UNRESTRICTED_MAX_BASE64_CHARS = 4 * ((UNRESTRICTED_MAX_FILE_BYTES + 2) / 3) + 4
    const val UNRESTRICTED_MAX_PARAM_CHARS = UNRESTRICTED_MAX_BASE64_CHARS + MAX_PATH_CHARS + 256
    const val MAX_LIST_ENTRIES = 256

    private val BASE64_PATTERN = Regex("^[A-Za-z0-9+/]*={0,2}$")
    private val FORBIDDEN_PATH_CHARS = setOf(
        '\u0000', '\r', '\n', '\t', '\'', '"', '`', '\\', ';', '|', '&', '$',
        '<', '>', '*', '?', '(', ')', '{', '}', '[', ']', '!', '~',
    )

    /** 默认只允许投递区两目录；仅在显式开启 `DEVICE_FILE_UNRESTRICTED` 时放行 `device` 整机通道。 */
    enum class Root(val wireValue: String, val absolutePath: String) {
        INBOX("inbox", "/storage/emulated/0/Documents/DSH/inbox"),
        OUTBOX("outbox", "/storage/emulated/0/Documents/DSH/outbox"),
        DEVICE("device", "/storage/emulated/0"),
        ;

        companion object {
            fun parse(value: Any?, allowUnrestricted: Boolean = false): Root {
                val matched = values().firstOrNull { it.wireValue == value }
                    ?: throw invalid("文件根目录不在投递区白名单")
                if (matched == DEVICE && !allowUnrestricted) {
                    throw invalid("未开启「设备文件通道解除投递区目录与大小限制」，仅允许访问 inbox/outbox")
                }
                return matched
            }
        }
    }

    data class Request(
        val root: Root,
        val path: String,
        val contentBase64: String?,
        val overwrite: Boolean,
    )

    /** 严格解析文件命令参数，拒绝未知字段与错误类型，避免宽松转换。 */
    fun parse(param: String, requireContent: Boolean = false, allowUnrestricted: Boolean = false): Request {
        val maxParamChars = if (allowUnrestricted) UNRESTRICTED_MAX_PARAM_CHARS else MAX_PARAM_CHARS
        val maxFileBytes = if (allowUnrestricted) UNRESTRICTED_MAX_FILE_BYTES else MAX_FILE_BYTES
        if (param.length !in 2..maxParamChars) throw invalid("文件请求参数长度无效")
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
        val root = Root.parse(json.opt("root"), allowUnrestricted = allowUnrestricted)
        val rawPath = json.opt("path")
        if (rawPath !is String) throw invalid("文件相对路径格式无效")
        validatePath(rawPath, allowEmpty = true, allowAbsolute = allowUnrestricted && root == Root.DEVICE)
        val content = if (json.has("contentBase64")) {
            val raw = json.opt("contentBase64")
            if (raw !is String) throw invalid("文件内容格式无效")
            validateBase64(raw, maxBytes = maxFileBytes)
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

    fun validatePath(path: String, allowEmpty: Boolean = false, allowAbsolute: Boolean = false) {
        if (path.isEmpty()) {
            if (!allowEmpty) throw invalid("文件相对路径不能为空")
            return
        }
        if (allowAbsolute && path == "/") return
        if (path.length > MAX_PATH_CHARS || (!allowAbsolute && path.startsWith('/')) || path.endsWith('/') || path.contains("//")) {
            throw invalid("文件相对路径格式无效")
        }
        if (path.any { it.isISOControl() || it in FORBIDDEN_PATH_CHARS }) {
            throw invalid("文件相对路径包含非法字符")
        }
        val body = if (allowAbsolute && path.startsWith('/')) path.removePrefix("/") else path
        val components = body.split('/')
        if (components.size > MAX_PATH_DEPTH || components.any { it.isEmpty() || it == "." || it == ".." }) {
            throw invalid("文件相对路径越界")
        }
        if (components.any { it.length > MAX_COMPONENT_CHARS }) throw invalid("文件名过长")
    }

    fun validateBase64(value: String, maxBytes: Int = MAX_FILE_BYTES): ByteArray {
        val maxBase64 = 4 * ((maxBytes + 2) / 3) + 4
        if (value.length > maxBase64 || !BASE64_PATTERN.matches(value) || value.length % 4 == 1) {
            throw invalid("文件内容不是受支持的 Base64")
        }
        val bytes = try {
            // 先过正则再解码，避免宽松解码器悄悄忽略非法字符。
            Base64.getDecoder().decode(value)
        } catch (error: Throwable) {
            throw RuntimeFailure("DEVICE_FILE_INVALID", "文件内容不是有效 Base64", error)
        }
        if (bytes.size > maxBytes) {
            val label = if (maxBytes >= 1024 * 1024) "${maxBytes / (1024 * 1024)} MiB" else "${maxBytes / 1024} KiB"
            throw invalid("文件内容超过 $label 限额")
        }
        return bytes
    }

    private fun invalid(message: String): RuntimeFailure = RuntimeFailure("DEVICE_FILE_INVALID", message)
}
