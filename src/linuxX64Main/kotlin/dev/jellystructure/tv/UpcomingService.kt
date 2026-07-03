package dev.jellystructure.tv

import dev.jellystructure.arr.ArrCalendarEpisode
import dev.jellystructure.arr.ArrCalendarMovie
import dev.jellystructure.arr.ArrClient
import dev.jellystructure.arr.ArrQueueItem
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.shared.tv.UpcomingFeed
import dev.jellystructure.shared.tv.UpcomingItem
import dev.jellystructure.shared.tv.UpcomingStatus
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time
import dev.jellystructure.model.MediaKind as StoreKind
import dev.jellystructure.shared.tv.MediaKind as TvMediaKind

private const val UPCOMING_TTL_MS = 5 * 60_000L
private const val LOOKAHEAD_DAYS = 60
private const val LOOKBACK_DAYS = 183  // ~6 months, the "missing" bound (FR-H)

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
) {
    private data class CacheEntry(val feed: UpcomingFeed, val builtAt: Long)
    private var cache: CacheEntry? = null

    suspend fun getUpcoming(): UpcomingFeed {
        cache?.let { if (nowMs() - it.builtAt < UPCOMING_TTL_MS) return it.feed }
        val feed = build()
        cache = CacheEntry(feed, nowMs())
        return feed
    }

    private suspend fun build(): UpcomingFeed {
        val cfg = configStore.current
        val sonarr = cfg.sonarr?.takeIf { it.enabled && it.url.isNotBlank() }
        val radarr = cfg.radarr?.takeIf { it.enabled && it.url.isNotBlank() }
        if (sonarr == null && radarr == null) return UpcomingFeed(enabled = false)

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
        val moviesByTmdb: Map<Int, dev.jellystructure.model.MediaItem> = allItems
            .filter { it.kind == StoreKind.MOVIE && it.tmdbId != null && it.tmdbId != 0 }
            .associateBy { it.tmdbId!! }

        val all = mutableListOf<UpcomingItem>()

        if (sonarr != null) {
            val episodes = runCatching { arrClient.getSonarrCalendar(sonarr.url, sonarr.apiKey, start, end) }.getOrElse { emptyList() }
            val queue = runCatching { arrClient.getQueue(sonarr.url, sonarr.apiKey) }.getOrElse { emptyList() }
            for (ep in episodes) {
                val series = ep.series ?: continue
                if (!ep.monitored) continue
                val date = ep.airDate?.takeIf { it.isNotBlank() } ?: ep.airDateUtc?.take(10) ?: continue
                val queued = queue.firstOrNull { it.refId == ep.seriesId && it.season == ep.seasonNumber && it.episode == ep.episodeNumber }
                val itemId = seriesByTvdb[series.tvdbId]?.id
                all += UpcomingItem(
                    id = "ep-${ep.seriesId}-${ep.seasonNumber}-${ep.episodeNumber}",
                    kind = TvMediaKind.SERIES,
                    title = series.title,
                    year = series.year,
                    genre = series.genres.firstOrNull(),
                    itemId = itemId,
                    posterUrl = itemId?.let { RaviloImageUrl.poster(it) },
                    date = date,
                    time = extractTime(ep.airDateUtc),
                    season = ep.seasonNumber,
                    episode = ep.episodeNumber,
                    episodeTitle = ep.title.takeIf { it.isNotBlank() },
                    network = series.network?.takeIf { it.isNotBlank() },
                    status = resolveStatus(date, today, itemId != null, queued != null),
                    progress = queued?.let { downloadProgress(it) },
                    synopsis = ep.overview?.takeIf { it.isNotBlank() },
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
                val itemId = moviesByTmdb[mv.tmdbId]?.id
                all += UpcomingItem(
                    id = "mv-${mv.id}",
                    kind = TvMediaKind.MOVIE,
                    title = mv.title,
                    year = mv.year,
                    genre = mv.genres.firstOrNull(),
                    itemId = itemId,
                    posterUrl = itemId?.let { RaviloImageUrl.poster(it) },
                    date = date,
                    releaseType = releaseType,
                    status = resolveStatus(date, today, itemId != null, queued != null),
                    progress = queued?.let { downloadProgress(it) },
                    synopsis = mv.overview?.takeIf { it.isNotBlank() },
                )
            }
        }

        // A past-dated item that's actively downloading (overdue but actively grabbing, not stuck)
        // still belongs in the forward agenda, not silently dropped between the two lists.
        val items = all.filter { it.date >= today || it.status == UpcomingStatus.DOWNLOADING }.sortedBy { it.date }
        val missing = all.filter { it.date < today && it.status == UpcomingStatus.MISSING }
            .sortedByDescending { it.date }
        return UpcomingFeed(enabled = true, items = items, missing = missing)
    }

    private fun resolveStatus(date: String, today: String, held: Boolean, queued: Boolean): UpcomingStatus = when {
        queued -> UpcomingStatus.DOWNLOADING
        held -> UpcomingStatus.AVAILABLE
        date < today -> UpcomingStatus.MISSING
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
