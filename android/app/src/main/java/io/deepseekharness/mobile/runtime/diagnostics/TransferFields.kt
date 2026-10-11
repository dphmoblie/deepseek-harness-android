package io.deepseekharness.mobile.runtime.diagnostics

/**
 * 传输 / 校验失败的**数字证据**字段。
 *
 * 为什么需要：安装失败时若只写 `phase=error|code=ARCHIVE_DIGEST_MISMATCH|reason=transfer`，
 * 这句话无法区分三种完全不同的故障，用户把日志发回来也定不了案：
 *  - 读短了（`actual_bytes < expected_bytes`）：资产被截断、介质读数不可靠、拷贝被中途打断；
 *  - 读满了但内容不是同一份（`actual_bytes == expected_bytes` 且 `digest_ok=false`）：
 *    安装包被改写 / 重签名重打包，或归档与清单不是一对；
 *  - 空间不够：`free_bytes` 明显小于归档大小。
 *
 * 因此 transfer 阶段的失败额外记 `expected_bytes` / `actual_bytes` / `digest_ok` / `free_bytes`。
 * 四项全是**数字与布尔**，没有路径、URL、文件名、凭据或自由文本，符合
 * [DiagnosticPolicy] 的白名单与取值形态契约（字节数落在 1–12 位数字内）。
 *
 * 本对象不依赖 Android API，便于单元测试。
 */
object TransferFields {
    const val EXPECTED_BYTES = "expected_bytes"
    const val ACTUAL_BYTES = "actual_bytes"
    const val DIGEST_OK = "digest_ok"
    const val FREE_BYTES = "free_bytes"

    /**
     * 字节上限，与 [DiagnosticPolicy] 的计数字段形态（1–12 位数字）一致。
     *
     * 超出时**夹住而不是丢弃**：宁可数字不精确，也不能丢掉这条证据。真实归档在 1 GB 量级，
     * 正常路径取不到这个上限，只有统计口径出错时才会碰到。
     */
    private const val MAX_BYTES = 999_999_999_999L

    /** 本对象允许写进失败细节的键：调用点无法借 `details` 塞进别的字段。 */
    private val detailKeys = setOf(EXPECTED_BYTES, ACTUAL_BYTES, DIGEST_OK)

    /**
     * 传输结果字段。
     *
     * 未知（负数）的字节数**省略**而不是写 0：`actual_bytes=0` 表示「一个字节都没读到」，
     * 与「没测到」是两回事，混在一起会把判读带偏。
     * [digestOk] 为 `null` 表示摘要还没算（例如长度先不符），此时不写 `digest_ok`，不写假结论。
     */
    fun of(expectedBytes: Long, actualBytes: Long, digestOk: Boolean? = null): Map<String, String> = buildMap {
        countable(expectedBytes)?.let { put(EXPECTED_BYTES, it) }
        countable(actualBytes)?.let { put(ACTUAL_BYTES, it) }
        if (digestOk != null) put(DIGEST_OK, digestOk.toString())
    }

    /** 安装时刻的可用空间；读不到（`null`）时省略字段，不猜数字。 */
    fun freeBytes(availableBytes: Long?): Map<String, String> {
        val text = availableBytes?.let { countable(it) } ?: return emptyMap()
        return mapOf(FREE_BYTES to text)
    }

    /**
     * 失败行的完整字段（顺序固定：基础四字段 → `free_bytes` → 细节字段）。
     *
     * 顺序有意义：[DiagnosticPolicy.MAX_FIELDS] 只保留前 8 个通过校验的字段，
     * 「空间不足」是用户最容易自己动手解决的一条，因此 `free_bytes` 必须排在细节前面，
     * 不能被挤掉。四个基础字段 + `free_bytes` + 三个细节字段正好等于上限。
     */
    fun failureFields(
        code: String,
        reason: String,
        availableBytes: Long?,
        details: Map<String, String>,
    ): Map<String, String> = buildMap {
        put("phase", "error")
        put("code", code)
        put("reason", reason)
        put("result", "failed")
        putAll(freeBytes(availableBytes))
        details.forEach { (key, value) -> if (key in detailKeys) put(key, value) }
    }

    private fun countable(value: Long): String? {
        if (value < 0) return null
        return value.coerceAtMost(MAX_BYTES).toString()
    }
}
