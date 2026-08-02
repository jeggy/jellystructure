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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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

/**
 * `status`: 1=UNKNOWN 2=PENDING 3=PROCESSING 4=PARTIALLY_AVAILABLE 5=AVAILABLE 6=DELETED.
 *
 * [downloadStatus] — verified live (2026-07-05) against a real in-progress movie: Seerr's `Media`
 * entity proxies real byte-level progress straight from Radarr/Sonarr's own download-client queue
 * (`server/lib/downloadtracker.ts` `DownloadingItem`), even though it isn't in the public OpenAPI
 * docs. Empty while status=3/PROCESSING but nothing has actually been grabbed yet (still "in queue");
 * populated once a download is under way — this is what R171 called out as unavailable, but it exists.
 */
@Serializable
data class SeerrMediaInfo(
    val status: Int = 0,
    val downloadStatus: List<SeerrDownloadItem> = emptyList(),
)

/** One item (a movie, or one episode of a series) actively tracked by the download client, as Seerr
 *  relays it. [size]/[sizeLeft] are bytes; `size - sizeLeft` over `size` is the real progress fraction. */
@Serializable
data class SeerrDownloadItem(
    val size: Long = 0,
    val sizeLeft: Long = 0,
    val status: String = "",
    val timeLeft: String? = null,
)

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

/** Phase 156 — just enough of Seerr's `User` to resolve a Jellyfin user's Seerr account id. */
@Serializable
data class SeerrUser(val id: Int = 0)

@Serializable
data class SeerrGenre(val name: String = "")

@Serializable
data class SeerrCastMember(val id: Int = 0, val name: String = "", val character: String = "", val profilePath: String? = null)

@Serializable
data class SeerrCredits(val cast: List<SeerrCastMember> = emptyList())

/** `GET /person/{id}/combined_credits` — one movie/tv credit for that person. No `mediaInfo`/library-match
 *  field (verified live 2026-08-02 against `stream.example.net`); callers cross-reference `id` (TMDB id)
 *  against the local library themselves, same as [SeerrDiscoverService.acquisitionFor] does for Discover. */
@Serializable
data class SeerrPersonCredit(
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
)

