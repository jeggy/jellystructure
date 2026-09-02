package dev.jellystructure.tmdb

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.ops.SpinLock
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import dev.jellystructure.OutboundHttp
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.TimeSource

/** Cap on [TmdbClient]'s internal 429 retry loop — see the `httpGet` doc comment. */
private const val MAX_429_RETRIES = 5

/**
 * Phase 183 (FR-183-1/FR-183-2) — TMDB publishes no current numeric rate limit (the historical
 * 40-requests-per-10-seconds figure was withdrawn in 2019; what remains is an undocumented per-IP
 * ceiling), so these are a conservative STARTING point, not a researched fact — [TmdbRateLimiter]
 * adjusts the real rate down on every 429 and back up on sustained success, so the effective ceiling is
 * discovered empirically rather than guessed once and left wrong. [OutboundHttp.withPermit] bounds
 * *concurrency* (how many requests may be in flight); this bounds *rate* (how many may START per
 * second) — the two are different things, and OutboundHttp's 64-permit concurrency cap alone allowed a
 * burst approaching 64 requests / (one round-trip latency), far above anything TMDB would tolerate.
 */
private const val TMDB_INITIAL_RATE_PER_SEC = 4.0
private const val TMDB_MIN_RATE_PER_SEC = 0.5
private const val TMDB_MAX_RATE_PER_SEC = 20.0
private const val TMDB_BURST = 10.0
private const val TMDB_SUCCESS_STREAK_TO_RECOVER = 50
private const val RETRY_BASE_BACKOFF_MS = 1_000L
private const val RETRY_MAX_BACKOFF_MS = 20_000L

/** Phase 183 (FR-183-5) — thrown instead of returning the raw 429 response once retries are exhausted,
 *  so a caller's `runCatching { … }.getOrNull()` (the established pattern at every httpGet call site in
 *  this file) can no longer accidentally succeed at deserializing an error body into a false "TMDB has
 *  no data for this" null. Every existing caller already treats a thrown exception as "this field is
 *  unavailable" — this only makes the CAUSE distinguishable in the log, not the caller-visible outcome. */
class TmdbRateLimitExhaustedException(url: String, attempts: Int) :
    Exception("TMDB rate-limited $attempts times, giving up: $url")

/**
 * Phase 183 (FR-183-1/FR-183-2) — a simple token bucket, AIMD-adjusted: halves its rate on a 429
 * (multiplicative decrease, floored) and nudges it back up after a run of consecutive successes
 * (additive increase, capped) — so a genuinely stricter or looser real-world ceiling than the seeded
 * default is found by observation instead of asserted. Guarded by [SpinLock] (not
 * `kotlinx.coroutines.sync.Mutex`) purely for consistency with this codebase's other low-level gates;
 * nothing here is called from a non-suspend context, a plain Mutex would have worked equally well.
 */
private class TmdbRateLimiter {
    private val lock = SpinLock()
    private var tokens = TMDB_BURST
    private var ratePerSec = TMDB_INITIAL_RATE_PER_SEC
    private var lastRefill = TimeSource.Monotonic.markNow()
    private var consecutiveSuccesses = 0

