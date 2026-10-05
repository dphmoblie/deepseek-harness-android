package io.deepseekharness.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话元数据载荷的白名单与降级语义。
 *
 * 这些用例钉住三件事：
 * 1. 只有 `id`/`title`/`updatedAt` 能出去——正文、`cwd`、路径与未知字段**结构性**消失；
 * 2. 读不到时回受控错误字段，**不用空列表冒充「没有会话」**；
 * 3. 条数上限与丢弃规则不会让界面声称「这就是全部」。
 */
class SessionCatalogTest {
    /** 一条访客可能回传的会话条目；`extra` 用来塞多余字段，验证白名单是结构性投影。 */
    private fun entry(id: String, title: String, updatedAt: Long, extra: Map<String, Any> = emptyMap()): JSONObject {
        val json = JSONObject().put("id", id).put("title", title).put("updatedAt", updatedAt)
        extra.forEach { (key, value) -> json.put(key, value) }
        return json
    }

    private fun payload(vararg entries: JSONObject): JSONObject =
        JSONObject().put("sessions", JSONArray(entries.toList())).put("truncated", false)

    @Test
    fun mapsSessionsAndKeepsTheGuestOrder() {
        val result = SessionCatalogPayload.fromProbe(
            true,
            false,
            payload(entry("session-b", "标题 B", 2000L), entry("session-a", "标题 A", 1000L)).toString() + "\n",
        )
        assertFalse(result.has("error"))
        val sessions = result.getJSONArray("sessions")
        assertEquals(2, sessions.length())
        assertEquals("session-b", sessions.getJSONObject(0).getString("id"))
        assertEquals("标题 B", sessions.getJSONObject(0).getString("title"))
        assertEquals(2000L, sessions.getJSONObject(0).getLong("updatedAt"))
        assertEquals("session-a", sessions.getJSONObject(1).getString("id"))
        assertFalse(result.getBoolean("truncated"))
    }

    @Test
    fun readsTheLastLineOfTheGuestOutputOnly() {
        val output = "proot: 正在启动\n诊断行\n" + payload(entry("session-a", "标题", 1000L)).toString() + "\n"
        val result = SessionCatalogPayload.fromProbe(true, false, output)
        assertEquals(1, result.getJSONArray("sessions").length())
    }

    @Test
    fun capsSessionsAtFiftyAndFlagsTruncation() {
        val entries = (1..60).map { entry("session-$it", "标题 $it", it.toLong()) }
        val result = SessionCatalogPayload.fromProbe(true, false, payload(*entries.toTypedArray()).toString())
        val sessions = result.getJSONArray("sessions")
        assertEquals(50, sessions.length())
        assertEquals("session-1", sessions.getJSONObject(0).getString("id"))
        assertEquals("session-50", sessions.getJSONObject(49).getString("id"))
        assertTrue(result.getBoolean("truncated"))
    }

    @Test
    fun reportsControlledErrorFieldsWhenTheSourceIsUnavailable() {
        val timedOut = SessionCatalogPayload.fromProbe(false, true, "")
        assertEquals(SessionCatalogPayload.TIMEOUT, timedOut.getString("error"))
        assertFalse(timedOut.has("sessions"))
        // 只有错误码：原始输出（可能含访客路径）一律不进回传体。
        assertEquals(1, timedOut.length())

        val failed = SessionCatalogPayload.fromProbe(false, false, "proot error: 打不开 /root/.dsh/sessions\n")
        assertEquals(SessionCatalogPayload.FAILED, failed.getString("error"))
        assertFalse(failed.has("sessions"))
        assertEquals(1, failed.length())

        val guestFailure = SessionCatalogPayload.fromProbe(true, false, "{\"error\":\"SESSION_CATALOG_UNREADABLE\"}\n")
        assertEquals(SessionCatalogPayload.FAILED, guestFailure.getString("error"))
        assertFalse(guestFailure.has("sessions"))

        val missingRuntime = SessionCatalogPayload.unavailable(SessionCatalogPayload.NOT_INSTALLED)
        assertEquals("RUNTIME_NOT_INSTALLED", missingRuntime.getString("error"))
        assertFalse(missingRuntime.has("sessions"))
    }

