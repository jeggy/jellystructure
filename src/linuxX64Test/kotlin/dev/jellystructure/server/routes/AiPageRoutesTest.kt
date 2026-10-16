package dev.jellystructure.server.routes

import dev.jellystructure.config.AiConfig
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
        assertEquals(HttpStatusCode.NotFound, answer(AppConfig(ai = AiConfig(apiKey = "")), seerr = true).first)
        assertEquals(HttpStatusCode.NotFound, answer(AppConfig(ai = AiConfig(apiKey = "  ")), seerr = true).first)
    }

    @Test fun aKeyWithoutSeerrHasTheSupportTabAlone() {
        val (status, body) = answer(AppConfig(ai = AiConfig(apiKey = "sk-test")), seerr = false)
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("""{"tabs":["support"]}""", body)
    }

    @Test fun aKeyWithSeerrNamesSupportThenSuggestions() {
        val (status, body) = answer(AppConfig(ai = AiConfig(apiKey = "sk-test")), seerr = true)
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("""{"tabs":["support","suggestions"]}""", body)
    }

    @Test fun supportIsThereWithTheLaneClosedAndWithItOpen() {
        for (open in listOf(false, true)) {
            val cfg = AppConfig(ai = AiConfig(apiKey = "sk-test"), support = SupportConfig(enabled = open))
            assertEquals("""{"tabs":["support"]}""", answer(cfg, seerr = false).second, "enabled = $open")
        }
    }

    @Test fun switchingAiOffWithAKeyStillAnswers200() {
        val (status, body) = answer(AppConfig(ai = AiConfig(enabled = false, apiKey = "sk-test")), seerr = true)
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("""{"tabs":["support","suggestions"]}""", body)
    }
}
