package dev.jellystructure.server.routes

import dev.jellystructure.config.ConfigStore
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-3737 (DEB-2474 FR-5.10, Acceptance 24) — `PUT /api/config` refuses an `[ai.autofetch] max_turns` outside 6–30 with 400 and the bounds. */
class AutofetchStepLimitSaveTest {
    private val path = "/tmp/jellystructure-test-af-turns-save-${getpid()}.toml"

    @AfterTest fun tearDown() { listOf(path, "$path.tmp").forEach { runCatching { platform.posix.remove(it) } } }

    private fun save(body: String, check: suspend (status: HttpStatusCode, text: String, store: ConfigStore) -> Unit) = testApplication {
        runCatching { platform.posix.remove(path) }
        val store = ConfigStore(path).also { runBlocking { it.load() } }
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { route("/api") { configureConfigRoutes(store, effectiveScanThreads = 4) } }
        }
        val r = client.put("/api/config") { contentType(ContentType.Application.Json); setBody(body) }
        check(r.status, r.bodyAsText(), store)
    }

    private fun body(turns: String?) =
        """{"public_url":"","scan_schedule":"","ai":{"autofetch":{"enabled":true${turns?.let { ",\"max_turns\":$it" }.orEmpty()}}}}"""

    @Test fun `no max_turns reads 12`() = save(body(null)) { status, _, store ->
        assertEquals(HttpStatusCode.NoContent, status)
        assertEquals(12, store.current.ai.autofetch.maxTurns)
    }

    @Test fun `5 and 31 are refused with 400 and the bounds and store nothing`() {
        for (bad in listOf("5", "31", "0", "-1")) save(body(bad)) { status, text, store ->
            assertEquals(HttpStatusCode.BadRequest, status, bad)
            assertTrue("6 to 30" in text && "max_turns" in text, text)
            assertEquals(12, store.current.ai.autofetch.maxTurns)
            assertEquals(false, store.current.ai.autofetch.enabled, "a refused save stores nothing")
        }
    }

    @Test fun `8 is stored and the bounds themselves are accepted`() {
        for (ok in listOf(8, 6, 30)) save(body(ok.toString())) { status, _, store ->
            assertEquals(HttpStatusCode.NoContent, status, ok.toString())
            assertEquals(ok, store.current.ai.autofetch.maxTurns)
        }
    }
}
