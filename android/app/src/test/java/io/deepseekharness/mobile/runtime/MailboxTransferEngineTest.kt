package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.optionalMailboxRoot
import io.deepseekharness.mobile.optionalStorageGuestPath
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 投递区与共享目录合并搬运核心（[MailboxTransferEngine]）及桥参数解析的真实文件系统测试。
 *
 * 重点验证：任意已通过边界校验的外部目录（如共享目录 `/mnt/user/<N>` 或其子目录）都可以作为
 * 导出目标和导入来源，且导出自证、manifest/sha256 校验、工作区原子替换与投递区路径完全一致。
 */
class MailboxTransferEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `exports workspace to a shared directory and imports it back with manifest verification`() {
        val runtimeParent = temporaryFolder.newFolder("runtime-parent")
        val workspace = File(runtimeParent, "current/root/1").apply { mkdirs() }
        File(workspace, "notes").mkdirs()
        File(workspace, "notes/plan.md").writeText("# 计划\n完成合并\n", Charsets.UTF_8)
        File(workspace, "README.txt").writeText("hello shared dir\n", Charsets.UTF_8)

        val sharedRoot = temporaryFolder.newFolder("shared-dir")
        val subDir = File(sharedRoot, "snapshots/2026").apply { mkdirs() }
        val events = mutableListOf<Map<String, String>>()
        val engine = MailboxTransferEngine(
            workspace = workspace,
            stagingParent = runtimeParent,
            recordOutcome = { _, fields -> events += fields },
        )

        val exported = engine.exportWorkspaceToDirectory(
            subdirectory = null,
            outputDirectory = subDir,
            destinationDirectory = "snapshots/2026",
        )
        assertEquals(RuntimeMailboxLayout.EXPORT_TAR_NAME, exported.tarName)
        assertEquals(RuntimeMailboxLayout.EXPORT_MANIFEST_NAME, exported.manifestName)
        assertEquals("snapshots/2026", exported.destinationDirectory)
        assertEquals(3, exported.entryCount)
        assertTrue(File(subDir, RuntimeMailboxLayout.EXPORT_TAR_NAME).isFile)
        assertTrue(File(subDir, RuntimeMailboxLayout.EXPORT_MANIFEST_NAME).isFile)
        assertTrue(File(subDir, RuntimeMailboxLayout.EXPORT_SHA256_NAME).isFile)

        // 把同一共享目录里的归档导回工作区的 mailbox-import/，验证端到端闭环。
        val imported = engine.importFromDirectory(subDir)
        assertTrue(imported.verified)
        assertEquals(RuntimeMailboxLayout.EXPORT_TAR_NAME, imported.tarName)
        assertEquals(RuntimeMailboxLayout.EXPORT_MANIFEST_NAME, imported.manifestName)
        assertEquals(RuntimeMailboxLayout.IMPORT_DIRECTORY, imported.target)
        assertEquals(3, imported.entryCount)

        val restored = File(workspace, "${RuntimeMailboxLayout.IMPORT_DIRECTORY}/notes/plan.md")
        assertTrue(restored.isFile)
        assertEquals("# 计划\n完成合并\n", restored.readText(Charsets.UTF_8))
        assertEquals(listOf("export", "import"), events.map { it["reason"] })
    }

    @Test
    fun `rejects missing workspace or missing external directory with controlled codes`() {
        val runtimeParent = temporaryFolder.newFolder("missing-parent")
        val missingWorkspace = File(runtimeParent, "not-installed/root/1")
        val sharedDir = temporaryFolder.newFolder("shared-empty")

        val engine = MailboxTransferEngine(missingWorkspace, runtimeParent)
        val noWorkspace = assertThrows(RuntimeFailure::class.java) {
            engine.exportWorkspaceToDirectory(null, sharedDir)
        }
        assertEquals(MailboxCodes.WORKSPACE_UNAVAILABLE, noWorkspace.code)

        missingWorkspace.mkdirs()
        val missingSource = File(sharedDir, "does-not-exist")
        val noSource = assertThrows(RuntimeFailure::class.java) {
            engine.importFromDirectory(missingSource)
        }
        assertEquals(MailboxCodes.UNAVAILABLE, noSource.code)

        val noTar = assertThrows(RuntimeFailure::class.java) {
            engine.importFromDirectory(sharedDir)
        }
        assertEquals(MailboxCodes.INPUT_INVALID, noTar.code)
    }

    @Test
    fun `validates optional guestPath and mailbox root bridge parameters strictly`() {
        assertEquals(null, optionalStorageGuestPath(JSONObject()))
        assertEquals("/mnt/user/1", optionalStorageGuestPath(JSONObject().put("guestPath", "/mnt/user/1")))
        assertEquals(
            StorageDirCodes.PATH_INVALID,
            assertThrows(RuntimeFailure::class.java) {
                optionalStorageGuestPath(JSONObject().put("guestPath", "   "))
            }.code,
        )
        assertEquals(
            StorageDirCodes.PATH_INVALID,
            assertThrows(RuntimeFailure::class.java) {
                optionalStorageGuestPath(JSONObject().put("guestPath", 42))
            }.code,
        )

        assertEquals(null, optionalMailboxRoot(JSONObject()))
        assertEquals("inbox", optionalMailboxRoot(JSONObject().put("root", "inbox")))
        assertEquals("outbox", optionalMailboxRoot(JSONObject().put("root", "outbox")))
        assertEquals(
            "MAILBOX_ROOT_INVALID",
            assertThrows(RuntimeFailure::class.java) {
                optionalMailboxRoot(JSONObject().put("root", "other"))
            }.code,
        )
    }

    @Test
    fun `re-importing from shared directory atomically replaces prior mailbox-import content`() {
        val runtimeParent = temporaryFolder.newFolder("replace-parent")
        val workspace = File(runtimeParent, "current/root/1").apply { mkdirs() }
        val prior = File(workspace, "${RuntimeMailboxLayout.IMPORT_DIRECTORY}/stale.txt").apply {
            parentFile?.mkdirs()
            writeText("stale", Charsets.UTF_8)
        }
        val sub = File(workspace, "bundle").apply { mkdirs() }
        File(sub, "fresh.txt").writeText("fresh", Charsets.UTF_8)

        val sharedDir = temporaryFolder.newFolder("shared-replace")
        val engine = MailboxTransferEngine(workspace, runtimeParent)
        engine.exportWorkspaceToDirectory("bundle", sharedDir)
        engine.importFromDirectory(sharedDir)

        assertTrue(!prior.exists())
        assertEquals(
            "fresh",
            File(workspace, "${RuntimeMailboxLayout.IMPORT_DIRECTORY}/bundle/fresh.txt").readText(Charsets.UTF_8),
        )
    }
}
