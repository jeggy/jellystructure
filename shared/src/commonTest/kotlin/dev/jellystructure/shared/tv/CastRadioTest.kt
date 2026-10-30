package dev.jellystructure.shared.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2660 (DEB-2628 FR-11) — what the Chromecast receiver decides for a station, and the wire it travels on. */
class CastRadioTest {
    private val json = RaviloWireJsonWithDefaults
    private val station = CastRadioItem(channelId = "ch-7", name = "Station One", logoUrl = "/img/one.png", programme = "Morning Show")

    private fun ticket(id: String = "live-1", kind: String = "audio", url: String = "/api/tv/livetv/radio/abc", type: String? = "audio/aac") = LiveTvStreamTicket(
        jellyfinBaseUrl = "https://jf.example.test", accessToken = "t", channelId = "ch-7", liveStreamId = id, hlsUrl = url,
        expiresAt = 0L, streamKind = kind, contentType = type, channelKind = "radio",
    )

    /** A fake server: records the calls; tunes never suspend, so a test drives them with an unconfined scope. */
    private class Fake(val tickets: MutableList<LiveTvStreamTicket>, var failTune: Boolean = false) : CastRadioApi {
        val tuned = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val beats = mutableListOf<LiveTvHeartbeatRequest>()
        override suspend fun tune(channelId: String, capabilities: ClientCapabilities): LiveTvStreamTicket {
            if (failTune) error("no tune")
            tuned += channelId
            return tickets.removeAt(0)
        }
        override suspend fun heartbeat(report: LiveTvHeartbeatRequest) { beats += report }
        override suspend fun stop(liveStreamId: String) { stopped += liveStreamId }
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private fun tune(api: Fake, heartbeatMs: Long = 3_600_000L) = CastRadioTune(scope, { api }, { ClientCapabilities() }, { "https://media.example.test" }, heartbeatMs)

    private fun eagerly(block: suspend () -> Unit) {
        var failure: Throwable? = null
        var done = false
        scope.launch { try { block() } catch (t: Throwable) { failure = t }; done = true }
        failure?.let { throw it }
        assertTrue(done, "the test body suspended; a fake waited on something")
    }

    // ── the wire ──

