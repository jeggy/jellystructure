package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinUserData
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MovieDetail
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.PlaybackState
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail
import dev.jellystructure.shared.tv.SeriesProgress
import dev.jellystructure.auth.JellyfinItemDetail
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import dev.jellystructure.shared.tv.Episode as TvEpisode

private const val RELATED_LIMIT = 12
private const val TICKS_PER_MS = 10_000L

class DetailService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    suspend fun getMovieDetail(device: DeviceData, jellyfinId: String): MovieDetail? = coroutineScope {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        // allItems() (SQLite) and tvToken() (Jellyfin/cached) have no dependency — run in parallel.
        val allDeferred   = async { mediaStore.allItems() }
        val tokenDeferred = async { jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken) }

        val all   = allDeferred.await()
        val item  = all.firstOrNull { it.jellyfinId == jellyfinId } ?: return@coroutineScope null
        val token = tokenDeferred.await()

        val jfDetail  = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val userData  = jfDetail?.userData
        val durationMs = (jfDetail?.runTimeTicks ?: 0L) / TICKS_PER_MS

        MovieDetail(
            card               = item.toMediaCard(jellyfinBase, token),
            synopsis           = item.overview,
            runtime            = (durationMs / 60_000L).toInt(),
            cast               = castFrom(item),
            related            = relatedItems(item, all, jellyfinBase, token),
            playback           = userData.toPlaybackState(durationMs),
            audioLanguages     = streamsOf(jfDetail, "Audio"),
            subtitleLanguages  = streamsOf(jfDetail, "Subtitle"),
        )
    }

    suspend fun getSeriesDetail(device: DeviceData, jellyfinId: String): SeriesDetail? = coroutineScope {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val allDeferred   = async { mediaStore.allItems() }
        val tokenDeferred = async { jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken) }

        val all   = allDeferred.await()
        val item  = all.firstOrNull { it.jellyfinId == jellyfinId } ?: return@coroutineScope null
        val token = tokenDeferred.await()

        val jfEpisodes = jellyfinClient.getSeriesEpisodes(
            jellyfinBase, token, device.jellyfinUserId, jellyfinId
        )
        // Index Jellyfin episode data by (season, episode) numbers
        val jfBySeasonEp = jfEpisodes.associateBy { (it.parentIndexNumber ?: 0) to (it.indexNumber ?: 0) }

        // Group our episodes by season, sorted by season number
        val seasonNums = item.episodes.map { it.seasonNumber ?: 0 }.distinct().sorted()

        val seasons = seasonNums.map { seasonNum ->
            val eps: List<Episode> = item.episodes
                .filter { (it.seasonNumber ?: 0) == seasonNum }
                .sortedBy { it.episodeNumber ?: 0 }
            val jfFirstSeason = jfEpisodes.firstOrNull { it.parentIndexNumber == seasonNum }
            val seasonName = jfFirstSeason?.seasonName ?: "Season $seasonNum"
            val tvEpisodes = eps.map { ep ->
                val jfEp = jfBySeasonEp[seasonNum to (ep.episodeNumber ?: 0)]
                val durationMs = (jfEp?.runTimeTicks ?: 0L) / TICKS_PER_MS
                val stillUrl = if (jfEp != null) JellyfinImageUrl.still(jellyfinBase, jfEp.id, token) else null
                TvEpisode(
                    id = jfEp?.id ?: ep.path,
                    episodeNumber = ep.episodeNumber ?: 0,
                    title = ep.title ?: jfEp?.name ?: "Episode ${ep.episodeNumber ?: seasonNum}",
                    runtime = (durationMs / 60_000L).toInt(),
                    overview = ep.overview,
                    stillUrl = stillUrl,
                    playback = jfEp?.userData.toPlaybackState(durationMs),
                )
            }
            Season(index = seasonNum, name = seasonName, episodes = tvEpisodes)
        }

        val allEpisodes = seasons.flatMap { it.episodes }
        val watchedCount = allEpisodes.count { it.playback.watched }
        val resumeEp = allEpisodes.firstOrNull { !it.playback.watched && it.playback.positionMs > 0 }
            ?: allEpisodes.firstOrNull { !it.playback.watched }
            ?: allEpisodes.lastOrNull()

        val resumeLabel = resumeEp?.let { ep ->
            val seasonIdx = seasons.indexOfFirst { s -> s.episodes.any { e -> e.id == ep.id } }.takeIf { it >= 0 }?.plus(1)
            if (seasonIdx != null) "S${seasonIdx}E${ep.episodeNumber} · ${ep.title}" else ep.title
        }

        val progress = SeriesProgress(
            watchedCount = watchedCount,
            totalCount = allEpisodes.size,
            resumeEpisodeId = resumeEp?.id,
            resumeLabel = resumeLabel,
        )

        // Use the first episode's scanned tracks for flag strips (no extra Jellyfin round-trip).
        val firstEpTracks = item.episodes.firstOrNull()?.tracks
        val seriesAudioLangs = firstEpTracks
            ?.filter { it.kind == dev.jellystructure.model.TrackKind.AUDIO }
            ?.mapNotNull { it.language?.lowercase()?.takeIf { l -> l.isNotBlank() } }
            ?: emptyList()
        val seriesSubLangs = firstEpTracks
            ?.filter { it.kind == dev.jellystructure.model.TrackKind.SUBTITLE }
            ?.mapNotNull { it.language?.lowercase()?.takeIf { l -> l.isNotBlank() } }
            ?: emptyList()
        SeriesDetail(
            card              = item.toMediaCard(jellyfinBase, token),
            synopsis          = item.overview,
            seasons           = seasons,
            cast              = castFrom(item),
            related           = relatedItems(item, all, jellyfinBase, token),
            progress          = progress,
            audioLanguages    = seriesAudioLangs,
            subtitleLanguages = seriesSubLangs,
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun relatedItems(source: MediaItem, all: List<MediaItem>, jellyfinBase: String, token: String): List<MediaCard> =
        all
            .filter { it.id != source.id && it.genres.any { g -> source.genres.contains(g) } }
            .sortedByDescending { it.scannedAt }
            .take(RELATED_LIMIT)
            .map { it.toMediaCard(jellyfinBase, token) }

    private fun MediaItem.toMediaCard(jellyfinBase: String, token: String): MediaCard {
        val jId = jellyfinId
        return MediaCard(
            id = jId ?: id,
            kind = if (kind == MediaKind.TV_SHOW) dev.jellystructure.shared.tv.MediaKind.SERIES
                   else dev.jellystructure.shared.tv.MediaKind.MOVIE,
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = null,
            posterUrl = if (jId != null) JellyfinImageUrl.poster(jellyfinBase, jId, token) else null,
            backdropUrl = if (jId != null) JellyfinImageUrl.backdrop(jellyfinBase, jId, token) else null,
        )
    }
}

