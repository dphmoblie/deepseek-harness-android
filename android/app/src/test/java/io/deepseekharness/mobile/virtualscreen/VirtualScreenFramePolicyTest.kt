package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 空白帧判定的纯函数约束：采样固定有界、与宽高比无关、极端尺寸不崩。 */
class VirtualScreenFramePolicyTest {
    /** 按 (x, y) 生成整帧像素，便于构造白帧、渐变帧和棋盘帧。 */
    private fun frame(width: Int, height: Int, color: (Int, Int) -> Int): IntArray =
        IntArray(width * height) { index -> color(index % width, index / width) }

    @Test fun `纯白帧与纯黑帧都判为空白`() {
        assertTrue(VirtualScreenPolicy.blankFrame(frame(80, 160) { _, _ -> 0xFFFFFFFF.toInt() }, 80, 160))
        assertTrue(VirtualScreenPolicy.blankFrame(frame(80, 160) { _, _ -> 0xFF000000.toInt() }, 80, 160))
        // 完全透明的纯色帧在采集里等价于“没有画面”，同样判为空白。
        assertTrue(VirtualScreenPolicy.blankFrame(frame(32, 32) { _, _ -> 0x00000000 }, 32, 32))
    }

    @Test fun `采样点极差很小时仍算空白，明显色差不算`() {
        // 各通道抖动不超过 FRAME_BLANK_TOLERANCE：仍视为未渲染的空白帧。
        val noisyWhite = frame(96, 96) { x, y ->
            0xFF000000.toInt() or ((0xFA + (x + y) % 4) shl 16) or ((0xFA + (x * y) % 4) shl 8) or (0xFA + (x + y) % 4)
        }
        assertTrue(VirtualScreenPolicy.blankFrame(noisyWhite, 96, 96))
        // 横向渐变覆盖到明显不同的颜色，不能判为空白。
        val gradient = frame(200, 120) { x, _ -> 0xFF000000.toInt() or (x * 255 / 199 shl 16) or (x * 255 / 199 shl 8) or (x * 255 / 199) }
        assertFalse(VirtualScreenPolicy.blankFrame(gradient, 200, 120))
        // 棋盘：明暗块交替，采样点不可能全部同色。
        val checker = frame(240, 160) { x, y -> if ((x / 8 + y / 8) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF101010.toInt() }
        assertFalse(VirtualScreenPolicy.blankFrame(checker, 240, 160))
    }

    @Test fun `采样线固定有界且包含首尾`() {
        assertArrayEquals(intArrayOf(0, 799, 1599), VirtualScreenPolicy.frameSampleLines(1600))
        assertArrayEquals(intArrayOf(0), VirtualScreenPolicy.frameSampleLines(1))
        assertArrayEquals(intArrayOf(0, 1, 2), VirtualScreenPolicy.frameSampleLines(3))
        assertTrue(VirtualScreenPolicy.frameSampleLines(0).isEmpty())
        assertTrue(VirtualScreenPolicy.frameSampleLines(-4).isEmpty())
        assertTrue(VirtualScreenPolicy.frameSampleLines(100_000).size <= VirtualScreenPolicy.FRAME_SAMPLE_LINES)
        assertTrue(VirtualScreenPolicy.frameSampleLines(100_000).first() == 0)
    }

    @Test fun `1×1 与 2×2 等极端尺寸不崩且结论正确`() {
        assertTrue(VirtualScreenPolicy.blankFrame(intArrayOf(0xFFFFFFFF.toInt()), 1, 1))
        assertTrue(VirtualScreenPolicy.blankFrame(intArrayOf(0x00000000), 1, 1))
        assertTrue(VirtualScreenPolicy.blankFrame(intArrayOf(0, 0, 0, 0), 2, 2))
        assertFalse(VirtualScreenPolicy.blankFrame(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2, 2))
        // 非正的宽高无从判定，返回 false 而不是抛异常。
        assertFalse(VirtualScreenPolicy.blankFrame(intArrayOf(0xFFFFFFFF.toInt()), 0, 5))
        assertFalse(VirtualScreenPolicy.blankFrame(intArrayOf(0xFFFFFFFF.toInt()), 5, 0))
        assertFalse(VirtualScreenPolicy.blankFrame(intArrayOf(), 0, 0))
        assertFalse(VirtualScreenPolicy.blankFrame(intArrayOf(), 8, 8))
    }

    @Test fun `空白判定不受宽高比影响`() {
        // 与真机副屏一致的 1:2.37 极端比例（同样缩小的尺寸，避免单测占用过多堆内存）。
        assertTrue(VirtualScreenPolicy.blankFrame(frame(540, 1280) { _, _ -> 0xFFFFFFFF.toInt() }, 540, 1280))
        assertTrue(VirtualScreenPolicy.blankFrame(frame(1280, 540) { _, _ -> 0xFFF2F2F2.toInt() }, 1280, 540))
        // 内容挤在首行与首列：采样必须覆盖首尾，否则会把有内容的帧误判成空白。
        assertFalse(VirtualScreenPolicy.blankFrame(frame(540, 1280) { x, y -> if (x == 0 || y == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }, 540, 1280))
        assertFalse(VirtualScreenPolicy.blankFrame(frame(1280, 540) { x, y -> if (x == 0 || y == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }, 1280, 540))
    }

    @Test fun `同一帧重复判定结论不变`() {
        val pixels = frame(120, 200) { x, y -> if ((x + y) % 3 == 0) 0xFF303030.toInt() else 0xFFFFFFFF.toInt() }
        val first = VirtualScreenPolicy.blankFrame(pixels, 120, 200)
        repeat(5) { assertEquals(first, VirtualScreenPolicy.blankFrame(pixels, 120, 200)) }
    }
}
