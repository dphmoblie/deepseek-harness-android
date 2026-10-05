package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticPolicy
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `stale-*` 残留清扫器的行为契约。
 *
 * 覆盖三件真机上必须成立的事：分级删除的三种结局都如实记录（绝不静默）、越界路径仍被拒绝、
 * 以及「删除器注入」能让这些分支在 JVM 单测里被真的走到。
 */
class RuntimeResidueSweeperTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `清扫器回收全部 stale 残留并统计份数与字节数`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        val first = residue(parent, "stale-previous-1a2b", bytes = 1024)
        val second = residue(parent, "stale-current-zz", bytes = 2048)
        // 非残留目录必须原样保留：清扫器的判定范围只有 stale-* 前缀。
        val kept = File(parent, "retained").apply {
            mkdirs()
            File(this, "rootfs.bin").writeBytes(ByteArray(16))
        }

        val records = mutableListOf<Pair<DiagnosticLevel, Map<String, String>>>()
        val result = RuntimeResidueSweeper(
            runtimeParent = parent,
            cleanup = RuntimeResidueCleanup(strictDelete = { target, _ -> target.deleteRecursively() }),
            record = { level, fields -> records += level to fields },
        ).sweep()

        assertEquals(2, result.cleaned)
        assertEquals(0, result.fallbackCleaned)
        assertEquals(0, result.failed)
        assertEquals(3072L, result.reclaimedBytes)
        assertFalse(result.hasFailures)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertTrue(kept.isDirectory)
        // 一处都没失败：只有一条汇总行，不逐份刷屏。
        assertEquals(1, records.size)
        val summary = records.single().second
        assertEquals("cleanup", summary["phase"])
        assertEquals("succeeded", summary["result"])
        assertEquals("stale_residue", summary["reason"])
        assertEquals("2", summary["count"])
        assertEquals("3072", summary["bytes"])
        assertEquals("false", summary["residual"])
        assertFieldsAllowed(summary)
    }

    @Test
    fun `严格删除器失败时走兜底删除器并保留严格那次的失败类别`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        val target = residue(parent, "stale-previous-1a2b", bytes = 64)

        val records = mutableListOf<Pair<DiagnosticLevel, Map<String, String>>>()
        val result = RuntimeResidueSweeper(
            runtimeParent = parent,
            cleanup = RuntimeResidueCleanup(
                strictDelete = { _, _ ->
                    throw RuntimeFailure(
                        "FILESYSTEM_SECURE_DELETE_UNAVAILABLE",
                        "系统不支持安全清理运行时文件",
                        UnsupportedOperationException("not a secure directory stream"),
                    )
                },
                fallbackDelete = { file, _ -> file.deleteRecursively() },
            ),
            record = { level, fields -> records += level to fields },
        ).sweep()

        assertEquals(1, result.cleaned)
        assertEquals(1, result.fallbackCleaned)
        assertEquals(0, result.failed)
        assertFalse(target.exists())

        val fallbackLine = records.first { it.second["result"] == "succeeded" && it.second["reason"] != "stale_residue" }
        assertEquals(DiagnosticLevel.WARN, fallbackLine.first)
        assertEquals("cleanup", fallbackLine.second["phase"])
        // code 是严格删除器那次的类别：兜底成功不能掩盖「严格删除器真机上失败」这个事实。
        // （类别由根异常决定，所以这里断言的是 UnsupportedOperationException 映射出的 token。）
        assertEquals("UNSUPPORTED", fallbackLine.second["code"])
        assertEquals("stale_previous-1a2b", fallbackLine.second["reason"])
        assertEquals("1", fallbackLine.second["count"])
        assertEquals("64", fallbackLine.second["bytes"])
        assertFieldsAllowed(fallbackLine.second)
        assertFieldsAllowed(records.last().second)
        assertEquals("1", records.last().second["count"])
        assertEquals("64", records.last().second["bytes"])
    }

    @Test
    fun `两条删除器都失败时只记失败且不抛出`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        val target = residue(parent, "stale-previous-1a2b", bytes = 128)

        val records = mutableListOf<Pair<DiagnosticLevel, Map<String, String>>>()
        val result = RuntimeResidueSweeper(
            runtimeParent = parent,
            cleanup = RuntimeResidueCleanup(
                strictDelete = { _, _ ->
                    throw RuntimeFailure("FILE_BUSY", "严格删除器失败", FileSystemException("stale-previous-1a2b"))
                },
                fallbackDelete = { _, _ ->
                    throw RuntimeFailure("FILESYSTEM_ERROR", "兜底删除器也失败", AccessDeniedException("stale-previous-1a2b"))
                },
            ),
            record = { level, fields -> records += level to fields },
        ).sweep()

        assertEquals(0, result.cleaned)
        assertEquals(1, result.failed)
        assertEquals(0L, result.reclaimedBytes)
        assertTrue(result.hasFailures)
        // 删不掉就必须留在盘上，不能假装删掉了。
        assertTrue(target.isDirectory)

        val failure = records.first { it.second["result"] == "failed" && it.second["reason"] != "stale_residue" }
        assertEquals("cleanup", failure.second["phase"])
        // code 取兜底那次的失败：它才是「为什么还是删不掉」的最终解释（严格那次的类别是 FILESYSTEM）。
        assertEquals("ACCESS_DENIED", failure.second["code"])
        assertEquals("stale_previous-1a2b", failure.second["reason"])
        assertEquals("128", failure.second["bytes"])
        val summary = records.last().second
        assertEquals("failed", summary["result"])
        assertEquals("true", summary["residual"])
        assertEquals("ACCESS_DENIED", summary["code"])
        records.forEach { assertFieldsAllowed(it.second) }
    }

    @Test
    fun `默认删除器在界内目标上也能把残留真正删掉`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        val target = residue(parent, "stale-staging-abc", bytes = 32)

        // 不注入任何 lambda：走真实的分级删除器。
        val result = RuntimeResidueSweeper(runtimeParent = parent).sweep()

        assertEquals(1, result.cleaned)
        assertEquals(0, result.failed)
        assertFalse(target.exists())
        // 严格删除器是否可用取决于平台（Windows JDK 没有 SecureDirectoryStream），
        // 但无论走哪条路径，结果都必须是「删干净」。
        assertTrue(result.fallbackCleaned == 0 || result.fallbackCleaned == 1)
    }

    @Test
    fun `越界路径仍然被拒绝且文件不被删除`() {
        val allowedParent = temporaryFolder.newFolder("dsh-runtime")
        val outside = temporaryFolder.newFolder("elsewhere").let { File(it, "stale-owner").apply { mkdirs() } }
        val marker = File(outside, "rootfs.bin").apply { writeBytes(ByteArray(8)) }

        val records = mutableListOf<Pair<DiagnosticLevel, Map<String, String>>>()
        val result = RuntimeResidueSweeper(
            runtimeParent = allowedParent,
            bytesOf = { 8L },
            record = { level, fields -> records += level to fields },
        ).sweep()

        // 清扫器只扫 runtimeParent 的直接子项，所以盘上这份越界残留不会被它碰到。
        assertEquals(0, result.cleaned)
        assertEquals(0, result.failed)
        assertTrue(marker.isFile)
        assertTrue(records.isEmpty())

        // 直接让分级删除器去删界外路径：作用域校验必须仍然拦住它（严格与兜底都会拒绝）。
        val outcome = RuntimeResidueCleanup().delete(outside, allowedParent)

        val failed = outcome as? ResidueDeleteOutcome.Failed
        assertNotNull("越界删除必须失败，实际结果：$outcome", failed)
        assertEquals("RESET_SCOPE_INVALID", failed!!.failure.code)
        assertTrue(marker.isFile)
    }

    @Test
    fun `残留名字的 token 规范不超过策略上限`() {
        assertTrue(RuntimeResidueNames.isResidueName("stale-previous"))
        assertTrue(RuntimeResidueNames.isResidueName("stale-previous-1a2b"))
        assertFalse(RuntimeResidueNames.isResidueName("stale-"))
        assertFalse(RuntimeResidueNames.isResidueName("staging-1a2b"))
        assertFalse(RuntimeResidueNames.isResidueName("preserve-1a2b"))

        val aside = RuntimeResidueNames.asideName("preserve-$UUID36", 1_700_000_000_000L)
        assertTrue(RuntimeResidueNames.isResidueName(aside))
        assertTrue(aside.startsWith("stale-preserve-$UUID36-"))

        val reason = RuntimeResidueNames.reason(aside)
        // 名字里带 UUID36 的残留最长：reason 必须被截断到 token 允许的 32 字符，
        // 否则诊断策略会静默丢掉整个 reason 字段。
        assertTrue("reason 超长：$reason", reason.length <= RuntimeResidueTokens.MAX_CHARS)
        assertTrue(reason, DiagnosticPolicy.isAllowedField("reason", reason))
        assertTrue(reason.startsWith("stale_preserve-"))
    }

    @Test
    fun `同名残留会被一起找到并排序`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        residue(parent, "stale-previous-zz", bytes = 1)
        residue(parent, "stale-previous", bytes = 1)
        residue(parent, "stale-previous-aa", bytes = 1)
        File(parent, "previous").mkdirs()

        val siblings = RuntimeResidueNames.siblingsOf(parent, "previous").map { it.name }

        assertEquals(listOf("stale-previous", "stale-previous-aa", "stale-previous-zz"), siblings)
    }

    private fun residue(parent: File, name: String, bytes: Int): File {
        val directory = File(parent, name).apply { mkdirs() }
        File(directory, "rootfs.bin").writeBytes(ByteArray(bytes))
        return directory
    }

    private fun assertFieldsAllowed(fields: Map<String, String>) {
        fields.forEach { (key, value) ->
            assertTrue("诊断字段 $key=$value 不在白名单内", DiagnosticPolicy.isAllowedField(key, value))
        }
    }

    private companion object {
        const val UUID36 = "0123456789abcdef0123456789abcdef0123"
    }
}
