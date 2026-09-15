package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `~/.dsh/.credentials.yaml` 保序编辑的纯文本测试。
 *
 * 这份文档同时承载 `refs`（模型 key）与 `records`（OAuth 授权记录等），
 * 而 App 只应该动 `refs` 段里属于它的那几行 —— **丢 record 就是丢用户的登录态**，
 * 所以「records 与注释逐行保留」在这里是硬断言，不是风格问题。
 */
class HarnessCredentialsDocumentTest {
    /** 一份形态完整的文档：version + refs + records + 注释。 */
    private val existingDocument = """
        # 由模型页写入
        version: 1
        refs:
          DEEPSEEK_API_KEY: 'sk-existing-value'
        records:
          llm-pi-ai/openai-codex:
            kind: grant
            payload:
              type: oauth
              access: sentinel-access-token
        """.trimIndent() + "\n"

    @Test
    fun `a missing document becomes a version one refs mapping`() {
        val updated = HarnessCredentialsDocument.upsert(null, mapOf("OPENAI_API_KEY" to "sk-sentinel"))

        assertEquals("version: 1\nrefs:\n  OPENAI_API_KEY: 'sk-sentinel'\n", updated)
        assertEquals(mapOf("OPENAI_API_KEY" to "sk-sentinel"), HarnessCredentialsDocument.refs(updated!!))
    }

    @Test
    fun `an existing document keeps its records and comments byte for byte`() {
        val updated = HarnessCredentialsDocument.upsert(
            existingDocument,
            mapOf("OPENAI_API_KEY" to "sk-sentinel-new"),
        )

        assertNotNull(updated)
        // 注释、已有的 ref、以及整段 records 都必须原样保留（只多出一行新 ref）。
        val lines = updated!!.split('\n')
        assertTrue(lines.contains("# 由模型页写入"))
        assertTrue(lines.contains("  DEEPSEEK_API_KEY: 'sk-existing-value'"))
        assertTrue(lines.contains("  llm-pi-ai/openai-codex:"))
        assertTrue(lines.contains("      access: sentinel-access-token"))
        assertEquals(
            existingDocument.trimEnd('\n').split('\n').size + 1,
            updated.trimEnd('\n').split('\n').size,
        )
        assertEquals(
            mapOf("DEEPSEEK_API_KEY" to "sk-existing-value", "OPENAI_API_KEY" to "sk-sentinel-new"),
            HarnessCredentialsDocument.refs(updated),
        )
    }

    @Test
    fun `an existing reference is replaced in place without touching its neighbours`() {
        val updated = HarnessCredentialsDocument.upsert(
            existingDocument,
            mapOf("DEEPSEEK_API_KEY" to "sk-rotated"),
        )

        assertNotNull(updated)
        assertTrue(updated!!.contains("  DEEPSEEK_API_KEY: 'sk-rotated'\n"))
        assertTrue(updated.contains("      access: sentinel-access-token"))
        assertEquals(existingDocument.trimEnd('\n').split('\n').size, updated.trimEnd('\n').split('\n').size)
    }

    @Test
    fun `a document already carrying every reference is returned untouched`() {
        val updates = mapOf("DEEPSEEK_API_KEY" to "sk-existing-value")

        assertEquals(existingDocument, HarnessCredentialsDocument.upsert(existingDocument, updates))
    }

    @Test
    fun `a document without a refs section gains one after the version line`() {
        val updated = HarnessCredentialsDocument.upsert(
            "version: 1\nrecords:\n  llm-pi-ai/openai-codex:\n    kind: api-key\n",
            mapOf("GEMINI_API_KEY" to "sk-sentinel"),
        )

        assertEquals(
            "version: 1\nrefs:\n  GEMINI_API_KEY: 'sk-sentinel'\nrecords:\n" +
                "  llm-pi-ai/openai-codex:\n    kind: api-key\n",
            updated,
        )
    }

    @Test
    fun `quotes and special characters in a value survive a round trip`() {
        val value = "sk-'quoted':#-value"

        val updated = HarnessCredentialsDocument.upsert(null, mapOf("OPENAI_API_KEY" to value))

        assertEquals(mapOf("OPENAI_API_KEY" to value), HarnessCredentialsDocument.refs(updated!!))
    }

    @Test
    fun `documents this editor must not rewrite are refused`() {
        val refused = listOf(
            // 顶层出现未知键：dsh 自己也会拒绝，改了只会制造一份两边都不认的文档。
            "version: 1\nunknown: value\nrefs:\n  DEEPSEEK_API_KEY: sk\n",
            // 版本不是 1。
            "version: 2\nrefs:\n  DEEPSEEK_API_KEY: sk\n",
            // 缺少 version（pre-release 扁平布局）。
            "refs:\n  DEEPSEEK_API_KEY: sk\n",
            // ref 是块标量：替换这一行会连它的续行一起破坏。
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: |\n    sk-multi\n    line\n",
            // ref 是流式集合。
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: [sk]\n",
            // 重复键。
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk\n  DEEPSEEK_API_KEY: sk2\n",
            // refs 段是流式映射。
            "version: 1\nrefs: {}\n",
            // CRLF：行下标假设不成立，一律放弃编辑。
            "version: 1\r\nrefs:\r\n  DEEPSEEK_API_KEY: sk\r\n",
            // records 不是映射。
            "version: 1\nrecords: []\n",
        )

        refused.forEach { text ->
            assertNull("本应拒绝编辑：$text", HarnessCredentialsDocument.upsert(text, mapOf("OPENAI_API_KEY" to "sk")))
        }
    }

    @Test
    fun `illegal updates are refused before any document is produced`() {
        assertNull(HarnessCredentialsDocument.upsert(null, mapOf("not a name" to "sk")))
        assertNull(HarnessCredentialsDocument.upsert(null, mapOf("OPENAI_API_KEY" to "has space")))
        assertNull(HarnessCredentialsDocument.upsert(null, mapOf("OPENAI_API_KEY" to "")))
    }

    @Test
    fun `reference reading fails closed on malformed or unsupported documents`() {
        // 与 HarnessCredentialStatusReader 的失败关闭口径一致：两份实现共用同一个解析器。
        val invalid = listOf(
            "version: 2\nrefs:\n  DEEPSEEK_API_KEY: value",
            "version: 1\nunknown: value\nrefs:\n  DEEPSEEK_API_KEY: value",
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: ''",
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: [value]",
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: first\n  DEEPSEEK_API_KEY: second",
            "just a scalar",
        )

        invalid.forEach { text -> assertNull("本应失败关闭：$text", HarnessCredentialsDocument.refs(text)) }
        assertEquals(emptyMap<String, String>(), HarnessCredentialsDocument.refs(""))
        assertEquals(emptyMap<String, String>(), HarnessCredentialsDocument.refs("version: 1\n"))
    }
}
