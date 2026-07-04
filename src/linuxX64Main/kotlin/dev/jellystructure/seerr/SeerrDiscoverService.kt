package dev.jellystructure.seerr

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.shared.tv.DiscoverDetail
import dev.jellystructure.shared.tv.DiscoverEntry
import dev.jellystructure.shared.tv.DiscoverResponse
import dev.jellystructure.shared.tv.DiscoverRow
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.RequestEntry
import kotlin.math.round

// Standard TMDB genre id → name tables (movie/tv lists differ) — stable, rarely-changing reference
// data; discover/search results only carry genreIds, so the first id is resolved to a display label
// here rather than round-tripping to TMDB for a name lookup per tile.
private val MOVIE_GENRES = mapOf(
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
    99 to "Documentary", 18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History",
    27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
    10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
)
private val TV_GENRES = mapOf(
    10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
    99 to "Documentary", 18 to "Drama", 10751 to "Family", 10762 to "Kids", 9648 to "Mystery",
    10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
    10767 to "Talk", 10768 to "War & Politics", 37 to "Western",
)

/**
 * R171 — the TV's Request tab: renders configured Seerr feeds (Phase 137), the Seerr-scoped search,
 * request detail and the request action, replacing the retired chart engine (Phase 136). Client never
 * calls Seerr directly (constitution) — every call here proxies through [SeerrClient].
 *
 * Deliberately **not** a persisted/reconciled acquisition source: every discover/search/detail call
 * re-derives each tile's status live from Seerr's own embedded `mediaInfo` (or the request-creation
 * response), rather than a background poller. See the R171 implementation note for why — in short, the
 * public Seerr API doesn't expose per-item download progress/ETA, so there is nothing a reconciler
 * would add over "the tab always shows what Seerr says right now."
 */
