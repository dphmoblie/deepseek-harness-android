package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * 版本账本（`installed-lock.json`）读取与核对的用例。
 *
 * 这些用例用真实的临时目录跑：`PluginInstallLock` 是纯 `java.io`/`org.json` 代码，
 * 在 JVM 上能真读真写，因此「删掉包目录后是不是真的计成缺失」是被实测的，不是走查出来的。
 *
 * 两类断言各自的意义：
 *  - **读取侧从严**：任何一条字段不合法就整份不可用（返回 null）。一份被篡改的账本比没有账本
 *    更危险 —— 它会把「缺失」算成「在」，让用户看不到自己的插件已经接不回去了。
 *  - **核对侧只读**：`verify()` 只 stat 目录，不创建、不删除、不跟随越界链接；越界的畸形链接
 *    计缺失而不是被跟随。
 */
class PluginInstallLockTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val instant = "2025-01-02T03:04:05.678Z"

    /** 造一个合法条目；字段可用命名参数改成非法值，用来逐条钉校验口径。 */
    private fun entry(
        id: String = "demo-plugin",
        version: String? = "1.2.3",
        transaction: String? = null,
        source: String = "npm",
        updatedAt: String = instant,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("version", version ?: JSONObject.NULL)
        .put("transaction", transaction ?: JSONObject.NULL)
        .put("source", source)
        .put("updatedAt", updatedAt)

    private fun lock(rows: List<JSONObject>, schema: Int = PluginInstallLock.SCHEMA_VERSION): String =
        JSONObject().put("schemaVersion", schema).put("plugins", JSONArray(rows)).toString()

    private fun write(name: String, text: String): File {
        val file = File(temporaryFolder.root, name)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun lockFile(text: String): File = write(PluginInstallLock.FILE_NAME, text)

    private fun home(): File = temporaryFolder.newFolder("plugin-manager")

    /** 造出「包本体确实在保留区里」的目录：`versions/<事务>/node_modules/<包名>`。 */
    private fun packageDir(home: File, transaction: String, id: String): File {
        val directory = File(File(File(home, "versions"), transaction), "node_modules/$id")
        directory.mkdirs()
        return directory
    }

    private fun transaction(): String = UUID.randomUUID().toString()

    @Test
    fun `账本文件不存在时返回 null`() {
        assertNull(PluginInstallLock.read(File(temporaryFolder.root, PluginInstallLock.FILE_NAME)))
    }

    @Test
    fun `合法账本按条目顺序解析出包名版本事务目录与来源`() {
        val first = transaction()
        val snapshot = PluginInstallLock.read(
            lockFile(
                lock(
                    listOf(
                        entry(id = "@scope/tool", version = "2.0.0-beta.1+build7", transaction = first, source = "bundle"),
                        entry(id = "plain", version = null, source = "unknown"),
                    ),
                ),
            ),
        )
        assertNotNull(snapshot)
        val entries = snapshot!!.entries
        assertEquals(2, entries.size)
        assertEquals("@scope/tool", entries[0].id)
        assertEquals("2.0.0-beta.1+build7", entries[0].version)
        assertEquals(first, entries[0].transaction)
        assertEquals("bundle", entries[0].source)
        assertNull(entries[1].version)
        assertNull(entries[1].transaction)
        assertEquals("unknown", entries[1].source)
    }

    @Test
    fun `架构版本不认识时整份账本不可用`() {
        assertNull(PluginInstallLock.read(lockFile(lock(listOf(entry()), schema = PluginInstallLock.SCHEMA_VERSION + 1))))
    }

    @Test
    fun `缺字段的条目让整份账本不可用`() {
        val full = entry()
        val cases = listOf(
            "缺 id" to JSONObject(full.toString()).apply { remove("id") },
            "缺 version" to JSONObject(full.toString()).apply { remove("version") },
            "缺 transaction" to JSONObject(full.toString()).apply { remove("transaction") },
            "缺 source" to JSONObject(full.toString()).apply { remove("source") },
            "缺 updatedAt" to JSONObject(full.toString()).apply { remove("updatedAt") },
        )
        for ((name, row) in cases) {
            assertNull("$name 时应不可用", PluginInstallLock.read(lockFile(lock(listOf(row)))))
        }
    }

    @Test
    fun `字段格式非法的条目让整份账本不可用`() {
        val cases = listOf(
            "包名大写" to entry(id = "Demo"),
            "包名带上级目录" to entry(id = ".."),
            "版本不是三段式" to entry(version = "1.2"),
            "版本带空格" to entry(version = "1.2.3 "),
            "事务目录不是 UUID 形状" to entry(transaction = "not-a-uuid"),
            "事务目录长度对但字符非十六进制" to entry(transaction = "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"),
            "来源不在受控取值内" to entry(source = "sideload"),
            "时间戳不是毫秒精度 UTC" to entry(updatedAt = "2025-01-02T03:04:05Z"),
        )
        for ((name, row) in cases) {
            assertNull("$name 时应不可用", PluginInstallLock.read(lockFile(lock(listOf(row)))))
        }
    }

    @Test
    fun `重复包名让整份账本不可用`() {
        assertNull(PluginInstallLock.read(lockFile(lock(listOf(entry(), entry())))))
    }

    @Test
    fun `条目数恰好到上限仍然可用，超过即不可用`() {
        val atLimit = (0 until PluginInstallLock.MAX_ENTRIES).map { entry(id = "plugin-$it") }
        assertEquals(PluginInstallLock.MAX_ENTRIES, PluginInstallLock.read(lockFile(lock(atLimit)))?.entries?.size)
        val overLimit = atLimit + entry(id = "one-more")
        assertNull(PluginInstallLock.read(lockFile(lock(overLimit))))
    }

    @Test
    fun `文件超过字节上限时不再解析`() {
        val file = lockFile(" ".repeat((PluginInstallLock.MAX_BYTES + 1).toInt()))
        assertEquals(PluginInstallLock.MAX_BYTES + 1, file.length())
        assertNull(PluginInstallLock.read(file))
    }

    @Test
    fun `JSON 损坏时返回 null`() {
        assertNull(PluginInstallLock.read(lockFile("{ 这不是 JSON")))
    }

    @Test
    fun `包目录被删掉后该条目计为缺失`() {
        val home = home()
        val kept = transaction()
        val gone = transaction()
        packageDir(home, kept, "kept-plugin")
        packageDir(home, gone, "gone-plugin").deleteRecursively()
        val snapshot = PluginInstallLock.read(
            lockFile(lock(listOf(entry(id = "kept-plugin", transaction = kept), entry(id = "gone-plugin", transaction = gone)))),
        )!!
        val verification = PluginInstallLock.verify(home, snapshot)
        assertEquals(2, verification.checked)
        assertEquals(1, verification.missing)
    }

    @Test
    fun `包目录是普通文件而不是目录时计为缺失`() {
        val home = home()
        val id = "file-plugin"
        val name = transaction()
        val directory = File(File(File(home, "versions"), name), "node_modules/$id")
        directory.parentFile?.mkdirs()
        directory.writeText("not a directory")
        val snapshot = PluginInstallLock.read(lockFile(lock(listOf(entry(id = id, transaction = name)))))!!
        assertEquals(1, PluginInstallLock.verify(home, snapshot).missing)
    }

    @Test
    fun `只有版本没有事务目录的条目不计缺失`() {
        // 官方包落在运行时自己的目录（例如 opt/dsh/node_modules），账本不记那条路径 —— 如实不猜。
        val home = home()
        val snapshot = PluginInstallLock.read(lockFile(lock(listOf(entry(id = "official", version = "0.2.13", source = "bundle")))))!!
        val verification = PluginInstallLock.verify(home, snapshot)
        assertEquals(1, verification.checked)
        assertEquals(0, verification.missing)
    }

    @Test
    fun `版本为空的条目直接计缺失`() {
        val home = home()
        val snapshot = PluginInstallLock.read(lockFile(lock(listOf(entry(id = "unknown-version", version = null)))))!!
        assertEquals(1, PluginInstallLock.verify(home, snapshot).missing)
    }

    @Test
    fun `包名带作用域时按 node_modules 下的真实路径核对`() {
        val home = home()
        val name = transaction()
        packageDir(home, name, "@scope/tool")
        val snapshot = PluginInstallLock.read(lockFile(lock(listOf(entry(id = "@scope/tool", transaction = name)))))!!
        assertEquals(0, PluginInstallLock.verify(home, snapshot).missing)
    }

    @Test
    fun `核对不改动任何文件`() {
        val home = home()
        val name = transaction()
        packageDir(home, name, "demo-plugin").let { File(it, "index.js").writeText("module.exports = 1") }
        val snapshot = PluginInstallLock.read(lockFile(lock(listOf(entry(id = "demo-plugin", transaction = name)))))!!
        val before = home.walkTopDown().map { it.path to it.isDirectory }.toList()
        PluginInstallLock.verify(home, snapshot)
        assertEquals(before, home.walkTopDown().map { it.path to it.isDirectory }.toList())
    }

    @Test
    fun `事务目录是指向保留区外的符号链接时计缺失`() {
        // 畸形链接必须计缺失，不能被跟随：否则越界的一份目录会被当成"插件还在"。
        // Windows 上没有创建符号链接的权限时跳过（不计失败），这与仓库里其他符号链接用例同口径。
        val home = home()
        val outside = temporaryFolder.newFolder("outside")
        val name = transaction()
        File(outside, "node_modules/demo-plugin").mkdirs()
        File(home, "versions").mkdirs()
        val link = File(File(home, "versions"), name)
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (failure: Exception) {
            assumeNoException(failure)
        }
        val snapshot = PluginInstallLock.read(lockFile(lock(listOf(entry(id = "demo-plugin", transaction = name)))))!!
        assertTrue(link.isDirectory)
        assertEquals(1, PluginInstallLock.verify(home, snapshot).missing)
    }

    @Test
    fun `包名判定与账本读取共用同一把尺子`() {
        // 逐包清理的入口直接调这个判定（桥接层用它挡下不合形状的串），所以它必须**就是**
        // 账本读取那套口径。两处一旦分叉，桥接层放过的串会在读取侧被判非法，
        // 于是「清这个插件」变成「账本读不出来，未清理任何数据」——一个查不出来的空转。
        val legal = listOf("left-pad", "a", "@scope/tool", "a.b-c_d", "x".repeat(214))
        val illegal = listOf(
            "", "..", "../evil", "a/../b", "a/b", "UPPER", "@scope/", "a b", "a\\b",
            "-leading", "x".repeat(215),
        )
        legal.forEach { assertTrue(it, PluginInstallLock.isPackageName(it)) }
        illegal.forEach { assertFalse(it, PluginInstallLock.isPackageName(it)) }
    }
}
