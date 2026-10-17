package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 317 acceptance 6 — the verdict, against the numbers measured on 2026-10-10: *Film T*'s nine
 * windows, the reported broadcast's seven, that broadcast's 160 ms remux, a planted drift and a file with
 * one confident window. Also the sign (FR-317-7: positive = audio early) and the library's zero (FR-317-6).
 */
class AvSyncVerdictTest {
    /** A window, as the sidecar returns it: offset in frames, confidence, face frames behind it. */
    private fun w(startS: Double, offset: Int?, conf: Double, frames: Int) =
        AvSyncWindow(startS = startS, offsetFrames = offset, conf = conf, frames = frames)

    // The nine windows measured on the second out-of-sync film the random sample found (the spec's Film T).
    private val filmT = listOf(
        w(600.0, 2, 2.45, 191), w(1200.0, 3, 2.54, 936), w(1800.0, 2, 0.16, 601), w(2400.0, 3, 1.03, 710),
        w(3000.0, 4, 2.68, 329), w(3600.0, 15, 0.31, 197), w(4200.0, 4, 3.13, 1311), w(4800.0, 4, 1.05, 713),
        w(5400.0, 2, 0.94, 1235),
    )

    // The reported broadcast, seven windows with the prototype: +3, +4, +4, +4, +3, +3, +3, of which three
    // are confident — +4/4.37, +3/4.29, +3/2.05 (the spec's FR-317-7 worked example, measured 2026-10-10).
    private val broadcast = listOf(
        w(1000.0, 3, 1.10, 500), w(2000.0, 4, 4.37, 900), w(3000.0, 4, 1.20, 300), w(4000.0, 4, 0.80, 400),
        w(5000.0, 3, 4.29, 1100), w(6000.0, 3, 2.05, 700), w(7000.0, 3, 0.60, 600),
    )

    @Test
    fun `Film T reads offset — 200 ms early`() {
        // Three windows clear conf 2 over 250 frames: +3, +4, +4. From a zero of −1 that is +4, +5, +5.
        val r = AvSyncVerdict.of(filmT, zeroFrames = -1)
        assertEquals(AvSyncState.OFFSET, r.state)
        assertEquals(5, r.medianFrames)
        assertEquals(200L, r.offsetMs)
        assertEquals(3, r.confidentWindows)
        assertTrue(r.audioEarly)
        assertTrue(r.isFinding)
    }

    @Test
    fun `the reported broadcast reads offset — 160 ms early`() {
        val r = AvSyncVerdict.of(broadcast, zeroFrames = -1)
        assertEquals(AvSyncState.OFFSET, r.state)
        assertEquals(4, r.medianFrames)
        assertEquals(160L, r.offsetMs)
    }

    @Test
    fun `the same broadcast remuxed with the command's own 160 ms reads in_sync`() {
        // The measured answer after `-itsoffset 0.160`: the same seven windows at −1, 0, 0, 0, −1, −1, −1,
        // of which three are confident — 0/4.56, −1/4.49, −1/2.07.
        val fixed = listOf(
            w(1000.0, -1, 1.10, 500), w(2000.0, 0, 4.56, 900), w(3000.0, 0, 1.20, 300), w(4000.0, 0, 0.80, 400),
            w(5000.0, -1, 4.49, 1100), w(6000.0, -1, 2.07, 700), w(7000.0, -1, 0.60, 600),
        )
        val r = AvSyncVerdict.of(fixed, zeroFrames = -1)
        assertEquals(AvSyncState.IN_SYNC, r.state)
        assertEquals(0L, r.offsetMs)
        assertFalse(r.isFinding)
    }

    @Test
    fun `in-sync material at the library's own zero is in_sync — not one frame late`() {
        // Why zero is the library's (FR-317-6): a film reading −1 everywhere is what normal looks like.
        val minusOne = (1..4).map { w(it * 1000.0, -1, 3.0, 800) }
        assertEquals(AvSyncState.IN_SYNC, AvSyncVerdict.of(minusOne, zeroFrames = -1).state)
        // Against a naive zero of 0 the same film would read −1 — still inside the threshold, but the
        // median moves, which is exactly the error a library-wide zero removes.
        assertEquals(-40L, AvSyncVerdict.of(minusOne, zeroFrames = 0).offsetMs)
    }

    @Test
    fun `a linear plus one to plus six over five windows reads drift`() {
        val drifting = listOf(
            w(600.0, 1, 3.0, 800), w(1800.0, 2, 3.0, 800), w(3000.0, 4, 3.0, 800),
            w(4200.0, 5, 3.0, 800), w(5400.0, 6, 3.0, 800),
        )
        val r = AvSyncVerdict.of(drifting, zeroFrames = 0)
        assertEquals(AvSyncState.DRIFT, r.state)
        assertNull(r.offsetMs)
        assertEquals(5, r.confidentWindows)
        // The line's value at the first and last window, not the raw readings (FR-317-7).
        assertEquals(600.0, r.driftFromS)
        assertEquals(5400.0, r.driftToS)
        assertTrue((r.driftToMs ?: 0) - (r.driftFromMs ?: 0) >= 3 * AvSyncVerdict.MS_PER_FRAME)
        assertTrue(r.isFinding)
    }

