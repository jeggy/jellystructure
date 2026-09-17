package dev.jellystructure.tv

import dev.jellystructure.model.SegmentPositionRules
import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.DuplicateEpisodes
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.SegmentKind
import dev.jellystructure.media.visibleTo
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Stinger
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
import dev.jellystructure.shared.tv.Episode as TvEpisode

private const val RELATED_LIMIT = 12

/**
 * Phase 200 (FR-200-4) — the series-level flag strip's language set: every language any episode
 * carries, ordered by the first episode (in [episodes]' own order) that carries it. Pulled out as a
 * standalone function so the union-not-episode-1 behavior is unit-testable without a `DetailService`.
 */
internal fun unionLanguagesInFirstSeenOrder(episodes: List<Episode>, kind: dev.jellystructure.model.TrackKind): List<String> {
    val seen = LinkedHashSet<String>()
    for (ep in episodes) {
        for (track in ep.tracks) {
            if (track.kind != kind) continue
            val lang = track.language?.lowercase()?.takeIf { it.isNotBlank() } ?: continue
            seen.add(lang)
        }
    }
    return seen.toList()
}

class DetailService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val artwork: ArtworkDownloader,
    private val segmentStore: MediaSegmentStore,
    // Phase 185 (FR-185-5) — playbackNote resolution.
    private val raviloDeviceService: RaviloDeviceService,
    private val playbackStartSampleStore: PlaybackStartSampleStore,
) {
    /** Phase 232 (FR-232-5) — set by Main; null in tests (= every logo's ink unknown). */
    var clearlogoInk: dev.jellystructure.media.ClearlogoInk? = null

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
        // Phase 185 (FR-185-5) — resolved for THIS requesting device, from THIS movie's own file.
        val movieVideoTrack = item.tracks.firstOrNull { it.kind == dev.jellystructure.model.TrackKind.VIDEO }
        val playbackNote = resolvePlaybackNote(
            videoTrack = movieVideoTrack,
            capabilities = raviloDeviceService.decodeCapabilities(device.deviceId, device.jellyfinUserId),
            deviceId = device.deviceId,
            deviceDisplayName = device.displayName,
            fileId = item.path,
            startSampleStore = playbackStartSampleStore,
        )
        return MovieDetail(
            card               = item.toMediaCard(),
            synopsis           = item.overview,
            runtime            = item.runtime ?: 0,
            cast               = castFrom(item),
            related            = hydrateRelated(device, mediaStore.relatedByGenre(item, RELATED_LIMIT).filter { it.visibleTo(device) }.map { it.toMediaCard() }.distinctBy { it.id }),
            playback           = null,  // R83: hydrated by /api/tv/playstate (R84 overlays it)
            audioLanguages     = movieAudioLangs,
            subtitleLanguages  = movieSubLangs,
            logoUrl            = RaviloImageUrl.logo(item.id, artwork.assetVersion(item, "clearlogo")),  // R130/R133/R214
            logoInk = clearlogoInk?.inkFor(item),   // Phase 232 (FR-232-5/6)
            ratingBadge        = item.ratingBadge(),  // Phase 106
            trailer            = item.tvTrailer(),  // Phase 130
            imdbRating         = item.tvImdbRating(),  // Phase 131
            originalLanguage   = item.originalLanguage,  // R181 — player's "Dubbed" audio badge
            segments           = toTv(item.id, "", 0, item.segments.stinger),  // Phase 150/163
            genres             = item.genres,  // R221 — TMDB's own order preserved (see Models.kt doc)
            playbackNote       = playbackNote,  // R222 (Phase 185)
        )
    }

    suspend fun getSeriesDetail(device: DeviceData, jellyfinId: String): SeriesDetail? {
        val item = mediaStore.resolveByJellyfinId(jellyfinId) ?: return null
        // Phase 142: see the matching check in getMovieDetail.
        if (!item.visibleTo(device)) return null

        // Bug fix (Ravilo auto-play-next loop): a library can legitimately contain two FILES that both
        // parse to the same S__E__ (a folder extracted twice, or two mislabelled release files). The
        // scanner keyed Jellyfin ids by (season, episode), so both entries carried the same id and Ravilo
        // built two rail slots out of them — making the "next episode" after episode 1 episode 1 itself,
        // which no navigation can satisfy (verified: Tellytots S01 existed twice, once nested inside the
        // S02 folder). The redundant copies stay in the workbench (see the `duplicate_episode` triage
        // type); the playback API only ever exposes ONE entry per (season, episode).
        val uniqueEpisodes = DuplicateEpisodes.deduped(item.episodes)
        val seasonNums = uniqueEpisodes.map { it.seasonNumber ?: 0 }.distinct().sorted()

        // Phase 185 (FR-185-5/FR-185-9) — one DB lookup for the whole series (not per episode); the
        // note itself is per FILE, resolved fresh per episode below since each episode may be a
        // different file (or share one, per Phase 149 — same file, same resolved note either way).
        val seriesDecodeCapabilities = raviloDeviceService.decodeCapabilities(device.deviceId, device.jellyfinUserId)

        val seasons = seasonNums.map { seasonNum ->
            val eps: List<Episode> = uniqueEpisodes
                .filter { (it.seasonNumber ?: 0) == seasonNum }
                .sortedBy { it.episodeNumber ?: 0 }
            val seasonName = item.seasonNames[seasonNum] ?: "Season $seasonNum"
            val tvEpisodes = eps.map { ep ->
                // R133: episode still from jellystructure's on-disk <base>-thumb.jpg (series id + filename).
                // Bug fix (Phase 149): episodes sharing a file also share filename — pass episodeNumber
                // so the image route serves THIS episode's still, not always the group's first one.
                val stillUrl = RaviloImageUrl.still(item.id, ep.filename, ep.episodeNumber)
                val epVideoTrack = ep.tracks.firstOrNull { it.kind == dev.jellystructure.model.TrackKind.VIDEO }
                val epPlaybackNote = resolvePlaybackNote(
                    videoTrack = epVideoTrack,
                    capabilities = seriesDecodeCapabilities,
                    deviceId = device.deviceId,
                    deviceDisplayName = device.displayName,
                    fileId = ep.path,
                    startSampleStore = playbackStartSampleStore,
                )
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
                    segments = toTv(item.id, ep.filename, ep.episodeNumber ?: 0, ep.segments.stinger),  // Phase 150/163
                    playbackNote = epPlaybackNote,  // R222 (Phase 185, FR-185-9)
                )
            }
            // Last line of defence: whatever the metadata says, two episodes with the same id can never
            // reach a client — that is exactly what made the player's next-up card re-fire forever.
            Season(
                index = seasonNum,
                name = seasonName,
                episodes = tvEpisodes.distinctBy { it.id },
                // R194: null (not a URL that would 404) when this season has no poster on disk, so the
                // client can do a plain `season.posterUrl ?: card.posterUrl` fallback — same pattern as
                // every other image field here — with no need to speculatively probe for a 404.
                posterUrl = if (artwork.checkSeasonPoster(item, seasonNum))
                    RaviloImageUrl.seasonPoster(item.id, seasonNum, artwork.seasonPosterVersion(item, seasonNum)) else null,
            )
        }

        // Phase 200 (FR-200-4): union across every episode, not just the first one — episodes routinely
        // disagree about which languages they carry (64 of 182 multi-episode series, measured). Order
        // stays physical-track order of the first episode (season/episode order) that has each
        // language, so the strip doesn't reshuffle between visits.
        val episodesInOrder = uniqueEpisodes.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
        val seriesAudioLangs = unionLanguagesInFirstSeenOrder(episodesInOrder, dev.jellystructure.model.TrackKind.AUDIO)
        val seriesSubLangs = unionLanguagesInFirstSeenOrder(episodesInOrder, dev.jellystructure.model.TrackKind.SUBTITLE)
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
            logoUrl           = RaviloImageUrl.logo(item.id, artwork.assetVersion(item, "clearlogo")),  // R130/R133/R214
            logoInk = clearlogoInk?.inkFor(item),   // Phase 232 (FR-232-5/6)
            nextAiring        = nextAiring,
            ratingBadge       = item.ratingBadge(),  // Phase 106
            trailer           = item.tvTrailer(),  // Phase 130
            imdbRating        = item.tvImdbRating(),  // Phase 131
            originalLanguage  = item.originalLanguage,  // R181 — player's "Dubbed" audio badge
            genres            = item.genres,  // R221 — TMDB's own order preserved (see Models.kt doc)
        )
    }

    /**
     * R83: per-user play-state for the given Jellyfin ids.
     *
     * Phase 205 (FR-205-1/FR-205-2) — this used to fan out its own chunked `getUserDataBulk` calls with
     * no timeout anywhere on them, on every single detail/episode-row open (every season open was a
     * live, unbounded Jellyfin fan-out). It now reads [PlaystateCache], the same background-refreshed
     * whole-catalog map Home/Browse/Search read — no Jellyfin call, no timeout needed, and this can no
     * longer disagree with what a tile shows on Home for the same title (reachable before, whenever one
     * surface's own bound fired and another's didn't). An absent entry (this user has never been
     * refreshed yet) degrades to "not played" — the same harmless fallback `withPlaystate` already
     * applies everywhere else; unlike Continue Watching's row there is no confident-wrong-answer risk
     * here worth a FR-203-2-style omission marker.
     */
    fun getPlaystate(device: DeviceData, jellyfinIds: List<String>): Map<String, CardPlayState> {
        if (jellyfinIds.isEmpty()) return emptyMap()
        val all = PlaystateCache.get(device.jellyfinUserId)
        if (all.isEmpty()) return emptyMap()
        // Filter out path-based IDs (ep.jellyfinId was null at scan time → ep.id = ep.path)
        return jellyfinIds.asSequence().filterNot { it.startsWith('/') }.mapNotNull { id -> all[id]?.let { id to it } }.toMap()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    // R100: related items now come from MediaStore.relatedByGenre (genre-bucket index) instead of a
    // full-library scan per detail open; the old in-place relatedItems()/allItems() pair is gone.

    /** R142: overlay played / in-progress state onto More-Like-This tiles. Phase 205 — reads
     *  [PlaystateCache] directly (see [getPlaystate]'s doc); no longer a live Jellyfin call, so no
     *  timeout is needed here either. */
    private fun hydrateRelated(device: DeviceData, cards: List<MediaCard>): List<MediaCard> {
        if (cards.isEmpty()) return cards
        val ps = PlaystateCache.get(device.jellyfinUserId)
        if (ps.isEmpty()) return cards
        return cards.map { it.withPlaystate(ps) }
    }

    private fun MediaItem.toMediaCard(): MediaCard {
        val jId = jellyfinId
        val sonarrEnabled = configStore.current.sonarr?.enabled == true
        return MediaCard(
            id = jId ?: id,
            kind = when (kind) {
                MediaKind.TV_SHOW -> dev.jellystructure.shared.tv.MediaKind.SERIES
                MediaKind.MUSIC_VIDEO -> dev.jellystructure.shared.tv.MediaKind.MUSIC_VIDEO
                MediaKind.MOVIE -> dev.jellystructure.shared.tv.MediaKind.MOVIE
            },
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = ratingBadge()?.code,  // Phase 106
            ageRating = CertificationResolver.normalizedAge(configStore.current.metadata.ageRatingCascade, configStore.current.metadata.ageRatingMap, certifications),  // Phase 155
            posterUrl = RaviloImageUrl.poster(id, artwork.assetVersion(this, "poster")),     // R133/R214
            backdropUrl = RaviloImageUrl.backdrop(id, artwork.assetVersion(this, "backdrop")),
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

    /** Phase 150, rewritten Phase 163 — the resolved intro/credits markers off `media_segment`
     *  (`episodeKey`/`episodeNumber` = `""`/`0` for a movie, matching the table's own sentinel
     *  convention), shared by both the per-episode and per-movie mapping call sites above. `stinger`
     *  prefers a real timed `media_segment(kind='stinger')` row — nothing writes one yet, this phase
     *  doesn't add a stinger detector, but the trim view's manual edit path (kind is one of
     *  [SegmentKind.ALL]) can create one — and falls back to the legacy TMDB-keyword presence flag
     *  ([legacyStinger], `atMs == null`) untouched since Phase 150. */
    private fun toTv(itemId: String, episodeKey: String, episodeNumber: Int, legacyStinger: Stinger?): TvSegmentMarkers {
        val rows = segmentStore.segmentsForEpisode(itemId, episodeKey, episodeNumber)
        var intro = rows.firstOrNull { it.kind == SegmentKind.INTRO }
        var credits = rows.firstOrNull { it.kind == SegmentKind.CREDITS }
        // Phase 233 (FR-233-6) — credits that begin before the intro is over are a detection defect (775
        // rows on production when this was written). Until the segments lane repairs the pair, each side
        // is served only if a person wrote, locked or confirmed it: no marker is the pre-150 behaviour and
        // is harmless; "Skip Credits" over the opening theme is not.
        val i = intro; val c = credits
        if (i != null && c != null && SegmentPositionRules.creditsInsideIntro(i.startMs, i.endMs, c.startMs)) {
            if (!SegmentPositionRules.humanTouched(i.source, i.locked, i.checkedAt)) intro = null
            if (!SegmentPositionRules.humanTouched(c.source, c.locked, c.checkedAt)) credits = null
        }
        val stingerRow = rows.firstOrNull { it.kind == SegmentKind.STINGER }
        val stinger = when {
            stingerRow != null -> TvStinger(atMs = stingerRow.startMs, kind = legacyStinger?.kind ?: "after")
            legacyStinger != null -> TvStinger(atMs = legacyStinger.atMs, kind = legacyStinger.kind)
            else -> null
        }
        return TvSegmentMarkers(
            introStartMs = intro?.startMs,
            introEndMs = intro?.endMs,
            creditsStartMs = credits?.startMs,
            stinger = stinger,
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

