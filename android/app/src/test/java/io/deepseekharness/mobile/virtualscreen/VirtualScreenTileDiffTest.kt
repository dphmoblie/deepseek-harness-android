package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 副屏无 OCR 瓦片差分（[VirtualScreenTileDiff]）单元测试。
 */
class VirtualScreenTileDiffTest {
    private fun solidRgba(width: Int, height: Int, r: Int, g: Int, b: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(width * height * 4)
        for (i in 0 until width * height) {
            buf.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(0xFF.toByte())
        }
        buf.flip()
        return buf
    }

    private fun paintRect(buf: ByteBuffer, width: Int, l: Int, t: Int, r: Int, b: Int, red: Int, green: Int, blue: Int) {
        for (y in t until b) {
            for (x in l until r) {
                val off = (y * width + x) * 4
                buf.put(off, red.toByte())
                buf.put(off + 1, green.toByte())
                buf.put(off + 2, blue.toByte())
            }
        }
    }

    @Test
    fun `detects localized dirty region and ignores top status bar jitter`() {
        val w = 320
        val h = 640
        val beforeBuf = solidRgba(w, h, 240, 240, 240)
        val afterBuf = solidRgba(w, h, 240, 240, 240)
        // 只改顶部状态栏区域（第 0 行）：应被默认 skipTopRows=1 过滤掉，不报变化。
        paintRect(afterBuf, w, 0, 0, w, 30, 10, 10, 10)

        val beforeTiles = VirtualScreenTileDiff.sampleRgba(beforeBuf, w, h, w * 4)!!
        val statusOnlyTiles = VirtualScreenTileDiff.sampleRgba(afterBuf, w, h, w * 4)!!
        val statusDiff = VirtualScreenTileDiff.diff(beforeTiles, statusOnlyTiles, w, h)
        assertFalse(statusDiff.getBoolean("changed"))
        assertEquals("unchanged", statusDiff.getString("kind"))

        // 在内容中部画一块高亮变动（模拟按钮变色/选项展开）。
        paintRect(afterBuf, w, 40, 240, 280, 360, 20, 80, 220)
        val changedTiles = VirtualScreenTileDiff.sampleRgba(afterBuf, w, h, w * 4)!!
        val diff = VirtualScreenTileDiff.diff(beforeTiles, changedTiles, w, h)
        assertEquals("tile_diff", diff.getString("source"))
        assertTrue(diff.getBoolean("changed"))
        assertEquals("delta", diff.getString("kind"))
        assertTrue(diff.getInt("changedTiles") > 0)
        val regions = diff.getJSONArray("dirtyRegions")
        assertEquals(1, regions.length())
        val rect = regions.getJSONArray(0)
        assertTrue(rect.getInt(0) <= 40 && rect.getInt(2) >= 280)
        assertTrue(rect.getInt(1) <= 240 && rect.getInt(3) >= 360)
    }

    @Test
    fun `reports page_changed when foreground activity transitions even before pixels differ`() {
        val tiles = IntArray(VirtualScreenTileDiff.TILE_COUNT) { 200 }
        val diff = VirtualScreenTileDiff.diff(
            before = tiles,
            after = tiles,
            width = 720,
            height = 1280,
            activityBefore = "com.demo/.HomeActivity",
            activityAfter = "com.demo/.DetailActivity",
        )
        assertTrue(diff.getBoolean("changed"))
        assertEquals("page_changed", diff.getString("kind"))
    }
}
