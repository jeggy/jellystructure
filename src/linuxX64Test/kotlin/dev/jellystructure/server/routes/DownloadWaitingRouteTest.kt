package dev.jellystructure.server.routes

import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.tv.DownloadStreams
import dev.jellystructure.tv.Encoder
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * DEB-4062 (DEB-3995 FR-6) — a download stream that is not running answers its playlist and segments `503` with `Retry-After` and
 * `X-Download-State: waiting` (the phone shows *Waiting for the server* and asks `start` again), never a 404 or a 410; a play's own stream
 * is not touched by the gate.
 */
class DownloadWaitingRouteTest {
    @Test fun `an ended or unknown download stream answers waiting and a play's stream does not`() = testApplication {
        val streams = DownloadStreams(Encoder({ EncoderConfig(enabled = true, workDir = "/tmp/js-test-4062-route", ffmpegDir = "/nonexistent") }), playing = { false })
        application {
            routing {
                // The gate the stream routes run first (TvRoutes): a held download stream is waiting, anything else carries on.
                get("/stream/{id}/v/0/{file}") {
                    val id = call.parameters["id"]!!
                    if (streams.holds(id)) return@get call.respondDownloadWaiting()
                    call.respondEncoderMiss(Encoder.Miss.UNKNOWN)
                }
            }
        }
        val waiting = client.get("/stream/dl-ended/v/0/3.ts")
        assertEquals(HttpStatusCode.ServiceUnavailable, waiting.status)
        assertEquals(DownloadStreams.RETRY_AFTER_SECONDS.toString(), waiting.headers["Retry-After"])
        assertEquals("waiting", waiting.headers["X-Download-State"])
        assertNotEquals(HttpStatusCode.NotFound, waiting.status)
        assertNotEquals(HttpStatusCode.Gone, waiting.status)
        // A play's stream id is not a download's: the encoder's own answer stands.
        val play = client.get("/stream/0123abcd/v/0/3.ts")
        assertEquals(HttpStatusCode.NotFound, play.status)
        assertTrue(play.headers["X-Download-State"] == null)
    }
}
