package dev.jellystructure.tv

import dev.jellystructure.arr.ArrCalendarEpisode
import dev.jellystructure.arr.ArrCalendarImage
import dev.jellystructure.arr.ArrCalendarMovie
import dev.jellystructure.arr.ArrClient
import dev.jellystructure.arr.ArrQueueItem
import dev.jellystructure.auth.DeviceData
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.visibleTo
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.UpcomingDetail
import dev.jellystructure.shared.tv.UpcomingFeed
import dev.jellystructure.shared.tv.UpcomingItem
import dev.jellystructure.shared.tv.UpcomingStatus
import dev.jellystructure.tmdb.TmdbCastMember
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time
import dev.jellystructure.model.MediaKind as StoreKind
import dev.jellystructure.shared.tv.MediaKind as TvMediaKind

private const val UPCOMING_TTL_MS = 5 * 60_000L
private const val LOOKAHEAD_DAYS = 60
private const val LOOKBACK_DAYS = 183  // ~6 months, the "missing" bound (FR-H)
private const val EPISODE_MISSING_GRACE_DAYS = 1  // R168 FR-R168-4 — Sonarr has no per-episode availability flag

/**
 * R160 — assembles the Upcoming calendar: Sonarr's next monitored episodes + Radarr's monitored
 * movie releases, combined into one TV feed with no source attribution (the constitution's
 * "server-pushed state only" — the client never asks Sonarr/Radarr directly). Best-effort: an
 * outage on either service yields an empty contribution from that service, never blocks the other
 * or throws. Cached with a short TTL (the R86 `HomeFeedService` pattern) so opening the tab never
 * fans out a live *arr round-trip per view.
 */
