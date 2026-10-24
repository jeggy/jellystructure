package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.MusicSteps
import dev.jellystructure.config.ProcessingConfig
import dev.jellystructure.config.ProcessingFeatures
import dev.jellystructure.config.RecommendationsStep
import dev.jellystructure.config.SuggestionsStep
import dev.jellystructure.config.featureOn
import dev.jellystructure.config.isWholeLibraryStep
import dev.jellystructure.config.intervalSeconds
import dev.jellystructure.config.partOn
import dev.jellystructure.config.processingOrDerived
import dev.jellystructure.cron.CronParse
import dev.jellystructure.cron.parseCron
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.nowEpochSec
import kotlinx.serialization.Serializable

// DEB-1664 (FR-3, FR-9) — when each processing feature last ran, which whole-library features are due on their own interval,
// and the feature list `GET /api/processing` answers. Every sentence is built here (FR-12.1); the admin draws what it is given.

/** When each feature last ran. Never throws into the engine: a lost row reads as an older "last ran", nothing worse. */
class ProcessingRuns(private val db: JellystructureDb) {
    fun record(featureId: String, atSec: Long = nowEpochSec()) {
        runCatching { db.processingFeatureRunQueries.put(featureId, atSec) }
    }

    fun all(): Map<String, Long> = runCatching {
        db.processingFeatureRunQueries.all().executeAsList().associate { it.feature to it.last_ran_at }
    }.getOrDefault(emptyMap())
}

object ProcessingSchedule {
    /** A run at the same time each period is not "not due" by a few minutes. */
    private const val SLACK_SEC = 300L

    /**
     * FR-3.3 — the whole-library features that are due on their own interval: Music and Audiobooks by `interval`, measured from
     * their last run (or never run), Recommendations & suggestions when a part's own cadence has passed since it was built.
     * A feature that is off is never due, and a part that cannot run (no Seerr) is not counted.
     */
    fun dueFeatures(cfg: AppConfig, nowSec: Long, lastRan: (String) -> Long?, lastBuilt: (String) -> Long?): Set<String> {
        val p = cfg.processingOrDerived()
        val due = mutableSetOf<String>()
        fun byInterval(id: String, word: String) {
            val last = lastRan(id)
            if (last == null || nowSec - last >= intervalSeconds(word) - SLACK_SEC) due += id
        }
        if (p.music.enabled) byInterval(ProcessingFeatures.MUSIC, p.music.interval)
        if (p.audiobooks.enabled) byInterval(ProcessingFeatures.AUDIOBOOKS, p.audiobooks.interval)
        if (p.suggestions.enabled) {
            fun cadenceDue(step: String, every: String, table: Map<String, Long>): Boolean {
                val secs = table[every] ?: table.getValue(RecommendationsStep.DEFAULT_CADENCE)
                return !RecommendationsStep.notDue(lastBuilt(step), nowSec, secs, byHand = false)
            }
            if (p.partOn(RecommendationsStep.STEP) && cadenceDue(RecommendationsStep.STEP, p.suggestions.recommendationsEvery, RecommendationsStep.CADENCES)) due += ProcessingFeatures.SUGGESTIONS
            if (p.partOn(SuggestionsStep.STEP) && seerrConnected(cfg) && cadenceDue(SuggestionsStep.STEP, p.suggestions.suggestionsEvery, SuggestionsStep.CADENCES)) due += ProcessingFeatures.SUGGESTIONS
        }
        return due
    }

    /**
     * The steps an automatic run (scheduled or at start-up) takes: a whole-library step whose feature is not due on its own
     * interval is left for the run it is due in. A run someone started ([manual]) takes everything it was given (FR-6.3).
     * Recommendations and suggestions keep their own "not due" answer inside the step, so they are not filtered here.
     */
    fun stepsForRun(pipeline: List<dev.jellystructure.config.PipelineStep>, manual: Boolean, due: Set<String>): List<dev.jellystructure.config.PipelineStep> =
        if (manual) pipeline else pipeline.filter { s ->
            val f = ProcessingFeatures.featureOfStep(s.step)
            !(isWholeLibraryStep(s.step) && (f == ProcessingFeatures.MUSIC || f == ProcessingFeatures.AUDIOBOOKS) && f !in due)
        }

