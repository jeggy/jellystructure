package dev.jellystructure.ravilo.ui.components

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R270 FR-R270-4 — *"Offline · last seen {when}"*: a weekday inside the last seven days, a date
 * beyond that, **never a duration and never "just now"**.
 *
 * The shipped helper broke all three rules — "just now", "17 min ago", "3 h ago", "2 d ago" — so an
 * offline row could read *"Offline · last seen just now"*, a contradiction on one line. The dev
 * review asked for the helper to be *verified* rather than assumed; it was, and it was wrong. These
 * are the rules as assertions so it cannot drift back.
 */
class LastSeenLabelTest {

    private val tz = TimeZone.currentSystemDefault()
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        LocalDateTime(y, m, d, h, min).toInstant(tz).toEpochMilliseconds()

    // A Wednesday.
    private val now = at(2026, 9, 16, 20, 30)

    @Test
    fun secondsAgoIsStillTodaysWeekdayNeverJustNow() {
        val label = lastSeenKey(at(2026, 9, 16, 20, 29), now)
        assertEquals("wd.wed", label)
        assertFalse(label.contains("just", ignoreCase = true))
    }

    @Test
    fun yesterdayIsYesterdaysWeekdayNotAnElapsedTime() {
        // Late yesterday to early-morning-today is ~8 hours, which the old helper called "8 h ago".
        // It is a calendar day, so it is Tuesday.
        assertEquals("wd.tue", lastSeenKey(at(2026, 9, 15, 23, 50), now))
    }

    @Test
    fun sixDaysAgoIsAWeekday() {
        assertEquals("wd.thu", lastSeenKey(at(2026, 9, 10), now))
    }

    @Test
    fun eightDaysAgoIsADate() {
        val label = lastSeenKey(at(2026, 9, 8), now)
        assertFalse(label.startsWith("wd."), "beyond seven days must be a date, got $label")
        assertEquals("8/9/2026", label)
    }

    @Test
    fun exactlySevenDaysAgoIsADateNotTheSameWeekdayAgain() {
        // The boundary matters: at seven days the weekday repeats, so "Wednesday" would be ambiguous
        // between today and a week ago — the one case where a weekday says the wrong thing.
        val label = lastSeenKey(at(2026, 9, 9), now)
        assertFalse(label.startsWith("wd."), "seven days must not reuse today's weekday, got $label")
    }

    @Test
    fun noLabelIsEverADuration() {
        // The whole rejected vocabulary, across a fortnight.
        for (d in 0..14) {
            val label = lastSeenKey(at(2026, 9, 16) - d * 86_400_000L, now)
            for (banned in listOf("ago", "min", " h", " d", "just")) {
                assertFalse(label.contains(banned), "day -$d produced a duration: $label")
            }
        }
    }

    @Test
    fun aFutureTimestampDoesNotFallOutOfTheWeekdayWindow() {
        // Clock skew between a TV and the server is real; a "last seen" a minute in the future must
        // not tip into the date branch and read as a random day.
        assertTrue(lastSeenKey(now + 60_000, now).startsWith("wd."))
    }
}
