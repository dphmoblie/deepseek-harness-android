package io.deepseekharness.mobile.virtualscreen

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 副屏无 OCR、无截图编码的轻量瓦片变化检测器（纯 Kotlin，可直接 JVM 单测）。
 *
 * 当无障碍服务未开启或目标应用为自绘 Canvas / 游戏而没有节点树时，仅读取 RGBA 缓冲中的
 * `8×16 = 128` 块（每块 4 个采样点，共 512 像素，耗时 < 0.2ms，不分配 Bitmap、不编 PNG），
 * 即可准确判定画面是否发生变化，并合并输出像素级脏矩形 `dirtyRegions`。
 */
internal object VirtualScreenTileDiff {
    const val GRID_COLS = 8
    const val GRID_ROWS = 16
    const val TILE_COUNT = GRID_COLS * GRID_ROWS
    /** 忽略顶部 1 行状态栏微变（占屏幕高度约 6.25%），减少时钟/电量图标造成的误报。 */
    const val DEFAULT_SKIP_TOP_ROWS = 1
    /** 单块亮度均值的最小变化阈值（0..255），过滤抗锯齿与细微压缩/抖动噪音。 */
    const val LUMINANCE_DIFF_THRESHOLD = 8
    const val MAX_DIRTY_REGIONS = 4

    /**
     * 直接从 RGBA_8888 字节缓冲按 8×16 网格采样亮度均值（每块取 2×2 内点）。
     * 调用方需保证 [pixelStride] >= 4 且 [rowStride] >= width * pixelStride；尺寸非法返回 null。
     */
    fun sampleRgba(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int = 4,
    ): IntArray? {
        if (width < GRID_COLS || height < GRID_ROWS || pixelStride < 4 || rowStride < width * pixelStride) return null
        val limit = buffer.limit()
        val tiles = IntArray(TILE_COUNT)
        for (row in 0 until GRID_ROWS) {
            val y0 = (row * height) / GRID_ROWS
            val y1 = ((row + 1) * height) / GRID_ROWS
            val syA = (y0 + (y1 - y0) / 3).coerceIn(0, height - 1)
            val syB = (y0 + ((y1 - y0) * 2) / 3).coerceIn(0, height - 1)
            for (col in 0 until GRID_COLS) {
                val x0 = (col * width) / GRID_COLS
                val x1 = ((col + 1) * width) / GRID_COLS
                val sxA = (x0 + (x1 - x0) / 3).coerceIn(0, width - 1)
                val sxB = (x0 + ((x1 - x0) * 2) / 3).coerceIn(0, width - 1)
                var sum = 0
                var count = 0
                for (sy in intArrayOf(syA, syB)) {
                    val rowOffset = sy * rowStride
                    for (sx in intArrayOf(sxA, sxB)) {
                        val offset = rowOffset + sx * pixelStride
                        if (offset + 2 < limit) {
                            val r = buffer.get(offset).toInt() and 0xFF
                            val g = buffer.get(offset + 1).toInt() and 0xFF
                            val b = buffer.get(offset + 2).toInt() and 0xFF
                            // 整数 ITU-R BT.601 近似亮度：(77R + 150G + 29B) >> 8
                            sum += (77 * r + 150 * g + 29 * b) shr 8
                            count++
                        }
                    }
                }
                tiles[row * GRID_COLS + col] = if (count > 0) sum / count else 0
            }
        }
        return tiles
    }

    /**
     * 比较前后两份 8×16 瓦片亮度签名，返回是否变化、变化占比与合并后的像素级脏矩形列表。
     */
    fun diff(
        before: IntArray,
        after: IntArray,
        width: Int,
        height: Int,
        skipTopRows: Int = DEFAULT_SKIP_TOP_ROWS,
        activityBefore: String = "",
        activityAfter: String = "",
    ): JSONObject {
        val activeRows = (GRID_ROWS - skipTopRows.coerceIn(0, GRID_ROWS - 1)).coerceAtLeast(1)
        val startRow = GRID_ROWS - activeRows
        val dirtyMask = BooleanArray(TILE_COUNT)
        var changedTiles = 0
        if (before.size == TILE_COUNT && after.size == TILE_COUNT) {
            for (row in startRow until GRID_ROWS) {
                for (col in 0 until GRID_COLS) {
                    val idx = row * GRID_COLS + col
                    if (abs(after[idx] - before[idx]) >= LUMINANCE_DIFF_THRESHOLD) {
                        dirtyMask[idx] = true
                        changedTiles++
                    }
                }
            }
        }
        val visited = BooleanArray(TILE_COUNT)
        val regions = ArrayList<IntArray>()
        val queue = IntArray(TILE_COUNT)
        for (row in startRow until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val start = row * GRID_COLS + col
                if (!dirtyMask[start] || visited[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                visited[start] = true
                var minCol = col
                var maxCol = col
                var minRow = row
                var maxRow = row
                while (head < tail) {
                    val cur = queue[head++]
                    val r = cur / GRID_COLS
                    val c = cur % GRID_COLS
                    if (c < minCol) minCol = c
                    if (c > maxCol) maxCol = c
                    if (r < minRow) minRow = r
                    if (r > maxRow) maxRow = r
                    val neighbors = intArrayOf(
                        if (r > startRow) (r - 1) * GRID_COLS + c else -1,
                        if (r + 1 < GRID_ROWS) (r + 1) * GRID_COLS + c else -1,
                        if (c > 0) r * GRID_COLS + (c - 1) else -1,
                        if (c + 1 < GRID_COLS) r * GRID_COLS + (c + 1) else -1,
                    )
                    for (next in neighbors) {
                        if (next >= 0 && dirtyMask[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
                val left = (minCol * width) / GRID_COLS
                val top = (minRow * height) / GRID_ROWS
                val right = ((maxCol + 1) * width) / GRID_COLS
                val bottom = ((maxRow + 1) * height) / GRID_ROWS
                regions += intArrayOf(left, top, right, bottom)
            }
        }
        regions.sortByDescending { (it[2] - it[0]).toLong() * (it[3] - it[1]).toLong() }

        val totalEvaluated = activeRows * GRID_COLS
        val changeRatio = Math.round(changedTiles * 1000.0 / totalEvaluated) / 1000.0
        val activityChanged = activityBefore.isNotEmpty() && activityAfter.isNotEmpty() && activityBefore != activityAfter
        val changed = changedTiles > 0 || activityChanged

        val dirtyArray = JSONArray()
        regions.take(MAX_DIRTY_REGIONS).forEach { rect ->
            dirtyArray.put(JSONArray().put(rect[0]).put(rect[1]).put(rect[2]).put(rect[3]))
        }

        return JSONObject()
            .put("source", "tile_diff")
            .put("changed", changed)
            .put("kind", when {
                !changed -> "unchanged"
                activityChanged || changeRatio >= 0.6 -> "page_changed"
                else -> "delta"
            })
            .put("changedTiles", changedTiles)
            .put("changeRatio", changeRatio)
            .put("dirtyRegions", dirtyArray)
            .apply {
                if (activityBefore.isNotEmpty()) put("activityBefore", activityBefore)
                if (activityAfter.isNotEmpty()) put("activityAfter", activityAfter)
            }
    }
}
