package dev.jellystructure.ravilo.ui.remote

import dev.jellystructure.ravilo.ui.music.castCard
import dev.jellystructure.ravilo.ui.music.castHandBack
import dev.jellystructure.ravilo.ui.music.castMusicLive
import dev.jellystructure.ravilo.ui.music.castRadioLive
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastMediaSnapshot
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.ravilo.ui.seams.castLoadMedia
import dev.jellystructure.ravilo.ui.seams.castQueueGap
import dev.jellystructure.ravilo.ui.seams.mergeCastStatus
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastRadioItem
import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.CastTrackItem
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2660 (DEB-2628 FR-11) — a station on a speaker, as the phone sees it: the remote's `radio` kind and shape, what the receiver's
 * word does to the status, and the LOAD that carries the station.
 */
class RadioOnSpeakerTest {
    private val station = CastRadioItem(channelId = "ch-7", name = "Station One", logoUrl = "/img/one.png", programme = "Morning Show")

    private val songQueue = listOf(CastTrackItem("t1", "Song One", artist = "Artist"), CastTrackItem("t2", "Song Two", artist = "Artist"))
    private val song = CastRemoteStatus(itemId = "t1", title = "Song One", loaded = true, playing = true, music = true, queue = songQueue, queueIndex = 0, queueRev = 3, durationMs = 180_000L)

    private fun stationSaid(title: String = "Morning Show") =
        CastReceiverMessage(type = "status", itemId = "ch-7", title = title, kicker = "Station One", artUrl = "https://media.example.test/img/one.png", playing = true, radio = true)

    private fun merged(prev: CastRemoteStatus?, said: CastReceiverMessage) =
        mergeCastStatus(prev, said, CastMediaSnapshot(playerState = "PLAYING"), "status", nowMs = 0L)

    // ── the remote's kind ──

    @Test fun `a live radio item is a radio remote and never a film`() {
        assertEquals("radio", remoteKindOf(null, radio = true))
        assertEquals("radio", remoteKindOf("film", radio = true))
        assertEquals("radio", remoteKindOf(null, episodeLike = true, radio = true), "a station reports a kicker, which reads as an episode's")
        assertEquals("radio", remoteKindOf("radio"))
        assertEquals("film", remoteKindOf(null))
        assertEquals("music", remoteKindOf("music"))
    }

    @Test fun `the link remote for a station is the radio shape with its volume block and no tracks`() {
        val status = merged(null, stationSaid())
        var rooms = 0
        val remote = linkRemote(NoActions, status, false, "Stue", null, null, RemoteExtras(), linkVolume = { }, radioRooms = { rooms++ })
        val m = remote.model
        assertEquals("radio", m.kind)
        assertTrue(m.radioShape)
        assertTrue(m.musicShape, "the station is drawn under the programme, the logo kept whole")
        assertFalse(m.offersTracks())
        assertNotNull(remote.linkVolume, "the group's level")
        assertNotNull(remote.sessionVolume, "the rooms and Add a speaker")
        assertNull(m.view, "a station has no session")
    }

    @Test fun `a lost link greys the radio shape's volume block like any other`() {
        val remote = linkRemote(NoActions, merged(null, stationSaid()), true, "Stue", null, null, RemoteExtras(), linkVolume = { }, radioRooms = { })
        assertNull(remote.sessionVolume)
    }

    // ── what the receiver's word does to the status ──

    @Test fun `a station's status says radio with the programme and the station and no queue`() {
        val st = merged(null, stationSaid())
        assertTrue(st.radio)
        assertFalse(st.music)
        assertEquals("Morning Show", st.title)
        assertEquals("Station One", st.kicker)
        assertTrue(st.queue.isEmpty())
        assertEquals(0L, st.durationMs)
        assertTrue(st.playing)
    }