    @Test
    fun `one confident window is unknown — never clean`() {
        val r = AvSyncVerdict.of(listOf(w(600.0, 4, 3.0, 800), w(1800.0, 1, 0.4, 900)), zeroFrames = -1)
        assertEquals(AvSyncState.UNKNOWN, r.state)
        assertEquals(1, r.confidentWindows)
        assertFalse(r.isFinding)
        assertNull(r.offsetMs)
    }

    @Test
    fun `no window with a face at all is unknown with no_faces`() {
        val r = AvSyncVerdict.of(listOf(AvSyncWindow(startS = 600.0), AvSyncWindow(startS = 1800.0)), zeroFrames = -1)
        assertEquals(AvSyncState.UNKNOWN, r.state)
        assertEquals(AvSyncReason.NO_FACES, r.reason)
    }

    @Test
    fun `a skip reason is unknown outright — whatever the windows say`() {
        val r = AvSyncVerdict.of(broadcast, zeroFrames = -1, reason = AvSyncReason.ANIMATION)
        assertEquals(AvSyncState.UNKNOWN, r.state)
        assertEquals(AvSyncReason.ANIMATION, r.reason)
        assertFalse(r.isFinding)
    }

    @Test
    fun `readings that neither hold still nor lie on a line are unknown`() {
        val noisy = listOf(
            w(600.0, 0, 3.0, 800), w(1800.0, 6, 3.0, 800), w(3000.0, -4, 3.0, 800),
            w(4200.0, 7, 3.0, 800), w(5400.0, 1, 3.0, 800),
        )
        val r = AvSyncVerdict.of(noisy, zeroFrames = 0)
        assertEquals(AvSyncState.UNKNOWN, r.state)
        assertTrue(r.why!!.contains("disagree"))
    }

    // ── FR-317-17 (amendment 2026-10-12) — the second triple's trigger ──

    /** The 20/50/80 % windows of a 90-minute film, drifting +1 → +6 frames across it. */
    private val driftFirstTriple = listOf(w(1080.0, 1, 3.0, 800), w(2700.0, 4, 3.0, 800), w(4320.0, 6, 3.0, 800))

    /** Its 35/65/90 % windows, on the same line. */
    private val driftSecondTriple = listOf(w(1890.0, 2, 3.0, 800), w(3510.0, 5, 3.0, 800), w(4860.0, 7, 3.0, 800))

    @Test
    fun `three confident windows that disagree are unknown — a drift cannot be reached in three`() {
        // Unchanged, and the reason FR-317-17 exists: DRIFT_MIN_WINDOWS is 4, so the exact signature of a
        // steady drift measured in three windows can only ever come out `unknown` — never counted, never
        // shown. The answer is more windows, never a lower bar.
        val r = AvSyncVerdict.of(driftFirstTriple, zeroFrames = -1)
        assertEquals(AvSyncState.UNKNOWN, r.state)
        assertEquals(3, r.confidentWindows)
        assertTrue(r.why!!.contains("disagree"))
        assertTrue(AvSyncVerdict.wantsMoreWindows(r), "a second triple must be asked for")
    }

    @Test
    fun `the same three plus a second triple on the line are a drift`() {
        val r = AvSyncVerdict.of(driftFirstTriple + driftSecondTriple, zeroFrames = -1)
        assertEquals(AvSyncState.DRIFT, r.state)
        assertEquals(6, r.confidentWindows)
        assertEquals(1080.0, r.driftFromS)
        assertEquals(4860.0, r.driftToS)
        assertTrue((r.driftToMs ?: 0) - (r.driftFromMs ?: 0) >= AvSyncVerdict.DRIFT_MIN_FRAMES * AvSyncVerdict.MS_PER_FRAME)
        assertEquals(4, AvSyncVerdict.DRIFT_MIN_WINDOWS, "the dev review's bar against calling noise a drift")
    }

