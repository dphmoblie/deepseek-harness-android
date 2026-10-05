package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 空白帧判定与预览内容判定的纯函数约束：采样固定有界、与宽高比无关、极端尺寸不崩。 */
class VirtualScreenFramePolicyTest {
    /** 按 (x, y) 生成整帧像素，便于构造白帧、渐变帧和棋盘帧。 */
    private fun frame(width: Int, height: Int, color: (Int, Int) -> Int): IntArray =
        IntArray(width * height) { index -> color(index % width, index / width) }

    /** 按预览采样网格取亮度样本，模拟客户端对解码后位图的采样结果。 */
    private fun samples(width: Int, height: Int, color: (Int, Int) -> Int): IntArray {
        val rows = VirtualScreenPolicy.previewSampleRows(height)
        val columns = VirtualScreenPolicy.previewSampleColumns(width)
        return IntArray(rows.size * columns.size) { index ->
            VirtualScreenPolicy.luminance(color(columns[index % columns.size], rows[index / columns.size]))
        }
    }

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

    @Test fun `宿主采样行固定有界且包含首尾`() {
        val tall = VirtualScreenPolicy.frameSampleRows(1600)
        assertEquals(VirtualScreenPolicy.FRAME_SAMPLE_ROWS, tall.size)
        assertEquals(0, tall.first())
        assertEquals(1599, tall.last())
        // 采样行必须严格递增，否则会重复采同一行、把采样密度算虚。
        assertTrue(tall.toList().zipWithNext().all { (left, right) -> right > left })
        assertArrayEquals(intArrayOf(0), VirtualScreenPolicy.frameSampleRows(1))
        assertArrayEquals(intArrayOf(0, 1, 2), VirtualScreenPolicy.frameSampleRows(3))
        assertTrue(VirtualScreenPolicy.frameSampleRows(0).isEmpty())
        assertTrue(VirtualScreenPolicy.frameSampleRows(-4).isEmpty())
        assertTrue(VirtualScreenPolicy.frameSampleRows(100_000).size <= VirtualScreenPolicy.FRAME_SAMPLE_ROWS)
        assertTrue(VirtualScreenPolicy.frameSampleRows(100_000).first() == 0)
    }