@Serializable
data class SeerrPersonCombinedCredits(
    val cast: List<SeerrPersonCredit> = emptyList(),
    val crew: List<SeerrPersonCredit> = emptyList(),
)

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
        // Bug fix: Seerr's language-discover route matches the ISO-639-1 code case-sensitively — an
        // uppercase code (e.g. "DA", easy to type by habit) silently returns zero results instead of
        // erroring, so the feed just looks empty. Verified live: /language/da -> 6443 results,
        // /language/DA -> 0. Lowercasing here guards every caller, not just the admin add-row UI.
        SeerrDiscoverEndpoint.MOVIES_LANGUAGE -> "/discover/movies/language/${param.orEmpty().lowercase()}"
        SeerrDiscoverEndpoint.MOVIES_STUDIO -> "/discover/movies/studio/${param.orEmpty()}"
        SeerrDiscoverEndpoint.MOVIES_UPCOMING -> "/discover/movies/upcoming"
        SeerrDiscoverEndpoint.TV_POPULAR -> "/discover/tv"
        SeerrDiscoverEndpoint.TV_GENRE -> "/discover/tv/genre/${param.orEmpty()}"
        SeerrDiscoverEndpoint.TV_LANGUAGE -> "/discover/tv/language/${param.orEmpty().lowercase()}"
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

    /** R190 §C — deduped cast+crew credits for [personId]'s Seerr overflow row. Verified live
     *  2026-08-02 (`GET /person/{id}/combined_credits` exists and returns real TMDB-shaped credits). */
    suspend fun personCombinedCredits(url: String, apiKey: String, personId: Int): SeerrPersonCombinedCredits? = runCatching {
        httpGet(base(url) + "/person/$personId/combined_credits", apiKey).body<SeerrPersonCombinedCredits>()
    }.getOrNull()

    /**
     * `mediaType` is `"movie"` or `"tv"` (a tv request always asks for every season). Returns null on
     * failure (declined by permission, or Seerr unreachable) — the caller surfaces that as FAILED.
     *
     * Phase 139 — [profileId] (+ optional [tagIds]) steers which Radarr/Sonarr quality profile the
     * request lands on, e.g. a Nordic-scored profile for a Danish-dub pick. Both are Seerr's own
     * documented `POST /request` fields (verified against `seerr-api.yml`); omitted (null/empty) they
     * simply aren't sent, reproducing today's plain-request behaviour exactly.
     *
     * Phase 156 — [seerrUserId], when non-null, sends `X-API-User: <id>` alongside the usual
     * `X-Api-Key`, which Seerr's own auth middleware (`server/middleware/auth.ts` `checkUser`) resolves
     * to `req.user = <that user>` **directly** — not the request-body `userId` override this used to
     * send. Traced live (2026-08-02): the body-`userId` override only reassigns `requestedBy` and the
     * REQUEST-permission/quota checks; the auto-approve decision (`MediaRequest.ts:374`,
     * `user.hasPermission([AUTO_APPROVE, AUTO_APPROVE_MOVIE, MANAGE_REQUESTS])`) still reads the
     * *original* API-key-authenticated `user`, so every request auto-approved regardless of who it was
     * attributed to. `X-API-User` swaps `req.user` itself before any of that runs, so both attribution
     * and auto-approval genuinely become that person's — no separate low-privilege service account
     * needed, and the same shared `[seerr]` API key keeps working for every other call in this file.
     */
    suspend fun createRequest(
        url: String,
        apiKey: String,
        mediaType: String,
        tmdbId: Int,
        profileId: Int? = null,
        tagIds: List<Int> = emptyList(),
        seerrUserId: Int? = null,
    ): SeerrRequestResult? =
        runCatching {
            val payload = buildJsonObject {
                put("mediaType", mediaType)
                put("mediaId", tmdbId)
                if (mediaType == "tv") put("seasons", "all")
                profileId?.let { put("profileId", it) }
                if (tagIds.isNotEmpty()) put("tags", buildJsonArray { tagIds.forEach { add(it) } })
            }
            val resp = httpPost(base(url) + "/request", apiKey) {
                contentType(ContentType.Application.Json); setBody(payload.toString())
                seerrUserId?.let { header("X-API-User", it.toString()) }
            }
            if (resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK) resp.body<SeerrRequestResult>() else null
        }.getOrNull()

    /**
     * Phase 156 — resolves [jellyfinUserId] to its Seerr account id, provisioning one via Jellyfin
     * import if it doesn't exist yet (never requires the viewer to log into Seerr's own web UI). Tries
     * the direct lookup first since import only returns *newly created* users — a second call for an
     * already-linked account would come back empty, not the existing user. Requires the configured API
     * key's account to hold `MANAGE_USERS` (for the import) and `MANAGE_USERS`/`MANAGE_REQUESTS` (for
     * [createRequest] to accept a `userId` on someone else's behalf). Returns null on any failure —
     * callers fall back to an unattributed request rather than blocking on this.
     */
    suspend fun resolveUserId(url: String, apiKey: String, jellyfinUserId: String): Int? = runCatching {
        val existing = httpGet(base(url) + "/user/jellyfin/$jellyfinUserId", apiKey)
        if (existing.status == HttpStatusCode.OK) return@runCatching existing.body<SeerrUser>().id
        val payload = buildJsonObject { put("jellyfinUserIds", buildJsonArray { add(jellyfinUserId) }) }
        val imported = httpPost(base(url) + "/user/import-from-jellyfin", apiKey) {
            contentType(ContentType.Application.Json); setBody(payload.toString())
        }
        if (imported.status == HttpStatusCode.Created) imported.body<List<SeerrUser>>().firstOrNull()?.id else null
    }.getOrNull()
}
