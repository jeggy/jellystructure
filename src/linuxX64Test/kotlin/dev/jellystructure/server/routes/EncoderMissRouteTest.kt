package dev.jellystructure.server.routes

import dev.jellystructure.tv.Encoder
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-3100 (trouble-never-reaches-the-viewer FR-3) — a segment not ready answers 503 with `Retry-After: 1`; a 404 stays for what does not exist. */
class EncoderMissRouteTest {
    @Test fun `each miss answers its own status`() = testApplication {
        application {
            routing {
                for (m in Encoder.Miss.entries) get("/miss/${m.name}") { call.respondEncoderMiss(m) }
            }
        }
        val retry = client.get("/miss/RETRY")
        assertEquals(HttpStatusCode.ServiceUnavailable, retry.status)
        assertEquals("1", retry.headers["Retry-After"])
        assertEquals(HttpStatusCode.NotFound, client.get("/miss/UNKNOWN").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/miss/UNMADE").status)
        val gone = client.get("/miss/GONE")
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertNull(gone.headers["Retry-After"])
    }
}
