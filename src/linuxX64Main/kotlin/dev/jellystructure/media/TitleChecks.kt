package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.needsRecommendationSignals
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Phase 261 (FR-261-11) — `GET /api/media/{id}/checks`: the title page's Checks card, one payload the page
 *  renders as is. Every date and state is decided here; the page computes none. */
@Serializable
data class TitleChecksDto(val steps: List<StepCheckDto>)

@Serializable
data class StepCheckDto(
    val step: String,
    val label: String,
    val enabled: Boolean,
    /** When it last ran for this title (epoch s), and the same as the card prints it — *not yet* when never. */
    @SerialName("last_at") val lastAt: Long? = null,
    val last: String,
    /** ok · changed · skipped · failed, or for the file steps clean · finding · partial; null before a first run. */
    val outcome: String? = null,
    val detail: String? = null,
    @SerialName("next_due_at") val nextDueAt: Long? = null,
    @SerialName("next_due") val nextDue: String,
    /** The existing per-title action this line's ↻ runs, or null — the card never invents one (FR-261-10). */
    val action: String? = null,
    val files: List<FileCheckRowDto> = emptyList(),
)

@Serializable
data class FileCheckRowDto(
    val path: String,
    val name: String,
    val season: Int? = null,
    @SerialName("episode_tag") val episodeTag: String? = null,
    /** clean · finding · unchecked */
    val state: String,
    @SerialName("checked_at") val checkedAt: Long? = null,
    val checked: String,
    @SerialName("next_due_at") val nextDueAt: Long? = null,
    @SerialName("next_due") val nextDue: String,
    val detail: String? = null,
)

/**
 * FR-261-9/10 — "what has this title been checked against, and when". `scan_files` reads the scan's own
 * `last_examined_at`; the steps that run over the scan's working set read [StepRunStore] and are next due
 * with the title's next scan; the two file steps are derived from their per-file tables (never a second
 * source, dev review item 7) and dated by [FileCheckSchedule], the rule the scheduler itself uses.
 */
