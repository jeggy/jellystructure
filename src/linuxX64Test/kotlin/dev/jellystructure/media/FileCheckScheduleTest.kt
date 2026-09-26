package dev.jellystructure.media

import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 261 (FR-261-7, acceptance 1) — when a file check is due: a file with no current result is due
 * whatever the cadence, a clean result is due once the tier's cadence has passed, a finding is never
 * re-derived by the cadence, and the airing floor never reaches a file (dev review item 4).
 */
class FileCheckScheduleTest {
    private val DAY = 24 * 3_600_000L
    private val YEAR = 365 * DAY
    private val now = 20_000L * DAY           // mid-2024 — a plain epoch, not the wall clock
    private val currentYear = yearFromEpochMs(now)
    private val verify = FileCheckSteps.defaultStep(FileCheckSteps.VERIFY)      // 5years on every tier
    private val lengths = FileCheckSteps.defaultStep(FileCheckSteps.LENGTHS)    // yearly / 2years / 5years

    private fun stored(checkedAgoMs: Long, finding: Boolean = false, size: Long = 100, mtime: Long = 7) =
        StoredFileCheck(size = size, mtime = mtime, checkedAtSec = (now - checkedAgoMs) / 1000, hasFinding = finding)

    @Test
    fun `a file with no stored result — or a changed one — is due at once`() {
        assertEquals(FileCheckDue.NO_RESULT, FileCheckSchedule.due(null, 100, 7, now, 2001, currentYear, verify))
        // Checked a minute ago — but the file is not the one that was checked (size, then mtime).
        assertEquals(FileCheckDue.NO_RESULT, FileCheckSchedule.due(stored(60_000), 101, 7, now, 2001, currentYear, verify))
        assertEquals(FileCheckDue.NO_RESULT, FileCheckSchedule.due(stored(60_000), 100, 8, now, 2001, currentYear, verify))
        // Even with the schedule switched off: a changed file is always due.
        assertEquals(FileCheckDue.NO_RESULT, FileCheckSchedule.due(stored(60_000), 101, 7, now, 2001, currentYear, verify.copy(recheckUnchanged = false)))
    }

    @Test
    fun `a clean result is due once its tier's cadence has passed`() {
        assertNull(FileCheckSchedule.due(stored(5 * YEAR - DAY), 100, 7, now, 2001, currentYear, verify))
        assertEquals(FileCheckDue.CADENCE, FileCheckSchedule.due(stored(5 * YEAR), 100, 7, now, 2001, currentYear, verify))
        // Track lengths: this year's titles yearly, 1–5 years 2years, older 5years.
        assertEquals(FileCheckDue.CADENCE, FileCheckSchedule.due(stored(YEAR), 100, 7, now, currentYear, currentYear, lengths))
        assertNull(FileCheckSchedule.due(stored(YEAR), 100, 7, now, currentYear - 2, currentYear, lengths))
        assertEquals(FileCheckDue.CADENCE, FileCheckSchedule.due(stored(2 * YEAR), 100, 7, now, currentYear - 2, currentYear, lengths))
        assertNull(FileCheckSchedule.due(stored(2 * YEAR), 100, 7, now, currentYear - 10, currentYear, lengths))
        // No year: the oldest (gentlest) tier.
        assertNull(FileCheckSchedule.due(stored(2 * YEAR), 100, 7, now, null, currentYear, lengths))
    }

    @Test
    fun `a stored finding is not re-queued by the cadence`() {
        assertNull(FileCheckSchedule.due(stored(20 * YEAR, finding = true), 100, 7, now, 2001, currentYear, verify))
        assertNull(FileCheckSchedule.nextCadenceDueMs(stored(0, finding = true), 2001, currentYear, verify))
        // …but a finding on a file that has since changed is a file with no current result.
        assertEquals(FileCheckDue.NO_RESULT, FileCheckSchedule.due(stored(DAY, finding = true), 100, 99, now, 2001, currentYear, verify))
    }

    @Test
    fun `the schedule switched off means only new and changed files`() {
        val off = lengths.copy(recheckUnchanged = false)
        assertNull(FileCheckSchedule.due(stored(20 * YEAR), 100, 7, now, currentYear, currentYear, off))
        assertNull(FileCheckSchedule.nextCadenceDueMs(stored(0), currentYear, currentYear, off))
    }

    @Test
    fun `the next cadence date is the check plus the tier — with no airing floor`() {
        val s = stored(0)
        assertEquals(now + 5 * YEAR, FileCheckSchedule.nextCadenceDueMs(s, 2001, currentYear, verify))
        // A `never` tier stays never for a this-year title — the airing floor would have made it daily.
        val never = verify.copy(refreshThisYear = "never")
        assertNull(FileCheckSchedule.nextCadenceDueMs(s, currentYear, currentYear, never))
    }

    private fun ep(path: String, season: Int, episode: Int) = Episode(
        filename = path.substringAfterLast('/'), path = path, seasonNumber = season, episodeNumber = episode,
        tracks = emptyList(), issueCount = 0,
    )

    @Test
    fun `a series is one unit per video file — labelled by episode`() {
        val series = MediaItem(
            id = "s1", title = "Starhaul", year = 2020, kind = MediaKind.TV_SHOW, path = "/m/Starhaul", tmdbId = null,
            originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
            episodes = listOf(ep("/m/Starhaul/S02E14.mkv", 2, 14), ep("/m/Starhaul/S02E14.mkv", 2, 15), ep("/m/Starhaul/S02E16.nfo", 2, 16)),
        )
        val units = FileCheckSchedule.units(series)
        assertEquals(listOf("/m/Starhaul/S02E14.mkv"), units.map { it.path }, "a two-episode file is one file; a non-video path is none")
        assertEquals("Verify · Starhaul · S02E14", FileCheckSchedule.label(FileCheckSchedule.VERIFY_JOB, units.single()))
        assertEquals("verify:/m/Starhaul/S02E14.mkv", FileCheckSchedule.dedupeKey(FileCheckSchedule.VERIFY_JOB, units.single().path))
        assertEquals("lengths:/m/Starhaul/S02E14.mkv", FileCheckSchedule.dedupeKey(FileCheckSchedule.LENGTHS_JOB, units.single().path))
    }
}
