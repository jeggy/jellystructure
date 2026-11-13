package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.MusicSteps
import dev.jellystructure.config.ProcessingCarryOver
import dev.jellystructure.config.ProcessingConfig
import dev.jellystructure.config.ProcessingFeatures
import dev.jellystructure.config.RecommendationsStep
import dev.jellystructure.config.SuggestionsStep
import dev.jellystructure.config.featureOn
import dev.jellystructure.config.isWholeLibraryStep
import dev.jellystructure.config.partOn
import dev.jellystructure.config.processingOrDerived
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
    /**
     * FR-3.3 — the whole-library features that are due on their own interval: Music and Audiobooks by `interval`, measured from
     * their last run (or never run), Recommendations & suggestions when a part's own cadence has passed since it was built.
     * A feature that is off is never due, and a part that cannot run (no Seerr) is not counted.
     */
    fun dueFeatures(cfg: AppConfig, nowSec: Long, lastRan: (String) -> Long?, lastBuilt: (String) -> Long?): Set<String> {
        val p = cfg.processingOrDerived()
        val zone = dev.jellystructure.cron.ScheduleZone.resolve(cfg.general.timezone)
        val due = mutableSetOf<String>()
        // DEB-4387 (FR-2.3, FR-5) — one rule for all four: due when the schedule's first fire after the last run has passed, and
        // fires missed in a row are one run. A blank schedule is *only by hand*; a feature never run is due.
        fun scheduleDue(id: String, last: Long?): Boolean = scheduleOf(p, id, zone, last)
            ?.let { dev.jellystructure.cron.ScheduleEvaluator.isDue(it, zone, last, nowSec) } == true
        if (p.music.enabled && scheduleDue(ProcessingFeatures.MUSIC, lastRan(ProcessingFeatures.MUSIC))) due += ProcessingFeatures.MUSIC
        if (p.audiobooks.enabled && scheduleDue(ProcessingFeatures.AUDIOBOOKS, lastRan(ProcessingFeatures.AUDIOBOOKS))) due += ProcessingFeatures.AUDIOBOOKS
        if (p.suggestions.enabled) {
            if (p.partOn(RecommendationsStep.STEP) && scheduleDue(RecommendationsStep.STEP, lastBuilt(RecommendationsStep.STEP))) due += ProcessingFeatures.SUGGESTIONS
            if (p.partOn(SuggestionsStep.STEP) && seerrConnected(cfg) && scheduleDue(SuggestionsStep.STEP, lastBuilt(SuggestionsStep.STEP))) due += ProcessingFeatures.SUGGESTIONS
        }
        return due
    }

    /** The parsed schedule a feature or Suggestions part runs on, or null for *only by hand* (blank) or one that does not parse. */
    fun scheduleOf(p: ProcessingConfig, id: String, zone: kotlinx.datetime.TimeZone, last: Long?): dev.jellystructure.cron.Schedule? =
        (dev.jellystructure.cron.parseSchedule(ProcessingCarryOver.effective(p, id, zone, last)) as? dev.jellystructure.cron.ScheduleParse.Ok)?.schedule

    /**
     * DEB-4387 (FR-2.3) — whether a Suggestions part (a step id) is due, for the step itself: a run that took it checks again, so
     * a Find run that fires between two of the part's own fires does not rebuild it early. A run started by hand is always due.
     */
    fun partDue(cfg: AppConfig, step: String, last: Long?, nowSec: Long, byHand: Boolean): Boolean {
        if (byHand || last == null) return true
        val zone = dev.jellystructure.cron.ScheduleZone.resolve(cfg.general.timezone)
        val s = scheduleOf(cfg.processingOrDerived(), step, zone, last) ?: return false
        return dev.jellystructure.cron.ScheduleEvaluator.isDue(s, zone, last, nowSec)
    }

    /** A feature's schedule in words (the shared sentence), or *Only when you scan* for a blank one. */
    fun describeFeature(p: ProcessingConfig, id: String, zone: kotlinx.datetime.TimeZone, last: Long?): String =
        describeSchedule(ProcessingCarryOver.effective(p, id, zone, last))

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

    /** The Find schedule in words (FR-3.2), from the one schedule type: the shapes the picker writes are named, anything else is *Custom* with its text. */
    fun describeSchedule(text: String): String = when (val p = dev.jellystructure.cron.parseSchedule(text)) {
        dev.jellystructure.cron.ScheduleParse.Off -> dev.jellystructure.cron.ScheduleWords.OFF
        is dev.jellystructure.cron.ScheduleParse.Ok -> p.schedule.sentence
        is dev.jellystructure.cron.ScheduleParse.Invalid -> "Custom · ${text.trim()}"
    }

    /** Epoch seconds of the next fire of [schedule] in [zone], or null for a blank or unusable one. */
    fun nextFind(schedule: String, nowSec: Long, zone: kotlinx.datetime.TimeZone): Long? {
        val parsed = (dev.jellystructure.cron.parseSchedule(schedule) as? dev.jellystructure.cron.ScheduleParse.Ok)?.schedule ?: return null
        return dev.jellystructure.cron.ScheduleEvaluator.nextFire(parsed, zone, nowSec)
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
data class ProcessingView(
    val features: List<ProcessingFeatureView>,
    val holdingForPlayback: Boolean = false,
    /** FR-4.2 — which device is playing while *Wait while someone is watching* holds, as one sentence; null when nothing is being held. */
    val holdingText: String? = null,
)

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
    /** The display names of the devices playing right now (FR-4.2); the card names them while the household switch holds. */
    val playingDevices: List<String> = emptyList(),
)

