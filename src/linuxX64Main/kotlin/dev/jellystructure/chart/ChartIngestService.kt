package dev.jellystructure.chart

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.shared.tv.ChartEntry
import dev.jellystructure.shared.tv.ChartListSpec
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Trend
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

/**
 * Phase 57 — ingest + normalize + TMDB-resolve + library-match. Per provider × list: fetch the feed,
 * apply the admin override map first, resolve by title+kind search (the TSV has no tmdbId/year),
 * enrich (backdrop/overview/year via TMDB details), compute the trend from weekly history, and upsert.
 * Country lists carry no views by construction. Failures are per-list and non-fatal.
 */
class ChartIngestService(
    private val configStore: ConfigStore,
    private val registry: ChartRegistry,
    private val tmdb: TmdbClient,
    private val store: ChartStore,
    private val mediaStore: MediaStore,
) {
    suspend fun refresh(region: String, force: Boolean = false) {
        val cfg = configStore.current.discover
        if (cfg?.enabled == false) return // explicitly disabled; absent = opt-in by default
        val providers = cfg?.providers ?: listOf("netflix")
        val libByTmdb = mediaStore.allItems().mapNotNull { i -> i.tmdbId?.let { it to i.id } }.toMap()
        for (provider in registry.enabled(providers)) {
            for (spec in provider.availableLists(region)) {
                runCatching {
                    val fetch = provider.fetch(spec)
                    if (fetch.entries.isEmpty()) return@runCatching
                    if (!force && store.listWeek(spec.id) == fetch.week) return@runCatching // week-gate
                    val kind = if (spec.category == "series") MediaKind.SERIES else MediaKind.MOVIE
                    val entries = fetch.entries.map { resolve(provider.id, spec, kind, it, fetch.week, libByTmdb) }
                    val now = nowMs()
                    store.replaceList(spec.id, fetch.week, entries, now)
                    store.appendHistory(spec.id, fetch.week, entries)
                    Logger.info("chart: ingested ${spec.id} week=${fetch.week} (${entries.size} entries)", "chart")
                }.onFailure { Logger.warn("chart ingest failed for ${spec.id}: ${it.message}", "chart") }
            }
        }
    }

    private suspend fun resolve(
        providerId: String,
        spec: ChartListSpec,
        kind: MediaKind,
        raw: RawChartEntry,
        week: String,
        libByTmdb: Map<Int, String>,
    ): ChartEntry {
        // override map wins; else title+kind search (no year — the TSV has none)
        val overrideId = store.getOverride(providerId, raw.title, kind.name)
        val match = if (overrideId != null) overrideId to 1f else searchTmdb(raw.title, kind)
        val tmdbId = match?.first
        val confidence = match?.second ?: 0f

        var year: Int? = null
        var backdrop: String? = null
        var overview: String? = null
        if (tmdbId != null) {
            if (kind == MediaKind.MOVIE) {
                tmdb.getMovieDetails(tmdbId)?.let { year = it.releaseDate.take(4).toIntOrNull(); backdrop = it.backdropPath; overview = it.overview }
            } else {
                tmdb.getTvDetails(tmdbId)?.let { year = it.firstAirDate.take(4).toIntOrNull(); backdrop = it.backdropPath; overview = it.overview }
            }
        }

        val prior = store.priorRank(spec.id, raw.title, week)
        val trend = when {
            raw.isNew -> Trend.NEW
            prior == null -> Trend.SAME
            prior > raw.rank -> Trend.UP
            prior < raw.rank -> Trend.DOWN
            else -> Trend.SAME
        }

        return ChartEntry(
            listId = spec.id,
            rank = raw.rank,
            title = raw.title,
            kind = kind,
            year = year,
            tmdbId = tmdbId,
            tmdbConfidence = confidence,
            itemId = tmdbId?.let { libByTmdb[it] },
            weeksOnChart = raw.weeksOnChart,
            trend = trend,
            isNew = raw.isNew,
            views = raw.views,
            backdropPath = backdrop,
            overview = overview,
        )
    }

    /** title+kind TMDB search → (id, confidence by title similarity). */
    private suspend fun searchTmdb(title: String, kind: MediaKind): Pair<Int, Float>? =
        if (kind == MediaKind.MOVIE) {
            tmdb.searchMovie(title, null)?.let { it.id to similarity(title, it.title) }
        } else {
            tmdb.searchTv(title, null)?.let { it.id to similarity(title, it.name) }
        }

    private fun similarity(a: String, b: String): Float {
        val x = a.trim().lowercase(); val y = b.trim().lowercase()
        return when {
            x == y -> 1f
            x.contains(y) || y.contains(x) -> 0.7f
            else -> 0.4f
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
