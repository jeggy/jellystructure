package dev.jellystructure.cron

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.localtime_r
import platform.posix.time_tVar
import platform.posix.tm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 166 — regression tests for the shared cron parser and its backend evaluator. All the fixed
 * "now" instants below are computed against the host's real local timezone (this deployment always
 * runs in Europe/Copenhagen — verified against the live host while writing this test), matching
 * `nextFireEpochSec`'s own local-wall-clock contract; a run in a different timezone would need
 * different fixture values, same as any other localtime-dependent test in this codebase.
 */
@OptIn(ExperimentalForeignApi::class)
class CronTest {

    private fun ok(text: String): CronExpr = when (val p = parseCron(text)) {
        is CronParse.Ok -> p.expr
        is CronParse.Invalid -> fail("expected '$text' to parse, got: ${p.message}")
    }

    private fun invalid(text: String): CronParse.Invalid = when (val p = parseCron(text)) {
        is CronParse.Ok -> fail("expected '$text' to be rejected, parsed as $p")
        is CronParse.Invalid -> p
    }

    private fun localHourMinute(epochSec: Long): Pair<Int, Int> = memScoped {
        val v = alloc<time_tVar>().apply { value = epochSec.convert() }
        val tm = alloc<tm>()
        localtime_r(v.ptr, tm.ptr)
        tm.tm_hour to tm.tm_min
    }

    // ── parseCron: field forms ──────────────────────────────────────────────

    @Test
    fun wildcardFieldsResolveToFullRanges() {
        val e = ok("0 3 * * *")
        assertEquals(setOf(0), e.minutes)
        assertEquals(setOf(3), e.hours)
        assertEquals((1..31).toSet(), e.daysOfMonth)
        assertEquals((1..12).toSet(), e.months)
        assertEquals((0..6).toSet(), e.daysOfWeek)
        assertTrue(e.domIsWildcard && e.dowIsWildcard)
    }

    @Test
    fun literalRangeStepAndListAllCombine() {
        val e = ok("0,30 9-17/2 1,15 * 1-5")
        assertEquals(setOf(0, 30), e.minutes)
        assertEquals(setOf(9, 11, 13, 15, 17), e.hours)
        assertEquals(setOf(1, 15), e.daysOfMonth)
        assertEquals((1..12).toSet(), e.months)
        assertEquals(setOf(1, 2, 3, 4, 5), e.daysOfWeek)
    }

    @Test
    fun dayOfWeek7NormalizesToSunday0() {
        val e = ok("0 3 * * 7")
        assertEquals(setOf(0), e.daysOfWeek)
    }

    @Test
    fun macrosExpandToTheEquivalent5FieldForm() {
        assertEquals(ok("0 0 * * *"), ok("@daily"))
        assertEquals(ok("0 0 * * 0"), ok("@weekly"))
        assertEquals(ok("0 * * * *"), ok("@hourly"))
    }

    // ── parseCron: rejections ───────────────────────────────────────────────

    @Test
    fun rejectsWrongFieldCount() {
        assertEquals(-1, invalid("0 3 * *").fieldIndex)
        val sixField = invalid("0 3 * * * *")
        assertEquals(-1, sixField.fieldIndex)
        assertTrue(sixField.message.contains("seconds", ignoreCase = true))
    }

    @Test
    fun rejectsNamedDaysAndMonths() {
        // fields are checked in order 0..4, so the month field (index 3) is flagged before day-of-week (4).
        val err = invalid("0 3 * JAN MON")
        assertEquals(3, err.fieldIndex)
    }

    @Test
    fun rejectsQuartzExtensions() {
        assertTrue(invalid("0 3 L * *").message.isNotBlank())
        assertTrue(invalid("0 3 ? * *").message.isNotBlank())
        assertTrue(invalid("0 3 * * 1#2").message.isNotBlank())
    }

    @Test
    fun rejectsReboot() {
        invalid("@reboot")
    }

    @Test
    fun rejectsOutOfRangeAndBackwardsRange() {
        assertTrue(invalid("0 24 * * *").message.contains("outside"))
        assertTrue(invalid("0 3 * * 8").message.contains("outside"))
        assertTrue(invalid("0 17-9 * * *").message.contains("backwards"))
    }

    // ── nextFireEpochSec: regression guard for the three legacy shapes (must match pre-166 behavior) ──

    private val fridayMorning = 1_786_694_400L // 2026-08-14 10:00 local (Europe/Copenhagen, CEST)

