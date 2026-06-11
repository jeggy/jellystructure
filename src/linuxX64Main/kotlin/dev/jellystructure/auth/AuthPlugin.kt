package dev.jellystructure.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

val SessionKey = AttributeKey<SessionData>("JsSession")

private val OPEN_API_PATHS = listOf(
    "/api/auth/login",
    "/api/setup",
)

fun Application.installAuthPlugin(sessionService: SessionService) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()

        if (!path.startsWith("/api/")) {
            proceed()
            return@intercept
        }

        if (OPEN_API_PATHS.any { path.startsWith(it) }) {
            proceed()
            return@intercept
        }

        val token = call.request.cookies["js_session"]
        val session = token?.let { sessionService.validate(it) }

        if (session == null) {
            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "Not authenticated"),
            )
            finish()
            return@intercept
        }

        call.attributes.put(SessionKey, session)
        proceed()
    }
}
