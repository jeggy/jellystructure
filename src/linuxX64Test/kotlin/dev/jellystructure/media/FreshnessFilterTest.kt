package dev.jellystructure.media

import dev.jellystructure.config.PipelineStep
import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 175 — the age-tiered freshness/cooldown decision, extracted out of `executePipeline` into
 *  [isDueForRecheck]/[cadenceMs] so it's testable without a live [MediaStore]. */
class FreshnessFilterTest {

    private val DAY = 24 * 3_600_000L

    private fun step(thisYear: String = "daily", oneToFive: String = "weekly", older: String = "monthly") =
        PipelineStep(step = "scan_files", refreshThisYear = thisYear, refresh1To5y = oneToFive, refreshOlder = older)

    @Test
    fun `cadenceMs covers every named tier plus never and an unknown default`() {
        assertEquals(DAY, cadenceMs("daily"))
        assertEquals(7 * DAY, cadenceMs("weekly"))
        assertEquals(30 * DAY, cadenceMs("monthly"))
        assertEquals(180 * DAY, cadenceMs("6months"))
        assertEquals(365 * DAY, cadenceMs("yearly"))
        assertEquals(null, cadenceMs("never"))
        assertEquals(30 * DAY, cadenceMs("bogus"))  // unrecognized → default monthly
    }

    // Phase 261 (FR-261-5, acceptance 1) — the two longer cadences the file checks default to.
    @Test
    fun `cadenceMs knows 2years and 5years`() {
        assertEquals(2 * 365 * DAY, cadenceMs("2years"))
        assertEquals(5 * 365 * DAY, cadenceMs("5years"))
    }

    // Phase 261 (FR-261-7/10, dev review item 6) — the card's "next due" and the scheduler's "is it due" are
    // one rule: due exactly from nextDueMs on, never a millisecond before, on every tier and the airing floor.
    @Test
    fun `isDueForRecheck turns true exactly at nextDueMs`() {
        val now = 1_000_000L * DAY
        for (s in listOf(step(), step(thisYear = "never", oneToFive = "5years", older = "2years"))) {
            for (year in listOf(2026, 2023, 2001)) for (airing in listOf(false, true)) {
                val last = now - 400 * DAY
                val next = nextDueMs(last, year, 2026, s, airing)
                if (next == null) {
                    assertEquals(false, isDueForRecheck(now, last, year, 2026, s, airing), "never → never due ($year, airing=$airing)")
                    continue
                }
                assertEquals(false, isDueForRecheck(next - 1, last, year, 2026, s, airing), "a millisecond early ($year, airing=$airing)")
                assertEquals(true, isDueForRecheck(next, last, year, 2026, s, airing), "exactly at next due ($year, airing=$airing)")
            }
        }
        // `never` on the this-year tier: no date at all unless airing, whose floor is a day.
        val never = step(thisYear = "never")
        assertEquals(null, nextDueMs(0L, 2026, 2026, never))
        assertEquals(DAY, nextDueMs(0L, 2026, 2026, never, isActivelyAiring = true))
    }

    @Test
    fun `this-year release uses the this-year tier`() {
        val s = step(thisYear = "daily")
        val now = 1_000_000L * DAY
        assertEquals(false, isDueForRecheck(now, now - (DAY - 1), 2026, 2026, s))  // checked <1 day ago → not due
        assertEquals(true, isDueForRecheck(now, now - DAY, 2026, 2026, s))         // checked exactly 1 day ago → due
    }

    @Test
    fun `1-5-year-old release uses the 1to5y tier`() {
        val s = step(oneToFive = "weekly")
        val now = 1_000_000L * DAY
        assertEquals(false, isDueForRecheck(now, now - (7 * DAY - 1), 2022, 2026, s))
        assertEquals(true, isDueForRecheck(now, now - 7 * DAY, 2022, 2026, s))
        // Boundary: exactly 5 years old is still the 1-5y tier, not "older".
        assertEquals(true, isDueForRecheck(now, now - 7 * DAY, 2021, 2026, s))
    }

    @Test
    fun `older-than-5-year release uses the older tier`() {
        val s = step(older = "monthly")
        val now = 1_000_000L * DAY
        assertEquals(false, isDueForRecheck(now, now - (30 * DAY - 1), 2010, 2026, s))
        assertEquals(true, isDueForRecheck(now, now - 30 * DAY, 2010, 2026, s))
    }

