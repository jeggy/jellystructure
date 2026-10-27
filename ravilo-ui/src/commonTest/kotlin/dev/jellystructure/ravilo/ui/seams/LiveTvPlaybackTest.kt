package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.LiveTvLadderRung
import dev.jellystructure.shared.tv.LiveTvStreamTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2627 (FR-3/FR-4, client half) — which tickets are HLS, what a live load tells the player, and when a tune falls back. */
class LiveTvPlaybackTest {
    private fun ticket(kind: String, url: String, ladder: List<LiveTvLadderRung> = emptyList()) = LiveTvStreamTicket(
        jellyfinBaseUrl = "http://jf", accessToken = "t", channelId = "c", liveStreamId = "l",
        hlsUrl = url, expiresAt = 0L, streamKind = kind, ladder = ladder,
    )

    @Test fun `a ticket the backend calls hls or hls_ladder is hls whatever the url ends in`() {
        assertTrue(isHlsTicket(STREAM_KIND_HLS_LADDER, "https://x.example/feed.smil"))
        assertTrue(isHlsTicket(STREAM_KIND_HLS, "https://x.example/live"))
    }

    @Test fun `an old backend's ticket is hls when m3u8 is anywhere in the url`() {
        // A field the server leaves off the wire decodes as its default, "other": that is what an old backend looks like.
        assertTrue(isHlsTicket(STREAM_KIND_OTHER, "https://x.example/master.m3u8"))
        assertTrue(isHlsTicket(STREAM_KIND_OTHER, "https://x.example/smil:feed.smil?type=m3u8"))
        assertTrue(isHlsTicket(STREAM_KIND_OTHER, "https://x.example/a.m3u8?token=1"))
    }

    @Test fun `other without m3u8 is not hls`() {
        assertFalse(isHlsTicket(STREAM_KIND_OTHER, "/LiveTv/LiveStreamFiles/abc/stream.ts"))
        assertFalse(isHlsTicket(STREAM_KIND_OTHER, "https://x.example/feed.smil"))
    }

    @Test fun `a live load carries the ladder highest first only for a ladder ticket`() {
        val rungs = listOf(560_000L, 7_700_000L, 2_000_000L).map { LiveTvLadderRung(it) }
        val ladder = LiveLoad.of(ticket(STREAM_KIND_HLS_LADDER, "https://x/f.smil", rungs), linkEstimateBps = 9_000_000L)
        assertEquals(listOf(7_700_000L, 2_000_000L, 560_000L), ladder.ladderBps)
        assertEquals(9_000_000L, ladder.linkEstimateBps)
        assertTrue(ladder.hls)
        val single = LiveLoad.of(ticket(STREAM_KIND_HLS, "https://x/f.smil", rungs), linkEstimateBps = null)
        assertEquals(emptyList(), single.ladderBps)
        assertTrue(single.hls)
        assertFalse(LiveLoad.of(ticket(STREAM_KIND_OTHER, "/stream.ts"), null).hls)
    }

    @Test fun `a live tune clears the film ladder state and a film's ticket puts the live mark back`() {
        PlayerLadderHints.oursEncoder = true
        PlayerLadderHints.adaptive = true
        PlayerLadderHints.startVariantBps = 5_000_000L
        PlayerLadderHints.directStreamBps = 40_000_000L
        PlayerLadderHints.climbBlockedUntilMs = Long.MAX_VALUE
        PlayerLadderHints.restreamStepping = true
        PlayerLadderHints.beginLive()
        assertTrue(PlayerLadderHints.live)
        assertFalse(PlayerLadderHints.oursEncoder)
        assertFalse(PlayerLadderHints.adaptive)
        assertEquals(null, PlayerLadderHints.startVariantBps)
        assertEquals(0L, PlayerLadderHints.directStreamBps)
        assertFalse(PlayerLadderHints.climbBlocked(1L))
        assertFalse(PlayerLadderHints.restreamStepping)
    }

    // ── FR-4 ──

    @Test fun `no first frame within the limit falls back once`() {
        val p = LiveFallbackPolicy(STREAM_KIND_HLS_LADDER, viaJellyfin = false)
        assertTrue(p.timesFirstFrame)
        assertEquals(LiveFallbackAction.FALL_BACK, p.onNoFirstFrame())
    }

    @Test fun `a fatal error before the first frame falls back`() {
        val p = LiveFallbackPolicy(STREAM_KIND_HLS, viaJellyfin = false)
        assertEquals(LiveFallbackAction.FALL_BACK, p.onFatal())
    }

    @Test fun `a fatal error after the first frame retries the source once and then falls back`() {
        val p = LiveFallbackPolicy(STREAM_KIND_HLS_LADDER, viaJellyfin = false)
        p.onFirstFrame()
        assertEquals(LiveFallbackAction.RETRY_SOURCE, p.onFatal())
        // the retry draws a frame and fails again
        p.onFirstFrame()
        assertEquals(LiveFallbackAction.FALL_BACK, p.onFatal())
    }

    @Test fun `a retry that fails before its first frame falls back at once`() {
        val p = LiveFallbackPolicy(STREAM_KIND_HLS_LADDER, viaJellyfin = false)
        p.onFirstFrame()
        assertEquals(LiveFallbackAction.RETRY_SOURCE, p.onFatal())
        assertEquals(LiveFallbackAction.FALL_BACK, p.onFatal())
    }

    @Test fun `a tune that has drawn a frame is not timed out`() {
        val p = LiveFallbackPolicy(STREAM_KIND_HLS_LADDER, viaJellyfin = false)
        p.onFirstFrame()
        assertEquals(LiveFallbackAction.IGNORE, p.onNoFirstFrame())
    }

    @Test fun `the fallback itself never falls back and is not timed`() {
        val p = LiveFallbackPolicy(STREAM_KIND_HLS, viaJellyfin = true)
        assertFalse(p.timesFirstFrame)
        assertEquals(LiveFallbackAction.IGNORE, p.onNoFirstFrame())
        assertEquals(LiveFallbackAction.SHOW_ERROR, p.onFatal())
    }

    @Test fun `a channel classified other never falls back`() {
        val p = LiveFallbackPolicy(STREAM_KIND_OTHER, viaJellyfin = false)
        assertFalse(p.timesFirstFrame)
        assertEquals(LiveFallbackAction.IGNORE, p.onNoFirstFrame())
        assertEquals(LiveFallbackAction.SHOW_ERROR, p.onFatal())
    }
}
