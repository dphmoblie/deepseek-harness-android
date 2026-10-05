package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 运行时目录盘点的契约：**只记类别、份数、总字节，不记任何路径名**。
 *
 * 设备上看不见这些目录（容器里只挂载 `current/`，release 包的私有目录不可 `run-as`），
 * 所以盘点行是唯一能让「哪一类占了几份、共多少字节」被看见的东西：
 * 一旦它开始往外带路径名或 UUID，就等于把内部目录结构写进了可导出的日志。
 */
class RuntimeResidueInventoryTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `按已知前缀归类并统计份数与字节数`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        slot(parent, "current", bytes = 100)
        slot(parent, "current-manifest.json", bytes = 1)
        slot(parent, "previous", bytes = 200)
        slot(parent, "retained", bytes = 300)
        slot(parent, "stale-previous-1a2b", bytes = 400)
        slot(parent, "stale-current-zz", bytes = 50)
        slot(parent, "preserve-$UUID36", bytes = 10)
        slot(parent, "staging-$UUID36", bytes = 20)
        slot(parent, "manifest-$UUID36.json", bytes = 2)
        slot(parent, "download-$UUID36.part", bytes = 3)
        slot(parent, "rootfs-${"a".repeat(64)}.part", bytes = 4)
        slot(parent, "unexpected-name", bytes = 5)

        val report = RuntimeResidueInventory(parent).report()

        assertEquals(
            mapOf(
                "current" to 101L,
                "previous" to 200L,
                "retained" to 300L,
                "stale" to 450L,
                "preserve" to 10L,
                "staging" to 20L,
                "manifest" to 2L,
                "download" to 3L,
                "resume" to 4L,
                "other" to 5L,
            ),
            report.categories.associate { it.token to it.bytes },
        )
        // current 与 current-manifest.json 归成一类，所以「份数」是这一类下的条目数之和。
        assertEquals(2, report.categories.first { it.token == "current" }.entries)
        assertEquals(2, report.categories.first { it.token == "stale" }.entries)
        assertEquals(12, report.entries)
        assertTrue(report.hasResidue)
        assertFalse(report.truncated)
    }

    @Test
    fun `盘点行只带类别计数且全部通过诊断白名单`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        slot(parent, "current", bytes = 10)
        slot(parent, "stale-previous-1a2b", bytes = 20)
        slot(parent, "preserve-$UUID36", bytes = 30)

        val lines = RuntimeResidueInventory(parent).lines("parent_prepare")

        // 总计行 + 三个非空类别行。
        assertEquals(4, lines.size)
        assertEquals(
            listOf("parent_prepare", "current", "stale", "preserve"),
            lines.map { it.fields["reason"] },
        )
        val total = lines.first().fields
        assertEquals("inventory", total["phase"])
        assertEquals("ok", total["result"])
        assertEquals("3", total["count"])
        assertEquals("60", total["bytes"])
        assertEquals("true", total["residual"])
        assertFalse(total.containsKey("code"))
        lines.forEach { line ->
            assertEquals(DiagnosticLevel.INFO, line.level)
            assertEquals("inventory", line.fields["phase"])
            assertEquals("ok", line.fields["result"])
            line.fields.forEach { (key, value) ->
                assertTrue("诊断字段 $key=$value 不在白名单内", DiagnosticPolicy.isAllowedField(key, value))
            }
            // 不记路径名：任何字段值都不得包含子目录名、UUID 或文件后缀。
            line.fields.values.forEach { value ->
                assertFalse("盘点行泄露了路径：$value", value.contains(UUID36) || value.contains(".part") || value.endsWith(".json"))
                assertFalse(value.contains("dsh-runtime"))
            }
        }
    }

    @Test
    fun `目录为空时只留一条总计行`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")

        val lines = RuntimeResidueInventory(parent).lines("ready")

        assertEquals(1, lines.size)
        assertEquals("0", lines.single().fields["count"])
        assertEquals("0", lines.single().fields["bytes"])
        assertEquals("false", lines.single().fields["residual"])
    }

    @Test
    fun `没有残留时 residual 为 false`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        slot(parent, "current", bytes = 1)
        slot(parent, "previous", bytes = 1)

        val report = RuntimeResidueInventory(parent).report()

        assertFalse(report.hasResidue)
        val total = RuntimeResidueInventory(parent).lines("reset").first().fields
        assertEquals("false", total["residual"])
    }

    @Test
    fun `未知的时机名字会被压成合法 token`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        slot(parent, "current", bytes = 1)

        assertEquals("ready", RuntimeResidueInventory(parent).lines("Ready").first().fields["reason"])
        assertEquals("scan", RuntimeResidueInventory(parent).lines("").first().fields["reason"])
        // 超长时机名同样会被截断，而不是让整行被策略丢掉。
        val long = RuntimeResidueInventory(parent).lines("x".repeat(80)).first().fields["reason"]!!
        assertTrue(long.length <= RuntimeResidueTokens.MAX_CHARS)
        assertTrue(DiagnosticPolicy.isAllowedField("reason", long))
    }

    @Test
    fun `字节数会收敛到诊断策略允许的范围`() {
        assertEquals("0", residueBytesToken(-1))
        assertEquals("1024", residueBytesToken(1024))
        val clamped = residueBytesToken(Long.MAX_VALUE)
        assertEquals("999999999999", clamped)
        // 超过 12 位会被策略静默丢弃，所以必须自己收敛。
        assertTrue(DiagnosticPolicy.isAllowedField("bytes", clamped))
    }

    @Test
    fun `尺寸统计会递归累加目录内容`() {
        val target = temporaryFolder.newFolder("stale-previous-1a2b")
        File(target, "nested").mkdirs()
        File(target, "rootfs.bin").writeBytes(ByteArray(32))
        File(target, "nested/inner.bin").writeBytes(ByteArray(8))

        val size = RuntimeResidueSizing.of(target)

        assertEquals(40L, size.bytes)
        assertEquals(2, size.entries)
        assertFalse(size.truncated)
        assertEquals(0L, RuntimeResidueSizing.of(File(target, "not-there")).bytes)
    }

    @Test
    fun `断点续传分片会被单独归类`() {
        val parent = temporaryFolder.newFolder("dsh-runtime")
        // 名字必须与 RuntimeInstaller 的续传文件规则一致，否则盘点会把它算进 other。
        val name = "rootfs-${"b".repeat(64)}.part"
        assertTrue(RuntimeInstaller.RESUME_FILE.matches(name))
        slot(parent, name, bytes = 7)

        assertEquals("resume", RuntimeResidueInventory.categoryOf(name))
        assertEquals(mapOf("resume" to 7L), RuntimeResidueInventory(parent).report().categories.associate { it.token to it.bytes })
    }

    private fun slot(parent: File, name: String, bytes: Int): File {
        val file = File(parent, name)
        file.writeBytes(ByteArray(bytes))
        return file
    }

    private companion object {
        const val UUID36 = "0123456789abcdef0123456789abcdef0123"
    }
}
