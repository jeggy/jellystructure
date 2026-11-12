package dev.jellystructure.cron

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.posix.getenv
import kotlin.time.Instant

/** FR-2.2 — a schedule is valid when it fires within this many years of now. Supersedes FR-166-4's 1500 days. */
const val SCHEDULE_HORIZON_YEARS = 100

/** FR-2.2 — fires are never closer than this; a setting may ask for a higher floor of its own. */
const val SCHEDULE_FLOOR_MINUTES = 15

/**
 * DEB-4387 (FR-4) — the one zone every schedule fires in: `[general] timezone` (an IANA name), else the server's `TZ`,
 * else UTC. A name the tz database does not know falls through to the next, never throws: a typo in the config file must
 * not stop the scheduler.
 */
object ScheduleZone {
    @OptIn(ExperimentalForeignApi::class)
    fun resolve(configured: String): TimeZone {
        fun zone(name: String?): TimeZone? = name?.trim()?.removePrefix(":")?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
        return zone(configured) ?: zone(getenv("TZ")?.toKString()) ?: TimeZone.UTC
    }
}

/**
 * DEB-4387 (FR-2, FR-4) — one evaluator for both forms of [Schedule]: the next *n* fires after a moment, in a zone.
 * A recurrence is computed arithmetically from its `FROM` (every 10 years costs what daily costs); a cron walks month by
 * month over the months it names. Both produce local dates in order, each date its local times, and the one conversion
 * to an instant applies FR-4.3: a time a DST change skips runs at the first minute after the gap, and a time it repeats
 * runs once (at its first occurrence).
 */
object ScheduleEvaluator {

    fun nextFires(schedule: Schedule, zone: TimeZone, afterEpochSec: Long, n: Int, horizonYears: Int = SCHEDULE_HORIZON_YEARS): List<Long> {
        if (n <= 0) return emptyList()
        val today = Instant.fromEpochSeconds(afterEpochSec).toLocalDateTime(zone).date
        // A day earlier covers a zone whose offset moved the local date under the caller's feet.
        val start = today.plus(DatePeriod(days = -1))
        val limit = today.plus(DatePeriod(years = horizonYears))
        val out = ArrayList<Long>(n)
        var last = afterEpochSec
        for (date in dates(schedule, start, limit)) {
            for (time in timesOn(schedule, date)) {
                val sec = toEpochSec(date, time, zone)
                if (sec > last) {
                    out += sec; last = sec
                    if (out.size == n) return out
                }
            }
        }
        return out
    }

    /** The first fire strictly after [afterEpochSec], or null when there is none within the horizon. */
    fun nextFire(schedule: Schedule, zone: TimeZone, afterEpochSec: Long): Long? = nextFires(schedule, zone, afterEpochSec, 1).firstOrNull()

    /**
     * FR-2.3 — due when the first fire after the last run has passed. A job that has never run is due, and fires missed in a
     * row are one run: the caller runs once and records the run, and the next fire after *that* is in the future.
     */
    fun isDue(schedule: Schedule, zone: TimeZone, lastRunEpochSec: Long?, nowEpochSec: Long): Boolean {
        if (lastRunEpochSec == null) return true
        val next = nextFire(schedule, zone, lastRunEpochSec) ?: return false
        return next <= nowEpochSec
    }

    /** When the job is next due: now if it is due already, else the first fire after its last run (null = never fires). */
    fun nextDue(schedule: Schedule, zone: TimeZone, lastRunEpochSec: Long?, nowEpochSec: Long): Long? {
        if (lastRunEpochSec == null) return nowEpochSec
        val next = nextFire(schedule, zone, lastRunEpochSec) ?: return null
        return maxOf(next, nowEpochSec)
    }

    // ── local dates, in order ────────────────────────────────────────────────────────────────────────────────────────────

    private fun dates(s: Schedule, start: LocalDate, limit: LocalDate): Sequence<LocalDate> = when (s) {
        is CronSchedule -> cronDates(s.expr, start, limit)
        is RecurrenceSchedule -> recurrenceDates(s.rule, start, limit)
    }

    private fun LocalDate.cronDow() = dayOfWeek.isoDayNumber % 7
    private fun LocalDate.isoIndex() = dayOfWeek.isoDayNumber - 1   // Monday = 0
    private fun LocalDate.addDays(n: Int) = plus(n, DateTimeUnit.DAY)
    private fun monthIndex(d: LocalDate) = d.year * 12 + d.month.ordinal
    private fun lastDay(y: Int, m: Int) = daysInMonth(y, m)

