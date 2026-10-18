package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import dev.jellystructure.tv.PlaybackTracker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-1824 (FR-178-1) — `GET /api/playback/active` answers 200 with `{active, devices}` through ContentNegotiation, not a 500. */
class PlaybackActiveRouteTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun routes(tracker: PlaybackTracker, body: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.headers["X-Test-Admin"] != null) call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE))
            }
            routing { route("/api") { playbackActiveRoutes(tracker::anyActive, tracker::activeDevices) } }
        }
        body()
    }

    private fun device(name: String) = DeviceData(
        deviceId = "tv-1", deviceToken = "dt", jellyfinUserId = "user-1", jellyfinUsername = "u", jellyfinUserToken = "jt",
        isAdmin = false, displayName = name,
    )

    private suspend fun ApplicationTestBuilder.read(signedIn: Boolean = true) =
        client.get("/api/playback/active") { if (signedIn) header("X-Test-Admin", "1") }

    @Test fun `signed in with nothing playing is 200 active false and no devices`() = routes(PlaybackTracker()) {
        val r = read()
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(PlaybackActiveResponse(false, emptyList()), json.decodeFromString(PlaybackActiveResponse.serializer(), r.bodyAsText()))
    }

    @Test fun `signed in with a tracked device is 200 active true naming it`() = routes(PlaybackTracker().also { runBlocking { it.started(device("Living room TV"), "item-1", 0L) } }) {
        val r = read()
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("""{"active":true,"devices":["Living room TV"]}""", r.bodyAsText())
    }

    @Test fun `not signed in is 401`() = routes(PlaybackTracker().also { runBlocking { it.started(device("Living room TV"), "item-1", 0L) } }) {
        assertEquals(HttpStatusCode.Unauthorized, read(signedIn = false).status)
    }
}
