package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2205 (FR-3, FR-4) — the decode signals and the hand-over rule, as pure rules. */
class DirectPlayHandoverTest {
    private val remux = 71_000_000L   // the reported film as a file: ~71 Mbps

    // ── FR-3 (a) — dropped frames over 10 s ──

    /** Feeds one tick a second at 24 fps, dropping [dropsPerSecond] of them, for [seconds]; returns the first signal and when. */
    private fun feed(m: DecodeHealthMonitor, seconds: Int, dropsPerSecond: Int, startMs: Long = 0, from: Pair<Long, Long> = 0L to 0L): Triple<DecodeSignal?, Long, Pair<Long, Long>> {
        var dropped = from.first; var rendered = from.second
        for (s in 1..seconds) {
            dropped += dropsPerSecond; rendered += 24 - dropsPerSecond
            val sig = m.observe(startMs + s * 1_000L, dropped, rendered, playing = true)
            if (sig != null) return Triple(sig, startMs + s * 1_000L, dropped to rendered)
        }
        return Triple(null, startMs + seconds * 1_000L, dropped to rendered)
    }

    @Test fun `more than two percent dropped over ten seconds is a decode signal`() {
        val (sig, at, _) = feed(DecodeHealthMonitor(), seconds = 30, dropsPerSecond = 1)   // 1 of 24 = 4.2 %
        assertEquals(DecodeSignal.DROPPED_FRAMES, sig)
        assertTrue(at >= DECODE_DROP_WINDOW_MS, "needs the full ten seconds first, was $at")
    }

    @Test fun `two percent or less is not`() {
        // 4 drops in 240 rendered-or-dropped frames: 1.7 % of the rendered.
        val m = DecodeHealthMonitor()
        var dropped = 0L; var rendered = 0L
        for (s in 1..60) {
            val d = if (s % 5 == 0) 1 else 0
            dropped += d; rendered += 24 - d
            assertNull(m.observe(s * 1_000L, dropped, rendered, playing = true))
        }
    }

    @Test fun `a burst is judged by the share over the whole window`() {
        val m = DecodeHealthMonitor()
        var dropped = 0L; var rendered = 0L
        var firstSignalAt: Int? = null
        for (s in 1..40) {
            val d = if (s in 3..4) 6 else 0         // 12 dropped of ~240 in a 10 s window = 5 %, for as long as it is in the window
            dropped += d; rendered += 24 - d
            val sig = m.observe(s * 1_000L, dropped, rendered, playing = true)
            if (sig != null && firstSignalAt == null) firstSignalAt = s
            if (s >= 15) assertNull(sig, "the burst has left the window by $s")
        }
        val first = firstSignalAt ?: error("a 5 % window must signal")
        assertTrue(first >= 11, "not before ten seconds of data: $first")
    }

    @Test fun `a window that rendered almost nothing says nothing`() {
        val m = DecodeHealthMonitor()
        for (s in 1..30) assertNull(m.observe(s * 1_000L, dropped = s * 3L, rendered = s * 3L, playing = true), "3 frames a second is a title card")
    }

    @Test fun `a pause or a seek starts the window again`() {
        val m = DecodeHealthMonitor()
        var dropped = 0L; var rendered = 0L
        for (s in 1..8) { dropped += 2; rendered += 22; assertNull(m.observe(s * 1_000L, dropped, rendered, true)) }
        assertNull(m.observe(9_000, dropped, rendered, playing = false))
        // Eight more bad seconds are not sixteen: the window restarted at 10 s.
        for (s in 10..18) { dropped += 2; rendered += 22; assertNull(m.observe(s * 1_000L, dropped, rendered, true), "at $s") }
        dropped += 2; rendered += 22
        assertEquals(DecodeSignal.DROPPED_FRAMES, m.observe(20_000L, dropped, rendered, true))
    }

    @Test fun `counters that go back are a new decoder and start the window again`() {
        val m = DecodeHealthMonitor()
        for (s in 1..12) m.observe(s * 1_000L, s * 2L, s * 22L, true)
        assertNull(m.observe(13_000, 0, 0, true))
    }

    // ── FR-3 (b) — frozen with buffer ──

    @Test fun `a picture frozen three seconds with five seconds buffered is a decode signal`() {
        assertEquals(DecodeSignal.FROZEN_WITH_BUFFER, frozenDecodeSignal(3_000, 5_000))
        assertNull(frozenDecodeSignal(2_999, 20_000))
        assertNull(frozenDecodeSignal(10_000, 4_999), "the buffer running out is delivery, not decode")
    }

    // ── FR-4 — the rule ──

    private fun rule(scale: Double = 1.0) = DirectPlayHandover(remux, scale)

