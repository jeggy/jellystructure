package dev.jellystructure.seerr

import dev.jellystructure.OutboundHttp
import dev.jellystructure.arr.ArrPing
import dev.jellystructure.shared.tv.SeerrDiscoverEndpoint
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class SeerrStatus(val version: String = "")

/**
 * R171 — one Seerr discover/search result (movie, tv, or person; person entries are filtered out by
 * the caller). Loosely typed since movie and tv results share this shape with different populated
 * fields (title vs name, releaseDate vs firstAirDate) and `ignoreUnknownKeys` tolerates the fields
 * unique to `PersonResult` (profilePath, knownFor, ...) that this DTO doesn't model.
 */
@Serializable
data class SeerrCatalogResult(
    val id: Int = 0,
    val mediaType: String = "",
    val title: String? = null,
    val name: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Double? = null,
    val genreIds: List<Int> = emptyList(),
    val mediaInfo: SeerrMediaInfo? = null,
)

/** `status`: 1=UNKNOWN 2=PENDING 3=PROCESSING 4=PARTIALLY_AVAILABLE 5=AVAILABLE 6=DELETED. */
@Serializable
data class SeerrMediaInfo(val status: Int = 0)

@Serializable
data class SeerrCatalogPage(
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val results: List<SeerrCatalogResult> = emptyList(),
)

/** `status`: 1=PENDING APPROVAL 2=APPROVED 3=DECLINED. */
@Serializable
data class SeerrRequestResult(
    val id: Int = 0,
    val status: Int = 0,
    val media: SeerrMediaInfo = SeerrMediaInfo(),
)

@Serializable
data class SeerrGenre(val name: String = "")

@Serializable
data class SeerrCastMember(val id: Int = 0, val name: String = "", val character: String = "", val profilePath: String? = null)

@Serializable
data class SeerrCredits(val cast: List<SeerrCastMember> = emptyList())

/** `GET /movie/{id}` — full detail, one call gives genres/runtime/cast/mediaInfo together. */
@Serializable
data class SeerrMovieDetails(
    val id: Int = 0,
    val title: String = "",
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val voteAverage: Double? = null,
    val runtime: Int? = null,
    val genres: List<SeerrGenre> = emptyList(),
    val credits: SeerrCredits = SeerrCredits(),
    val mediaInfo: SeerrMediaInfo? = null,
)

/** `GET /tv/{id}` — TV's equivalent of [SeerrMovieDetails] (name/firstAirDate/episodeRunTime instead
 *  of title/releaseDate/runtime). */
@Serializable
data class SeerrTvDetails(
    val id: Int = 0,
    val name: String = "",
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Double? = null,
    val episodeRunTime: List<Int> = emptyList(),
    val genres: List<SeerrGenre> = emptyList(),
    val credits: SeerrCredits = SeerrCredits(),
    val mediaInfo: SeerrMediaInfo? = null,
)

/**
 * Phase 136 — a tiny client for Jellyseerr/Overseerr (their API is identical for our purposes).
 * Mirrors [dev.jellystructure.arr.ArrClient]'s shape: same `X-Api-Key` header convention, same shared
 * [OutboundHttp] permit gate (Phase 90/129/134 — never a second unbounded Curl client), same
 * "temporary creds for the test flow, stored creds for the real call sites" pattern.
 *
 * R171 — discover/search/request methods against the documented Seerr API (verified against
 * `seerr-api.yml`, github.com/seerr-team/seerr — Jellyseerr's current upstream): plain GET calls, one
 * per configured [SeerrDiscoverEndpoint], `X-Api-Key` auth, JSON responses decoded straight into
 * [SeerrCatalogPage]/[SeerrRequestResult].
 */
class SeerrClient {
    private suspend fun httpGet(url: String, apiKey: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.get(url) { header("X-Api-Key", apiKey); block() } }
    private suspend fun httpPost(url: String, apiKey: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.post(url) { header("X-Api-Key", apiKey); block() } }

    private val http = OutboundHttp.client

    private fun base(url: String) = url.trimEnd('/') + "/api/v1"

