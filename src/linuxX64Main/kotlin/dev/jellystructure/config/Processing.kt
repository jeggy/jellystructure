package dev.jellystructure.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DEB-1664 (processing-runs-as-nine-features.md) — the nine features, their parts, and how today's `[[scan.pipeline]]`
// becomes them. The order of the work is a dependency graph in this file (FR-1.2), never a setting; the engine still
// runs a list of [PipelineStep]s, and [ProcessingConfig.derivedPipeline] is that list, so the step functions are untouched.

/**
 * FR-2.1 — the `[processing]` section: per feature its `enabled`, its interval, the on/off of each part and the feature's
 * own options. Options that live elsewhere keep their keys (FR-2.2) and are not repeated here. Find new & changed has no
 * `enabled` key (it cannot be switched off); "Make files play directly" has none either: its switches are database rows.
 */
@Serializable
data class ProcessingConfig(
    val find: FindProcessing = FindProcessing(),
    val metadata: MetadataProcessing = MetadataProcessing(),
    val segments: SegmentsProcessing = SegmentsProcessing(),
    val health: HealthProcessing = HealthProcessing(),
    val subtitles: SubtitlesProcessing = SubtitlesProcessing(),
    val suggestions: SuggestionsProcessing = SuggestionsProcessing(),
    val music: MusicProcessing = MusicProcessing(),
    val audiobooks: AudiobooksProcessing = AudiobooksProcessing(),
)

/** One part's "re-check unchanged titles by release age" rule — the four fields a step carries today (FR-2.3). */
@Serializable
data class AgeTiers(
    @SerialName("recheck_unchanged") val recheckUnchanged: Boolean = false,
    @SerialName("this_year") val thisYear: String = "weekly",
    @SerialName("one_to_five_years") val oneToFiveYears: String = "monthly",
    val older: String = "6months",
)

/** Feature 1. [schedule] is a cron (phase 166's, validated before it can be saved); blank = no automatic look. */
@Serializable
data class FindProcessing(
    val schedule: String = ProcessingFeatures.DEFAULT_FIND_SCHEDULE,
)

/** Feature 2. The release-age tiers decide which unchanged titles a look lets back in (FR-3.3). */
@Serializable
data class MetadataProcessing(
    val enabled: Boolean = true,
    val tiers: AgeTiers = AgeTiers(recheckUnchanged = true),
    val tmdb: Boolean = true,
    @SerialName("tmdb_scope") val tmdbScope: String = "all",
    val artwork: Boolean = true,
    @SerialName("artwork_scope") val artworkScope: String = "missing",
    val imdb: Boolean = true,
    val nfo: Boolean = true,
    @SerialName("nfo_overwrite") val nfoOverwrite: Boolean = false,
    val drift: Boolean = true,
    @SerialName("auto_reassert") val autoReassert: Boolean = false,
    @SerialName("rescan_arr") val rescanArr: Boolean = true,
)

/** Feature 3. Its options are kept even while it is off: the code reads them whether the step is on or not. */
@Serializable
data class SegmentsProcessing(
    val enabled: Boolean = false,
    val scope: String = "missing",
    @SerialName("detect_fingerprint") val detectFingerprint: Boolean = false,
    @SerialName("trust_stinger_tags") val trustStingerTags: Boolean = true,
    @SerialName("chapter_keywords") val chapterKeywords: List<String> = emptyList(),
)

/** Feature 4. FR-261-6's defaults: 5 y / 5 y / 5 y to read files, 1 y / 2 y / 5 y for track lengths, sound in step once per file. */
@Serializable
data class HealthProcessing(
    val enabled: Boolean = true,
    val verify: Boolean = true,
    @SerialName("verify_tiers") val verifyTiers: AgeTiers = FileCheckSteps.defaultStep(FileCheckSteps.VERIFY).tiers(),
    val lengths: Boolean = true,
    @SerialName("lengths_tiers") val lengthsTiers: AgeTiers = FileCheckSteps.defaultStep(FileCheckSteps.LENGTHS).tiers(),
    @SerialName("av_sync") val avSync: Boolean = true,
    @SerialName("av_sync_tiers") val avSyncTiers: AgeTiers = FileCheckSteps.defaultStep(FileCheckSteps.AVSYNC).tiers(),
)

