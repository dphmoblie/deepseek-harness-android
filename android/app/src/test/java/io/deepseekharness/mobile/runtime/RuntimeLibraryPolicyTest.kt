package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本库配额与淘汰的纯逻辑测试。
 *
 * 真正的淘汰动作在 [RuntimeLibrary] 里落盘（要 `Os.chmod`、原子改名、校验归档摘要，属设备侧验收项），
 * 这里穷举的是它背后的两条唯一规则：**放不放得下**，以及**满额时该淘汰谁**。
 *
 * 这两条规则决定用户锁定的版本会不会在他不知情的时候被删掉，所以必须能脱离设备穷举，
 * 而不是靠「大概不会被删吧」。
 */
class RuntimeLibraryPolicyTest {
    private fun entry(id: String, downloadedAt: Long, pinned: Boolean = false, active: Boolean = false) =
        RuntimeVersionPolicy.LibraryEntry(id = id, downloadedAt = downloadedAt, pinned = pinned, active = active)

    @Test
    fun acceptsNewArchiveWhileThereIsRoom() {
        for (count in 0 until RuntimeVersionPolicy.LIBRARY_CAPACITY) {
            assertTrue("count=$count 时应该还有空位", RuntimeVersionPolicy.canAddToLibrary(existing = false, count = count))
        }
        assertFalse(
            "满额后新增条目必须走淘汰",
            RuntimeVersionPolicy.canAddToLibrary(existing = false, count = RuntimeVersionPolicy.LIBRARY_CAPACITY),
        )
        // 超出容量（例如用户手改过目录）不能算「有空位」。
        assertFalse(RuntimeVersionPolicy.canAddToLibrary(existing = false, count = RuntimeVersionPolicy.LIBRARY_CAPACITY + 3))
    }

    @Test
    fun reDownloadOfAnExistingVersionDoesNotConsumeQuota() {
        assertTrue(RuntimeVersionPolicy.canAddToLibrary(existing = true, count = RuntimeVersionPolicy.LIBRARY_CAPACITY))
    }

    @Test
    fun capacityHasExactlyOneDefinition() {
        // 界面上的「已下载 X/5」与淘汰判定必须是同一个数，两处各写一个字面量迟早会分叉。
        assertEquals(5, RuntimeVersionPolicy.LIBRARY_CAPACITY)
        assertEquals(RuntimeVersionPolicy.LIBRARY_CAPACITY, RuntimeLibrary.MAX_CAPACITY)
    }

    @Test
    fun evictionPicksTheOldestUnpinnedEntry() {
        val entries = listOf(
            entry("b".repeat(64), downloadedAt = 30),
            entry("a".repeat(64), downloadedAt = 10),
            entry("c".repeat(64), downloadedAt = 20),
        )
        assertEquals("a".repeat(64), RuntimeVersionPolicy.evictionCandidate(entries, incomingId = null))
    }

    @Test
    fun lockedEntriesAreNeverEvicted() {
        val entries = listOf(
            entry("a".repeat(64), downloadedAt = 10, pinned = true),
            entry("b".repeat(64), downloadedAt = 30),
            entry("c".repeat(64), downloadedAt = 20),
        )
        assertEquals("c".repeat(64), RuntimeVersionPolicy.evictionCandidate(entries, incomingId = null))
    }

    @Test
    fun theActiveEntryIsNeverEvicted() {
        val entries = listOf(
            entry("a".repeat(64), downloadedAt = 10, active = true),
            entry("b".repeat(64), downloadedAt = 30),
        )
        assertEquals("b".repeat(64), RuntimeVersionPolicy.evictionCandidate(entries, incomingId = null))
    }

    @Test
    fun theIncomingEntryIsNeverEvicted() {
        // 重下同一个版本：它自己就在库里，淘汰逻辑不能先把它删掉再写进去。
        val entries = listOf(
            entry("a".repeat(64), downloadedAt = 10),
            entry("b".repeat(64), downloadedAt = 30),
        )
        assertEquals("b".repeat(64), RuntimeVersionPolicy.evictionCandidate(entries, incomingId = "a".repeat(64)))
    }

