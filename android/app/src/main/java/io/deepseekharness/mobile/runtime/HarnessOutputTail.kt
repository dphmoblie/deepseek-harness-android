package io.deepseekharness.mobile.runtime

/**
 * 界面「运行日志」默认读取的字节上限。
 *
 * 8 KB 足以装下一段 Node.js 异常栈或插件加载报错，又不会让设置页的一次读取明显卡顿。
 * 缓冲区本身是 16 KB（见 ProcessOutputTail.drain 的默认值），因此这里只取尾部。
 */
internal const val HARNESS_OUTPUT_TAIL_BYTES = 8 * 1024

/** UTF-8 续字节的判定掩码与目标值：高两位为 10。 */
private const val UTF8_CONTINUATION_MASK = 0xC0
private const val UTF8_CONTINUATION_PREFIX = 0x80

/**
 * 取 [text] 末尾不超过 [maxBytes] 个 UTF-8 字节的片段。
 *
 * 截断必须落在字符边界上。从末尾按字节切，首字节很容易落在多字节字符的续字节
 * （形如 10xxxxxx）上，解码出来就是一个残缺字符（U+FFFD）：复制出去的日志开头
 * 永远是乱码，中文报错尤其明显。因此这里向前跳过续字节 —— 宁可少给几个字节，
 * 也不产生半个字符。
 *
 * 边界行为：[maxBytes] 非正时返回空串；文本本身不超过上限时原样返回；
 * 上限小于最后一个字符的字节数时同样返回空串（不硬塞一个残缺字符）。
 */
internal fun utf8TailWithin(text: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text
    var start = bytes.size - maxBytes
    // 落在字符中间时向前推进到下一个前导字节（ASCII 与多字节前导字节的高两位都不是 10）
    while (start < bytes.size && (bytes[start].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_PREFIX) {
        start += 1
    }
    return String(bytes, start, bytes.size - start, Charsets.UTF_8)
}

/**
 * Harness（访客进程）输出尾部的进程级读取入口。
 *
 * 为什么需要这一层：[RuntimeSupervisor] 由 [MobileRuntimeController] 私有持有，
 * WebView 桥接层（io.deepseekharness.mobile.MobileRuntimePlugin）拿不到实例；而
 * 「工具调用失败只报一句无栈信息」这类问题，唯一线索就是 dsh 自己打在 stdout/stderr
 * 上的完整报错。supervisor 在构造时把只读出口登记到这里，桥接层只读这里。
 *
 * 生命周期：登记项由 [RuntimeHost] 在运行时释放时清空（见 RuntimeHost.takeControllerLocked），
 * 因此它只在当前运行时存活期间引用 supervisor；运行时释放后，输出尾部随之不可达、可被回收。
 *
 * 边界：只读、有界（上限由读取方传入）、不落盘、不进诊断日志导出、不新增诊断事件或字段。
 * 返回的文本可能包含会话内容，仅供设备上的界面展示。
 */
internal object HarnessOutputTailSource {
    private val lock = Any()
    private var reader: ((Int) -> String?)? = null

    /** 登记读取出口；重复登记以最后一次为准（同一时刻只有一个运行时）。 */
    fun register(read: (Int) -> String?) = synchronized(lock) {
        reader = read
    }

    /** 取消登记；运行时释放时调用，避免登记项继续持有已释放的 supervisor。 */
    fun clear() = synchronized(lock) {
        reader = null
    }

    /** 当前 Harness 输出的尾部；没有登记（本进程未持有 Harness）时返回 null。 */
    fun read(maxBytes: Int = HARNESS_OUTPUT_TAIL_BYTES): String? {
        // 先在锁内复制引用，再在锁外调用：读取会触碰 supervisor 与缓冲区，
        // 不应该把本对象的锁也卷进那条路径。
        val current = synchronized(lock) { reader } ?: return null
        return current(maxBytes)
    }
}
