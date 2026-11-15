package io.deepseekharness.mobile.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBallFrameUpdatesTest {
    @Test
    fun coalescesMovesUntilTheNextFrameAndFlushesLatestPosition() {
        val frames = mutableListOf<Runnable>()
        val applied = mutableListOf<Pair<Int, Int>>()
        val updates = OverlayBallFrameUpdates(
            postFrame = frames::add,
            removeFrame = frames::remove,
            applyPosition = { x, y -> applied += x to y },
        )

        updates.moveTo(1, 2)
        updates.moveTo(3, 4)

        assertEquals(1, frames.size)
        assertTrue(applied.isEmpty())
        frames.removeAt(0).run()

        assertEquals(listOf(3 to 4), applied)
        updates.moveTo(5, 6)
        assertEquals(1, frames.size)
        frames.removeAt(0).run()
        assertEquals(listOf(3 to 4, 5 to 6), applied)
    }

    @Test
    fun flushAppliesPendingImmediatelyAndCancelDropsIt() {
        val frames = mutableListOf<Runnable>()
        val applied = mutableListOf<Pair<Int, Int>>()
        val updates = OverlayBallFrameUpdates(
            postFrame = frames::add,
            removeFrame = frames::remove,
            applyPosition = { x, y -> applied += x to y },
        )

        updates.moveTo(5, 6)
        updates.flush()
        assertEquals(listOf(5 to 6), applied)
        assertTrue(frames.isEmpty())

        updates.moveTo(7, 8)
        updates.cancel()
        assertEquals(listOf(5 to 6), applied)
        assertTrue(frames.isEmpty())
    }
}
