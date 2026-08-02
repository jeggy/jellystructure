package dev.jellystructure.seerr

import dev.jellystructure.arr.AcquisitionService
import dev.jellystructure.arr.RequestLanguageService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
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
import dev.jellystructure.shared.tv.PickerOption
import dev.jellystructure.shared.tv.RequestEntry
import kotlin.math.round

// Standard TMDB genre id → name tables (movie/tv lists differ) — stable, rarely-changing reference
// data; discover/search results only carry genreIds, so the first id is resolved to a display label
// here rather than round-tripping to TMDB for a name lookup per tile. Not private: also the source
// for the admin Request-tab add-row genre dropdown (Phase 138, seerrGenreOptions below).
val MOVIE_GENRES = mapOf(
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
    99 to "Documentary", 18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History",
    27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
    10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
)
val TV_GENRES = mapOf(
    10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
    99 to "Documentary", 18 to "Drama", 10751 to "Family", 10762 to "Kids", 9648 to "Mystery",
    10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
    10767 to "Talk", 10768 to "War & Politics", 37 to "Western",
)

/** Phase 138 — genre options for the admin Request-tab add-row dropdown, sorted alphabetically
 *  (the raw maps above are declaration-ordered for readability, not display order). */
fun seerrGenreOptions(kind: String): List<PickerOption> =
    (if (kind == "tv") TV_GENRES else MOVIE_GENRES)
        .map { (id, name) -> PickerOption(id, name) }
        .sortedBy { it.name }

/**
 * R171 — the TV's Request tab: renders configured Seerr feeds (Phase 137), the Seerr-scoped search,
 * request detail and the request action, replacing the retired chart engine (Phase 136). Client never
 * calls Seerr directly (constitution) — every call here proxies through [SeerrClient].
 *
 * Every discover/search/detail *read* still re-derives each tile's status live from Seerr's own
 * embedded `mediaInfo` (or the request-creation response) rather than a persisted store — the public
 * Seerr API doesn't expose per-item download progress/ETA, so there'd be nothing to read back.
 *
 * Bug fix: [request] now bridges a successful Seerr request into [AcquisitionService]'s existing
 * Radarr/Sonarr queue reconciler (`trackSeerrRequest`) — that poller already runs continuously and
 * pushes live progress over the same WebSocket channel the client listens on; it just never knew Seerr-
 * originated requests existed, so the Request tab's badge stayed static ("Requested"/0%) until the page
 * was closed and reopened. This is a one-time registration call, not a new poller — the "no reconciler
 * needed" reasoning above was about *reading*, not about live progress push, which reuses the one that
 * already exists for the older direct-to-*arr flow.
 */
