package dev.jellystructure.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

val httpClient = HttpClient(Js) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    expectSuccess = false
}.also { c ->
    // DEB-1253 (FR-7) — a request that gets no HTTP response at all is the server being gone; any status, even a 5xx,
    // is the server answering. The AI page listens; nothing else does.
    c.plugin(HttpSend).intercept { request ->
        try { execute(request) } catch (e: CancellationException) { throw e } catch (e: Throwable) { ServerLink.onNoResponse?.invoke(); throw e }
    }
}

/** DEB-1253 (FR-7) — set by the AI page while it is mounted; null everywhere else. */
object ServerLink {
    var onNoResponse: (() -> Unit)? = null
}