/** Feature 5. Check fit weekly (FR-2.6). *When a subtitle does not fit* stays `[subtitle_check] action` (FR-2.2). */
@Serializable
data class SubtitlesProcessing(
    val enabled: Boolean = true,
    val prewarm: Boolean = true,
    @SerialName("check_fit") val checkFit: Boolean = true,
    @SerialName("check_fit_tiers") val checkFitTiers: AgeTiers = FileCheckSteps.defaultStep(FileCheckSteps.SUBTITLES).tiers(),
)

/** Feature 6. Each part rebuilds `daily` or `weekly`. */
@Serializable
data class SuggestionsProcessing(
    val enabled: Boolean = true,
    val recommendations: Boolean = true,
    @SerialName("recommendations_every") val recommendationsEvery: String = RecommendationsStep.DEFAULT_CADENCE,
    val suggestions: Boolean = true,
    @SerialName("suggestions_every") val suggestionsEvery: String = SuggestionsStep.DEFAULT_CADENCE,
)

/** Feature 7. [interval] is `hourly` | `daily` | `weekly`; the provider switches stay on `[musicbrainz]` and `[music]` (FR-2.2). */
@Serializable
data class MusicProcessing(
    val enabled: Boolean = true,
    val interval: String = ProcessingFeatures.DAILY,
    val read: Boolean = true,
    val match: Boolean = true,
    @SerialName("match_scope") val matchScope: String = "missing",
    val compare: Boolean = true,
    val artwork: Boolean = true,
    @SerialName("artwork_scope") val artworkScope: String = "missing",
    val lyrics: Boolean = true,
    @SerialName("lyrics_scope") val lyricsScope: String = "missing",
    val nfo: Boolean = true,
    val tags: Boolean = true,
)

/** Feature 8. */
@Serializable
data class AudiobooksProcessing(
    val enabled: Boolean = true,
    val interval: String = ProcessingFeatures.DAILY,
)

private fun PipelineStep.tiers() = AgeTiers(recheckUnchanged, refreshThisYear, refresh1To5y, refreshOlder)

/** What a part is, as the registry knows it. [step] is the engine's step id; it doubles as the part's id on the wire. */
class ProcessingPart(val step: String, val name: String)

class ProcessingFeature(val id: String, val name: String, val queuesWork: Boolean, val parts: List<ProcessingPart>) {
    /** FR-5.2 — *queues work* when every part of it queues work; a feature with no step of its own (Make files play directly) has none. */
    val partIds: List<String> get() = parts.map { it.step }
}

/** FR-1 — the nine features, the one list every surface reads. Step ids appear here so the engine can be mapped; they never reach a screen (FR-13). */
object ProcessingFeatures {
    const val FIND = "find"
    const val METADATA = "metadata"
    const val SEGMENTS = "segments"
    const val HEALTH = "health"
    const val SUBTITLES = "subtitles"
    const val SUGGESTIONS = "suggestions"
    const val MUSIC = "music"
    const val AUDIOBOOKS = "audiobooks"
    const val DIRECT_PLAY = "direct_play"

    const val HOURLY = "hourly"
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    val INTERVAL_SECONDS = mapOf(HOURLY to 3_600L, DAILY to 86_400L, WEEKLY to 7 * 86_400L)

    /** FR-2.6 — every hour. */
    const val DEFAULT_FIND_SCHEDULE = "0 */1 * * *"

    /** FR-1.3 — not a part: it runs whenever NFO files are on, and there is no switch. */
    const val TELL_JELLYFIN = "sync_jellyfin"