class UpcomingService(
    private val configStore: ConfigStore,
    private val arrClient: ArrClient,
    private val mediaStore: MediaStore,
    private val tmdbClient: TmdbClient,
) {
    /** R167 — the tmdb/tvdb id behind one feed item, kept alongside the cache so [getDetail] can do
     *  a live TMDB enrichment (genres/runtime/cast) without a second *arr round-trip. */
    private data class DetailKey(val tmdbId: Int?, val tvdbId: Int?, val isSeries: Boolean)
    private data class CacheEntry(
        val feed: UpcomingFeed,
        val builtAt: Long,
        val keys: Map<String, DetailKey>,
        // R188 — the MediaItem an item resolved to (null = not yet held), kept so a per-device filter
        // can run against the already-cached feed instead of re-fetching/rebuilding per request.
        val matched: Map<String, dev.jellystructure.model.MediaItem?>,
    )
    private var cache: CacheEntry? = null

    /**
     * R188 — held items (a real [dev.jellystructure.model.MediaItem] behind them) go through the same
     * [dev.jellystructure.media.visibleTo] check every other device-facing read path uses. Not-yet-held
     * items (pure *arr calendar data, no library/tags to check yet) are hidden from any restricted
     * device outright — there's no reliable per-item signal to check them against pre-scan, so the safe
     * default is to only show that half of the feed to unrestricted (non-kids, full-library) profiles.
     */
    suspend fun getUpcoming(device: DeviceData): UpcomingFeed {
        val entry = cache?.takeIf { nowMs() - it.builtAt < UPCOMING_TTL_MS } ?: buildAndCache()
        return entry.feed.copy(
            items = entry.feed.items.filter { visibleToDevice(it.id, entry.matched, device) },
            missing = entry.feed.missing.filter { visibleToDevice(it.id, entry.matched, device) },
        )
    }

    private fun visibleToDevice(itemId: String, matched: Map<String, dev.jellystructure.model.MediaItem?>, device: DeviceData): Boolean {
        val item = matched[itemId]
        if (item != null) return item.visibleTo(device)
        return device.allowedLibraries == null && !device.isKids
    }

    /** R167 FR-R167-2 — Discover-detail parity: a live TMDB lookup (by tmdbId, resolving tvdb→tmdb
     *  for series) for genres/runtime/cast, best-effort (never throws; empty on any miss/failure so
     *  the client still has [UpcomingDetail.item] to render).
     *
     *  R188 — mirrors [getUpcoming]'s visibility filter: a restricted device asking for an item it
     *  couldn't see in the list gets the same 404 an unknown id would, not a data leak via direct
     *  id-guessing. */
    suspend fun getDetail(device: DeviceData, id: String): UpcomingDetail? {
        val entry = cache?.takeIf { nowMs() - it.builtAt < UPCOMING_TTL_MS } ?: buildAndCache()
        if (!visibleToDevice(id, entry.matched, device)) return null
        val item = (entry.feed.items + entry.feed.missing).firstOrNull { it.id == id } ?: return null
        val key = entry.keys[id]
        var genres = emptyList<String>()
        var runtime: Int? = null
        var cast = emptyList<Person>()
        val tmdbId = key?.tmdbId ?: key?.tvdbId?.let { runCatching { tmdbClient.findTvByTvdbId(it) }.getOrNull() }
        if (tmdbId != null) {
            if (key?.isSeries == true) {
                val det = runCatching { tmdbClient.getTvDetails(tmdbId) }.getOrNull()
                genres = det?.genres?.map { it.name } ?: emptyList()
                runtime = det?.episodeRunTime?.firstOrNull()
                cast = runCatching { tmdbClient.getTvCredits(tmdbId) }.getOrNull()?.map { it.toPerson() } ?: emptyList()
            } else {
                val det = runCatching { tmdbClient.getMovieDetails(tmdbId) }.getOrNull()
                genres = det?.genres?.map { it.name } ?: emptyList()
                runtime = det?.runtime
                cast = runCatching { tmdbClient.getMovieCredits(tmdbId) }.getOrNull()?.map { it.toPerson() } ?: emptyList()
            }
        }
        return UpcomingDetail(item = item, genres = genres, runtime = runtime, cast = cast)
    }

    private fun TmdbCastMember.toPerson() = Person(
        id = id.toString(),
        name = name,
        role = character.takeIf { it.isNotBlank() },
        imageUrl = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" },
    )

    private suspend fun buildAndCache(): CacheEntry {
        val (feed, keys, matched) = build()
        val entry = CacheEntry(feed, nowMs(), keys, matched)
        cache = entry
        return entry
    }

    private suspend fun build(): Triple<UpcomingFeed, Map<String, DetailKey>, Map<String, dev.jellystructure.model.MediaItem?>> {
        val cfg = configStore.current
        val sonarr = cfg.sonarr?.takeIf { it.enabled && it.url.isNotBlank() }
        val radarr = cfg.radarr?.takeIf { it.enabled && it.url.isNotBlank() }
        if (sonarr == null && radarr == null) return Triple(UpcomingFeed(enabled = false), emptyMap(), emptyMap())

        val today = todayUtcDateString()
        val start = shiftDate(today, -LOOKBACK_DAYS)
        val end = shiftDate(today, LOOKAHEAD_DAYS)

        // Ground truth for "do we actually hold this" is OUR OWN catalogue (MediaStore), not the
        // *arr's own hasFile bookkeeping — a file Radarr/Sonarr has that jellystructure hasn't
        // scanned yet isn't actually playable in Ravilo (R81/constitution: Ravilo streams via
        // Jellyfin, which requires our own scan first).
        val allItems = mediaStore.allItems()
        val seriesByTvdb: Map<Int, dev.jellystructure.model.MediaItem> = allItems
            .filter { it.kind == StoreKind.TV_SHOW && it.tvdbId != null && it.tvdbId != 0 }
            .associateBy { it.tvdbId!! }
        // Bug fix: fallback match key for a show TMDB hasn't cross-referenced to TheTVDB yet (so our own
        // scan recorded a null tvdbId) even though Sonarr — which resolves TVDB directly — has the right
        // one. Without this a fully-held series (all episodes on disk) shows as permanently "missing".
        val seriesByTmdb: Map<Int, dev.jellystructure.model.MediaItem> = allItems
            .filter { it.kind == StoreKind.TV_SHOW && it.tmdbId != null && it.tmdbId != 0 }
            .associateBy { it.tmdbId!! }
        val moviesByTmdb: Map<Int, dev.jellystructure.model.MediaItem> = allItems
            .filter { it.kind == StoreKind.MOVIE && it.tmdbId != null && it.tmdbId != 0 }
            .associateBy { it.tmdbId!! }

        val all = mutableListOf<UpcomingItem>()
        val keys = mutableMapOf<String, DetailKey>()
        // R188 — MediaItem behind each item (null = not yet held), so getUpcoming/getDetail can apply
        // the per-device visibility filter against the already-cached feed.
        val matchedItems = mutableMapOf<String, dev.jellystructure.model.MediaItem?>()

        if (sonarr != null) {
            val episodes = runCatching { arrClient.getSonarrCalendar(sonarr.url, sonarr.apiKey, start, end) }.getOrElse { emptyList() }
            val queue = runCatching { arrClient.getQueue(sonarr.url, sonarr.apiKey) }.getOrElse { emptyList() }
            for (ep in episodes) {
                val series = ep.series ?: continue
                if (!ep.monitored) continue
                val date = ep.airDate?.takeIf { it.isNotBlank() } ?: ep.airDateUtc?.take(10) ?: continue
                val queued = queue.firstOrNull { it.refId == ep.seriesId && it.season == ep.seasonNumber && it.episode == ep.episodeNumber }
                val matched = seriesByTvdb[series.tvdbId]
                    ?: series.tmdbId.takeIf { it != 0 }?.let { seriesByTmdb[it] }
                // itemId is the client-navigable id (matches DetailService.toMediaCard's `jellyfinId ?: id`
                // convention — /api/tv/series/{id} resolves via resolveByJellyfinId, NOT MediaItem.id).
                // posterUrl keys off MediaItem.id (the on-disk artwork cache key) — a different id scheme.
                // itemId (routing) stays series-level so a held series still opens the real detail page
                // (R160 §G) even for an episode we don't hold yet; `held` (below) is episode-specific.
                val itemId = matched?.let { it.jellyfinId ?: it.id }
                val held = matched != null && matched.episodes.any {
                    it.seasonNumber == ep.seasonNumber && it.episodeNumber == ep.episodeNumber
                }
                val itemIdStr = "ep-${ep.seriesId}-${ep.seasonNumber}-${ep.episodeNumber}"
                keys[itemIdStr] = DetailKey(tmdbId = series.tmdbId.takeIf { it != 0 }, tvdbId = series.tvdbId.takeIf { it != 0 }, isSeries = true)
                matchedItems[itemIdStr] = matched
                all += UpcomingItem(
                    id = itemIdStr,
                    kind = TvMediaKind.SERIES,
                    // Prefer the library's localized title/synopsis/genre when we hold the series
                    // (constitution's language-resolution algorithm already resolved them); fall back
                    // to the *arr's English string only when there's no catalogue match to localize from.
                    title = matched?.title ?: series.title,
                    year = series.year,
                    genre = matched?.genres?.firstOrNull() ?: series.genres.firstOrNull(),
                    itemId = itemId,
                    posterUrl = matched?.let { RaviloImageUrl.poster(it.id) },
                    date = date,
                    time = extractTime(ep.airDateUtc),
                    season = ep.seasonNumber,
                    episode = ep.episodeNumber,
                    episodeTitle = ep.title.takeIf { it.isNotBlank() },
                    network = series.network?.takeIf { it.isNotBlank() },
                    status = resolveEpisodeStatus(date, today, held, queued != null),
                    progress = queued?.let { downloadProgress(it) },
                    synopsis = matched?.overview?.takeIf { it.isNotBlank() } ?: ep.overview?.takeIf { it.isNotBlank() },
                    // R167 — not-held-only client-direct-CDN fallback art (no jellystructure proxy).
                    posterRemoteUrl = pickImage(series.images, "poster"),
                    backdropRemoteUrl = pickImage(series.images, "fanart"),
                )
            }
        }

        if (radarr != null) {
            val movies = runCatching { arrClient.getRadarrCalendar(radarr.url, radarr.apiKey, start, end) }.getOrElse { emptyList() }
            val queue = runCatching { arrClient.getQueue(radarr.url, radarr.apiKey) }.getOrElse { emptyList() }
            for (mv in movies) {
                if (!mv.monitored) continue
                val (date, releaseType) = pickMovieRelease(mv, today, start, end) ?: continue
                val queued = queue.firstOrNull { it.refId == mv.id }
                val matched = moviesByTmdb[mv.tmdbId]
                val itemId = matched?.let { it.jellyfinId ?: it.id }
                val itemIdStr = "mv-${mv.id}"
                keys[itemIdStr] = DetailKey(tmdbId = mv.tmdbId.takeIf { it != 0 }, tvdbId = null, isSeries = false)
                matchedItems[itemIdStr] = matched
                all += UpcomingItem(
                    id = itemIdStr,
                    kind = TvMediaKind.MOVIE,
                    // A movie match is item-level-correct (atomic) — held = matched != null stays right
                    // for the Radarr branch (FR-R166-1 #4); still prefer the localized library value for
                    // title/genre/synopsis when we hold it (FR-R166-2).
                    title = matched?.title ?: mv.title,
                    year = mv.year,
                    genre = matched?.genres?.firstOrNull() ?: mv.genres.firstOrNull(),
                    itemId = itemId,
                    posterUrl = matched?.let { RaviloImageUrl.poster(it.id) },
                    date = date,
                    releaseType = releaseType,
                    status = resolveMovieStatus(date, today, itemId != null, queued != null, mv.isAvailable),
                    progress = queued?.let { downloadProgress(it) },
                    synopsis = matched?.overview?.takeIf { it.isNotBlank() } ?: mv.overview?.takeIf { it.isNotBlank() },
                    // R167 — not-held-only client-direct-CDN fallback art (no jellystructure proxy).
                    posterRemoteUrl = pickImage(mv.images, "poster"),
                    backdropRemoteUrl = pickImage(mv.images, "fanart"),
                )
            }
        }

        // A past-dated item that's actively downloading (overdue but actively grabbing, not stuck)
        // still belongs in the forward agenda, not silently dropped between the two lists.
        val items = all.filter { it.date >= today || it.status == UpcomingStatus.DOWNLOADING }.sortedBy { it.date }
        val missing = all.filter { it.date < today && it.status == UpcomingStatus.MISSING }
            .sortedByDescending { it.date }
        return Triple(UpcomingFeed(enabled = true, items = items, missing = missing), keys, matchedItems)
    }

    private fun pickImage(images: List<ArrCalendarImage>, coverType: String): String? =
        images.firstOrNull { it.coverType == coverType }?.remoteUrl?.takeIf { it.isNotBlank() }

    /** Sonarr has no per-episode availability flag — a grace window avoids flagging MISSING the
     *  instant the air date passes (a just-aired episode legitimately isn't grabbable yet).
     *
     *  Bug fix: [held] (our own MediaStore — the ground truth for "can the user watch this now") must
     *  win over [queued] (the *arr's live queue). A completed-but-never-cleared queue entry (Radarr/
     *  Sonarr stuck on an import warning, `sizeLeft == 0` forever) previously showed as "Downloading
     *  100%" indefinitely for an item we'd already fully scanned in, and its still-`queued` status also
     *  exempted it from the past-date filter below — so an already-watched movie kept reappearing in
     *  the calendar a month after its release date. Verified live: Radarr's queue had a `trackedState:
     *  importPending` / `trackedStatus: warning` entry for "The Hitchhiker" (tmdbId 1285959) with
     *  `sizeLeft: 0`, even though the file was on disk and fully scanned. */
    private fun resolveEpisodeStatus(date: String, today: String, held: Boolean, queued: Boolean): UpcomingStatus = when {
        held -> UpcomingStatus.AVAILABLE
        queued -> UpcomingStatus.DOWNLOADING
        shiftDate(date, EPISODE_MISSING_GRACE_DAYS) < today -> UpcomingStatus.MISSING
        else -> UpcomingStatus.MONITORED
    }

    /** [isAvailable] is Radarr's own computed signal (bakes in the movie's `minimumAvailability`) —
     *  a "Released"-minimum movie that's only had a cinema release has `isAvailable == false` and is
     *  therefore never MISSING, even though [date] (from [pickMovieRelease]) may be in the past.
     *  [held] wins over [queued] for the same reason as [resolveEpisodeStatus] above. */
    private fun resolveMovieStatus(date: String, today: String, held: Boolean, queued: Boolean, isAvailable: Boolean): UpcomingStatus = when {
        held -> UpcomingStatus.AVAILABLE
        queued -> UpcomingStatus.DOWNLOADING
        isAvailable && date < today -> UpcomingStatus.MISSING
        else -> UpcomingStatus.MONITORED
    }

    private fun downloadProgress(q: ArrQueueItem): Int? {
        if (q.size <= 0.0) return null
        return (((q.size - q.sizeLeft) / q.size) * 100).toInt().coerceIn(0, 100)
    }

    /** "2026-07-10T20:00:00Z" → "20:00"; null/short input → null. */
    private fun extractTime(airDateUtc: String?): String? {
        if (airDateUtc == null || airDateUtc.length < 16) return null
        return airDateUtc.substring(11, 16)
    }

    /**
     * A movie carries up to 3 release dates (cinema/physical/digital); Radarr's calendar returns
     * all 3 unconditionally if ANY falls in [start, end], so we pick the one relevant date+type
     * ourselves: the soonest one still >= today, else (nothing upcoming) the most recent one < today
     * (for the missing check). Null when none of the 3 fall in the window at all.
     */
    private fun pickMovieRelease(mv: ArrCalendarMovie, today: String, start: String, end: String): Pair<String, String>? {
        val candidates = listOfNotNull(
            mv.digitalRelease?.take(10)?.takeIf { it.isNotBlank() }?.let { it to "digital" },
            mv.physicalRelease?.take(10)?.takeIf { it.isNotBlank() }?.let { it to "physical" },
            mv.inCinemas?.take(10)?.takeIf { it.isNotBlank() }?.let { it to "cinema" },
        ).filter { (d, _) -> d in start..end }
        if (candidates.isEmpty()) return null
        val upcoming = candidates.filter { (d, _) -> d >= today }.minByOrNull { it.first }
        return upcoming ?: candidates.maxByOrNull { it.first }
    }
}

