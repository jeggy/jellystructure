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

/** DEB-1854 (FR-4, FR-11) — `PUT /api/config` refuses a password page that is not https and stores a good one. */
class PasswordChangeUrlSaveTest {
    private val path = "/tmp/jellystructure-test-pwurl-save-${getpid()}.toml"

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

    @Test fun aNonHttpsAddressIsRefusedBesideItsField() = save("""{"public_url":"","scan_schedule":"","password_change_url":"http://account.example.org/p"}""") { status, text, store ->
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue("password_change_url" in text, text)
        assertEquals("", store.current.passwordChangeUrl, "a refused save stores nothing")
    }

    @Test fun anHttpsAddressIsStoredTrimmedAndServedToClients() = save("""{"public_url":"","scan_schedule":"","password_change_url":"  https://account.example.org/p?x=1  "}""") { status, _, store ->
        assertEquals(HttpStatusCode.NoContent, status)
        assertEquals("https://account.example.org/p?x=1", store.current.passwordChangeUrl)
        assertEquals("https://account.example.org/p?x=1", store.passwordChangeUrl)
    }

    @Test fun blankClearsIt() = save("""{"public_url":"","scan_schedule":"","password_change_url":""}""") { status, _, store ->
        assertEquals(HttpStatusCode.NoContent, status)
        assertEquals("", store.current.passwordChangeUrl)
    }
}
