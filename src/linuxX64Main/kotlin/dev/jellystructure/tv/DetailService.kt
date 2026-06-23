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
import dev.jellystructure.shared.tv.Episode as TvEpisode

private const val RELATED_LIMIT = 12
private const val TICKS_PER_MS = 10_000L

class DetailService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    suspend fun getMovieDetail(device: DeviceData, jellyfinId: String): MovieDetail? {
        val item = mediaStore.allItems().firstOrNull { it.jellyfinId == jellyfinId } ?: return null
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)

        val jfDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val userData = jfDetail?.userData
        val durationMs = (jfDetail?.runTimeTicks ?: 0L) / TICKS_PER_MS

        return MovieDetail(
            card = item.toMediaCard(jellyfinBase, token),
            synopsis = item.overview,
            runtime = (durationMs / 60_000L).toInt(),
            cast = emptyList(),
            related = relatedItems(item, jellyfinBase, token),
            playback = userData.toPlaybackState(durationMs),
        )
    }

    suspend fun getSeriesDetail(device: DeviceData, jellyfinId: String): SeriesDetail? {
        val item = mediaStore.allItems().firstOrNull { it.jellyfinId == jellyfinId } ?: return null
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)

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
                val stillUrl = if (jfEp != null) "$jellyfinBase/Items/${jfEp.id}/Images/Primary?api_key=$token" else null
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

        return SeriesDetail(
            card = item.toMediaCard(jellyfinBase, token),
            synopsis = item.overview,
            seasons = seasons,
            cast = emptyList(),
            related = relatedItems(item, jellyfinBase, token),
            progress = progress,
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun relatedItems(source: MediaItem, jellyfinBase: String, token: String): List<MediaCard> =
        mediaStore.allItems()
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
            posterUrl = if (jId != null) "$jellyfinBase/Items/$jId/Images/Primary?api_key=$token" else null,
            backdropUrl = if (jId != null) "$jellyfinBase/Items/$jId/Images/Backdrop/0?api_key=$token" else null,
        )
    }
}

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
