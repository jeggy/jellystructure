package dev.jellystructure.cron

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * DEB-4387 (FR-1, FR-2, FR-4, FR-6.1) — the schedule type and its evaluator: the spec's *Acceptance* cases for piece 1.
 * Every instant is built in a named zone, so none of it leans on the host's `TZ`.
 */
class ScheduleTest {
    private val utc = TimeZone.UTC
    private val faroe = TimeZone.of("Atlantic/Faroe")

    private fun schedule(text: String): Schedule = when (val p = parseSchedule(text)) {
        is ScheduleParse.Ok -> p.schedule
        is ScheduleParse.Invalid -> fail("expected '$text' to parse, got: ${p.message}")
        ScheduleParse.Off -> fail("'$text' parsed as off")
    }

    private fun invalid(text: String): String = when (val p = parseSchedule(text)) {
        is ScheduleParse.Invalid -> p.message
        else -> fail("expected '$text' to be refused, got $p")
    }

    private fun at(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(zone).epochSeconds

    private fun local(zone: TimeZone, epochSec: Long): String =
        Instant.fromEpochSeconds(epochSec).toLocalDateTime(zone).toString()

    private fun fires(text: String, zone: TimeZone, after: Long, n: Int): List<String> =
        ScheduleEvaluator.nextFires(schedule(text), zone, after, n).map { local(zone, it) }

    // ── FR-1: one type, one stored form ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun existingFindCronsAreKeptByteForByte() {
        for (c in listOf("0 */1 * * *", "30 3 * * 0", "0 */6 * * *", "15 2 1 * *", "0,15,30,45 * * * *", "@daily")) {
            assertEquals(c, schedule(c).canonical, "canonical of '$c'")
        }
    }

    @Test
    fun emptyIsOff() {
        assertEquals(ScheduleParse.Off, parseSchedule(""))
        assertEquals(ScheduleParse.Off, parseSchedule("   "))
    }

    @Test
    fun mondayAndTuesdayIsWrittenAsCron() {
        val s = schedule("every:FREQ=WEEKLY;BYDAY=MO,TU;AT=04:00")
        assertEquals("0 4 * * 1,2", s.canonical)
        assertEquals("Weekly on Monday and Tuesday at 04:00", s.sentence)
        // What the picker writes round-trips: the cron says the same thing.
        assertEquals("Weekly on Monday and Tuesday at 04:00", schedule("0 4 * * 1,2").sentence)
    }

    @Test
    fun cronIsWrittenWheneverItCanBeSaid() {
        assertEquals("0 * * * *", schedule("every:FREQ=HOURLY").canonical)
        assertEquals("15 */6 * * *", schedule("every:FREQ=HOURLY;INTERVAL=6;AT=15").canonical)
        assertEquals("30 3 * * *", schedule("every:FREQ=DAILY;AT=03:30").canonical)
        assertEquals("0 4 * * 0", schedule("every:FREQ=WEEKLY;BYDAY=SU;AT=04:00").canonical)
        assertEquals("0 4 15 * *", schedule("every:FREQ=MONTHLY;BYMONTHDAY=15;AT=04:00").canonical)
        assertEquals("0 4 1 3 *", schedule("every:FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=1;AT=04:00").canonical)
    }