class SeerrDiscoverService(
    private val configStore: ConfigStore,
    private val seerrClient: SeerrClient,
    private val raviloConfigService: RaviloConfigService,
    private val mediaStore: MediaStore,
    // Phase 139 — null (rather than a required param) so this constructor doesn't ripple through every
    // existing call site; absent ⇒ the feature is fully inert (resolveIntentId/optionsFor on an empty
    // catalog already return null/no-catalog, same as if RequestLanguageService were never wired).
    private val requestLanguageService: RequestLanguageService? = null,
    private val requestIntentStore: RequestIntentStore? = null,
    // Bug fix: null-safe for the same reason as above — absent ⇒ requests behave exactly as before
    // (no live progress bridge), same as if AcquisitionService were never wired.
    private val acquisitionService: AcquisitionService? = null,
) {
    private fun seerr() = configStore.current.seerr?.takeIf { it.enabled && it.url.isNotBlank() }

    suspend fun getRequestFeeds(userId: String, isAdmin: Boolean, isKids: Boolean = false): DiscoverResponse {
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
        val viewerDefault = raviloConfigService.getBehaviourOverlay(userId).requestLanguage
        val (languages, defaultLanguage) = requestLanguageService?.optionsFor(viewerDefault, isKids) ?: (emptyList<dev.jellystructure.shared.tv.RequestLanguageOption>() to null)
        return DiscoverResponse(available = true, canRequest = isAdmin || d.canRequest, rows = rows, languages = languages, defaultLanguage = defaultLanguage)
    }

    /** Phase 139 §D.2 — the viewer's own not-yet-available requests, for the Request tab's "In progress"
     *  rail. Re-derives each entry's live status exactly like [getEntry] (no separate poller); a request
     *  that has since become AVAILABLE simply drops off this list on the next fetch. */
    suspend fun getMyRequests(userId: String): List<DiscoverEntry> {
        val store = requestIntentStore ?: return emptyList()
        val seerr = seerr() ?: return emptyList()
        val libByTmdb = libraryByTmdbId()
        return store.forUser(userId).mapNotNull { row ->
            if (row.mediaKind == MediaKind.SERIES) {
                val d = seerrClient.tvDetails(seerr.url, seerr.apiKey, row.tmdbId) ?: return@mapNotNull null
                val acq = acquisitionFor(row.tmdbId, MediaKind.SERIES, d.title(), d.mediaInfo, libByTmdb)
                if (acq.status == AcquisitionStatus.AVAILABLE) return@mapNotNull null
                val entry = RequestEntry(row.tmdbId, MediaKind.SERIES, d.title(), d.firstAirDate?.take(4)?.toIntOrNull(), d.genres.firstOrNull()?.name, formatRating(d.voteAverage), d.posterPath, d.backdropPath, d.overview)
                DiscoverEntry(entry, acq)
            } else {
                val d = seerrClient.movieDetails(seerr.url, seerr.apiKey, row.tmdbId) ?: return@mapNotNull null
                val acq = acquisitionFor(row.tmdbId, MediaKind.MOVIE, d.title, d.mediaInfo, libByTmdb)
                if (acq.status == AcquisitionStatus.AVAILABLE) return@mapNotNull null
                val entry = RequestEntry(row.tmdbId, MediaKind.MOVIE, d.title, d.releaseDate?.take(4)?.toIntOrNull(), d.genres.firstOrNull()?.name, formatRating(d.voteAverage), d.posterPath, d.backdropPath, d.overview)
                DiscoverEntry(entry, acq)
            }
        }
    }

    /**
     * R190 §C — up to 12 requestable titles featuring [personTmdbId] that the library doesn't already
     * hold, for the person-browse page's Seerr overflow row. Cast + crew credits deduped by (tmdbId,
     * mediaType) since a person can appear in both lists for the same title (e.g. actor-director); library
     * matches are filtered out entirely rather than shown as AVAILABLE — the row is "more to request",
     * not a mixed library+request grid.
     */
    suspend fun getPersonOverflow(personTmdbId: Int): List<DiscoverEntry> {
        val seerr = seerr() ?: return emptyList()
        val credits = seerrClient.personCombinedCredits(seerr.url, seerr.apiKey, personTmdbId) ?: return emptyList()
        val libByTmdb = libraryByTmdbId()
        return (credits.cast + credits.crew)
            .filter { (it.mediaType == "movie" || it.mediaType == "tv") && !libByTmdb.containsKey(it.id) }
            .distinctBy { it.id to it.mediaType }
            .take(12)
            .map { toDiscoverEntry(it.toCatalogResult(), libByTmdb) }
    }

    private fun SeerrPersonCredit.toCatalogResult() = SeerrCatalogResult(
        id = id, mediaType = mediaType, title = title, name = name, posterPath = posterPath,
        backdropPath = backdropPath, overview = overview, releaseDate = releaseDate,
        firstAirDate = firstAirDate, voteAverage = voteAverage, genreIds = genreIds, mediaInfo = null,
    )

    suspend fun search(query: String): List<DiscoverEntry> {
        val seerr = seerr() ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val libByTmdb = libraryByTmdbId()
        val page = seerrClient.search(seerr.url, seerr.apiKey, query)
        return page.results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { toDiscoverEntry(it, libByTmdb) }
    }

    suspend fun getEntry(mediaType: String, tmdbId: Int, userId: String? = null, isKids: Boolean = false): DiscoverDetail? {
        val seerr = seerr() ?: return null
        val libByTmdb = libraryByTmdbId()
        val viewerDefault = userId?.let { raviloConfigService.getBehaviourOverlay(it).requestLanguage }
        val (languages, defaultLanguage) = requestLanguageService?.optionsFor(viewerDefault, isKids) ?: (emptyList<dev.jellystructure.shared.tv.RequestLanguageOption>() to null)
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
                languages = languages,
                defaultLanguage = defaultLanguage,
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
                languages = languages,
                defaultLanguage = defaultLanguage,
            )
        }
    }

    /** Phase 139 §E — switch a still-waiting request to a different language: re-profiles the *arr item
     *  and re-searches ([RequestLanguageService.changeLanguage]), then updates the persisted intent so
     *  the flag/waiting-state the client sees next reflects the new choice. False on any failure (bad
     *  intent id, item not found in the *arr, etc.) — the caller responds accordingly, nothing partial. */
    suspend fun changeLanguage(userId: String, mediaType: String, tmdbId: Int, newLanguage: String): Boolean {
        val svc = requestLanguageService ?: return false
        val mediaKind = if (mediaType == "tv") MediaKind.SERIES else MediaKind.MOVIE
        val intent = svc.intent(newLanguage) ?: return false
        val ok = svc.changeLanguage(mediaKind, tmdbId, newLanguage)
        if (ok) requestIntentStore?.save(mediaKind, tmdbId, userId, newLanguage, intent.strict, dev.jellystructure.nowEpochSec())
        return ok
    }

    /**
     * [mediaType] is `"movie"` or `"tv"`; [title] is only used to label a FAILED record when Seerr
     * rejects the request. Requesting obeys the per-user `canRequest` permission (Phase 137).
     *
     * Phase 139 — [language] is the viewer's explicit pick (null = let the resolution precedence in
     * [RequestLanguageService.resolveIntentId] decide: per-viewer default → kids default → catalog
     * default). The resolved intent is persisted to [requestIntentStore] *before* the Seerr call so a
     * request that fails still remembers the viewer's choice for a retry, and turned into a
     * `profileId`/`tags` pair added to the Seerr payload — a feature-off catalog (no
     * [requestLanguageService] wired, or an empty catalog) resolves to `null` and reproduces exactly
     * today's plain request.
     */
    suspend fun request(userId: String, isAdmin: Boolean, mediaType: String, tmdbId: Int, title: String, language: String? = null, isKids: Boolean = false): AcquisitionRecord {
        val mediaKind = if (mediaType == "tv") MediaKind.SERIES else MediaKind.MOVIE
        val itemKey = "tmdb:$tmdbId"
        val d = raviloConfigService.getConfig(userId).discover
        if (!(isAdmin || d.canRequest)) {
            return AcquisitionRecord(itemKey, mediaKind, AcquisitionStatus.FAILED, tmdbId, title, reason = "not allowed to request — ask the owner", retryable = false)
        }
        val seerr = seerr()
            ?: return AcquisitionRecord(itemKey, mediaKind, AcquisitionStatus.FAILED, tmdbId, title, reason = "Seerr not configured", retryable = true)

        val langSvc = requestLanguageService
        val viewerDefault = raviloConfigService.getBehaviourOverlay(userId).requestLanguage
        val resolvedLanguage = langSvc?.resolveIntentId(language, viewerDefault, isKids)
        val resolvedIntent = resolvedLanguage?.let { langSvc?.intent(it) }
        val (profileId, tagIds) = if (langSvc != null && resolvedLanguage != null) langSvc.profileFor(resolvedLanguage, mediaKind) else (null to emptyList())
        // Bug fix: a steering intent (non-blank `match`, e.g. "Dansk") that couldn't be resolved to a
        // real scored *arr profile must reject the request outright — proceeding with profileId=null
        // would let Seerr fall back to its own default profile, silently enforcing no language
        // preference at all (see RequestLanguageService.profileFor's doc for the incident this fixes:
        // "Inside Out 2" requested as Dansk grabbed a plain English release with no error anywhere).
        if (resolvedIntent != null && resolvedIntent.match.isNotBlank() && profileId == null) {
            return AcquisitionRecord(
                itemKey, mediaKind, AcquisitionStatus.FAILED, tmdbId, title,
                reason = "\"${resolvedIntent.label}\" isn't set up in Radarr/Sonarr yet — ask the admin to check Settings ▸ Download tools ▸ Request languages",
                retryable = true,
            )
        }
        // Phase 156 — attribute the request to the actual Jellyfin user's own Seerr account (provisioned
        // on demand) rather than the shared API-key account, so Seerr's approval UI shows the real
        // requester and auto-approval depends on that person's own permissions. A resolution failure
        // (Seerr hiccup, missing MANAGE_USERS on the configured key) degrades to an unattributed request
        // rather than blocking the viewer.
        val seerrUserId = seerrClient.resolveUserId(seerr.url, seerr.apiKey, userId)
        if (seerrUserId == null) Logger.warn("Seerr: couldn't resolve/provision a Seerr account for Jellyfin user $userId — request will be unattributed", "seerr")
        val result = seerrClient.createRequest(seerr.url, seerr.apiKey, mediaType, tmdbId, profileId, tagIds, seerrUserId)
            ?: return AcquisitionRecord(itemKey, mediaKind, AcquisitionStatus.FAILED, tmdbId, title, reason = "Seerr request failed", retryable = true)
        // Bug fix: this used to save unconditionally *before* createRequest, so a failed Seerr call
        // (network hiccup, Seerr-side rejection, etc.) still left a local "you requested this" row —
        // jellystructure then treated the title as permanently "in progress" on the Request tab even
        // though Seerr never actually received a request for it (confirmed live: "Grænseløs" had a
        // request_intent row but zero mediaInfo on the Seerr side). Only persist the intent once Seerr
        // has actually accepted the request.
        if (resolvedLanguage != null && resolvedIntent != null) {
            requestIntentStore?.save(mediaKind, tmdbId, userId, resolvedLanguage, resolvedIntent.strict, dev.jellystructure.nowEpochSec())
        }
        val strictWaiting = resolvedIntent?.strict == true
        val (status, progress, eta) = statusFromMediaInfo(result.media)
        // Bug fix: register with the acquisition reconciler so this title's progress keeps updating
        // live (over the existing WebSocket channel) instead of staying frozen at whatever Seerr
        // reported at request time until the page is reloaded. No-op if already available.
        if (status != AcquisitionStatus.AVAILABLE) {
            acquisitionService?.trackSeerrRequest(mediaKind, tmdbId, title, userId, resolvedLanguage, strictWaiting)
        }
        return AcquisitionRecord(itemKey, mediaKind, status, tmdbId, title, progress = progress, eta = eta, language = resolvedLanguage, languageStrictWaiting = strictWaiting)
    }

    private suspend fun libraryByTmdbId(): Map<Int, MediaItem> =
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
        val (status, progress, eta) = statusFromMediaInfo(mediaInfo)
        // Phase 139 — the persisted intent (not Seerr's own status, which carries no language) drives
        // the flag + "waiting for a <label> release" state; AVAILABLE is already handled above.
        val row = requestIntentStore?.get(mediaKind, tmdbId)
        return AcquisitionRecord(itemKey, mediaKind, status, tmdbId, title, progress = progress, eta = eta, language = row?.languageId, languageStrictWaiting = row?.strict == true && status != AcquisitionStatus.AVAILABLE)
    }

    /**
     * Seerr `MediaInfo.status`: 1=UNKNOWN 2=PENDING 3=PROCESSING 4=PARTIALLY_AVAILABLE 5=AVAILABLE
     * 6=DELETED. A declined request simply reads back as NOT_REQUESTED (re-requestable) rather than a
     * flagged FAILED, since this field carries no distinct "declined" signal — see the R171 note.
     *
     * Bug fix (2026-07-05): status=3/PROCESSING used to collapse queued/downloading/importing into one
     * bare "in queue" — the R171-era assumption was that Seerr's public API exposes no per-item
     * progress/ETA. Verified live against a real in-progress movie that this is only half true: the flat
     * `status` field indeed can't distinguish them, but the *nested* `downloadStatus` array (Seerr
     * relaying Radarr/Sonarr's own download-client queue) carries real `size`/`sizeLeft` bytes whenever
     * a download is actually under way. When present, this now reports real DOWNLOADING + a genuine
     * percentage + ETA; status=3 with no entries yet (nothing grabbed) still correctly reads as QUEUED.
     */
    private fun statusFromMediaInfo(mediaInfo: SeerrMediaInfo?): Triple<AcquisitionStatus, Int, String?> = when (mediaInfo?.status) {
        2 -> Triple(AcquisitionStatus.REQUESTED, 0, null)
        3 -> {
            val items = mediaInfo.downloadStatus
            val totalSize = items.sumOf { it.size }
            if (items.isEmpty() || totalSize <= 0) Triple(AcquisitionStatus.QUEUED, 0, null)
            else {
                val totalLeft = items.sumOf { it.sizeLeft }
                val pct = (((totalSize - totalLeft).toDouble() / totalSize) * 100).toInt().coerceIn(0, 99)
                Triple(AcquisitionStatus.DOWNLOADING, pct, items.firstOrNull()?.timeLeft)
            }
        }
        4, 5 -> Triple(AcquisitionStatus.AVAILABLE, 100, null)
        else -> Triple(AcquisitionStatus.NOT_REQUESTED, 0, null)
    }

    private fun formatRating(voteAverage: Double?): String? =
        voteAverage?.takeIf { it > 0 }?.let { (round(it * 10) / 10.0).toString() }

    private fun toPerson(c: SeerrCastMember): Person = Person(
        id = c.id.toString(), name = c.name, role = c.character.takeIf { it.isNotBlank() },
        imageUrl = c.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" },
    )

    private fun SeerrTvDetails.title() = name
}
