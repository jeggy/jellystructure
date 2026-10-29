package dev.jellystructure.shared.tv

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2627 (FR-5) — the heartbeat's optional body is additive: an empty or older shape still decodes, a newer field is ignored, the keys are the spec's. */
class LiveTvHeartbeatWireTest {
    private val server = Json { ignoreUnknownKeys = true }
    private val client = Json { encodeDefaults = false }

    @Test fun an_empty_object_decodes_to_a_report_that_says_nothing() {
        val r = server.decodeFromString(LiveTvHeartbeatRequest.serializer(), "{}")
        assertNull(r.channelId); assertNull(r.liveStreamId); assertNull(r.variantBandwidthBps); assertNull(r.height); assertNull(r.delivery)
    }

    @Test fun a_report_uses_the_specs_keys_and_round_trips() {
        val sent = LiveTvHeartbeatRequest("c1", "ls1", 7_700_000L, 1080, "source")
        val wire = client.encodeToString(LiveTvHeartbeatRequest.serializer(), sent)
        for (key in listOf("channel_id", "live_stream_id", "variant_bandwidth_bps", "height", "delivery")) assertTrue("\"$key\"" in wire, wire)
        assertEquals(sent, server.decodeFromString(LiveTvHeartbeatRequest.serializer(), wire))
    }

    @Test fun a_field_from_a_newer_client_is_ignored() {
        val r = server.decodeFromString(LiveTvHeartbeatRequest.serializer(), """{"height":720,"codec":"hevc"}""")
        assertEquals(720, r.height)
    }
}
