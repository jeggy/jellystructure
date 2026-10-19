package dev.jellystructure.server.routes

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-1906 (FR-214-3) — Stop scan and Stop this step answer 200 with what Jellyfin is still running, not a 500. */
class ScanStopRouteTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var cancels = 0
    private var stepStops = 0

    private fun routes(running: Boolean, subtitles: Int, body: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            routing {
                route("/api") {
                    scanStopRoutes(
                        isRunning = { running },
                        cancelRun = { cancels++ },
                        stopCurrentStep = { stepStops++ },
                        subtitlesStillRunning = { subtitles },
                    )
                }
            }
        }
        body()
    }

    @Test fun `stop scan with a scan running is 200 and reports no subtitle extraction`() = routes(running = true, subtitles = 0) {
        val r = client.post("/api/scan/cancel")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("""{"status":"cancel requested","subtitlesStillRunning":0}""", r.bodyAsText())
        assertEquals(1, cancels)
    }

    @Test fun `stop scan with one subtitle extraction still running reports 1`() = routes(running = true, subtitles = 1) {
        val r = client.post("/api/scan/cancel")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("""{"status":"cancel requested","subtitlesStillRunning":1}""", r.bodyAsText())
    }

    @Test fun `stop this step with a scan running is 200 and reports no subtitle extraction`() = routes(running = true, subtitles = 0) {
        val r = client.post("/api/scan/stop-step")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("""{"status":"step stop requested","subtitlesStillRunning":0}""", r.bodyAsText())
        assertEquals(1, stepStops)
    }

    @Test fun `stop this step with one subtitle extraction still running reports 1`() = routes(running = true, subtitles = 1) {
        val r = client.post("/api/scan/stop-step")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("""{"status":"step stop requested","subtitlesStillRunning":1}""", r.bodyAsText())
    }

    @Test fun `both are 409 no scan running when nothing runs and stop nothing`() = routes(running = false, subtitles = 1) {
        for (path in listOf("/api/scan/cancel", "/api/scan/stop-step")) {
            val r = client.post(path)
            assertEquals(HttpStatusCode.Conflict, r.status, path)
            assertEquals("""{"error":"no scan running"}""", r.bodyAsText(), path)
        }
        assertEquals(0, cancels)
        assertEquals(0, stepStops)
    }
}