    @Test
    fun returnsNullWhenEverythingIsLocked() {
        val entries = listOf(
            entry("a".repeat(64), downloadedAt = 10, pinned = true),
            entry("b".repeat(64), downloadedAt = 30, pinned = true),
        )
        assertNull(
            "全锁定必须返回 null，让调用方明确失败，而不是偷偷删掉用户锁定的版本",
            RuntimeVersionPolicy.evictionCandidate(entries, incomingId = null),
        )
    }

    @Test
    fun returnsNullWhenThereIsNothingToChooseFrom() {
        assertNull(RuntimeVersionPolicy.evictionCandidate(emptyList(), incomingId = null))
        assertNull(RuntimeVersionPolicy.evictionCandidate(listOf(entry("a".repeat(64), 10, active = true)), incomingId = null))
    }

    @Test
    fun tiesAreBrokenByIdSoTheSameDiskStateAlwaysGivesTheSameAnswer() {
        val first = entry("a".repeat(64), downloadedAt = 42)
        val second = entry("b".repeat(64), downloadedAt = 42)
        val expected = "a".repeat(64)
        assertEquals(expected, RuntimeVersionPolicy.evictionCandidate(listOf(first, second), incomingId = null))
        assertEquals(expected, RuntimeVersionPolicy.evictionCandidate(listOf(second, first), incomingId = null))
    }

    @Test
    fun listOrderPutsTheNewestFirst() {
        // 界面按「最新的在前」折叠，默认只露前几条。顺序错了，被藏起来的就不是旧版本。
        val oldest = entry("a".repeat(64), downloadedAt = 10)
        val newest = entry("c".repeat(64), downloadedAt = 30)
        val middle = entry("b".repeat(64), downloadedAt = 20)
        // 入参顺序故意打乱：目录枚举顺序与时间无关，结果必须与入参顺序无关。
        val ordered = RuntimeVersionPolicy.listOrder(listOf(middle, oldest, newest))
        assertEquals(listOf("c".repeat(64), "b".repeat(64), "a".repeat(64)), ordered.map { it.id })
        assertEquals(ordered.map { it.id }, RuntimeVersionPolicy.listOrder(listOf(newest, middle, oldest)).map { it.id })
    }

    @Test
    fun listOrderIsTheReverseOfEvictionPreference() {
        // 两条规则方向相反：淘汰挑最旧，展示挑最新。写成同向就会「一进版本管理页
        // 看不到刚下载的那个」，而且因为库里通常只有 1 个包，很久都发现不了。
        val entries = listOf(
            entry("a".repeat(64), downloadedAt = 10),
            entry("b".repeat(64), downloadedAt = 20),
            entry("c".repeat(64), downloadedAt = 30),
        )
        assertEquals("a".repeat(64), RuntimeVersionPolicy.evictionCandidate(entries, incomingId = null))
        assertEquals("c".repeat(64), RuntimeVersionPolicy.listOrder(entries).first().id)
    }

    @Test
    fun listOrderBreaksTiesByIdDescendingForReproducibility() {
        val first = entry("a".repeat(64), downloadedAt = 42)
        val second = entry("b".repeat(64), downloadedAt = 42)
        val expected = listOf("b".repeat(64), "a".repeat(64))
        assertEquals(expected, RuntimeVersionPolicy.listOrder(listOf(first, second)).map { it.id })
        assertEquals(expected, RuntimeVersionPolicy.listOrder(listOf(second, first)).map { it.id })
    }

    @Test
    fun listOrderKeepsEntriesWithUnknownTimestampLast() {
        // `downloadedAt = 0` 是「时间读不到」（旧版本库没有 sidecar），不是「1970 年下载的」。
        // 它必须排在所有已知时间之后，否则损坏的元数据会把真实版本挤下折叠线。
        val unknown = entry("a".repeat(64), downloadedAt = 0)
        val known = entry("b".repeat(64), downloadedAt = 1)
        assertEquals(listOf("b".repeat(64), "a".repeat(64)), RuntimeVersionPolicy.listOrder(listOf(unknown, known)).map { it.id })
    }
}