    @Test
    fun theRecurrenceFormIsKeptForWhatCronCannotSay() {
        assertEquals("every:FREQ=DAILY;INTERVAL=3;AT=04:00;FROM=2026-11-18", schedule("every:FREQ=DAILY;INTERVAL=3;AT=04:00;FROM=2026-11-18").canonical)
        assertEquals("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18", schedule("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18").canonical)
        assertEquals("every:FREQ=MONTHLY;BYDAY=-1FR;AT=04:00", schedule("every:FREQ=MONTHLY;BYDAY=-1FR;AT=04:00").canonical)
        assertEquals("every:FREQ=MONTHLY;BYMONTHDAY=-1;AT=04:00", schedule("every:FREQ=MONTHLY;BYMONTHDAY=-1;AT=04:00").canonical)
        assertEquals("every:FREQ=HOURLY;INTERVAL=5", schedule("every:FREQ=HOURLY;INTERVAL=5").canonical)
        // The example from the spec, keys in the canonical order whatever order they were written in.
        assertEquals(
            "every:FREQ=YEARLY;INTERVAL=10;BYMONTH=3;BYMONTHDAY=1;AT=04:00;FROM=2026-03-01",
            schedule("every:AT=04:00;FROM=2026-03-01;FREQ=YEARLY;BYMONTHDAY=1;BYMONTH=3;INTERVAL=10").canonical,
        )
    }

    @Test
    fun refusesWhatItCannotRun() {
        assertTrue(invalid("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00").contains("FROM"))
        assertTrue(invalid("every:FREQ=DAILY;INTERVAL=101;FROM=2026-11-18").contains("INTERVAL"))
        assertTrue(invalid("every:FREQ=WEEKLY;AT=04:00").contains("BYDAY"))
        assertTrue(invalid("every:FREQ=WEEKLY;BYDAY=1MO;AT=04:00").contains("MONTHLY"))
        assertTrue(invalid("every:FREQ=MONTHLY;AT=04:00").contains("BYMONTHDAY"))
        assertTrue(invalid("every:FREQ=MONTHLY;BYMONTHDAY=3;BYDAY=MO;AT=04:00").contains("BYMONTHDAY"))
        assertTrue(invalid("every:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30").contains("February"))
        assertTrue(invalid("every:FREQ=DAILY;AT=25:00").contains("AT"))
        assertTrue(invalid("every:FREQ=DAILY;FROM=2026-02-30").contains("FROM"))
        assertTrue(invalid("every:FREQ=FORTNIGHTLY").contains("FREQ"))
        assertTrue(invalid("every:FREQ=DAILY;COUNT=3").contains("COUNT"))
        assertTrue(invalid("every:FREQ=DAILY;AT=04:00;AT=05:00").contains("twice"))
    }

    @Test
    fun sentencesAreInPlainWords() {
        assertEquals("Every 2 weeks on Wednesday at 04:00, starting 18 November 2026", schedule("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18").sentence)
        assertEquals("Every 10 years on 1 March at 04:00, starting 1 March 2026", schedule("every:FREQ=YEARLY;INTERVAL=10;BYMONTH=3;BYMONTHDAY=1;AT=04:00;FROM=2026-03-01").sentence)
        assertEquals("Monthly on the last Friday at 04:00", schedule("every:FREQ=MONTHLY;BYDAY=-1FR;AT=04:00").sentence)
        assertEquals("Monthly on the first Monday and last Friday at 04:00", schedule("every:FREQ=MONTHLY;BYDAY=1MO,-1FR;AT=04:00").sentence)
        assertEquals("Monthly on the last day at 04:00", schedule("every:FREQ=MONTHLY;BYMONTHDAY=-1;AT=04:00").sentence)
        assertEquals("Every 3 days at 04:00, starting 18 November 2026", schedule("every:FREQ=DAILY;INTERVAL=3;AT=04:00;FROM=2026-11-18").sentence)
        assertEquals("Every hour", schedule("0 * * * *").sentence)
        assertEquals("Monthly on day 15 at 04:00", schedule("0 4 15 * *").sentence)
        assertEquals("Yearly on 1 March at 04:00", schedule("0 4 1 3 *").sentence)
        assertEquals("Custom · 15 2 1,15 * *", schedule("15 2 1,15 * *").sentence)
    }

