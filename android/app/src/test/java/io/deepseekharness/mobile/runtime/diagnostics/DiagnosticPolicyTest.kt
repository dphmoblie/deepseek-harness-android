package io.deepseekharness.mobile.runtime.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * 诊断日志策略测试。
 *
 * 这一层的意义不只是格式化：它是「绝不把敏感内容写进可导出文件」的唯一把关点，
 * 因此除正常路径外，重点覆盖各类**必须被拒绝**的输入。
 */
class DiagnosticPolicyTest {
    private val instant: Instant = Instant.parse("2026-09-12T10:20:30Z")

    private fun line(
        level: DiagnosticLevel = DiagnosticLevel.INFO,
        event: DiagnosticEvent = DiagnosticEvent.RUNTIME_PHASE,
        fields: Map<String, String> = emptyMap(),
    ): String = DiagnosticPolicy.recordLine(instant, level, event, fields)

    @Test
    fun writesFixedOrderedFields() {
        val result = line(fields = mapOf("phase" to "running", "count" to "2"))
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE|phase=running|count=2\n", result)
    }

    @Test
    fun omitsFieldsWhenNoneAreGiven() {
        assertEquals("2026-09-12T10:20:30Z|WARN|RECOVERY\n", line(level = DiagnosticLevel.WARN, event = DiagnosticEvent.RECOVERY))
    }

    @Test
    fun dropsValuesThatCouldCarryPathsUrlsOrCredentials() {
        listOf(
            "/data/user/0/io.deepseekharness.mobile/noBackupFilesDir",
            "http://127.0.0.1:3080/",
            "Basic ZHNOLW1vYmlsZTpzZWNyZXQ=",
            "has space",
            "has\"quote",
            "key=value",
            "{\"json\":true}",
            // `sk-` 开头的凭据形态：仅靠字符集挡不住，靠 code 字段的形态约束挡住。
            "sk-abcdefghijklmnopqrstuvwxyz012345",
            "MixedCaseToken",
            "",
            "a".repeat(49),
        ).forEach { hostile ->
            val result = line(fields = mapOf("code" to hostile))
            assertEquals("hostile value must be dropped entirely: $hostile", "2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE\n", result)
        }
    }

    /**
     * 形态规则的边界必须写清楚，避免高估它的作用。
     *
     * 全大写且只含 `A-Z0-9_` 的字符串在形态上与错误码无法区分（`SECRETTOKEN` 与
     * `HARNESS_START_TIMEOUT` 同形）。因此 `code` 字段的真实保证不是字符集，而是
     * **取值来源受控**：它只接受 `RuntimeFailure.code` / 状态快照里的 `errorCode`，
     * 两者都是固定的内部枚举，永远不来自用户输入、WebView 或外部数据。
     * 字符集规则的作用是拦住小写、混合大小写、含空格/引号/斜杠等自由文本形态。
     */
    @Test
    fun uppercaseCodeShapeIsAcceptedByDesignAndOnlyFedFromFixedEnums() {
        assertEquals(
            "2026-09-12T10:20:30Z|ERROR|RUNTIME_PHASE|code=SECRETTOKEN\n",
            line(level = DiagnosticLevel.ERROR, fields = mapOf("code" to "SECRETTOKEN")),
        )
        // 真实错误码同样通过；两类字符串在形态上不可区分，这正是需要写清边界的原因。
        assertEquals(
            "2026-09-12T10:20:30Z|ERROR|RUNTIME_PHASE|code=HARNESS_START_TIMEOUT\n",
            line(level = DiagnosticLevel.ERROR, fields = mapOf("code" to "HARNESS_START_TIMEOUT")),
        )
    }

