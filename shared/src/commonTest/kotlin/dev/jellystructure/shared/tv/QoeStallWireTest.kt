package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2205 (FR-1, FR-8) — a stall entry gains optional fields; an older app's entry and an older server's reader both still work. */
class QoeStallWireTest {
    @Test fun `an older apps entry without the new fields decodes with them null`() {
        val old = """{"after_first_frame_ms":120000,"position_ms":120000,"buffered_ms":300,"duration_ms":1500,"phase":"mid","variant_bps":8000000}"""
        val s = RaviloWireJson.decodeFromString(QoeStall.serializer(), old)
        assertEquals(300L, s.bufferedMs)
        assertEquals(8_000_000L, s.variantBps)
        assertNull(s.cause)
        assertNull(s.bufferAheadMs)
        assertNull(s.rungHeight)
        assertNull(s.rungBps)
        assertNull(s.pendingSubtitleLoads)
    }

    @Test fun `the new fields round trip under their snake case names and the old names stay`() {
        val s = QoeStall(
            afterFirstFrameMs = 5, positionMs = 6, bufferedMs = 7, durationMs = 8, phase = "start", variantBps = 9,
            cause = "buffer_empty", bufferAheadMs = 7, rungHeight = 1080, rungBps = 9, pendingSubtitleLoads = 1,
        )
        val json = RaviloWireJsonWithDefaults.encodeToString(QoeStall.serializer(), s)
        for (key in listOf("after_first_frame_ms", "position_ms", "buffered_ms", "duration_ms", "phase", "variant_bps",
            "cause", "buffer_ahead_ms", "rung_height", "rung_bps", "pending_subtitle_loads")) {
            assertTrue("\"$key\":" in json, "$key in $json")
        }
        assertEquals(s, RaviloWireJson.decodeFromString(QoeStall.serializer(), json))
    }

    @Test fun `an entry naming the frozen cause decodes`() {
        val json = """{"after_first_frame_ms":1,"position_ms":2,"buffered_ms":3,"duration_ms":4,"phase":"mid","cause":"frozen_with_buffer","rung_height":2160}"""
        assertEquals("frozen_with_buffer", RaviloWireJson.decodeFromString(QoeStall.serializer(), json).cause)
    }

    // ── DEB-2205 (FR-1, FR-5.2) — the frozen counters and the recovery bound are additive ──
    @Test fun `a report from an older app carries no frozen stop`() {
        val r = RaviloWireJson.decodeFromString(PlaybackQoeReport.serializer(), """{"item_id":"i","rebuffer_count":1}""")
        assertEquals(0, r.frozenCount)
        assertEquals(0L, r.frozenMs)
    }

    @Test fun `the frozen counters round trip under their snake case names`() {
        val json = RaviloWireJsonWithDefaults.encodeToString(PlaybackQoeReport.serializer(), PlaybackQoeReport(itemId = "i", frozenCount = 2, frozenMs = 7_500))
        assertTrue("\"frozen_count\":2" in json && "\"frozen_ms\":7500" in json, json)
        assertEquals(2, RaviloWireJson.decodeFromString(PlaybackQoeReport.serializer(), json).frozenCount)
    }

    @Test fun `a restream request names the bound only when a recovery sets it`() {
        val plain = RaviloWireJson.encodeToString(PlaybackRestreamRequest.serializer(), PlaybackRestreamRequest("i", -1, 1000))
        assertTrue("below_bandwidth_bps" !in plain, plain)
        val bounded = RaviloWireJson.encodeToString(PlaybackRestreamRequest.serializer(), PlaybackRestreamRequest("i", -1, 1000, belowBandwidthBps = 31_000_000))
        assertEquals(31_000_000L, RaviloWireJson.decodeFromString(PlaybackRestreamRequest.serializer(), bounded).belowBandwidthBps)
        assertEquals(null, RaviloWireJson.decodeFromString(PlaybackRestreamRequest.serializer(), """{"item_id":"i","subtitle_stream_index":-1}""").belowBandwidthBps)
    }
}
