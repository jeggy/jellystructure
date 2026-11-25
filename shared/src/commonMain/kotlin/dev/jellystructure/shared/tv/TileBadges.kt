package dev.jellystructure.shared.tv

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/*
 * DEB-5640 (FR-52) — the pure text behind a Home tile's badges and a row's count, moved here from `ravilo-ui` so the
 * Android TV app and the Samsung (`ravilo-screen`, no Compose) read one copy. [tr] is the viewer's string table.
 */

/** The calendar year a date is compared against (UTC, as the Upcoming calendar's *today*). */
fun currentYearUtc(): Int = Clock.System.now().toLocalDateTime(TimeZone.UTC).year

fun weekdayKey(d: DayOfWeek): String = when (d) {
    DayOfWeek.MONDAY -> "wd.abbr.mon"; DayOfWeek.TUESDAY -> "wd.abbr.tue"; DayOfWeek.WEDNESDAY -> "wd.abbr.wed"
    DayOfWeek.THURSDAY -> "wd.abbr.thu"; DayOfWeek.FRIDAY -> "wd.abbr.fri"; DayOfWeek.SATURDAY -> "wd.abbr.sat"
    else -> "wd.abbr.sun"
}

fun monthKey(m: Month): String = when (m) {
    Month.JANUARY -> "month.jan"; Month.FEBRUARY -> "month.feb"; Month.MARCH -> "month.mar"
    Month.APRIL -> "month.apr"; Month.MAY -> "month.may"; Month.JUNE -> "month.jun"
    Month.JULY -> "month.jul"; Month.AUGUST -> "month.aug"; Month.SEPTEMBER -> "month.sep"
    Month.OCTOBER -> "month.oct"; Month.NOVEMBER -> "month.nov"; else -> "month.dec"
}

/**
 * DEB-2938 (FR-9) — an air date (`yyyy-MM-dd`) as the viewer reads it: `date.weekday_day_month` (*Thu 29 Oct*), or
 * `date.month_day_year` when it is not in [thisYear]. Null for a blank, malformed or impossible date, so the
 * caller keeps the episode's code.
 */
fun airDateText(iso: String?, thisYear: Int, tr: (key: String, vars: Map<String, String>) -> String): String? {
    val date = iso?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val day = date.day.toString()
    val month = tr(monthKey(date.month), emptyMap())
    return if (date.year == thisYear) {
        tr("date.weekday_day_month", mapOf("weekday" to tr(weekdayKey(date.dayOfWeek), emptyMap()), "day" to day, "month" to month))
    } else {
        tr("date.month_day_year", mapOf("month" to month, "day" to day, "year" to date.year.toString()))
    }
}

/**
 * DEB-2938 (FR-15) — the Continue tile's corner badge for a card that carries `episode_air_date` (a newest-first
 * series): *New · Thu 29 Oct* (`tile.new_on`) when there is no progress, the date alone beside the progress bar when
 * there is. Null — keep the code — for a card with no date, or one the date will not parse.
 */
fun continueDateBadge(
    airDate: String?, progressPct: Float, thisYear: Int = currentYearUtc(),
    tr: (key: String, vars: Map<String, String>) -> String,
): String? {
    val date = airDateText(airDate, thisYear, tr) ?: return null
    return if (progressPct > 0f) date else tr("tile.new_on", mapOf("date" to date))
}

/**
 * DEB-3983 (FR-14) — the count that sits beside a content row's heading on the TV: the seed's true total when the
 * server sent one, else the items the row holds. A row of one item or fewer has no meaningful count, so none shows.
 */
fun rowHeadingCount(seedTotalCount: Int?, itemCount: Int): Int? = (seedTotalCount ?: itemCount).takeIf { it > 1 }
