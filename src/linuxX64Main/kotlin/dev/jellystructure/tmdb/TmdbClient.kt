package dev.jellystructure.tmdb

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
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

@Serializable
data class TmdbTranslation(
    @SerialName("iso_639_1") val languageCode: String = "",
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

// --- Keywords (used as the non-JS tag source on TMDB re-pull). Movie and TV use different
// field names for the same shape: movies nest under `keywords`, TV under `results`. ---
@Serializable
data class TmdbKeyword(val id: Int, val name: String)

@Serializable
data class TmdbMovieKeywordsResponse(val keywords: List<TmdbKeyword> = emptyList())

@Serializable
data class TmdbTvKeywordsResponse(val results: List<TmdbKeyword> = emptyList())

@Serializable
data class TmdbExternalIds(@SerialName("tvdb_id") val tvdbId: Int? = null)

class TmdbClient(
    private val configStore: ConfigStore,
    private val baseUrl: String = "https://api.themoviedb.org/3",
) {
    private val http = HttpClient(Curl) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val detailsCache = mutableMapOf<Int, TmdbMovieDetails>()

    private fun apiKey(): String = configStore.current.apiKeys.tmdbV3Key

    suspend fun searchMovie(title: String, year: Int?): TmdbSearchResult? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = http.get("$baseUrl/search/movie") {
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
        val cacheKey = tmdbId
        if (language == null) detailsCache[cacheKey]?.let { return it }
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = http.get("$baseUrl/movie/$tmdbId") {
                parameter("api_key", key)
                if (!language.isNullOrBlank()) parameter("language", language)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getMovieDetails(tmdbId, language)
            }
            val details = response.body<TmdbMovieDetails>()
            if (language == null) detailsCache[cacheKey] = details
            details
        }
        if (result.isFailure) Logger.warn("TMDB details failed for id=$tmdbId lang=$language: ${result.exceptionOrNull()?.message}")
        return result.getOrNull()
    }

    // Try each language in priority order; use the first that has a non-empty overview.
    suspend fun getMovieDetailsLocalized(tmdbId: Int, languages: List<String>): TmdbMovieDetails? {
        for (lang in languages) {
            val d = getMovieDetails(tmdbId, lang) ?: continue
            if (d.overview.isNotBlank()) return d
        }
        return getMovieDetails(tmdbId)
    }

    suspend fun searchTv(title: String, year: Int?): TmdbTvSearchResult? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = http.get("$baseUrl/search/tv") {
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
            val response = http.get("$baseUrl/tv/$tmdbId") {
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

    /** Phase 56 — bridge a TMDB tv id to its TheTVDB id (Sonarr is keyed by tvdbId, not tmdbId). */
    suspend fun getTvTvdbId(tmdbId: Int): Int? {
        val key = apiKey()
        if (key.isBlank()) return null
        return runCatching {
            val response = http.get("$baseUrl/tv/$tmdbId/external_ids") { parameter("api_key", key) }
            if (response.status != HttpStatusCode.OK) return null
            response.body<TmdbExternalIds>().tvdbId
        }.getOrNull()
    }

    /** R63 — top-5 cast members for a movie (by `order`). Returns emptyList on any failure. */
    suspend fun getMovieCredits(tmdbId: Int): List<TmdbCastMember> {
        val key = apiKey(); if (key.isBlank()) return emptyList()
        return runCatching {
            val r = http.get("$baseUrl/movie/$tmdbId/credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return emptyList()
            r.body<TmdbCreditsResponse>().cast.sortedBy { it.order }.take(5)
        }.getOrElse { Logger.warn("TMDB movie credits failed tmdbId=$tmdbId: ${it.message}"); emptyList() }
    }

    /** R63 — top-5 cast members for a TV series (by `order`). Returns emptyList on any failure. */
    suspend fun getTvCredits(tmdbId: Int): List<TmdbCastMember> {
        val key = apiKey(); if (key.isBlank()) return emptyList()
        return runCatching {
            val r = http.get("$baseUrl/tv/$tmdbId/credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return emptyList()
            r.body<TmdbCreditsResponse>().cast.sortedBy { it.order }.take(5)
        }.getOrElse { Logger.warn("TMDB tv credits failed tmdbId=$tmdbId: ${it.message}"); emptyList() }
    }

    /** Phase 75 — full cast + crew for a movie. */
    suspend fun getMovieFullCredits(tmdbId: Int): TmdbCreditsResponse {
        val key = apiKey(); if (key.isBlank()) return TmdbCreditsResponse()
        return runCatching {
            val r = http.get("$baseUrl/movie/$tmdbId/credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return TmdbCreditsResponse()
            r.body<TmdbCreditsResponse>()
        }.getOrElse { Logger.warn("TMDB movie full credits failed tmdbId=$tmdbId: ${it.message}"); TmdbCreditsResponse() }
    }

    /** Phase 75 — full cast + crew for a TV series. */
    suspend fun getTvFullCredits(tmdbId: Int): TmdbCreditsResponse {
        val key = apiKey(); if (key.isBlank()) return TmdbCreditsResponse()
        return runCatching {
            val r = http.get("$baseUrl/tv/$tmdbId/credits") { parameter("api_key", key) }
            if (r.status != HttpStatusCode.OK) return TmdbCreditsResponse()
            r.body<TmdbCreditsResponse>()
        }.getOrElse { Logger.warn("TMDB tv full credits failed tmdbId=$tmdbId: ${it.message}"); TmdbCreditsResponse() }
    }

    /** Phase 75 — search TMDB for people by name. */
    suspend fun searchPeople(query: String): List<TmdbPersonSearchResult> {
        val key = apiKey(); if (key.isBlank()) return emptyList()
        return runCatching {
            val r = http.get("$baseUrl/search/person") {
                parameter("api_key", key)
                parameter("query", query)
            }
            if (r.status != HttpStatusCode.OK) return emptyList()
            r.body<TmdbPersonSearchResponse>().results.take(10)
        }.getOrElse { Logger.warn("TMDB person search failed '$query': ${it.message}"); emptyList() }
    }

    suspend fun getTvDetailsLocalized(tmdbId: Int, languages: List<String>): TmdbTvDetails? {
        for (lang in languages) {
            val d = getTvDetails(tmdbId, lang) ?: continue
            if (d.overview.isNotBlank()) return d
        }
        return getTvDetails(tmdbId)
    }

    suspend fun getTranslationLanguages(tmdbId: Int, isMovie: Boolean): List<String> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        val path = if (isMovie) "movie/$tmdbId/translations" else "tv/$tmdbId/translations"
        val result = runCatching {
            val response = http.get("$baseUrl/$path") {
                parameter("api_key", key)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getTranslationLanguages(tmdbId, isMovie)
            }
            response.body<TmdbTranslationsResponse>().translations
                .filter { it.languageCode.isNotBlank() && it.data.overview.isNotBlank() }
                .map { it.languageCode }
                .distinct()
        }
        if (result.isFailure) Logger.warn("TMDB translations failed tmdbId=$tmdbId: ${result.exceptionOrNull()?.message}")
        return result.getOrElse { emptyList() }
    }

    /** Returns a map of language code → localized title for all available languages. */
    suspend fun getTranslatedTitles(tmdbId: Int, isMovie: Boolean): Map<String, String> {
        val key = apiKey()
        if (key.isBlank()) return emptyMap()
        val path = if (isMovie) "movie/$tmdbId/translations" else "tv/$tmdbId/translations"
        val result = runCatching {
            val response = http.get("$baseUrl/$path") {
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

    suspend fun searchMovieAll(query: String, year: Int?): List<TmdbSearchResult> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        val result = runCatching {
            val response = http.get("$baseUrl/search/movie") {
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
            val response = http.get("$baseUrl/search/tv") {
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
            val response = http.get("$baseUrl/search/company") {
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
            val response = http.get("$baseUrl/tv/$seriesId/season/$season/episode/$episode") {
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

    // --- Phase 47: image candidate galleries. No `language` param so TMDB returns every
    // available image across all languages, including textless (iso_639_1 = null). ---
    private suspend fun getImages(path: String): TmdbImagesResponse? {
        val key = apiKey()
        if (key.isBlank()) return null
        val result = runCatching {
            val response = http.get("$baseUrl/$path/images") {
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
            val response = http.get("$baseUrl/movie/$tmdbId/keywords") { parameter("api_key", key) }
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
            val response = http.get("$baseUrl/tv/$tmdbId/keywords") { parameter("api_key", key) }
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
}
