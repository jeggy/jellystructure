package dev.jellystructure.ravilo.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R383 (FR-R383-1) — the frame counter's arithmetic and its log line, which `scripts/check-mac-resources.sh` reads. */
class FrameCounterTest {
    @Test fun `a period reports its frames and its length, then starts again from zero`() {
        var now = 0L
        val tally = FrameCounter.Tally { now }
        repeat(3612) { tally.frame() }
        now = 60_000_000_000L
        assertEquals(3612L to 60.0, tally.drain())
        repeat(5) { tally.frame() }
        now = 90_000_000_000L
        assertEquals(5L to 30.0, tally.drain())
    }

    @Test fun `the line carries the rate and the window state`() {
        assertEquals(
            "frames: 3612 in 60 s = 60.2/s · shown=true minimised=false focused=true",
            FrameCounter.line(3612, 60.0, shown = true, minimised = false, focused = true),
        )
    }

    @Test fun `a hidden window with no frames reads zero, and a zero-length period does not divide by zero`() {
        assertTrue("frames: 0 in 60 s = 0.0/s" in FrameCounter.line(0, 60.0, shown = false, minimised = true, focused = false))
        assertTrue("= 0.0/s" in FrameCounter.line(0, 0.0, shown = true, minimised = false, focused = true))
    }
}
