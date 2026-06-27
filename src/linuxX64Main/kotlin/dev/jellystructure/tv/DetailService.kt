package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MovieDetail
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.jellystructure.shared.tv.Episode as TvEpisode

private const val RELATED_LIMIT = 12
private const val TICKS_PER_MS = 10_000L
private const val PLAYSTATE_CHUNK = 100   // max ids per Jellyfin bulk UserData call

// R83: gate concurrent outbound Jellyfin UserData calls to avoid FD ceiling (Phase 78).
private val playstateGate = Semaphore(4)

class DetailService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    suspend fun getMovieDetail(device: DeviceData, jellyfinId: String): MovieDetail? {
        val item = mediaStore.resolveByJellyfinId(jellyfinId) ?: return null
        val all  = mediaStore.allItems()

        // R82: audio/sub languages from local scanned tracks; R83: runtime from local model.
        val movieAudioLangs = item.tracks
            .filter { it.kind == dev.jellystructure.model.TrackKind.AUDIO }
            .mapNotNull { it.language?.lowercase()?.takeIf { l -> l.isNotBlank() } }
        val movieSubLangs = item.tracks
            .filter { it.kind == dev.jellystructure.model.TrackKind.SUBTITLE }
            .mapNotNull { it.language?.lowercase()?.takeIf { l -> l.isNotBlank() } }
        return MovieDetail(
            card               = item.toMediaCard(),
            synopsis           = item.overview,
            runtime            = item.runtime ?: 0,
            cast               = castFrom(item),
            related            = relatedItems(item, all),
            playback           = null,  // R83: hydrated by /api/tv/playstate (R84 overlays it)
            audioLanguages     = movieAudioLangs,
            subtitleLanguages  = movieSubLangs,
        )
    }

    suspend fun getSeriesDetail(device: DeviceData, jellyfinId: String): SeriesDetail? {
        val item = mediaStore.resolveByJellyfinId(jellyfinId) ?: return null
        val all  = mediaStore.allItems()

        val seasonNums = item.episodes.map { it.seasonNumber ?: 0 }.distinct().sorted()

        val seasons = seasonNums.map { seasonNum ->
            val eps: List<Episode> = item.episodes
                .filter { (it.seasonNumber ?: 0) == seasonNum }
                .sortedBy { it.episodeNumber ?: 0 }
            val seasonName = item.seasonNames[seasonNum] ?: "Season $seasonNum"
            val tvEpisodes = eps.map { ep ->
                // R85: episode stills served through the jellystructure image proxy (no TMDB CDN).
                val stillUrl = ep.jellyfinId?.let { JellyfinImageUrl.still(it) }
                    ?: ep.stillPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w300$it" }
                TvEpisode(
                    id = ep.jellyfinId ?: ep.path,
                    episodeNumber = ep.episodeNumber ?: 0,
                    title = ep.title ?: "Episode ${ep.episodeNumber ?: seasonNum}",
                    runtime = ep.runtime ?: 0,
                    overview = ep.overview,
                    stillUrl = stillUrl,
                    playback = null,  // R83: hydrated by /api/tv/playstate (R84 overlays it)
                )
            }
            Season(index = seasonNum, name = seasonName, episodes = tvEpisodes)
        }

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
        return SeriesDetail(
            card              = item.toMediaCard(),
            synopsis          = item.overview,
            seasons           = seasons,
            cast              = castFrom(item),
            related           = relatedItems(item, all),
            progress          = null,  // R83: hydrated by /api/tv/playstate (R84 overlays it)
            audioLanguages    = seriesAudioLangs,
            subtitleLanguages = seriesSubLangs,
        )
    }

    /**
     * R83: Fetch per-user play-state for the given Jellyfin ids from Jellyfin UserData.
     * Chunks the request into batches of [PLAYSTATE_CHUNK] and fans out with [playstateGate].
     * Returns an empty map on auth/connectivity failures (callers treat absent entries as "not played").
     */
    suspend fun getPlaystate(device: DeviceData, jellyfinIds: List<String>): Map<String, CardPlayState> {
        if (jellyfinIds.isEmpty()) return emptyMap()
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        // Filter out path-based IDs (ep.jellyfinId was null at scan time → ep.id = ep.path)
        val realIds = jellyfinIds.filterNot { it.startsWith('/') }
        if (realIds.isEmpty()) return emptyMap()
        val chunks = realIds.chunked(PLAYSTATE_CHUNK)
        val results = mutableMapOf<String, CardPlayState>()
        for (chunk in chunks) {
            playstateGate.withPermit {
                val items = jellyfinClient.getUserDataBulk(jellyfinBase, token, device.jellyfinUserId, chunk)
                for (jfItem in items) {
                    val ud = jfItem.userData ?: continue
                    val posMs = ud.playbackPositionTicks / TICKS_PER_MS
                    val pct = (ud.playedPercentage?.toFloat() ?: 0f) / 100f
                    results[jfItem.id] = CardPlayState(
                        resumeMs  = posMs,
                        played    = ud.played,
                        playedPct = pct,
                    )
                }
            }
        }
        return results
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun relatedItems(source: MediaItem, all: List<MediaItem>): List<MediaCard> =
        all
            .filter { it.id != source.id && it.genres.any { g -> source.genres.contains(g) } }
            .sortedByDescending { it.scannedAt }
            .take(RELATED_LIMIT)
            .map { it.toMediaCard() }

    private fun MediaItem.toMediaCard(): MediaCard {
        val jId = jellyfinId
        return MediaCard(
            id = jId ?: id,
            kind = if (kind == MediaKind.TV_SHOW) dev.jellystructure.shared.tv.MediaKind.SERIES
                   else dev.jellystructure.shared.tv.MediaKind.MOVIE,
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = null,
            posterUrl = if (jId != null) JellyfinImageUrl.poster(jId) else null,
            backdropUrl = if (jId != null) JellyfinImageUrl.backdrop(jId) else null,
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

