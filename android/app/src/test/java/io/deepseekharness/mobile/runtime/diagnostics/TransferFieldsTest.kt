package io.deepseekharness.mobile.runtime.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 传输失败数字证据测试。
 *
 * 这一层的价值几乎全在**边界**上：写错一个数字（未知写成 0、摘要没算却写 `false`）会把排障的人
 * 引向完全错误的结论，因此逐条钉死省略语义、字段顺序与渲染结果。
 */
class TransferFieldsTest {
    private val instant: Instant = Instant.parse("2026-10-11T02:22:40Z")

    private fun rendered(fields: Map<String, String>): String =
        DiagnosticPolicy.recordLine(instant, DiagnosticLevel.ERROR, DiagnosticEvent.RUNTIME_PHASE, fields).trim()

    @Test
    fun carriesBothCountsAndTheDigestVerdict() {
        assertEquals(
            mapOf("expected_bytes" to "311733891", "actual_bytes" to "1024", "digest_ok" to "false"),
            TransferFields.of(311733891, 1024, digestOk = false),
        )
        assertEquals(
            mapOf("expected_bytes" to "311733891", "actual_bytes" to "311733891", "digest_ok" to "true"),
            TransferFields.of(311733891, 311733891, digestOk = true),
        )
    }

    @Test
    fun omitsUnknownCountsInsteadOfWritingZero() {
        // 0 表示「一个字节都没读到」，与「没测到」是两回事，不能混。
        assertEquals(mapOf("actual_bytes" to "40"), TransferFields.of(-1, 40))
        assertEquals(mapOf("expected_bytes" to "100"), TransferFields.of(100, -1))
        assertTrue(TransferFields.of(-1, -1).isEmpty())
    }

    @Test
    fun omitsDigestWhenItWasNeverComputed() {
        assertFalse(TransferFields.of(100, 40).containsKey("digest_ok"))
        assertTrue(TransferFields.of(100, 40, digestOk = true).containsKey("digest_ok"))
    }

    @Test
    fun clampsCountsIntoThePolicyShapeRange() {
        val fields = TransferFields.of(Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals("999999999999", fields.getValue("expected_bytes"))
        // 12 位数字是计数字段的上限：夹取后必须仍然通过策略校验，而不是被整条丢掉。
        assertTrue(DiagnosticPolicy.isAllowedField("expected_bytes", fields.getValue("expected_bytes")))
        assertTrue(rendered(fields).contains("expected_bytes=999999999999"))
    }

    @Test
    fun omitsAvailableSpaceWhenItCannotBeRead() {
        assertTrue(TransferFields.freeBytes(null).isEmpty())
        assertTrue(TransferFields.freeBytes(-1).isEmpty())
        // 真的读到 0 时如实写 0：这本身就是「空间耗尽了」的证据。
        assertEquals(mapOf("free_bytes" to "0"), TransferFields.freeBytes(0))
        assertEquals(mapOf("free_bytes" to "2147483648"), TransferFields.freeBytes(2_147_483_648L))
    }

    @Test
    fun keepsFreeSpaceAheadOfDetailFields() {
        val fields = TransferFields.failureFields(
            code = "ARCHIVE_DIGEST_MISMATCH",
            reason = "transfer",
            availableBytes = 2_147_483_648L,
            details = TransferFields.of(311733891, 311733891, digestOk = false),
        )
        // 8 字段上限正好用满；顺序即优先级，`free_bytes` 必须排在细节字段之前。
        assertEquals(
            listOf("phase", "code", "reason", "result", "free_bytes", "expected_bytes", "actual_bytes", "digest_ok"),
            fields.keys.toList(),
        )
    }

    @Test
    fun rendersTheWholeFailureLineWithoutDroppingAnyField() {
        val line = DiagnosticPolicy.recordLine(
            instant,
            DiagnosticLevel.ERROR,
            DiagnosticEvent.RUNTIME_PHASE,
            TransferFields.failureFields(
                code = "ARCHIVE_DIGEST_MISMATCH",
                reason = "transfer",
                availableBytes = 2_147_483_648L,
                details = TransferFields.of(311733891, 311733891, digestOk = false),
            ),
        )
        assertEquals(
            "2026-10-11T02:22:40Z|ERROR|RUNTIME_PHASE|phase=error|code=ARCHIVE_DIGEST_MISMATCH" +
                "|reason=transfer|result=failed|free_bytes=2147483648|expected_bytes=311733891" +
                "|actual_bytes=311733891|digest_ok=false\n",
            line,
        )
    }

    @Test
    fun failureFieldsDropDetailKeysThatAreNotOurs() {
        val fields = TransferFields.failureFields(
            code = "ARCHIVE_DIGEST_MISMATCH",
            reason = "transfer",
            availableBytes = null,
            details = mapOf("path" to "root", "password" to "hunter2", "expected_bytes" to "100"),
        )
        assertEquals(listOf("phase", "code", "reason", "result", "expected_bytes"), fields.keys.toList())
    }

    @Test
    fun hostileValuesNeverReachTheRenderedLine() {
        // 键名合法但取值是路径 / 自由文本：策略层的形态校验必须把它丢掉（双层保证）。
        val fields = TransferFields.failureFields(
            code = "ARCHIVE_DIGEST_MISMATCH",
            reason = "transfer",
            availableBytes = null,
            details = mapOf("expected_bytes" to "/data/user/0/noBackupFilesDir", "actual_bytes" to "has space"),
        )
        assertEquals(
            "2026-10-11T02:22:40Z|ERROR|RUNTIME_PHASE|phase=error|code=ARCHIVE_DIGEST_MISMATCH" +
                "|reason=transfer|result=failed",
            rendered(fields),
        )
    }
}