class SeerrDiscoverService(
    private val configStore: ConfigStore,
    private val seerrClient: SeerrClient,
    private val raviloConfigService: RaviloConfigService,
    private val mediaStore: MediaStore,
) {
    private fun seerr() = configStore.current.seerr?.takeIf { it.enabled && it.url.isNotBlank() }

    suspend fun getRequestFeeds(userId: String, isAdmin: Boolean): DiscoverResponse {
        val d = raviloConfigService.getConfig(userId).discover
        val seerr = seerr()
        if (!d.enabled || seerr == null) return DiscoverResponse(available = false, canRequest = false)

        val libByTmdb = libraryByTmdbId()
        val rows = d.feeds.filter { it.visible }.map { feed ->
            val page = seerrClient.discover(seerr.url, seerr.apiKey, feed.endpoint, feed.param)
            val entries = page.results
                .filter { it.mediaType == "movie" || it.mediaType == "tv" }
                .map { toDiscoverEntry(it, libByTmdb) }
            DiscoverRow(feedId = feed.id, feedName = feed.name, entries = entries)
        }
        return DiscoverResponse(available = true, canRequest = isAdmin || d.canRequest, rows = rows)
    }

    suspend fun search(query: String): List<DiscoverEntry> {
        val seerr = seerr() ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val libByTmdb = libraryByTmdbId()
        val page = seerrClient.search(seerr.url, seerr.apiKey, query)
        return page.results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { toDiscoverEntry(it, libByTmdb) }
    }

    suspend fun getEntry(mediaType: String, tmdbId: Int): DiscoverDetail? {
        val seerr = seerr() ?: return null
        val libByTmdb = libraryByTmdbId()
        return if (mediaType == "tv") {
            val d = seerrClient.tvDetails(seerr.url, seerr.apiKey, tmdbId) ?: return null
            val entry = RequestEntry(
                tmdbId = d.id, mediaKind = MediaKind.SERIES, title = d.name,
                year = d.firstAirDate?.take(4)?.toIntOrNull(), genre = d.genres.firstOrNull()?.name,
                rating = formatRating(d.voteAverage), posterPath = d.posterPath, backdropPath = d.backdropPath,
                overview = d.overview,
            )
            DiscoverDetail(
                entry = entry,
                acquisition = acquisitionFor(tmdbId, MediaKind.SERIES, d.title(), d.mediaInfo, libByTmdb),
                genres = d.genres.map { it.name },
                runtime = d.episodeRunTime.firstOrNull(),
                isSeries = true,
                cast = d.credits.cast.map(::toPerson),
            )
        } else {
            val d = seerrClient.movieDetails(seerr.url, seerr.apiKey, tmdbId) ?: return null
            val entry = RequestEntry(
                tmdbId = d.id, mediaKind = MediaKind.MOVIE, title = d.title,
                year = d.releaseDate?.take(4)?.toIntOrNull(), genre = d.genres.firstOrNull()?.name,
                rating = formatRating(d.voteAverage), posterPath = d.posterPath, backdropPath = d.backdropPath,
                overview = d.overview,
            )
            DiscoverDetail(
                entry = entry,
                acquisition = acquisitionFor(tmdbId, MediaKind.MOVIE, d.title, d.mediaInfo, libByTmdb),
                genres = d.genres.map { it.name },
                runtime = d.runtime,
                isSeries = false,
                cast = d.credits.cast.map(::toPerson),
            )
        }
    }

    /** [mediaType] is `"movie"` or `"tv"`; [title] is only used to label a FAILED record when Seerr
     *  rejects the request. Requesting obeys the per-user `canRequest` permission (Phase 137). */
    suspend fun request(userId: String, isAdmin: Boolean, mediaType: String, tmdbId: Int, title: String): AcquisitionRecord {
        val mediaKind = if (mediaType == "tv") MediaKind.SERIES else MediaKind.MOVIE
        val itemKey = "tmdb:$tmdbId"
        val d = raviloConfigService.getConfig(userId).discover
        if (!(isAdmin || d.canRequest)) {
            return AcquisitionRecord(itemKey, mediaKind, AcquisitionStatus.FAILED, tmdbId, title, reason = "not allowed to request — ask the owner", retryable = false)
        }
        val seerr = seerr()
            ?: return AcquisitionRecord(itemKey, mediaKind, AcquisitionStatus.FAILED, tmdbId, title, reason = "Seerr not configured", retryable = true)
        val result = seerrClient.createRequest(seerr.url, seerr.apiKey, mediaType, tmdbId)
            ?: return AcquisitionRecord(itemKey, mediaKind, AcquisitionStatus.FAILED, tmdbId, title, reason = "Seerr request failed", retryable = true)
        return AcquisitionRecord(itemKey, mediaKind, statusFromMediaInfoStatus(result.media.status), tmdbId, title)
    }

    private fun libraryByTmdbId(): Map<Int, MediaItem> =
        mediaStore.allItems().mapNotNull { item -> item.tmdbId?.let { it to item } }.toMap()

    private fun toDiscoverEntry(r: SeerrCatalogResult, libByTmdb: Map<Int, MediaItem>): DiscoverEntry {
        val mediaKind = if (r.mediaType == "tv") MediaKind.SERIES else MediaKind.MOVIE
        val title = r.title ?: r.name ?: ""
        val year = (r.releaseDate ?: r.firstAirDate)?.take(4)?.toIntOrNull()
        val genreTable = if (mediaKind == MediaKind.SERIES) TV_GENRES else MOVIE_GENRES
        val genre = r.genreIds.firstNotNullOfOrNull { genreTable[it] }
        val entry = RequestEntry(
            tmdbId = r.id, mediaKind = mediaKind, title = title, year = year, genre = genre,
            rating = formatRating(r.voteAverage), posterPath = r.posterPath, backdropPath = r.backdropPath,
            overview = r.overview,
        )
        return DiscoverEntry(entry, acquisitionFor(r.id, mediaKind, title, r.mediaInfo, libByTmdb))
    }

    private fun acquisitionFor(tmdbId: Int, mediaKind: MediaKind, title: String, mediaInfo: SeerrMediaInfo?, libByTmdb: Map<Int, MediaItem>): AcquisitionRecord {
        val itemKey = "tmdb:$tmdbId"
        val libItem = libByTmdb[tmdbId]
        if (libItem != null) {
            return AcquisitionRecord(itemKey, mediaKind, AcquisitionStatus.AVAILABLE, tmdbId, title, progress = 100, itemId = libItem.id)
        }
        return AcquisitionRecord(itemKey, mediaKind, statusFromMediaInfoStatus(mediaInfo?.status), tmdbId, title)
    }

    /** Seerr `MediaInfo.status`: 1=UNKNOWN 2=PENDING 3=PROCESSING 4=PARTIALLY_AVAILABLE 5=AVAILABLE
     *  6=DELETED. §B scope note: the public API has no per-item download progress/ETA and no distinct
     *  "declined" signal on this field, so PROCESSING folds queued/downloading/importing into one
     *  QUEUED bucket, and a declined request simply reads back as NOT_REQUESTED (re-requestable) rather
     *  than a flagged FAILED — see the R171 implementation note. */
    private fun statusFromMediaInfoStatus(status: Int?): AcquisitionStatus = when (status) {
        2 -> AcquisitionStatus.REQUESTED
        3 -> AcquisitionStatus.QUEUED
        4, 5 -> AcquisitionStatus.AVAILABLE
        else -> AcquisitionStatus.NOT_REQUESTED
    }

    private fun formatRating(voteAverage: Double?): String? =
        voteAverage?.takeIf { it > 0 }?.let { (round(it * 10) / 10.0).toString() }

    private fun toPerson(c: SeerrCastMember): Person = Person(
        id = c.id.toString(), name = c.name, role = c.character.takeIf { it.isNotBlank() },
        imageUrl = c.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" },
    )

    private fun SeerrTvDetails.title() = name
}
