package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinPolicy
import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.auth.PasswordChangeOutcome
import dev.jellystructure.shared.tv.AccountPasswordResult
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-1982 (FR-187-8) — what `POST /api/tv/account/password` puts on the wire. The server installs
 * `Json { ignoreUnknownKeys = true }` (Server.kt), which does not encode defaults, so a successful change used to answer
 * `{"ok":true}` and leave out the measured `token_survived`. This answers through that same Json, with the result
 * [AccountPassword.change] returns, exactly as the route's `call.respond(HttpStatusCode.OK, result)` does.
 */
class PasswordResultWireTest {
    private fun localUser() = JellyfinUser(id = "u1", name = "n", policy = JellyfinPolicy(isAdministrator = false, authenticationProviderId = null))

    private fun answerBody(survived: Boolean, others: Boolean): String {
        var body = ""
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                routing {
                    post("/api/tv/account/password") {
                        val result = AccountPassword.change(
                            readUser = { localUser() }, onProvider = {}, relay = { PasswordChangeOutcome.OK },
                            tokenSurvived = { survived }, otherDevices = { others },
                        )
                        call.respond(HttpStatusCode.OK, checkNotNull(result))
                    }
                }
            }
            val r = client.post("/api/tv/account/password")
            assertEquals(HttpStatusCode.OK, r.status)
            body = r.bodyAsText()
        }
        return body
    }

    @Test fun aSuccessfulChangeStatesTokenSurvivedTrueExplicitly() {
        val o = Json.parseToJsonElement(answerBody(survived = true, others = false)).jsonObject
        assertTrue(o.getValue("ok").jsonPrimitive.boolean)
        assertTrue("token_survived" in o, "token_survived must be on the wire, got $o")
        assertTrue(o.getValue("token_survived").jsonPrimitive.boolean)
    }

    @Test fun aMeasuredDeathIsStatedAsFalse() {
        val o = Json.parseToJsonElement(answerBody(survived = false, others = false)).jsonObject
        assertFalse(o.getValue("token_survived").jsonPrimitive.boolean)
    }

    @Test fun otherDevicesSignedOutStaysOmittedWhenFalseAndIsStatedWhenTrue() {
        assertFalse("other_devices_signed_out" in Json.parseToJsonElement(answerBody(survived = true, others = false)).jsonObject)
        assertTrue(Json.parseToJsonElement(answerBody(survived = true, others = true)).jsonObject
            .getValue("other_devices_signed_out").jsonPrimitive.boolean)
    }

    @Test fun anOlderBodyWithoutTheFieldStillDecodesAsSurvived() {
        assertTrue(Json { ignoreUnknownKeys = true }.decodeFromString<AccountPasswordResult>("""{"ok":true}""").tokenSurvived)
    }
}