    // ── FR-2: one evaluator ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun mondayAndTuesdayFireOnMondaysAndTuesdaysInTheScheduleZone() {
        // Thursday 2026-11-12 10:00; the next five are Mon 16, Tue 17, Mon 23, Tue 24, Mon 30 — at 04:00 in the zone, not in UTC.
        val faroeRuns = fires("every:FREQ=WEEKLY;BYDAY=MO,TU;AT=04:00", faroe, at(faroe, 2026, 11, 12, 10), 5)
        assertEquals(listOf("2026-11-16T04:00", "2026-11-17T04:00", "2026-11-23T04:00", "2026-11-24T04:00", "2026-11-30T04:00"), faroeRuns)
        val cronRuns = fires("0 4 * * 1,2", faroe, at(faroe, 2026, 11, 12, 10), 5)
        assertEquals(faroeRuns, cronRuns)
    }

    @Test
    fun everyTwoWeeksFromAnAnchorSkipsTheWeekBetween() {
        val runs = fires("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18", utc, at(utc, 2026, 11, 12), 3)
        assertEquals(listOf("2026-11-18T04:00", "2026-12-02T04:00", "2026-12-16T04:00"), runs)
        assertFalse("2026-11-25T04:00" in runs)
        // Asked from the far side of the anchor, the intervals still count from it.
        assertEquals(listOf("2027-01-13T04:00", "2027-01-27T04:00"), fires("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18", utc, at(utc, 2027, 1, 1), 2))
    }

    @Test
    fun everyTenYearsFromAnAnchor() {
        val text = "every:FREQ=YEARLY;INTERVAL=10;BYMONTH=3;BYMONTHDAY=1;AT=04:00;FROM=2026-03-01"
        assertEquals(listOf("2026-03-01T04:00", "2036-03-01T04:00", "2046-03-01T04:00"), fires(text, utc, at(utc, 2026, 1, 1), 3))
        val check = validateSchedule(text, at(utc, 2026, 1, 1), utc)
        assertTrue(check is ScheduleCheck.Ok, "valid: $check")
        assertEquals(listOf("2026-03-01T04:00", "2036-03-01T04:00", "2046-03-01T04:00", "2056-03-01T04:00", "2066-03-01T04:00"),
            (check as ScheduleCheck.Ok).nextRuns.map { local(utc, it) })
    }

    @Test
    fun theLastFridayOfTheMonth() {
        assertEquals(listOf("2026-11-27T04:00", "2026-12-25T04:00", "2027-01-29T04:00"), fires("every:FREQ=MONTHLY;BYDAY=-1FR;AT=04:00", utc, at(utc, 2026, 11, 1), 3))
        assertEquals(listOf("2026-11-02T04:00", "2026-12-07T04:00"), fires("every:FREQ=MONTHLY;BYDAY=1MO;AT=04:00", utc, at(utc, 2026, 11, 1), 2))
    }

    @Test
    fun dayThirtyOneSkipsMonthsWithoutItAndLastDayDoesNot() {
        val after = at(utc, 2026, 10, 1)
        assertEquals(listOf("2026-10-31T04:00", "2026-12-31T04:00", "2027-01-31T04:00"), fires("every:FREQ=MONTHLY;BYMONTHDAY=31;AT=04:00", utc, after, 3))
        assertEquals(listOf("2026-10-31T04:00", "2026-12-31T04:00"), fires("0 4 31 * *", utc, after, 2))
        assertEquals(listOf("2026-10-31T04:00", "2026-11-30T04:00", "2026-12-31T04:00", "2027-01-31T04:00"),
            fires("every:FREQ=MONTHLY;BYMONTHDAY=-1;AT=04:00", utc, after, 4))
    }

    @Test
    fun everyThreeMonthsAndFebruary29() {
        assertEquals(listOf("2027-01-15T06:30", "2027-04-15T06:30"), fires("every:FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=15;AT=06:30;FROM=2026-10-15", utc, at(utc, 2026, 12, 1), 2))
        // 29 February is skipped in a common year, as RFC 5545 skips it.
        assertEquals(listOf("2028-02-29T00:00", "2032-02-29T00:00"), fires("every:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29", utc, at(utc, 2026, 1, 1), 2))
    }

    @Test
    fun hourlyRecurrenceCountsFromMidnightOfItsAnchor() {
        assertEquals(listOf("2026-11-12T10:15", "2026-11-12T15:15", "2026-11-12T20:15", "2026-11-13T01:15"),
            fires("every:FREQ=HOURLY;INTERVAL=5;AT=15;FROM=2026-11-12", utc, at(utc, 2026, 11, 12, 9), 4))
    }

    @Test
    fun aScheduleThatNeverFiresIsRefused() {
        val now = at(utc, 2026, 11, 12)
        val feb30 = validateSchedule("0 0 30 2 *", now, utc)
        assertTrue(feb30 is ScheduleCheck.Invalid && feb30.message.contains("never fires"))
        val farOff = validateSchedule("every:FREQ=YEARLY;INTERVAL=100;BYMONTH=3;BYMONTHDAY=1;FROM=2200-01-01", now, utc)
        assertTrue(farOff is ScheduleCheck.Invalid && farOff.message.contains("100 years"), "$farOff")
        // 100 years is the horizon: a fire 60 years out is valid.
        assertTrue(validateSchedule("every:FREQ=YEARLY;INTERVAL=100;BYMONTH=3;BYMONTHDAY=1;FROM=2086-01-01", now, utc) is ScheduleCheck.Ok)
    }

    @Test
    fun theFifteenMinuteFloorHoldsAndASettingMayRaiseIt() {
        val now = at(utc, 2026, 11, 12)
        assertTrue(validateSchedule("* * * * *", now, utc) is ScheduleCheck.Invalid)
        assertTrue(validateSchedule("*/15 * * * *", now, utc) is ScheduleCheck.Ok)
        assertTrue(validateSchedule("*/10 * * * *", now, utc, floorMinutes = 5) is ScheduleCheck.Invalid, "a floor below 15 does not lower it")
        // The auto-fetcher's 6 h floor: every hour is refused, every 6 hours is not.
        val hourly = validateSchedule("0 * * * *", now, utc, floorMinutes = 360)
        assertTrue(hourly is ScheduleCheck.Invalid && hourly.message.contains("6 hours"), "$hourly")
        assertTrue(validateSchedule("0 */6 * * *", now, utc, floorMinutes = 360) is ScheduleCheck.Ok)
    }

    @Test
    fun validationAnswersWithTheSentenceAndTheCanonicalString() {
        val ok = validateSchedule("every:FREQ=WEEKLY;BYDAY=MO,TU;AT=04:00", at(faroe, 2026, 11, 12), faroe) as ScheduleCheck.Ok
        assertEquals("0 4 * * 1,2", ok.canonical)
        assertEquals("Weekly on Monday and Tuesday at 04:00", ok.sentence)
        assertEquals(5, ok.nextRuns.size)
    }

    // ── FR-2.3: when a job is due ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aJobIsDueWhenTheFirstFireAfterItsLastRunHasPassed() {
        val daily = schedule("0 4 * * *")
        val lastRun = at(utc, 2026, 11, 10, 4)
        assertTrue(ScheduleEvaluator.isDue(daily, utc, null, at(utc, 2026, 11, 10, 5)), "never run")
        assertFalse(ScheduleEvaluator.isDue(daily, utc, lastRun, at(utc, 2026, 11, 11, 3, 59)))
        assertTrue(ScheduleEvaluator.isDue(daily, utc, lastRun, at(utc, 2026, 11, 11, 4)))
    }

    @Test
    fun firesMissedInARowRunOnce() {
        val daily = schedule("0 4 * * *")
        val lastRun = at(utc, 2026, 11, 8, 4)
        val back = at(utc, 2026, 11, 12, 10)     // down over four fires
        assertTrue(ScheduleEvaluator.isDue(daily, utc, lastRun, back))
        assertEquals(back, ScheduleEvaluator.nextDue(daily, utc, lastRun, back), "due now, once")
        // The run is recorded; the next fire after it is tomorrow, so the four missed fires do not run four times.
        assertFalse(ScheduleEvaluator.isDue(daily, utc, back, back + 60))
        assertEquals(at(utc, 2026, 11, 13, 4), ScheduleEvaluator.nextDue(daily, utc, back, back + 60))
    }

    // ── FR-4: the schedule zone ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun dailyFourAmIsFourAmLocalOnBothSidesOfADstChange() {
        // Atlantic/Faroe moves to summer time at 01:00 UTC on 2027-03-28.
        val runs = fires("0 4 * * *", faroe, at(faroe, 2027, 3, 26, 12), 4)
        assertEquals(listOf("2027-03-27T04:00", "2027-03-28T04:00", "2027-03-29T04:00", "2027-03-30T04:00"), runs)
        val utcHours = ScheduleEvaluator.nextFires(schedule("0 4 * * *"), faroe, at(faroe, 2027, 3, 26, 12), 4).map { (it % 86_400) / 3600 }
        assertEquals(listOf(4L, 3L, 3L, 3L), utcHours, "04:00 is 04:00 UTC in winter and 03:00 UTC in summer")
    }

    @Test
    fun aTimeADstGapSkipsRunsAtTheFirstMinuteAfterIt() {
        // 01:30 does not exist in Faroe on 2027-03-28 (01:00 -> 02:00). It runs at 02:00, once.
        val runs = ScheduleEvaluator.nextFires(schedule("30 1 * * *"), faroe, at(faroe, 2027, 3, 27, 12), 2)
        assertEquals(listOf("2027-03-28T02:00", "2027-03-29T01:30"), runs.map { local(faroe, it) })
        // And a day with two skipped times does not run twice at the gap's end.
        val both = ScheduleEvaluator.nextFires(schedule("15,45 1 * * *"), faroe, at(faroe, 2027, 3, 27, 12), 3).map { local(faroe, it) }
        assertEquals(listOf("2027-03-28T02:00", "2027-03-29T01:15", "2027-03-29T01:45"), both)
    }

    @Test
    fun aTimeADstChangeRepeatsRunsOnce() {
        // Faroe goes back at 01:00 UTC on 2027-10-31, so 01:30 local happens twice. It runs once.
        val runs = ScheduleEvaluator.nextFires(schedule("30 1 * * *"), faroe, at(utc, 2027, 10, 29, 12), 4)
        assertEquals(listOf("2027-10-30T01:30", "2027-10-31T01:30", "2027-11-01T01:30", "2027-11-02T01:30"), runs.map { local(faroe, it) })
        assertEquals(runs.size, runs.toSet().size)
    }

    @Test
    fun theZoneComesFromTheConfigThenTzThenUtc() {
        assertEquals("Atlantic/Faroe", ScheduleZone.resolve("Atlantic/Faroe").id)
        assertEquals("Atlantic/Faroe", ScheduleZone.resolve(" Atlantic/Faroe ").id)
        // A name the tz database does not know never throws: the scheduler must survive a typo in the file.
        ScheduleZone.resolve("Not/AZone")
        assertNull(runCatching { TimeZone.of("Not/AZone") }.getOrNull())
    }

    // DEB-4402 (FR-3.8, FR-4.2) — the picker's runs are worded by the server, in the schedule zone, never the browser's.
    @Test
    fun aRunIsWordedInTheScheduleZone() {
        val sec = at(faroe, 2026, 11, 16, 4, 0)
        assertEquals("Mon 16 Nov 2026 · 04:00", runLabel(sec, faroe))
        assertEquals("Mon 16 Nov 2026 · 04:00", runLabel(at(utc, 2026, 11, 16, 4, 0), utc))
        // the same instant, read in the other zone's wall clock (Faroe is UTC+0 in November, +1 in summer)
        assertEquals("Wed 1 Jul 2026 · 05:00", runLabel(at(utc, 2026, 7, 1, 4, 0), faroe))
    }
}