    /** A buffer that starts at [startAhead] and loses [lossPerSecond] ms every second, looked at every 500 ms. */
    private fun drain(r: DirectPlayHandover, startAhead: Long, lossPerSecond: Long, startPos: Long = 60_000, estimate: Long? = null, seconds: Int = 60): Pair<Long, HandoverDecision?> {
        var ahead = startAhead; var pos = startPos; var t = 0L
        repeat(seconds * 2) {
            t += 500; pos += 500; ahead -= lossPerSecond / 2
            r.observe(t, pos, ahead, estimate, playing = true)?.let { return t to it }
        }
        return t to null
    }

    @Test fun `a buffer under fifteen seconds that has fallen for three hands over ahead of time`() {
        val r = rule()
        // 20 s buffered, losing 1 s a second (the path carries nothing): under 15 s from t = 5 s, and it has fallen for 3 s by t = 5.5 s.
        val (at, d) = drain(r, startAhead = 20_000, lossPerSecond = 1_000)
        val ahead = assertIs<HandoverDecision.Ahead>(d)
        assertEquals("falling", ahead.reason)
        assertTrue(at in 5_000..6_500, "fired at $at ms")
        // H = position + buffered − 2 s, where position and buffer are those at the moment it fired.
        val bufferThen = 20_000 - at
        assertEquals(60_000 + at + bufferThen - HANDOVER_MARGIN_MS, ahead.atMs)
        assertTrue(bufferThen > HANDOVER_IMMEDIATE_UNDER_MS, "still ahead of time at $bufferThen")
    }

    @Test fun `it never lets the buffer run dry before the switch`() {
        // Whatever the drain rate, H is two seconds inside what is buffered, so reaching H leaves the buffer non-empty.
        for (loss in listOf(250L, 500L, 1_000L, 2_000L)) {
            val r = rule()
            val (at, d) = drain(r, startAhead = 16_000, lossPerSecond = loss)
            val ahead = d as? HandoverDecision.Ahead ?: continue
            val bufferedAtFire = 16_000 - loss * at / 1_000
            val bufferEnd = 60_000 + at + bufferedAtFire
            assertEquals(2_000, bufferEnd - ahead.atMs, "loss $loss/s: H is exactly 2 s inside the buffer")
        }
    }

    @Test fun `a buffer that holds or grows is left alone`() {
        assertNull(drain(rule(), startAhead = 12_000, lossPerSecond = 0).second)
        assertNull(drain(rule(), startAhead = 12_000, lossPerSecond = -500).second)
    }

    @Test fun `a buffer above fifteen seconds is left alone even when it falls`() {
        assertNull(drain(rule(), startAhead = 40_000, lossPerSecond = 200, seconds = 30).second)
    }

    @Test fun `a chunk landing in a falling buffer breaks falling`() {
        val r = rule()
        var ahead = 14_000L; var pos = 0L; var t = 0L
        for (i in 1..40) {
            t += 500; pos += 500; ahead -= 500
            if (i % 5 == 0) ahead += 3_000      // a 3 s chunk lands every 2.5 s
            assertNull(r.observe(t, pos, ahead, null, true), "at $t with $ahead")
        }
    }

    @Test fun `under four seconds it restreams at once`() {
        val r = rule()
        val (_, d) = drain(r, startAhead = 7_000, lossPerSecond = 1_000)
        assertIs<HandoverDecision.Immediate>(d)
    }

    /** Looks every 500 ms from 0 to [seconds], the buffer moving by [gainPerSecond] ms a second from [start]; the first decision, if any. */
    private fun watch(r: DirectPlayHandover, start: Long, gainPerSecond: Long, seconds: Int, estimate: Long? = 50_000_000): Pair<Long, HandoverDecision?> {
        var ahead = start; var t = 0L
        repeat(seconds * 2) {
            t += 500; ahead += gainPerSecond / 2
            r.observe(t, 10_000 + t, ahead, estimate, playing = true)?.let { return t to it }
        }
        return t to null
    }

    @Test fun `the path's estimate under the file's bitrate fires it once the buffer has gained nothing for six seconds`() {
        // 25 s buffered and flat, but the path measures 50 Mbps for a 71 Mbps file: 50 × 0.8 = 40 < 71.
        val (at, d) = watch(rule(), start = 25_000, gainPerSecond = 0, seconds = 20)
        val ahead = assertIs<HandoverDecision.Ahead>(d)
        assertEquals("estimate", ahead.reason)
        assertTrue(at in 5_750..6_500, "fired at $at ms, not before six seconds of history")
        assertEquals(10_000 + at + 25_000 - 2_000, ahead.atMs)
    }

    @Test fun `a low estimate with a rising buffer never fires at any age of the play`() {
        // Every play is under 30 s while it fills: 1.5 s of buffer gained per second for 18 s, then slower but still rising.
        assertNull(watch(rule(), start = 2_000, gainPerSecond = 1_500, seconds = 18).second)
        assertNull(watch(rule(), start = 10_000, gainPerSecond = 300, seconds = 60).second, "0.3 s a second is 1.8 s over six: a rise")
    }

