package dev.jellystructure.subtitles

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Subtitle_check
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.media.FileIntegrityService
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.SidecarSubtitleScanner
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.model.fileDurationMs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Phase 273 (§A) — a verdict on every text sidecar, from the strongest reference its video has (FR-273-2):
 * an embedded text subtitle, a sibling sidecar already judged in sync, or the speech track. A sidecar that does not
 * fit is compared with the neighbouring episodes to name the one it belongs to (FR-273-3). Verdicts are stored per
 * sidecar and are current while both files keep their size and mtime (FR-273-7).
 *
 * Two ways in: [checkVideo] with [Mode.INLINE] (the Bazarr hook, milliseconds when the references are stored; never
 * fetches or decodes, so it needs no deferral) and [Mode.JOB] (the `check_subtitles` file job on the segments lane,
 * which may fetch an embedded subtitle from Jellyfin or decode a speech track, and waits while a TV plays).
 */
class SubtitleCheckService(
    private val db: JellystructureDb,
    private val store: MediaStore,
    private val configStore: ConfigStore,
    val references: SubtitleReferences,
    private val mediaHistory: MediaHistory,
) {
    private val q get() = db.subtitleCheckQueries

    /** The Bazarr side (§C), set at startup; null means verdicts only. */
    var steering: SubtitleSteering? = null

    enum class Mode { INLINE, JOB }

    /** Where a sidecar came from, when Bazarr's hook said so (FR-273-9). */
    data class Source(val provider: String?, val subsId: String?)

    data class Outcome(val checked: Int, val needsJob: Boolean, val rows: List<Subtitle_check>, val changed: List<Subtitle_check>)

    data class Sidecar(val path: String, val language: String?, val forced: Boolean, val hi: Boolean, val size: Long, val mtime: Long)

    private val locksGuard = Mutex()
    private val locks = HashMap<String, Mutex>()
    private suspend fun lockFor(path: String): Mutex = locksGuard.withLock { locks.getOrPut(path) { Mutex() } }

    companion object {
        private val TEXT_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
        /** A `cant_tell` is looked at again after this long, or as soon as a reference newer than it exists. */
        const val RECHECK_UNSETTLED_SEC = 30L * 86_400
        var current: SubtitleCheckService? = null

        fun contentHash(bytes: ByteArray): String {
            var h = 0xcbf29ce484222325uL
            for (b in bytes) { h = h xor (b.toULong() and 0xFFuL); h *= 0x100000001b3uL }
            return h.toString(16).padStart(16, '0')
        }

        fun episodeLabel(season: Int?, episode: Int?): String? =
            if (season == null || episode == null) null
            else "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    }

    fun init() {
        current = this
        SubtitleVerdicts.load(q.allChecks().executeAsList())
    }

    /** The text sidecars beside a video on disk right now (the store may not have seen a download yet). Forced
     *  sidecars are left out: a signs-only track has too few cues to say anything about its timing. */
    fun sidecarsOf(videoPath: String): List<Sidecar> = SidecarSubtitleScanner.discover(videoPath, 0)
        .filter { it.codec in TEXT_EXTENSIONS && !it.forced && it.externalPath != null }
        .mapNotNull { t ->
            val p = t.externalPath!!
            val st = FileIntegrityService.stampOf(p) ?: return@mapNotNull null
            Sidecar(p, t.language, t.forced, t.sdh, st.size, st.mtime)
        }

    fun checksForItem(itemId: String): List<Subtitle_check> = q.checksForItem(itemId).executeAsList()

    /** FR-273-8 — a video is due when a sidecar has no current verdict, or an unsettled verdict could now be settled. */
    fun isDue(videoPath: String, force: Boolean = false): Boolean {
        val sidecars = sidecarsOf(videoPath)
        if (sidecars.isEmpty()) return q.checksForVideo(videoPath).executeAsList().isNotEmpty()  // clean up stale rows
        if (force) return true
        val video = FileIntegrityService.stampOf(videoPath) ?: return false
        val rows = q.checksForVideo(videoPath).executeAsList().associateBy { it.sidecar_path }
        val now = nowSec()
        for (s in sidecars) {
            val r = rows[s.path] ?: return true
            if (r.sidecar_size != s.size || r.sidecar_mtime != s.mtime || r.video_size != video.size || r.video_mtime != video.mtime) return true
            if (r.verdict == Verdict.CANT_TELL.wire && r.reason != CantTell.TOO_FEW_CUES) {
                if (now - r.checked_at > RECHECK_UNSETTLED_SEC) return true
                if (r.reason == CantTell.NO_REFERENCE && hasUnknownReference(videoPath)) return true
            }
        }
        return rows.keys.any { k -> sidecars.none { it.path == k } }
    }

    private fun hasUnknownReference(videoPath: String): Boolean =
        references.stored(videoPath, SubtitleReferences.EMBEDDED) is SubtitleReferences.Stored.Unknown ||
            references.stored(videoPath, SubtitleReferences.SPEECH) is SubtitleReferences.Stored.Unknown

    private data class VideoUnit(val tracks: List<Track>, val jellyfinId: String?, val season: Int?, val episode: Int?)

    private fun unitOf(item: MediaItem, videoPath: String): VideoUnit? =
        if (item.kind == MediaKind.TV_SHOW) item.episodes.firstOrNull { it.path == videoPath }?.let { VideoUnit(it.tracks, it.jellyfinId, it.seasonNumber, it.episodeNumber) }
        else if (item.path == videoPath) VideoUnit(item.tracks, item.jellyfinId, null, null) else null

    /** The `check_subtitles` job (FR-273-8): references may be fetched and decoded here. */
    suspend fun runJob(itemId: String, videoPath: String): Outcome? {
        val item = store.resolve(itemId) ?: return null
        return checkVideo(item, videoPath, Mode.JOB)
    }

    suspend fun checkVideo(
        item: MediaItem,
        videoPath: String,
        mode: Mode,
        sources: Map<String, Source> = emptyMap(),
        force: Boolean = false,
    ): Outcome = lockFor(videoPath).withLock { checkLocked(item, videoPath, mode, sources, force) }

    private suspend fun checkLocked(item: MediaItem, videoPath: String, mode: Mode, sources: Map<String, Source>, force: Boolean): Outcome {
        val video = FileIntegrityService.stampOf(videoPath) ?: return Outcome(0, false, emptyList(), emptyList())
        val unit = unitOf(item, videoPath) ?: return Outcome(0, false, emptyList(), emptyList())
        val durationMs = unit.tracks.fileDurationMs()
        val sidecars = sidecarsOf(videoPath)
        val existing = q.checksForVideo(videoPath).executeAsList().associateBy { it.sidecar_path }

        // Rows for sidecars no longer on disk go (Bazarr deleted or replaced them).
        for (gone in existing.keys - sidecars.map { it.path }.toSet()) q.removeCheck(gone)
        if (sidecars.isEmpty()) {
            SubtitleVerdicts.updateVideo(videoPath, emptyList())
            return Outcome(0, false, emptyList(), emptyList())
        }

        val bytes = sidecars.associate { it.path to (runCatching { FileIo.readBytes(Path(it.path)) }.getOrNull() ?: ByteArray(0)) }
        val hashes = bytes.mapValues { contentHash(it.value) }
        val due = sidecars.filter { s ->
            val r = existing[s.path]
            force || r == null || r.sidecar_size != s.size || r.sidecar_mtime != s.mtime || r.video_size != video.size ||
                r.video_mtime != video.mtime || r.content_hash != hashes[s.path] || sources.containsKey(s.path) ||
                (r.verdict == Verdict.CANT_TELL.wire && r.reason != CantTell.TOO_FEW_CUES)
        }
        if (due.isEmpty()) {
            val rows = q.checksForVideo(videoPath).executeAsList()
            SubtitleVerdicts.updateVideo(videoPath, rows)
            return Outcome(0, false, rows, emptyList())
        }

        // FR-273-2 rung 1 — the embedded reference, fetched now when a job may.
        var embeddedState = references.stored(videoPath, SubtitleReferences.EMBEDDED)
        if (embeddedState is SubtitleReferences.Stored.Unknown && mode == Mode.JOB &&
            SubtitleReferences.embeddedCandidates(unit.tracks).isNotEmpty()) {
            references.fetchEmbedded(videoPath, unit.jellyfinId, unit.tracks, durationMs)
            embeddedState = references.stored(videoPath, SubtitleReferences.EMBEDDED)
        }
        val embedded = (embeddedState as? SubtitleReferences.Stored.Present)?.let { CueCodec.decode(it.row.payload) to (it.row.source ?: "") }
        val embeddedUnusable = embeddedState is SubtitleReferences.Stored.Absent && SubtitleReferences.embeddedCandidates(unit.tracks).isNotEmpty()

        // Phase 301 (FR-301-3/4) — judged on cleaned cues: none longer than 20 s, a few lines after the end left out.
        val parsed = due.associate { s -> s.path to VerdictRules.clean(CueParser.parse(bytes[s.path]!!.decodeToString()), durationMs) }

        // Rung 2 — a sibling already in sync against rung 1 or 3 (never one judged against another sibling).
        fun siblingFor(s: Sidecar): Pair<Sidecar, List<Cue>>? {
            if (embedded != null) return null
            val sib = existing.values.firstOrNull { r ->
                r.sidecar_path != s.path && r.verdict == Verdict.IN_SYNC.wire && r.reference?.startsWith(RefKind.SIBLING.wire) == false &&
                    sidecars.any { it.path == r.sidecar_path && it.size == r.sidecar_size && it.mtime == r.sidecar_mtime }
            } ?: return null
            val side = sidecars.first { it.path == sib.sidecar_path }
            val cues = CueParser.parse(runCatching { FileIo.readBytes(Path(side.path)) }.getOrNull()?.decodeToString() ?: return null)
            return side to VerdictRules.clean(cues, durationMs).cues
        }

        // Rung 3 — the speech track, decoded now when a job may and nothing stronger exists.
        var speechState = references.stored(videoPath, SubtitleReferences.SPEECH)
        val needsSpeech = embedded == null && due.any { siblingFor(it) == null }
        if (needsSpeech && speechState is SubtitleReferences.Stored.Unknown && mode == Mode.JOB) {
            references.computeSpeech(videoPath, unit.tracks, item.originalLanguage)
            speechState = references.stored(videoPath, SubtitleReferences.SPEECH)
        }
        val speech = (speechState as? SubtitleReferences.Stored.Present)?.row
        val mono = (speechState as? SubtitleReferences.Stored.Absent)?.source == SubtitleReferences.SOURCE_MONO

        val neighbours = neighbourPaths(item, unit, videoPath)
        val neighbourRefs by lazy { references.currentFor(neighbours.keys) }

        val now = nowSec()
        val changed = ArrayList<Subtitle_check>()
        var needsJob = false
        for (s in due) {
            val cleaned = parsed[s.path] ?: VerdictRules.Cleaned(emptyList(), 0)
            val cues = cleaned.cues
            val prev = existing[s.path]
            val source = sources[s.path] ?: prev?.takeIf { it.content_hash == hashes[s.path] }?.let { Source(it.provider, it.subs_id) }
            val sibling = siblingFor(s)
            val refLabel: String?
            val j: Judgement = when {
                cues.size < VerdictRules.MIN_CUES -> { refLabel = null; Judgement(Verdict.CANT_TELL, reason = CantTell.TOO_FEW_CUES) }
                embedded != null -> { refLabel = "${RefKind.EMBEDDED.wire}:${embedded.second}"; judgeAgainstCues(cues, embedded.first, RefKind.EMBEDDED, durationMs, neighbours, { neighbourRefs }) }
                sibling != null -> { refLabel = "${RefKind.SIBLING.wire}:${sibling.first.path.substringAfterLast('/')}"; judgeAgainstCues(cues, sibling.second, RefKind.SIBLING, durationMs, neighbours, { neighbourRefs }) }
                speech != null -> { refLabel = "${RefKind.SPEECH.wire}:${speech.source ?: ""}"; judgeAgainstSpeech(cues, speech.payload, durationMs, neighbours, { neighbourRefs }) }
                else -> {
                    refLabel = null
                    if (mode == Mode.INLINE && hasUnknownReference(videoPath)) needsJob = true
                    val reason = when {
                        mono -> CantTell.MONO_AUDIO
                        embeddedUnusable -> CantTell.REFERENCE_UNUSABLE
                        else -> CantTell.NO_REFERENCE
                    }
                    VerdictRules.judge(null, null, emptyMap(), cues.firstOrNull()?.startMs ?: 0, cues.lastOrNull()?.endMs ?: 0, durationMs, noRefReason = reason,
                        lastStartMs = cues.lastOrNull()?.startMs ?: 0)
                }
            }
            val match = j.matchKey?.let { neighbours[it] }
            q.putCheck(
                sidecarPath = s.path, videoPath = videoPath, itemId = item.id, language = s.language,
                forced = if (s.forced) 1 else 0, hi = if (s.hi) 1 else 0,
                sidecarSize = s.size, sidecarMtime = s.mtime, videoSize = video.size, videoMtime = video.mtime,
                verdict = j.verdict.wire, reason = j.reason, reference = refLabel,
                rho = j.fit?.rho, z = j.fit?.z, scale = j.fit?.scale, shiftMs = j.fit?.shiftMs, worstMs = j.worstMs,
                driftMsPerHour = j.driftMsPerHour, matchVideoPath = j.matchKey, matchLabel = match,
                cues = cues.size.toLong(), lastCueMs = cues.lastOrNull()?.endMs, contentHash = hashes[s.path] ?: "",
                cuesPastEnd = cleaned.pastEnd.toLong(),
                provider = source?.provider, subsId = source?.subsId,
                detail = j.chunks.joinToString(",") { it.shiftMs?.toString() ?: "-" }, checkedAt = now,
            )
            val row = q.checkByPath(s.path).executeAsOne()
            if (prev == null || prev.verdict != row.verdict || prev.content_hash != row.content_hash || prev.worst_ms != row.worst_ms) changed += row
            if (prev?.verdict != row.verdict && row.verdict != Verdict.IN_SYNC.wire && row.verdict != Verdict.CANT_TELL.wire) {
                mediaHistory.record(item.id, "subtitle_check", describe(row))
            }
        }
        val rows = q.checksForVideo(videoPath).executeAsList()
        SubtitleVerdicts.updateVideo(videoPath, rows)
        if (changed.isNotEmpty()) Logger.info("check_subtitles: ${changed.size} verdict(s) for ${videoPath.substringAfterLast('/')}", "subtitles", item.id)
        steering?.afterCheck(item, videoPath, rows, changed)
        return Outcome(due.size, needsJob, rows, changed)
    }

    /** Same and adjacent seasons of a series, keyed by video path, valued by the episode label (FR-273-3). */
    private fun neighbourPaths(item: MediaItem, unit: VideoUnit, videoPath: String): Map<String, String> {
        if (item.kind != MediaKind.TV_SHOW) return emptyMap()
        val season = unit.season ?: return emptyMap()
        return item.episodes.asSequence()
            .filter { it.path != videoPath && it.seasonNumber != null && abs(it.seasonNumber - season) <= 1 }
            .distinctBy { it.path }
            .associate { it.path to (episodeLabel(it.seasonNumber, it.episodeNumber) ?: it.filename) }
    }

    private fun judgeAgainstCues(
        cues: List<Cue>, ref: List<Cue>, kind: RefKind, durationMs: Long?,
        neighbours: Map<String, String>, neighbourRefs: () -> Map<String, List<dev.jellystructure.db.Subtitle_reference>>,
    ): Judgement {
        val last = maxOf(cues.last().endMs, ref.lastOrNull()?.endMs ?: 0L, durationMs ?: 0L)
        val plan = FftPlan(SubtitleTiming.sizeFor(last))
        val cand = SubtitleTiming.candidateSpectra(cues, plan)
        val own = SubtitleTiming.bestFit(cand, SubtitleTiming.spectrum(SubtitleTiming.cueSignal(ref, plan.n), plan), plan)
        val fits = VerdictRules.fitsSubtitle(own)
        val chunks = if (fits) SubtitleTiming.chunkShiftsMs(cues, own, ref) else emptyList()
        val others = if (fits || neighbours.isEmpty()) emptyMap() else {
            val refs = neighbourRefs().mapNotNull { (path, rs) ->
                rs.firstOrNull { it.kind == SubtitleReferences.EMBEDDED }?.let { path to CueCodec.decode(it.payload) }
            }.toMap()
            if (refs.isEmpty()) emptyMap() else {
                val nLast = maxOf(last, refs.values.maxOf { it.lastOrNull()?.endMs ?: 0L })
                val p2 = if (SubtitleTiming.sizeFor(nLast) == plan.n) plan else FftPlan(SubtitleTiming.sizeFor(nLast))
                val c2 = if (p2 === plan) cand else SubtitleTiming.candidateSpectra(cues, p2)
                refs.mapValues { (_, rc) -> SubtitleTiming.bestFit(c2, SubtitleTiming.spectrum(SubtitleTiming.cueSignal(rc, p2.n), p2), p2) }
            }
        }
        return VerdictRules.judge(own, kind, others, cues.first().startMs, cues.last().endMs, durationMs, chunks = chunks, lastStartMs = cues.last().startMs)
    }

    private fun judgeAgainstSpeech(
        cues: List<Cue>, frames: ByteArray, durationMs: Long?,
        neighbours: Map<String, String>, neighbourRefs: () -> Map<String, List<dev.jellystructure.db.Subtitle_reference>>,
    ): Judgement {
        val refs = if (neighbours.isEmpty()) emptyMap() else neighbourRefs().mapNotNull { (path, rs) ->
            rs.firstOrNull { it.kind == SubtitleReferences.SPEECH }?.let { path to it.payload }
        }.toMap()
        val lastMs = maxOf(cues.last().endMs, frames.size * SubtitleTiming.FRAME_MS, durationMs ?: 0L,
            refs.values.maxOfOrNull { it.size * SubtitleTiming.FRAME_MS } ?: 0L)
        val plan = FftPlan(SubtitleTiming.sizeFor(lastMs))
        val cand = SubtitleTiming.candidateSpectra(cues, plan)
        val own = SubtitleTiming.bestFit(cand, SubtitleTiming.spectrum(SubtitleTiming.speechSignal(frames, plan.n), plan), plan)
        val others = refs.mapValues { (_, f) -> SubtitleTiming.bestFit(cand, SubtitleTiming.spectrum(SubtitleTiming.speechSignal(f, plan.n), plan), plan) }
        return VerdictRules.judge(own, RefKind.SPEECH, others, cues.first().startMs, cues.last().endMs, durationMs, lastStartMs = cues.last().startMs)
    }

    /** The History line for a verdict (FR-273-22): which file, what it is, and the numbers. */
    fun describe(row: Subtitle_check): String {
        val name = row.sidecar_path.substringAfterLast('/')
        return "file=$name lang=${row.language ?: "?"} ${verdictWords(row)} · ρ=${row.rho?.let { (it * 100).roundToLong() / 100.0 }} z=${row.z?.let { (it * 10).roundToLong() / 10.0 }}"
    }

    /** A verdict in the admin's words (FR-273-20), without the numbers. Phase 301 (FR-301-4) names the lines left
     *  out after the video's end. */
    fun verdictWords(row: Subtitle_check): String {
        val past = row.cues_past_end.toInt()
        val note = if (past > 0) " · $past ${if (past == 1) "line" else "lines"} after the video ends: an ad or a credit" else ""
        return verdictCore(row) + note
    }

    private fun verdictCore(row: Subtitle_check): String =
        when (row.verdict) {
            Verdict.OFF.wire, Verdict.OFF_MID_FILE.wire -> {
                val shift = row.shift_ms ?: 0
                val dir = if (shift < 0) "late" else "early"
                buildString {
                    append(if (row.verdict == Verdict.OFF_MID_FILE.wire) "off, and not by one offset" else "off")
                    if (abs(shift) >= 500) append(", ${(abs(shift) / 1000.0).roundToOneDecimal()} s $dir")
                    row.scale?.takeIf { abs(it - 1.0) > 1e-6 }?.let { append(", runs at ${(it * 100_000).roundToLong() / 1000.0}% speed") }
                    row.worst_ms?.let { append(" (up to ${(it / 1000.0).roundToOneDecimal()} s)") }
                }
            }
            Verdict.OTHER_EPISODE.wire -> "belongs to ${row.match_label ?: row.match_video_path?.substringAfterLast('/')}"
            Verdict.NOT_THIS_VIDEO.wire -> "not this video"
            Verdict.LONGER_VIDEO.wire -> "timed for a longer video"
            Verdict.IN_SYNC.wire -> "in sync"
            else -> when (row.reason) {
                CantTell.NO_REFERENCE -> "can't check yet: nothing in this file to compare with"
                CantTell.REFERENCE_UNUSABLE -> "can't check: the subtitle inside the file is only signs"
                CantTell.MONO_AUDIO -> "can't check: mono audio"
                CantTell.TOO_FEW_CUES -> "can't check: too few lines"
                else -> if (row.reference?.startsWith(RefKind.SPEECH.wire) == true) "doubtful: it does not follow the speech" else "can't tell"
            }
        }

    private fun Double.roundToOneDecimal(): Double = (this * 10).roundToLong() / 10.0

    /** A title's verdicts are all this module knows about the store's tracks, for the admin's strip (§F). */
    fun hiddenTracks(item: MediaItem): Set<String> {
        if (SubtitleVerdicts.reportOnly()) return emptySet()
        val tracks = item.tracks + item.episodes.flatMap { it.tracks }
        return tracks.filter { it.kind == TrackKind.SUBTITLE && it.external && !SubtitleVerdicts.isOffered(it.externalPath) }
            .mapNotNull { it.externalPath }.toSet()
    }
}
