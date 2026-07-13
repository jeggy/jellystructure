package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.visibleTo
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.SegmentMarkers
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MovieDetail
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.NextAiring
import dev.jellystructure.shared.tv.RatingBadge
import dev.jellystructure.shared.tv.SeriesDetail
import dev.jellystructure.shared.tv.TvImdbRating
import dev.jellystructure.shared.tv.TvSegmentMarkers
import dev.jellystructure.shared.tv.TvStinger
import dev.jellystructure.shared.tv.TvTrailer
import dev.jellystructure.resolver.CertificationResolver
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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
        // Phase 142: a blocked item's detail returns 404 (as if absent) rather than leaking metadata —
        // defense-in-depth, since the user's own token would 403 the stream anyway.
        if (!item.visibleTo(device)) return null

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
            related            = hydrateRelated(device, mediaStore.relatedByGenre(item, RELATED_LIMIT).filter { it.visibleTo(device) }.map { it.toMediaCard() }.distinctBy { it.id }),
            playback           = null,  // R83: hydrated by /api/tv/playstate (R84 overlays it)
            audioLanguages     = movieAudioLangs,
            subtitleLanguages  = movieSubLangs,
            logoUrl            = RaviloImageUrl.logo(item.id),  // R130/R133
            ratingBadge        = item.ratingBadge(),  // Phase 106
            trailer            = item.tvTrailer(),  // Phase 130
            imdbRating         = item.tvImdbRating(),  // Phase 131
            originalLanguage   = item.originalLanguage,  // R181 — player's "Dubbed" audio badge
            segments           = item.segments.toTv(),  // Phase 150
        )
    }

    suspend fun getSeriesDetail(device: DeviceData, jellyfinId: String): SeriesDetail? {
        val item = mediaStore.resolveByJellyfinId(jellyfinId) ?: return null
        // Phase 142: see the matching check in getMovieDetail.
        if (!item.visibleTo(device)) return null

        val seasonNums = item.episodes.map { it.seasonNumber ?: 0 }.distinct().sorted()

        val seasons = seasonNums.map { seasonNum ->
            val eps: List<Episode> = item.episodes
                .filter { (it.seasonNumber ?: 0) == seasonNum }
                .sortedBy { it.episodeNumber ?: 0 }
            val seasonName = item.seasonNames[seasonNum] ?: "Season $seasonNum"
            val tvEpisodes = eps.map { ep ->
                // R133: episode still from jellystructure's on-disk <base>-thumb.jpg (series id + filename).
                // Bug fix (Phase 149): episodes sharing a file also share filename — pass episodeNumber
                // so the image route serves THIS episode's still, not always the group's first one.
                val stillUrl = RaviloImageUrl.still(item.id, ep.filename, ep.episodeNumber)
                TvEpisode(
                    // Bug fix (Phase 149): episodes sharing a file also share `path`, so the jellyfinId
                    // fallback used to collapse a whole group onto the SAME id — the episodeNumber
                    // suffix keeps every episode unique even when jellyfinId isn't populated yet.
                    id = ep.jellyfinId ?: "${ep.path}#${ep.episodeNumber ?: seasonNum}",
                    episodeNumber = ep.episodeNumber ?: 0,
                    title = ep.title ?: "Episode ${ep.episodeNumber ?: seasonNum}",
                    runtime = ep.runtime ?: 0,
                    overview = ep.overview,
                    stillUrl = stillUrl,
                    playback = null,  // R83: hydrated by /api/tv/playstate (R84 overlays it)
                    airDate = ep.airDate,  // R148: scanned TMDB air date — no request-time call
                    file = ep.path,  // Phase 149: R179's client-side grouping key
                    partIndex = ep.partIndex,
                    partCount = ep.partCount,
                    chapterStartMs = ep.chapterStartMs,
                    hasChapters = ep.hasChapters,
                    segments = ep.segments.toTv(),  // Phase 150
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
        val sonarrEnabled = configStore.current.sonarr?.enabled == true
        val nextAiring = if (sonarrEnabled && item.sonarrStatus != "ended") {
            val date = item.sonarrNextAiringDate
            val season = item.sonarrNextAiringSeason
            val episode = item.sonarrNextAiringEpisode
            if (date != null && season != null && episode != null)
                NextAiring(season = season, episode = episode, title = item.sonarrNextAiringTitle, airDate = date)
            else null
        } else null
        return SeriesDetail(
            card              = item.toMediaCard(),
            synopsis          = item.overview,
            seasons           = seasons,
            cast              = castFrom(item),
            related           = hydrateRelated(device, mediaStore.relatedByGenre(item, RELATED_LIMIT).filter { it.visibleTo(device) }.map { it.toMediaCard() }.distinctBy { it.id }),
            progress          = null,  // R83: hydrated by /api/tv/playstate (R84 overlays it)
            audioLanguages    = seriesAudioLangs,
            subtitleLanguages = seriesSubLangs,
            logoUrl           = RaviloImageUrl.logo(item.id),  // R130/R133
            nextAiring        = nextAiring,
            ratingBadge       = item.ratingBadge(),  // Phase 106
            trailer           = item.tvTrailer(),  // Phase 130
            imdbRating        = item.tvImdbRating(),  // Phase 131
            originalLanguage  = item.originalLanguage,  // R181 — player's "Dubbed" audio badge
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
                        // Skip a vacuous series ✓ (empty Jellyfin child rollup → Played=true of 0). See PlaystateHydrator.
                        played    = ud.played && !(jfItem.type == "Series" && jfItem.recursiveItemCount == 0),
                        playedPct = pct,
                    )
                }
            }
        }
        return results
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    // R100: related items now come from MediaStore.relatedByGenre (genre-bucket index) instead of a
    // full-library scan per detail open; the old in-place relatedItems()/allItems() pair is gone.

    /** R142: overlay played / in-progress state onto More-Like-This tiles (bounded so it never hangs detail). */
    private suspend fun hydrateRelated(device: DeviceData, cards: List<MediaCard>): List<MediaCard> {
        if (cards.isEmpty()) return cards
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val ps = withTimeoutOrNull(2_500L) {
            val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
            fetchPlaystate(jellyfinClient, base, token, device.jellyfinUserId, cards.map { it.id })
        } ?: return cards
        return cards.map { it.withPlaystate(ps) }
    }

    private fun MediaItem.toMediaCard(): MediaCard {
        val jId = jellyfinId
        val sonarrEnabled = configStore.current.sonarr?.enabled == true
        return MediaCard(
            id = jId ?: id,
            kind = if (kind == MediaKind.TV_SHOW) dev.jellystructure.shared.tv.MediaKind.SERIES
                   else dev.jellystructure.shared.tv.MediaKind.MOVIE,
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = ratingBadge()?.code,  // Phase 106
            posterUrl = RaviloImageUrl.poster(id),     // R133: keyed by MediaItem.id (on-disk artwork)
            backdropUrl = RaviloImageUrl.backdrop(id),
            upcomingEpisode = if (sonarrEnabled && kind == MediaKind.TV_SHOW &&
                sonarrStatus != "ended" && sonarrNextAiringDate != null &&
                sonarrNextAiringSeason != null && sonarrNextAiringEpisode != null)
                "S${sonarrNextAiringSeason.toString().padStart(2,'0')}E${sonarrNextAiringEpisode.toString().padStart(2,'0')}" else null,
        )
    }

    /** Phase 106: resolve the item's certification against the configured region cascade. */
    private fun MediaItem.ratingBadge(): RatingBadge? {
        val cert = CertificationResolver.resolve(configStore.current.metadata.ageRatingCascade, certifications) ?: return null
        return RatingBadge(region = cert.region, code = cert.code, tier = cert.tier, fallback = cert.fallback)
    }

    /** Phase 130: catalog-only — the item's stored trailer, straight off the in-memory MediaItem
     *  (no TMDB/Jellyfin round-trip at detail-read time). */
    private fun MediaItem.tvTrailer(): TvTrailer? =
        trailer?.let { TvTrailer(site = it.site, key = it.key, name = it.name.takeIf { n -> n.isNotBlank() }) }

    /** Phase 131: catalog-only — the item's stored IMDb rating; never fetched at detail-read time. */
    private fun MediaItem.tvImdbRating(): TvImdbRating? =
        imdbRating?.let { TvImdbRating(aggregateRating = it.aggregateRating, voteCount = it.voteCount) }

    /** Phase 150: catalog-only — the stored segment markers (Episode's own or a movie's), straight off
     *  the in-memory model. An extension on the server-side [SegmentMarkers] itself (not [MediaItem])
     *  since it's shared by both the per-episode and per-movie mapping call sites above. */
    private fun SegmentMarkers.toTv(): TvSegmentMarkers = TvSegmentMarkers(
        introStartMs = introStartMs,
        introEndMs = introEndMs,
        creditsStartMs = creditsStartMs,
        stinger = stinger?.let { TvStinger(atMs = it.atMs, kind = it.kind) },
    )
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