    @Test fun `a low estimate at the target buffer does not fire`() {
        val held = directBufferMs(memoryClassMb = 256, streamBps = remux)
        val scale = handoverScale(held)
        // Load control paused at the target: flat, just under it, for a minute.
        assertNull(watch(rule(scale), start = held - 100, gainPerSecond = 0, seconds = 60).second)
    }

    @Test fun `an estimate that carries the file does not fire`() {
        assertNull(watch(rule(), 25_000, 0, 20, estimate = 100_000_000).second)
        assertNull(watch(rule(), 25_000, 0, 20, estimate = null).second, "a meter that is still guessing is not read")
        assertNull(watch(rule(), 35_000, 0, 20, estimate = 10_000_000).second, "30 s or more is in hand")
    }

    @Test fun `a falling buffer still fires without waiting for six seconds`() {
        val (at, d) = drain(rule(), startAhead = 20_000, lossPerSecond = 1_000, estimate = 100_000_000)
        assertEquals("falling", assertIs<HandoverDecision.Ahead>(d).reason)
        assertTrue(at < 6_000, "fired at $at ms")
    }

    @Test fun `it fires once`() {
        val r = rule()
        assertIs<HandoverDecision.Ahead>(watch(r, 25_000, 0, 20).second)
        assertNull(r.observe(20_500, 20_500, 24_500, 50_000_000, true))
        r.reset()
        assertIs<HandoverDecision.Ahead>(watch(r, 25_000, 0, 20).second)
    }

    @Test fun `a pause or a seek is not a falling buffer`() {
        val r = rule()
        assertNull(r.observe(1_000, 0, 25_000, 50_000_000, playing = false))
        assertNull(r.observe(1_500, 0, -1, 50_000_000, playing = true), "a platform that cannot say how much is buffered never fires it")
    }

    @Test fun `a device that cannot hold thirty seconds scales every threshold to what it holds`() {
        // 256 MB of heap × 0.25 = 64 MB: 7.6 s of a 71 Mbps stream.
        val held = directBufferMs(memoryClassMb = 256, streamBps = remux)
        assertTrue(held in 7_000..8_000, "held $held ms")
        val scale = handoverScale(held)
        assertTrue(scale in 0.23..0.27, "scale $scale")
        val r = rule(scale)
        // 15 s × 0.24 = 3.6 s: a 5 s buffer is not "under fifteen" for this device, a 3 s one is.
        assertNull(drain(r, startAhead = 6_000, lossPerSecond = 1_000, seconds = 3).second)
        // The immediate form's 4 s is 1 s here.
        assertIs<HandoverDecision.Ahead>(watch(DirectPlayHandover(remux, scale), start = 5_000, gainPerSecond = 0, seconds = 10, estimate = 20_000_000).second)
        assertIs<HandoverDecision.Immediate>(watch(DirectPlayHandover(remux, scale), start = 900, gainPerSecond = 0, seconds = 10, estimate = 20_000_000).second)
        // A device that holds 30 s or more keeps the spec's numbers.
        assertEquals(DIRECT_BUFFER_TARGET_MS, directBufferMs(memoryClassMb = 512, streamBps = 5_000_000))
        assertEquals(1.0, handoverScale(DIRECT_BUFFER_TARGET_MS))
    }

    @Test fun `the target buffer is a time and is bounded by memory`() {
        assertEquals(DIRECT_BUFFER_TARGET_MS, directBufferMs(memoryClassMb = 0, streamBps = remux), "unknown memory keeps the target")
        assertEquals(DIRECT_BUFFER_TARGET_MS, directBufferMs(memoryClassMb = 256, streamBps = 0), "no direct play stream to size")
        assertTrue(directBufferMs(memoryClassMb = 192, streamBps = remux) < DIRECT_BUFFER_REFERENCE_MS)
    }

    // ── the switch ──

    @Test fun `the switch is due when the playhead reaches H`() {
        assertFalse(handoverSwitchDue(positionMs = 40_000, handoverAtMs = 45_000))
        assertFalse(handoverSwitchDue(44_700, 45_000))
        assertTrue(handoverSwitchDue(44_750, 45_000))
        assertTrue(handoverSwitchDue(45_400, 45_000))
        assertEquals(45_000, handoverStartMs(44_800, 45_000))
        assertEquals(46_000, handoverStartMs(46_000, 45_000), "a ticket that arrives late starts where the playhead is")
    }

    @Test fun `a seek takes the hand-over away`() {
        assertFalse(handoverStale(positionMs = 41_000, askedAtPositionMs = 40_000, handoverAtMs = 45_000))
        assertTrue(handoverStale(10_000, 40_000, 45_000), "back")
        assertTrue(handoverStale(120_000, 40_000, 45_000), "far forward")
        assertFalse(handoverStale(47_000, 40_000, 45_000), "a ticket that arrives just after H still switches")
    }
}
