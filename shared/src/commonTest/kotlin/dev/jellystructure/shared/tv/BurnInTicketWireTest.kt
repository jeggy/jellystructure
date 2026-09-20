package dev.jellystructure.shared.tv

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 252 — the wire contract of the two additive fields. The failure this guards is v1.31's: a
 * DTO change that looks free and makes an installed client (or an older server) unable to parse.
 * [server] mirrors the backend's Json (`encodeDefaults = false`); [client] mirrors TvApiClient's.
 */
class BurnInTicketWireTest {
    private val server = Json { encodeDefaults = false }
    private val client = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun ticket(burned: Int?) = StreamTicket(
        jellyfinBaseUrl = "http://jf", accessToken = "", itemId = "i", container = "mkv",
        directPlay = burned == null, hlsUrl = "http://jf/s", expiresAt = 1L, burnedSubtitleIndex = burned,
    )

    @Test fun an_ordinary_ticket_does_not_mention_a_burn_in_at_all() {
        assertFalse("burned_subtitle_index" in server.encodeToString(StreamTicket.serializer(), ticket(null)))
    }

    @Test fun a_burn_in_ticket_names_the_index_and_round_trips() {
        val wire = server.encodeToString(StreamTicket.serializer(), ticket(6))
        assertTrue("\"burned_subtitle_index\":6" in wire)
        assertEquals(6, client.decodeFromString(StreamTicket.serializer(), wire).burnedSubtitleIndex)
    }

    @Test fun a_ticket_from_a_pre_252_server_still_parses_as_no_burn_in() {
        val old = """{"jellyfin_base_url":"http://jf","access_token":"","item_id":"i","container":"mkv","direct_play":false,"hls_url":"http://jf/s","expires_at":1}"""
        assertNull(client.decodeFromString(StreamTicket.serializer(), old).burnedSubtitleIndex)
    }

    @Test fun a_restream_request_from_a_pre_252_client_still_parses() {
        val old = """{"item_id":"i","subtitle_stream_index":6,"position_ms":1000}"""
        val req = Json.decodeFromString(PlaybackRestreamRequest.serializer(), old)
        assertEquals(6, req.subtitleStreamIndex)
        assertNull(req.capabilities)
    }

    // ── Phase 253 ─────────────────────────────────────────────────────────────────────────────────
    @Test fun a_direct_play_ticket_does_not_mention_an_audio_index() {
        assertFalse("audio_stream_index" in server.encodeToString(StreamTicket.serializer(), ticket(null)))
    }

    @Test fun a_transcode_ticket_names_its_audio_and_round_trips() {
        val wire = server.encodeToString(StreamTicket.serializer(), ticket(6).copy(audioStreamIndex = 5))
        assertEquals(5, client.decodeFromString(StreamTicket.serializer(), wire).audioStreamIndex)
    }

    @Test fun a_restream_request_composes_audio_and_burn_in() {
        val wire = server.encodeToString(PlaybackRestreamRequest.serializer(), PlaybackRestreamRequest("i", 6, 1000, null, 5))
        val back = Json.decodeFromString(PlaybackRestreamRequest.serializer(), wire)
        assertEquals(6 to 5, back.subtitleStreamIndex to back.audioStreamIndex)
    }

    @Test fun capabilities_from_a_pre_253_client_mean_no_hevc_over_hls() {
        assertFalse(Json.decodeFromString(ClientCapabilities.serializer(), """{"hls_only":true,"video_codecs":["hevc"]}""").hlsHevc)
    }

    @Test fun an_un_burn_request_carries_minus_one_and_the_capabilities() {
        val wire = server.encodeToString(
            PlaybackRestreamRequest.serializer(),
            PlaybackRestreamRequest("i", -1, 1000, ClientCapabilities(supportsEmbeddedTextSubs = true)),
        )
        val back = Json.decodeFromString(PlaybackRestreamRequest.serializer(), wire)
        assertEquals(-1, back.subtitleStreamIndex)
        assertEquals(true, back.capabilities?.supportsEmbeddedTextSubs)
    }
}
