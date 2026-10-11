package io.deepseekharness.mobile.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * 迁移记账文件（`installed-migration.json`）的读取与写入用例。
 *
 * 这份记录只回答「上次成功迁移到哪一步」。读侧从严（不可用一律按"从未迁移过"处理，最多让
 * 幂等的迁移多跑一次）；写侧必须让调用方知道失败 —— 迁移跑完却记不上账，下次启动会重跑，
 * 这本身不是灾难，但**静默**是：用户会看到「迁移从未发生」而找不到原因。
 */
class PluginMigrationStateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val record = PluginMigrationState.Record(
        appVersionCode = 35,
        runtimeGeneration = "runtime-1|0.2.13|e3b0c44298fc1c14",
        migratedAtMillis = 1_700_000_000_000L,
        reason = PluginMigrationPolicy.REASON_APP_UPGRADED,
    )

    private fun target(): File = File(temporaryFolder.root, PluginMigrationState.FILE_NAME)

    private fun raw(text: String): File {
        val file = target()
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun json(
        appVersionCode: Int = 35,
        runtimeGeneration: Any = "runtime-1|0.2.13|e3b0c44298fc1c14",
        migratedAtMillis: Long = 1_700_000_000_000L,
        reason: String = PluginMigrationPolicy.REASON_APP_UPGRADED,
        schema: Int = PluginMigrationState.SCHEMA_VERSION,
    ): File = raw(
        JSONObject()
            .put("schemaVersion", schema)
            .put("appVersionCode", appVersionCode)
            .put("runtimeGeneration", runtimeGeneration)
            .put("migratedAtMillis", migratedAtMillis)
            .put("reason", reason)
            .toString(),
    )

    @Test
    fun `记录文件不存在时返回 null`() {
        assertNull(PluginMigrationState.read(target()))
    }

    @Test
    fun `写入后能原样读回`() {
        PluginMigrationState.write(target(), record)
        assertEquals(record, PluginMigrationState.read(target()))
    }

    @Test
    fun `运行时未装好时代次为空的记录也能读回`() {
        // installedManifest() 为 null（运行时还没装）时迁移照样要记账，否则永远停在"从未迁移过"。
        val empty = record.copy(runtimeGeneration = null, reason = PluginMigrationPolicy.REASON_FIRST_RUN)
        PluginMigrationState.write(target(), empty)
        val read = PluginMigrationState.read(target())
        assertNotNull(read)
        assertEquals(empty, read)
        assertNull(read!!.runtimeGeneration)
    }

    @Test
    fun `重复写入覆盖旧记录且不留临时文件`() {
        PluginMigrationState.write(target(), record)
        val next = record.copy(appVersionCode = 36, migratedAtMillis = 1_800_000_000_000L, reason = PluginMigrationPolicy.REASON_APP_UPGRADED)
        PluginMigrationState.write(target(), next)
        assertEquals(next, PluginMigrationState.read(target()))
        assertEquals(listOf(PluginMigrationState.FILE_NAME), temporaryFolder.root.list()!!.sorted())
    }

    @Test
    fun `目录不存在时会按需创建`() {
        val nested = File(File(temporaryFolder.root, "plugin-manager"), PluginMigrationState.FILE_NAME)
        PluginMigrationState.write(nested, record)
        assertEquals(record, PluginMigrationState.read(nested))
    }

    @Test
    fun `架构版本不认识时返回 null`() {
        assertNull(PluginMigrationState.read(json(schema = PluginMigrationState.SCHEMA_VERSION + 1)))
    }

    @Test
    fun `版本号非正时返回 null`() {
        assertNull(PluginMigrationState.read(json(appVersionCode = 0)))
        assertNull(PluginMigrationState.read(json(appVersionCode = -1)))
    }

    @Test
    fun `迁移时刻为负时返回 null`() {
        assertNull(PluginMigrationState.read(json(migratedAtMillis = -1L)))
    }

    @Test
    fun `原因不在受控取值内时返回 null`() {
        assertNull(PluginMigrationState.read(json(reason = "whatever")))
        // 与策略常量必须一一对应：多一个少一个都说明两边口径漂了。
        assertEquals(4, PluginMigrationState.REASONS.size)
    }

    @Test
    fun `代次指纹含非法字符时返回 null`() {
        assertNull(PluginMigrationState.read(json(runtimeGeneration = "runtime 1|0.2.13")))
        assertNull(PluginMigrationState.read(json(runtimeGeneration = "")))
    }

    @Test
    fun `文件超过字节上限时返回 null`() {
        val file = raw(" ".repeat((PluginMigrationState.MAX_BYTES + 1).toInt()))
        assertEquals(PluginMigrationState.MAX_BYTES + 1, file.length())
        assertNull(PluginMigrationState.read(file))
    }

    @Test
    fun `JSON 损坏时返回 null`() {
        assertNull(PluginMigrationState.read(raw("{ 半截内容")))
    }

    @Test
    fun `目标位置无法替换时写入抛出 IOException 而不是静默失败`() {
        // 造一个非空目录占住目标路径：delete() 失败 ⇒ 必须抛，让调用方记进迁移状态。
        val occupied = File(temporaryFolder.root, PluginMigrationState.FILE_NAME)
        occupied.mkdirs()
        File(occupied, "blocker").writeText("x")
        assertThrows(IOException::class.java) { PluginMigrationState.write(occupied, record) }
        assertEquals(listOf(PluginMigrationState.FILE_NAME), temporaryFolder.root.list()!!.sorted())
    }
}
