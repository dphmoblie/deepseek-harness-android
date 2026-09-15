package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单持久化的用例（内存替身，不碰 SharedPreferences）。
 *
 * 重点不是「能存能读」，而是容错口径：坏条目只丢自己、整份坏掉时报 corrupt、
 * 上限在写入点就被拒 —— 这三条决定了「用户目录被删后运行时还能不能起来」。
 */
class RuntimeStorageDirPreferencesTest {
    private val root = RuntimeStorageDirsLayout.PUBLIC_STORAGE

    @Test
    fun `writes and reads entries in order`() {
        val preferences = RuntimeStorageDirPreferences(InMemoryStorage())
        preferences.write(
            listOf(
                StorageDirEntry("$root/Download", "Download"),
                StorageDirEntry("$root/Documents/Project", "Project"),
            ),
        )

        val stored = preferences.read()
        assertEquals(0, stored.dropped)
        assertFalse(stored.corrupt)
        assertEquals(
            listOf("$root/Download" to "Download", "$root/Documents/Project" to "Project"),
            stored.entries.map { it.path to it.displayName },
        )
    }

    @Test
    fun `missing key reads as an empty whitelist`() {
        val stored = RuntimeStorageDirPreferences(InMemoryStorage()).read()
        assertTrue(stored.entries.isEmpty())
        assertEquals(0, stored.dropped)
        assertFalse(stored.corrupt)
    }

    @Test
    fun `unparseable document reads as corrupt instead of throwing`() {
        val storage = InMemoryStorage().apply {
            writeString(RuntimeStorageDirPreferences.KEY_ENTRIES, "{ not json")
        }
        val stored = RuntimeStorageDirPreferences(storage).read()
        assertTrue(stored.corrupt)
        assertTrue(stored.entries.isEmpty())
    }

    @Test
    fun `drops only the broken items and keeps the rest`() {
        val storage = InMemoryStorage().apply {
            writeString(
                RuntimeStorageDirPreferences.KEY_ENTRIES,
                """
                [
                  {"path":"$root/Download","name":"Download"},
                  {"path":"","name":"空路径"},
                  {"name":"缺路径"},
                  {"path":"$root/DCIM"},
                  "不是对象",
                  {"path":"$root/Download","name":"重复"},
                  {"path":"$root/DCIM","name":"DCIM"}
                ]
                """.trimIndent(),
            )
        }

        val stored = RuntimeStorageDirPreferences(storage).read()
        // 坏的只丢自己：空路径、缺路径、缺展示名、非对象、重复路径各算一条。
        assertEquals(
            listOf("$root/Download" to "Download", "$root/DCIM" to "DCIM"),
            stored.entries.map { it.path to it.displayName },
        )
        assertEquals(5, stored.dropped)
        assertFalse(stored.corrupt)
    }

    @Test
    fun `over limit items are dropped on read and rejected on write`() {
        val items = (1..(RuntimeStorageDirsLimits.MAX_DIRECTORIES + 2)).joinToString(",") { index ->
            """{"path":"$root/dir$index","name":"dir$index"}"""
        }
        val storage = InMemoryStorage().apply {
            writeString(RuntimeStorageDirPreferences.KEY_ENTRIES, "[$items]")
        }
        val stored = RuntimeStorageDirPreferences(storage).read()
        assertEquals(RuntimeStorageDirsLimits.MAX_DIRECTORIES, stored.entries.size)
        assertEquals(2, stored.dropped)

        val failure = assertThrows(RuntimeFailure::class.java) {
            RuntimeStorageDirPreferences(InMemoryStorage()).write(
                (1..(RuntimeStorageDirsLimits.MAX_DIRECTORIES + 1)).map {
                    StorageDirEntry("$root/dir$it", "dir$it")
                },
            )
        }
        assertEquals(StorageDirCodes.LIMIT_REACHED, failure.code)
    }

    @Test
    fun `display names are truncated when stored or read`() {
        val long = "x".repeat(200)
        val preferences = RuntimeStorageDirPreferences(InMemoryStorage())
        preferences.write(listOf(StorageDirEntry("$root/$long", long)))
        assertEquals(
            RuntimeStorageDirsLimits.MAX_DISPLAY_NAME_CHARS,
            preferences.read().entries.single().displayName.length,
        )
    }

    private class InMemoryStorage : RuntimeStorageDirPreferences.Storage {
        private val values = mutableMapOf<String, String>()

        override fun readString(key: String): String? = values[key]

        override fun writeString(key: String, value: String) {
            values[key] = value
        }
    }
}