    val ALL: List<ProcessingFeature> = listOf(
        ProcessingFeature(FIND, "Find new & changed", queuesWork = false, parts = emptyList()),
        ProcessingFeature(METADATA, "Metadata & artwork", queuesWork = false, parts = listOf(
            ProcessingPart("pull_tmdb", "TMDB metadata"), ProcessingPart("fetch_artwork", "Artwork"),
            ProcessingPart("sync_imdb_ratings", "IMDb ratings"), ProcessingPart("write_nfo", "NFO files"),
            ProcessingPart("detect_drift", "Drift check"), ProcessingPart("rescan_arr", "Rescan in Radarr/Sonarr"),
        )),
        ProcessingFeature(SEGMENTS, "Intro & credits", queuesWork = true, parts = listOf(ProcessingPart("detect_segments", "Intro & credits"))),
        ProcessingFeature(HEALTH, "File health", queuesWork = true, parts = listOf(
            ProcessingPart(FileCheckSteps.VERIFY, "Read files end to end"), ProcessingPart(FileCheckSteps.LENGTHS, "Track lengths"),
            ProcessingPart(FileCheckSteps.AVSYNC, "Sound in step with the picture"),
        )),
        ProcessingFeature(SUBTITLES, "Subtitles", queuesWork = true, parts = listOf(
            ProcessingPart("prewarm_subtitles", "Pre-warm"), ProcessingPart(FileCheckSteps.SUBTITLES, "Check fit"),
        )),
        ProcessingFeature(SUGGESTIONS, "Recommendations & suggestions", queuesWork = false, parts = listOf(
            ProcessingPart(RecommendationsStep.STEP, "Recommendations"), ProcessingPart(SuggestionsStep.STEP, "Suggestions"),
        )),
        ProcessingFeature(MUSIC, "Music", queuesWork = false, parts = listOf(
            ProcessingPart(MusicSteps.SCAN, "Read the music library"), ProcessingPart(MusicSteps.MATCH, "Match on the music database"),
            ProcessingPart(MusicSteps.COMPARE, "Songs held twice"), ProcessingPart(MusicSteps.ARTWORK, "Covers & pictures"),
            ProcessingPart(MusicSteps.LYRICS, "Lyrics"), ProcessingPart(MusicSteps.NFO, "Album/artist NFO files"),
            ProcessingPart(MusicSteps.TAGS, "Write tags"),
        )),
        ProcessingFeature(AUDIOBOOKS, "Audiobooks", queuesWork = false, parts = listOf(ProcessingPart(MusicSteps.AUDIOBOOKS, "Read the audiobook library"))),
        // Its one part is the file-fix kinds and their tick, which are not engine steps and live in the database (FR-2.2).
        ProcessingFeature(DIRECT_PLAY, "Make files play directly", queuesWork = true, parts = emptyList()),
    )

    fun feature(id: String): ProcessingFeature? = ALL.firstOrNull { it.id == id }

    /** The feature a pipeline step belongs to; `scan_files` is feature 1, `sync_jellyfin` feature 2 (it is Metadata & artwork's, without being a part). */
    fun featureOfStep(step: String): String? = when (step) {
        "scan_files" -> FIND
        TELL_JELLYFIN -> METADATA
        else -> ALL.firstOrNull { f -> f.parts.any { it.step == step } }?.id
    }

    /** Every engine step the features know, in the order the features list them (the tie-break for [ORDER]). */
    private val LISTED: List<String> =
        listOf("scan_files") + ALL.flatMap { f -> f.parts.map { it.step } + if (f.id == METADATA) listOf(TELL_JELLYFIN) else emptyList() }

    /** FR-1.2 — the only orderings that matter: `step` runs after every step listed for it. Everything else is independent. */
    val AFTER: Map<String, List<String>> = mapOf(
        "pull_tmdb" to listOf("scan_files"),
        "fetch_artwork" to listOf("pull_tmdb"),
        "write_nfo" to listOf("pull_tmdb"),
        RecommendationsStep.STEP to listOf("pull_tmdb"),
        TELL_JELLYFIN to listOf("write_nfo"),
        "rescan_arr" to listOf("write_nfo", TELL_JELLYFIN),   // FR-1.2: it now always runs after the NFO files
        FileCheckSteps.SUBTITLES to listOf("prewarm_subtitles"),
        MusicSteps.MATCH to listOf(MusicSteps.SCAN),
        MusicSteps.ARTWORK to listOf(MusicSteps.MATCH),
        MusicSteps.LYRICS to listOf(MusicSteps.MATCH),
        MusicSteps.NFO to listOf(MusicSteps.MATCH),
        MusicSteps.TAGS to listOf(MusicSteps.MATCH),
    ).let { edges ->
        // "Find new & changed runs first": every other step waits for discovery.
        (LISTED - "scan_files").associateWith { (listOf("scan_files") + (edges[it] ?: emptyList())).distinct() }
    }

