package dev.jellystructure.config

import dev.jellystructure.cron.ScheduleCarry
import dev.jellystructure.media.ProcessingSchedule
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-4387 (FR-5, FR-6.2–6.3) — Music, Audiobooks, Recommendations and Suggestions on the shared schedule: the one-time carry-over
 * from the old interval words, the words written back for a rolled-back image, and the FR-2.3 due rule.
 */
class ProcessingSchedulesTest {
    private val utc = TimeZone.UTC
    // 2026-11-12 (Thursday) 12:00:00 UTC
    private val now = 1_794_484_800L
    private fun cfg(p: ProcessingConfig) = ProcessingCarryOver.settle(AppConfig(processing = p, general = GeneralConfig(timezone = "UTC")))

    @Test
    fun `a word becomes a schedule at the time of the last run or 04-00 and Monday with none`() {
        val last = now - 9 * 3600L - 48 * 60L   // Thursday 02:12
        assertEquals("0 * * * *", ScheduleCarry.fromWord("hourly", last, utc))
        assertEquals("12 2 * * *", ScheduleCarry.fromWord("daily", last, utc))
        assertEquals("12 2 * * 4", ScheduleCarry.fromWord("weekly", last, utc))
        assertEquals("0 4 * * *", ScheduleCarry.fromWord("daily", null, utc))
        assertEquals("0 4 * * 1", ScheduleCarry.fromWord("weekly", null, utc))
        assertEquals("12 3 * * *", ScheduleCarry.fromWord("daily", last, TimeZone.of("Europe/Copenhagen")), "the hour is the last run's, in the schedule zone")
    }

    @Test
    fun `carry-over fills only the absent keys and a blank one stays only-by-hand`() {
        val p = ProcessingConfig().let { it.copy(music = it.music.copy(interval = "weekly"), audiobooks = it.audiobooks.copy(schedule = "")) }
        val out = ProcessingCarryOver.carrySchedules(cfg(p)) { if (it == ProcessingFeatures.MUSIC) now - 3600L else null }.processing!!
        assertEquals("0 11 * * 4", out.music.schedule)
        assertEquals("", out.audiobooks.schedule, "a blank schedule is a choice, not an absence")
        assertEquals("0 4 * * 1", out.suggestions.recommendationsSchedule)
        assertFalse(ProcessingCarryOver.schedulesPending(ProcessingCarryOver.carrySchedules(cfg(p)) { null }))
        // Idempotent: a second pass changes nothing.
        val again = ProcessingCarryOver.carrySchedules(ProcessingCarryOver.carrySchedules(cfg(p)) { null }) { now }
        assertEquals(ProcessingCarryOver.carrySchedules(cfg(p)) { null }, again)
    }

    @Test
    fun `every save writes the old word a rolled-back image reads`() {
        val p = ProcessingConfig().let {
            it.copy(
                music = it.music.copy(schedule = "0 * * * *"),
                audiobooks = it.audiobooks.copy(schedule = "0 4 * * 1,2"),   // two fires 24 h apart, then 5 days
                suggestions = it.suggestions.copy(recommendationsSchedule = "0 4 1 * *", suggestionsSchedule = "0 */6 * * *"),
            )
        }
        val out = cfg(p).processing!!
        assertEquals("hourly", out.music.interval)
        assertEquals("daily", out.audiobooks.interval, "Mon 04:00 → Tue 04:00 is 24 h")
        assertEquals("weekly", out.suggestions.recommendationsEvery, "monthly is not within 36 h")
        assertEquals("daily", out.suggestions.suggestionsEvery, "Recommendations and Suggestions have no hourly word")
        assertNull(ScheduleCarry.legacyWord("", utc, now, true), "a blank schedule leaves the word it has")
    }

    @Test
    fun `an old admin that edits the word and leaves the schedule wins and the schedule is carried over again`() {
        val stored = cfg(ProcessingConfig().let { it.copy(music = it.music.copy(schedule = "0 4 * * 1,2")) })
        val sent = stored.copy(processing = stored.processing!!.let { it.copy(music = it.music.copy(interval = "hourly")) })
        val merged = ProcessingCarryOver.reconcile(stored, sent).processing!!
        assertNull(merged.music.schedule)
        assertEquals("hourly", merged.music.interval)
        // The unchanged echo keeps the schedule.
        assertEquals("0 4 * * 1,2", ProcessingCarryOver.reconcile(stored, stored).processing!!.music.schedule)
    }

