package io.deepseekharness.mobile.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「数据保留」页背后的文件操作：列出待处理副本、取回、删除。
 *
 * 重点覆盖**旧版本兼容**：老版本装出来的 `preserve-*` 没有 `preserve-report.json`，
 * 槽位也只有老的 7 项——这种目录必须照样能列出来、取回、删除，不能因为少一份清单就报错。
 */
class PreserveStoreTest {
    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    /** 旧版本留下的暂存目录：只有数据，没有清单。 */
    private fun legacyPreserve(parent: File, id: String = "preserve-0f8fad5b-d9cb-469f-a165-70867728950e"): File {
        val directory = File(parent, id)
        write(File(directory, "sessions/-root-project/session-a/session.v4.jsonl"), "会话日志正文")
        write(File(directory, "workspace/notes.md"), "用户笔记")
        return directory
    }

    @Test
    fun `旧版暂存目录没有清单也能列出来，分类按槽位判定`() {
        val parent = tempDir("preserve-state-")
        val root = tempDir("preserve-root-")
        val directory = legacyPreserve(parent)

        val state = PreserveStore(parent, root).state()

        assertEquals(1, state.pending.size)
        val pending = state.pending[0]
        assertEquals(directory.name, pending.id)
        assertEquals(setOf("sessions", "workspace"), pending.items.map { it.slot }.toSet())
        val sessions = pending.items.first { it.slot == "sessions" }
        assertEquals("conversations", sessions.category)
        assertEquals("root/.dsh/sessions", sessions.path)
        assertTrue(sessions.bytes > 0)
        val workspace = pending.items.first { it.slot == "workspace" }
        assertEquals("files", workspace.category)
        assertEquals("root/1", workspace.path)
        // 没有清单 → 没有回填结果可报，界面据此显示「没有记录」而不是编一个 0。
        assertNull(pending.report)
    }

    @Test
    fun `旧版暂存目录取回到工作区，同名文件顺延改名而不覆盖`() {
        val parent = tempDir("preserve-recover-")
        val root = tempDir("preserve-root-")
        val directory = legacyPreserve(parent)
        // 工作区里已经有同名文件：取回必须顺延改名，绝不覆盖。
        write(File(root, "root/1/recovered/${directory.name}/workspace/notes.md"), "旧副本")

        val store = PreserveStore(parent, root)
        val first = store.recover(directory.name)

        assertEquals("recovered/${directory.name}", first.target)
        assertEquals(2, first.files)
        assertTrue(first.bytes > 0)
        assertEquals("用户笔记", File(root, "root/1/recovered/${directory.name}/workspace/notes-1.md").readText())
        assertEquals("旧副本", File(root, "root/1/recovered/${directory.name}/workspace/notes.md").readText())
        // 取回是复制：暂存目录原件必须还在，用户还要靠它决定「保留还是删除」。
        assertTrue(File(directory, "workspace/notes.md").exists())

        // 第二次取回同样不覆盖，继续顺延。
        store.recover(directory.name)
        assertTrue(File(root, "root/1/recovered/${directory.name}/workspace/notes-2.md").exists())
    }

    @Test
    fun `没有安装运行时不取回，也不顺手造半份根目录`() {
        val parent = tempDir("preserve-noruntime-")
        val directory = legacyPreserve(parent)
        val missingRoot = File(tempDir("preserve-missing-"), "current")

        try {
            PreserveStore(parent, missingRoot).recover(directory.name)
            fail("没有运行时时取回应被拒绝")
        } catch (error: RuntimeFailure) {
            assertEquals("RUNTIME_NOT_INSTALLED", error.code)
        }
        assertFalse("不得创建半份根目录", missingRoot.exists())
    }

    @Test
    fun `删除只认保存目录，非法标识与不存在的目录都给受控错误`() {
        val parent = tempDir("preserve-discard-")
        val root = tempDir("preserve-root-")
        val directory = legacyPreserve(parent)
        val store = PreserveStore(parent, root)

        for (bad in listOf("", "../current", "preserve-", "preserve-not-a-uuid", "current")) {
            try {
                store.discard(bad)
                fail("非法标识应被拒绝：$bad")
            } catch (error: RuntimeFailure) {
                assertEquals("PRESERVE_INVALID_ID", error.code)
            }
        }
        try {
            store.discard("preserve-11111111-2222-3333-4444-555555555555")
            fail("不存在的暂存目录应被拒绝")
        } catch (error: RuntimeFailure) {
            assertEquals("PRESERVE_NOT_FOUND", error.code)
        }
        assertTrue(store.discard(directory.name))
        assertFalse(directory.exists())
        // 其它目录（例如 current）绝不能跟着被删。
        assertTrue(parent.exists())
    }

    @Test
    fun `新版清单里的扩展工作区槽位能按清单还原路径`() {
        val parent = tempDir("preserve-report-")
        val root = tempDir("preserve-root-")
        val directory = File(parent, "preserve-22222222-3333-4444-5555-666666666666")
        write(File(directory, "sessions/keep.jsonl"), "x")
        write(File(directory, "workspace/notes.md"), "y")
        write(File(directory, "workspace-2/project/main.go"), "package main")
        val paths = RuntimePreservePolicy.restoreSlots()
        paths["workspace-2"] = "root/dsh"
        write(
            File(directory, RuntimePreservePolicy.REPORT_FILE_NAME),
            RuntimePreservePolicy.reportJson(paths, restored = 1, merged = 3, conflicts = listOf("workspace/audit")),
        )

        val pending = PreserveStore(parent, root).state().pending.single()

        val extra = pending.items.first { it.slot == "workspace-2" }
        assertEquals("root/dsh", extra.path)
        assertEquals("files", extra.category)
        // 清单里的回填结果要能读出来，界面才能显示「上次回填了几项、还有几项待处理」。
        assertEquals(1, pending.report?.restored)
        assertEquals(3, pending.report?.merged)
        assertEquals(listOf("workspace/audit"), pending.report?.conflicts)
    }

    @Test
    fun `清单文件本身不算一条保留项`() {
        val parent = tempDir("preserve-report-only-")
        val root = tempDir("preserve-root-")
        val directory = File(parent, "preserve-33333333-4444-5555-6666-777777777777")
        write(File(directory, "workspace/notes.md"), "y")
        write(File(directory, RuntimePreservePolicy.REPORT_FILE_NAME), RuntimePreservePolicy.reportJson(RuntimePreservePolicy.preservedPaths(), 1, 0, emptyList()))

        val pending = PreserveStore(parent, root).state().pending.single()
        assertEquals(listOf("workspace"), pending.items.map { it.slot })
    }
}