    /** The one run order (a stable topological sort of [LISTED] over [AFTER]): the listed order wherever the graph is silent. */
    val ORDER: List<String> = run {
        val placed = mutableListOf<String>()
        val rest = LISTED.toMutableList()
        while (rest.isNotEmpty()) {
            val next = rest.first { s -> (AFTER[s] ?: emptyList()).all { it in placed } }
            placed += next
            rest -= next
        }
        placed
    }

    /** Every step id the features know plus the two the configurator allowed and FR-14 removes. */
    private val RETIRED = setOf("wait", "notify")
    fun knows(step: String): Boolean = step in LISTED || step in RETIRED

    /** FR-9.2 — the old `skipSteps` ids mapped to parts. An id that is not a part (a retired step, `sync_jellyfin`, `scan_files`, junk) is ignored, never an error. */
    fun skippableParts(raw: Set<String>): Set<String> = raw.filterTo(mutableSetOf()) { id -> ALL.any { f -> id in f.partIds } }

    fun ignoredSkips(raw: Set<String>): Set<String> = raw - skippableParts(raw)
}

/** The whole-library steps — the ones a feature's own interval gates (FR-3.3), as opposed to the per-title ones the freshness filter gates. */
internal fun isWholeLibraryStep(step: String): Boolean = WholeLibrarySteps.contains(step) || step == MusicSteps.AUDIOBOOKS

/** Seconds a feature's `hourly | daily | weekly` interval means; an unknown word is daily. */
internal fun intervalSeconds(word: String): Long = ProcessingFeatures.INTERVAL_SECONDS[word] ?: ProcessingFeatures.INTERVAL_SECONDS.getValue(ProcessingFeatures.DAILY)

/**
 * FR-1.4 — which parts are on, as the saved configuration says. A part of a feature that is off is off, and Find new &
 * changed's `scan_files` is always on. A dependent switch elsewhere (tag writing, Seerr, …) is NOT applied here: the part
 * stays in the list, and [dev.jellystructure.media.effectivePipeline] / the step itself decides whether it can run.
 */
fun ProcessingConfig.partOn(step: String): Boolean = when (step) {
    "scan_files" -> true
    "pull_tmdb" -> metadata.enabled && metadata.tmdb
    "fetch_artwork" -> metadata.enabled && metadata.artwork
    "sync_imdb_ratings" -> metadata.enabled && metadata.imdb
    "write_nfo" -> metadata.enabled && metadata.nfo
    ProcessingFeatures.TELL_JELLYFIN -> metadata.enabled && metadata.nfo   // FR-1.3 — no switch of its own
    "detect_drift" -> metadata.enabled && metadata.drift
    "rescan_arr" -> metadata.enabled && metadata.rescanArr
    "detect_segments" -> segments.enabled
    FileCheckSteps.VERIFY -> health.enabled && health.verify
    FileCheckSteps.LENGTHS -> health.enabled && health.lengths
    FileCheckSteps.AVSYNC -> health.enabled && health.avSync
    "prewarm_subtitles" -> subtitles.enabled && subtitles.prewarm
    FileCheckSteps.SUBTITLES -> subtitles.enabled && subtitles.checkFit
    RecommendationsStep.STEP -> suggestions.enabled && suggestions.recommendations
    SuggestionsStep.STEP -> suggestions.enabled && suggestions.suggestions
    MusicSteps.SCAN -> music.enabled && music.read
    MusicSteps.MATCH -> music.enabled && music.match
    MusicSteps.COMPARE -> music.enabled && music.compare
    MusicSteps.ARTWORK -> music.enabled && music.artwork
    MusicSteps.LYRICS -> music.enabled && music.lyrics
    MusicSteps.NFO -> music.enabled && music.nfo
    MusicSteps.TAGS -> music.enabled && music.tags
    MusicSteps.AUDIOBOOKS -> audiobooks.enabled
    else -> false
}

