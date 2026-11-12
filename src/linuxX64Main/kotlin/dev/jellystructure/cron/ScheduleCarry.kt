package dev.jellystructure.cron

import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * DEB-4387 (FR-5) — the two conversions between the old interval words (`hourly` · `daily` · `weekly`) and the shared schedule,
 * for Music, Audiobooks, Recommendations and Suggestions.
 *
 *  - [fromWord], once at load: `hourly` is `0 * * * *`; `daily` and `weekly` are *daily at* / *weekly on* the hour, minute and
 *    weekday of the last run, so a job that runs at 03:12 today keeps running at 03:12. With no run yet: 04:00, and Monday
 *    (DEB-2823 FR-3.4).
 *  - [legacyWord], on every save for one release: the word a rolled-back image reads, derived from the schedule (DEB-1664 FR-2.4,
 *    DEB-2823 FR-3.5).
 */
object ScheduleCarry {
    private const val DEFAULT_HOUR = 4
    private const val MONDAY = 1

    fun fromWord(word: String, lastRunEpochSec: Long?, zone: TimeZone): String {
        if (word == "hourly") return "0 * * * *"
        val last = lastRunEpochSec?.let { Instant.fromEpochSeconds(it).toLocalDateTime(zone) }
        val hour = last?.hour ?: DEFAULT_HOUR
        val minute = last?.minute ?: 0
        return if (word == "weekly") "$minute $hour * * ${last?.let { it.date.dayOfWeek.isoDayNumber % 7 } ?: MONDAY}" else "$minute $hour * * *"
    }

    /** `hourly` when the fires are at most 90 minutes apart (and [allowHourly]), `daily` up to 36 hours, `weekly` beyond. Null for a blank or unusable schedule: the caller keeps the word it has. */
    fun legacyWord(schedule: String?, zone: TimeZone, nowEpochSec: Long, allowHourly: Boolean): String? {
        val parsed = (parseSchedule(schedule.orEmpty()) as? ScheduleParse.Ok)?.schedule ?: return null
        val fires = ScheduleEvaluator.nextFires(parsed, zone, nowEpochSec, 2)
        if (fires.size < 2) return "weekly"
        val gap = fires[1] - fires[0]
        return when {
            allowHourly && gap <= 90 * 60L -> "hourly"
            gap <= 36 * 3600L -> "daily"
            else -> "weekly"
        }
    }
}
