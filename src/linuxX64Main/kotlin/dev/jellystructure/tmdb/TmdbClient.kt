package dev.jellystructure.tmdb

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import dev.jellystructure.OutboundHttp
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val data: TmdbTranslationData = TmdbTranslationData(),
)

@Serializable
data class TmdbTranslationData(
    val overview: String = "",
    val title: String = "",
    val name: String = "",
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
    private val http = HttpClient(Curl) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis  = 60_000
            requestTimeoutMillis = 60_000
        }
    }

    private val detailsCache = mutableMapOf<Int, TmdbMovieDetails>()

    private fun apiKey(): String = configStore.current.apiKeys.tmdbV3Key

    private suspend fun httpGet(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        OutboundHttp.withPermit { http.get(url, block) }

    suspend fun searchMovie(title: String, year: Int?): TmdbSearchResult? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/search/movie") {
                parameter("api_key", key)
                parameter("query", title)
                if (year != null) parameter("year", year)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return searchMovie(title, year)
            }
            response.body<TmdbSearchResponse>().results.firstOrNull()
        }
        if (result.isFailure) Logger.warn("TMDB search failed for '$title': ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    suspend fun getMovieDetails(tmdbId: Int, language: String? = null): TmdbMovieDetails? {
        if (language == null) detailsCache[tmdbId]?.let { return it }
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/movie/$tmdbId") {
                parameter("api_key", key)
                if (!language.isNullOrBlank()) parameter("language", language)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getMovieDetails(tmdbId, language)
            }
            val details = response.body<TmdbMovieDetails>()
            if (language == null) detailsCache[tmdbId] = details
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return searchTv(title, year)
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getTvDetails(tmdbId, language)
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getTranslationLanguages(tmdbId, isMovie)
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

    /** Returns a map of language code → localized title for all available languages. */
    suspend fun getTranslatedTitles(tmdbId: Int, isMovie: Boolean): Map<String, String> {
        val key = apiKey()
        if (key.isBlank()) return emptyMap()
        val path = if (isMovie) "movie/$tmdbId/translations" else "tv/$tmdbId/translations"
        val result = runCatching {
            val response = httpGet("$baseUrl/$path") {
                parameter("api_key", key)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getTranslatedTitles(tmdbId, isMovie)
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
        val key = apiKey()
        if (key.isBlank()) return emptyMap()
        val path = if (isMovie) "movie/$tmdbId/translations" else "tv/$tmdbId/translations"
        val result = runCatching {
            val response = httpGet("$baseUrl/$path") {
                parameter("api_key", key)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getRegionedLanguageTags(tmdbId, isMovie)
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
        if (result.isFailure) Logger.warn("TMDB regioned tags failed tmdbId=$tmdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrElse { emptyMap() }
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return searchMovieAll(query, year)
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return searchTvAll(query, year)
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return searchCompany(name)
            }
            response.body<TmdbCompanySearchResponse>().results.firstOrNull()
        }
        if (result.isFailure) Logger.warn("TMDB company search failed for '$name': ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    suspend fun getEpisodeDetails(seriesId: Int, season: Int, episode: Int, language: String? = null): TmdbEpisodeDetails? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = httpGet("$baseUrl/tv/$seriesId/season/$season/episode/$episode") {
                parameter("api_key", key)
                if (!language.isNullOrBlank()) parameter("language", language)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getEpisodeDetails(seriesId, season, episode, language)
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getImages(path)
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getMovieKeywords(tmdbId)
            }
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getTvKeywords(tmdbId)
            }
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getMovieCertifications(tmdbId)
            }
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
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getTvCertifications(tmdbId)
            }
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
}
