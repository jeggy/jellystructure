package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.LiveFallbackAction
import dev.jellystructure.ravilo.ui.seams.LiveFallbackPolicy
import dev.jellystructure.ravilo.ui.seams.STREAM_KIND_AUDIO
import dev.jellystructure.ravilo.ui.seams.STREAM_KIND_OTHER
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvProgramInfo
import dev.jellystructure.shared.tv.LiveTvStreamTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2658 (DEB-2628 FR-6, FR-7, FR-8) — the radio decisions of TV mode. */
class RadioPlaybackTest {
    private fun channel(id: String, number: Int, kind: String = "tv", program: LiveTvProgramInfo? = null) = LiveTvChannel(
        channelId = id, name = "Name $id", number = number, order = number, shown = true, logoUrl = null, category = "",
        hasGuide = program != null, currentProgram = program, kind = kind,
    )

    private val news = LiveTvProgramInfo("Evening News", startMs = 1_000L, endMs = 2_000L)

    // One lineup: two channels, a station between them, a channel after it.
    private val lineup = listOf(channel("tv1", 1), channel("hr", 2, "radio", news), channel("tv2", 3), channel("hr2", 4, "radio"))

    @Test
    fun `zapping in one lineup opens the radio player for a station and the video player for a channel`() {
        val fromChannel = zapTarget(lineup, "tv1", +1)!!
        assertEquals("hr", fromChannel.channelId)
        assertEquals(LivePlayerLayout.RADIO, livePlayerLayout(fromChannel))

        val fromStation = zapTarget(lineup, "hr", +1)!!
        assertEquals("tv2", fromStation.channelId)
        assertEquals(LivePlayerLayout.VIDEO, livePlayerLayout(fromStation))

        // and backwards, and wrapping at both ends of the one list
        assertEquals(LivePlayerLayout.RADIO, livePlayerLayout(zapTarget(lineup, "tv2", -1)))
        assertEquals("hr2", zapTarget(lineup, "tv1", -1)!!.channelId)
        assertEquals("tv1", zapTarget(lineup, "hr2", +1)!!.channelId)
    }

    @Test
    fun `number entry runs over the same lineup`() {
        assertEquals(LivePlayerLayout.RADIO, livePlayerLayout(channelByNumber(lineup, 2)))
        assertEquals(LivePlayerLayout.VIDEO, livePlayerLayout(channelByNumber(lineup, 3)))
        assertNull(channelByNumber(lineup, 99))
    }

    @Test
    fun `a channel not yet known opens the video player and an unknown id does not zap`() {
        assertEquals(LivePlayerLayout.VIDEO, livePlayerLayout(null))
        assertNull(zapTarget(lineup, "gone", +1))
        assertNull(zapTarget(emptyList(), "tv1", +1))
    }

    @Test
    fun `a kind that is not radio is a channel`() {
        assertFalse(channel("x", 9, kind = "future").isRadio)
        assertTrue(channel("x", 9, kind = "radio").isRadio)
    }

    // ── the tile's text (FR-6) ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun tile(c: LiveTvChannel) = liveTileText(c, liveRadio = "Live radio", noProgram = "No program info") { "20:00–20:30" }

    @Test
    fun `a station with no guide shows its name once and Live radio takes the programme's place`() {
        val t = tile(lineup[3])
        assertEquals("Name hr2", t.title)
        assertEquals("Live radio", t.subtitle)
        assertNull(t.caption)
        assertFalse(t.showProgress)
        // the name is not drawn a second time at the programme's weight
        assertEquals(1, listOf(t.title, t.subtitle).count { it == "Name hr2" })
    }

    @Test
    fun `a station with a programme and a channel keep the programme the time range and the progress`() {
        val station = tile(lineup[1])
        assertEquals("Evening News", station.subtitle)
        assertEquals("20:00–20:30", station.caption)
        assertTrue(station.showProgress)
        assertEquals(tile(channel("tv9", 9, program = news)).copy(title = station.title), station)
    }

    @Test
    fun `a channel with no programme keeps No program info`() {
        val t = tile(lineup[0])
        assertEquals("No program info", t.subtitle)
        assertFalse(t.showProgress)
    }