    @Test
    fun dropsBodyPathsAndUnknownFields() {
        val body = "ZZ-SESSION-BODY-SENTINEL"
        val hostile = entry(
            "session-a",
            "正常标题",
            1000L,
            mapOf(
                "cwd" to "/root/secret-project",
                "path" to "/root/.dsh/sessions/-root-secret/session-a/session.v4.jsonl.zstd",
                "snippet" to body,
                "content" to body,
                "message" to body,
                "events" to JSONArray().put(JSONObject().put("type", "user/message").put("text", body)),
            ),
        )
        val result = SessionCatalogPayload.fromProbe(true, false, payload(hostile).toString())
        val session = result.getJSONArray("sessions").getJSONObject(0)
        assertEquals(setOf("id", "title", "updatedAt"), session.keys().asSequence().toSet())
        val serialized = result.toString()
        for (forbidden in listOf(body, "/root", ".dsh", "cwd", "snippet", "path", "events")) {
            assertFalse("回传体里不得出现 $forbidden", serialized.contains(forbidden))
        }
    }

    @Test
    fun dropsEntriesWithUnusableIdentifiersOrTimestamps() {
        val result = SessionCatalogPayload.fromProbe(
            true,
            false,
            payload(
                entry("session-a", "标题 A", 1000L),
                entry("会话-一", "中文 id 是合法形态", 1000L),
                entry("../etc/passwd", "越界 id", 1000L),
                entry("session/b", "带斜杠的 id", 1000L),
                entry("session\\b", "带反斜杠的 id", 1000L),
                entry("session b", "带空格的 id", 1000L),
                entry(".hidden", "点开头的 id", 1000L),
                entry("", "空 id", 1000L),
                entry("session-c", "负时间", -5L),
                entry("session-d", "越界时间", SessionCatalogPayload.MAX_TIMESTAMP_MILLIS + 1),
            ).toString(),
        )
        val sessions = result.getJSONArray("sessions")
        assertEquals(2, sessions.length())
        assertEquals("session-a", sessions.getJSONObject(0).getString("id"))
        assertEquals("会话-一", sessions.getJSONObject(1).getString("id"))
        // 丢过条目就不能声称「这就是全部」。
        assertTrue(result.getBoolean("truncated"))
    }

    @Test
    fun treatsAPayloadWhereEveryEntryIsUnusableAsUnavailableInsteadOfEmpty() {
        val result = SessionCatalogPayload.fromProbe(true, false, payload(entry("session/a", "x", 1L)).toString())
        assertEquals(SessionCatalogPayload.FAILED, result.getString("error"))
        assertFalse(result.has("sessions"))
    }

    @Test
    fun blanksUnusableTitlesButKeepsTheSession() {
        val result = SessionCatalogPayload.fromProbe(
            true,
            false,
            payload(
                entry("session-a", "带\u0007控制字符", 1000L),
                entry("session-b", "x".repeat(SessionCatalogPayload.MAX_TITLE_CHARS + 1), 1000L),
                entry("session-c", "", 1000L),
                entry("session-d", "真正好标题", 1000L),
            ).toString(),
        )
        val sessions = result.getJSONArray("sessions")
        assertEquals(4, sessions.length())
        assertEquals("", sessions.getJSONObject(0).getString("title"))
        assertEquals("", sessions.getJSONObject(1).getString("title"))
        assertEquals("", sessions.getJSONObject(2).getString("title"))
        assertEquals("真正好标题", sessions.getJSONObject(3).getString("title"))
        assertFalse(result.getBoolean("truncated"))
    }

    @Test
    fun rejectsOversizedGarbageAndStructurallyInvalidPayloads() {
        val cases = listOf(
            "x".repeat(SessionCatalogPayload.MAX_PAYLOAD_CHARS + 1),
            "",
            "   ",
            "not json",
            JSONObject().put("truncated", false).toString(),
            JSONObject().put("sessions", "不是数组").toString(),
            JSONObject().put("sessions", JSONArray().put("不是对象")).toString(),
        )
        for (output in cases) {
            val result = SessionCatalogPayload.fromProbe(true, false, output)
            assertEquals(SessionCatalogPayload.FAILED, result.getString("error"))
            assertFalse(result.has("sessions"))
        }
        // 全是空数组是合法载荷（真的没有会话），与「读不到」必须区分开。
        val empty = SessionCatalogPayload.fromProbe(true, false, JSONObject().put("sessions", JSONArray()).toString())
        assertEquals(0, empty.getJSONArray("sessions").length())
        assertFalse(empty.has("error"))
    }
}