object ProcessingViews {
    /** FR-7.3 / FR-8.3 — which queued job types belong to which feature. */
    val JOB_TYPES: Map<String, Set<String>> = mapOf(
        ProcessingFeatures.SEGMENTS to setOf("segments_movie", "segments_season", "segments_episodes", "waveform_unit", "waveform_backfill"),
        ProcessingFeatures.HEALTH to setOf(FileCheckSchedule.VERIFY_JOB, FileCheckSchedule.LENGTHS_JOB, FileCheckSchedule.AVSYNC_JOB),
        ProcessingFeatures.SUBTITLES to setOf("prewarm_subtitles", FileCheckSchedule.SUBTITLES_JOB),
        ProcessingFeatures.DIRECT_PLAY to setOf(MediaJobQueue.FILE_FIX_JOB),
    )

    /** A part whose own switch on Settings ▸ Processing is off. The Scan dialog links to that row when it sees this reason. */
    const val PART_OFF = "Off in Settings."

    fun build(cfg: AppConfig, inp: ProcessingInputs): ProcessingView {
        val p = cfg.processingOrDerived()
        val findNext = ProcessingSchedule.nextFind(p.find.schedule, inp.nowSec, dev.jellystructure.cron.ScheduleZone.resolve(cfg.general.timezone))
        val features = ProcessingFeatures.ALL.map { f ->
            val on = if (f.id == ProcessingFeatures.DIRECT_PLAY) inp.directPlay.any { it.second } else p.featureOn(f.id)
            val locked = f.id == ProcessingFeatures.FIND
            val unmapped = when (f.id) {
                ProcessingFeatures.MUSIC -> !inp.musicLibraries
                ProcessingFeatures.AUDIOBOOKS -> !inp.audiobookLibraries
                else -> false
            }
            val parts = if (f.id == ProcessingFeatures.DIRECT_PLAY) {
                inp.directPlay.map { (name, enabled) -> ProcessingPartView(name, name, enabled, canRun = enabled, reason = if (enabled) null else PART_OFF) }
            } else f.parts.map { part ->
                val partOn = p.partOn(part.step)
                val blocker = if (on) blockerOf(cfg, p, part.step) else null
                ProcessingPartView(
                    part.step, part.name, partOn,
                    canRun = on && partOn && blocker == null && !unmapped,
                    reason = when {
                        !on -> "Off in Settings."
                        !partOn -> PART_OFF
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
                interval = intervalWords(cfg, p, f.id, inp),
                lastRanAt = lastAt, lastRan = ProcessingSchedule.agoText(lastAt, inp.nowSec),
                nextAt = nextAt, next = if (!locked && !on) "Off" else ProcessingSchedule.inText(nextAt, inp.nowSec),
                waiting = waiting,
                waitingText = if (JOB_TYPES[f.id] == null) "" else if (waiting == 0) "Nothing waiting" else "$waiting waiting",
                parts = parts,
            )
        }
        val holding = cfg.scan.deferWhilePlaying && inp.playingDevices.isNotEmpty()
        return ProcessingView(features, holdingForPlayback = inp.deferred, holdingText = if (holding) holdingLine(inp.playingDevices) else null)
    }

    /** FR-4.2 — "Living room is playing, so …": the heavy work waits, and picks up by itself when playback stops. */
    internal fun holdingLine(devices: List<String>): String {
        val who = when (devices.size) {
            1 -> "${devices[0]} is"
            2 -> "${devices[0]} and ${devices[1]} are"
            else -> devices.dropLast(1).joinToString(", ") + " and ${devices.last()} are"
        }
        return "$who playing, so the heavy work is paused. It picks up by itself when playback stops."
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

    private fun intervalWords(cfg: AppConfig, p: ProcessingConfig, id: String, inp: ProcessingInputs): String = when (id) {
        ProcessingFeatures.FIND -> ProcessingSchedule.describeSchedule(p.find.schedule)
        ProcessingFeatures.METADATA -> if (p.metadata.tiers.recheckUnchanged)
            "Unchanged titles by release age: ${tier(p.metadata.tiers.thisYear)} · ${tier(p.metadata.tiers.oneToFiveYears)} · ${tier(p.metadata.tiers.older)}"
        else "With every look"
        ProcessingFeatures.SEGMENTS, ProcessingFeatures.HEALTH, ProcessingFeatures.SUBTITLES -> "Queued with every look, what is due"
        ProcessingFeatures.SUGGESTIONS -> {
            val zone = dev.jellystructure.cron.ScheduleZone.resolve(cfg.general.timezone)
            "Recommendations: ${ProcessingSchedule.describeFeature(p, RecommendationsStep.STEP, zone, inp.lastBuilt[RecommendationsStep.STEP])} · " +
                "suggestions: ${ProcessingSchedule.describeFeature(p, SuggestionsStep.STEP, zone, inp.lastBuilt[SuggestionsStep.STEP])}"
        }
        ProcessingFeatures.MUSIC, ProcessingFeatures.AUDIOBOOKS ->
            ProcessingSchedule.describeFeature(p, id, dev.jellystructure.cron.ScheduleZone.resolve(cfg.general.timezone), inp.lastRan[id])
        else -> "Every minute, for the kinds that are on"
    }

    private fun tier(w: String) = when (w) {
        "daily" -> "daily"; "weekly" -> "weekly"; "monthly" -> "monthly"; "6months" -> "every 6 months"; "yearly" -> "yearly"
        "2years" -> "every 2 years"; "5years" -> "every 5 years"; "never" -> "never"
        // DEB-4387 (FR-5.2) — an age no word says: `10years` is "every 10 years", `1days` never reaches here (it is written `daily`).
        else -> dev.jellystructure.cron.ReleaseAge.parse(w)?.let { "every ${it.n} ${if (it.n == 1) it.unit.key.removeSuffix("s") else it.unit.key}" } ?: w
    }

    private fun nextOf(cfg: AppConfig, p: ProcessingConfig, id: String, on: Boolean, lastAt: Long?, findNext: Long?, inp: ProcessingInputs): Long? = when {
        id == ProcessingFeatures.FIND -> findNext
        !on -> null
        // DEB-4387 (FR-6.3) — every next run comes from the one evaluator: now when due already, else the first fire after the last run.
        id == ProcessingFeatures.MUSIC || id == ProcessingFeatures.AUDIOBOOKS -> nextRun(p, id, cfg, lastAt, inp.nowSec)
        id == ProcessingFeatures.SUGGESTIONS -> listOfNotNull(
            RecommendationsStep.STEP.takeIf { p.partOn(it) }?.let { nextRun(p, it, cfg, inp.lastBuilt[it], inp.nowSec) },
            SuggestionsStep.STEP.takeIf { p.partOn(it) && ProcessingSchedule.seerrConnected(cfg) }?.let { nextRun(p, it, cfg, inp.lastBuilt[it], inp.nowSec) },
        ).minOrNull()
        id == ProcessingFeatures.DIRECT_PLAY -> inp.nowSec + 60
        else -> findNext   // per-title features are looked at with the next look
    }

    private fun nextRun(p: ProcessingConfig, id: String, cfg: AppConfig, last: Long?, nowSec: Long): Long? {
        val zone = dev.jellystructure.cron.ScheduleZone.resolve(cfg.general.timezone)
        return ProcessingSchedule.scheduleOf(p, id, zone, last)?.let { dev.jellystructure.cron.ScheduleEvaluator.nextDue(it, zone, last, nowSec) }
    }
}