    @Test
    fun `more windows are wanted only while the verdict is unknown without a skip`() {
        fun steady(frames: Int) = (1..4).map { w(it * 1000.0, frames, 3.0, 800) }
        assertFalse(AvSyncVerdict.wantsMoreWindows(AvSyncVerdict.of(steady(0), zeroFrames = -1)))      // in_sync
        assertFalse(AvSyncVerdict.wantsMoreWindows(AvSyncVerdict.of(steady(4), zeroFrames = -1)))      // offset
        // FR-317-42 — a drift is an answer too, and ends the escalation (FR-317-17's trigger did not stop on it).
        assertFalse(AvSyncVerdict.wantsMoreWindows(AvSyncVerdict.of(driftFirstTriple + driftSecondTriple, zeroFrames = -1)))
        // Too few confident (the original rule), no face at all, and a confident disagreement.
        assertTrue(AvSyncVerdict.wantsMoreWindows(AvSyncVerdict.of(listOf(w(600.0, 4, 3.0, 800), w(1800.0, 1, 0.4, 900)), zeroFrames = -1)))
        assertTrue(AvSyncVerdict.wantsMoreWindows(AvSyncVerdict.of(listOf(AvSyncWindow(startS = 600.0)), zeroFrames = -1)))
        assertTrue(AvSyncVerdict.wantsMoreWindows(AvSyncVerdict.of(driftFirstTriple, zeroFrames = -1)))
        // A skip decided by the backend is not a file to measure harder.
        for (skip in AvSyncReason.SKIPS) {
            assertFalse(AvSyncVerdict.wantsMoreWindows(AvSyncVerdict.of(emptyList(), zeroFrames = -1, reason = skip)), skip)
        }
    }

    @Test
    fun `the five triples are in order and all fifteen fractions differ`() {
        assertEquals(
            listOf(
                listOf(0.20, 0.50, 0.80), listOf(0.35, 0.65, 0.90), listOf(0.10, 0.425, 0.725),
                listOf(0.275, 0.575, 0.85), listOf(0.15, 0.3125, 0.6125),
            ),
            AvSyncVerdict.PASS_FRACTIONS,
        )
        assertEquals(5, AvSyncVerdict.PASSES_MAX)
        assertEquals(15, AvSyncVerdict.PASS_FRACTIONS.flatten().toSet().size)
    }

    @Test
    fun `the thresholds are asymmetric - early is noticed sooner than late`() {
        fun steady(frames: Int) = (1..4).map { w(it * 1000.0, frames, 3.0, 800) }
        // Early: +2 frames (80 ms) is a finding, +1 is not.
        assertEquals(AvSyncState.OFFSET, AvSyncVerdict.of(steady(1), zeroFrames = -1).state)   // rel +2
        assertEquals(AvSyncState.IN_SYNC, AvSyncVerdict.of(steady(0), zeroFrames = -1).state)  // rel +1
        // Late: −4 frames (160 ms) is a finding, −3 is not.
        assertEquals(AvSyncState.OFFSET, AvSyncVerdict.of(steady(-5), zeroFrames = -1).state)  // rel −4
        assertEquals(AvSyncState.IN_SYNC, AvSyncVerdict.of(steady(-4), zeroFrames = -1).state) // rel −3
        assertFalse(AvSyncVerdict.of(steady(-5), zeroFrames = -1).audioEarly)
    }

    @Test
    fun `a window is confident only above both thresholds`() {
        assertTrue(AvSyncVerdict.isConfident(w(0.0, 2, 2.0, 250)))
        assertFalse(AvSyncVerdict.isConfident(w(0.0, 2, 1.99, 250)))
        assertFalse(AvSyncVerdict.isConfident(w(0.0, 2, 2.0, 249)))
        assertFalse(AvSyncVerdict.isConfident(w(0.0, null, 9.0, 9000)))
    }

    @Test
    fun `the library's zero is minus one until two hundred confident windows exist`() {
        val few = (1..199).map { 3 to 3.0 }
        assertEquals(AvSyncVerdict.DEFAULT_ZERO_FRAMES, AvSyncVerdict.zeroFrames(few))
        // 200 windows, most of them at 0: the median is what the library actually reads.
        val many = (1..150).map { 0 to 3.0 } + (1..50).map { 4 to 3.0 }
        assertEquals(0, AvSyncVerdict.zeroFrames(many))
    }

    @Test
    fun `the weighted median is pulled by evidence — not by headcount`() {
        // Two long confident windows at +4 outweigh three brief ones at 0.
        assertEquals(4, AvSyncVerdict.weightedMedian(listOf(0 to 0.2, 0 to 0.2, 0 to 0.2, 4 to 5.0, 4 to 5.0)))
        assertNull(AvSyncVerdict.weightedMedian(emptyList()))
    }

    @Test
    fun `the sign is written the way a human reads it`() {
        assertEquals("+160 ms", AvSyncVerdict.signedMs(160))
        assertEquals("−160 ms", AvSyncVerdict.signedMs(-160))
    }

    @Test
    fun `a file under two minutes is too_short — whatever its windows read`() {
        val r = AvSyncVerdict.of(broadcast, zeroFrames = -1, runtimeS = 119.0)
        assertEquals(AvSyncState.UNKNOWN, r.state)
        assertEquals(AvSyncReason.TOO_SHORT, r.reason)
        // An unknown length is never a skip.
        assertEquals(AvSyncState.OFFSET, AvSyncVerdict.of(broadcast, zeroFrames = -1, runtimeS = null).state)
        assertEquals(AvSyncState.OFFSET, AvSyncVerdict.of(broadcast, zeroFrames = -1, runtimeS = 2700.0).state)
    }
}
