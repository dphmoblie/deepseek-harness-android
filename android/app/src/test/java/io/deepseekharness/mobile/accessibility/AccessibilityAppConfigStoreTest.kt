package io.deepseekharness.mobile.accessibility

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccessibilityAppConfigStore]：按应用参数的持久化、越界拒绝与**损坏时的 fail-closed**。
 *
 * 用内存替身而不是真 SharedPreferences：这里要验的是「坏数据不删也不放行」「越界写入不留半份数据」，
 * 在设备上得先装应用、把偏好文件手工改坏才能复现一次。
 *
 * 每个用例新建一份 [FakeStorage]（store 是无状态 object，但存储替身必须隔离）。
 */
class AccessibilityAppConfigStoreTest {

    @Test
    fun `没写过读出默认值且没有暂停`() {
        val storage = FakeStorage()

        assertEquals(
            AccessibilityAppConfigRead.Ok(AccessibilityAppConfig()),
            AccessibilityAppConfigStore.read(storage, "com.example.reader"),
        )
        assertFalse(AccessibilityAppConfigStore.isPaused(storage, "com.example.reader"))
    }

    @Test
    fun `写入后读回逐字段相等，热路径也认暂停`() {
        val storage = FakeStorage()
        val config = AccessibilityAppConfig(
            paused = true,
            defaultMaxActions = 5,
            defaultActionCoolDownMs = 1200,
            defaultMatchDelayMs = 300,
        )

        AccessibilityAppConfigStore.write(storage, "com.example.reader", config)

        assertEquals(
            AccessibilityAppConfigRead.Ok(config),
            AccessibilityAppConfigStore.read(storage, "com.example.reader"),
        )
        assertTrue(AccessibilityAppConfigStore.isPaused(storage, "com.example.reader"))
    }

    @Test
    fun `写入是整条替换，不会留下上一版的字段`() {
        val storage = FakeStorage()
        AccessibilityAppConfigStore.write(
            storage,
            "com.example.reader",
            AccessibilityAppConfig(paused = true, defaultMaxActions = 9, defaultActionCoolDownMs = 700, defaultMatchDelayMs = 250),
        )
        AccessibilityAppConfigStore.write(storage, "com.example.reader", AccessibilityAppConfig())

        assertEquals(
            AccessibilityAppConfigRead.Ok(AccessibilityAppConfig()),
            AccessibilityAppConfigStore.read(storage, "com.example.reader"),
        )
        // 一个应用一个键：整条替换后不该多出第二个键。
        assertEquals(1, storage.keys().size)
    }

    @Test
    fun `暂停只影响它自己那个应用`() {
        val storage = FakeStorage()
        AccessibilityAppConfigStore.write(storage, "com.example.reader", AccessibilityAppConfig(paused = true))

        assertTrue(AccessibilityAppConfigStore.isPaused(storage, "com.example.reader"))
        assertFalse(AccessibilityAppConfigStore.isPaused(storage, "com.example.other"))
    }

    @Test
    fun `包名非法直接拒绝`() {
        val storage = FakeStorage()

        assertThrows(IllegalArgumentException::class.java) {
            AccessibilityAppConfigStore.write(storage, "not a package", AccessibilityAppConfig())
        }
        assertTrue(storage.keys().isEmpty())
    }