    private fun cronDates(e: CronExpr, start: LocalDate, limit: LocalDate): Sequence<LocalDate> = sequence {
        var idx = monthIndex(start)
        while (true) {
            val y = idx / 12
            val m = idx % 12 + 1
            if (LocalDate(y, m, 1) > limit) return@sequence
            if (m in e.months) {
                for (d in 1..lastDay(y, m)) {
                    val date = LocalDate(y, m, d)
                    if (date < start) continue
                    if (date > limit) return@sequence
                    val domMatch = e.domIsWildcard || d in e.daysOfMonth
                    val dowMatch = e.dowIsWildcard || date.cronDow() in e.daysOfWeek
                    // Standard cron: with both fields restricted either one fires the day; otherwise both must match.
                    val dayMatches = if (e.domIsWildcard || e.dowIsWildcard) domMatch && dowMatch else domMatch || dowMatch
                    if (dayMatches) yield(date)
                }
            }
            idx++
        }
    }

    private val EPOCH_MONDAY = LocalDate(1970, 1, 5)
    private val EPOCH_DAY = LocalDate(1970, 1, 1)

    private fun recurrenceDates(r: Recurrence, start: LocalDate, limit: LocalDate): Sequence<LocalDate> = sequence {
        val from = r.from?.let { LocalDate(it.year, it.month, it.day) }
        val n = r.interval

        fun ok(d: LocalDate) = d >= start && (from == null || d >= from)

        when (r.freq) {
            Freq.HOURLY, Freq.DAILY -> {
                val anchor = from ?: EPOCH_DAY
                // HOURLY is every day; its hours are filtered in [timesOn]. DAILY steps by its interval from the anchor.
                val step = if (r.freq == Freq.HOURLY) 1 else n
                var k = maxOf(0, anchor.daysUntil(start).floorDiv(step))
                while (true) {
                    val d = anchor.addDays(k * step)
                    if (d > limit) return@sequence
                    if (ok(d)) yield(d)
                    k++
                }
            }
            Freq.WEEKLY -> {
                val anchorWeek = (from ?: EPOCH_MONDAY).let { it.addDays(-it.isoIndex()) }
                val startWeek = start.addDays(-start.isoIndex())
                var k = maxOf(0, (anchorWeek.daysUntil(startWeek) / 7).floorDiv(n))
                while (true) {
                    val week = anchorWeek.addDays(k * n * 7)
                    if (week > limit) return@sequence
                    for (dow in ScheduleNames.WEEK_ORDER) {
                        if (r.byDay.none { it.dow == dow }) continue
                        val d = week.addDays((dow + 6) % 7)
                        if (ok(d)) yield(d)
                    }
                    k++
                }
            }
            Freq.MONTHLY -> {
                val anchorIdx = from?.let { monthIndex(it) } ?: (1970 * 12)
                var k = maxOf(0, (monthIndex(start) - anchorIdx).floorDiv(n))
                while (true) {
                    val idx = anchorIdx + k * n
                    val y = idx / 12
                    val m = idx % 12 + 1
                    if (LocalDate(y, m, 1) > limit) return@sequence
                    val len = lastDay(y, m)
                    val days = mutableSetOf<Int>()
                    when (val md = r.byMonthDay) {
                        -1 -> days += len
                        null -> for (b in r.byDay) days += monthDaysOf(y, m, len, b)
                        else -> if (md <= len) days += md
                    }
                    for (d in days.sorted()) {
                        val date = LocalDate(y, m, d)
                        if (date > limit) return@sequence
                        if (ok(date)) yield(date)
                    }
                    k++
                }
            }
            Freq.YEARLY -> {
                val anchorYear = from?.year ?: 1970
                var k = maxOf(0, (start.year - anchorYear).floorDiv(n))
                while (true) {
                    val y = anchorYear + k * n
                    if (LocalDate(y, 1, 1) > limit) return@sequence
                    val m = r.byMonth ?: 1
                    val len = lastDay(y, m)
                    val d = if (r.byMonthDay == -1) len else r.byMonthDay ?: 1
                    if (d <= len) {
                        val date = LocalDate(y, m, d)
                        if (date > limit) return@sequence
                        if (ok(date)) yield(date)
                    }
                    k++
                }
            }
        }
    }

    /** The days of month ([y], [m]) a MONTHLY `BYDAY` entry names: every such weekday, or just the *n*th / *n*th-to-last. */
    private fun monthDaysOf(y: Int, m: Int, len: Int, b: ByDay): List<Int> {
        val all = (1..len).filter { LocalDate(y, m, it).cronDow() == b.dow }
        val o = b.ordinal ?: return all
        val i = if (o > 0) o - 1 else all.size + o
        return listOfNotNull(all.getOrNull(i)?.takeIf { i >= 0 })
    }