private const val CAST_LIMIT = 20
private const val TMDB_PROFILE_W185 = "https://image.tmdb.org/t/p/w185"

/**
 * Map jellystructure's own scanned cast + crew (sourced from TMDB at scan time) to the TV [Person]
 * DTO — R81. No Jellyfin round-trip: the [MediaItem] is already in memory, and person images come
 * from the TMDB CDN, not Jellyfin. Cast first (TMDB billing order, already sorted on the item),
 * then crew; deduped by TMDB id (a person credited as both keeps their cast entry); capped.
 */
private fun castFrom(item: MediaItem): List<Person> =
    (item.cast + item.crew)
        .filter { it.name.isNotBlank() }
        .distinctBy { it.tmdbId }
        .take(CAST_LIMIT)
        .map { p ->
            Person(
                id = p.tmdbId.toString(),
                name = p.name,
                role = p.character?.takeIf { it.isNotBlank() }
                    ?: p.job?.takeIf { it.isNotBlank() }
                    ?: p.role?.takeIf { it.isNotBlank() }
                    ?: p.department?.takeIf { it.isNotBlank() }
                    ?: p.type.takeIf { it.isNotBlank() },
                imageUrl = p.profilePath?.takeIf { it.isNotBlank() }?.let { "$TMDB_PROFILE_W185$it" },
            )
        }

/** Extract ordered language codes of the given stream type from a Jellyfin item's MediaStreams (R75/R78). */
private fun streamsOf(jfDetail: JellyfinItemDetail?, type: String): List<String> =
    (jfDetail?.mediaStreams ?: return emptyList())
        .filter { it.type.equals(type, ignoreCase = true) }
        .mapNotNull { it.language?.lowercase()?.takeIf { l -> l.isNotBlank() } }

private fun JellyfinUserData?.toPlaybackState(durationMs: Long): PlaybackState {
    val posMs = (this?.playbackPositionTicks ?: 0L) / TICKS_PER_MS
    val pct = if (durationMs > 0) (posMs.toFloat() / durationMs.toFloat()) else 0f
    return PlaybackState(
        watched = this?.played ?: false,
        positionMs = posMs,
        durationMs = durationMs,
        pct = pct,
    )
}