    @Test fun `预览采样网格比宿主侧更密`() {
        val rows = VirtualScreenPolicy.previewSampleRows(1600)
        assertEquals(VirtualScreenPolicy.PREVIEW_SAMPLE_ROWS, rows.size)
        assertEquals(0, rows.first())
        assertEquals(1599, rows.last())
        assertTrue(rows.toList().zipWithNext().all { (left, right) -> right > left })
        // 真机 1600 高时相邻采样行只隔十几像素：只有一整行文字的页面不会被漏掉。
        assertTrue(rows[1] - rows[0] < 20)
        val columns = VirtualScreenPolicy.previewSampleColumns(726)
        assertEquals(VirtualScreenPolicy.PREVIEW_SAMPLE_COLUMNS, columns.size)
        assertEquals(0, columns.first())
        assertEquals(725, columns.last())
        assertArrayEquals(intArrayOf(0), VirtualScreenPolicy.previewSampleColumns(1))
        assertTrue(VirtualScreenPolicy.previewSampleColumns(0).isEmpty())
        assertTrue(VirtualScreenPolicy.previewSampleRows(-4).isEmpty())
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

    @Test fun `大片白底上加少量文字不算空白帧`() {
        // 旧实现只取 3 行采样线：文字正好落在采样线之间就会把有内容的页面判成空白帧。
        val band = VirtualScreenPolicy.frameSampleRows(800).let { rows -> rows[rows.size / 3] }
        assertFalse(VirtualScreenPolicy.blankFrame(frame(360, 800) { _, y -> if (y == band) 0xFF202020.toInt() else 0xFFFFFFFF.toInt() }, 360, 800))
        // 行缓冲语义：宿主只把手里的采样行逐行相接传进来，height 是行数。
        val rows = VirtualScreenPolicy.frameSampleRows(800)
        val buffer = IntArray(rows.size * 360)
        rows.forEachIndexed { index, row ->
            for (column in 0 until 360) buffer[index * 360 + column] = if (row == band) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
        }
        assertFalse(VirtualScreenPolicy.blankFrame(buffer, 360, rows.size))
        // 同样的行缓冲里没有任何内容时仍判空白，保证这条用例不是恒假。
        assertTrue(VirtualScreenPolicy.blankFrame(IntArray(rows.size * 360) { 0xFFFFFFFF.toInt() }, 360, rows.size))
    }

    @Test fun `纯色帧判为没有内容，少量文字就够`() {
        assertEquals(0, VirtualScreenPolicy.frameSpread(samples(360, 800) { _, _ -> 0xFFFFFFFF.toInt() }))
        assertFalse(VirtualScreenPolicy.frameHasContent(samples(360, 800) { _, _ -> 0xFFFFFFFF.toInt() }))
        assertFalse(VirtualScreenPolicy.frameHasContent(samples(360, 800) { _, _ -> 0xFF000000.toInt() }))
        assertFalse(VirtualScreenPolicy.frameHasContent(samples(360, 800) { _, _ -> 0x00000000 }))
        // 大片白底上有一段文字：必须判出内容，否则真机预览会一直不显示画面（0.2.9 黑屏的直接成因）。
        assertTrue(VirtualScreenPolicy.frameHasContent(samples(360, 800) { _, y -> if (y in 300..340) 0xFF202020.toInt() else 0xFFFFFFFF.toInt() }))
        // 只有一个小图标（40×40）：固定几行采样会漏掉，网格采样不能漏。
        assertTrue(VirtualScreenPolicy.frameHasContent(samples(360, 800) { x, y -> if (x in 100..140 && y in 500..540) 0xFF202020.toInt() else 0xFFFFFFFF.toInt() }))
        // 极淡的灰底（亮度极差 11）在阈值内仍算「没有内容」：宁可保守也不假装有画面。
        assertFalse(VirtualScreenPolicy.frameHasContent(samples(360, 800) { x, _ -> if (x % 3 == 0) 0xFFF4F4F4.toInt() else 0xFFFFFFFF.toInt() }))
        assertFalse(VirtualScreenPolicy.frameHasContent(IntArray(0)))
    }

    @Test fun `与上一帧比较：首帧算变了，相同算没变，明显不同算变了`() {
        val first = IntArray(8) { 200 }
        assertTrue(VirtualScreenPolicy.frameChanged(null, first))
        assertFalse(VirtualScreenPolicy.frameChanged(first, IntArray(8) { 200 }))
        // 差值正好等于阈值不算变化：渲染抖动不能让状态行来回翻「画面暂无变化」。
        assertFalse(VirtualScreenPolicy.frameChanged(first, IntArray(8) { index -> if (index == 3) 200 - VirtualScreenPolicy.PREVIEW_SAMPLE_DELTA else 200 }))
        // 只要有一个点超出阈值就算变了：小幅真实变化不能被说成「画面暂无变化」。
        assertTrue(VirtualScreenPolicy.frameChanged(first, IntArray(8) { index -> if (index == 5) 200 - VirtualScreenPolicy.PREVIEW_SAMPLE_DELTA - 1 else 200 }))
        assertTrue(VirtualScreenPolicy.frameChanged(first, IntArray(8) { 40 }))
        // 采样规模变了（分辨率变化）要按「变了」处理，不能拿旧采样比出新结论。
        assertTrue(VirtualScreenPolicy.frameChanged(first, IntArray(4) { 200 }))
        assertTrue(VirtualScreenPolicy.frameChanged(first, IntArray(0)))
    }

    @Test fun `亮度与极差是纯函数且边界不崩`() {
        assertEquals(255, VirtualScreenPolicy.luminance(0xFFFFFFFF.toInt()))
        assertEquals(0, VirtualScreenPolicy.luminance(0xFF000000.toInt()))
        assertEquals(0, VirtualScreenPolicy.luminance(0x00000000))
        assertEquals(76, VirtualScreenPolicy.luminance(0xFFFF0000.toInt()))
        assertEquals(0, VirtualScreenPolicy.frameSpread(IntArray(0)))
        assertEquals(0, VirtualScreenPolicy.frameSpread(intArrayOf(7, 7, 7)))
        assertEquals(14, VirtualScreenPolicy.frameSpread(intArrayOf(7, 17, 3)))
    }
}
