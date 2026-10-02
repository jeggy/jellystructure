package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.shared.tv.CastTrackItem
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun aPlayingSongNeverRunsPastItsEnd() {
        assertEquals(CastHandBack(1, 180_000L), castHandBack(status(1, 170_000L, playing = true), elapsedMs = 60_000L, endedByApp = false))
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
