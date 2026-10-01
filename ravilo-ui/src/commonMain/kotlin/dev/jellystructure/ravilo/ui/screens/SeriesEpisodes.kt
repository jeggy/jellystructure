package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail

/**
 * R346 (FR-R346-1) — a catalog row with no Jellyfin item: the server gives it a path as its id
 * (`"${path}#${episode}"`, `DetailService.getSeriesDetail`). It can never be played or ticked.
 */
internal fun Episode.isCatalogOnly(): Boolean = id.startsWith("/") || id.contains('#')

/**
 * R346 (FR-R346-1) — the episodes that count: a season of 1 or more (specials, and episodes with no season,
 * are season 0) and a Jellyfin item. The watched counts, the opening season, the Play fallback and all of
 * R343 (finished, Start over's target, the shuffle set) use this one list, in season and episode order.
 */
internal fun countedEpisodes(seasons: List<Season>): List<Episode> =
    seasons.filter { it.index >= 1 }.sortedBy { it.index }.flatMap { s -> s.episodes.filter { !it.isCatalogOnly() } }

/** R343 (FR-R343-1) — every counted episode is watched by this viewer. False until the overlay lands. */
internal fun seriesFinished(counted: List<Episode>, overlay: Map<String, CardPlayState>): Boolean =
    counted.isNotEmpty() && counted.all { overlay[it.id]?.played == true }

/**
 * R346 (FR-R346-3) / R343 (FR-R343-2) — which season the page opens on: the first season (index ≥ 1) with an
 * unwatched counted episode; on a finished series (or one whose seasons have nothing unwatched) the first
 * season of index ≥ 1; Specials only when the series has nothing else. Returns a position in [seasons].
 */
internal fun openingSeasonIndex(seasons: List<Season>, overlay: Map<String, CardPlayState>): Int {
    val regular = seasons.withIndex().filter { it.value.index >= 1 }
    if (regular.isEmpty()) return 0
    return regular.firstOrNull { (_, s) -> s.episodes.any { ep -> !ep.isCatalogOnly() && overlay[ep.id]?.played != true } }?.index
        ?: regular.minBy { it.value.index }.index
}

/**
 * R346 (FR-R346-3) / R306 (FR-R306-5) / R343 (FR-R343-2) — the episode the primary button plays.
 *
 * On a finished series: the first counted episode (*Start over*). Otherwise, in this order: the episode this
 * series' Continue Watching tile names; an episode in progress (a started special keeps its place here); the
 * first unwatched counted episode; the first counted episode; any episode at all.
 */
internal fun primaryEpisodeId(detail: SeriesDetail, overlay: Map<String, CardPlayState>): String? {
    val all = detail.seasons.flatMap { it.episodes }
    val counted = countedEpisodes(detail.seasons)
    if (seriesFinished(counted, overlay)) return counted.first().id
    return overlay[detail.card.id]?.continueEpisodeId?.takeIf { cid -> all.any { it.id == cid } }
        ?: all.firstOrNull { ep -> overlay[ep.id].let { ps -> ps != null && !ps.played && ps.resumeMs > 0 } }?.id
        ?: counted.firstOrNull { ep -> overlay[ep.id]?.played != true }?.id
        ?: counted.firstOrNull()?.id
        ?: all.firstOrNull()?.id
}

/** R346 / R343 — whether the viewer has begun this series at all (anything watched or in progress): the
 *  primary button then reads *Resume*, else *Play*. */
internal fun seriesStarted(counted: List<Episode>, overlay: Map<String, CardPlayState>): Boolean =
    counted.any { ep -> overlay[ep.id].let { ps -> ps != null && (ps.played || ps.resumeMs > 0) } }