    /** The steps of the features in [ids], for a run that is only those features (FR-3.3), in the one dependency order. */
    fun stepsOf(pipeline: List<dev.jellystructure.config.PipelineStep>, ids: Set<String>): List<dev.jellystructure.config.PipelineStep> =
        pipeline.filter { ProcessingFeatures.featureOfStep(it.step) in ids && isWholeLibraryStep(it.step) }

    fun seerrConnected(cfg: AppConfig): Boolean = cfg.seerr?.let { it.enabled && it.url.isNotBlank() } == true

    /** The Find schedule in words: the presets the Settings picker emits are named, anything else is *Custom* with its expression (FR-3.2). */
    fun describeFind(cron: String): String {
        val c = cron.trim()
        if (c.isEmpty()) return "Not on a schedule — it looks when you ask it to"
        fun time(h: String, m: String) = h.padStart(2, '0') + ":" + m.padStart(2, '0')
        Regex("""(\d+) \*/(\d+) \* \* \*""").matchEntire(c)?.let { m ->
            val n = m.groupValues[2].toIntOrNull() ?: return@let
            val at = m.groupValues[1].toIntOrNull()?.takeIf { it != 0 }?.let { " at :" + it.toString().padStart(2, '0') } ?: ""
            return (if (n == 1) "Every hour" else "Every $n hours") + at
        }
        Regex("""(\d+) (\d+) \* \* 0""").matchEntire(c)?.let { return "Weekly on Sunday at " + time(it.groupValues[2], it.groupValues[1]) }
        Regex("""(\d+) (\d+) \* \* \*""").matchEntire(c)?.let { return "Daily at " + time(it.groupValues[2], it.groupValues[1]) }
        return "Custom · $c"
    }

    /** Epoch seconds of the next fire of [cron], or null for a blank or unusable one. */
    fun nextFind(cron: String, nowSec: Long): Long? {
        if (cron.isBlank()) return null
        val parsed = parseCron(cron) as? CronParse.Ok ?: return null
        return dev.jellystructure.cron.nextFireEpochSec(parsed.expr, nowSec)
    }

    fun agoText(thenSec: Long?, nowSec: Long): String {
        if (thenSec == null) return "Not run yet"
        val d = (nowSec - thenSec).coerceAtLeast(0)
        return "Ran " + when {
            d < 90 -> "just now"
            d < 90 * 60 -> "${(d + 30) / 60} min ago"
            d < 36 * 3600 -> "${(d + 1800) / 3600} h ago"
            else -> "${(d + 43200) / 86400} days ago"
        }
    }

    fun inText(thenSec: Long?, nowSec: Long): String {
        if (thenSec == null) return "Not scheduled"
        val d = thenSec - nowSec
        return when {
            d <= 90 -> "Due now"
            d < 90 * 60 -> "In ${(d + 30) / 60} min"
            d < 36 * 3600 -> "In ${(d + 1800) / 3600} h"
            else -> "In ${(d + 43200) / 86400} days"
        }
    }
}

// ── GET /api/processing ──────────────────────────────────────────────────────────────────────────────────────────────

/** One part of a feature. [on] is what Settings says; [canRun] is whether it can do anything right now, and [reason] says why not. */
@Serializable
data class ProcessingPartView(val id: String, val name: String, val on: Boolean, val canRun: Boolean, val reason: String? = null)

@Serializable
data class ProcessingFeatureView(
    val id: String,
    val name: String,
    val queuesWork: Boolean,
    val enabled: Boolean,
    /** Shown on and disabled (Find new & changed). */
    val locked: Boolean,
    val canRun: Boolean,
    /** `on` | `off` | `unavailable` | `running` | `waiting` (held for a TV). */
    val state: String,
    val reason: String? = null,
    val interval: String,
    val lastRanAt: Long? = null,
    val lastRan: String,
    val nextAt: Long? = null,
    val next: String,
    val waiting: Int = 0,
    val waitingText: String,
    val parts: List<ProcessingPartView>,
)

@Serializable
data class ProcessingView(val features: List<ProcessingFeatureView>, val holdingForPlayback: Boolean = false)

/** Everything the view reads that is not the configuration — gathered by the route, so the build below is a pure function. */
class ProcessingInputs(
    val nowSec: Long,
    val lastRan: Map<String, Long> = emptyMap(),
    val queuedJobTypes: List<String> = emptyList(),
    /** The file-fix kinds with whether each is switched on (database rows, FR-2.2). */
    val directPlay: List<Pair<String, Boolean>> = emptyList(),
    val lastBuilt: Map<String, Long> = emptyMap(),
    val activeStep: String? = null,
    val runActive: Boolean = false,
    val deferred: Boolean = false,
    val musicLibraries: Boolean = true,
    val audiobookLibraries: Boolean = true,
)

