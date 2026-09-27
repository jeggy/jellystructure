package dev.jellystructure.bazarr

/**
 * Phase 273 — the Bazarr calls the subtitle check's steering, hook and id resolution make, as an interface so a test
 * can stand in for Bazarr (the Anthropic client's `Transport` is the same idea). [BazarrClient] is the only real
 * implementation.
 */
interface BazarrOps {
    suspend fun allMovies(url: String, apiKey: String): List<BazarrMovie>
    suspend fun allSeries(url: String, apiKey: String): List<BazarrSeries>
    suspend fun episodesFor(url: String, apiKey: String, sonarrSeriesId: Int): List<BazarrEpisode>
    suspend fun episodeById(url: String, apiKey: String, sonarrEpisodeId: Int): BazarrEpisode?
    suspend fun movieById(url: String, apiKey: String, radarrId: Int): BazarrMovie?
    suspend fun movieHistoryRows(url: String, apiKey: String, radarrId: Int? = null, start: Int = 0, length: Int = 20): List<BazarrHistoryRow>
    suspend fun episodeHistoryRows(url: String, apiKey: String, episodeId: Int? = null, start: Int = 0, length: Int = 20): List<BazarrHistoryRow>
    suspend fun syncSubtitle(
        url: String, apiKey: String, type: String, id: Int, language: String, path: String,
        reference: String? = null, maxOffsetSeconds: Int? = null, noFixFramerate: Boolean? = null, gss: Boolean? = null,
        forced: Boolean = false, hi: Boolean = false,
    ): Boolean
    suspend fun blacklistEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, provider: String, subsId: String, language: String, subtitlesPath: String): Boolean
    suspend fun blacklistMovieSubtitle(url: String, apiKey: String, radarrId: Int, provider: String, subsId: String, language: String, subtitlesPath: String): Boolean
    suspend fun uploadEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, language: String, forced: Boolean, hi: Boolean, fileName: String, content: ByteArray): Boolean
    suspend fun uploadMovieSubtitle(url: String, apiKey: String, radarrId: Int, language: String, forced: Boolean, hi: Boolean, fileName: String, content: ByteArray): Boolean
    suspend fun deleteMovieSubtitle(url: String, apiKey: String, radarrId: Int, language: String, forced: Boolean, hi: Boolean, path: String): Boolean
    suspend fun deleteEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, language: String, forced: Boolean, hi: Boolean, path: String): Boolean
    suspend fun searchProvidersEpisode(url: String, apiKey: String, episodeId: Int): List<BazarrProviderResult>
    suspend fun downloadProviderEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, hi: Boolean, forced: Boolean, provider: String, subtitle: String): Boolean
}
