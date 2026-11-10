package dev.jellystructure.server.routes

import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.shared.tv.DownloadTicket
import dev.jellystructure.tv.DownloadOutcome
import dev.jellystructure.tv.DownloadStreams
import dev.jellystructure.tv.Encoder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-4062 (DEB-3995 FR-6) — a download stream that is not running answers its playlist and segments `503` with `Retry-After` and
 * `X-Download-State: waiting` (the phone shows *Waiting for the server* and asks `start` again), never a 404 or a 410; a play's own stream
 * is not touched by the gate. DEB-4256 (FR-6) — `start`'s 503 says why: `reason: encoder_full` with `streams`, or `reason: unavailable`,
 * both with the two headers; a segment's answer carries no reason; no body names a title, a file or a path.
 */
class DownloadWaitingRouteTest {
    @Test fun `an ended or unknown download stream answers waiting and a play's stream does not`() = testApplication {
        val streams = DownloadStreams(Encoder({ EncoderConfig(enabled = true, workDir = "/tmp/js-test-4062-route", ffmpegDir = "/nonexistent") }))
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
        // DEB-4256 — a segment's waiting answer carries no reason: the phone's next `start` is what says why, if it has to wait.
        val body = Json.parseToJsonElement(waiting.bodyAsText()).jsonObject
        assertEquals(setOf("error"), body.keys)
        // A play's stream id is not a download's: the encoder's own answer stands.
        val play = client.get("/stream/0123abcd/v/0/3.ts")
        assertEquals(HttpStatusCode.NotFound, play.status)
        assertTrue(play.headers["X-Download-State"] == null)
    }

    @Test fun `start's 503 says why — encoder_full with the count unavailable with both headers and no name in either body`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                // What `POST /api/tv/download/start` answers for each outcome (TvRoutes.respondDownload), the service's reasons as they are today.
                get("/start/full") { call.respondDownload<DownloadTicket>(DownloadOutcome.Waiting("no encoder slot free on any card", streams = 3)) }
                get("/start/full-one") { call.respondDownload<DownloadTicket>(DownloadOutcome.Waiting("no encoder slot free on any card", streams = 1)) }
                get("/start/off") { call.respondDownload<DownloadTicket>(DownloadOutcome.Unavailable("encoder off")) }
                get("/start/jellyfin") { call.respondDownload<DownloadTicket>(DownloadOutcome.Unavailable("the title could not be read")) }
                get("/start/refused") { call.respondDownload<DownloadTicket>(DownloadOutcome.Refused("quality high is not offered for this title")) }
            }
        }
        suspend fun waiting(r: HttpResponse): Map<String, kotlinx.serialization.json.JsonElement> {
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertEquals(DownloadStreams.RETRY_AFTER_SECONDS.toString(), r.headers["Retry-After"])
            assertEquals("waiting", r.headers["X-Download-State"])
            return Json.parseToJsonElement(r.bodyAsText()).jsonObject
        }
        val full = waiting(client.get("/start/full"))
        assertEquals("encoder_full", full["reason"]!!.jsonPrimitive.content)
        assertEquals(3, full["streams"]!!.jsonPrimitive.int)
        assertEquals("no encoder slot free on any card", full["error"]!!.jsonPrimitive.content)
        assertEquals(1, waiting(client.get("/start/full-one"))["streams"]!!.jsonPrimitive.int)
        // The encoder off, or Jellyfin unasked: `unavailable`, the same headers, and no count.
        for (path in listOf("/start/off", "/start/jellyfin")) {
            val off = waiting(client.get(path))
            assertEquals("unavailable", off["reason"]!!.jsonPrimitive.content, path)
            assertNull(off["streams"], path)
            assertTrue(off["error"]!!.jsonPrimitive.content.isNotBlank(), path)
        }
        // A refusal is final (400) and no wait: neither header, no reason.
        val refused = client.get("/start/refused")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertNull(refused.headers["X-Download-State"])
        assertNull(refused.headers["Retry-After"])
        assertFalse("reason" in Json.parseToJsonElement(refused.bodyAsText()).jsonObject)
    }
}
