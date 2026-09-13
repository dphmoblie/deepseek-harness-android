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
    fun roundTripsOriginPosition() {
        // (0,0) 是合法位置的边界值：本类用负数表示非法、-1 表示空，
        // 它能区分出「0 即未设置」的错误实现。
        val preferences = OverlayBallPreferences(FakeStorage())
        preferences.writePosition(0, 0)
        assertEquals(0 to 0, preferences.readPosition())
    }

    @Test
    fun ignoresNegativeWriteAndKeepsStoredPosition() {
        // 非法写入被静默丢弃，已存位置保持原样——注意这不同于「没存过」。
        val preferences = OverlayBallPreferences(FakeStorage())
        preferences.writePosition(120, 480)
        preferences.writePosition(-1, 600)
        assertEquals(120 to 480, preferences.readPosition())
    }

    @Test
    fun rejectsNegativeStoredValues() {
        // 负值有两个来源：clearPosition 写入的哨兵，以及被外部改写的存储；读取时都当作没存过。
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