object ProcessingViews {
    /** FR-7.3 / FR-8.3 — which queued job types belong to which feature. */
    val JOB_TYPES: Map<String, Set<String>> = mapOf(
        ProcessingFeatures.SEGMENTS to setOf("segments_movie", "segments_season", "segments_episodes", "waveform_unit", "waveform_backfill"),
        ProcessingFeatures.HEALTH to setOf(FileCheckSchedule.VERIFY_JOB, FileCheckSchedule.LENGTHS_JOB, FileCheckSchedule.AVSYNC_JOB),
        ProcessingFeatures.SUBTITLES to setOf("prewarm_subtitles", FileCheckSchedule.SUBTITLES_JOB),
        ProcessingFeatures.DIRECT_PLAY to setOf(MediaJobQueue.FILE_FIX_JOB),
    )

    fun build(cfg: AppConfig, inp: ProcessingInputs): ProcessingView {
        val p = cfg.processingOrDerived()
        val findNext = ProcessingSchedule.nextFind(p.find.schedule, inp.nowSec)
        val features = ProcessingFeatures.ALL.map { f ->
            val on = if (f.id == ProcessingFeatures.DIRECT_PLAY) inp.directPlay.any { it.second } else p.featureOn(f.id)
            val locked = f.id == ProcessingFeatures.FIND
            val unmapped = when (f.id) {
                ProcessingFeatures.MUSIC -> !inp.musicLibraries
                ProcessingFeatures.AUDIOBOOKS -> !inp.audiobookLibraries
                else -> false
            }
            val parts = if (f.id == ProcessingFeatures.DIRECT_PLAY) {
                inp.directPlay.map { (name, enabled) -> ProcessingPartView(name, name, enabled, canRun = enabled, reason = if (enabled) null else "Off in Settings.") }
            } else f.parts.map { part ->
                val partOn = p.partOn(part.step)
                val blocker = if (on) blockerOf(cfg, p, part.step) else null
                ProcessingPartView(
                    part.step, part.name, partOn,
                    canRun = on && partOn && blocker == null && !unmapped,
                    reason = when {
                        !on -> "Off in Settings."
                        !partOn -> "Off in Settings."
                        unmapped -> unmappedReason(f.id)
                        else -> blocker
                    },
                )
            }
            val waiting = JOB_TYPES[f.id]?.let { types -> inp.queuedJobTypes.count { it in types } } ?: 0
            val running = inp.runActive && inp.activeStep?.let { ProcessingFeatures.featureOfStep(it) } == f.id
            val canRun = locked || (on && !unmapped)
            val state = when {
                locked && inp.runActive && running -> "running"
                !locked && !on -> "off"
                unmapped -> "unavailable"
                running -> "running"
                inp.deferred && waiting > 0 -> "waiting"
                else -> "on"
            }
            val lastAt = inp.lastRan[f.id]
            val nextAt = nextOf(cfg, p, f.id, on, lastAt, findNext, inp)
            ProcessingFeatureView(
                id = f.id, name = f.name, queuesWork = f.queuesWork, enabled = on || locked, locked = locked, canRun = canRun, state = state,
                reason = when {
                    locked -> "Always on: this is how a new or changed title is found."
                    !on -> "Off in Settings — nothing in it runs."
                    unmapped -> unmappedReason(f.id)
                    else -> null
                },
                interval = intervalWords(p, f.id),
                lastRanAt = lastAt, lastRan = ProcessingSchedule.agoText(lastAt, inp.nowSec),
                nextAt = nextAt, next = if (!locked && !on) "Off" else ProcessingSchedule.inText(nextAt, inp.nowSec),
                waiting = waiting,
                waitingText = if (JOB_TYPES[f.id] == null) "" else if (waiting == 0) "Nothing waiting" else "$waiting waiting",
                parts = parts,
            )
        }
        return ProcessingView(features, holdingForPlayback = inp.deferred)
    }

