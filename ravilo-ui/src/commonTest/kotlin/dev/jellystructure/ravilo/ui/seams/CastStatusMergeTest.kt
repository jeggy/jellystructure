package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.CastTrackItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R330 (FR-R330-3, dev review 5) — the rules both senders share, tested once. */
class CastStatusMergeTest {
    private val playing = CastMediaSnapshot(playerState = "PLAYING", positionMs = 61_000, durationMs = 5_400_000, title = "From the SDK")

    @Test
    fun `the receiver's word wins over the media's own metadata and the media gives the position`() {
        val said = CastReceiverMessage(type = "status", itemId = "i1", title = "From the receiver", hasNext = true)
        val st = mergeCastStatus(null, said, playing, "status", 0)
        assertEquals("From the receiver", st.title); assertEquals("i1", st.itemId)
        assertEquals(61_000, st.positionMs); assertTrue(st.playing); assertTrue(st.loaded); assertTrue(st.hasNext)
        assertEquals("From the SDK", mergeCastStatus(null, null, playing, null, 0).title)
    }

    @Test
    fun `finished is ended and idle with nothing said is not loaded`() {
        val done = mergeCastStatus(null, null, CastMediaSnapshot(playerState = "IDLE", idleFinished = true), null, 0)
        assertTrue(done.ended); assertFalse(done.loaded)
        assertTrue(mergeCastStatus(null, null, CastMediaSnapshot(), "ended", 0).ended, "the receiver's own ended event")
        assertTrue(mergeCastStatus(null, null, CastMediaSnapshot(playerState = "LOADING"), null, 0).buffering)
    }

    @Test
    fun `a busy server is remembered until a status or tracks message clears it`() {
        val busy = mergeCastStatus(null, CastReceiverMessage(type = "busy", retryAfter = 30), playing, "busy", 1_000)
        assertEquals(30, busy.busyRetryAfter); assertEquals(1_000, busy.busySinceMs)
        val still = mergeCastStatus(busy, CastReceiverMessage(type = "nextup", nextupSecs = 10), playing, "nextup", 2_000)
        assertEquals(30, still.busyRetryAfter); assertEquals(10, still.nextUpSecs)
        val cleared = mergeCastStatus(still, CastReceiverMessage(type = "status"), playing, "status", 3_000)
        assertNull(cleared.busyRetryAfter); assertNull(cleared.nextUpSecs)
    }

    @Test
    fun `an active CAF text track is the selection and otherwise a burn-in only the receiver knows`() {
        val subs = listOf(CastTrack(index = 0, label = "English", trackId = 11), CastTrack(index = 1, label = "PGS", trackId = 99))
        val text = mergeCastStatus(null, CastReceiverMessage(type = "tracks", subtitleTracks = subs, selectedSub = 1),
            playing.copy(activeTrackIds = setOf(11), mediaTrackIds = setOf(11)), "tracks", 0)
        assertEquals(0, text.selectedSub)
        val burned = mergeCastStatus(null, CastReceiverMessage(type = "tracks", subtitleTracks = subs, selectedSub = 1),
            playing.copy(mediaTrackIds = setOf(11)), "tracks", 0)
        assertEquals(1, burned.selectedSub, "track 99 has no CAF track: it is burned in")
        assertTrue(isCastBurnIn(subs[1], playing.copy(mediaTrackIds = setOf(11))))
        assertFalse(isCastBurnIn(subs[1], playing), "no media loaded: nothing is a burn-in")
    }

    @Test
    fun `the queue snapshot comes from the receiver and a partial message keeps the rest`() {
        val q = listOf(CastTrackItem(id = "a", title = "A"), CastTrackItem(id = "b", title = "B"))
        val st = mergeCastStatus(null, CastReceiverMessage(type = "status", queue = q, queueIndex = 1, repeat = "all"), playing, "status", 0)
        assertTrue(st.music); assertEquals(1, st.queueIndex); assertEquals("all", st.repeat)
        val folded = foldReceiverMessage(CastReceiverMessage(type = "status", queue = q, queueIndex = 1, receiverId = "r1"),
            CastReceiverMessage(type = "nextup", nextupSecs = 5))
        assertEquals(q, folded.queue); assertEquals("r1", folded.receiverId); assertEquals(5, folded.nextupSecs)
    }
}
