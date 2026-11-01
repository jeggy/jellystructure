package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/*
 * DEB-2938 (DEB-2916, FR-8..FR-12, FR-15) — a newest-first series on the series page and the Continue tile.
 * The server sends the seasons and episodes ascending and says `newest_first`; everything below is the client's
 * reading of that one flag, kept out of the composables so a test reads it without drawing a page.
 */

/** FR-8 — the seasons newest first, Specials still last; each season's episodes newest first. A multi-episode
 *  file stays one slot with its parts in order, so a slot's first episode is still the file's first part. */
internal fun newestFirstSeasons(seasons: List<Season>): List<Season> {
    fun Season.newestFirst() = copy(episodes = episodeGroups(episodes).reversed().flatten())
    val regular = seasons.filter { it.index >= 1 }.sortedByDescending { it.index }
    val specials = seasons.filter { it.index < 1 }
    return (regular + specials).map { it.newestFirst() }
}

/** What the page draws: the wire detail, or on a newest-first series the same detail in FR-8's order. */
internal fun SeriesDetail.forDisplay(): SeriesDetail = if (newestFirst) copy(seasons = newestFirstSeasons(seasons)) else this

/**
 * FR-4 / FR-10 — the newest counted episode (R346: season 1 or later, with a Jellyfin item), whatever order
 * [seasons] arrive in: the last of the highest season's episodes by number; for a multi-episode file, its first part.
 */
internal fun newestEpisode(seasons: List<Season>): Episode? {
    for (season in seasons.filter { it.index >= 1 }.sortedByDescending { it.index }) {
        val eps = season.episodes.filter { !it.isCatalogOnly() }.sortedBy { it.episodeNumber }
        if (eps.isNotEmpty()) return episodeGroups(eps).last().first()
    }
    return null
}

/** FR-10 — the newest episode is in progress (unwatched, with a position): the button reads *Resume*. */
internal fun newestInProgress(newest: Episode?, overlay: Map<String, CardPlayState>): Boolean =
    newest != null && overlay[newest.id].let { it != null && !it.played && it.resumeMs > 0 }

/** FR-12's state pill beside the *Newest first* mark. */
internal enum class NewestState { NONE, NEW_EPISODE, CAUGHT_UP }

/**
 * FR-12 — *Caught up* when the newest episode is watched; *New Episode* when it is not and an older one is (the
 * *NEW* ribbon on its card follows the same condition); nothing for a never-started series, for the newest in
 * progress, and until the playstate overlay has landed.
 */
internal fun newestState(detail: SeriesDetail, overlay: Map<String, CardPlayState>): NewestState {
    if (!detail.newestFirst || overlay.isEmpty()) return NewestState.NONE
    val newest = newestEpisode(detail.seasons) ?: return NewestState.NONE
    if (overlay[newest.id]?.played == true) return NewestState.CAUGHT_UP
    if (newestInProgress(newest, overlay)) return NewestState.NONE
    val olderWatched = countedEpisodes(detail.seasons).any { it.id != newest.id && overlay[it.id]?.played == true }
    return if (olderWatched) NewestState.NEW_EPISODE else NewestState.NONE
}

/** The calendar year a date is compared against (UTC, as the Upcoming calendar's *today*). */
internal fun currentYearUtc(): Int = Clock.System.now().toLocalDateTime(TimeZone.UTC).year

/**
 * FR-9 — an air date (`yyyy-MM-dd`) as the viewer reads it: `date.weekday_day_month` (*Thu 29 Oct*), or
 * `date.month_day_year` when it is not in [thisYear]. Null for a blank, malformed or impossible date, so the
 * caller keeps the episode's code. [tr] is the viewer's string table.
 */
internal fun airDateText(iso: String?, thisYear: Int, tr: (key: String, vars: Map<String, String>) -> String): String? {
    val date = iso?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val day = date.day.toString()
    val month = tr(monthKey(date.month), emptyMap())
    return if (date.year == thisYear) {
        tr("date.weekday_day_month", mapOf("weekday" to tr(weekdayKey(date.dayOfWeek), emptyMap()), "day" to day, "month" to month))
    } else {
        tr("date.month_day_year", mapOf("month" to month, "day" to day, "year" to date.year.toString()))
    }
}

/** [airDateText] in the language [lang], for code that is not composable. */
internal fun airDateText(iso: String?, lang: String, thisYear: Int = currentYearUtc()): String? =
    airDateText(iso, thisYear) { k, v -> t(k, lang, v) }

/** [airDateText] in the viewer's language. */
@Composable
internal fun airDateLabel(iso: String?): String? {
    val lang = LocalLang.current
    return airDateText(iso, lang)
}

/** FR-9 — what names an episode where a viewer would see its code: its air date on a newest-first series, else [code]. */
internal fun episodeName(newestFirst: Boolean, ep: Episode, lang: String, code: () -> String): String =
    (if (newestFirst) airDateText(ep.airDate, lang) else null) ?: code()

/**
 * FR-10 — the primary button on a newest-first series: *Resume · Thu 29 Oct* while the newest is in progress, else
 * *Play newest · Thu 29 Oct* (a watched newest plays again from the start). [name] is the newest episode's
 * [episodeName]; without one the words stand alone.
 */
internal fun newestButtonLabel(name: String?, inProgress: Boolean, lang: String): String =
    t(if (inProgress) "action.resume" else "detail.play_newest", lang) + (name?.let { " · $it" } ?: "")
