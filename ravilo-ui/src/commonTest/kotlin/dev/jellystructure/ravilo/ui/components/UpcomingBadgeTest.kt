package dev.jellystructure.ravilo.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone

/** DEB-1482 (FR-3) — the poster badge names the day it airs, in the viewer's own zone, and falls back to "Soon". */
class UpcomingBadgeTest {
    private val faroe = TimeZone.of("Atlantic/Faroe")
    private val newYork = TimeZone.of("America/New_York")
    // Wednesday 2026-10-14, midday UTC (Faroe is UTC+0/+1 and New York UTC-4 then — all three still on the 14th).
    private val now = Instant.parse("2026-10-14T12:00:00Z")

    @Test
    fun `an episode airing later today names today's weekday`() {
        assertEquals(UpcomingWhen.Weekday(DayOfWeek.WEDNESDAY), upcomingWhen("2026-10-14T19:00:00Z", now, faroe))
    }

    @Test
    fun `tomorrow and six days ahead name a weekday`() {
        assertEquals(UpcomingWhen.Weekday(DayOfWeek.THURSDAY), upcomingWhen("2026-10-15T19:00:00Z", now, faroe))
        assertEquals(UpcomingWhen.Weekday(DayOfWeek.TUESDAY), upcomingWhen("2026-10-20T19:00:00Z", now, faroe))
    }

    @Test
    fun `seven days ahead names a date, not a weekday`() {
        assertEquals(UpcomingWhen.DayMonth(21, Month.OCTOBER), upcomingWhen("2026-10-21T19:00:00Z", now, faroe))
        assertEquals(UpcomingWhen.DayMonth(12, Month.NOVEMBER), upcomingWhen("2026-11-12T19:00:00Z", now, faroe))
    }

    @Test
    fun `yesterday keeps Soon`() {
        assertNull(upcomingWhen("2026-10-13T19:00:00Z", now, faroe))
    }

    @Test
    fun `missing, blank and garbage keep Soon`() {
        assertNull(upcomingWhen(null, now, faroe))
        assertNull(upcomingWhen("", now, faroe))
        assertNull(upcomingWhen("   ", now, faroe))
        assertNull(upcomingWhen("soon-ish", now, faroe))
        assertNull(upcomingWhen("2026-10-15", now, faroe))
    }

    @Test
    fun `01_00 UTC on a Friday is Thursday in New York and Friday in Faroe`() {
        val air = "2026-10-16T01:00:00Z"   // a Friday in UTC
        assertEquals(UpcomingWhen.Weekday(DayOfWeek.THURSDAY), upcomingWhen(air, now, newYork))
        assertEquals(UpcomingWhen.Weekday(DayOfWeek.FRIDAY), upcomingWhen(air, now, faroe))
    }

    @Test
    fun `today is the viewer's today, not UTC's`() {
        // 02:00 UTC on the 15th is still the evening of the 14th in New York.
        val lateNow = Instant.parse("2026-10-15T02:00:00Z")
        assertEquals(UpcomingWhen.Weekday(DayOfWeek.WEDNESDAY), upcomingWhen("2026-10-15T01:00:00Z", lateNow, newYork))
    }

    @Test
    fun `the weekday keys are the table's own`() {
        assertEquals("wd.abbr.thu", weekdayAbbrKey(DayOfWeek.THURSDAY))
        assertEquals("wd.abbr.sun", weekdayAbbrKey(DayOfWeek.SUNDAY))
    }
}
