package dev.jellystructure.api

import dev.jellystructure.model.SuggestionActionResult
import dev.jellystructure.model.SuggestionDismissRequest
import dev.jellystructure.model.SuggestionDismissedDto
import dev.jellystructure.model.SuggestionsPageDto
import dev.jellystructure.model.SuggestionsSummaryDto
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/** Phase 274 — the Suggestions page's calls. Every route 404s while Seerr isn't connected (FR-274-1): the sidebar
 *  entry, the page and the Dashboard card ask [available] and draw nothing without it. */
object SuggestionsApi {
    suspend fun available(): Boolean = runCatching { httpClient.get("/api/suggestions/summary").status != HttpStatusCode.NotFound }.getOrDefault(false)

    /** Null when Seerr is not connected or the call failed; the page tells the two apart by [available]. */
    suspend fun page(): SuggestionsPageDto? = runCatching {
        httpClient.get("/api/suggestions").takeIf { it.status == HttpStatusCode.OK }?.body<SuggestionsPageDto>()
    }.getOrNull()

    suspend fun summary(): SuggestionsSummaryDto? = runCatching {
        httpClient.get("/api/suggestions/summary").takeIf { it.status == HttpStatusCode.OK }?.body<SuggestionsSummaryDto>()
    }.getOrNull()

    suspend fun dismissed(): List<SuggestionDismissedDto>? = runCatching {
        httpClient.get("/api/suggestions/dismissed").takeIf { it.status == HttpStatusCode.OK }?.body<List<SuggestionDismissedDto>>()
    }.getOrNull()

    suspend fun rebuild(): Boolean = runCatching { httpClient.post("/api/suggestions/rebuild").status == HttpStatusCode.OK }.getOrDefault(false)

    suspend fun download(tmdbId: Int): SuggestionActionResult? = runCatching {
        httpClient.post("/api/suggestions/$tmdbId/download").body<SuggestionActionResult>()
    }.getOrNull()

    suspend fun dismiss(tmdbId: Int, reason: String, note: String?): SuggestionActionResult? = runCatching {
        httpClient.post("/api/suggestions/$tmdbId/dismiss") { contentType(ContentType.Application.Json); setBody(SuggestionDismissRequest(reason, note)) }.body<SuggestionActionResult>()
    }.getOrNull()

    /** *Undo* and *Bring back*. */
    suspend fun bringBack(tmdbId: Int): SuggestionActionResult? = runCatching {
        httpClient.delete("/api/suggestions/$tmdbId/dismiss").body<SuggestionActionResult>()
    }.getOrNull()
}
