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
 * R350 (FR-R350-3) — which season the page opens on: the season holding [primaryId], the episode the primary
 * button plays (the Continue Watching episode, one in progress, the first unwatched counted episode, or S01E01 on
 * a finished series — [primaryEpisodeId]). It used to be the first season with anything unwatched, so a series
 * whose button read *Resume · S13E19* opened on Season 1 and Down focused Season 1.
 *
 * With no primary episode (or one no season holds), R346 (FR-R346-3) / R343 (FR-R343-2): the first season (index
 * ≥ 1) with an unwatched counted episode; on a finished series (or one whose seasons have nothing unwatched) the
 * first season of index ≥ 1; Specials only when the series has nothing else. Returns a position in [seasons].
 */
internal fun openingSeasonIndex(seasons: List<Season>, overlay: Map<String, CardPlayState>, primaryId: String? = null): Int {
    if (primaryId != null) {
        val holding = seasons.indexOfFirst { s -> s.episodes.any { it.id == primaryId } }
        if (holding >= 0) return holding
    }
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

/**
 * R350 (FR-R350-3) — where the episode rail opens in a season: the slot holding [anchorId] (the card the viewer
 * comes back to, FR-R350-2), else the slot holding [primaryId] (the primary button's episode), else the slot of
 * the first unwatched episode, else the start. [groups] are the rail's slots (a multi-episode file is one).
 */
internal fun railOpeningIndex(
    groups: List<List<Episode>>,
    overlay: Map<String, CardPlayState>,
    primaryId: String?,
    anchorId: String? = null,
): Int {
    fun slotOf(id: String?) = id?.let { target -> groups.indexOfFirst { g -> g.any { it.id == target } } }?.takeIf { it >= 0 }
    return slotOf(anchorId)
        ?: slotOf(primaryId)
        ?: slotOf(groups.flatten().firstOrNull { ep -> overlay[ep.id]?.played != true }?.id)
        ?: 0
}

/** R350 (FR-R350-2) — the control that started playback on the series page, and the season open then. */
internal sealed class SeriesReturnFocus {
    abstract val seasonIdx: Int
    data class Play(override val seasonIdx: Int) : SeriesReturnFocus()
    data class Shuffle(override val seasonIdx: Int) : SeriesReturnFocus()
    /** [cardId] is the rail slot's first episode id (a multi-episode file's card counts as one). */
    data class Episode(override val seasonIdx: Int, val cardId: String) : SeriesReturnFocus()
}

/**
 * R350 (FR-R350-2) — held by the page's store, which outlives the page (only the top of the navigation stack is
 * composed, so coming back from the player rebuilds the page). Written when a control starts playback, read once
 * when the page is composed again.
 */
class SeriesReturnTarget {
    private var target: SeriesReturnFocus? = null
    internal fun remember(focus: SeriesReturnFocus) { target = focus }
    /** The control to land on, once. Forgets it either way. */
    internal fun take(): SeriesReturnFocus? = target.also { target = null }
}