    @Test
    fun legacyDailyShapeUnchanged() {
        val e = ok("0 3 * * *")
        assertEquals(1_786_755_600L, nextFireEpochSec(e, fridayMorning)) // 2026-08-15 03:00 local
    }

    @Test
    fun legacyWeeklyShapeUnchanged() {
        val e = ok("0 3 * * 0")
        assertEquals(1_786_842_000L, nextFireEpochSec(e, fridayMorning)) // next Sunday, 2026-08-16 03:00 local
    }

    @Test
    fun legacyEvery6hShapeUnchanged() {
        val e = ok("0 */6 * * *")
        assertEquals(1_786_701_600L, nextFireEpochSec(e, fridayMorning)) // 2026-08-14 12:00 local (next 6h boundary)
    }

    // ── nextFireEpochSec: the three defects this phase fixes ───────────────

    @Test
    fun dayOfMonthAndMonthAreActuallyEvaluated() {
        // Bug fix #1: "0 3 1 * *" used to run DAILY (day-of-month/month were parsed but never checked).
        val e = ok("0 3 1 * *")
        assertEquals(1_788_224_400L, nextFireEpochSec(e, fridayMorning)) // 2026-09-01 03:00 local, not tomorrow
    }

    @Test
    fun everyNHoursHonorsTheMinuteField() {
        // Bug fix #2: "30 */6 * * *" used to force minute to 0 and run on the hour.
        val e = ok("30 */6 * * *")
        val next = nextFireEpochSec(e, fridayMorning)!!
        val (hour, minute) = localHourMinute(next)
        assertEquals(30, minute)
        assertEquals(0, hour % 6)
    }

    @Test
    fun neverFiresReturnsNull() {
        val e = ok("0 0 30 2 *") // February never has a 30th
        assertNull(nextFireEpochSec(e, fridayMorning))
    }

    @Test
    fun domDowOrRuleFiresOnWhicheverComesFirst() {
        // "the 1st of the month, OR every Monday" — from Friday 2026-08-14, the next Monday (2026-08-17)
        // is nearer than the 1st of September, so the OR rule must pick it, not require both.
        val e = ok("0 3 1 * 1")
        assertEquals(1_786_928_400L, nextFireEpochSec(e, fridayMorning)) // 2026-08-17 03:00 local
    }

    @Test
    fun dstSpringForwardResolvesToTheShiftedTime() {
        // Europe/Copenhagen springs forward on 2027-03-28 (02:00 -> 03:00 local); 02:30 that day doesn't
        // exist. A daily 02:30 schedule must resolve to the DST-shifted 03:30, not silently skip the day
        // or crash — matching the existing tm_isdst=-1 convention this evaluator inherited.
        val e = ok("30 2 * * *")
        val dayBefore = 1_806_138_000L // 2027-03-27 10:00 local (CET)
        assertEquals(1_806_197_400L, nextFireEpochSec(e, dayBefore)) // 2027-03-28 03:30 local (CEST)
    }

    // ── validateSchedule: the save-path / live-preview backstop ─────────────

    @Test
    fun validateSchedulePassesAReasonableDailySchedule() {
        val check = validateSchedule("0 3 * * *", fridayMorning)
        assertTrue(check is ScheduleCheck.Ok)
        assertEquals(5, (check as ScheduleCheck.Ok).nextRuns.size)
    }

    @Test
    fun validateScheduleRejectsSyntaxErrors() {
        assertTrue(validateSchedule("bogus", fridayMorning) is ScheduleCheck.Invalid)
    }

    @Test
    fun validateScheduleRejectsNeverFires() {
        val check = validateSchedule("0 0 30 2 *", fridayMorning)
        assertTrue(check is ScheduleCheck.Invalid)
        assertTrue((check as ScheduleCheck.Invalid).message.contains("never fires"))
    }

    @Test
    fun validateScheduleRejectsMoreFrequentThan15Minutes() {
        val check = validateSchedule("* * * * *", fridayMorning)
        assertTrue(check is ScheduleCheck.Invalid)
        assertTrue((check as ScheduleCheck.Invalid).message.contains("15 minutes"))
    }

    @Test
    fun validateScheduleAllowsExactly15MinuteCadence() {
        val check = validateSchedule("0,15,30,45 * * * *", fridayMorning)
        assertTrue(check is ScheduleCheck.Ok)
    }
}