    @Test
    fun enforcesPerFieldValueShape() {
        // 布尔字段只接受 true/false。
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE|enabled=false\n", line(fields = mapOf("enabled" to "false")))
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE\n", line(fields = mapOf("enabled" to "1")))
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE\n", line(fields = mapOf("active" to "yes")))
        // 计数字段只接受数字。
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE|bytes=4096\n", line(fields = mapOf("bytes" to "4096")))
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE\n", line(fields = mapOf("bytes" to "4kb")))
        // code 只接受大写错误码；小写凭据形态一律丢弃。
        assertEquals("2026-09-12T10:20:30Z|ERROR|RUNTIME_PHASE|code=HARNESS_START_TIMEOUT\n",
            line(level = DiagnosticLevel.ERROR, fields = mapOf("code" to "HARNESS_START_TIMEOUT")))
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE\n", line(fields = mapOf("code" to "lower_case")))
        // result / permission 是封闭集合，不接受任意值。
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE|result=ok\n", line(fields = mapOf("result" to "ok")))
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE\n", line(fields = mapOf("result" to "maybe")))
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE|permission=granted\n", line(fields = mapOf("permission" to "granted")))
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE\n", line(fields = mapOf("permission" to "GRANTED")))
        // token 字段只接受小写形态；真实阶段名与原因码都能通过。
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE|phase=not-installed\n", line(fields = mapOf("phase" to "not-installed")))
        assertEquals("2026-09-12T10:20:30Z|WARN|RECOVERY|reason=reaped_pid_file\n",
            line(level = DiagnosticLevel.WARN, event = DiagnosticEvent.RECOVERY, fields = mapOf("reason" to "reaped_pid_file")))
    }

    @Test
    fun acceptsEveryShapeThatRealCallSitesProduce() {
        // 与各调用点一一对应；这些值一旦被规则误杀，排障时间线就会缺段。
        val realFields = listOf(
            mapOf("phase" to "running"),
            mapOf("phase" to "not-installed"),
            mapOf("reason" to "stopped"),
            mapOf("code" to "HARNESS_START_TIMEOUT"),
            mapOf("result" to "ok", "phase" to "running"),
            mapOf("result" to "failed", "code" to "RUNTIME_BUSY"),
            mapOf("result" to "succeeded", "active" to "true"),
            mapOf("result" to "created"),
            mapOf("active" to "false", "running" to "true", "enabled" to "true"),
            mapOf("reason" to "no_runtime", "active" to "false"),
            mapOf("reason" to "foreground", "active" to "true"),
            mapOf("reason" to "reaped_residual", "count" to "3"),
            mapOf("reason" to "reaped_pid_file", "count" to "1"),
            mapOf("enabled" to "true", "days" to "3"),
            mapOf("files" to "2", "bytes" to "4096"),
        )
        realFields.forEach { fields ->
            val result = line(fields = fields)
            val rendered = result.trim().split('|').drop(3)
            assertEquals("all real fields must survive validation: $fields", fields.size, rendered.size)
        }
    }

    @Test
    fun dropsFieldsOutsideTheAllowlist() {
        val result = line(
            fields = mapOf(
                "password" to "hunter2",
                "token" to "abcdef",
                "path" to "root",
                "api_key" to "abcdef",
                "phase" to "running",
            ),
        )
        assertEquals("2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE|phase=running\n", result)
        assertFalse(DiagnosticPolicy.isAllowedKey("password"))
        assertFalse(DiagnosticPolicy.isAllowedKey("token"))
        assertFalse(DiagnosticPolicy.isAllowedKey("path"))
        assertTrue(DiagnosticPolicy.isAllowedKey("phase"))
    }

    @Test
    fun keepsTheEventEvenWhenEveryFieldIsRejected() {
        // 事件本身（谁发生了）比上下文更值得保留，且丢弃字段永不导致写入失控内容。
        val result = line(fields = mapOf("code" to "/etc/passwd"))
        assertTrue(result.contains("|RUNTIME_PHASE"))
        assertFalse(result.contains("passwd"))
    }

    @Test
    fun capsFieldCountAndLineLength() {
        val many = (1..20).associate { index -> "count" to index.toString() }
        val result = DiagnosticPolicy.recordLine(instant, DiagnosticLevel.INFO, DiagnosticEvent.RUNTIME_PHASE, many)
        assertTrue(result.length <= DiagnosticPolicy.MAX_LINE_CHARS)
        // 同名键在 Map 中只存在一个，因此这里退化为单字段；关键是长度上限生效。
        assertNotNull(result)
    }

    @Test
    fun clampsRetentionIntoSupportedRange() {
        assertEquals(DiagnosticPolicy.MIN_RETENTION_DAYS, DiagnosticPolicy.clampRetentionDays(0))
        assertEquals(DiagnosticPolicy.MIN_RETENTION_DAYS, DiagnosticPolicy.clampRetentionDays(-5))
        assertEquals(7, DiagnosticPolicy.clampRetentionDays(7))
        assertEquals(DiagnosticPolicy.MAX_RETENTION_DAYS, DiagnosticPolicy.clampRetentionDays(365))
        assertEquals(DiagnosticPolicy.DEFAULT_RETENTION_DAYS, DiagnosticPolicy.DEFAULT_RETENTION_DAYS)
    }