    @Test
    fun `never cadence is never due`() {
        val s = step(thisYear = "never")
        val now = 1_000_000L * DAY
        assertEquals(false, isDueForRecheck(now, 0L, 2026, 2026, s))
        assertEquals(false, isDueForRecheck(now, now - 10_000 * DAY, 2026, 2026, s))
    }

    // Phase 181/FR-181-2 — the Fjollerne bug: a 2005 premiere with a Sonarr-reported next episode was
    // landing in the monthly `refreshOlder` tier purely from its premiere year.

    @Test
    fun `actively airing overrides an old premiere year to the this-year tier`() {
        val s = step(thisYear = "daily", older = "monthly")
        val now = 1_000_000L * DAY
        // 21 years old, but actively airing: due after 1 day, same as a brand-new release —
        // NOT the 30-day threshold its premiere year alone would imply.
        assertEquals(false, isDueForRecheck(now, now - (DAY - 1), 2005, 2026, s, isActivelyAiring = true))
        assertEquals(true, isDueForRecheck(now, now - DAY, 2005, 2026, s, isActivelyAiring = true))
    }

    @Test
    fun `not actively airing falls back to the age-tiered behavior unchanged`() {
        val s = step(older = "monthly")
        val now = 1_000_000L * DAY
        // isActivelyAiring defaults to false — identical to the pre-181 behavior.
        assertEquals(false, isDueForRecheck(now, now - (30 * DAY - 1), 2005, 2026, s))
        assertEquals(true, isDueForRecheck(now, now - 30 * DAY, 2005, 2026, s))
        assertEquals(false, isDueForRecheck(now, now - (30 * DAY - 1), 2005, 2026, s, isActivelyAiring = false))
    }

    // ─── Phase 196 (FR-196-6) — an airing show can never be starved ──────────────────────────────

    @Test
    fun `an airing show is due after a day even when its tier is much slower`() {
        // The live case: Fjollerne premiered in 2005, so without Phase 181's isActivelyAiring it lands in
        // the `older` tier. With it, the this-year tier applies — but an operator who sets that tier to
        // something slow would silently re-create the starvation 181 set out to remove.
        val s = step(thisYear = "monthly", older = "monthly")
        val now = 1_000_000L * DAY
        assertEquals(false, isDueForRecheck(now, now - 25 * DAY, 2005, 2026, s, isActivelyAiring = false))
        assertEquals(true, isDueForRecheck(now, now - 25 * DAY, 2005, 2026, s, isActivelyAiring = true))
    }

    @Test
    fun `the airing floor does not make a non-airing title due sooner`() {
        val s = step(older = "monthly")
        val now = 1_000_000L * DAY
        assertEquals(false, isDueForRecheck(now, now - 2 * DAY, 2005, 2026, s, isActivelyAiring = false))
    }

    @Test
    fun `never still means never for a non-airing title but not for an airing one`() {
        val s = step(thisYear = "never", older = "never")
        val now = 1_000_000L * DAY
        // A title nobody is waiting on stays excluded forever, exactly as before.
        assertEquals(false, isDueForRecheck(now, now - 400 * DAY, 2005, 2026, s, isActivelyAiring = false))
        // One with an episode landing this week is not something "never" should be able to hide.
        assertEquals(true, isDueForRecheck(now, now - 400 * DAY, 2005, 2026, s, isActivelyAiring = true))
        assertEquals(false, isDueForRecheck(now, now - (DAY - 1), 2005, 2026, s, isActivelyAiring = true))
    }

    @Test
    fun `a faster configured cadence still wins over the airing floor`() {
        // The floor is a ceiling on staleness, not an override — `daily` config plus an airing show
        // must not become "once a day at best" if someone configures something faster later.
        val s = step(thisYear = "daily")
        val now = 1_000_000L * DAY
        assertEquals(true, isDueForRecheck(now, now - DAY, 2026, 2026, s, isActivelyAiring = true))
        assertEquals(false, isDueForRecheck(now, now - (DAY - 1), 2026, 2026, s, isActivelyAiring = true))
    }
}
