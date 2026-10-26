package dev.jellystructure.api

import dev.jellystructure.model.DashboardDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode

/** Phase 285 — the Dashboard reads one endpoint and renders it; nothing on the page is computed here. */
object DashboardApi {
    suspend fun get(): DashboardDto? = runCatching { httpClient.get("/api/dashboard").body<DashboardDto>() }.getOrNull()

    /** DEB-2518 (FR-7) — *Save to Jellyfin* on a filter-tag row; [key] is the row's own percent-encoded tag. How many titles were queued, or null. */
    suspend fun saveFilterTag(key: String): Int? = runCatching {
        val r = httpClient.post("/api/dashboard/filter-tags/$key/save")
        if (r.status.value in 200..299) r.body<FilterTagSaved>().queued else null
    }.getOrNull()

    @kotlinx.serialization.Serializable
    private data class FilterTagSaved(val queued: Int = 0)

    /** FR-285-8 — called when the admin leaves the page; the next open's *Since your last visit* measures from now. */
    suspend fun seen(): Boolean = runCatching { httpClient.post("/api/dashboard/seen").status == HttpStatusCode.OK }.getOrDefault(false)
}