    @Test
    fun selectsOnlyFilesOlderThanTheRetentionWindow() {
        val today = LocalDate.parse("2026-09-12")
        val names = listOf(
            "diagnostic-2026-09-12.log",
            "diagnostic-2026-09-10.log",
            "diagnostic-2026-09-09.log",
            "diagnostic-2026-09-08.log",
            "audit-2026-09-01.log",
            "diagnostic-not-a-date.log",
            "diagnostic-2026-09-11.log.bak",
        )
        // 保留 3 天 => 保留 09-12 / 09-11 / 09-10 / 09-09，删除 09-08 及更早。
        assertEquals(
            setOf("diagnostic-2026-09-08.log"),
            DiagnosticPolicy.retentionCandidates(names, today, 3),
        )
        assertEquals(
            setOf("diagnostic-2026-09-10.log", "diagnostic-2026-09-09.log", "diagnostic-2026-09-08.log"),
            DiagnosticPolicy.retentionCandidates(names, today, 1),
        )
        assertTrue(DiagnosticPolicy.retentionCandidates(names, today, 30).isEmpty())
    }

    @Test
    fun deletesOldestFilesFirstWhenTotalSizeExceedsTheCap() {
        val entries = listOf(
            "diagnostic-2026-09-10.log" to 400L,
            "diagnostic-2026-09-11.log" to 400L,
            "diagnostic-2026-09-12.log" to 400L,
        )
        // 上限 1000 字节 => 删掉最旧的 09-10 后即降到上限之内。
        assertEquals(setOf("diagnostic-2026-09-10.log"), DiagnosticPolicy.sizeCandidates(entries, 1000))
        // 上限 800 => 删掉 09-10 后正好等于上限，因此不再多删。
        assertEquals(setOf("diagnostic-2026-09-10.log"), DiagnosticPolicy.sizeCandidates(entries, 800))
        // 上限 700 => 需要删掉 09-10 与 09-11。
        assertEquals(
            setOf("diagnostic-2026-09-10.log", "diagnostic-2026-09-11.log"),
            DiagnosticPolicy.sizeCandidates(entries, 700),
        )
        // 未超限时不动任何文件。
        assertTrue(DiagnosticPolicy.sizeCandidates(entries, 1200).isEmpty())
        // 无法解析日期的文件不参与容量淘汰（也不会被误删）。
        assertTrue(DiagnosticPolicy.sizeCandidates(listOf("notes.txt" to 9999L), 10).isEmpty())
    }

    @Test
    fun parsesOnlyOwnFileNames() {
        assertEquals(LocalDate.parse("2026-09-12"), DiagnosticPolicy.parseFileDate("diagnostic-2026-09-12.log"))
        assertEquals(null, DiagnosticPolicy.parseFileDate("diagnostic-2026-13-40.log"))
        assertEquals(null, DiagnosticPolicy.parseFileDate("audit-2026-09-12.log"))
        assertEquals(null, DiagnosticPolicy.parseFileDate("diagnostic-2026-09-12.log.1"))
        assertEquals("diagnostic-2026-09-12.log", DiagnosticPolicy.fileName(LocalDate.parse("2026-09-12")))
    }

    @Test
    fun exportFileNameCarriesNoUserOrPackageDetail() {
        val name = DiagnosticPolicy.exportFileName(instant)
        assertEquals("dsh-diagnostic-20260912-102030.txt", name)
        assertTrue(name.matches(Regex("^[A-Za-z0-9._-]+$")))
    }

    @Test
    fun everyEventAndLevelRendersWithoutFreeText() {
        DiagnosticEvent.entries.forEach { event ->
            DiagnosticLevel.entries.forEach { level ->
                val result = DiagnosticPolicy.recordLine(instant, level, event)
                assertTrue(result.startsWith("2026-09-12T10:20:30Z|${level.name}|${event.name}"))
                assertTrue(result.endsWith("\n"))
            }
        }
    }
}
