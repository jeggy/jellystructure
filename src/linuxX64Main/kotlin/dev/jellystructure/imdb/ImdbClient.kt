package dev.jellystructure.imdb

import dev.jellystructure.OutboundHttp
import dev.jellystructure.log.Logger
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class ImdbTitleResponse(
    val rating: ImdbRatingBlock? = null,
)

@Serializable
data class ImdbRatingBlock(
    val aggregateRating: Double? = null,
    val voteCount: Long? = null,
)

/** One fetched rating: (aggregateRating 0-10, voteCount). */
data class ImdbFetchedRating(val aggregateRating: Double, val voteCount: Long)

/**
 * Phase 131 — imdbapi.dev client. Free, no API key. Rides the Phase 129 shared [OutboundHttp] pool
 * (no new `HttpClient`, per the FD-budget mandate) — called only by the scheduled sync pipeline step
 * or the manual per-title Re-sync route, **never** at detail/feed-read time.
 */
class ImdbClient(private val baseUrl: String = "https://api.imdbapi.dev") {
    private val http = OutboundHttp.client

    /** Best-effort — null on any failure/missing rating, so callers can leave the stored value intact
     *  on a transient error rather than blanking a good rating. */
    suspend fun getRating(imdbId: String): ImdbFetchedRating? {
        val result = runCatching {
            val response = OutboundHttp.withPermit { http.get("$baseUrl/titles/$imdbId") }
            if (response.status != HttpStatusCode.OK) return@runCatching null
            val rating = response.body<ImdbTitleResponse>().rating ?: return@runCatching null
            val agg = rating.aggregateRating ?: return@runCatching null
            ImdbFetchedRating(agg, rating.voteCount ?: 0L)
        }
        if (result.isFailure) Logger.warn("imdbapi.dev fetch failed for id=$imdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }
}
