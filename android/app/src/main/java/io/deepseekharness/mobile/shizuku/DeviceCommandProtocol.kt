package io.deepseekharness.mobile.shizuku

/**
 * 设备命令的双哨兵协议（纯逻辑，不依赖 Android API，便于 JVM 单测逐条固定行为）。
 *
 * 为什么放弃「按回显间隙解析」：设备 Shell 是 `/system/bin/sh`（Android 上是 mksh），
 * 它自带行编辑器，**无视终端 ECHO 标志**（`stty -echo` 对它无效），注入的命令行文本
 * 会被行编辑器回显，跨列时还会折行重绘（`\r`、`<` 续行提示、退格擦除）。于是：
 *
 *  - 短命令：回显里的哨兵完整且排在真实输出之前，解析命中假的，其后跟着未展开的 `:$?`，
 *    匹配失败后再也不重试 → 必然 60 秒超时；
 *  - 长命令：回显里的哨兵被折行打断，反而侥幸走通；
 *  - 截图：回显污染落在 payload 头部，完好的 PNG 被判为非法。
 *
 * 本协议把标记的**真值交给 shell 自己展开**：
 *
 * ```sh
 * dsh_nonce=$$-$RANDOM
 * echo "__DSH_B_<请求标识>_${dsh_nonce}__"
 * <命令>
 * echo "__DSH_E_<请求标识>_${dsh_nonce}__:$?"
 * ```
 *
 * 回显里出现的只能是**未展开的字面量** `${dsh_nonce}`；而真实标记的 token 必须形如
 * `<数字>-<数字>`（例如 `1234-5678`），字面量永远不可能匹配。因此：
 *
 *  1. 回显**无法伪造**真实标记；
 *  2. payload 严格取真实 BEGIN 与真实 END 之间的区间，回显不再进入 payload。
 *
 * 解析规则（见 [parse]）：取**最后一个** BEGIN，再取其后 token 相同、**最后一个** END，
 * 退出码取 END 尾部的十进制值。只认最后一个，因此嵌套或重放的多组哨兵不会截错。
 */
internal sealed interface DeviceCommandParseResult {
    /** 还没有看到真实 BEGIN：继续等待（超时由调用方兜底）。 */
    object Pending : DeviceCommandParseResult

    /** 已经取到真实区间，payload 即两个标记之间的内容。 */
    data class Completed(
        val exitCode: Int,
        val payload: String,
        /** 正文超出有界前缀窗口：payload 只覆盖到窗口末尾。 */
        val payloadTruncated: Boolean,
    ) : DeviceCommandParseResult

    /** 协议层受控失败：流不可能再产生合法标记（既不是超时，也不是成功）。 */
    data class Failed(val errorCode: String) : DeviceCommandParseResult
}

internal object DeviceCommandProtocol {
    /** 已看到 BEGIN 但 END 缺失、token 不匹配：设备 Shell 产出与协议不符。 */
    const val ERROR_PROTOCOL = "DEVICE_COMMAND_PROTOCOL_ERROR"

    /** 设备 Shell 会话已结束，却连 BEGIN 都没出现。 */
    const val ERROR_SESSION_LOST = "DEVICE_COMMAND_SESSION_LOST"

    private const val BEGIN_PREFIX = "__DSH_B_"
    private const val END_PREFIX = "__DSH_E_"

    /**
     * nonce 的**字面量**引用：注入文本里写的是 `${dsh_nonce}`，
     * 只有被内层 shell 展开后才成为数字 token。回显看到的就是这段字面量。
     */
    private const val NONCE_REFERENCE = "\${dsh_nonce}"

    /** 真实 token：`<shell pid>-<随机数>`，与字面量 `${dsh_nonce}` 天然可区分。 */
    private val TOKEN_PATTERN = "\\d+-\\d+"

    /** BEGIN 标记的字面量模板（含未展开的 `${dsh_nonce}`）。 */
    fun beginMarker(requestId: String): String = "${BEGIN_PREFIX}${requestId}_${NONCE_REFERENCE}__"

    /** END 标记的字面量模板（含未展开的 `${dsh_nonce}`），退出码由调用方追加。 */
    fun endMarker(requestId: String): String = "${END_PREFIX}${requestId}_${NONCE_REFERENCE}__"

