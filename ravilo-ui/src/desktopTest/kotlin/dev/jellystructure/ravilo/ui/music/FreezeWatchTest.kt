package dev.jellystructure.ravilo.ui.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DEB-3400 — FR-1 (the 8 s watch, once started), FR-2 (the 5 s edge), FR-3 (one restart), FR-4 (the 30 s rule), FR-5 (a network failure); DEB-5538 — FR-7 (a start is not a freeze), FR-8 (no QoE for a song). */
class FreezeWatchTest {
    private fun sample(t: Long, pos: Long, want: Boolean = true, seeking: Boolean = false, ended: Boolean = false, failed: Boolean = false) =
        FreezeWatch.Sample(t, pos, want, seeking, ended, failed)

    /** Ticks every 250 ms from [from] to [to] (inclusive) at a fixed position; returns the first non-null answer. */
    private fun FreezeWatch.still(from: Long, to: Long, pos: Long = 160_000L, want: Boolean = true): Long? {
        var t = from
        while (t <= to) { feed(sample(t, pos, want))?.let { return it }; t += 250 }
        return null
    }

    /** A watch whose song already moved 1 s past where it was loaded, just before the test's t = 0: FR-1's 8 s count applies. */
    private fun begun(): FreezeWatch = FreezeWatch().also {
        assertNull(it.feed(sample(-1_000, 159_000)))
        assertNull(it.feed(sample(-750, 160_000)))
    }

    @Test
    fun `a position that stands still for 8 s is frozen`() {
        val w = begun()
        assertNull(w.still(0, 7_750))
        assertEquals(8_000L, w.feed(sample(8_000, 160_000)))
        assertEquals(false, w.neverStarted)
    }

    @Test
    fun `a position that keeps moving is never frozen`() {
        val w = begun()
        var t = 0L
        while (t < 60_000) { assertNull(w.feed(sample(t, t))); t += 250 }
    }

    @Test
    fun `a creep of under a second does not hide a freeze`() {
        val w = begun()
        assertNull(w.feed(sample(0, 160_000)))
        assertNull(w.still(250, 7_750, pos = 160_600))
        assertNotNull(w.feed(sample(8_000, 160_900)))
    }

    @Test
    fun `a pause is not a freeze and the count starts again on play`() {
        val w = begun()
        assertNull(w.still(0, 30_000, want = false))
        assertNull(w.still(30_250, 37_750))
        assertNotNull(w.feed(sample(38_250, 160_000)))
    }

    @Test
    fun `a seek ended or failed song is not frozen`() {
        for (flags in listOf(sample(0, 1, seeking = true), sample(0, 1, ended = true), sample(0, 1, failed = true))) {
            val w = begun()
            var t = 0L
            while (t <= 20_000) { assertNull(w.feed(flags.copy(nowMs = t))); t += 250 }
        }
    }

    @Test
    fun `a buffering song that does not move for 8 s is frozen too`() {
        // buffering is not a sample field: the watch reads only wants-play and the position, which is the rule
        val w = begun()
        assertNull(w.still(0, 7_750, pos = 0))
        assertNotNull(w.feed(sample(8_000, 0)))
    }

    @Test
    fun `a tick gap of over 2 s starts the count again`() {
        val w = begun()
        assertNull(w.still(0, 6_000))
        // the Mac sleeps for a minute; the first tick after waking is not 8 s of stillness
        assertNull(w.feed(sample(66_000, 160_000)))
        assertNull(w.still(66_250, 73_750))
        assertNotNull(w.feed(sample(74_000, 160_000)))
    }

    @Test
    fun `a gap of exactly 2 s is not a sleep`() {
        val w = begun()
        assertNull(w.still(0, 6_000))
        assertNotNull(w.feed(sample(8_000, 160_000)), "a 2 s hiccup inside the window keeps the count")
    }

    @Test
    fun `a found freeze starts the next count from nothing`() {
        val w = begun()
        assertNotNull(w.still(0, 8_000))
        assertNull(w.feed(sample(8_250, 160_000)))
        assertNull(w.still(8_500, 15_750))
        assertNotNull(w.feed(sample(16_250, 160_000)))
    }

    // ── FR-2 ──

    @Test
    fun `a freeze within 5 s of the end has ended the song`() {
        val r = FreezeRecovery()
        assertEquals(FreezeVerdict.ENDED, r.onFreeze(positionMs = 160_400, durationMs = 163_100, book = false))
    }

    @Test
    fun `the 5 s edge is inclusive and a second before it is not the end`() {
        assertEquals(FreezeVerdict.ENDED, FreezeRecovery().onFreeze(158_000, 163_000, book = false))
        assertEquals(FreezeVerdict.RESTART, FreezeRecovery().onFreeze(157_999, 163_000, book = false))
    }