    @Test fun `a station over a song clears the queue and its revision`() {
        val st = merged(song, stationSaid())
        assertTrue(st.queue.isEmpty() && st.queueIndex == -1 && st.queueRev == null && !st.music)
        assertEquals(0L, st.durationMs, "the song's length is not the station's")
    }

    @Test fun `a song after a station asks for its queue afresh and the status is no longer radio`() {
        val onStation = merged(song, stationSaid())
        val slim = CastReceiverMessage(type = "status", itemId = "t1", title = "Song One", queueRev = 3, queueIndex = 0, queueSize = 2)
        assertTrue(castQueueGap(onStation, slim), "revision 3 is not held: it is asked for")
        val back = merged(onStation, slim)
        assertFalse(back.radio)
    }

    @Test fun `a receiver that says nothing of radio is never a station`() {
        val st = merged(null, CastReceiverMessage(type = "status", itemId = "i1", title = "A film"))
        assertFalse(st.radio)
    }

    // ── the phone's reading of it ──

    @Test fun `the programme is the title and the station the name and with no guide the title is the name again`() {
        val live = castRadioLive(merged(null, stationSaid()))
        assertEquals("Morning Show", live.title)
        assertEquals("Station One", live.station.name)
        assertEquals("Station One", live.subtitle("Live radio"))
        val noGuide = castRadioLive(merged(null, stationSaid(title = "Station One")))
        assertEquals("Station One", noGuide.title)
        assertEquals("Live radio", noGuide.subtitle("Live radio"))
    }

    @Test fun `a linked speaker with a station is live music and a station is never handed back to the phone`() {
        val st = merged(null, stationSaid())
        assertTrue(castMusicLive(CastLinkState.CONNECTED, st))
        assertFalse(castMusicLive(CastLinkState.NONE, st))
        assertNull(castHandBack(st, 5_000L, endedByApp = false))
    }

    @Test fun `the media card for a station is live and has no place`() {
        val card = castCard(CastLinkState.CONNECTED, merged(null, stationSaid()), 0.4, null)
        assertNotNull(card)
        assertTrue(card.live)
        assertEquals("Morning Show", card.title)
        assertEquals("Station One", card.subtitle)
        assertEquals(0L, card.durationMs)
        assertFalse(card.hasNext)
    }

    // ── the LOAD ──

    @Test fun `the LOAD carries the station and no tracks and its media is live`() {
        val load = CastLoadData(
            serverUrl = "https://media.example.test", code = "C1", itemId = "ch-7", title = "Morning Show", kicker = "Station One",
            artUrl = "https://media.example.test/img/one.png", radio = station,
        )
        val media = castLoadMedia(load)
        assertEquals("LIVE", media["streamType"]!!.jsonPrimitive.content)
        val md = media["metadata"]!!.jsonObject
        assertEquals("3", md["metadataType"]!!.jsonPrimitive.content)
        assertEquals("Morning Show", md["title"]!!.jsonPrimitive.content)
        assertEquals("Station One", md["artist"]!!.jsonPrimitive.content)
        assertTrue(load.tracks.isEmpty())
        val custom = media["customData"]!!.jsonObject
        assertEquals("ch-7", custom["radio"]!!.jsonObject["channel_id"]!!.jsonPrimitive.content)
    }

    @Test fun `a song's LOAD is as before`() {
        val media = castLoadMedia(CastLoadData(serverUrl = "https://media.example.test", code = "C1", itemId = "t1", title = "Song One", tracks = songQueue, currentIndex = 0))
        assertEquals("BUFFERED", media["streamType"]!!.jsonPrimitive.content)
    }

    private object NoActions : RemoteActions {
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun next() {}
        override fun stop() {}
        override fun selectAudio(trackId: Long?) {}
        override fun selectSubtitle(trackId: Long?) {}
        override fun nextUpPlay() {}
        override fun nextUpCancel() {}
        override fun subtitleSize(size: Char) {}
        override fun retry() {}
    }
}
