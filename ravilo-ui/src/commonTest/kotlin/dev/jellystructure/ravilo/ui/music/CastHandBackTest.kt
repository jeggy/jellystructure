package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.shared.tv.CastTrackItem
import kotlin.test.Test
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * R353 (FR-R353-5) — a cast that ends from outside the app (Google Home's *Stop cast*) hands back the song the SPEAKER
 * had reached — also when another controller moved it on — not the one the phone handed over.
 */
class CastHandBackTest {
    private val queue = listOf(
        CastTrackItem(id = "a", title = "Song A", durationMs = 200_000L),
        CastTrackItem(id = "b", title = "Song B", durationMs = 180_000L),
        CastTrackItem(id = "c", title = "Song C", durationMs = 240_000L),
    )
    private fun status(index: Int, positionMs: Long, playing: Boolean, ended: Boolean = false, failed: Boolean = false, music: Boolean = true) =
        CastRemoteStatus(itemId = queue[index].id, music = music, queue = queue, queueIndex = index, positionMs = positionMs,
            durationMs = queue[index].durationMs ?: 0L, playing = playing, loaded = true, ended = ended, failed = failed)

    @Test
    fun theSpeakersSongAfterAnotherControllersNextComesBack() {
        // The phone handed over song A; Google Home pressed next; the speaker said "B at 0:42, playing"; 5 s later the
        // session ended from Google Home. The phone resumes B at 0:47 — not A.
        assertEquals(CastHandBack(index = 1, positionMs = 47_000L), castHandBack(status(1, 42_000L, playing = true), elapsedMs = 5_000L, endedByApp = false))
    }

    @Test
    fun aPausedSongComesBackWhereItStopped() {
        assertEquals(CastHandBack(2, 90_000L), castHandBack(status(2, 90_000L, playing = false), elapsedMs = 600_000L, endedByApp = false))
    }

    @Test
    fun aSongThatReachedItsEndComesBackAtItsStart() {
        // Amended 2026-10-02: a song played to its end resumes at 0:00, as a queue end does on the phone (FR-R322-5).
        assertEquals(CastHandBack(1, 0L), castHandBack(status(1, 170_000L, playing = true), elapsedMs = 60_000L, endedByApp = false))
        assertEquals(CastHandBack(2, 0L), castHandBack(status(2, 239_000L, playing = true), elapsedMs = 800L, endedByApp = false))
    }

    @Test
    fun theDashboardsStopHandsBackTheSpeakersSongAtItsPlace() {
        // 2026-10-02 on the Pixel 9: song A handed over, the dashboard's Next → song B, then the dashboard's Stop at 0:25.
        // The phone came back to song A at the hand-over's place. The last LIVE report is B at 0:25, 600 ms before the stop.
        assertEquals(CastHandBack(1, 25_600L), castHandBack(status(1, 25_000L, playing = true), elapsedMs = 600L, endedByApp = false))
    }

    @Test
    fun onlyALiveReportIsAPlaceToHandBack() {
        // Seen on the Pixel 9: after the dashboard's Stop the receiver said `ended`, then its media went idle — still
        // music, queue intact, not loaded, not ended, position 0. Kept as "the last music status", it replaced B at 0:13.
        val live = status(2, 13_000L, playing = true)
        assertTrue(castMusicLive(CastLinkState.CONNECTED, live))
        assertTrue(castMusicLive(CastLinkState.CONNECTED, live.copy(playing = false)), "paused on the speaker is still live")
        assertFalse(castMusicLive(CastLinkState.CONNECTED, live.copy(playing = false, ended = true)))
        assertFalse(castMusicLive(CastLinkState.CONNECTED, live.copy(playing = false, loaded = false, positionMs = 0L)))
        assertFalse(castMusicLive(CastLinkState.CONNECTED, live.copy(failed = true)))
        assertFalse(castMusicLive(CastLinkState.NONE, live))
    }

    @Test
    fun aStopOnTheDeviceWhileConnectedIsAHandBack() {
        val live = status(1, 25_000L, playing = true)
        val ended = live.copy(playing = false, ended = true)
        // The receiver's `ended` (the dashboard's Stop, the queue played out), the session still connected.
        assertTrue(castStoppedOnDevice(wasLinked = true, link = CastLinkState.CONNECTED, st = ended, endedByApp = false))
        assertTrue(castStoppedOnDevice(true, CastLinkState.CONNECTED, live.copy(playing = false, loaded = false), false))
        assertTrue(castStoppedOnDevice(true, CastLinkState.CONNECTED, null, false))
        // Not a stop on the device: still live, never linked, the app ended it, the session itself ended, a failure,
        // a film in its place.
        assertFalse(castStoppedOnDevice(true, CastLinkState.CONNECTED, live, false))
        assertFalse(castStoppedOnDevice(false, CastLinkState.CONNECTED, ended, false))
        assertFalse(castStoppedOnDevice(true, CastLinkState.CONNECTED, ended, endedByApp = true))
        assertFalse(castStoppedOnDevice(true, CastLinkState.NONE, ended, false))
        assertFalse(castStoppedOnDevice(true, CastLinkState.CONNECTED, live.copy(failed = true, playing = false), false))
        assertFalse(castStoppedOnDevice(true, CastLinkState.CONNECTED, status(0, 0L, playing = true, music = false), false))
    }

    @Test
    fun aQueueThatPlayedOutIsAtTheStartOfItsLastSong() {
        assertEquals(CastHandBack(2, 0L), castHandBack(status(2, 240_000L, playing = false, ended = true), elapsedMs = 1_000L, endedByApp = false))
    }

    @Test
    fun nothingWhenTheAppEndedItOrThereWasNoSong() {
        assertNull(castHandBack(status(1, 42_000L, playing = true), 0L, endedByApp = true), "Play on this phone / Stop casting bring it back themselves")
        assertNull(castHandBack(null, 0L, endedByApp = false))
        assertNull(castHandBack(status(1, 0L, playing = false, failed = true), 0L, endedByApp = false))
        assertNull(castHandBack(status(1, 0L, playing = true, music = false), 0L, endedByApp = false))
        assertNull(castHandBack(status(1, 0L, playing = true).copy(queueIndex = 7), 0L, endedByApp = false))
    }
}