    @Test
    fun `Music on Monday and Tuesday at 04-00 is due after the missed fire once and not again until the next`() {
        val c = cfg(ProcessingConfig().let { it.copy(music = it.music.copy(schedule = "0 4 * * 1,2"), audiobooks = it.audiobooks.copy(enabled = false)) })
        fun due(last: Long?, at: Long = now) = ProcessingFeatures.MUSIC in ProcessingSchedule.dueFeatures(c, at, { if (it == ProcessingFeatures.MUSIC) last else null }, { null })
        val tue0400 = 1_794_484_800L - 2 * 86_400L - 8 * 3600L   // Tuesday 2026-11-10 04:00
        assertTrue(due(null), "never run is due")
        assertTrue(due(tue0400 - 3600L), "fires missed since a run before Tuesday 04:00 are one run")
        assertFalse(due(tue0400 + 60L), "ran after Tuesday's fire; the next is Monday")
        assertFalse(due(tue0400 + 60L, at = tue0400 + 6 * 86_400L - 60L), "Sunday night: Monday 04:00 has not come")
        assertTrue(due(tue0400 + 60L, at = tue0400 + 6 * 86_400L), "Monday 04:00")
    }

    @Test
    fun `a blank schedule is only by hand and a recurrence runs from its anchor`() {
        val blank = cfg(ProcessingConfig().let { it.copy(music = it.music.copy(schedule = ""), audiobooks = it.audiobooks.copy(enabled = false)) })
        assertTrue(ProcessingSchedule.dueFeatures(blank, now, { null }, { null }).none { it == ProcessingFeatures.MUSIC })
        val rec = cfg(ProcessingConfig().let { it.copy(music = it.music.copy(enabled = false), audiobooks = it.audiobooks.copy(schedule = "every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18")) })
        fun due(last: Long, at: Long) = ProcessingFeatures.AUDIOBOOKS in ProcessingSchedule.dueFeatures(rec, at, { if (it == ProcessingFeatures.AUDIOBOOKS) last else null }, { null })
        val nov18 = 1_794_484_800L + 6 * 86_400L - 8 * 3600L   // 2026-11-18 04:00
        assertFalse(due(nov18 + 60L, nov18 + 7 * 86_400L + 3600L), "25 Nov is skipped")
        assertTrue(due(nov18 + 60L, nov18 + 14 * 86_400L), "2 Dec")
    }

    @Test
    fun `the Suggestions parts are due by their own schedules and the step checks again when it runs`() {
        val c = cfg(ProcessingConfig().let { it.copy(music = it.music.copy(enabled = false), audiobooks = it.audiobooks.copy(enabled = false),
            suggestions = it.suggestions.copy(recommendationsSchedule = "0 4 * * *")) })
        fun due(built: Long?) = ProcessingFeatures.SUGGESTIONS in ProcessingSchedule.dueFeatures(c, now, { null }, { if (it == RecommendationsStep.STEP) built else null })
        assertTrue(due(null)); assertTrue(due(now - 86_400L)); assertFalse(due(now - 3600L))
        assertTrue(ProcessingSchedule.partDue(c, RecommendationsStep.STEP, now - 86_400L, now, byHand = false))
        assertFalse(ProcessingSchedule.partDue(c, RecommendationsStep.STEP, now - 3600L, now, byHand = false))
        assertTrue(ProcessingSchedule.partDue(c, RecommendationsStep.STEP, now - 3600L, now, byHand = true), "a run started by hand always builds")
    }

    @Test
    fun `the row says the schedule in the shared sentence`() {
        val c = cfg(ProcessingConfig().let { it.copy(music = it.music.copy(schedule = "0 4 * * 1,2")) })
        val words = ProcessingSchedule.describeFeature(c.processing!!, ProcessingFeatures.MUSIC, utc, null)
        assertNotNull(words); assertTrue("04:00" in words && "Mon" in words && "Tue" in words, words)
        assertEquals(dev.jellystructure.cron.ScheduleWords.OFF, ProcessingSchedule.describeFeature(cfg(ProcessingConfig().let { it.copy(music = it.music.copy(schedule = "")) }).processing!!, ProcessingFeatures.MUSIC, utc, null))
    }
}