    @Test
    fun `越界的默认值被拒绝且不留半份数据`() {
        val storage = FakeStorage()

        // 上限、冷却、延迟三者各自越界一次：拒写之后盘上必须是空的（不能出现「暂停写进去了、
        // 数字还是旧的」这种中间态）。
        assertThrows(IllegalArgumentException::class.java) {
            AccessibilityAppConfigStore.write(storage, "com.example.reader", AccessibilityAppConfig(defaultMaxActions = 100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessibilityAppConfigStore.write(
                storage,
                "com.example.reader",
                AccessibilityAppConfig(defaultActionCoolDownMs = 600_001),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessibilityAppConfigStore.write(storage, "com.example.reader", AccessibilityAppConfig(defaultMatchDelayMs = 5_001))
        }
        assertTrue(storage.keys().isEmpty())
    }

    @Test
    fun `坏 JSON 按已暂停处理且不自动删除`() {
        val storage = FakeStorage()
        storage.write(AccessibilityAppConfigStore.key("com.example.reader"), "{不是 JSON")

        val read = AccessibilityAppConfigStore.read(storage, "com.example.reader")
        assertTrue(read is AccessibilityAppConfigRead.Corrupt)
        assertEquals(true, (read as AccessibilityAppConfigRead.Corrupt).config.paused)
        // fail closed：读不出来时热路径必须按暂停处理，否则用户按过的暂停会因为文件损坏而失效。
        assertTrue(AccessibilityAppConfigStore.isPaused(storage, "com.example.reader"))
        // 坏数据留着：自动删掉等于把「已暂停」静默改回「继续动」，只能由用户保存一次覆盖。
        assertEquals("{不是 JSON", storage.read(AccessibilityAppConfigStore.key("com.example.reader")))
    }

    @Test
    fun `字段缺失或类型不对都算损坏`() {
        val missing = FakeStorage()
        missing.write(AccessibilityAppConfigStore.key("com.example.reader"), JSONObject().put("paused", true).toString())
        val wrongType = FakeStorage()
        wrongType.write(
            AccessibilityAppConfigStore.key("com.example.reader"),
            JSONObject().put("paused", true)
                .put("defaultMaxActions", true)
                .put("defaultActionCoolDownMs", 700)
                .put("defaultMatchDelayMs", 0)
                .toString(),
        )

        assertTrue(AccessibilityAppConfigStore.read(missing, "com.example.reader") is AccessibilityAppConfigRead.Corrupt)
        assertTrue(AccessibilityAppConfigStore.read(wrongType, "com.example.reader") is AccessibilityAppConfigRead.Corrupt)
        assertTrue(AccessibilityAppConfigStore.isPaused(missing, "com.example.reader"))
        assertTrue(AccessibilityAppConfigStore.isPaused(wrongType, "com.example.reader"))
    }

    @Test
    fun `存量数据越界也算损坏`() {
        val storage = FakeStorage()
        storage.write(
            AccessibilityAppConfigStore.key("com.example.reader"),
            JSONObject().put("paused", false)
                .put("defaultMaxActions", 100)
                .put("defaultActionCoolDownMs", 700)
                .put("defaultMatchDelayMs", 0)
                .toString(),
        )

        val read = AccessibilityAppConfigStore.read(storage, "com.example.reader")
        assertTrue(read is AccessibilityAppConfigRead.Corrupt)
        // paused=false 写在盘上，但越界让整份读数不可信：宁可当成已暂停。
        assertTrue(AccessibilityAppConfigStore.isPaused(storage, "com.example.reader"))
    }

    @Test
    fun `保存一次就能覆盖损坏的数据`() {
        val storage = FakeStorage()
        storage.write(AccessibilityAppConfigStore.key("com.example.reader"), "坏了")

        AccessibilityAppConfigStore.write(storage, "com.example.reader", AccessibilityAppConfig(paused = false))

        assertEquals(
            AccessibilityAppConfigRead.Ok(AccessibilityAppConfig()),
            AccessibilityAppConfigStore.read(storage, "com.example.reader"),
        )
        assertFalse(AccessibilityAppConfigStore.isPaused(storage, "com.example.reader"))
    }

    @Test
    fun `清除后回到默认值且未暂停`() {
        val storage = FakeStorage()
        AccessibilityAppConfigStore.write(storage, "com.example.reader", AccessibilityAppConfig(paused = true))

        AccessibilityAppConfigStore.clear(storage, "com.example.reader")

        assertEquals(
            AccessibilityAppConfigRead.Ok(AccessibilityAppConfig()),
            AccessibilityAppConfigStore.read(storage, "com.example.reader"),
        )
        assertFalse(AccessibilityAppConfigStore.isPaused(storage, "com.example.reader"))
    }

    /** 内存替身：只实现 store 真正用到的那三个操作。 */
    private class FakeStorage : AccessibilityAppConfigStorage {
        private val values = linkedMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        fun keys(): List<String> = values.keys.toList()
    }
}
