package dev.jellystructure.media

import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.model.MediaItem

/** Phase 261 — what a file check has stored for one file (null = never checked): the size+mtime it
 *  described (254 FR-254-3's currency rule), when, and whether it found something. */
data class StoredFileCheck(val size: Long, val mtime: Long, val checkedAtSec: Long, val hasFinding: Boolean)

/** Why a file is due: it has no current result (new, changed, never checked — cadence irrelevant), or its
 *  clean result has aged past the tier's cadence. The two are queued at different priorities (dev review 2). */
enum class FileCheckDue { NO_RESULT, CADENCE }

/** One video file of a title, and how the Jobs view names it (*S01E04*; null for a film). */
data class FileUnit(val item: MediaItem, val path: String, val episodeTag: String?, val season: Int? = null)

/**
 * Phase 261 (FR-261-7) — "due" is one rule, pure so it is tested rather than eyeballed; the pipeline
 * steps, the title's Check now and the Checks card all go through it.
 */
object FileCheckSchedule {
    /** Dev review item 2 — a cadence re-check yields to everything else on the lane; a new or changed file
     *  queues with segment work, as the sweep did; an operator's Check now goes first. */
    const val PRIORITY_CADENCE = -1L
    const val PRIORITY_NEW = 0L
    const val PRIORITY_OPERATOR = 1L

    /** FR-261-1 — the queue's job type for each step. */
    const val VERIFY_JOB = "verify_file"
    const val LENGTHS_JOB = "check_track_lengths"
    val JOB_TYPES = listOf(VERIFY_JOB, LENGTHS_JOB)
    fun jobTypeFor(step: String): String? = when (step) {
        FileCheckSteps.VERIFY -> VERIFY_JOB
        FileCheckSteps.LENGTHS -> LENGTHS_JOB
        else -> null
    }
    fun dedupeKey(jobType: String, path: String): String = (if (jobType == VERIFY_JOB) "verify:" else "lengths:") + path

    /** FR-261-7 — null when the file is not due. A changed file is always due; a stored finding is never
     *  re-derived by the cadence (it stands until the file changes or the operator re-runs it). */
    fun due(stored: StoredFileCheck?, size: Long, mtime: Long, nowMs: Long, releaseYear: Int?, currentYear: Int, step: PipelineStep): FileCheckDue? {
        if (stored == null || stored.size != size || stored.mtime != mtime) return FileCheckDue.NO_RESULT
        val next = nextCadenceDueMs(stored, releaseYear, currentYear, step) ?: return null
        return if (nowMs >= next) FileCheckDue.CADENCE else null
    }

    /** When a CURRENT result is next due by cadence; null = not by cadence (a finding; the step's schedule
     *  switched off; a `never` tier). Dev review item 4: never the airing floor — a new episode is a new
     *  file, which has no result and is due regardless, so the floor would only re-read old files daily. */
    fun nextCadenceDueMs(stored: StoredFileCheck, releaseYear: Int?, currentYear: Int, step: PipelineStep): Long? {
        if (stored.hasFinding || !step.recheckUnchanged) return null
        // A title with no year sits in the oldest tier (release year 0), the gentlest cadence.
        return nextDueMs(stored.checkedAtSec * 1000, releaseYear ?: 0, currentYear, step, isActivelyAiring = false)
    }

    /** Every video file of [item], once — a multi-episode file is one file ([FileIntegrityService.videoPaths]'
     *  own rule, with the episode tag of its first episode). */
    fun units(item: MediaItem): List<FileUnit> {
        val raw = if (item.episodes.isNotEmpty()) item.episodes.map { ep ->
            FileUnit(item, ep.path, ep.seasonNumber?.let { s -> "S${s.toString().padStart(2, '0')}" + (ep.episodeNumber?.let { e -> "E${e.toString().padStart(2, '0')}" } ?: "") }, ep.seasonNumber)
        } else listOf(FileUnit(item, item.path, null))
        return raw.filter { it.path.substringAfterLast('.').lowercase() in FileIntegrityService.VIDEO_EXTENSIONS }.distinctBy { it.path }
    }

    /** FR-261-1 — *Verify · Title · S01E04*. */
    fun label(jobType: String, unit: FileUnit): String =
        (if (jobType == VERIFY_JOB) "Verify" else "Track lengths") + " · ${unit.item.title}" + (unit.episodeTag?.let { " · $it" } ?: "")
}