    // ── the burn-in orbit (FR-8) ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the orbit holds a position for seven minutes and loops every twenty-eight`() {
        val min = 60_000L
        assertEquals(0, RadioBurnIn.stepAt(0))
        assertEquals(0, RadioBurnIn.stepAt(7 * min - 1))
        assertEquals(1, RadioBurnIn.stepAt(7 * min))
        assertEquals(2, RadioBurnIn.stepAt(14 * min))
        assertEquals(3, RadioBurnIn.stepAt(21 * min))
        assertEquals(0, RadioBurnIn.stepAt(28 * min))
        assertEquals(1, RadioBurnIn.stepAt(35 * min))
    }

    @Test
    fun `the orbit travels 56 by 36 px at 1920 by 1080 and scales with the screen`() {
        val at = (0 until 4).map { RadioBurnIn.offsetPx(it, 1920f, 1080f) }
        assertEquals(listOf(0 to 0, 28 to 18, 0 to 36, -28 to 18), at)
        assertEquals(56, at.maxOf { it.first } - at.minOf { it.first })
        assertEquals(36, at.maxOf { it.second } - at.minOf { it.second })
        // a 960 x 540 dp TV moves half as many dp, which is the same share of what it shows
        assertEquals(listOf(0 to 0, 14 to 9, 0 to 18, -14 to 9), (0 until 4).map { RadioBurnIn.offsetPx(it, 960f, 540f) })
    }

    @Test
    fun `the step glides for 600 ms and is a cut under reduced motion`() {
        assertEquals(600, RadioBurnIn.glideMs(reducedMotion = false))
        assertEquals(0, RadioBurnIn.glideMs(reducedMotion = true))
    }

    // ── the dim (FR-8) ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the layout dims to 40 percent after five minutes with no key`() {
        val min = 60_000L
        val dim = RadioIdleDim(startMs = 0L)
        assertFalse(dim.isDimmed(5 * min - 1))
        assertEquals(1f, dim.alpha(5 * min - 1))
        assertTrue(dim.isDimmed(5 * min))
        assertEquals(0.4f, dim.alpha(5 * min))
        assertEquals(0L, dim.untilDimMs(6 * min))
        assertEquals(1L, dim.untilDimMs(5 * min - 1))
    }

    @Test
    fun `any key restores the brightness and restarts the five minutes`() {
        val min = 60_000L
        val dim = RadioIdleDim(startMs = 0L)
        assertTrue(dim.isDimmed(6 * min))
        dim.onKey(6 * min)
        assertFalse(dim.isDimmed(6 * min))
        assertEquals(5 * min, dim.untilDimMs(6 * min))
        assertFalse(dim.isDimmed(11 * min - 1))
        assertTrue(dim.isDimmed(11 * min))
        // a key just before the deadline pushes it out by a whole five minutes
        val other = RadioIdleDim(startMs = 0L)
        other.onKey(5 * min - 1)
        assertFalse(other.isDimmed(9 * min))
    }

    // ── the ticket (FR-4, FR-8) ────────────────────────────────────────────────────────────────────────────────────────────

    private fun ticket(kind: String, url: String) = LiveTvStreamTicket(
        jellyfinBaseUrl = "https://jf.invalid", accessToken = "t", channelId = "hr", liveStreamId = "s", hlsUrl = url, expiresAt = 0L,
        streamKind = kind, contentType = if (kind == STREAM_KIND_AUDIO) "audio/mpeg" else null, channelKind = if (kind == STREAM_KIND_AUDIO) "radio" else "tv",
    )

    @Test
    fun `a relay path is resolved against this server and only for an audio ticket`() {
        assertEquals("https://jelly.invalid/api/tv/livetv/radio/abc", radioPlayUrl(ticket(STREAM_KIND_AUDIO, "/api/tv/livetv/radio/abc"), "https://jelly.invalid"))
        assertEquals("https://jelly.invalid/api/tv/livetv/radio/abc", radioPlayUrl(ticket(STREAM_KIND_AUDIO, "/api/tv/livetv/radio/abc"), "https://jelly.invalid/"))
        // any other ticket's URL goes to the player as it came
        assertEquals("/Videos/x/master.m3u8", radioPlayUrl(ticket(STREAM_KIND_OTHER, "/Videos/x/master.m3u8"), "https://jelly.invalid"))
        assertEquals("https://cdn.invalid/live.m3u8", radioPlayUrl(ticket(STREAM_KIND_AUDIO, "https://cdn.invalid/live.m3u8"), "https://jelly.invalid"))
    }

    @Test
    fun `a station is heard when the player is past buffering and its position has moved, a channel when it draws a frame`() {
        assertFalse(liveSignalStarted(radio = true, renderedFirstFrame = false, isBuffering = true, positionMs = 0L))
        assertFalse(liveSignalStarted(radio = true, renderedFirstFrame = false, isBuffering = false, positionMs = 0L))
        assertFalse(liveSignalStarted(radio = true, renderedFirstFrame = false, isBuffering = true, positionMs = 800L))
        assertTrue(liveSignalStarted(radio = true, renderedFirstFrame = false, isBuffering = false, positionMs = 800L))
        // audio draws no frame; a channel needs one
        assertFalse(liveSignalStarted(radio = false, renderedFirstFrame = false, isBuffering = false, positionMs = 800L))
        assertTrue(liveSignalStarted(radio = false, renderedFirstFrame = true, isBuffering = true, positionMs = 0L))
    }

    @Test
    fun `a relay that gives no audio re-tunes once through Jellyfin`() {
        val relay = LiveFallbackPolicy(STREAM_KIND_AUDIO, viaJellyfin = false)
        assertTrue(relay.timesFirstFrame)
        assertEquals(LiveFallbackAction.FALL_BACK, relay.onNoFirstFrame())
        assertEquals(LiveFallbackAction.FALL_BACK, LiveFallbackPolicy(STREAM_KIND_AUDIO, false).onFatal())
        // audio that did start is not re-tuned for a slow clock
        val heard = LiveFallbackPolicy(STREAM_KIND_AUDIO, false).also { it.onFirstFrame() }
        assertEquals(LiveFallbackAction.IGNORE, heard.onNoFirstFrame())
        // never from the fallback itself
        val viaJf = LiveFallbackPolicy("hls", viaJellyfin = true)
        assertFalse(viaJf.timesFirstFrame)
        assertEquals(LiveFallbackAction.SHOW_ERROR, viaJf.onFatal())
    }
}