fun ProcessingConfig.featureOn(id: String): Boolean = when (id) {
    ProcessingFeatures.FIND -> true
    ProcessingFeatures.METADATA -> metadata.enabled
    ProcessingFeatures.SEGMENTS -> segments.enabled
    ProcessingFeatures.HEALTH -> health.enabled
    ProcessingFeatures.SUBTITLES -> subtitles.enabled
    ProcessingFeatures.SUGGESTIONS -> suggestions.enabled
    ProcessingFeatures.MUSIC -> music.enabled
    ProcessingFeatures.AUDIOBOOKS -> audiobooks.enabled
    else -> false
}

/**
 * The engine's step list for these switches (FR-1.2): every step the features know, in the one dependency order, each
 * carrying its options — `enabled` says whether it runs. Disabled steps are kept so the options survive a round trip and
 * a rolled-back image finds the same operator choices (FR-2.4).
 */
fun ProcessingConfig.derivedPipeline(): List<PipelineStep> = ProcessingFeatures.ORDER.map { step ->
    val on = partOn(step)
    when (step) {
        "scan_files" -> PipelineStep(step = step, enabled = true, recheckUnchanged = metadata.tiers.recheckUnchanged,
            refreshThisYear = metadata.tiers.thisYear, refresh1To5y = metadata.tiers.oneToFiveYears, refreshOlder = metadata.tiers.older)
        "pull_tmdb" -> PipelineStep(step = step, enabled = on, scope = metadata.tmdbScope)
        "fetch_artwork" -> PipelineStep(step = step, enabled = on, scope = metadata.artworkScope)
        "write_nfo" -> PipelineStep(step = step, enabled = on, overwrite = metadata.nfoOverwrite)
        "detect_drift" -> PipelineStep(step = step, enabled = on, autoReassert = metadata.autoReassert)
        "detect_segments" -> PipelineStep(step = step, enabled = on, scope = segments.scope, detectFingerprint = segments.detectFingerprint,
            trustStingerTags = segments.trustStingerTags, chapterKeywords = segments.chapterKeywords)
        FileCheckSteps.VERIFY -> health.verifyTiers.toStep(step, on)
        FileCheckSteps.LENGTHS -> health.lengthsTiers.toStep(step, on)
        FileCheckSteps.AVSYNC -> health.avSyncTiers.toStep(step, on)
        FileCheckSteps.SUBTITLES -> subtitles.checkFitTiers.toStep(step, on)
        RecommendationsStep.STEP -> PipelineStep(step = step, enabled = on, rebuildEvery = suggestions.recommendationsEvery)
        SuggestionsStep.STEP -> PipelineStep(step = step, enabled = on, rebuildEvery = suggestions.suggestionsEvery)
        MusicSteps.MATCH -> PipelineStep(step = step, enabled = on, scope = music.matchScope)
        MusicSteps.ARTWORK -> PipelineStep(step = step, enabled = on, scope = music.artworkScope)
        MusicSteps.LYRICS -> PipelineStep(step = step, enabled = on, scope = music.lyricsScope)
        else -> PipelineStep(step = step, enabled = on)
    }
}

private fun AgeTiers.toStep(step: String, on: Boolean) =
    PipelineStep(step = step, enabled = on, recheckUnchanged = recheckUnchanged, refreshThisYear = thisYear, refresh1To5y = oneToFiveYears, refreshOlder = older)

