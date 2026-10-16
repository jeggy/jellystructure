package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.Composable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.monthKey
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** DEB-1482 (FR-3) — when a poster's upcoming episode airs, in the viewer's own calendar. */
internal sealed interface UpcomingWhen {
    /** Today, or 1–6 days ahead. */
    data class Weekday(val day: DayOfWeek) : UpcomingWhen
    /** 7 or more days ahead. */
    data class DayMonth(val day: Int, val month: Month) : UpcomingWhen
}

/**
 * DEB-1482 (FR-3) — the decidable half, pure so a fixed [now] and [zone] test it (the R270 `lastSeenKey` split).
 * [airUtc] is Sonarr's `airDateUtc`. The day is the one in [zone], not the UTC one: `…T01:00:00Z` on a Friday is
 * Thursday evening in New York. Returns null for a missing, unparseable or already-past date, and the tile then
 * keeps `tile.badge_soon` — a row Sonarr has not refreshed, or a server that predates the field, never shows a
 * day that has gone.
 */
internal fun upcomingWhen(airUtc: String?, now: Instant, zone: TimeZone): UpcomingWhen? {
    val air = airUtc?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val date = air.toLocalDateTime(zone).date
    val days = date.toEpochDays() - now.toLocalDateTime(zone).date.toEpochDays()
    return when {
        days < 0 -> null
        days <= 6 -> UpcomingWhen.Weekday(date.dayOfWeek)
        else -> UpcomingWhen.DayMonth(date.day, date.month)
    }
}

internal fun weekdayAbbrKey(d: DayOfWeek): String = when (d) {
    DayOfWeek.MONDAY -> "wd.abbr.mon"; DayOfWeek.TUESDAY -> "wd.abbr.tue"; DayOfWeek.WEDNESDAY -> "wd.abbr.wed"
    DayOfWeek.THURSDAY -> "wd.abbr.thu"; DayOfWeek.FRIDAY -> "wd.abbr.fri"; DayOfWeek.SATURDAY -> "wd.abbr.sat"
    else -> "wd.abbr.sun"
}

/** DEB-1482 (FR-3/4) — the badge's text: `Thu • S01E07`, `12 Nov • S03E01`, or today's `Soon • S01E07`. */
@Composable
internal fun upcomingBadgeText(label: String, airUtc: String?): String {
    val whenAir = upcomingWhen(airUtc, Clock.System.now(), TimeZone.currentSystemDefault())
        ?: return str("tile.badge_soon", mapOf("label" to label))
    val whenText = when (whenAir) {
        is UpcomingWhen.Weekday -> str(weekdayAbbrKey(whenAir.day))
        is UpcomingWhen.DayMonth -> str("date.day_month", mapOf("day" to whenAir.day.toString(), "month" to str(monthKey(whenAir.month))))
    }
    return str("tile.badge_upcoming", mapOf("when" to whenText, "label" to label))
}
