package dev.jellystructure.tmdb

import dev.jellystructure.config.ConfigStore
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
    val genres: List<TmdbGenre> = emptyList(),
)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

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
    val genres: List<TmdbGenre> = emptyList(),
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
        return runCatching {
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
        }.onFailure { println("[WARN] TMDB search failed for '$title': ${it.message}") }
         .getOrNull()
    }

    suspend fun getMovieDetails(tmdbId: Int, language: String? = null): TmdbMovieDetails? {
        val cacheKey = tmdbId
        if (language == null) detailsCache[cacheKey]?.let { return it }
        val key = apiKey()
        if (key.isBlank()) return null
        return runCatching {
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
        }.onFailure { println("[WARN] TMDB details failed for id=$tmdbId lang=$language: ${it.message}") }
         .getOrNull()
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
        return runCatching {
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
        }.onFailure { println("[WARN] TMDB TV search failed for '$title': ${it.message}") }
         .getOrNull()
    }

    suspend fun getTvDetails(tmdbId: Int, language: String? = null): TmdbTvDetails? {
        val key = apiKey()
        if (key.isBlank()) return null
        return runCatching {
            val response = http.get("$baseUrl/tv/$tmdbId") {
                parameter("api_key", key)
                if (!language.isNullOrBlank()) parameter("language", language)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(3000)
                return getTvDetails(tmdbId, language)
            }
            response.body<TmdbTvDetails>()
        }.onFailure { println("[WARN] TMDB TV details failed for id=$tmdbId lang=$language: ${it.message}") }
         .getOrNull()
    }

    suspend fun getTvDetailsLocalized(tmdbId: Int, languages: List<String>): TmdbTvDetails? {
        for (lang in languages) {
            val d = getTvDetails(tmdbId, lang) ?: continue
            if (d.overview.isNotBlank()) return d
        }
        return getTvDetails(tmdbId)
    }

    suspend fun getEpisodeDetails(seriesId: Int, season: Int, episode: Int, language: String? = null): TmdbEpisodeDetails? {
        val key = apiKey()
        if (key.isBlank()) return null
        return runCatching {
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
        }.onFailure { println("[WARN] TMDB episode details failed for series=$seriesId s${season}e${episode} lang=$language: ${it.message}") }
         .getOrNull()
    }
}
