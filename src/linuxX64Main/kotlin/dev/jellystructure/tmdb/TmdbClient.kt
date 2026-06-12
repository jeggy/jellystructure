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

class TmdbClient(private val configStore: ConfigStore) {
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
            val response = http.get("https://api.themoviedb.org/3/search/movie") {
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
            val response = http.get("https://api.themoviedb.org/3/movie/$tmdbId") {
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
}