    @Test
    fun `a LOAD with a station carries radio and no tracks and an older LOAD reads as no station`() {
        val load = CastLoadData(serverUrl = "https://media.example.test", code = "C1", itemId = "ch-7", title = "Morning Show", radio = station)
        val text = json.encodeToString(CastLoadData.serializer(), load)
        assertTrue(""""channel_id":"ch-7"""" in text && """"logo_url":"/img/one.png"""" in text && """"programme":"Morning Show"""" in text, text)
        val back = json.decodeFromString(CastLoadData.serializer(), text)
        assertEquals(station, back.radio)
        assertTrue(back.tracks.isEmpty())
        val older = """{"server_url":"https://media.example.test","code":"C1","item_id":"i1","title":"A film"}"""
        assertNull(json.decodeFromString(CastLoadData.serializer(), older).radio)
    }

    @Test
    fun `a status without radio reads as not a station and a station's says so`() {
        assertNull(json.decodeFromString(CastReceiverMessage.serializer(), """{"type":"status","item_id":"i1"}""").radio)
        val said = castRadioStatus(station, "https://media.example.test/img/one.png", playing = true, receiverId = "r1", headless = true)
        val back = json.decodeFromString(CastReceiverMessage.serializer(), json.encodeToString(CastReceiverMessage.serializer(), said))
        assertEquals(true, back.radio)
        assertEquals("Morning Show", back.title)
        assertEquals("Station One", back.kicker)
        assertEquals("ch-7", back.itemId)
        assertTrue(back.queue == null && back.queueIndex == null, "a station has no queue")
        assertEquals(true, back.playing)
    }

    // ── what the speaker is told ──

    @Test
    fun `the title is the programme and the artist the station and the image the logo`() {
        val meta = castRadioMeta(station) { "https://media.example.test$it" }
        assertEquals(CastRadioMeta("Morning Show", "Station One", "https://media.example.test/img/one.png"), meta)
    }

    @Test
    fun `with no programme the title is the station's name`() {
        assertEquals("Station Two", castRadioMeta(CastRadioItem("ch-8", "Station Two")).title)
        assertEquals("Station Two", castRadioMeta(CastRadioItem("ch-8", "Station Two", programme = "  ")).title)
    }

    @Test
    fun `a station offers pause and volume and never seek or a queue command`() {
        assertEquals(listOf("PAUSE", "STREAM_VOLUME", "STREAM_MUTE"), CAST_RADIO_COMMANDS)
        assertTrue(CAST_RADIO_COMMANDS.none { it == "SEEK" || it.startsWith("QUEUE_") })
        assertTrue("SEEK" in CAST_MUSIC_COMMANDS && "QUEUE_NEXT" in CAST_MUSIC_COMMANDS, "a song after a station gets its commands back")
    }

    @Test
    fun `the relay path is resolved against the server and the ticket's type is the type loaded`() {
        val t = ticket()
        val url = castRadioUrl(t, "https://media.example.test/")
        assertEquals("https://media.example.test/api/tv/livetv/radio/abc", url)
        assertEquals("audio/aac", castRadioContentType(t, url))
        assertEquals("audio/mpeg", castRadioContentType(ticket(type = null), url))
        assertEquals("application/x-mpegURL", castRadioContentType(ticket(type = null, kind = "hls", url = "https://cdn.example.test/live.m3u8"), "https://cdn.example.test/live.m3u8"))
    }

    // ── the tune ──

    @Test
    fun `a station load tunes as this device and hands back the relay with the ticket's type`() = eagerly {
        val api = Fake(mutableListOf(ticket()))
        val t = tune(api)
        val stream = t.start(station)
        assertEquals(listOf("ch-7"), api.tuned)
        assertEquals(CastRadioStream("https://media.example.test/api/tv/livetv/radio/abc", "audio/aac", "live-1"), stream)
        assertTrue(t.open)
        assertEquals(station, t.radio)
    }

    @Test
    fun `a tune that fails opens nothing`() = eagerly {
        val api = Fake(mutableListOf(), failTune = true)
        val t = tune(api)
        assertNull(t.start(station))
        assertFalse(t.open)
        assertTrue(api.stopped.isEmpty())
    }

    @Test
    fun `a song load after a station stops the station's tune and stopping twice closes it once`() = eagerly {
        val api = Fake(mutableListOf(ticket()))
        val t = tune(api)
        t.start(station)
        t.stop()   // the receiver's song path
        t.stop()   // …and the idle that follows
        t.stopInBackground()
        assertEquals(listOf("live-1"), api.stopped)
        assertFalse(t.open)
        assertNull(t.radio)
    }

    @Test
    fun `a second station closes the first one's tune before it tunes`() = eagerly {
        val api = Fake(mutableListOf(ticket("live-1"), ticket("live-2")))
        val t = tune(api)
        t.start(station)
        val second = t.start(CastRadioItem("ch-8", "Station Two"))
        assertEquals(listOf("live-1"), api.stopped)
        assertEquals("live-2", second?.liveStreamId)
        assertEquals("ch-8", t.radio?.channelId)
    }

    @Test
    fun `a background stop never closes a station tuned straight after it`() {
        val api = Fake(mutableListOf(ticket("live-1"), ticket("live-2")))
        val t = tune(api)
        eagerly {
            t.start(station)
            t.stopInBackground()
            t.start(CastRadioItem("ch-8", "Station Two"))
        }
        // The background stop is queued behind the body on an unconfined scope; it ran by now, on the first stream only.
        assertEquals(listOf("live-1"), api.stopped)
        assertTrue(t.open)
        assertNotNull(t.radio)
    }

    // ── a finished station ──

    @Test
    fun `a finished station is tuned again three times in a minute and then it has failed`() {
        assertTrue(castRadioMayRetune(emptyList(), 100_000L))
        assertTrue(castRadioMayRetune(listOf(10_000L, 20_000L), 30_000L))
        assertFalse(castRadioMayRetune(listOf(10_000L, 20_000L, 30_000L), 40_000L))
        assertTrue(castRadioMayRetune(listOf(10_000L, 20_000L, 30_000L), 80_000L), "the first of the three is over a minute old")
    }
}