object ProcessingCarryOver {
    /**
     * FR-2.3 — `[processing]` from a saved `[[scan.pipeline]]` and the schedule. A feature is on when any of its parts is an
     * enabled step; per-step options carry over unchanged; Intro & credits' options are carried even from a disabled step.
     * No saved pipeline at all gives the fresh-install parts of FR-2.6 (not the engine's old built-in list). [keep] supplies
     * what a pipeline cannot say (the music and audiobook intervals) when this is a re-derivation rather than the first one.
     * An unknown step id is dropped ([unknownSteps] names them so the caller can log them once).
     */
    fun derive(cfg: AppConfig, keep: ProcessingConfig? = null): ProcessingConfig {
        val pipe = cfg.scan.pipeline
        val schedule = cfg.scanSchedule.ifBlank {
            cfg.behavior.scanIntervalHours.takeIf { it > 0 }?.let { "0 */${it.coerceAtMost(23)} * * *" } ?: ""
        }
        if (pipe.isEmpty()) {
            val fresh = ProcessingConfig()
            return fresh.copy(
                find = FindProcessing(schedule),
                metadata = fresh.metadata.copy(artwork = cfg.behavior.fetchImages),
            )
        }
        fun step(id: String) = pipe.firstOrNull { it.step == id }
        fun on(id: String) = step(id)?.enabled == true
        fun tiers(id: String): AgeTiers {
            val s = step(id) ?: FileCheckSteps.defaultStep(id)
            return AgeTiers(s.recheckUnchanged, s.refreshThisYear, s.refresh1To5y, s.refreshOlder)
        }
        val scan = step("scan_files")?.let { AgeTiers(it.recheckUnchanged, it.refreshThisYear, it.refresh1To5y, it.refreshOlder) } ?: AgeTiers()
        val seg = step("detect_segments") ?: PipelineStep(step = "detect_segments")
        val meta = listOf("pull_tmdb", "fetch_artwork", "sync_imdb_ratings", "write_nfo", "detect_drift", "rescan_arr").map(::on)
        val health = listOf(FileCheckSteps.VERIFY, FileCheckSteps.LENGTHS, FileCheckSteps.AVSYNC).map(::on)
        val subs = listOf("prewarm_subtitles", FileCheckSteps.SUBTITLES).map(::on)
        val recs = on(RecommendationsStep.STEP); val sugg = on(SuggestionsStep.STEP)
        val musicParts = listOf(MusicSteps.SCAN, MusicSteps.MATCH, MusicSteps.COMPARE, MusicSteps.ARTWORK, MusicSteps.LYRICS, MusicSteps.NFO, MusicSteps.TAGS).map(::on)
        // The pipeline ran its whole-library steps with every run: carrying "hourly" keeps the work an hourly run did.
        val everyRun = ProcessingFeatures.HOURLY
        return ProcessingConfig(
            find = FindProcessing(schedule),
            metadata = MetadataProcessing(
                enabled = meta.any { it }, tiers = scan,
                tmdb = meta[0], tmdbScope = step("pull_tmdb")?.scope ?: "all",
                artwork = meta[1], artworkScope = step("fetch_artwork")?.scope ?: "missing",
                imdb = meta[2], nfo = meta[3], nfoOverwrite = step("write_nfo")?.overwrite ?: false,
                drift = meta[4], autoReassert = step("detect_drift")?.autoReassert ?: false, rescanArr = meta[5],
            ),
            segments = SegmentsProcessing(
                enabled = seg.enabled && step("detect_segments") != null, scope = seg.scope, detectFingerprint = seg.detectFingerprint,
                trustStingerTags = seg.trustStingerTags, chapterKeywords = seg.chapterKeywords,
            ),
            health = HealthProcessing(
                enabled = health.any { it }, verify = health[0], verifyTiers = tiers(FileCheckSteps.VERIFY),
                lengths = health[1], lengthsTiers = tiers(FileCheckSteps.LENGTHS), avSync = health[2], avSyncTiers = tiers(FileCheckSteps.AVSYNC),
            ),
            subtitles = SubtitlesProcessing(enabled = subs.any { it }, prewarm = subs[0], checkFit = subs[1], checkFitTiers = tiers(FileCheckSteps.SUBTITLES)),
            suggestions = SuggestionsProcessing(
                enabled = recs || sugg, recommendations = recs, recommendationsEvery = step(RecommendationsStep.STEP)?.rebuildEvery ?: RecommendationsStep.DEFAULT_CADENCE,
                suggestions = sugg, suggestionsEvery = step(SuggestionsStep.STEP)?.rebuildEvery ?: SuggestionsStep.DEFAULT_CADENCE,
            ),
            music = MusicProcessing(
                enabled = musicParts.any { it }, interval = keep?.music?.interval ?: everyRun,
                read = musicParts[0], match = musicParts[1], matchScope = step(MusicSteps.MATCH)?.scope ?: "missing",
                compare = musicParts[2], artwork = musicParts[3], artworkScope = step(MusicSteps.ARTWORK)?.scope ?: "missing",
                lyrics = musicParts[4], lyricsScope = step(MusicSteps.LYRICS)?.scope ?: "missing", nfo = musicParts[5], tags = musicParts[6],
            ),
            audiobooks = AudiobooksProcessing(enabled = on(MusicSteps.AUDIOBOOKS), interval = keep?.audiobooks?.interval ?: everyRun),
        )
    }

