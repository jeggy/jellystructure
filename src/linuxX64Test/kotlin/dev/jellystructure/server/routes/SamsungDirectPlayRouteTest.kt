package dev.jellystructure.server.routes

import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-5916 (DEB-5503, FR-46) — `PUT/GET /api/tv/admin/playback/samsung-direct-play`: under an admin session, written to the live config
 * and `config.toml` at once, and a Settings save in between leaves it as it was (DEB-731).
 */
class SamsungDirectPlayRouteTest {
    private val path = "/tmp/jellystructure-test-samsung-direct-play-${getpid()}.toml"

    @AfterTest fun tearDown() { listOf(path, "$path.tmp").forEach { runCatching { platform.posix.remove(it) } } }

    private fun rig(admin: Boolean = true, block: suspend io.ktor.server.testing.ApplicationTestBuilder.(ConfigStore) -> Unit) = testApplication {
        runCatching { platform.posix.remove(path) }
        val store = ConfigStore(path).also { runBlocking { it.load() } }
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            if (admin) intercept(ApplicationCallPipeline.Plugins) { call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE)) }
            routing { route("/api") { samsungDirectPlayRoutes(store); configureConfigRoutes(store, effectiveScanThreads = 4) } }
        }
        block(store)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.flip(on: Boolean) =
        client.put("/api/tv/admin/playback/samsung-direct-play") { contentType(ContentType.Application.Json); setBody("""{"on":$on}""") }

    @Test fun `GET answers on by default and a PUT flips it and in the live config and in config toml`() = rig { store ->
        assertEquals("""{"on":true}""", client.get("/api/tv/admin/playback/samsung-direct-play").bodyAsText())
        val off = flip(false)
        assertEquals(HttpStatusCode.OK, off.status)
        assertEquals("""{"on":false}""", off.bodyAsText())
        assertEquals(false, store.current.ravilo.samsungDirectPlay, "the next start reads the live config, no restart")
        assertEquals("""{"on":false}""", client.get("/api/tv/admin/playback/samsung-direct-play").bodyAsText())
        assertTrue("samsung_direct_play = false" in readFile(path), readFile(path))

        assertEquals("""{"on":true}""", flip(true).bodyAsText())
        assertEquals(true, store.current.ravilo.samsungDirectPlay)
    }

    @Test fun `a Settings save in between leaves the switch as it was`() = rig { store ->
        flip(false)
        val save = client.put("/api/config") { contentType(ContentType.Application.Json); setBody("""{"public_url":"","scan_schedule":""}""") }
        assertEquals(HttpStatusCode.NoContent, save.status)
        assertEquals(false, store.current.ravilo.samsungDirectPlay)
        assertEquals("""{"on":false}""", client.get("/api/tv/admin/playback/samsung-direct-play").bodyAsText())
    }

    @Test fun `flipping it leaves the household switch alone`() = rig { store ->
        runBlocking { store.update(store.current.copy(ravilo = store.current.ravilo.copy(householdControl = true))) }
        flip(false)
        assertEquals(true, store.current.ravilo.householdControl)
    }

    @Test fun `without an admin session both answer 401 and change nothing`() = rig(admin = false) { store ->
        assertEquals(HttpStatusCode.Unauthorized, flip(false).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/tv/admin/playback/samsung-direct-play").status)
        assertEquals(true, store.current.ravilo.samsungDirectPlay)
    }

    private fun readFile(p: String): String =
        kotlinx.io.files.SystemFileSystem.source(kotlinx.io.files.Path(p)).buffered().use { it.readString() }
}
