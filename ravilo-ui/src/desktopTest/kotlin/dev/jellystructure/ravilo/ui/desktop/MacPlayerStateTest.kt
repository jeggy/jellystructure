package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacPlayerStateTest {
    private fun raw(vararg pairs: Pair<Int, Long>) = LongArray(MacPlayer.STATE_FIELDS).also { a -> pairs.forEach { (i, v) -> a[i] = v } }

    @Test
    fun `the fields are read in Player swift's order`() {
        val s = MacPlayerState.of(raw(0 to 61_000, 1 to 5_400_000, 2 to 90_000, 3 to 2, 4 to 1, 6 to 1, 7 to 1920, 8 to 1080,
            9 to 3, 10 to 1, 12 to 8_000_000, 13 to 1))
        assertEquals(61_000, s.positionMs); assertEquals(5_400_000, s.durationMs); assertEquals(90_000, s.bufferedMs)
        assertTrue(s.ready); assertFalse(s.failed); assertFalse(s.buffering)
        assertTrue(s.firstFrame); assertEquals(1920, s.width); assertEquals(1080, s.height)
        assertEquals(3, s.droppedFrames); assertEquals(1, s.stalls); assertEquals(8_000_000, s.observedBitrate); assertTrue(s.wantsPlay)
    }

    @Test
    fun `waiting is buffering and so is wanting to play before the item is ready`() {
        assertTrue(MacPlayerState.of(raw(3 to 1, 4 to 1, 13 to 1)).buffering, "AVPlayer holding at the requested rate")
        assertTrue(MacPlayerState.of(raw(4 to 0, 13 to 1)).buffering, "asked to play, still loading")
        assertFalse(MacPlayerState.of(raw(4 to 0)).buffering, "loading but paused is not a wait the viewer sees")
        assertTrue(MacPlayerState.of(raw(4 to 2)).failed)
        assertEquals(0, MacPlayerState.of(raw(0 to -5)).positionMs, "never negative")
    }

    @Test
    fun `no library means an empty, never-failing state`() {
        val s = MacPlayerState.EMPTY
        assertFalse(s.failed); assertFalse(s.ready); assertEquals(-1, s.durationMs)
    }
}