    /** The step ids in the saved pipeline that are none of the features' (FR-2.3: logged once, then dropped). */
    fun unknownSteps(cfg: AppConfig): List<String> = cfg.scan.pipeline.map { it.step }.distinct().filterNot { ProcessingFeatures.knows(it) }

    /** FR-14.3 — a saved pipeline whose `notify` was on turns *Scan finished* on. */
    fun notifyWasOn(cfg: AppConfig): Boolean = cfg.scan.pipeline.any { it.step == "notify" && it.enabled }

    /**
     * Brings [cfg] to the shape every writer and reader can rely on: `[processing]` present (carried over once from the legacy
     * pipeline), and — for one release (FR-2.4) — `[[scan.pipeline]]` and `scan_schedule` derived from it, so a rolled-back image
     * finds a pipeline that does what the features say. The seed markers are set so that image does not seed back what a
     * feature turned off. Idempotent.
     */
    fun settle(cfg: AppConfig): AppConfig {
        val first = cfg.processing == null
        val processing = cfg.processing ?: derive(cfg)
        var out = cfg.copy(processing = processing)
        if (first && notifyWasOn(cfg) && !cfg.behavior.notifyOnScanDone) out = out.copy(behavior = out.behavior.copy(notifyOnScanDone = true))
        return out.copy(
            scanSchedule = processing.find.schedule,
            scan = out.scan.copy(
                pipeline = processing.derivedPipeline(),
                fileCheckStepsSeeded = true, recommendationsStepSeeded = true, suggestionsStepSeeded = true, subtitleCheckSeeded = true,
                musicStepsSeeded = MusicSteps.ALL, avSyncStepSeeded = true,
            ),
        )
    }

    /**
     * `PUT /api/config` replaces the whole config (FR-2.5). Until the Processing tab exists the Settings pipeline card edits
     * the derived `[[scan.pipeline]]` and `scan_schedule`, and sends `[processing]` back untouched: when only those moved, the
     * card's edit wins and `[processing]` is re-derived from it; when `[processing]` itself moved (or the sender has no pipeline
     * card), `[processing]` wins and the legacy fields are re-derived by [settle]. A sender that does not know `[processing]` at all
     * (an older admin) is the first case.
     */
    fun reconcile(stored: AppConfig, received: AppConfig): AppConfig {
        val sent = received.processing
        val legacyMoved = received.scan.pipeline != stored.scan.pipeline || received.scanSchedule != stored.scanSchedule
        val processingMoved = sent != null && sent != stored.processing
        return if ((sent == null || !processingMoved) && legacyMoved) {
            received.copy(processing = derive(received, keep = stored.processing ?: sent))
                .let { r -> if (notifyWasOn(received) && !stored.scan.pipeline.any { it.step == "notify" && it.enabled }) r.copy(behavior = r.behavior.copy(notifyOnScanDone = true)) else r }
        } else received.copy(processing = sent ?: stored.processing)
    }
}

/** The switches the engine reads: the saved `[processing]`, or — where an [AppConfig] never went through [ProcessingCarryOver.settle] (a test, a fresh object) — what the legacy fields would carry over to. */
fun AppConfig.processingOrDerived(): ProcessingConfig = processing ?: ProcessingCarryOver.derive(this)

/** Dependency-ordered steps for every feature, enabled or not — what `[[scan.pipeline]]` is derived to. */
fun AppConfig.processingSteps(): List<PipelineStep> = processingOrDerived().derivedPipeline()
