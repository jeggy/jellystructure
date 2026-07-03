package dev.jellystructure.seerr

import dev.jellystructure.OutboundHttp
import dev.jellystructure.arr.ArrPing
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
private data class SeerrStatus(val version: String = "")

/**
 * Phase 136 — a tiny client for Jellyseerr/Overseerr (their API is identical for our purposes).
 * Mirrors [dev.jellystructure.arr.ArrClient]'s shape: same `X-Api-Key` header convention, same shared
 * [OutboundHttp] permit gate (Phase 90/129/134 — never a second unbounded Curl client), same
 * "temporary creds for the test flow, stored creds for the real call sites" pattern. Discover/search/
 * request methods are added by Phase 137/R171 when the TV Request tab actually consumes them — this
 * phase only needs enough to prove the connection.
 */
class SeerrClient {
    private suspend fun httpGet(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.get(url, block) }

    private val http = OutboundHttp.client

    private fun base(url: String) = url.trimEnd('/') + "/api/v1"

    /** Reachability (`GET /status`, no auth) + key validity (`GET /auth/me`, `X-Api-Key`) in one probe. */
    suspend fun ping(url: String, apiKey: String): ArrPing = runCatching {
        val status: SeerrStatus = httpGet(base(url) + "/status").body()
        val me = httpGet(base(url) + "/auth/me") { header("X-Api-Key", apiKey) }
        if (me.status != HttpStatusCode.OK) return@runCatching ArrPing(false, "Reachable, but the API key was rejected")
        ArrPing(true, "Connected", status.version.ifBlank { null })
    }.getOrElse { e -> ArrPing(false, e.message ?: "Unknown error") }
}
