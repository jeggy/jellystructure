package dev.jellystructure.cron

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.localtime_r
import platform.posix.mktime
import platform.posix.time_tVar
import platform.posix.tm

private const val SEARCH_HORIZON_DAYS = 1500
private const val MIN_INTERVAL_SECONDS = 15 * 60

/**
 * Phase 166 (FR-166-2) — evaluates a parsed [CronExpr] against LOCAL wall-clock time (the same basis
 * the pre-Phase-166 hand-rolled matcher used — the clock the admin reads), walking forward day by day
 * from [afterEpochSec]'s date. Bounded at [SEARCH_HORIZON_DAYS] days; an expression that never fires
 * within that window (e.g. `0 0 30 2 *` — February never has a 30th) returns null rather than looping
 * forever. Every `mktime` call sets `tm_isdst = -1` first so a candidate on a DST-transition day
 * resolves correctly, matching this codebase's established convention for exactly this problem.
 *
 * Honors standard cron's day-of-month/day-of-week OR rule: when BOTH fields are restricted (neither is
 * textually `*`), a day matches if EITHER matches; when only one (or neither) is restricted, both must
 * match (a wildcard field trivially matches everything).
 */
@OptIn(ExperimentalForeignApi::class)
fun nextFireEpochSec(expr: CronExpr, afterEpochSec: Long): Long? = memScoped {
    val sortedHours = expr.hours.sorted()
    val sortedMinutes = expr.minutes.sorted()
    if (sortedHours.isEmpty() || sortedMinutes.isEmpty()) return@memScoped null

    val nowVar = alloc<time_tVar>().apply { value = afterEpochSec.convert() }
    val tm = alloc<tm>()
    if (localtime_r(nowVar.ptr, tm.ptr) == null) return@memScoped null
    tm.tm_hour = 0; tm.tm_min = 0; tm.tm_sec = 0
    tm.tm_isdst = -1
    mktime(tm.ptr) // normalize to local midnight of afterEpochSec's date; refreshes tm_wday/tm_mon/tm_year

    var dayGuard = 0
    while (dayGuard <= SEARCH_HORIZON_DAYS) {
        val monthOk = (tm.tm_mon + 1) in expr.months
        val domMatch = expr.domIsWildcard || tm.tm_mday in expr.daysOfMonth
        val dowMatch = expr.dowIsWildcard || tm.tm_wday in expr.daysOfWeek
        val dayMatches = monthOk && if (expr.domIsWildcard || expr.dowIsWildcard) (domMatch && dowMatch) else (domMatch || dowMatch)

        if (dayMatches) {
            for (h in sortedHours) {
                for (m in sortedMinutes) {
                    tm.tm_hour = h; tm.tm_min = m; tm.tm_sec = 0; tm.tm_isdst = -1
                    val candidate = mktime(tm.ptr).convert<Long>()
                    if (candidate > afterEpochSec) return@memScoped candidate
                }
            }
        }

        tm.tm_hour = 0; tm.tm_min = 0; tm.tm_sec = 0
        tm.tm_mday += 1
        tm.tm_isdst = -1
        mktime(tm.ptr) // normalizes month/year rollover and refreshes tm_wday for the new day
        dayGuard++
    }
    null
}

/** Up to [n] consecutive fire times after [afterEpochSec] — the Settings "next N runs" preview and the
 *  ≥15-minute-apart floor check (FR-166-4) both use this. Stops early if the expression runs out of
 *  fires within the search horizon (a bounded result, not an error — the caller distinguishes "empty
 *  because it never fires" via [validateSchedule]). */
fun nextNFireEpochSecs(expr: CronExpr, afterEpochSec: Long, n: Int): List<Long> {
    val out = ArrayList<Long>(n)
    var after = afterEpochSec
    repeat(n) {
        val next = nextFireEpochSec(expr, after) ?: return out
        out.add(next)
        after = next
    }
    return out
}

/** Validation outcome for a schedule string (FR-166-4) — shared by `PUT /api/config` (422 on save) and
 *  the live-preview endpoint the Settings UI polls while typing, so the two can never disagree about
 *  whether a schedule is acceptable. */
sealed interface ScheduleCheck {
    data class Ok(val description: String, val nextRuns: List<Long>) : ScheduleCheck
    data class Invalid(val message: String) : ScheduleCheck
}

/** A blank [cron] means "scheduling off" and is valid, but has nothing to describe or preview — callers
 *  must check for blank before calling this (both call sites already treat blank specially). */
fun validateSchedule(cron: String, nowEpochSec: Long): ScheduleCheck = when (val parsed = parseCron(cron)) {
    is CronParse.Invalid -> ScheduleCheck.Invalid(parsed.message)
    is CronParse.Ok -> {
        val runs = nextNFireEpochSecs(parsed.expr, nowEpochSec, 10)
        when {
            runs.isEmpty() -> ScheduleCheck.Invalid("This schedule never fires within the next $SEARCH_HORIZON_DAYS days.")
            runs.zipWithNext().any { (a, b) -> b - a < MIN_INTERVAL_SECONDS } ->
                ScheduleCheck.Invalid("A pipeline run takes minutes to hours — schedules more frequent than every 15 minutes aren't allowed.")
            else -> ScheduleCheck.Ok(parsed.description, runs.take(5))
        }
    }
}