    @Test
    fun `an unknown duration is never the end`() {
        assertEquals(FreezeVerdict.RESTART, FreezeRecovery().onFreeze(160_000, 0, book = false))
    }

    @Test
    fun `a book part frozen near its end has ended too`() {
        assertEquals(FreezeVerdict.ENDED, FreezeRecovery().onFreeze(3_599_000, 3_600_000, book = true))
    }

    // ── FR-3 / FR-4 ──

    @Test
    fun `the first freeze at a point is restarted once`() {
        val r = FreezeRecovery()
        assertEquals(FreezeVerdict.RESTART, r.onFreeze(60_000, 200_000, book = false))
    }

    @Test
    fun `a second freeze within 30 s of the first skips the song`() {
        val r = FreezeRecovery()
        r.onFreeze(60_000, 200_000, book = false)
        assertEquals(FreezeVerdict.SKIP, r.onFreeze(60_000, 200_000, book = false))
    }

    @Test
    fun `a restart that is still loading at position zero has failed`() {
        val r = FreezeRecovery()
        r.onFreeze(60_000, 200_000, book = false)
        assertEquals(FreezeVerdict.SKIP, r.onFreeze(0, 200_000, book = false))
    }

    @Test
    fun `the 30 s rule has its edge at 30 s past the first freeze point`() {
        val a = FreezeRecovery()
        a.onFreeze(60_000, 200_000, book = false)
        assertEquals(FreezeVerdict.SKIP, a.onFreeze(89_999, 200_000, book = false))
        val b = FreezeRecovery()
        b.onFreeze(60_000, 200_000, book = false)
        assertEquals(FreezeVerdict.RESTART, b.onFreeze(90_000, 200_000, book = false), "a new freeze point gets a restart of its own")
    }

    @Test
    fun `a freeze minutes later somewhere else gets its own restart`() {
        val r = FreezeRecovery()
        assertEquals(FreezeVerdict.RESTART, r.onFreeze(60_000, 600_000, book = false))
        assertEquals(FreezeVerdict.RESTART, r.onFreeze(300_000, 600_000, book = false))
        assertEquals(FreezeVerdict.SKIP, r.onFreeze(310_000, 600_000, book = false))
    }

    @Test
    fun `a failed restart of a book part gets the sheet and never a skip`() {
        val r = FreezeRecovery()
        assertEquals(FreezeVerdict.RESTART, r.onFreeze(60_000, 3_600_000, book = true))
        assertEquals(FreezeVerdict.SHEET, r.onFreeze(60_000, 3_600_000, book = true))
    }

    @Test
    fun `a new song forgets the last one's restart`() {
        val r = FreezeRecovery()
        r.onFreeze(60_000, 200_000, book = false)
        r.newSong()
        assertEquals(FreezeVerdict.RESTART, r.onFreeze(60_000, 200_000, book = false))
    }

    @Test
    fun `a seek forgets the freeze point`() {
        val r = FreezeRecovery()
        r.onFreeze(60_000, 200_000, book = false)
        r.moved()
        assertEquals(FreezeVerdict.RESTART, r.onFreeze(61_000, 200_000, book = false))
    }

    // ── FR-5 ──

    @Test
    fun `a network failure is restarted once and then gets the sheet`() {
        val r = FreezeRecovery()
        assertEquals(FreezeVerdict.RESTART, r.onFailure(12_000, network = true))
        assertEquals(FreezeVerdict.SHEET, r.onFailure(0, network = true))
    }

    @Test
    fun `any other failure goes straight to the sheet`() {
        val r = FreezeRecovery()
        assertEquals(FreezeVerdict.SHEET, r.onFailure(12_000, network = false))
        assertEquals(FreezeVerdict.RESTART, r.onFailure(12_000, network = true), "and spends no restart")
    }

    @Test
    fun `a network failure after a freeze restart gets no second restart`() {
        val r = FreezeRecovery()
        r.onFreeze(60_000, 200_000, book = false)
        assertEquals(FreezeVerdict.SHEET, r.onFailure(0, network = true))
    }

    // ── FR-7 ──

    @Test
    fun `a song that loads for 25 s and then moves gets no verdict`() {
        val w = FreezeWatch()
        assertNull(w.still(0, 25_000, pos = 0))
        var t = 25_250L
        while (t < 90_000) { assertNull(w.feed(sample(t, t - 25_000))); t += 250 }
    }

    @Test
    fun `a song that has not moved after 30 s is frozen and never started`() {
        val w = FreezeWatch()
        assertNull(w.still(0, 29_750, pos = 0))
        assertEquals(30_000L, w.feed(sample(30_000, 0)))
        assertEquals(true, w.neverStarted)
    }

