package dev.jellystructure.cron

import dev.jellystructure.media.cadenceMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-4387 (FR-5.2) — the release-age tiers: today's word whenever one fits, `<N><unit>` otherwise, and [cadenceMs] reads both. */
class ReleaseAgeTest {
    private val day = 24 * 3_600_000L

    @Test fun `today's word is written whenever the choice is one`() {
        assertEquals("daily", ReleaseAge(1, AgeUnit.DAYS).word)
        assertEquals("weekly", ReleaseAge(1, AgeUnit.WEEKS).word)
        assertEquals("monthly", ReleaseAge(1, AgeUnit.MONTHS).word)
        assertEquals("6months", ReleaseAge(6, AgeUnit.MONTHS).word)
        assertEquals("yearly", ReleaseAge(1, AgeUnit.YEARS).word)
        assertEquals("2years", ReleaseAge(2, AgeUnit.YEARS).word)
        assertEquals("5years", ReleaseAge(5, AgeUnit.YEARS).word)
    }

    @Test fun `an age no word says is written as N and its unit`() {
        assertEquals("10years", ReleaseAge(10, AgeUnit.YEARS).word)
        assertEquals("3days", ReleaseAge(3, AgeUnit.DAYS).word)
        assertEquals("2weeks", ReleaseAge(2, AgeUnit.WEEKS).word)
        assertEquals("3months", ReleaseAge(3, AgeUnit.MONTHS).word)
        // 12 months is 360 days and a year is 365: not the same choice, so not the same word
        assertEquals("12months", ReleaseAge(12, AgeUnit.MONTHS).word)
    }

    @Test fun `every word parses back to the age it says and what is written reads back as itself`() {
        for ((word, age) in ReleaseAge.WORDS) {
            assertEquals(age, ReleaseAge.parse(word), word)
            assertEquals(word, ReleaseAge.parse(word)!!.word)
        }
        for (n in listOf(1, 2, 7, 10, 52, 100)) for (u in AgeUnit.entries) {
            val age = ReleaseAge(n, u)
            assertEquals(age, ReleaseAge.parse(age.word), age.word)
        }
    }

    @Test fun `never and the unreadable are not an age and N is 1 to 100`() {
        assertNull(ReleaseAge.parse("never"))
        for (bad in listOf("", "years", "0days", "101years", "-1days", "1.5weeks", "2 years", "10yrs", "tenyears")) assertNull(ReleaseAge.parse(bad), bad)
    }

    @Test fun `cadenceMs reads both forms and stays the one duration`() {
        // Verify's tiers (acceptance): 5years is a word, 10years is not
        assertEquals(5 * 365 * day, cadenceMs("5years"))
        assertEquals(10 * 365 * day, cadenceMs("10years"))
        assertEquals(3 * day, cadenceMs("3days"))
        assertEquals(2 * 7 * day, cadenceMs("2weeks"))
        assertEquals(4 * 30 * day, cadenceMs(" 4Months "))
        assertEquals(100 * 365 * day, cadenceMs("100years"))
        // a word and the same age spelled as N + unit are one duration
        assertEquals(cadenceMs("6months"), cadenceMs("6months".let { ReleaseAge.parse(it)!!.let { a -> "${a.n}${a.unit.key}" } }))
    }

    @Test fun `never is null and an unreadable tier is monthly as it was`() {
        assertNull(cadenceMs("never"))
        assertEquals(30 * day, cadenceMs("101years"))
        assertEquals(30 * day, cadenceMs("0days"))
        assertEquals(30 * day, cadenceMs("whenever"))
    }
}
