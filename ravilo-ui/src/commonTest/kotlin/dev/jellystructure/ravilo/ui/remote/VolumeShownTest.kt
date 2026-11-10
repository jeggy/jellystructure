package dev.jellystructure.ravilo.ui.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2185 (FR-11, FR-12; acceptance 3, 4, 5) — the slider's rule, replayed against report sequences: it opens true
 * (never 0), no report moves the thumb in a drag, the viewer's own request is held until a report matches it ± 1, the
 * thumb returns to the reported level 3 s after a request the place did not take with the *kept* line, and a change
 * from elsewhere moves it live.
 */
class VolumeShownTest {

    /** A slider with a clock the test moves: every call goes through [VolumeHold] as the row does. */
    private class Slider {
        val hold = VolumeHold()
        var now = 1_000_000L
        var reported: Int? = null
        val sent = mutableListOf<Pair<Int, Long>>()
        fun report(level: Int?) { reported = level; hold.onReport(level, now) }
        fun drag(level: Int) { hold.onDrag(level, now)?.let { sent += it to now } }
        fun release(level: Int) { sent += hold.onRelease(level, now) to now }
        fun after(ms: Long) { now += ms }
        val shown get() = hold.shown(reported, now)
    }

    @Test fun `opens true - a dash until the first report and never 0`() {
        // Acceptance 3 — a TV playing at 37 %: the slider reads 37 at first draw, or — until the first report, never 0.
        assertEquals(VolumeShown(null), volumeShown(reported = null, request = null, sentAtMs = null, nowMs = 0))
        assertEquals(VolumeShown(37), volumeShown(37, null, null, 0))
        val s = Slider()
        assertNull(s.shown.level, "nothing reported yet: a dash")
        s.report(37)
        assertEquals(37, s.shown.level)
    }

    @Test fun `no report moves the thumb during a drag`() {
        val s = Slider(); s.report(2)
        s.drag(8); s.report(2); assertEquals(8, s.shown.level)
        s.after(50); s.drag(12); s.report(40); assertEquals(12, s.shown.level, "the finger wins over a report from elsewhere too")
        assertFalse(s.shown.inFlight); assertNull(s.shown.kept)
    }

    @Test fun `set_volume goes at most once per 200 ms in a drag and once more on release`() {
        val s = Slider(); s.report(2)
        s.drag(5)                      // sent at once
        s.after(100); s.drag(8)        // 100 ms later: gated
        s.after(100); s.drag(11)       // 200 ms after the first: sent
        s.after(50); s.drag(13)        // gated
        s.after(10); s.release(15)     // release: always sent, even 60 ms after the last
        assertEquals(listOf(5, 11, 15), s.sent.map { it.first })
    }

    @Test fun `no flicker - the request is held until a report matches it within one`() {
        // Acceptance 4 — drag from 2 % to 15 % against a place that reports its old level once before obeying.
        val s = Slider(); s.report(2)
        s.drag(10); s.after(100); s.release(15)
        assertEquals(VolumeShown(15, inFlight = true), s.shown, "held on release")
        s.after(300); s.report(2)
        assertEquals(VolumeShown(15, inFlight = true), s.shown, "the old level is not drawn while in flight")
        s.after(300); s.report(14)
        assertEquals(VolumeShown(14), s.shown, "14 matches 15 ± 1: settled, and from then on the slider is the reported level")
        assertNull(s.hold.request, "the hold forgets a request that landed")
    }

    @Test fun `a change that did not take returns after 3 s and the line names the level the place kept`() {
        // Acceptance 5 — a place that never confirms: 3 s after release the thumb is back at 2 % and the line shows.
        val s = Slider(); s.report(2)
        s.release(15)
        s.after(2_999)
        assertEquals(VolumeShown(15, inFlight = true), s.shown)
        s.after(1)
        assertEquals(VolumeShown(2, kept = 2), s.shown, "back at the reported level, and the kept line is up")
        s.after(2_999)
        assertEquals(VolumeShown(2, kept = 2), s.shown, "the line stays 3 s")
        s.after(1)
        assertEquals(VolumeShown(2), s.shown, "then nothing is marked")
        assertFalse(s.hold.ticking(s.now))
    }

    @Test fun `the kept line names the level the place reports now - a report in that window moves both`() {
        val s = Slider(); s.report(2)
        s.release(15)
        s.after(3_500)
        assertEquals(VolumeShown(2, kept = 2), s.shown)
        s.report(5)   // the TV's own remote moved it meanwhile
        assertEquals(VolumeShown(5, kept = 5), s.shown)
    }

    @Test fun `reports from elsewhere move the thumb live when nothing is in flight`() {
        // Acceptance 6 — the TV's own remote, Google Home, another app: the thumb follows every report.
        val s = Slider(); s.report(37)
        s.report(40); assertEquals(40, s.shown.level)
        s.report(12); assertEquals(12, s.shown.level)
        // After a request landed, the next change from elsewhere is drawn live, not held against the old request.
        s.release(50); s.report(50); assertEquals(VolumeShown(50), s.shown)
        s.after(100); s.report(60); assertEquals(VolumeShown(60), s.shown, "60 is not compared against the request that already landed")
    }

    @Test fun `a place that stops reporting shows a dash again`() {
        val s = Slider(); s.report(37)
        s.report(null)
        assertNull(s.shown.level)
        s.release(20)   // cannot happen from the row (not draggable), but the rule stays honest
        s.after(3_000)
        assertEquals(VolumeShown(null, kept = null), s.shown, "no reported level: nothing to return to and nothing to name")
    }

    @Test fun `the hold ticks only while a request is out or its line is up`() {
        val s = Slider(); s.report(2)
        assertFalse(s.hold.ticking(s.now))
        s.release(15); assertTrue(s.hold.ticking(s.now))
        s.after(5_999); assertTrue(s.hold.ticking(s.now))
        s.after(1); assertFalse(s.hold.ticking(s.now))
        s.release(20); s.report(20); assertFalse(s.hold.ticking(s.now), "settled: nothing to tick for")
    }

    @Test fun `the pure rule clamps the levels it draws`() {
        assertEquals(VolumeShown(100, inFlight = true), volumeShown(10, 140, 0, 100))
        assertEquals(VolumeShown(0), volumeShown(50, null, null, 0, dragging = -3))
    }
}