    @Test
    fun `a creep of under a second does not start the song`() {
        val w = FreezeWatch()
        assertNull(w.feed(sample(0, 0)))
        assertNull(w.still(250, 29_750, pos = 800))
        assertNotNull(w.feed(sample(30_250, 900)))
        assertEquals(true, w.neverStarted)
    }

    @Test
    fun `once a song has moved 1 s the 8 s count applies again`() {
        val w = FreezeWatch()
        assertNull(w.feed(sample(0, 0)))
        assertNull(w.feed(sample(10_000, 1_000)))
        assertNull(w.still(10_250, 17_750, pos = 1_000))
        assertEquals(8_000L, w.feed(sample(18_000, 1_000)))
        assertEquals(false, w.neverStarted)
    }

    @Test
    fun `a reset makes the next load a start again`() {
        val w = begun()
        w.reset()
        assertNull(w.still(0, 29_750, pos = 5_000))
        assertNotNull(w.feed(sample(30_000, 5_000)))
        assertEquals(true, w.neverStarted)
    }

    @Test
    fun `a never started song is skipped with no restart and spends no restart`() {
        val r = FreezeRecovery()
        assertEquals(FreezeVerdict.SKIP, r.onFreeze(0, 200_000, book = false, neverStarted = true))
        assertEquals(FreezeVerdict.RESTART, r.onFreeze(60_000, 200_000, book = false), "a later freeze of the next song still gets its restart")
    }

    @Test
    fun `a book part that never started gets the sheet and never a skip`() {
        assertEquals(FreezeVerdict.SHEET, FreezeRecovery().onFreeze(0, 3_600_000, book = true, neverStarted = true))
    }

    @Test
    fun `a never started song at its very end has ended`() {
        assertEquals(FreezeVerdict.ENDED, FreezeRecovery().onFreeze(160_400, 163_100, book = false, neverStarted = true))
    }

    // ── DEB-5531 (FR-9, FR-10) — a song not started after 6 s asks once more ──

    /** Ticks every 250 ms from [from] to [to] for a song whose position stands at its load point; returns the first time [StartReask.due] says ask. */
    private fun StartReask.waitFor(from: Long, to: Long, eligible: Boolean = true, started: Boolean = false, want: Boolean = true): Long? {
        var t = from
        while (t <= to) { if (due(t, started, want, eligible)) return t; t += 250 }
        return null
    }

    @Test
    fun `a song not started at 6 s with an observed figure sent asks once, and a lighter answer is loaded`() {
        val r = StartReask()
        assertNull(r.waitFor(0, 5_750))
        assertEquals(6_000L, r.waitFor(6_000, 12_000))
        assertNull(r.waitFor(12_250, 60_000), "it asks once")
        assertEquals(true, StartReask.loadsAnswer(currentDirect = true, answerDirect = false))
    }

    @Test
    fun `a song given the same direct copy keeps its load and does not ask again`() {
        val r = StartReask()
        assertEquals(6_000L, r.waitFor(0, 10_000))
        assertEquals(false, StartReask.loadsAnswer(currentDirect = true, answerDirect = true))
        assertNull(r.waitFor(10_250, 90_000))
    }

    @Test
    fun `a song with no observed figure sent never asks`() {
        assertNull(StartReask().waitFor(0, 90_000, eligible = false))
    }

    @Test
    fun `a song that has started, or a paused one, does not ask`() {
        assertNull(StartReask().waitFor(0, 30_000, started = true))
        val r = StartReask()
        assertNull(r.waitFor(0, 30_000, want = false))
        assertEquals(36_250L, r.waitFor(30_250, 40_000), "the count starts when the viewer plays")
    }

    @Test
    fun `a tick after a gap starts the count again, and a new load counts from nothing`() {
        val r = StartReask()
        assertNull(r.waitFor(0, 4_000))
        assertNull(r.waitFor(10_000, 15_750), "the Mac slept: only ran time counts")
        r.newLoad()
        assertNull(r.waitFor(16_000, 21_750))
        assertEquals(22_000L, r.waitFor(22_000, 30_000))
    }

    @Test
    fun `a restart of ours does not let the song ask again, and a new song does`() {
        val r = StartReask()
        assertNotNull(r.waitFor(0, 7_000))
        r.newLoad()   // a restart keeps the song's memory
        assertNull(r.waitFor(8_000, 60_000))
        r.newSong()
        assertNotNull(r.waitFor(61_000, 70_000))
    }

    @Test
    fun `a lighter copy or a transcode is not a direct load and is never eligible, so it never asks`() {
        assertEquals(false, StartReask.loadsAnswer(currentDirect = false, answerDirect = false))
        assertEquals(false, StartReask.loadsAnswer(currentDirect = false, answerDirect = true))
    }
}
