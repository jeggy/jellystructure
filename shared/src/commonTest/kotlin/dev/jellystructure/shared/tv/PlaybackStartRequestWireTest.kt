package dev.jellystructure.shared.tv

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R292 (FR-R292-2, dev review item 2) — the start request's one additive field, on the wire both ways. */
class PlaybackStartRequestWireTest {
    private val server = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val client = Json { ignoreUnknownKeys = true; isLenient = true }
    private val caps = ClientCapabilities()

    @Test
    fun `an old client's request without the field still parses and means an ordinary start`() {
        val old = """{"item_id":"i1","capabilities":${client.encodeToString(ClientCapabilities.serializer(), caps)}}"""
        val req = server.decodeFromString(PlaybackStartRequest.serializer(), old)
        assertEquals("i1", req.itemId)
        assertNull(req.startPositionMs)
    }

    @Test
    fun `a return carries the engine's position and an ordinary start omits it`() {
        val ret = client.encodeToString(PlaybackStartRequest.serializer(), PlaybackStartRequest("i1", caps, 91_000L))
        assertTrue(ret.contains("\"start_position_ms\":91000"), ret)
        assertEquals(91_000L, server.decodeFromString(PlaybackStartRequest.serializer(), ret).startPositionMs)
        val plain = server.encodeToString(PlaybackStartRequest.serializer(), PlaybackStartRequest("i1", caps))
        assertFalse(plain.contains("start_position_ms"), plain)
    }

    @Test
    fun `a qoe report from before R292 parses with the new counters at zero`() {
        val old = """{"item_id":"i1","dropped_frames":2,"direct_play":true}"""
        val r = server.decodeFromString(PlaybackQoeReport.serializer(), old)
        assertEquals(0, r.videoOutputRecoveries); assertEquals(0, r.backgroundReturns); assertEquals(0, r.restoredAfterRecreate)
    }
}
