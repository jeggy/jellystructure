package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-5640 (FR-52) — the pure text behind a Home tile's badges and a row's count, now shared by both apps. */
class TileBadgesTest {
    private val strings = mapOf(
        "wd.abbr.thu" to "Thu", "wd.abbr.mon" to "Mon", "month.oct" to "Oct", "month.jan" to "Jan", "month.dec" to "Dec", "month.nov" to "Nov",
    )
    private fun en(key: String, vars: Map<String, String>): String = when (key) {
        "date.weekday_day_month" -> "${vars["weekday"]} ${vars["day"]} ${vars["month"]}"
        "date.month_day_year" -> "${vars["month"]} ${vars["day"]}, ${vars["year"]}"
        "tile.new_on" -> "New · ${vars["date"]}"
        "time.hour_minute" -> "${vars["h"]}:${vars["m"]}"
        else -> strings[key] ?: key
    }

    @Test fun `an air date this year is weekday day month`() {
        assertEquals("Thu 29 Oct", airDateText("2026-10-29", 2026, ::en))
        assertEquals("Mon 5 Jan", airDateText("2026-01-05", 2026, ::en))
    }

    @Test fun `DEB-5432 the when is the date then the time`() {
        assertEquals("Mon 23 Nov · 21:00", airWhenText("2026-11-23", "21:00", 2026, ::en))
        assertEquals("Mon 23 Nov", airWhenText("2026-11-23", null, 2026, ::en))
        assertEquals("Mon 23 Nov", airWhenText("2026-11-23", "25:00", 2026, ::en), "a malformed time is dropped")
        assertEquals("Mon 23 Nov", airWhenText("2026-11-23", "9:5", 2026, ::en))
        assertNull(airWhenText(null, "21:00", 2026, ::en), "a time with no date shows nothing")
        assertEquals("New · Mon 23 Nov · 21:00", continueWhenBadge("2026-11-23", "21:00", 0f, 2026, ::en))
        assertEquals("Mon 23 Nov · 21:00", continueWhenBadge("2026-11-23", "21:00", 0.4f, 2026, ::en))
    }

    @Test fun `an air date in another year is month day year`() {
        assertEquals("Dec 31, 2025", airDateText("2025-12-31", 2026, ::en))
    }

    @Test fun `a blank malformed or impossible date is null`() {
        assertNull(airDateText(null, 2026, ::en))
        assertNull(airDateText("", 2026, ::en))
        assertNull(airDateText("29/10/2026", 2026, ::en))
        assertNull(airDateText("2026-02-31", 2026, ::en))
    }

    @Test fun `a continue date is New on a card with no progress and bare beside a bar`() {
        assertEquals("New · Thu 29 Oct", continueDateBadge("2026-10-29", 0f, 2026, ::en))
        assertEquals("Thu 29 Oct", continueDateBadge("2026-10-29", 0.4f, 2026, ::en))
        assertEquals("New · Dec 31, 2025", continueDateBadge("2025-12-31", 0f, 2026, ::en))
    }

    @Test fun `a continue card without a usable date keeps its code`() {
        assertNull(continueDateBadge(null, 0f, 2026, ::en))
        assertNull(continueDateBadge("soon", 0.4f, 2026, ::en))
    }

    @Test fun `the seed's total wins over the items held`() = assertEquals(37, rowHeadingCount(37, 20))
    @Test fun `without a seed total the items are counted`() = assertEquals(20, rowHeadingCount(null, 20))
    @Test fun `two is the smallest count and one or none has none`() {
        assertEquals(2, rowHeadingCount(null, 2))
        assertNull(rowHeadingCount(null, 1))
        assertNull(rowHeadingCount(null, 0))
        assertNull(rowHeadingCount(1, 5))
    }
}