    /** FR-1.4 — a part that depends on a switch elsewhere says so while that switch is off. */
    internal fun blockerOf(cfg: AppConfig, p: ProcessingConfig, step: String): String? = when (step) {
        MusicSteps.TAGS -> if (!cfg.music.writeTags) "Tag writing is off in Settings." else null
        MusicSteps.LYRICS -> if (!cfg.music.fetchLyrics) "Fetching lyrics is off in Settings." else null
        MusicSteps.MATCH -> if (!cfg.musicbrainz.enabled) "The music database is off in Settings." else null
        SuggestionsStep.STEP -> if (!ProcessingSchedule.seerrConnected(cfg)) "Seerr is not connected." else null
        FileCheckSteps.AVSYNC -> if (!cfg.avsync.enabled) "The measuring service is not set up." else null
        "rescan_arr" -> if (!arrRescanOn(cfg)) "No Radarr or Sonarr rescan is switched on." else null
        else -> null
    }

    private fun arrRescanOn(cfg: AppConfig) = listOfNotNull(cfg.radarr, cfg.sonarr).any { it.enabled && it.rescanAfterWrite }

    private fun unmappedReason(id: String) = when (id) {
        ProcessingFeatures.MUSIC -> "No music library is mapped. Map one in Settings ▸ Libraries and this starts on its own."
        else -> "No audiobook library is mapped. Map one in Settings ▸ Libraries and this starts on its own."
    }

    private fun intervalWords(p: ProcessingConfig, id: String): String = when (id) {
        ProcessingFeatures.FIND -> ProcessingSchedule.describeFind(p.find.schedule)
        ProcessingFeatures.METADATA -> if (p.metadata.tiers.recheckUnchanged)
            "Unchanged titles by release age: ${tier(p.metadata.tiers.thisYear)} · ${tier(p.metadata.tiers.oneToFiveYears)} · ${tier(p.metadata.tiers.older)}"
        else "With every look"
        ProcessingFeatures.SEGMENTS, ProcessingFeatures.HEALTH, ProcessingFeatures.SUBTITLES -> "Queued with every look, what is due"
        ProcessingFeatures.SUGGESTIONS -> "Recommendations ${p.suggestions.recommendationsEvery} · suggestions ${p.suggestions.suggestionsEvery}"
        ProcessingFeatures.MUSIC -> intervalWord(p.music.interval)
        ProcessingFeatures.AUDIOBOOKS -> intervalWord(p.audiobooks.interval)
        else -> "Every minute, for the kinds that are on"
    }

    private fun intervalWord(w: String) = when (w) {
        ProcessingFeatures.HOURLY -> "Every hour"
        ProcessingFeatures.WEEKLY -> "Weekly"
        else -> "Daily"
    }

    private fun tier(w: String) = when (w) {
        "daily" -> "daily"; "weekly" -> "weekly"; "monthly" -> "monthly"; "6months" -> "every 6 months"; "yearly" -> "yearly"
        "2years" -> "every 2 years"; "5years" -> "every 5 years"; "never" -> "never"; else -> w
    }

    private fun nextOf(cfg: AppConfig, p: ProcessingConfig, id: String, on: Boolean, lastAt: Long?, findNext: Long?, inp: ProcessingInputs): Long? = when {
        id == ProcessingFeatures.FIND -> findNext
        !on -> null
        id == ProcessingFeatures.MUSIC -> lastAt?.plus(intervalSeconds(p.music.interval)) ?: inp.nowSec
        id == ProcessingFeatures.AUDIOBOOKS -> lastAt?.plus(intervalSeconds(p.audiobooks.interval)) ?: inp.nowSec
        id == ProcessingFeatures.SUGGESTIONS -> listOfNotNull(
            RecommendationsStep.STEP.takeIf { p.partOn(it) }?.let { s -> (inp.lastBuilt[s])?.plus(RecommendationsStep.CADENCES[p.suggestions.recommendationsEvery] ?: 0L) ?: inp.nowSec },
            SuggestionsStep.STEP.takeIf { p.partOn(it) && ProcessingSchedule.seerrConnected(cfg) }?.let { s -> (inp.lastBuilt[s])?.plus(SuggestionsStep.CADENCES[p.suggestions.suggestionsEvery] ?: 0L) ?: inp.nowSec },
        ).minOrNull()
        id == ProcessingFeatures.DIRECT_PLAY -> inp.nowSec + 60
        else -> findNext   // per-title features are looked at with the next look
    }
}
