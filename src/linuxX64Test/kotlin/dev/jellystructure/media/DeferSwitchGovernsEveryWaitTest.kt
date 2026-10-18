package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-1737 (FR-262-1) — the household switch governs every wait for playback, not only the queue's claim. */
class DeferSwitchGovernsEveryWaitTest {
    @Test
    fun `the pipeline enqueues segment rows as background work whatever the switch says`() {
        assertTrue(segmentJobParams().deferWhilePlaying)
        assertTrue(segmentJobParams(season = 3).deferWhilePlaying)
        assertEquals(3, segmentJobParams(season = 3).segmentSeason)
        assertEquals(null, segmentJobParams().segmentSeason)
    }

    @Test
    fun `the file-fix tick and Bazarr steering do not wait with the switch off and a TV playing`() {
        // Both sites ask the queue's rule with rowDefers = true (the work is background work).
        assertFalse(MediaJobQueue.waitsForPlayback(rowDefers = true, householdDefers = false, playing = true))
        assertTrue(MediaJobQueue.waitsForPlayback(rowDefers = true, householdDefers = true, playing = true))
        assertFalse(MediaJobQueue.waitsForPlayback(rowDefers = true, householdDefers = true, playing = false))
    }

    @Test
    fun `a parked row no longer holds its lane once the switch is off`() {
        var now = 0L
        val parking = PlaybackParking(nowMs = { now })
        parking.park("job")
        now = 1_000
        val switchOn = MediaJobQueue.waitsForPlayback(true, householdDefers = true, playing = true)
        val switchOff = MediaJobQueue.waitsForPlayback(true, householdDefers = false, playing = true)
        assertTrue(parking.holds("job", switchOn))
        assertFalse(parking.holds("job", switchOff))
    }
}
