package io.deepseekharness.mobile.runtime

/** 只保存已通过桥接参数校验的会话摘要与轮次，不保存会话内容。 */
internal class TurnActivityState {
    data class Snapshot(val activeCount: Int, val startedAtMs: Long?)

    private data class Entry(
        val turn: Long,
        val started: Boolean,
        val lastSignalAtMs: Long,
        val startedAtMs: Long?,
    )

    private val sessions = LinkedHashMap<String, Entry>()

    /** 返回是否接受了新状态；同轮收口优先于开始，避免 HTTP 乱序使已完成任务复活。 */
    @Synchronized
    fun record(session: String, turn: Long, started: Boolean, nowMs: Long): Boolean {
        val previous = sessions[session]
        if (previous != null && (turn < previous.turn ||
                (turn == previous.turn && (!previous.started || started)))) return false
        sessions.remove(session)
        sessions[session] = Entry(turn, started, nowMs, if (started) nowMs else null)
        while (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.keys.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }

    @Synchronized
    fun snapshot(nowMs: Long): Snapshot {
        var activeCount = 0
        var startedAtMs: Long? = null
        for (entry in sessions.entries) {
            val value = entry.value
            val start = value.startedAtMs ?: continue
            if (nowMs - value.lastSignalAtMs >= STALE_AFTER_MS) {
                // 保留轮次墓碑，丢失收口的旧任务超时后不会被迟到的开始再次点亮。
                entry.setValue(value.copy(startedAtMs = null))
                continue
            }
            activeCount++
            if (startedAtMs == null || start < startedAtMs) startedAtMs = start
        }
        return Snapshot(activeCount, startedAtMs)
    }

    @Synchronized
    fun clear() = sessions.clear()

    companion object {
        const val MAX_SESSIONS = 256
        const val STALE_AFTER_MS = 6 * 60 * 60 * 1_000L
    }
}