    /** Blocks (via [delay], never busy-spins across the wait) until a token is available. */
    suspend fun acquire() {
        while (true) {
            val waitMs = lock.withLock {
                refillLocked()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    (((1.0 - tokens) / ratePerSec) * 1000).toLong().coerceAtLeast(10L)
                }
            }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    fun onRateLimited() = lock.withLock {
        ratePerSec = (ratePerSec / 2).coerceAtLeast(TMDB_MIN_RATE_PER_SEC)
        consecutiveSuccesses = 0
    }

    fun currentRate(): Double = lock.withLock { ratePerSec }

    fun onSuccess() = lock.withLock {
        consecutiveSuccesses++
        if (consecutiveSuccesses >= TMDB_SUCCESS_STREAK_TO_RECOVER) {
            consecutiveSuccesses = 0
            ratePerSec = (ratePerSec + 1.0).coerceAtMost(TMDB_MAX_RATE_PER_SEC)
        }
    }

    private fun refillLocked() {
        val now = TimeSource.Monotonic.markNow()
        val elapsedSec = (now - lastRefill).inWholeMilliseconds / 1000.0
        if (elapsedSec <= 0.0) return
        tokens = (tokens + elapsedSec * ratePerSec).coerceAtMost(TMDB_BURST)
        lastRefill = now
    }
}

@Serializable
data class TmdbSearchResponse(
    val results: List<TmdbSearchResult> = emptyList(),
)

@Serializable
data class TmdbSearchResult(
    val id: Int,
    val title: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("original_language") val originalLanguage: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    val overview: String = "",
)

@Serializable
data class TmdbMovieDetails(
    val id: Int,
    val title: String,
    @SerialName("original_title") val originalTitle: String = "",
    @SerialName("original_language") val originalLanguage: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

@Serializable
data class TmdbCompany(
    val id: Int,
    val name: String,
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
data class TmdbCompanySearchResponse(
    val results: List<TmdbCompany> = emptyList(),
)

@Serializable
data class TmdbTvSearchResponse(
    val results: List<TmdbTvSearchResult> = emptyList(),
)

@Serializable
data class TmdbTvSearchResult(
    val id: Int,
    val name: String = "",
    @SerialName("first_air_date") val firstAirDate: String = "",
)

@Serializable
data class TmdbTvDetails(
    val id: Int,
    val name: String = "",
    @SerialName("original_name") val originalName: String = "",
    @SerialName("original_language") val originalLanguage: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String = "",
    @SerialName("first_air_date") val firstAirDate: String = "",
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    val genres: List<TmdbGenre> = emptyList(),
    val networks: List<TmdbNetwork> = emptyList(),
)

@Serializable
data class TmdbCastMember(
    val id: Int,
    val name: String = "",
    val character: String = "",
    val order: Int = 0,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbCrewMember(
    val id: Int,
    val name: String = "",
    val job: String = "",
    val department: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbCreditsResponse(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

@Serializable
data class TmdbPersonSearchResult(
    val id: Int,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String = "",
)

@Serializable
data class TmdbPersonSearchResponse(
    val results: List<TmdbPersonSearchResult> = emptyList(),
)

@Serializable
data class TmdbNetwork(
    val id: Int,
    val name: String,
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
data class TmdbTranslationsResponse(
    val translations: List<TmdbTranslation> = emptyList(),
)

/**
 * Localized details together with the priority language that actually produced them — the entry from
 * the caller's priority list whose translation had content (e.g. "en"), or null when no translation
 * matched and TMDB's default/original details were used. Callers record this as resolvedLanguage so
 * the stored language reflects what metadata was really fetched in, not a guess.
 */
data class Localized<T>(val details: T, val language: String?)

@Serializable
data class TmdbTranslation(
    @SerialName("iso_639_1") val languageCode: String = "",
    @SerialName("iso_3166_1") val region: String = "",
    // Phase 184 — the language's own display names, straight from TMDB (not the title/overview content
    // in `data`): `name` is the language's name IN that language (e.g. "Français" for fr), `englishName`
    // is its English name (e.g. "French"). Used by the metadata-language picker's coverage list.
    @SerialName("english_name") val englishName: String = "",
    val name: String = "",
    val data: TmdbTranslationData = TmdbTranslationData(),
)

@Serializable
data class TmdbTranslationData(
    val overview: String = "",
    val title: String = "",
    val name: String = "",
)

/** Phase 184 (FR-184-4) — one row of the metadata-language picker's coverage list. [code] is ISO-639-1;
 *  [englishName]/[nativeName] are null only when TMDB's translations response happened to omit them
 *  (rare — the picker falls back to the bare code). */
@Serializable
data class TmdbLanguageCoverage(
    val code: String,
    val englishName: String? = null,
    val nativeName: String? = null,
    val hasTitle: Boolean = false,
    val hasOverview: Boolean = false,
    val posterCount: Int = 0,
)

@Serializable
data class TmdbEpisodeDetails(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("still_path") val stillPath: String? = null,
    val runtime: Int? = null,
    // R148: episode first-air date (ISO yyyy-MM-dd) — already returned by TMDB's episode endpoint.
    @SerialName("air_date") val airDate: String? = null,
)

// --- Phase 47: TMDB images API (candidate galleries) ---
@Serializable
data class TmdbImagesResponse(
    val posters: List<TmdbImage> = emptyList(),
    val backdrops: List<TmdbImage> = emptyList(),
    val logos: List<TmdbImage> = emptyList(),
    val stills: List<TmdbImage> = emptyList(),
)

@Serializable
data class TmdbImage(
    @SerialName("file_path") val filePath: String,
    @SerialName("iso_639_1") val languageCode: String? = null, // null = textless / no-language
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
)

// --- Phase 76: aggregate_credits for TV series (returns total_episode_count per actor) ---
@Serializable
data class TmdbAggregateRole(
    val character: String = "",
    @SerialName("episode_count") val episodeCount: Int = 0,
)

@Serializable
data class TmdbAggregateCastMember(
    val id: Int,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0,
    @SerialName("total_episode_count") val totalEpisodeCount: Int = 0,
    val roles: List<TmdbAggregateRole> = emptyList(),
)

@Serializable
data class TmdbAggregateJob(
    val job: String = "",
    @SerialName("episode_count") val episodeCount: Int = 0,
)

@Serializable
data class TmdbAggregateCrewMember(
    val id: Int,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val department: String = "",
    val jobs: List<TmdbAggregateJob> = emptyList(),
    @SerialName("total_episode_count") val totalEpisodeCount: Int = 0,
)

@Serializable
data class TmdbAggregateCreditsResponse(
    val cast: List<TmdbAggregateCastMember> = emptyList(),
    val crew: List<TmdbAggregateCrewMember> = emptyList(),
)

// --- Phase 76: per-episode credits (guest stars + crew from episode endpoint) ---
@Serializable
data class TmdbEpisodeCastMember(
    val id: Int,
    val name: String = "",
    val character: String = "",
    val order: Int = 0,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbEpisodeCreditsResponse(
    @SerialName("guest_stars") val guestStars: List<TmdbEpisodeCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

// --- Keywords (used as the non-JS tag source on TMDB re-pull). Movie and TV use different
// field names for the same shape: movies nest under `keywords`, TV under `results`. ---
@Serializable
data class TmdbKeyword(val id: Int, val name: String)

@Serializable
data class TmdbMovieKeywordsResponse(val keywords: List<TmdbKeyword> = emptyList())

@Serializable
data class TmdbTvKeywordsResponse(val results: List<TmdbKeyword> = emptyList())

@Serializable
data class TmdbExternalIds(
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
)

// --- R167: /find/{external_id} (tvdb -> tmdb resolution for not-held Upcoming series) ---
@Serializable
data class TmdbFindResult(val id: Int)

@Serializable
data class TmdbFindResponse(
    @SerialName("tv_results") val tvResults: List<TmdbFindResult> = emptyList(),
)

// --- Phase 130: /videos (trailer ingest) ---
@Serializable
data class TmdbVideosResponse(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbVideo(
    val site: String = "",
    val key: String = "",
    val name: String = "",
    val type: String = "",
    val official: Boolean = false,
    @SerialName("iso_639_1") val language: String = "",
    @SerialName("published_at") val publishedAt: String = "",
)

@Serializable
data class VimeoOembedResponse(
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

// --- Phase 106: age-rating certifications. Movies: /release_dates (per-country, several release
// `type`s can carry different certification strings — theatrical types preferred). TV: /content_ratings
// (flat, one rating per country, no type/date — TMDB has no episode-level ratings). ---
@Serializable
data class TmdbReleaseDateEntry(
    val certification: String = "",
    val type: Int = 0,
)

@Serializable
data class TmdbReleaseDatesCountry(
    @SerialName("iso_3166_1") val country: String,
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDateEntry> = emptyList(),
)

@Serializable
data class TmdbReleaseDatesResponse(val results: List<TmdbReleaseDatesCountry> = emptyList())

@Serializable
data class TmdbContentRatingEntry(
    @SerialName("iso_3166_1") val country: String,
    val rating: String = "",
)

@Serializable
data class TmdbContentRatingsResponse(val results: List<TmdbContentRatingEntry> = emptyList())

class TmdbClient(
    private val configStore: ConfigStore,
    private val baseUrl: String = "https://api.themoviedb.org/3",
) {
    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    // Phase 182 (FR-182-2): plain mutableMapOf, hit from hundreds of concurrent per-episode coroutines
    // across the real 4-thread scan pool with NO synchronization — the same unsynchronized-shared-
    // mutable-state shape MediaStore's caches had, and hotter here (a per-episode call site, not a
    // per-item one). Guarded by [cacheLock], a SpinLock so it works from both suspend call sites here.
    private val detailsCache = mutableMapOf<Int, TmdbMovieDetails>()

    // Bug fix: getRegionedLanguageTags returns series-level data (the show's /translations) but used
    // to be re-fetched from scratch for every episode that missed its primary language — for a
    // 300-episode series that's up to 300 redundant identical requests in one pull_tmdb run. Cached
    // per (tmdbId, isMovie), same idiom as [detailsCache].
    private val regionTagsCache = mutableMapOf<Pair<Int, Boolean>, Map<String, String>>()

    private val cacheLock = SpinLock()

    // Phase 183 (FR-183-1) — one bucket per TmdbClient instance (one TMDB host), acquired before every
    // outbound call below, in addition to (not instead of) OutboundHttp's concurrency permit.
    private val rateLimiter = TmdbRateLimiter()

    private fun apiKey(): String = configStore.current.apiKeys.tmdbV3Key

    /**
     * Bug fix: every one of the ~18 call sites below used to handle a 429 itself, via
     * `if (status == TooManyRequests) { delay(3000); return theSameFunction(sameArgs) }` — an
     * unbounded, silent, per-call-site recursive retry. A library with even a handful of
     * long-running TV series (300+ episodes each is common for reality shows/sitcoms) makes a
     * `pull_tmdb` "scope=all" run issue thousands of sequential episode-detail requests; once TMDB
     * starts rate-limiting under that volume, dozens of in-flight coroutines can end up retrying
     * this way *indefinitely* in lockstep, with zero log trace (the retry was silent) — from the
     * outside this looked exactly like the whole backend hanging. Centralizing the retry here (a)
     * caps it at [MAX_429_RETRIES] instead of forever, and (b) finally logs every occurrence, so a
     * future incident shows "TMDB rate-limited" in the activity log instead of just going quiet.
     *
     * Phase 183 (FR-183-1/FR-183-2) — three further fixes to the SAME bug, evidenced live by a supplied
     * log where 18 requests all hit 429 within one second, all retrying at an identical flat 3s delay
     * (converging, not scattering): (a) [rateLimiter] paces the request RATE, not just concurrency — the
     * actual mechanism the flat-3s retry alone could never fix, since every retry just re-entered the
     * same burst; (b) the retry honours the response's own `Retry-After` header when TMDB sends one,
     * falling back to exponential backoff (not flat) otherwise; (c) every wait carries FULL JITTER
     * (`Random.nextLong`), which is the direct fix for "18 requests retrying at the exact same instant."
     * Exhaustion now THROWS [TmdbRateLimitExhaustedException] instead of returning the raw error
     * response — see that class's own doc for why silently returning it was a real data-loss bug.
     */
    private suspend fun httpGet(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        var attempt = 0
        while (true) {
            rateLimiter.acquire()
            val response = OutboundHttp.withPermit { http.get(url, block) }
            if (response.status != HttpStatusCode.TooManyRequests) {
                rateLimiter.onSuccess()
                return response
            }
            rateLimiter.onRateLimited()
            attempt++
            if (attempt > MAX_429_RETRIES) {
                Logger.warn("TMDB rate-limited (429) $attempt times, giving up: $url", "tmdb")
                throw TmdbRateLimitExhaustedException(url, attempt)
            }
            val retryAfterMs = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000L)
            val backoffMs = retryAfterMs
                ?: (RETRY_BASE_BACKOFF_MS * (1L shl (attempt - 1))).coerceAtMost(RETRY_MAX_BACKOFF_MS)
            // Full jitter (not "backoff ± a bit"): a wait uniformly random in [0, backoffMs) — the
            // specific fix for a batch that got 429'd together retrying together, which the log evidence
            // (18 identical timestamps) showed the old flat delay(3000) produced every time.
            val jitteredMs = Random.nextLong(backoffMs.coerceAtLeast(1L))
            Logger.warn(
                "TMDB rate-limited (429), retrying in ${jitteredMs}ms (attempt $attempt/$MAX_429_RETRIES, " +
                    "rate now ${rateLimiter.currentRate()}/s): $url",
                "tmdb",
            )
            delay(jitteredMs)
        }
    }

    suspend fun searchMovie(title: String, year: Int?): TmdbSearchResult? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/search/movie") {
                parameter("api_key", key)
                parameter("query", title)
                if (year != null) parameter("year", year)
            }
            response.body<TmdbSearchResponse>().results.firstOrNull()
        }
        if (result.isFailure) Logger.warn("TMDB search failed for '$title': ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    suspend fun getMovieDetails(tmdbId: Int, language: String? = null): TmdbMovieDetails? {
        if (language == null) cacheLock.withLock { detailsCache[tmdbId] }?.let { return it }
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/movie/$tmdbId") {
                parameter("api_key", key)
                if (!language.isNullOrBlank()) parameter("language", language)
            }
            val details = response.body<TmdbMovieDetails>()
            if (language == null) cacheLock.withLock { detailsCache[tmdbId] = details }
            details
        }
        if (result.isFailure) Logger.warn("TMDB details failed for id=$tmdbId lang=$language: ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    // Try each language in priority order; use the first that has a non-empty overview OR (for
    // the regional retry) a non-empty title. When a bare two-letter code yields an empty overview,
    // retry with the region-qualified tag from /translations (e.g. en → en-US, fo → fo-FO) so a
    // movie translated only under a regional variant is still found. The regional result is accepted
    // even with a blank overview as long as a localized title is present — some minority-language
    // translations supply only a title; the overview stays blank in the NFO rather than falling back
    // to English.
    //
    // [acceptTitleOnly]: when true, the FIRST language in the priority list is also accepted when it
    // returns a non-blank title even with a blank overview. Pass true when the caller has an explicit
    // user-chosen language override — the user chose that language knowing an overview might not exist.
    suspend fun getMovieDetailsLocalized(
        tmdbId: Int,
        languages: List<String>,
        acceptTitleOnly: Boolean = false,
    ): Localized<TmdbMovieDetails>? {
        var regionTags: Map<String, String>? = null
        for ((idx, lang) in languages.withIndex()) {
            val d = getMovieDetails(tmdbId, lang)
            val accepted = d != null && (d.overview.isNotBlank() ||
                (acceptTitleOnly && idx == 0 && d.title.isNotBlank()))
            if (d != null && accepted) return Localized(d, lang)
            if (regionTags == null) regionTags = getRegionedLanguageTags(tmdbId, isMovie = true)
            val regional = regionTags[lang.lowercase()]
            if (regional != null && !regional.equals(lang, ignoreCase = true)) {
                val dr = getMovieDetails(tmdbId, regional)
                if (dr != null && (dr.overview.isNotBlank() || dr.title.isNotBlank())) return Localized(dr, lang)
            }
        }
        return getMovieDetails(tmdbId)?.let { Localized(it, null) }
    }

    suspend fun searchTv(title: String, year: Int?): TmdbTvSearchResult? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/search/tv") {
                parameter("api_key", key)
                parameter("query", title)
                if (year != null) parameter("first_air_date_year", year)
            }
            response.body<TmdbTvSearchResponse>().results.firstOrNull()
        }
        if (result.isFailure) Logger.warn("TMDB TV search failed for '$title': ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    suspend fun getTvDetails(tmdbId: Int, language: String? = null): TmdbTvDetails? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/tv/$tmdbId") {
                parameter("api_key", key)
                if (!language.isNullOrBlank()) parameter("language", language)
            }
            response.body<TmdbTvDetails>()
        }
        if (result.isFailure) Logger.warn("TMDB TV details failed for id=$tmdbId lang=$language: ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    /** Phase 76 — fetch all useful external ids (IMDb, TheTVDB) for a movie or TV series. */
    suspend fun getExternalIds(tmdbId: Int, isMovie: Boolean): TmdbExternalIds? {
        val key = apiKey(); if (key.isBlank()) return null
        val path = if (isMovie) "movie/$tmdbId/external_ids" else "tv/$tmdbId/external_ids"
        return runCatching {
            val response = httpGet("$baseUrl/$path") { parameter("api_key", key) }
            if (response.status != HttpStatusCode.OK) return null
            response.body<TmdbExternalIds>()
        }.getOrNull()
    }

    /** Phase 56 — bridge a TMDB tv id to its TheTVDB id (Sonarr is keyed by tvdbId, not tmdbId). */
    suspend fun getTvTvdbId(tmdbId: Int): Int? = getExternalIds(tmdbId, isMovie = false)?.tvdbId

    /** R167 — the reverse of [getTvTvdbId]: resolve a TheTVDB id (Sonarr calendar) to its TMDB id,
     *  via TMDB's `/find` endpoint, so a not-held series can still get a live TMDB enrichment. */
    suspend fun findTvByTvdbId(tvdbId: Int): Int? {
        val key = apiKey(); if (key.isBlank()) return null
        return runCatching {
            val response = httpGet("$baseUrl/find/$tvdbId") {
                parameter("api_key", key)
                parameter("external_source", "tvdb_id")
            }
            if (response.status != HttpStatusCode.OK) return null
            response.body<TmdbFindResponse>().tvResults.firstOrNull()?.id
        }.getOrNull()
    }

    /** R63 — top-5 cast members for a movie (by `order`). Returns emptyList on any failure. */
    suspend fun getMovieCredits(tmdbId: Int): List<TmdbCastMember> {
        val key = apiKey(); if (key.isBlank()) return emptyList()
        return runCatching {
            val r = httpGet("$baseUrl/movie/$tmdbId/credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return emptyList()
            r.body<TmdbCreditsResponse>().cast.sortedBy { it.order }.take(5)
        }.getOrElse { Logger.warn("TMDB movie credits failed tmdbId=$tmdbId: ${it.message}"); emptyList() }
    }

    /** R63 — top-5 cast members for a TV series (by `order`). Returns emptyList on any failure. */
    suspend fun getTvCredits(tmdbId: Int): List<TmdbCastMember> {
        val key = apiKey(); if (key.isBlank()) return emptyList()
        return runCatching {
            val r = httpGet("$baseUrl/tv/$tmdbId/credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return emptyList()
            r.body<TmdbCreditsResponse>().cast.sortedBy { it.order }.take(5)
        }.getOrElse { Logger.warn("TMDB tv credits failed tmdbId=$tmdbId: ${it.message}"); emptyList() }
    }

    /** Phase 75 — full cast + crew for a movie. */
    suspend fun getMovieFullCredits(tmdbId: Int): TmdbCreditsResponse {
        val key = apiKey(); if (key.isBlank()) return TmdbCreditsResponse()
        return runCatching {
            val r = httpGet("$baseUrl/movie/$tmdbId/credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return TmdbCreditsResponse()
            r.body<TmdbCreditsResponse>()
        }.getOrElse { Logger.warn("TMDB movie full credits failed tmdbId=$tmdbId: ${it.message}"); TmdbCreditsResponse() }
    }

    /** Phase 76 — aggregate_credits for a TV series (includes total_episode_count per cast member). */
    suspend fun getTvAggregateCredits(tmdbId: Int): TmdbAggregateCreditsResponse {
        val key = apiKey(); if (key.isBlank()) return TmdbAggregateCreditsResponse()
        return runCatching {
            val r = httpGet("$baseUrl/tv/$tmdbId/aggregate_credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return TmdbAggregateCreditsResponse()
            r.body<TmdbAggregateCreditsResponse>()
        }.getOrElse { Logger.warn("TMDB aggregate_credits failed tmdbId=$tmdbId: ${it.message}"); TmdbAggregateCreditsResponse() }
    }

    /**
     * Phase 80 — per-season aggregate_credits. Each cast member's `totalEpisodeCount` here is the
     * count of episodes they appear in **within this season** (TMDB scopes it to the season).
     * A cast member absent from the season is simply not in the response.
     */
    suspend fun getTvSeasonAggregateCredits(seriesId: Int, season: Int): TmdbAggregateCreditsResponse {
        val key = apiKey(); if (key.isBlank()) return TmdbAggregateCreditsResponse()
        return runCatching {
            val r = httpGet("$baseUrl/tv/$seriesId/season/$season/aggregate_credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return TmdbAggregateCreditsResponse()
            r.body<TmdbAggregateCreditsResponse>()
        }.getOrElse { Logger.warn("TMDB season aggregate_credits failed s$season series=$seriesId: ${it.message}"); TmdbAggregateCreditsResponse() }
    }

    /** Phase 76 — per-episode credits (guest stars + crew). */
    suspend fun getEpisodeCredits(seriesId: Int, season: Int, episode: Int): TmdbEpisodeCreditsResponse {
        val key = apiKey(); if (key.isBlank()) return TmdbEpisodeCreditsResponse()
        return runCatching {
            val r = httpGet("$baseUrl/tv/$seriesId/season/$season/episode/$episode/credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return TmdbEpisodeCreditsResponse()
            r.body<TmdbEpisodeCreditsResponse>()
        }.getOrElse { Logger.warn("TMDB episode credits failed s${season}e${episode} series=$seriesId: ${it.message}"); TmdbEpisodeCreditsResponse() }
    }

    /** Phase 75 — search TMDB for people by name. */
    suspend fun searchPeople(query: String): List<TmdbPersonSearchResult> {
        val key = apiKey(); if (key.isBlank()) return emptyList()
        return runCatching {
            val r = httpGet("$baseUrl/search/person") {
                parameter("api_key", key)
                parameter("query", query)
            }
            if (r.status != HttpStatusCode.OK) return emptyList()
            r.body<TmdbPersonSearchResponse>().results.take(10)
        }.getOrElse { Logger.warn("TMDB person search failed '$query': ${it.message}"); emptyList() }
    }

    suspend fun getTvDetailsLocalized(
        tmdbId: Int,
        languages: List<String>,
        acceptTitleOnly: Boolean = false,
    ): Localized<TmdbTvDetails>? {
        var regionTags: Map<String, String>? = null
        for ((idx, lang) in languages.withIndex()) {
            val d = getTvDetails(tmdbId, lang)
            // When acceptTitleOnly is set, the first language (the explicit user override) is
            // accepted even with a blank overview — a Faroese show may have no contributed
            // Faroese overview on TMDB; the overview stays null and the UI shows a warning.
            val accepted = d != null && (d.overview.isNotBlank() ||
                (acceptTitleOnly && idx == 0 && d.name.isNotBlank()))
            if (d != null && accepted) return Localized(d, lang)
            if (regionTags == null) regionTags = getRegionedLanguageTags(tmdbId, isMovie = false)
            val regional = regionTags[lang.lowercase()]
            if (regional != null && !regional.equals(lang, ignoreCase = true)) {
                val dr = getTvDetails(tmdbId, regional)
                // Accept regional result even with a blank overview if it has a localized title —
                // minority-language translations (e.g. fo-FO) often supply only the name/title.
                if (dr != null && (dr.overview.isNotBlank() || dr.name.isNotBlank())) return Localized(dr, lang)
            }
        }
        return getTvDetails(tmdbId)?.let { Localized(it, null) }
    }

    suspend fun getTranslationLanguages(tmdbId: Int, isMovie: Boolean): List<String> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        // Fetch translations and details in parallel (coroutines run sequentially here but both are fast).
        // Details give us original_language — for original-language shows (e.g. a Faroese series) TMDB
        // does not list the original language as a "translation", so it would never appear in the
        // translations list; we must add it explicitly.
        val originalLang: String? = runCatching {
            if (isMovie) getMovieDetails(tmdbId)?.originalLanguage
            else getTvDetails(tmdbId)?.originalLanguage
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val path = if (isMovie) "movie/$tmdbId/translations" else "tv/$tmdbId/translations"
        val fromTranslations = runCatching {
            val response = httpGet("$baseUrl/$path") {
                parameter("api_key", key)
            }
            response.body<TmdbTranslationsResponse>().translations
                // Include a language when TMDB has ANY localized content (name/title or overview).
                // Some minority languages (e.g. fo-FO) only have a translated title with no overview;
                // excluding those left Faroese out of the picker entirely.
                .filter { t ->
                    t.languageCode.isNotBlank() &&
                        (t.data.overview.isNotBlank() || t.data.name.isNotBlank() || t.data.title.isNotBlank())
                }
                .map { it.languageCode }
        }.getOrElse { emptyList() }

        if (fromTranslations.isEmpty() && originalLang == null) {
            Logger.warn("TMDB translations empty for tmdbId=$tmdbId", "tmdb")
        }
        // original_language is always fetchable (TMDB returns it natively) — prepend it so the
        // resolver prefers the original over contributed translations when both are available.
        return (listOfNotNull(originalLang) + fromTranslations).distinct()
    }

    /**
     * Phase 184 (FR-184-4) — the picker's coverage list: every language TMDB actually holds something
     * for, each with enough to judge it (title? overview? how many posters?) rather than just the bare
     * code [getTranslationLanguages] returns. Two calls, same shape [getTranslationLanguages] already
     * makes (`/translations` + the original-language backfill) plus the images call the Artwork tab
     * already fetches for per-language poster counts — no third TMDB endpoint invented for this.
     */
    suspend fun getTranslationCoverage(tmdbId: Int, isMovie: Boolean): List<TmdbLanguageCoverage> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        val originalLang: String? = runCatching {
            if (isMovie) getMovieDetails(tmdbId)?.originalLanguage else getTvDetails(tmdbId)?.originalLanguage
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val path = if (isMovie) "movie/$tmdbId/translations" else "tv/$tmdbId/translations"
        val translations = runCatching {
            httpGet("$baseUrl/$path") { parameter("api_key", key) }
                .body<TmdbTranslationsResponse>().translations.filter { it.languageCode.isNotBlank() }
        }.getOrElse { emptyList() }

        val images = if (isMovie) getMovieImages(tmdbId) else getTvImages(tmdbId)
        val posterCounts = images?.posters.orEmpty()
            .mapNotNull { it.languageCode }
            .groupingBy { it }.eachCount()

        val byCode = LinkedHashMap<String, TmdbLanguageCoverage>()
        // Original language first (may have no `translations` entry of its own — TMDB doesn't list it
        // as a translation of itself — but it's always a real, selectable language for the title).
        originalLang?.let { code ->
            byCode[code] = TmdbLanguageCoverage(code = code, posterCount = posterCounts[code] ?: 0)
        }
        for (t in translations) {
            val code = t.languageCode
            val hasTitle = t.data.title.isNotBlank() || t.data.name.isNotBlank()
            val hasOverview = t.data.overview.isNotBlank()
            if (!hasTitle && !hasOverview && code != originalLang) continue  // matches getTranslationLanguages' own filter
            val existing = byCode[code]
            byCode[code] = TmdbLanguageCoverage(
                code = code,
                englishName = t.englishName.takeIf { it.isNotBlank() } ?: existing?.englishName,
                nativeName = t.name.takeIf { it.isNotBlank() } ?: existing?.nativeName,
                hasTitle = hasTitle || existing?.hasTitle == true,
                hasOverview = hasOverview || existing?.hasOverview == true,
                posterCount = posterCounts[code] ?: existing?.posterCount ?: 0,
            )
        }
        return byCode.values.toList()
    }

    /** Returns a map of language code → localized title for all available languages. */
    suspend fun getTranslatedTitles(tmdbId: Int, isMovie: Boolean): Map<String, String> {
        val key = apiKey()
        if (key.isBlank()) return emptyMap()
        val path = if (isMovie) "movie/$tmdbId/translations" else "tv/$tmdbId/translations"
        val result = runCatching {
            val response = httpGet("$baseUrl/$path") {
                parameter("api_key", key)
            }
            response.body<TmdbTranslationsResponse>().translations
                .filter { it.languageCode.isNotBlank() }
                .mapNotNull { t ->
                    val localTitle = if (isMovie) t.data.title else t.data.name
                    if (localTitle.isNotBlank()) t.languageCode to localTitle else null
                }
                .toMap()
        }
        if (result.isFailure) Logger.warn("TMDB translated titles failed tmdbId=$tmdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrElse { emptyMap() }
    }

    /**
     * Region-qualified language tags for every translation that actually carries an overview, keyed
     * by the lowercased ISO 639-1 code — e.g. {"en" -> "en-US", "pt" -> "pt-BR"}. TMDB's details
     * endpoint can return an empty overview for a bare two-letter `language` when the only translation
     * is a regional variant (e.g. en-US, not plain en); callers retry the details fetch with this
     * region-qualified tag. First translation (with an overview) wins per language.
     */
    suspend fun getRegionedLanguageTags(tmdbId: Int, isMovie: Boolean): Map<String, String> {
        val cacheKey = tmdbId to isMovie
        cacheLock.withLock { regionTagsCache[cacheKey] }?.let { return it }
        val key = apiKey()
        if (key.isBlank()) return emptyMap()
        val path = if (isMovie) "movie/$tmdbId/translations" else "tv/$tmdbId/translations"
        val result = runCatching {
            val response = httpGet("$baseUrl/$path") {
                parameter("api_key", key)
            }
            val map = LinkedHashMap<String, String>()
            for (t in response.body<TmdbTranslationsResponse>().translations) {
                val lang = t.languageCode.lowercase()
                val hasContent = t.data.overview.isNotBlank() || t.data.name.isNotBlank() || t.data.title.isNotBlank()
                if (lang.isBlank() || t.region.isBlank() || !hasContent) continue
                map.getOrPut(lang) { "$lang-${t.region.uppercase()}" }
            }
            map
        }
        if (result.isFailure) {
            Logger.warn("TMDB regioned tags failed tmdbId=$tmdbId: ${result.exceptionOrNull()?.message}")
            return emptyMap()
        }
        val tags = result.getOrThrow()
        cacheLock.withLock { regionTagsCache[cacheKey] = tags }
        return tags
    }

    suspend fun searchMovieAll(query: String, year: Int?): List<TmdbSearchResult> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        val result = runCatching {
            val response = httpGet("$baseUrl/search/movie") {
                parameter("api_key", key)
                parameter("query", query)
                if (year != null) parameter("year", year)
            }
            response.body<TmdbSearchResponse>().results.take(10)
        }
        if (result.isFailure) Logger.warn("TMDB movie search failed for '$query': ${result.exceptionOrNull()?.message}")
        return result.getOrElse { emptyList() }
    }

    suspend fun searchTvAll(query: String, year: Int?): List<TmdbTvSearchResult> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        val result = runCatching {
            val response = httpGet("$baseUrl/search/tv") {
                parameter("api_key", key)
                parameter("query", query)
                if (year != null) parameter("first_air_date_year", year)
            }
            response.body<TmdbTvSearchResponse>().results.take(10)
        }
        if (result.isFailure) Logger.warn("TMDB TV search failed for '$query': ${result.exceptionOrNull()?.message}")
        return result.getOrElse { emptyList() }
    }

    suspend fun searchCompany(name: String): TmdbCompany? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/search/company") {
                parameter("api_key", key)
                parameter("query", name)
            }
            response.body<TmdbCompanySearchResponse>().results.firstOrNull()
        }
        if (result.isFailure) Logger.warn("TMDB company search failed for '$name': ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    /** Phase 138 — full result list (vs. [searchCompany]'s first-match) for the admin Request-tab
     *  studio picker's live search. */
    suspend fun searchCompanies(query: String): List<TmdbCompany> {
        val key = apiKey()
        if (key.isBlank() || query.isBlank()) return emptyList()
        val result = runCatching {
            val response = httpGet("$baseUrl/search/company") {
                parameter("api_key", key)
                parameter("query", query)
            }
            response.body<TmdbCompanySearchResponse>().results.take(10)
        }
        if (result.isFailure) Logger.warn("TMDB company search failed for '$query': ${result.exceptionOrNull()?.message}")
        return result.getOrElse { emptyList() }
    }

    suspend fun getEpisodeDetails(seriesId: Int, season: Int, episode: Int, language: String? = null): TmdbEpisodeDetails? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/tv/$seriesId/season/$season/episode/$episode") {
                parameter("api_key", key)
                if (!language.isNullOrBlank()) parameter("language", language)
            }
            if (response.status.value == 404) return null
            response.body<TmdbEpisodeDetails>()
        }
        if (result.isFailure) Logger.warn("TMDB episode details failed for series=$seriesId s${season}e${episode} lang=$language: ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    /**
     * Like [getEpisodeDetails] but walks [languages] in priority order with the same regional-retry
     * logic as [getTvDetailsLocalized]: if bare `fo` returns no name/overview, look up `fo-FO` from
     * the series /translations and retry. Falls back to no-language (TMDB default) when exhausted.
     */
    suspend fun getEpisodeDetailsLocalized(
        seriesId: Int,
        season: Int,
        episode: Int,
        languages: List<String>,
    ): TmdbEpisodeDetails? {
        var regionTags: Map<String, String>? = null
        for (lang in languages) {
            val d = getEpisodeDetails(seriesId, season, episode, lang)
            if (d != null && (d.name.isNotBlank() || d.overview.isNotBlank())) return d
            if (regionTags == null) regionTags = getRegionedLanguageTags(seriesId, isMovie = false)
            val regional = regionTags[lang.lowercase()]
            if (regional != null && !regional.equals(lang, ignoreCase = true)) {
                val dr = getEpisodeDetails(seriesId, season, episode, regional)
                if (dr != null && (dr.name.isNotBlank() || dr.overview.isNotBlank())) return dr
            }
        }
        return getEpisodeDetails(seriesId, season, episode)
    }

    // --- Phase 47: image candidate galleries. No `language` param so TMDB returns every
    // available image across all languages, including textless (iso_639_1 = null). ---
    private suspend fun getImages(path: String): TmdbImagesResponse? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/$path/images") {
                parameter("api_key", key)
            }
            if (response.status.value == 404) return null
            response.body<TmdbImagesResponse>()
        }
        if (result.isFailure) Logger.warn("TMDB images failed for $path: ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    suspend fun getMovieImages(tmdbId: Int): TmdbImagesResponse? = getImages("movie/$tmdbId")
    suspend fun getTvImages(tmdbId: Int): TmdbImagesResponse? = getImages("tv/$tmdbId")

    // --- TMDB keywords → non-JS tags. Not language-localized; returns canonical names. ---
    suspend fun getMovieKeywords(tmdbId: Int): List<String> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        val result = runCatching {
            val response = httpGet("$baseUrl/movie/$tmdbId/keywords") { parameter("api_key", key) }
            if (response.status.value == 404) return emptyList()
            response.body<TmdbMovieKeywordsResponse>().keywords.map { it.name }
        }
        if (result.isFailure) Logger.warn("TMDB movie keywords failed for id=$tmdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrDefault(emptyList())
    }

    suspend fun getTvKeywords(tmdbId: Int): List<String> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        val result = runCatching {
            val response = httpGet("$baseUrl/tv/$tmdbId/keywords") { parameter("api_key", key) }
            if (response.status.value == 404) return emptyList()
            response.body<TmdbTvKeywordsResponse>().results.map { it.name }
        }
        if (result.isFailure) Logger.warn("TMDB tv keywords failed for id=$tmdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrDefault(emptyList())
    }
    suspend fun getSeasonImages(seriesId: Int, season: Int): TmdbImagesResponse? =
        getImages("tv/$seriesId/season/$season")
    suspend fun getEpisodeImages(seriesId: Int, season: Int, episode: Int): TmdbImagesResponse? =
        getImages("tv/$seriesId/season/$season/episode/$episode")

    // Preference order for a movie's release `type` when several entries carry a certification for
    // the same country: theatrical (3) first, then digital/physical/limited-theatrical, TV, premiere.
    private val releaseTypePreference = listOf(3, 4, 5, 2, 6, 1)

    /** Phase 106: per-country certification map for a movie (uppercase ISO-3166-1 → code), picking the
     *  best release-type entry per country. Best-effort — empty map on any failure/missing key. */
    suspend fun getMovieCertifications(tmdbId: Int): Map<String, String> {
        val key = apiKey()
        if (key.isBlank()) return emptyMap()
        val result = runCatching {
            val response = httpGet("$baseUrl/movie/$tmdbId/release_dates") { parameter("api_key", key) }
            if (response.status.value == 404) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (c in response.body<TmdbReleaseDatesResponse>().results) {
                val byType = c.releaseDates.groupBy { it.type }
                val code = releaseTypePreference.firstNotNullOfOrNull { t ->
                    byType[t]?.firstOrNull { it.certification.isNotBlank() }?.certification
                } ?: c.releaseDates.firstOrNull { it.certification.isNotBlank() }?.certification
                if (!code.isNullOrBlank()) map[c.country.uppercase()] = code
            }
            map
        }
        if (result.isFailure) Logger.warn("TMDB movie release_dates failed for id=$tmdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrDefault(emptyMap())
    }

    /** Phase 106: per-country certification map for a TV series (uppercase ISO-3166-1 → rating).
     *  Series-level only — TMDB has no episode-level ratings. Best-effort. */
    suspend fun getTvCertifications(tmdbId: Int): Map<String, String> {
        val key = apiKey()
        if (key.isBlank()) return emptyMap()
        val result = runCatching {
            val response = httpGet("$baseUrl/tv/$tmdbId/content_ratings") { parameter("api_key", key) }
            if (response.status.value == 404) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (e in response.body<TmdbContentRatingsResponse>().results) {
                if (e.rating.isNotBlank()) map[e.country.uppercase()] = e.rating
            }
            map
        }
        if (result.isFailure) Logger.warn("TMDB tv content_ratings failed for id=$tmdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrDefault(emptyMap())
    }

    // --- Phase 130: one official trailer per title (YouTube/Vimeo). Deterministic selection —
    // Trailer preferred over Teaser, then official, then original-language/English, then newest.
    private fun selectTrailerVideo(videos: List<TmdbVideo>, originalLanguage: String): TmdbVideo? {
        val usable = videos.filter { it.site.equals("YouTube", ignoreCase = true) || it.site.equals("Vimeo", ignoreCase = true) }
        val byType = usable.filter { it.type == "Trailer" }.ifEmpty { usable.filter { it.type == "Teaser" } }
        if (byType.isEmpty()) return null
        return byType.sortedWith(
            compareByDescending<TmdbVideo> { it.official }
                .thenByDescending { it.language == originalLanguage }
                .thenByDescending { it.language == "en" }
                .thenByDescending { it.publishedAt }
        ).firstOrNull()
    }

    suspend fun getMovieVideos(tmdbId: Int, originalLanguage: String): TmdbVideo? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/movie/$tmdbId/videos") { parameter("api_key", key) }
            if (response.status.value == 404) return null
            selectTrailerVideo(response.body<TmdbVideosResponse>().results, originalLanguage)
        }
        if (result.isFailure) Logger.warn("TMDB movie videos failed for id=$tmdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    suspend fun getTvVideos(tmdbId: Int, originalLanguage: String): TmdbVideo? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/tv/$tmdbId/videos") { parameter("api_key", key) }
            if (response.status.value == 404) return null
            selectTrailerVideo(response.body<TmdbVideosResponse>().results, originalLanguage)
        }
        if (result.isFailure) Logger.warn("TMDB tv videos failed for id=$tmdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    /** Phase 130: Vimeo has no key-derivable thumbnail URL (unlike YouTube's `img.youtube.com/vi/{key}`),
     *  so resolve it once at ingest via Vimeo's public oEmbed endpoint. Best-effort — null on any failure,
     *  the admin card then falls back to a generic play-glyph tile. No render-time external calls. */
    suspend fun resolveVimeoThumb(videoKey: String): String? {
        val result = runCatching {
            val response = httpGet("https://vimeo.com/api/oembed.json") { parameter("url", "https://vimeo.com/$videoKey") }
            if (response.status.value != 200) return null
            response.body<VimeoOembedResponse>().thumbnailUrl
        }
        if (result.isFailure) Logger.warn("Vimeo oEmbed failed for key=$videoKey: ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }
}
