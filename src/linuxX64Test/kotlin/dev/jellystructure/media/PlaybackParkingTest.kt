package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 295 — a `prewarm_subtitles` job that stops for playback must wait, not spin. Production on 2026-10-02: the
 * household's *defer while watching* switch was off, so the claim never held the row, but the walk still stopped on
 * playback alone and the row was claimed again at once — about eight times a second while anything played.
 */
class PlaybackParkingTest {
    private var now = 1_000_000L
    private val parking = PlaybackParking(nowMs = { now }, backoffMs = 30_000L)

    @Test
    fun `the walk yields only where the claim would hold the row`() {
        for (rowDefers in listOf(true, false)) for (household in listOf(true, false)) for (playing in listOf(true, false)) {
            // claimNext: holding = waitsForPlayback(true, household, playing); then only rows without the flag are taken.
            val claimHolds = MediaJobQueue.waitsForPlayback(true, household, playing) && rowDefers
            assertEquals(claimHolds, MediaJobQueue.waitsForPlayback(rowDefers, household, playing), "row=$rowDefers household=$household playing=$playing")
        }
        // The production case: switch off, something playing — the walk carries on instead of yielding.
        assertFalse(MediaJobQueue.waitsForPlayback(rowDefers = true, householdDefers = false, playing = true))
    }

    @Test
    fun `a parked row is held while playback goes on`() {
        parking.park("job-1")
        assertTrue(parking.holds("job-1", playing = true))
        now += 29_000L
        assertTrue(parking.holds("job-1", playing = true))
    }

    @Test
    fun `a parked row is claimable the moment playback ends`() {
        parking.park("job-1")
        now += 1_000L
        assertFalse(parking.holds("job-1", playing = false))
    }

    @Test
    fun `a parked row is claimable again after its back-off even if playback goes on`() {
        parking.park("job-1")
        now += 30_000L
        assertFalse(parking.holds("job-1", playing = true))
    }

    @Test
    fun `the claim releases a parked row once so the resume is logged once`() {
        parking.park("job-1")
        assertTrue(parking.release("job-1"))
        assertFalse(parking.release("job-1"))
        assertFalse(parking.holds("job-1", playing = true))
    }

    @Test
    fun `a row that never stopped is never held`() {
        assertFalse(parking.holds("job-2", playing = true))
        assertFalse(parking.release("job-2"))
    }

    @Test
    fun `a row that yields every time it runs is requeued once per back-off and not once per pass`() {
        // A worker polling once a second through two minutes of playback, against one row that yields whenever it runs.
        var requeues = 0
        repeat(120) {
            if (!parking.holds("job-1", playing = true)) { parking.release("job-1"); parking.park("job-1"); requeues++ }
            now += 1_000L
        }
        assertEquals(4, requeues)
    }
}