    // ── local times on a date ────────────────────────────────────────────────────────────────────────────────────────────

    private fun timesOn(s: Schedule, date: LocalDate): List<LocalTime> = when (s) {
        is CronSchedule -> s.expr.hours.sorted().flatMap { h -> s.expr.minutes.sorted().map { LocalTime(h, it) } }
        is RecurrenceSchedule -> {
            val r = s.rule
            if (r.freq != Freq.HOURLY) listOf(LocalTime(r.hour, r.minute))
            else {
                // Hours count from midnight of FROM (or the epoch), by wall clock: an interval that does not divide 24 keeps its phase across days.
                val anchor = r.from?.let { LocalDate(it.year, it.month, it.day) } ?: EPOCH_DAY
                val base = anchor.daysUntil(date).toLong() * 24
                (0..23).filter { (base + it).mod(r.interval.toLong()) == 0L }.map { LocalTime(it, r.minute) }
            }
        }
    }

    /**
     * FR-4.3. A time inside a gap becomes the first minute after it; a repeated time takes its first occurrence
     * (kotlinx-datetime resolves an overlap to the earlier offset).
     */
    private fun toEpochSec(date: LocalDate, time: LocalTime, zone: TimeZone): Long {
        val local = LocalDateTime(date, time)
        var t = local.toInstant(zone).epochSeconds
        if (Instant.fromEpochSeconds(t).toLocalDateTime(zone) == local) return t
        // In a gap the library shifts forward by the gap's length; walk back to the first minute whose local time is past [local].
        while (Instant.fromEpochSeconds(t - 60).toLocalDateTime(zone) >= local) t -= 60
        return t
    }
}

private val RUN_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * DEB-4402 (FR-3.8, FR-4.2) — one fire as the picker's *Next five runs* shows it, `Mon 16 Nov 2026 · 04:00`, in the schedule
 * zone and never the browser's. The admin works out no times itself, so the server words them. The ` · ` is the one divider.
 */
fun runLabel(epochSec: Long, zone: TimeZone): String {
    val t = Instant.fromEpochSeconds(epochSec).toLocalDateTime(zone)
    val day = RUN_DAYS[t.date.dayOfWeek.isoDayNumber - 1]
    val month = ScheduleNames.MONTHS[t.date.month.ordinal].take(3)
    return "$day ${t.date.day} $month ${t.date.year} · ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/** Validation outcome for a schedule string (FR-2.2) — shared by every save route (422) and `validate-schedule`, so they cannot disagree. */
sealed interface ScheduleCheck {
    data class Ok(val schedule: Schedule, val sentence: String, val canonical: String, val nextRuns: List<Long>) : ScheduleCheck {
        /** Kept for the callers that read the Phase 166 field name. */
        val description: String get() = sentence
    }

    data class Invalid(val message: String) : ScheduleCheck
}

private fun floorWords(minutes: Int) = if (minutes >= 60 && minutes % 60 == 0) (minutes / 60).let { if (it == 1) "hour" else "$it hours" } else "$minutes minutes"

/**
 * A blank [text] means "off" and is valid, but has nothing to describe or preview — callers check for blank before calling
 * this (every call site already treats blank specially). [floorMinutes] is the setting's own floor, never below 15 (FR-2.2).
 */
fun validateSchedule(text: String, nowEpochSec: Long, zone: TimeZone = TimeZone.UTC, floorMinutes: Int = SCHEDULE_FLOOR_MINUTES): ScheduleCheck {
    val floor = maxOf(floorMinutes, SCHEDULE_FLOOR_MINUTES)
    val schedule = when (val p = parseSchedule(text)) {
        is ScheduleParse.Invalid -> return ScheduleCheck.Invalid(p.message)
        ScheduleParse.Off -> return ScheduleCheck.Invalid("The schedule is empty.")
        is ScheduleParse.Ok -> p.schedule
    }
    val runs = ScheduleEvaluator.nextFires(schedule, zone, nowEpochSec, 10)
    return when {
        runs.isEmpty() -> ScheduleCheck.Invalid("This schedule never fires within the next $SCHEDULE_HORIZON_YEARS years.")
        runs.zipWithNext().any { (a, b) -> b - a < floor * 60L } ->
            ScheduleCheck.Invalid("A run takes minutes to hours — schedules more frequent than every ${floorWords(floor)} aren't allowed.")
        else -> ScheduleCheck.Ok(schedule, schedule.sentence, schedule.canonical, runs.take(5))
    }
}
