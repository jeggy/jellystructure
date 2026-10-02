package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.shared.tv.CastTrackItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R358 — a cast that never started gives the song back where it was. The Mac, 2026-10-02: a song at 0:30 cast to a
 * speaker whose token was rejected; the bar read 0:00 and *Stop casting* brought the song back at 0:00.
 */
class CastHandOverTest {
    private val queue = listOf(
        CastTrackItem(id = "a", title = "Song A", durationMs = 200_000L),
        CastTrackItem(id = "b", title = "Song B", durationMs = 180_000L),
        CastTrackItem(id = "c", title = "Song C", durationMs = 240_000L),
    )
    private val handOver = CastHandOver(queue, index = 0, positionMs = 30_000L)

    /** The sender's own status the moment it sends the queue (both senders): loaded, loading, nothing said of a place. */
    private val sending = CastRemoteStatus(itemId = "a", loaded = true, buffering = true, music = true, queue = queue, queueIndex = 0)

    private fun report(index: Int, positionMs: Long, playing: Boolean, buffering: Boolean = false) =
        CastRemoteStatus(itemId = queue[index].id, music = true, queue = queue, queueIndex = index, positionMs = positionMs,
            durationMs = queue[index].durationMs ?: 0L, playing = playing, buffering = buffering, loaded = true)

    /** What [MusicCast]'s collector does with each report: heard once a report is a place; the shown status is kept. */
    private fun run(vararg reports: CastRemoteStatus): Pair<CastRemoteStatus?, Boolean> {
        var heard = false
        var last: CastRemoteStatus? = null
        for (raw in reports) {
            val live = castMusicLive(CastLinkState.CONNECTED, raw)
            if (live && castReportIsAPlace(raw, handOver)) heard = true
            val shown = castShownStatus(raw, handOver, heard)
            if (live && shown != null && shown.queue.isNotEmpty()) last = shown
        }
        return last to heard
    }

    @Test
    fun handedOverAt30sNoReportStopGivesTheSameSongAt30s() {
        // FR-R358-4, first case — the sender's own status was the only one, at position 0.
        val (last, heard) = run(sending)
        assertFalse(heard)
        assertEquals(CastHandBack(0, 30_000L), castHandBack(castHandBackFrom(last, handOver), elapsedMs = 20_000L, endedByApp = false))
        // Nothing live at all (the session dropped before any status): the hand-over itself.
        assertEquals(CastHandBack(0, 30_000L), castHandBack(castHandBackFrom(null, handOver), elapsedMs = 0L, endedByApp = false))
    }

    @Test
    fun aReportAt45sOnTheNextSongIsThePlaceHandedBack() {
        // FR-R358-4, second case.
        val (last, heard) = run(sending, report(0, 30_000L, playing = true), report(1, 45_000L, playing = true))
        assertTrue(heard)
        assertEquals(CastHandBack(1, 45_000L), castHandBack(castHandBackFrom(last, handOver), elapsedMs = 0L, endedByApp = false))
    }

    @Test
    fun aReportOfZeroWhileTheReceiverIsStillLoadingIsNotAPlace() {
        val loading = report(0, 0L, playing = false, buffering = true)
        assertFalse(castReportIsAPlace(loading, handOver))
        assertFalse(castReportIsAPlace(sending, handOver))
        // The receiver's "no server" (a rejected token) keeps the item loaded at 0, playing nothing.
        val (last, _) = run(sending, loading, sending.copy(noServer = true))
        assertEquals(CastHandBack(0, 30_000L), castHandBack(castHandBackFrom(last, handOver), elapsedMs = 5_000L, endedByApp = false))
    }

    @Test
    fun theBarShowsTheHandOverPlaceUntilTheFirstReport() {
        // FR-R358-2 — not 0:00, and not playing.
        val shown = castShownStatus(sending, handOver, heard = false)!!
        assertEquals(30_000L, shown.positionMs)
        assertEquals("a", shown.itemId)
        assertEquals(0, shown.queueIndex)
        assertEquals(200_000L, shown.durationMs)
        assertFalse(shown.playing)
        // Once the device has reported playing, its own word stands.
        val playing = report(0, 31_000L, playing = true)
        assertSame(playing, castShownStatus(playing, handOver, heard = true))
    }

    @Test
    fun theSpeakerMovingOnIsAPlaceEvenBeforeItPlays() {
        // Next pressed while the speaker was still loading: song B at 0 is the speaker's own place.
        assertTrue(castReportIsAPlace(report(1, 0L, playing = false, buffering = true), handOver))
        assertTrue(castReportIsAPlace(report(0, 31_000L, playing = true), handOver))
    }

    @Test
    fun aPlainJoinTakesTheDevicesWordAsItIs() {
        // Nothing was handed over (the phone joined a speaker already playing): every report is the device's own.
        val paused = report(2, 0L, playing = false)
        assertTrue(castReportIsAPlace(paused, null))
        assertSame(paused, castShownStatus(paused, null, heard = false))
        assertNull(castHandBackFrom(null, null))
    }
}