    /** Reachability (`GET /status`, no auth) + key validity (`GET /auth/me`, `X-Api-Key`) in one probe. */
    suspend fun ping(url: String, apiKey: String): ArrPing = runCatching {
        val status: SeerrStatus = OutboundHttp.withPermit { http.get(base(url) + "/status") }.body()
        val me = httpGet(base(url) + "/auth/me", apiKey)
        if (me.status != HttpStatusCode.OK) return@runCatching ArrPing(false, "Reachable, but the API key was rejected")
        ArrPing(true, "Connected", status.version.ifBlank { null })
    }.getOrElse { e -> ArrPing(false, e.message ?: "Unknown error") }

    /** Path for one configured discover feed; `param` is the genre/studio/network id or ISO-639-1 code. */
    private fun discoverPath(endpoint: SeerrDiscoverEndpoint, param: String?): String = when (endpoint) {
        SeerrDiscoverEndpoint.MOVIES_POPULAR -> "/discover/movies"
        SeerrDiscoverEndpoint.MOVIES_GENRE -> "/discover/movies/genre/${param.orEmpty()}"
        SeerrDiscoverEndpoint.MOVIES_LANGUAGE -> "/discover/movies/language/${param.orEmpty()}"
        SeerrDiscoverEndpoint.MOVIES_STUDIO -> "/discover/movies/studio/${param.orEmpty()}"
        SeerrDiscoverEndpoint.MOVIES_UPCOMING -> "/discover/movies/upcoming"
        SeerrDiscoverEndpoint.TV_POPULAR -> "/discover/tv"
        SeerrDiscoverEndpoint.TV_GENRE -> "/discover/tv/genre/${param.orEmpty()}"
        SeerrDiscoverEndpoint.TV_LANGUAGE -> "/discover/tv/language/${param.orEmpty()}"
        SeerrDiscoverEndpoint.TV_NETWORK -> "/discover/tv/network/${param.orEmpty()}"
        SeerrDiscoverEndpoint.TV_UPCOMING -> "/discover/tv/upcoming"
        SeerrDiscoverEndpoint.TRENDING -> "/discover/trending"
    }

    suspend fun discover(url: String, apiKey: String, endpoint: SeerrDiscoverEndpoint, param: String?, page: Int = 1): SeerrCatalogPage =
        runCatching {
            httpGet(base(url) + discoverPath(endpoint, param), apiKey) { parameter("page", page) }.body<SeerrCatalogPage>()
        }.getOrElse { SeerrCatalogPage() }

    suspend fun search(url: String, apiKey: String, query: String, page: Int = 1): SeerrCatalogPage = runCatching {
        httpGet(base(url) + "/search", apiKey) { parameter("query", query); parameter("page", page) }.body<SeerrCatalogPage>()
    }.getOrElse { SeerrCatalogPage() }

    suspend fun movieDetails(url: String, apiKey: String, tmdbId: Int): SeerrMovieDetails? = runCatching {
        httpGet(base(url) + "/movie/$tmdbId", apiKey).body<SeerrMovieDetails>()
    }.getOrNull()

    suspend fun tvDetails(url: String, apiKey: String, tmdbId: Int): SeerrTvDetails? = runCatching {
        httpGet(base(url) + "/tv/$tmdbId", apiKey).body<SeerrTvDetails>()
    }.getOrNull()

    /** `mediaType` is `"movie"` or `"tv"` (a tv request always asks for every season). Returns null on
     *  failure (declined by permission, or Seerr unreachable) — the caller surfaces that as FAILED. */
    suspend fun createRequest(url: String, apiKey: String, mediaType: String, tmdbId: Int): SeerrRequestResult? =
        runCatching {
            val payload = buildJsonObject {
                put("mediaType", mediaType)
                put("mediaId", tmdbId)
                if (mediaType == "tv") put("seasons", "all")
            }
            val resp = httpPost(base(url) + "/request", apiKey) {
                contentType(ContentType.Application.Json); setBody(payload.toString())
            }
            if (resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK) resp.body<SeerrRequestResult>() else null
        }.getOrNull()
}
