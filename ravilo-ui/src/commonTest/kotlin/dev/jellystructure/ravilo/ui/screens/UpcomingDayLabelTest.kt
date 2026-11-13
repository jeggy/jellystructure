package dev.jellystructure.ravilo.ui.screens

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * DEB-4289. Coming Soon decides *Today* in the viewer's zone, not UTC. At 23:54Z on Tue 10 Nov the household
 * (UTC+1) is 00:54 on Wed 11 Nov, and the row dated Tue 10 is *Yesterday* — it used to read *Today* for the whole
 * local hour after midnight (two hours in summer).
 */
class UpcomingDayLabelTest {
    private val copenhagen = TimeZone.of("Europe/Copenhagen")
    private val tue10 = LocalDate(2026, 11, 10)
    private val wed11 = LocalDate(2026, 11, 11)

    @Test
    fun theFirstLocalHourAfterMidnightIsTheNewDay() {
        val now = Instant.parse("2026-11-10T23:54:00Z")
        val today = todayOn(copenhagen, now)
        assertEquals(wed11, today)
        assertEquals("up.day_yesterday", relativeDayKey(tue10, today))
        assertEquals("up.day_today", relativeDayKey(wed11, today))
    }

    @Test
    fun theSameInstantInUtcIsStillTheOldDay() {
        assertEquals(tue10, todayOn(TimeZone.UTC, Instant.parse("2026-11-10T23:54:00Z")))
    }

    @Test
    fun aViewerBehindUtcKeepsTheirOwnDayAfterUtcMidnight() {
        val newYork = TimeZone.of("America/New_York")
        val today = todayOn(newYork, Instant.parse("2026-11-11T01:32:00Z"))
        assertEquals(tue10, today)
        assertEquals("up.day_today", relativeDayKey(tue10, today))
    }

    @Test
    fun onlyTheThreeNeighbouringDaysAreNamed() {
        assertEquals("up.day_tomorrow", relativeDayKey(LocalDate(2026, 11, 12), wed11))
        assertNull(relativeDayKey(LocalDate(2026, 11, 13), wed11))
        assertNull(relativeDayKey(LocalDate(2026, 11, 9), wed11))
    }
}
