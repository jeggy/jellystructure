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

/** DEB-2233 (FR-1) — `PUT /api/config` refuses each new address beside its own field and stores a good one normalised. */
class ServiceAddressSaveTest {
    private val path = "/tmp/jellystructure-test-svcaddr-save-${getpid()}.toml"

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

    private fun json(web: String, internal: String) =
        """{"public_url":"","scan_schedule":"","ravilo_web_url":"$web","jellyfin_internal_url":"$internal"}"""

    @Test fun `a bad Ravilo Web address is refused beside its field and stores nothing`() = save(json("ravilo.example.net", "")) { status, text, store ->
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue("ravilo_web_url" in text, text)
        assertEquals("", store.current.raviloWebUrl)
    }

    @Test fun `a bad internal address is refused beside its own field`() = save(json("https://ravilo.example.net", "http://jellyfin:8096/?x=1")) { status, text, store ->
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue("jellyfin_internal_url" in text, text)
        assertEquals("", store.current.raviloWebUrl, "a refused save stores neither field")
    }

    @Test fun `good addresses are stored trimmed without a trailing slash`() = save(json("  https://ravilo.example.net/  ", "http://jellyfin:8096/")) { status, _, store ->
        assertEquals(HttpStatusCode.NoContent, status)
        assertEquals("https://ravilo.example.net", store.current.raviloWebUrl)
        assertEquals("http://jellyfin:8096", store.current.jellyfinInternalUrl)
        assertEquals("https://ravilo.example.net", store.raviloWebUrl)
    }

    @Test fun `blank clears both`() = save(json("", "")) { status, _, store ->
        assertEquals(HttpStatusCode.NoContent, status)
        assertEquals("", store.current.raviloWebUrl)
        assertEquals("", store.current.jellyfinInternalUrl)
    }
}
