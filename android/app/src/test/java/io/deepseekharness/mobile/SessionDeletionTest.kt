package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 删除已归档会话的三条硬约束：
 * 1. 只删已归档会话，且运行期一律拒绝（返回 `RUNTIME_RUNNING`，磁盘一个字节都不动）；
 * 2. 删掉会话日志目录与投影缓存，并从 workspace 注册表里摘掉记账；
 * 3. 拒绝原因走受控码（`SESSION_NOT_ARCHIVED` / `SESSION_NOT_FOUND` / `SESSION_DELETE_FAILED`），
 *    不把「删不了」说成「删掉了」。
 */
class SessionDeletionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun runtimeRoot(): File = temporaryFolder.newFolder("runtime")

    /** 造一条会话：`root/.dsh/sessions/<project>/<directory>/session.v4.jsonl`。 */
    private fun session(root: File, directory: String, project: String = "-root-project"): File {
        val directoryFile = File(root, "root/.dsh/sessions/$project/$directory")
        directoryFile.mkdirs()
        File(directoryFile, "session.v4.jsonl").writeText("""{"type":"session","version":4}""" + "\n")
        return directoryFile
    }

    private fun workspaceState(root: File, archived: List<String>, pinned: List<String> = emptyList()) {
        val state = File(root, "root/.dsh/storages/workspace.json")
        state.parentFile?.mkdirs()
        state.writeText(
            JSONObject()
                .put("unit", JSONObject().put("name", "workspace").put("version", 2))
                .put(
                    "global",
                    JSONObject()
                        .put("initialized", true)
                        .put("workspaceIds", JSONArray(listOf("ws-1")))
                        .put("archivedSessionIds", JSONArray(archived))
                        .put("pinnedSessionIds", JSONArray(pinned)),
                )
                .put(
                    "tables",
                    JSONObject().put(
                        "workspaces",
                        JSONObject().put(
                            "ws-1",
                            JSONObject().put("id", "ws-1").put("sessionIds", JSONArray(archived + listOf("session-live"))),
                        ),
                    ),
                )
                .toString(),
        )
    }

    private fun projectionCache(root: File, sessionId: String): File {
        val cache = File(root, "root/.dsh/storages/session_projcache/sessions")
        cache.mkdirs()
        return File(cache, "$sessionId.json").apply { writeText("{}") }
    }

    private fun delete(root: File, sessionId: String, running: Boolean = false): JSONObject =
        SessionDeletion.delete(root, sessionId, running)

    @Test
    fun `deletes the archived session log, its projection cache and its registry bookkeeping`() {
        val root = runtimeRoot()
        val directory = session(root, "session-old")
        val cache = projectionCache(root, "session-old")
        // 另一条未归档会话：必须原封不动。
        val live = session(root, "session-live")
        workspaceState(root, archived = listOf("session-old"))

        val result = delete(root, "session-old")

        assertTrue(result.getBoolean("deleted"))
        assertFalse("会话日志目录必须被删掉", directory.exists())
        assertFalse("投影缓存必须被删掉", cache.exists())
        assertTrue("别的会话一条都不能动", live.exists())

        val state = JSONObject(File(root, "root/.dsh/storages/workspace.json").readText())
        val global = state.getJSONObject("global")
        assertEquals(0, global.getJSONArray("archivedSessionIds").length())
        // 同一个 id 在 pinned 里也要摘掉：归档与置顶互斥，残留只会让 dsh 侧出现不存在的置顶。
        assertEquals(0, global.getJSONArray("pinnedSessionIds").length())
        val workspaceSessions = state.getJSONObject("tables").getJSONObject("workspaces")
            .getJSONObject("ws-1").getJSONArray("sessionIds")
        assertEquals(listOf("session-live"), (0 until workspaceSessions.length()).map { workspaceSessions.getString(it) })
    }

    @Test
    fun `refuses while the harness is running and leaves every file alone`() {
        val root = runtimeRoot()
        val directory = session(root, "session-old")
        workspaceState(root, archived = listOf("session-old"))

        val result = delete(root, "session-old", running = true)

        assertFalse(result.getBoolean("deleted"))
        assertEquals(SessionDeletion.RUNTIME_RUNNING, result.getString("reason"))
        assertTrue(directory.exists())
    }

    @Test
    fun `only deletes archived sessions and reports a session that is already gone`() {
        val root = runtimeRoot()
        session(root, "session-live")
        workspaceState(root, archived = listOf("session-gone"))

        val notArchived = delete(root, "session-live")
        assertFalse(notArchived.getBoolean("deleted"))
        assertEquals(SessionDeletion.SESSION_NOT_ARCHIVED, notArchived.getString("reason"))
        assertTrue("未归档会话必须还在", File(root, "root/.dsh/sessions/-root-project/session-live").exists())

        val missing = delete(root, "session-gone")
        assertFalse(missing.getBoolean("deleted"))
        assertEquals(SessionDeletion.SESSION_NOT_FOUND, missing.getString("reason"))
    }

    @Test
    fun `refuses to delete when the registry cannot be read`() {
        val root = runtimeRoot()
        val directory = session(root, "session-old")
        val state = File(root, "root/.dsh/storages/workspace.json")
        state.parentFile?.mkdirs()
        state.writeText("{not json")

        val result = delete(root, "session-old")

        assertFalse(result.getBoolean("deleted"))
        assertEquals(SessionDeletion.SESSION_DELETE_FAILED, result.getString("reason"))
        assertTrue("看不懂注册表时不是「删」，而是「不删」", directory.exists())
    }

    @Test
    fun `rejects identifiers that could escape the sessions directory`() {
        val root = runtimeRoot()
        for (sessionId in listOf("", "../escape", "a/b", "a b", ".hidden", "x".repeat(201))) {
            val failure = assertThrows(RuntimeFailure::class.java) { delete(root, sessionId) }
            assertEquals(SessionDeletion.SESSION_ID_INVALID, failure.code)
        }
    }

    @Test
    fun `finds an encoded session directory name by decoded identifier`() {
        val root = runtimeRoot()
        // 会话标识里的 `.` 在目录名里编码成 `~002E`（dsh 的目录编码规则）。
        val directory = session(root, "session_v4~002Etest")
        workspaceState(root, archived = listOf("session_v4.test"))

        val result = delete(root, "session_v4.test")

        assertTrue(result.getBoolean("deleted"))
        assertFalse(directory.exists())
    }
}
