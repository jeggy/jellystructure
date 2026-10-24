package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2205 (FR-2, FR-3, FR-4) — every new field is optional: an installed client and an installed server keep working in both directions. */
class DecodeHealthWireTest {
    @Test fun `an installed client does not report decode health`() {
        val caps = RaviloWireJson.decodeFromString(ClientCapabilities.serializer(), """{"max_hevc_bitrate":60000000,"max_video_bitrate":60000000}""")
        assertFalse(caps.reportsDecodeHealth)
        assertEquals(60_000_000, caps.maxVideoBitrate)
    }

    @Test fun `a client that reports decode health says so under its snake case name`() {
        val json = RaviloWireJson.encodeToString(ClientCapabilities.serializer(), ClientCapabilities(reportsDecodeHealth = true))
        assertTrue("\"reports_decode_health\":true" in json, json)
        assertTrue(RaviloWireJson.decodeFromString(ClientCapabilities.serializer(), json).reportsDecodeHealth)
    }

    @Test fun `a report from an older app has no rendered frames and names no failure`() {
        val r = RaviloWireJson.decodeFromString(PlaybackQoeReport.serializer(), """{"item_id":"i","dropped_frames":4}""")
        assertEquals(0L, r.renderedFrames)
        assertNull(r.decodeFailedCodec); assertNull(r.decodeFailedBps); assertNull(r.stalledBps)
    }

    @Test fun `the decode and hand-over fields round trip under their snake case names`() {
        val json = RaviloWireJson.encodeToString(PlaybackQoeReport.serializer(),
            PlaybackQoeReport(itemId = "i", droppedFrames = 4, renderedFrames = 86_400, decodeFailedCodec = "hevc", decodeFailedBps = 69_600_000, stalledBps = 71_000_000))
        for (k in listOf("\"rendered_frames\":86400", "\"decode_failed_codec\":\"hevc\"", "\"decode_failed_bps\":69600000", "\"stalled_bps\":71000000")) assertTrue(k in json, "$k in $json")
        val back = RaviloWireJson.decodeFromString(PlaybackQoeReport.serializer(), json)
        assertEquals("hevc", back.decodeFailedCodec); assertEquals(71_000_000L, back.stalledBps); assertEquals(86_400L, back.renderedFrames)
    }

    @Test fun `a restream names a hand-over only when the player asks for one`() {
        val plain = RaviloWireJson.encodeToString(PlaybackRestreamRequest.serializer(), PlaybackRestreamRequest("i", -1, 1000))
        assertTrue("handover_at_ms" !in plain, plain)
        val ahead = RaviloWireJson.encodeToString(PlaybackRestreamRequest.serializer(), PlaybackRestreamRequest("i", -1, 1000, handoverAtMs = 14_000))
        assertEquals(14_000L, RaviloWireJson.decodeFromString(PlaybackRestreamRequest.serializer(), ahead).handoverAtMs)
        assertNull(RaviloWireJson.decodeFromString(PlaybackRestreamRequest.serializer(), """{"item_id":"i","subtitle_stream_index":-1}""").handoverAtMs)
    }

    @Test fun `a direct play ticket carries its stream bitrate and an older server's ticket does not`() {
        val t = RaviloWireJson.decodeFromString(StreamTicket.serializer(),
            """{"jellyfin_base_url":"http://jf","access_token":"","item_id":"i","container":"mkv","direct_play":true,"hls_url":"u","expires_at":1,"stream_bps":71000000}""")
        assertEquals(71_000_000L, t.streamBps)
        assertNull(RaviloWireJson.decodeFromString(StreamTicket.serializer(), """{"jellyfin_base_url":"http://jf","access_token":"","item_id":"i","container":"mkv","direct_play":true,"hls_url":"u","expires_at":1}""").streamBps)
    }
}
