package dev.jellystructure.shared.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-2660 (DEB-2628 FR-11) — the receiver heartbeats a station while its tune is open, with DEB-2627 FR-5's body, and stops when it closes. */
class CastRadioHeartbeatTest {
    private class Fake : CastRadioApi {
        val beats = mutableListOf<LiveTvHeartbeatRequest>()
        val stopped = mutableListOf<String>()
        override suspend fun tune(channelId: String, capabilities: ClientCapabilities) = LiveTvStreamTicket(
            jellyfinBaseUrl = "https://jf.example.test", accessToken = "t", channelId = channelId, liveStreamId = "live-1",
            hlsUrl = "/api/tv/livetv/radio/abc", expiresAt = 0L, streamKind = "audio", contentType = "audio/mpeg", channelKind = "radio",
        )
        override suspend fun heartbeat(report: LiveTvHeartbeatRequest) { synchronized(this) { beats += report } }
        override suspend fun stop(liveStreamId: String) { stopped += liveStreamId }
    }

    @Test
    fun `the tune is heartbeaten with the stream and the delivery until it is stopped`() = runBlocking {
        val api = Fake()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val tune = CastRadioTune(scope, { api }, { ClientCapabilities() }, { "https://media.example.test" }, heartbeatMs = 20L)
        tune.start(CastRadioItem("ch-7", "Station One"))
        delay(400L)
        tune.stop()
        val sent = synchronized(api) { api.beats.size }
        assertTrue(sent >= 2, "heartbeats while the tune is open: $sent")
        val first = api.beats.first()
        assertEquals("ch-7", first.channelId); assertEquals("live-1", first.liveStreamId); assertEquals("source", first.delivery)
        delay(200L)
        assertEquals(sent, synchronized(api) { api.beats.size }, "none after the stop")
        assertEquals(listOf("live-1"), api.stopped)
        scope.cancel()
    }
}