// ── UTC date arithmetic (no kotlinx-datetime in this module — see SonarrEnrichService's identical
// pattern; duplicated rather than shared since both are small, self-contained, and file-private). ──

private fun isLeapYear(y: Int) = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
private val MONTH_DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

private fun epochDaysFromYmd(y: Int, m: Int, d: Int): Long {
    var days = 0L
    if (y >= 1970) { for (yy in 1970 until y) days += if (isLeapYear(yy)) 366 else 365 }
    else { for (yy in y until 1970) days -= if (isLeapYear(yy)) 366 else 365 }
    for (mm in 1 until m) days += MONTH_DAYS[mm - 1] + (if (mm == 2 && isLeapYear(y)) 1 else 0)
    return days + (d - 1)
}

private fun ymdFromEpochDays(days: Long): Triple<Int, Int, Int> {
    var d = days
    var y = 1970
    while (true) {
        val diy = if (isLeapYear(y)) 366L else 365L
        when {
            d >= diy -> { d -= diy; y++ }
            d < 0 -> { y--; d += if (isLeapYear(y)) 366L else 365L }
            else -> return run {
                var m = 1
                var dd = d
                for (md in MONTH_DAYS) {
                    val len = md + (if (m == 2 && isLeapYear(y)) 1 else 0)
                    if (dd < len) break
                    dd -= len; m++
                }
                Triple(y, m, (dd + 1).toInt())
            }
        }
    }
}

private fun formatDate(y: Int, m: Int, d: Int): String =
    "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"

private fun shiftDate(dateStr: String, deltaDays: Int): String {
    val p = dateStr.split("-")
    val days = epochDaysFromYmd(p[0].toInt(), p[1].toInt(), p[2].toInt()) + deltaDays
    val (y, m, d) = ymdFromEpochDays(days)
    return formatDate(y, m, d)
}

@OptIn(ExperimentalForeignApi::class)
private fun todayUtcDateString(): String {
    val (y, m, d) = ymdFromEpochDays(time(null) / 86400)
    return formatDate(y, m, d)
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = time(null) * 1000L