class TitleChecks(
    private val db: JellystructureDb,
    private val store: MediaStore,
    private val stepRuns: StepRunStore?,
    private val integrity: FileIntegrityService?,
    private val coverage: TrackCoverageService?,
) {
    fun forItem(item: MediaItem, config: AppConfig): TitleChecksDto {
        val nowMs = store.nowMs()
        val currentYear = yearFromEpochMs(nowMs)
        val configured = config.scan.pipeline.ifEmpty { effectivePipeline(config) }
        // OQ2 — the card lists what is done TO this title; `wait`, `notify` and the whole-library
        // `build_recommendations` (Phase 269) are not.
        val steps = (listOf(configured.firstOrNull { it.step == "scan_files" } ?: PipelineStep(step = "scan_files")) +
            configured.filter { it.step != "scan_files" && it.step !in setOf("wait", "notify", dev.jellystructure.config.RecommendationsStep.STEP) })
            .distinctBy { it.step }
        val scanStep = steps.first()
        val runs = stepRuns?.forItem(item.id) ?: emptyMap()
        val active = db.mediaJobQueries.activeFileChecksForItem(item.id, FileCheckSchedule.JOB_TYPES).executeAsList()
            .mapNotNull { it.dedupe_key }.toSet()

        // The title's next scan — what every working-set step waits for (dev review item 6).
        val lastScanMs = store.lastExaminedAt(item.id)
        val nextScanMs: Long? = when {
            !scanStep.recheckUnchanged || lastScanMs == null || item.year == null -> null
            else -> nextDueMs(lastScanMs, item.year, currentYear, scanStep, isActivelyAiring(item, nowMs))
        }
        val nextScanText = when {
            !scanStep.recheckUnchanged || lastScanMs == null || item.year == null -> "every run"
            nextScanMs == null -> "never"
            else -> dueLine(nextScanMs, nowMs)
        }

        return TitleChecksDto(steps.map { step ->
            val label = STEP_LABELS[step.step] ?: step.step
            val run = runs[step.step]
            fun off() = !step.enabled
            when (step.step) {
                "scan_files" -> StepCheckDto(
                    step = step.step, label = label, enabled = true,
                    lastAt = lastScanMs?.div(1000), last = lastScanMs?.let { dayText(it, nowMs) } ?: "not yet",
                    outcome = lastScanMs?.let { StepRunStore.OK }, nextDueAt = nextScanMs?.div(1000), nextDue = nextScanText, action = "sync",
                )
                FileCheckSteps.VERIFY, FileCheckSteps.LENGTHS -> fileStep(item, step, label, active, nowMs, currentYear)
                else -> StepCheckDto(
                    step = step.step, label = label, enabled = step.enabled,
                    lastAt = run?.ran_at, last = run?.ran_at?.let { dayText(it * 1000, nowMs) } ?: "not yet",
                    outcome = run?.outcome, detail = run?.detail,
                    nextDueAt = if (off()) null else nextScanMs?.takeIf { dueWithScan(step, item) }?.div(1000),
                    nextDue = when {
                        off() -> "off"
                        step.step == "sync_jellyfin" -> "when its NFO changes"
                        step.step == "detect_segments" && step.scope != "all" -> "when a marker is missing"
                        step.step == "pull_tmdb" && step.scope != "all" && item.tmdbId != null && !item.needsRecommendationSignals() -> "only while unmatched"
                        step.step == "fetch_artwork" && step.scope != "all" -> "when artwork is missing"
                        step.step == "sync_imdb_ratings" && item.imdbId.isNullOrBlank() -> "no IMDb id"
                        nextScanText == "every run" || nextScanText == "never" -> nextScanText
                        else -> "$nextScanText, with the next scan"
                    },
                    action = ACTIONS[step.step],
                )
            }
        })
    }

    private fun dueWithScan(step: PipelineStep, item: MediaItem): Boolean = when (step.step) {
        "sync_jellyfin" -> false
        "detect_segments", "fetch_artwork" -> step.scope == "all"
        "pull_tmdb" -> step.scope == "all" || item.tmdbId == null || item.needsRecommendationSignals()  // Phase 269
        "sync_imdb_ratings" -> !item.imdbId.isNullOrBlank()
        else -> true
    }

    /** FR-261-10 — a file step's line (its latest check, its worst state, its earliest next due) and its files. */
    private fun fileStep(item: MediaItem, step: PipelineStep, label: String, active: Set<String>, nowMs: Long, currentYear: Int): StepCheckDto {
        val jobType = FileCheckSchedule.jobTypeFor(step.step)!!
        val rows: List<FileCheckRowDto> = FileCheckSchedule.units(item).map { unit ->
            val stamp = FileIntegrityService.stampOf(unit.path)
            val queued = FileCheckSchedule.dedupeKey(jobType, unit.path) in active
            val (stored, detail) = storedFor(jobType, unit.path)
            val current = stored != null && stamp != null && stored.size == stamp.size && stored.mtime == stamp.mtime
            val state = when {
                !current -> "unchecked"
                stored!!.hasFinding -> "finding"
                else -> "clean"
            }
            val nextMs = if (current && step.enabled) FileCheckSchedule.nextCadenceDueMs(stored!!, item.year, currentYear, step) else null
            FileCheckRowDto(
                path = unit.path, name = unit.path.substringAfterLast('/'), season = unit.season, episodeTag = unit.episodeTag,
                state = state, checkedAt = stored?.checkedAtSec?.takeIf { current }, checked = if (current) dayText(stored!!.checkedAtSec * 1000, nowMs) else "not yet",
                nextDueAt = nextMs?.div(1000),
                nextDue = when {
                    queued -> "queued"
                    !step.enabled -> "off"
                    stamp == null -> "file missing"
                    !current -> "next run"
                    state == "finding" -> "until the file changes"
                    nextMs == null -> if (!step.recheckUnchanged) "when the file changes" else "never"
                    else -> dueLine(nextMs, nowMs)
                },
                detail = if (current && state == "finding") detail else null,
            )
        }
        val checked = rows.mapNotNull { it.checkedAt }
        val outcome = when {
            rows.isEmpty() || checked.isEmpty() -> null
            rows.any { it.state == "finding" } -> "finding"
            rows.any { it.state == "unchecked" } -> "partial"
            else -> "clean"
        }
        val findings = rows.count { it.state == "finding" }
        val unchecked = rows.count { it.state == "unchecked" }
        val soonest = rows.mapNotNull { it.nextDueAt }.minOrNull()
        return StepCheckDto(
            step = step.step, label = label, enabled = step.enabled,
            lastAt = checked.maxOrNull(), last = checked.maxOrNull()?.let { dayText(it * 1000, nowMs) } ?: "not yet",
            outcome = outcome,
            detail = listOfNotNull(
                findings.takeIf { it > 0 }?.let { "$it with a finding" },
                unchecked.takeIf { it > 0 }?.let { "$it not yet checked" },
            ).joinToString(" · ").ifEmpty { null },
            nextDueAt = soonest,
            nextDue = when {
                !step.enabled -> "off"
                rows.any { it.nextDue == "queued" } -> "queued"
                unchecked > 0 -> "next run"
                soonest != null -> dueLine(soonest * 1000, nowMs)
                !step.recheckUnchanged -> "when a file changes"
                else -> "never"
            },
            action = if (step.step == FileCheckSteps.VERIFY) "check_verify" else "check_lengths",
            files = rows,
        )
    }

    private fun storedFor(jobType: String, path: String): Pair<StoredFileCheck?, String?> = when (jobType) {
        FileCheckSchedule.VERIFY_JOB -> {
            val r = integrity?.rowsFor(path)
            (r?.let { StoredFileCheck(it.size, it.mtime, it.checked_at, it.damage_count > 0) }) to r?.let { row ->
                "${row.damage_count} damage line${if (row.damage_count == 1L) "" else "s"}" + (row.first_damage?.let { " — $it" } ?: "")
            }
        }
        else -> {
            val r = coverage?.rowFor(path)
            (r?.let { StoredFileCheck(it.size, it.mtime, it.checked_at, it.findings != "[]") }) to r?.let { coverage.findingsSummary(it) }
        }
    }

    companion object {
        val STEP_LABELS = mapOf(
            "scan_files" to "Scan files", "pull_tmdb" to "TMDB metadata", "fetch_artwork" to "Artwork",
            "detect_segments" to "Intro & credits", "write_nfo" to "NFO files", "sync_jellyfin" to "Jellyfin sync",
            "rescan_arr" to "Radarr / Sonarr rescan", "detect_drift" to "Drift", "sync_imdb_ratings" to "IMDb rating",
            "prewarm_subtitles" to "Subtitle pre-warm", FileCheckSteps.VERIFY to "Verify files", FileCheckSteps.LENGTHS to "Track lengths",
        )

        /** FR-261-10 — only actions a title already has; the page maps each key to the call it already makes. */
        private val ACTIONS = mapOf(
            "pull_tmdb" to "sync_metadata", "fetch_artwork" to "fetch_artwork", "write_nfo" to "write_nfo",
            "sync_jellyfin" to "jellyfin_refresh", "sync_imdb_ratings" to "sync_imdb",
        )

        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** UTC civil date for [epochMs] — the same algorithm as FreshnessFilter's date string. */
        internal fun civil(epochMs: Long): Triple<Int, Int, Int> {
            var d = (epochMs / 86_400_000L).toInt()
            var y = 1970
            while (true) {
                val diy = if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 366 else 365
                if (d < diy) break
                d -= diy; y++
            }
            val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
            val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            var m = 0
            while (m < 11 && d >= monthDays[m]) { d -= monthDays[m]; m++ }
            return Triple(y, m, d + 1)
        }

        /** *3 Aug* this year, *3 Aug 2025* otherwise. */
        internal fun dayText(epochMs: Long, nowMs: Long): String {
            val (y, m, d) = civil(epochMs)
            return "$d ${MONTHS[m]}" + if (y != civil(nowMs).first) " $y" else ""
        }

        /** The card's whole phrase: *due now*, or *next due 3 Oct* / *next due 2031*. */
        internal fun dueLine(dueMs: Long, nowMs: Long): String = dueText(dueMs, nowMs).let { if (it == "due now") it else "next due $it" }

        /** *due now* when it has passed; *3 Oct* this year; a later year alone (*2031*) — the card's "next due 2031". */
        internal fun dueText(dueMs: Long, nowMs: Long): String {
            if (dueMs <= nowMs) return "due now"
            val (y, m, d) = civil(dueMs)
            return if (y == civil(nowMs).first) "$d ${MONTHS[m]}" else "$y"
        }
    }
}

