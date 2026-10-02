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

    @Test
    fun `a song's length comes from the queue when the device reports none`() {
        // A speaker plays a FLAC and reports no duration at all: the bar read 2:17 of 0:00.
        val queue = listOf(CastTrackItem(id = "a", title = "One", durationMs = 217_000), CastTrackItem(id = "b", title = "Two"))
        val noLength = CastMediaSnapshot(playerState = "PLAYING", positionMs = 137_000)
        val first = mergeCastStatus(null, CastReceiverMessage(type = "status", itemId = "a", queue = queue, queueIndex = 0), noLength, "status", 0)
        assertEquals(217_000, first.durationMs)
        // The device's own word wins when it has one.
        assertEquals(216_500, mergeCastStatus(first, null, noLength.copy(durationMs = 216_500), null, 0).durationMs)
        // The next song has no length anywhere: nothing is shown, not the last song's.
        val second = mergeCastStatus(first, CastReceiverMessage(type = "status", itemId = "b", queue = queue, queueIndex = 1), noLength.copy(positionMs = 1_000), "status", 0)
        assertEquals(0, second.durationMs)
        // A film's report without a length keeps the one it had.
        assertEquals(5_400_000, mergeCastStatus(mergeCastStatus(null, null, playing, null, 0), null, CastMediaSnapshot(playerState = "PAUSED", positionMs = 5), null, 0).durationMs)
    }

    // ── R356 ──

    private val q3 = listOf(CastTrackItem(id = "a", title = "A"), CastTrackItem(id = "b", title = "B"), CastTrackItem(id = "c", title = "C"))

    @Test
    fun `a status without the queue keeps the copy held, and its revision`() {
        val full = mergeCastStatus(null, CastReceiverMessage(type = "status", itemId = "a", queue = q3, queueIndex = 0, queueRev = 4, queueSize = 3), playing, "status", 0)
        assertEquals(4, full.queueRev); assertEquals(q3, full.queue)
        val slim = mergeCastStatus(full, CastReceiverMessage(type = "status", itemId = "b", queueIndex = 1, queueRev = 4, queueSize = 3), playing, "status", 1)
        assertEquals(q3, slim.queue); assertEquals(1, slim.queueIndex); assertEquals(4, slim.queueRev); assertTrue(slim.music)
        assertFalse(castQueueGap(full, CastReceiverMessage(type = "status", queueIndex = 1, queueRev = 4)))
    }

    @Test
    fun `a newer revision without the queue is a gap, and the named song is found in the queue held`() {
        val full = mergeCastStatus(null, CastReceiverMessage(type = "status", itemId = "a", queue = q3, queueIndex = 0, queueRev = 4), playing, "status", 0)
        // The receiver moved "c" to the front (rev 5) and plays it; this sender missed the full status.
        val said = CastReceiverMessage(type = "status", itemId = "c", queueIndex = 0, queueRev = 5)
        assertTrue(castQueueGap(full, said))
        val st = mergeCastStatus(full, said, playing, "status", 1)
        assertEquals(2, st.queueIndex, "index 0 of the new queue is not 'c' in the queue held"); assertEquals(4, st.queueRev)
        // `get_queue` answers: the new queue and its revision.
        val healed = mergeCastStatus(st, CastReceiverMessage(type = "status", itemId = "c", queue = listOf(q3[2], q3[0], q3[1]), queueIndex = 0, queueRev = 5), playing, "status", 2)
        assertEquals(0, healed.queueIndex); assertEquals(5, healed.queueRev); assertFalse(castQueueGap(healed, said))
    }

    @Test
    fun `a receiver older than R356 sends the queue every time and is never a gap`() {
        val old = CastReceiverMessage(type = "status", itemId = "b", queue = q3, queueIndex = 1)
        assertFalse(castQueueGap(null, old))
        val st = mergeCastStatus(null, old, playing, "status", 0)
        assertNull(st.queueRev); assertEquals(1, st.queueIndex)
    }

    @Test
    fun `no media status keeps the last known song, place and state`() {
        val live = mergeCastStatus(null, CastReceiverMessage(type = "status", itemId = "b", queue = q3, queueIndex = 1), playing, "status", 0)
        assertTrue(live.playing)
        // The SDK lost its media status (a frozen app, a rejoin): not "paused at 0:00".
        val lost = mergeCastStatus(live, null, CastMediaSnapshot(), null, 1)
        assertTrue(lost.playing); assertTrue(lost.loaded); assertFalse(lost.buffering)
        assertEquals(61_000, lost.positionMs); assertEquals(5_400_000, lost.durationMs); assertEquals(1, lost.queueIndex)
        // A real idle report still ends it.
        assertFalse(mergeCastStatus(lost, null, CastMediaSnapshot(playerState = "IDLE"), null, 2).playing)
    }
}
