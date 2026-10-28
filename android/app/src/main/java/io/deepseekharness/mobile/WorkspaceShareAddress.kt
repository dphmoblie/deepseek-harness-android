package io.deepseekharness.mobile

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 解析预览面板发来的 `dsh-share://file?address=…` 请求。
 *
 * 前端只负责从页面里取出预览资源的 `dsh-resource://file/session/<会话>/<路径>` 地址；
 * 「这个路径能不能分享」必须由原生判定——页面里的路径同样可能被注入或篡改。
 * 这里先把地址还原成工作区相对路径（[relativePaths] 会给出会话工作目录两种口径的候选），
 * 随后 [io.deepseekharness.mobile.runtime.RuntimeWorkspaceFiles]
 * 还会逐层 NoFollow 校验并限制在工作区 `root/1` 下。
 *
 * 只接受主文档 GET、单个 `address` 参数，且 scheme/host 必须精确匹配；其余一律视为无效。
 */
internal object WorkspaceShareAddress {
    const val SCHEME = "dsh-share"

    private const val HOST = "file"
    private const val ADDRESS_PREFIX = "dsh-resource://file/session/"
    private const val WORKSPACE_PATH = "/root/1/"
    /**
     * 会话工作目录是 PRoot 启动目录 `/root` 时，工作区文件在地址里多出的第一层。
     *
     * 前端地址相对于**会话工作目录**：会话绑定工作区时 cwd 就是工作区，地址形如 `notes.md`；
     * 会话使用 PRoot 启动目录时 cwd 是 `/root`，同一份文件在地址里是 `1/notes.md`。
     */
    private const val SESSION_CWD_SEGMENT = "1/"
    private const val MAX_ADDRESS_LENGTH = 8192
    private const val MAX_SESSION_LENGTH = 200

    private data class Address(val path: String, val absolute: Boolean)

    /**
     * 按优先级给出工作区相对路径候选；无效时返回 null。
     *
     * 相对地址可能多带一层会话工作目录前缀（见 [SESSION_CWD_SEGMENT]）：原生侧无法从地址
     * 里分辨会话 cwd 是 `/root` 还是工作区本身，因此以 `1/` 开头的相对地址额外给出剥掉这一层
     * 的候选，由调用方按「工作区里确实存在该普通文件」决定用哪一个。两个候选都只描述
     * `root/1` 下的路径，不会因为多一个候选而扩大分享范围。
     *
     * 绝对地址（`/root/1/…`）已经由前端按会话 cwd 处理过，只对应一个确定路径，不加候选。
     *
     * 不要另加一个「只取第一个路径」的入口：那样调用方会绕开工作区存在性判断，
     * 重新踩上 `/root/1/1/…` 找不到文件的坑。
     */
    fun relativePaths(raw: String): List<String>? {
        val address = parse(raw) ?: return null
        if (address.absolute) return listOf(address.path)
        val withoutSessionCwd = address.path.removePrefix(SESSION_CWD_SEGMENT)
            .takeIf { it.length < address.path.length && it.isNotEmpty() }
            ?: return listOf(address.path)
        return listOf(address.path, withoutSessionCwd)
    }

    /** 解析地址；工作区之外的绝对路径直接判失败（返回 null）。 */
    private fun parse(raw: String): Address? {
        if (raw.length !in 1..MAX_ADDRESS_LENGTH || raw.any { it.isISOControl() || it == '\\' }) return null
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true) ||
            !HOST.equals(uri.host, ignoreCase = true) ||
            uri.rawUserInfo != null ||
            uri.port != -1 ||
            uri.rawFragment != null ||
            !uri.rawPath.isNullOrEmpty()
        ) {
            return null
        }
        val query = uri.rawQuery ?: return null
        // 只认唯一参数 address；多参数或别名都说明请求不是本适配层发的。
        val separator = query.indexOf('=')
        if (separator <= 0 || query.substring(0, separator) != "address") return null
        if (query.indexOf('&', separator + 1) >= 0) return null
        val address = percentDecode(query.substring(separator + 1)) ?: return null
        return pathOf(address)
    }

    private fun pathOf(address: String): Address? {
        if (!address.startsWith(ADDRESS_PREFIX)) return null
        val rest = address.substring(ADDRESS_PREFIX.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val session = percentDecode(rest.substring(0, slash)) ?: return null
        if (session.length !in 1..MAX_SESSION_LENGTH || isUnsafeSegment(session)) return null
        val rawSegments = rest.substring(slash + 1).split('/')
        if (rawSegments.isEmpty()) return null
        val segments = rawSegments.map { encoded -> percentDecode(encoded) ?: return null }
        // 地址里的绝对路径（/root/1/…）会被拆成 ["", "root", "1", …]；
        // 先摘掉这个空段再统一校验，避免把合法的绝对路径误判成空段。
        val absolute = segments.firstOrNull()?.isEmpty() == true
        val parts = if (absolute) segments.drop(1) else segments
        if (parts.isEmpty() || parts.any { it.isEmpty() || isUnsafeSegment(it) }) return null
        if (absolute) {
            val path = "/" + parts.joinToString("/")
            if (!path.startsWith(WORKSPACE_PATH)) return null
            // 工作区外的绝对路径不分享；也不尝试用 ".." 之类的写法绕回工作区。
            val relative = path.substring(WORKSPACE_PATH.length).takeIf { it.isNotEmpty() } ?: return null
            return Address(relative, absolute = true)
        }
        return Address(parts.joinToString("/"), absolute = false)
    }

    /** 解码后的单个路径段不允许分隔符、控制字符或目录跳转。 */
    private fun isUnsafeSegment(segment: String): Boolean =
        segment == "." || segment == ".." || segment.contains('/') || segment.contains('\\') ||
            segment.any { it.isISOControl() || it == '�' }

    /**
     * 严格百分号解码：`%` 后必须是两位十六进制，非法 UTF-8 直接判失败。
     *
     * 不用 `URLDecoder`：它会把 `+` 当成空格，真实文件名里的加号会被改写。
     */
    private fun percentDecode(value: String): String? {
        if (value.length > MAX_ADDRESS_LENGTH) return null
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                if (index + 2 >= value.length) return null
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high < 0 || low < 0) return null
                bytes.write(high * 16 + low)
                index += 3
            } else {
                val encoded = char.toString().toByteArray(StandardCharsets.UTF_8)
                bytes.write(encoded, 0, encoded.size)
                index += 1
            }
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: Exception) {
            null
        }
    }
}
