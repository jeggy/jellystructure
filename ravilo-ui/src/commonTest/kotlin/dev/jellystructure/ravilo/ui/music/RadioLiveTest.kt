package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.seams.STREAM_KIND_AUDIO
import dev.jellystructure.ravilo.ui.seams.STREAM_KIND_HLS
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvHeartbeatRequest
import dev.jellystructure.shared.tv.LiveTvProgramInfo
import dev.jellystructure.shared.tv.LiveTvStreamTicket
import dev.jellystructure.shared.tv.MusicRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2659 (DEB-2628 FR-9, FR-10) — a station as a live item in the music engine: what the screens read of it, and the tune's
 * control plane (the heartbeat, the programme's turnover, the one fallback, the stop) on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioLiveTest {
    private val news = LiveTvProgramInfo("Evening News", startMs = 1_000_000L, endMs = 2_000_000L)
    private val sport = LiveTvProgramInfo("Sports Desk", startMs = 2_000_000L, endMs = 3_000_000L)

    private fun station(id: String = "hr", program: LiveTvProgramInfo? = news, hasGuide: Boolean = program != null) = LiveTvChannel(
        channelId = id, name = "Harbour Radio", number = 7, order = 7, shown = true, logoUrl = "/logo/$id", category = "",
        hasGuide = hasGuide, currentProgram = program, kind = "radio",
    )

    private fun ticket(id: String, kind: String = STREAM_KIND_AUDIO, url: String = "/api/tv/livetv/radio/$id") = LiveTvStreamTicket(
        jellyfinBaseUrl = "http://jf", accessToken = "t", channelId = "hr", liveStreamId = id, hlsUrl = url, expiresAt = 0L,
        streamKind = kind, contentType = if (kind == STREAM_KIND_AUDIO) "audio/mpeg" else null, channelKind = "radio",
    )

    private class FakeApi(var stations: List<LiveTvChannel> = emptyList()) : RadioApi {
        val tunes = mutableListOf<String?>()          // the `via` of each tune
        val heartbeats = mutableListOf<LiveTvHeartbeatRequest>()
        val stops = mutableListOf<String>()
        var failTune = false
        private var n = 0
        override suspend fun tune(channelId: String, capabilities: ClientCapabilities, via: String?): LiveTvStreamTicket {
            tunes += via
            if (failTune) error("no tune")
            n++
            return if (via == "jellyfin") LiveTvStreamTicket("http://jf", "t", channelId, "s$n", "http://jf/hls/$n.m3u8", 0L, STREAM_KIND_HLS, channelKind = "radio")
            else LiveTvStreamTicket("http://jf", "t", channelId, "s$n", "/api/tv/livetv/radio/r$n", 0L, STREAM_KIND_AUDIO, contentType = "audio/mpeg", channelKind = "radio")
        }
        override suspend fun heartbeat(report: LiveTvHeartbeatRequest) { heartbeats += report }
        override suspend fun stop(liveStreamId: String) { stops += liveStreamId }
        override suspend fun stations(): List<LiveTvChannel> = stations
    }

    private class Heard : RadioListener {
        val loads = mutableListOf<RadioStream>()
        val changes = mutableListOf<RadioLive>()
        var failures = 0
        override fun onLoad(stream: RadioStream) { loads += stream }
        override fun onChanged(radio: RadioLive) { changes += radio }
        override fun onFailed() { failures++ }
    }

    private fun TestScope.tuner(api: FakeApi, heard: Heard, baseMs: Long = 1_500_000L) = RadioTuner(
        scope = backgroundScope, api = { api }, capabilities = { ClientCapabilities() }, baseUrl = { "https://jelly.example" },
        listener = heard, nowMs = { baseMs + currentTime },
    )

    // ── what the screens read of a station ──

    @Test
    fun `the title is the programme, the artist line the station and the cover the logo`() {
        val r = RadioLive(station())
        assertEquals("Evening News", r.title)
        assertEquals("Harbour Radio", r.subtitle("Live radio"))
        assertEquals("/logo/hr", r.coverUrl)
    }

    @Test
    fun `with no guide the title is the name and the second line reads Live radio`() {
        val r = RadioLive(station(program = null))
        assertEquals("Harbour Radio", r.title)
        assertEquals("Live radio", r.subtitle("Live radio"))
    }

    // ── the player state: left out, not greyed ──

    @Test
    fun `a station has no next and no queue and no up next`() {
        val st = MusicPlayerState(radio = RadioLive(station()), playing = true)
        assertTrue(st.active)
        assertFalse(st.hasNext)
        assertTrue(st.queue.isEmpty())
        assertTrue(st.upNext.isEmpty())
        assertNull(st.current)
        assertEquals(0L, st.durationMs)
    }

    @Test
    fun `LIVE shows only while the station plays - stopped or failed shows none`() {
        val r = RadioLive(station())
        assertTrue(MusicPlayerState(radio = r, playing = true).showsLive)
        assertTrue(MusicPlayerState(radio = r, playing = true, buffering = true).showsLive)
        assertFalse(MusicPlayerState(radio = r, playing = false).showsLive, "stopped")
        assertFalse(MusicPlayerState(radio = r, playing = true, failed = true).showsLive, "failed")
        assertFalse(MusicPlayerState(playing = true).showsLive, "a song is not live")
    }

    // ── the Radio row ──

    @Test
    fun `ListenRow draws a radio row from its stations and a song row from its tracks`() {
        val radio = MusicRow("radio", "Radio", stations = listOf(station()))
        assertEquals(ListenRowKind.RADIO, listenRowKind(radio))
        assertEquals(ListenRowKind.ALBUMS, listenRowKind(MusicRow("recent")))
        assertEquals(ListenRowKind.TRACKS, listenRowKind(MusicRow("played", tracks = listOf(dev.jellystructure.shared.tv.MusicTrackItem("a", "A")))))
        assertTrue(listenRowShown(radio))
        // An empty Radio row is nothing, not an empty heading: an installed phone drew one, this one does not.
        assertFalse(listenRowShown(MusicRow("radio", "Radio")))
        assertTrue(listenRowShown(MusicRow("recent")))
    }

    @Test
    fun `a tile says the programme now, or Live radio with no guide`() {
        assertEquals("Evening News", stationTileLine(station(), "Live radio"))
        assertEquals("Live radio", stationTileLine(station(program = null), "Live radio"))
    }

    // ── the turnover's timing ──

    @Test
    fun `the engine re-reads a hair after the programme ends, a minute later when a guide has a gap, and never with no guide`() {
        val r = RadioLive(station())
        assertEquals(500_000L + RadioTuner.TURNOVER_SLACK_MS, radioRefreshDelayMs(r, nowMs = 1_500_000L))
        assertEquals(RadioTuner.MIN_REFRESH_MS, radioRefreshDelayMs(r, nowMs = 1_999_990L), "a stale answer never spins the loop")
        assertEquals(RadioTuner.GUIDE_GAP_REFRESH_MS, radioRefreshDelayMs(r, nowMs = 2_500_000L), "the programme is over and the guide has no next")
        assertNull(radioRefreshDelayMs(RadioLive(station(program = null)), nowMs = 0L))
    }

    // ── the tune ──

    @Test
    fun `starting a station tunes it and hands the relay stream, resolved against the server, to the player`() = runTest {
        val api = FakeApi(); val heard = Heard(); val t = tuner(api, heard)
        t.start(station())
        runCurrent()
        assertEquals(listOf<String?>(null), api.tunes)
        assertEquals(RadioStream("https://jelly.example/api/tv/livetv/radio/r1", "audio/mpeg", hls = false), heard.loads.single())
        assertTrue(t.listening && t.loading)
        t.heard()
        assertFalse(t.loading)
        assertTrue(t.listening)
    }

    @Test
    fun `the engine heartbeats every 20 s with the stream and channel, and stops sends the stop`() = runTest {
        val api = FakeApi(); val t = tuner(api, Heard())
        t.start(station())
        runCurrent()
        t.heard()   // audio is playing, so the 15 s fallback clock is out of it
        assertTrue(api.heartbeats.isEmpty())
        advanceTimeBy(20_001); runCurrent()
        assertEquals(1, api.heartbeats.size)
        assertEquals(LiveTvHeartbeatRequest(channelId = "hr", liveStreamId = "s1", delivery = "source"), api.heartbeats.single())
        advanceTimeBy(40_000); runCurrent()
        assertEquals(3, api.heartbeats.size)
        t.stop()
        runCurrent()
        assertEquals(listOf("s1"), api.stops)
        assertNull(t.radio)
        assertFalse(t.listening)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, api.heartbeats.size, "a stopped station sends no more heartbeats")
    }

    @Test
    fun `pause stops the tune and keeps the station, and play re-tunes at the live edge`() = runTest {
        val api = FakeApi(); val heard = Heard(); val t = tuner(api, heard)
        t.start(station()); runCurrent(); t.heard()
        t.endTune(); runCurrent()
        assertEquals(listOf("s1"), api.stops)
        assertFalse(t.listening)
        assertEquals("hr", t.radio?.station?.channelId, "the station stays on the bar")
        t.retune(); runCurrent()
        assertEquals(2, api.tunes.size)
        assertEquals(2, heard.loads.size)
        assertTrue(t.listening)
    }

    @Test
    fun `a new station closes the previous tune`() = runTest {
        val api = FakeApi(); val t = tuner(api, Heard())
        t.start(station("hr")); runCurrent()
        t.start(station("hr2")); runCurrent()
        assertEquals(listOf("s1"), api.stops)
        assertEquals("hr2", t.radio?.station?.channelId)
    }

    @Test
    fun `the title turns over at the programmes end`() = runTest {
        val api = FakeApi(stations = listOf(station(program = sport)))
        val heard = Heard(); val t = tuner(api, heard)   // now = 1 500 000, the news ends at 2 000 000
        t.start(station()); runCurrent()
        assertEquals("Evening News", t.radio?.title)
        advanceTimeBy(499_000); runCurrent()
        assertTrue(heard.changes.isEmpty(), "not before the programme ends")
        advanceTimeBy(2_000); runCurrent()
        assertEquals("Sports Desk", heard.changes.single().title)
        assertEquals("Sports Desk", t.radio?.title)
    }

    @Test
    fun `start past a kept programmes end drops it and re-reads the station at once`() = runTest {
        val api = FakeApi(stations = listOf(station(program = sport)))
        val heard = Heard(); val t = tuner(api, heard, baseMs = 1_500_000L)
        t.start(station()); runCurrent()
        t.endTune(); runCurrent()
        advanceTimeBy(600_000)   // paused across the news' end (2 000 000); now = 2 100 000
        t.start(station())
        assertEquals("Harbour Radio", t.radio?.title, "the ended programme is not brought back")
        runCurrent()
        assertEquals("Sports Desk", heard.changes.last().title, "the re-read answers at once, not after 60 s")
        assertEquals("Sports Desk", t.radio?.title)
    }

    @Test
    fun `retune past a kept programmes end drops it and re-reads the station at once`() = runTest {
        val api = FakeApi(stations = listOf(station(program = sport)))
        val heard = Heard(); val t = tuner(api, heard, baseMs = 1_500_000L)
        t.start(station()); runCurrent(); t.heard()
        t.endTune(); runCurrent()
        advanceTimeBy(600_000); runCurrent()
        val before = heard.changes.size
        t.retune()
        assertEquals("Harbour Radio", t.radio?.title, "dropped before the tune answers")
        runCurrent()
        assertEquals(before + 1, heard.changes.size)
        assertEquals("Sports Desk", heard.changes.last().title)
    }

    @Test
    fun `a retune within the programme keeps it and does not re-read early`() = runTest {
        val api = FakeApi(stations = listOf(station(program = sport)))
        val heard = Heard(); val t = tuner(api, heard)
        t.start(station()); runCurrent(); t.heard()
        t.endTune(); runCurrent()
        advanceTimeBy(60_000); t.retune(); runCurrent()
        assertEquals("Evening News", t.radio?.title)
        assertTrue(heard.changes.isEmpty())
    }

    @Test
    fun `a dropped programme on a station with no guide still gets its one read`() = runTest {
        val api = FakeApi(stations = listOf(station(program = sport, hasGuide = false)))
        val heard = Heard(); val t = tuner(api, heard, baseMs = 2_100_000L)
        t.start(station(hasGuide = false)); runCurrent()   // the snapshot's programme ended at 2 000 000
        assertEquals("Sports Desk", t.radio?.title, "read once even though radioRefreshDelayMs is null for this station")
        assertEquals(1, heard.changes.size)
    }

    @Test
    fun `a gap keeps the engine re-reading and the title turns over when the gap ends`() = runTest {
        val api = FakeApi(stations = listOf(station(program = null, hasGuide = true)))
        val heard = Heard(); val t = tuner(api, heard, baseMs = 2_100_000L)
        t.start(station()); runCurrent(); t.heard()   // the news ended: dropped, read at once, the gap answers with no programme
        assertEquals("Harbour Radio", t.radio?.title)
        val afterFirst = heard.changes.size
        api.stations = listOf(station(program = sport))
        advanceTimeBy(59_000); runCurrent()
        assertEquals(afterFirst, heard.changes.size, "the gap is re-read every 60 s, not sooner")
        advanceTimeBy(2_000); runCurrent()
        assertEquals("Sports Desk", heard.changes.last().title)
    }

    @Test
    fun `a station with no guide is never re-read`() = runTest {
        val api = FakeApi(); val heard = Heard(); val t = tuner(api, heard)
        t.start(station(program = null)); runCurrent()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertTrue(heard.changes.isEmpty())
        assertEquals("Harbour Radio", t.radio?.title)
    }

    @Test
    fun `no audio within 15 s re-tunes once through Jellyfin and never again`() = runTest {
        val api = FakeApi(); val heard = Heard(); val t = tuner(api, heard)
        t.start(station()); runCurrent()
        advanceTimeBy(14_000); runCurrent()
        assertEquals(1, api.tunes.size)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf<String?>(null, "jellyfin"), api.tunes)
        assertEquals(listOf("s1"), api.stops, "the relay's tune is closed")
        assertEquals(RadioStream("http://jf/hls/2.m3u8", null, hls = true), heard.loads.last())
        // The fallback is not timed: a second silence is not a second re-tune.
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, api.tunes.size)
        assertEquals(0, heard.failures)
    }

    @Test
    fun `audio before 15 s cancels the fallback`() = runTest {
        val api = FakeApi(); val t = tuner(api, Heard())
        t.start(station()); runCurrent()
        advanceTimeBy(5_000); t.heard()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, api.tunes.size)
    }

    @Test
    fun `a failed fallback is the couldn't play state and closes the tune`() = runTest {
        val api = FakeApi(); val heard = Heard(); val t = tuner(api, heard)
        t.start(station()); runCurrent()
        t.onPlayerError(); runCurrent()          // before any audio: falls back
        assertEquals(listOf<String?>(null, "jellyfin"), api.tunes)
        t.onPlayerError(); runCurrent()          // the fallback itself failing: today's error
        assertEquals(1, heard.failures)
        assertFalse(t.listening)
        assertEquals("hr", t.radio?.station?.channelId, "the failed station stays, so Try again can tune it")
    }

    @Test
    fun `a tune that cannot be made fails at once`() = runTest {
        val api = FakeApi().also { it.failTune = true }; val heard = Heard(); val t = tuner(api, heard)
        t.start(station()); runCurrent()
        assertEquals(1, heard.failures)
        assertTrue(heard.loads.isEmpty())
        assertFalse(t.listening)
    }

    @Test
    fun `a song starting stops the station - its tune is closed and no heartbeat follows`() = runTest {
        // The engines call stop() as the first step of playQueue / playBook / playNext / addToQueue / loadPaused.
        val api = FakeApi(); val t = tuner(api, Heard())
        t.start(station()); runCurrent(); t.heard()
        advanceTimeBy(25_000); runCurrent()
        t.stop(); runCurrent()
        assertEquals(listOf("s1"), api.stops)
        assertNull(t.radio)
        val beats = api.heartbeats.size
        advanceTimeBy(120_000); runCurrent()
        assertEquals(beats, api.heartbeats.size)
    }
}
