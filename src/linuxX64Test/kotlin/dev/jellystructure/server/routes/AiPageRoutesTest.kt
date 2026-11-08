package dev.jellystructure.server.routes

import dev.jellystructure.config.AiAutofetchConfig
import dev.jellystructure.config.AiConfig
import dev.jellystructure.config.AutofetchConfig
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.SupportConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-1253 (FR-1, FR-2) + DEB-1500 (FR-1) — `GET /api/ai/page` across the configs the specs' acceptance lists name, through
 * `testApplication`: no `[ai]`, a blank key, a key without Seerr, a key with Seerr. `ai.enabled = false` with a key
 * still answers 200, because the key is the gate and not the switch. Support is the first tab whenever the key is set,
 * with the lane open or closed (DEB-1500), so a key with no Seerr is no longer a 404.
 */
class AiPageRoutesTest {
    private fun fetchersOn() = AppConfig(
        ai = AiConfig(enabled = true, apiKey = "sk-test", autofetch = AiAutofetchConfig(enabled = true)),
        autofetch = AutofetchConfig(enabled = true),
    )

    private fun answer(cfg: AppConfig, seerr: Boolean): Pair<HttpStatusCode, String> {
        var out: Pair<HttpStatusCode, String>? = null
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(Json { encodeDefaults = true })
                }
                routing { route("/api") { aiPageRoutes({ cfg }, { seerr }) } }
            }
            val r = client.get("/api/ai/page")
            out = r.status to r.bodyAsText()
        }
        return out!!
    }

    @Test fun noAiBlockIs404() = assertEquals(HttpStatusCode.NotFound, answer(AppConfig(), seerr = true).first)

    @Test fun aBlankKeyIs404() {
        val on = fetchersOn()
        for (key in listOf("", "  ")) {
            val cfg = on.copy(ai = on.ai.copy(apiKey = key))
            assertEquals(HttpStatusCode.NotFound, answer(cfg, seerr = true).first)
        }
    }

    @Test fun aKeyWithoutSeerrHasSupportAndFetchers() {
        val (status, body) = answer(fetchersOn(), seerr = false)
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("""{"tabs":["support","fetchers"]}""", body)
    }

    @Test fun aKeyWithSeerrNamesSupportFetchersThenSuggestions() {
        val (status, body) = answer(fetchersOn(), seerr = true)
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("""{"tabs":["support","fetchers","suggestions"]}""", body)
    }

    @Test fun supportIsThereWithTheLaneClosedAndWithItOpen() {
        for (open in listOf(false, true)) {
            val cfg = fetchersOn().copy(support = SupportConfig(enabled = open))
            assertEquals("""{"tabs":["support","fetchers"]}""", answer(cfg, seerr = false).second, "enabled = $open")
        }
    }

    @Test fun switchingAiOffWithAKeyStillAnswers200() {
        val cfg = fetchersOn().copy(ai = fetchersOn().ai.copy(enabled = false))
        val (status, body) = answer(cfg, seerr = true)
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("""{"tabs":["support","suggestions"]}""", body)
    }

    @Test fun switchingTheAutoFetchJobOffHidesFetchers() {
        val on = fetchersOn()
        val cfg = on.copy(ai = on.ai.copy(autofetch = AiAutofetchConfig(enabled = false)))
        assertEquals("""{"tabs":["support","suggestions"]}""", answer(cfg, seerr = true).second)
    }

    @Test fun switchingAutoFetchOffHidesFetchers() {
        val cfg = fetchersOn().copy(autofetch = AutofetchConfig(enabled = false))
        assertEquals("""{"tabs":["support"]}""", answer(cfg, seerr = false).second)
    }
}