    /**
     * 廉价门：尾部窗口里是否已经出现「token 已展开」的 END 形态。
     *
     * 回显里的字面量 `${dsh_nonce}` 不匹配，因此超长 payload 不会因为外层回显里带着
     * END 前缀就每片都全量扫描正文；只有真实 END 出现后才进入完整解析。
     */
    fun appearsComplete(requestId: String, tail: String): Boolean =
        endHintPattern(requestId).containsMatchIn(tail)

    private fun beginPattern(requestId: String): Regex =
        Regex(BEGIN_PREFIX + Regex.escape(requestId) + "_(" + TOKEN_PATTERN + ")__")

    private fun endPattern(requestId: String): Regex =
        // 真实标记由 echo 一次性写出，尾部一定有换行（PTY 可能把 \n 转成 \r\n）。
        Regex(END_PREFIX + Regex.escape(requestId) + "_(" + TOKEN_PATTERN + ")__:(\\d{1,3})\\r?\\n")

    /** 只要求 token 已展开，不要求退出码与换行：用于决定是否值得做完整解析。 */
    private fun endHintPattern(requestId: String): Regex =
        Regex(END_PREFIX + Regex.escape(requestId) + "_" + TOKEN_PATTERN + "__")

    /**
     * 解析一段（可能被截断的）设备 Shell 输出。
     *
     * @param head 输出流的**有界前缀**，绝对偏移从 0 开始。
     * @param tail 输出流的**有界尾部窗口**，[tailStart] 是 `tail[0]` 在流中的绝对偏移。
     * @param streamEnded 调用方已经确知不会再有新数据（会话结束）。为 true 时，
     *   缺失或损坏的标记会给出受控失败而不是继续等待。
     */
    fun parse(
        requestId: String,
        head: String,
        tail: String,
        tailStart: Long,
        streamEnded: Boolean,
    ): DeviceCommandParseResult {
        val begins = beginPattern(requestId).findAll(head).toList()
        if (begins.isEmpty()) {
            // 一个真实 BEGIN 都没有：要么命令还没跑起来，要么 shell 已经没了。
            return if (streamEnded) {
                DeviceCommandParseResult.Failed(ERROR_SESSION_LOST)
            } else {
                DeviceCommandParseResult.Pending
            }
        }
        // 取**最后一个**真实 BEGIN。回显里的字面量不可能进入这个集合。
        val begin = begins.last()
        val beginEnd = begin.range.last + 1
        val token = begin.groupValues[1]

        val end = findLastEnd(requestId, token, head, tail, tailStart, beginEnd)
        if (end != null) {
            val payloadLimit = minOf(end.absoluteStart, head.length.toLong())
            val payload = if (beginEnd >= payloadLimit) {
                ""
            } else {
                head.substring(beginEnd, payloadLimit.toInt())
            }
            return DeviceCommandParseResult.Completed(
                exitCode = end.exitCode,
                payload = payload,
                payloadTruncated = end.absoluteStart > head.length,
            )
        }

        // 出现了完整但 token 不同的 END：流里混进了别的命令或陈旧数据，再等也不会有结果。
        if (findLastEnd(requestId, token = null, head, tail, tailStart, beginEnd) != null) {
            return DeviceCommandParseResult.Failed(ERROR_PROTOCOL)
        }
        return if (streamEnded) {
            DeviceCommandParseResult.Failed(ERROR_PROTOCOL)
        } else {
            DeviceCommandParseResult.Pending
        }
    }

    private class EndMatch(val absoluteStart: Long, val exitCode: Int)

    /**
     * 查找 [afterAbsolute] 之后、绝对位置最靠后的 END 标记。
     * [token] 为 null 时不比较 token（用于识别「有 END 但 token 不匹配」的损坏流）。
     */
    private fun findLastEnd(
        requestId: String,
        token: String?,
        head: String,
        tail: String,
        tailStart: Long,
        afterAbsolute: Int,
    ): EndMatch? {
        val pattern = endPattern(requestId)
        var best: EndMatch? = null
        for (match in pattern.findAll(head)) {
            if (token != null && match.groupValues[1] != token) continue
            val start = match.range.first.toLong()
            if (start < afterAbsolute) continue
            val current = best
            if (current == null || start > current.absoluteStart) {
                best = EndMatch(start, match.groupValues[2].toInt())
            }
        }
        for (match in pattern.findAll(tail)) {
            if (token != null && match.groupValues[1] != token) continue
            val start = tailStart + match.range.first
            if (start < afterAbsolute) continue
            val current = best
            if (current == null || start > current.absoluteStart) {
                best = EndMatch(start, match.groupValues[2].toInt())
            }
        }
        return best
    }
}
