package io.deepseekharness.mobile.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 悬浮球位置持久化的纯逻辑测试。
 *
 * 真实实现包一层 SharedPreferences，这里用内存替身验证读写与越界回退语义：
 * 存过的坐标要能原样读回，没存过或存了非法值时要回退到默认位置。
 */
class OverlayBallPreferencesTest {
    private class FakeStorage : OverlayBallPreferences.Storage {
        val values = mutableMapOf<String, Int>()
        override fun readInt(key: String): Int? = values[key]
        override fun writeInt(key: String, value: Int) { values[key] = value }
    }

    @Test
    fun returnsNullWhenNothingStored() {
        val preferences = OverlayBallPreferences(FakeStorage())
        assertNull(preferences.readPosition())
    }

    @Test
    fun roundTripsStoredPosition() {
        val storage = FakeStorage()
        val preferences = OverlayBallPreferences(storage)
        preferences.writePosition(120, 480)
        assertEquals(120 to 480, preferences.readPosition())
    }

    @Test
    fun rejectsNegativeStoredValues() {
        // 负坐标只可能来自被篡改的存储或旧版本写入，读回时必须当作没存过。
        val storage = FakeStorage()
        storage.values[OverlayBallPreferences.KEY_X] = -10
        storage.values[OverlayBallPreferences.KEY_Y] = 480
        assertNull(OverlayBallPreferences(storage).readPosition())
    }

    @Test
    fun rejectsHalfStoredPosition() {
        // 只存了一半（写过程被杀）同样视为无效，避免球跳到左上角。
        val storage = FakeStorage()
        storage.values[OverlayBallPreferences.KEY_X] = 120
        assertNull(OverlayBallPreferences(storage).readPosition())
    }

    @Test
    fun clearsStoredPosition() {
        val storage = FakeStorage()
        val preferences = OverlayBallPreferences(storage)
        preferences.writePosition(120, 480)
        preferences.clearPosition()
        assertNull(preferences.readPosition())
    }
}
