package dev.jellystructure.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 317 — sound that runs ahead of (or behind) the picture: the measurement's raw shape, the ONE rule
 * that turns it into a verdict ([AvSyncVerdict]), and the ONE builder of the fix ([AvSyncAdvice]).
 *
 * Why a rule and not a stored state: zero is the library's own median, and it MOVES as the sweep runs
 * (FR-317-6). A state stored beside the windows would go stale across thousands of rows the first time
 * the median shifted by a frame, so the sidecar's windows are what is stored and every verdict is
 * computed on read.
 *
 * Every number here is in 25 fps frames — one frame is 40 ms — and **positive means audio EARLY**: the
 * words are heard before the lips move. That sign is SyncNet's own, calibrated against a file with a
 * known 200 ms delay (the spec's "What was found" table), and it is the sign the command's delay takes.
 *
 * See specs/requirements/phase-317-sound-that-runs-ahead-of-the-picture-is-found-and-explained.md.
 */

/** One measured 45 s window as the sidecar returns it (FR-317-2). [offsetFrames] is null when the window
 *  had no face track at all — such a window is never confident and never counted. */
@Serializable
data class AvSyncWindow(
    @SerialName("start_s") val startS: Double,
    @SerialName("offset_frames") val offsetFrames: Int? = null,
    val conf: Double = 0.0,
    val frames: Int = 0,
    val faces: List<AvSyncFace> = emptyList(),
)

/** One face track inside a window: the evidence the title page folds open. */
@Serializable
data class AvSyncFace(
    @SerialName("at_s") val atS: Double,
    val frames: Int = 0,
    @SerialName("offset_frames") val offsetFrames: Int = 0,
    val conf: Double = 0.0,
)

/** FR-317-4 — one of the file's OTHER audio streams, matched to the measured one by its loudness envelope.
 *  [lagFrames] is positive when this stream runs EARLY of the measured one, the same sign as everything
 *  else here; null when every window was silent on it. */
@Serializable
data class AvSyncOther(
    val audio: String,
    @SerialName("lag_frames") val lagFrames: Int? = null,
    val corr: Double = 0.0,
)

/** FR-317-5 — why nothing was measured. A file with a reason is `unknown`, never "clean". */
object AvSyncReason {
    const val ANIMATION = "animation"
    const val TOO_SHORT = "too_short"
    const val NO_ORIGINAL_TRACK = "no_original_track"
    const val NO_FACES = "no_faces"

    /** The one quiet line the Checks card shows for a skipped file (FR-317-10). */
    fun label(reason: String): String = when (reason) {
        ANIMATION -> "skipped — animation has no lips to follow"
        TOO_SHORT -> "skipped — shorter than two minutes"
        NO_ORIGINAL_TRACK -> "skipped — no track in the original language; a dub has no lips to follow"
        NO_FACES -> "no faces to measure — nothing on screen the model can read lips from"
        else -> "skipped"
    }
}

/** FR-317-7 — four states, and `UNKNOWN` is never rendered or counted as `IN_SYNC`. */
enum class AvSyncState { IN_SYNC, OFFSET, DRIFT, UNKNOWN }

/**
 * What the stored windows mean, computed on read.
 *
 * [offsetMs] is the steady offset (positive = audio early), null for `DRIFT` and `UNKNOWN`. A drift
 * carries the fitted line's value at the first and last confident window ([driftFromMs] at [driftFromS],
 * [driftToMs] at [driftToS]). [why] is the one honest sentence for `UNKNOWN`.
 */
data class AvSyncReading(
    val state: AvSyncState,
    val offsetMs: Long? = null,
    val medianFrames: Int? = null,
    val confidentWindows: Int = 0,
    val driftFromMs: Long? = null,
    val driftToMs: Long? = null,
    val driftFromS: Double? = null,
    val driftToS: Double? = null,
    val reason: String? = null,
    val why: String? = null,
) {
    /** FR-317-9 — does this file need an operator's attention? `UNKNOWN` never does. */
    val isFinding: Boolean get() = state == AvSyncState.OFFSET || state == AvSyncState.DRIFT
    /** True while the sound is heard BEFORE the picture (the reported broadcast's direction). */
    val audioEarly: Boolean get() = (offsetMs ?: driftToMs ?: 0L) > 0
}

object AvSyncVerdict {
    /** One frame of the 25 fps grid every reading is on. */
    const val MS_PER_FRAME = 40L

    /** FR-317-7 — a window counts only this confident, over this many face frames. Below either, the
     *  window is evidence of nothing: SyncNet's distance curve on 2 s of a half-turned face has a minimum
     *  wherever it likes. */
    const val CONF_MIN = 2.0
    const val FRAMES_MIN = 250

    /** FR-317-6 — in-sync material reads −1…0 frames, not 0, so the library's own median is zero. Until
     *  [ZERO_MIN_WINDOWS] confident windows have been stored there is no median worth trusting and the
     *  measured −1 of ~20 titles stands in for it. */
    const val DEFAULT_ZERO_FRAMES = -1
    const val ZERO_MIN_WINDOWS = 200

    /** FR-317-7 — past these, relative to zero, the offset is a finding: audio early ≥ 80 ms, or late
     *  ≥ 160 ms. The asymmetry is ITU-R BT.1359's: early sound is noticed at less than half the delay
     *  late sound is (detectable at 45 ms early / 125 ms late, acceptable to 90 / 185). */
    const val EARLY_FRAMES = 2
    const val LATE_FRAMES = -4

    /** FR-317-7 — steady when this fraction of the confident windows sits within ±1 frame of the median;
     *  a drift needs at least [DRIFT_MIN_WINDOWS] of them on a line, changing by [DRIFT_MIN_FRAMES]. */
    const val STEADY_FRACTION = 0.75
    const val DRIFT_MIN_WINDOWS = 4
    const val DRIFT_MIN_FRAMES = 3

    /** FR-317-5/-8 — a file shorter than this is `too_short`: three 45 s windows cannot fit in it, and
     *  nothing in the library that short is a film or an episode. The rule lives here, with the other
     *  rules, rather than in the service, so both the enqueue pass's skip and the job's own agree by
     *  construction. */
    const val MIN_RUNTIME_S = 120.0

    fun isConfident(w: AvSyncWindow): Boolean = w.offsetFrames != null && w.conf >= CONF_MIN && w.frames >= FRAMES_MIN

    /**
     * FR-317-6 — the library's zero: the conf-weighted median [offsetFrames] of every confident window
     * stored, or [DEFAULT_ZERO_FRAMES] until [ZERO_MIN_WINDOWS] of them exist. Takes the windows as
     * (offsetFrames, conf) pairs so the caller can stream them out of SQLite without building objects.
     */
    fun zeroFrames(confidentOffsets: List<Pair<Int, Double>>): Int =
        if (confidentOffsets.size < ZERO_MIN_WINDOWS) DEFAULT_ZERO_FRAMES
        else weightedMedian(confidentOffsets) ?: DEFAULT_ZERO_FRAMES

    /** The smallest value whose cumulative weight reaches half the total — a median that a long window
     *  of strong evidence can pull and a 2 s glance cannot. Null for an empty or weightless list. */
    fun weightedMedian(values: List<Pair<Int, Double>>): Int? {
        val usable = values.filter { it.second > 0.0 }
        if (usable.isEmpty()) return values.map { it.first }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val sorted = usable.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2.0
        var acc = 0.0
        for ((value, weight) in sorted) {
            acc += weight
            if (acc >= half) return value
        }
        return sorted.last().first
    }

    /**
     * FR-317-7 — the verdict for one file. [zeroFrames] is the library's (never a per-file zero);
     * [reason] is set when the sidecar was never asked (FR-317-5) and makes the answer `UNKNOWN` outright.
     * [runtimeS] is the file's own length, which decides [AvSyncReason.TOO_SHORT] here so that rule has one
     * home; null means "not known", which is never a skip.
     */
    fun of(windows: List<AvSyncWindow>, zeroFrames: Int = DEFAULT_ZERO_FRAMES, runtimeS: Double? = null, reason: String? = null): AvSyncReading {
        if (reason != null) return AvSyncReading(AvSyncState.UNKNOWN, reason = reason, why = AvSyncReason.label(reason))
        if (runtimeS != null && runtimeS < MIN_RUNTIME_S) {
            return AvSyncReading(AvSyncState.UNKNOWN, reason = AvSyncReason.TOO_SHORT, why = AvSyncReason.label(AvSyncReason.TOO_SHORT))
        }
        val confident = windows.filter { isConfident(it) }.sortedBy { it.startS }
        if (confident.size < 2) {
            return AvSyncReading(
                AvSyncState.UNKNOWN, confidentWindows = confident.size,
                reason = if (windows.isEmpty()) null else AvSyncReason.NO_FACES,
                why = if (confident.isEmpty()) "no window read clearly enough to measure" else "only one window read clearly enough to measure",
            )
        }
        val rel = confident.map { (it.offsetFrames!! - zeroFrames) to it.conf }
        val m = weightedMedian(rel)!!
        val steady = rel.count { kotlin.math.abs(it.first - m) <= 1 } >= ceilFraction(rel.size, STEADY_FRACTION)
        if (steady) {
            val state = if (m >= EARLY_FRAMES || m <= LATE_FRAMES) AvSyncState.OFFSET else AvSyncState.IN_SYNC
            return AvSyncReading(state, offsetMs = m * MS_PER_FRAME, medianFrames = m, confidentWindows = rel.size)
        }
        drift(confident, zeroFrames)?.let { return it }
        return AvSyncReading(
            AvSyncState.UNKNOWN, medianFrames = m, confidentWindows = rel.size,
            why = "the readings disagree — they are neither one steady offset nor a drift",
        )
    }

    /** The drift half of FR-317-7, kept separate so the steady path reads as the common case it is. */
    private fun drift(confident: List<AvSyncWindow>, zeroFrames: Int): AvSyncReading? {
        if (confident.size < DRIFT_MIN_WINDOWS) return null
        val xs = confident.map { it.startS }
        val ys = confident.map { (it.offsetFrames!! - zeroFrames).toDouble() }
        val n = xs.size
        val mx = xs.sum() / n
        val my = ys.sum() / n
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0.0) return null                       // every window at the same time: no line to fit
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val intercept = my - slope * mx
        fun line(x: Double) = slope * x + intercept
        val onLine = xs.indices.count { kotlin.math.abs(ys[it] - line(xs[it])) <= 1.0 }
        if (onLine < ceilFraction(n, STEADY_FRACTION)) return null
        // FR-317-7 — reported at the first and last CONFIDENT window, never extrapolated past them: the
        // line is evidence between the windows that drew it and a guess anywhere else.
        val fromS = xs.first()
        val toS = xs.last()
        val from = line(fromS)
        val to = line(toS)
        if (kotlin.math.abs(to - from) < DRIFT_MIN_FRAMES) return null
        return AvSyncReading(
            AvSyncState.DRIFT, confidentWindows = n,
            driftFromMs = (from * MS_PER_FRAME).toLong(), driftToMs = (to * MS_PER_FRAME).toLong(),
            driftFromS = fromS, driftToS = toS,
        )
    }

    /** How many of [n] make up [fraction] of it, rounded up: 3 of 4, 3 of 3, 2 of 2. */
    private fun ceilFraction(n: Int, fraction: Double): Int {
        val exact = n * fraction
        val whole = exact.toInt()
        return if (exact > whole) whole + 1 else whole
    }

    /** `+160 ms` / `−160 ms`, the sign a human reads (ahead of the picture is positive). */
    fun signedMs(ms: Long): String = if (ms < 0) "−${-ms} ms" else "+$ms ms"
}

/** FR-317-11 — one audio stream as the advice sees it: its ffprobe specifier (`0:a:N`, never Jellyfin's
 *  numbering), how a page names it, its mkvmerge track id where the file is a matroska, and — for the
 *  tracks that were not measured — how it sits against the measured one. */
data class AvSyncTrack(
    val specifier: String,
    val label: String,
    val mkvId: Int? = null,
    val lagFrames: Int? = null,
    val corr: Double = 0.0,
)

/** FR-317-11 — the suggestion for one flagged file: prose, the exact command where one exists, and which
 *  tracks the command moves ([shifted]) against the ones it deliberately leaves ([left], each with the
 *  reason it was left). */
data class AvSyncFix(
    val what: String,
    val experience: String,
    val who: String,
    val suggestion: String,
    val command: String?,
    val shifted: List<String> = emptyList(),
    val left: List<String> = emptyList(),
)

/**
 * Phase 317 (FR-317-11) — ONE builder for the words and the command, so a page can never say one thing in
 * prose and another in the shell. Paths are shell-quoted; the output is a work file in `<dir>/.jellystructure/`
 * (311) that is renamed over the original, so a hard-linked torrent copy keeps its bytes and keeps seeding
 * and Radarr/Sonarr never see a half-written file (FR-317-12).
 */
object AvSyncAdvice {
    /** FR-317-11 — a track moves with the measured one only this much in step with it. */
    const val IN_STEP_CORR = 0.6
    const val IN_STEP_LAG_FRAMES = 1

    /** The tag both commands write, so a shift can be recognised (and 317b's Undo read) from the file itself. */
    const val TAG = "JELLYSTRUCTURE_AV_SHIFT"

    private fun q(s: String) = "'${s.replace("'", "'\\''")}'"

    /** FR-317-11 — does [other] move with the measured track? */
    fun inStep(other: AvSyncTrack): Boolean =
        other.corr >= IN_STEP_CORR && other.lagFrames != null && kotlin.math.abs(other.lagFrames) <= IN_STEP_LAG_FRAMES

    fun advise(reading: AvSyncReading, path: String, measured: AvSyncTrack, others: List<AvSyncTrack> = emptyList()): AvSyncFix {
        val who = "Every player — it is in the file, not in the playback."
        if (reading.state == AvSyncState.DRIFT) {
            val from = AvSyncVerdict.signedMs(reading.driftFromMs ?: 0)
            val to = AvSyncVerdict.signedMs(reading.driftToMs ?: 0)
            return AvSyncFix(
                what = "The sound drifts away from the picture: $from at ${TrackCoverage.mmss(((reading.driftFromS ?: 0.0) * 1000).toLong())}, " +
                    "$to at ${TrackCoverage.mmss(((reading.driftToS ?: 0.0) * 1000).toLong())}.",
                experience = "It starts in step and ends out of step — worst in the last part of the file, where it is far enough to be distracting in any dialogue.",
                who = who,
                suggestion = "A single shift cannot fix a drift: whatever delay is right at the start is wrong at the end. " +
                    "Replace the file — in Sonarr/Radarr, delete it and search again. A different release of the same title is almost always in step.",
                command = null,
            )
        }
        val delayMs = reading.offsetMs ?: 0L
        val early = delayMs > 0
        val absMs = if (delayMs < 0) -delayMs else delayMs
        val shifted = listOf(measured) + others.filter { inStep(it) }
        val left = others.filterNot { inStep(it) }
        val what = "Audio is $absMs ms ${if (early) "ahead of" else "behind"} the picture."
        val experience = if (early) "The words are heard before the lips move — most visible in close-ups and dialogue."
        else "The lips move before the words are heard — most visible in close-ups and dialogue."
        val move = if (early) "Delay the audio by $absMs ms" else "Pull the audio $absMs ms earlier"
        val leftWords = left.map { t ->
            "${t.label} is not in step with the main audio (" +
                (t.lagFrames?.let { "${AvSyncVerdict.signedMs(it * AvSyncVerdict.MS_PER_FRAME)} of it" } ?: "nothing in common with it") +
                "${if (t.corr < IN_STEP_CORR) ", correlation ${t.corr}" else ""}) and is left as it is"
        }
        val suggestion = buildString {
            append("$move — a remux, no re-encode: the picture and the sound are unchanged, only their timing. ")
            append(if (shifted.size == 1) "It moves the measured track, ${measured.label}. " else "It moves ${shifted.size} tracks together: ${shifted.joinToString(", ") { it.label }}. ")
            if (leftWords.isNotEmpty()) append(leftWords.joinToString("; ").replaceFirstChar { it.uppercase() }).append(". ")
            append("The new file is written in the hidden ${WorkFiles.DIR} folder beside this one and renamed over it, so a torrent copy keeps seeding and Radarr/Sonarr never see a half-written file.")
        }
        val command = command(path, delayMs, listOf(measured) + others, shifted.map { it.specifier }.toSet())
        return AvSyncFix(what, experience, who, suggestion, command, shifted.map { it.label }, left.map { it.label })
    }

    /** FR-317-11 — the command, or null for a container neither mkvmerge nor a stream-copy mp4 remux fits.
     *  [allAudio] is every audio stream of the file (the mp4 remux has to re-map all of them, in order);
     *  [shiftedSpecs] the ones that move. */
    fun command(path: String, delayMs: Long, allAudio: List<AvSyncTrack>, shiftedSpecs: Set<String>): String? {
        if (delayMs == 0L) return null
        val work = WorkFiles.pathFor(path, WorkFiles.Kind.AV_SHIFT)
        val prepare = WorkFiles.prepareCommand(path)
        return when (path.substringAfterLast('.').lowercase()) {
            "mkv", "mka", "mks" -> {
                val ids = allAudio.filter { it.specifier in shiftedSpecs }.mapNotNull { it.mkvId }
                if (ids.isEmpty()) return null                 // without mkvmerge's own ids a --sync would move the wrong track
                val tags = WorkFiles.pathFor(path, WorkFiles.Kind.AV_TAGS)
                val syncs = ids.joinToString(" ") { "--sync $it:$delayMs" }
                "$prepare && printf '<Tags><Tag><Simple><Name>$TAG</Name><String>$delayMs</String></Simple></Tag></Tags>' > ${q(tags)}" +
                    " && nice -n 19 ionice -c3 mkvmerge -q -o ${q(work)} --global-tags ${q(tags)} $syncs ${q(path)}" +
                    " && mv ${q(work)} ${q(path)} && rm ${q(tags)}"
            }
            "mp4", "m4v", "mov" -> {
                // The shifted streams come from input 1 (the one `-itsoffset` moved), the rest from input 0, every
                // audio stream in its original order — so a player's track list is unchanged by the fix.
                val maps = allAudio.map { it.specifier }.distinct().sortedBy { it.substringAfterLast(':').toIntOrNull() ?: 0 }
                    .joinToString(" ") { spec -> "-map ${if (spec in shiftedSpecs) "1" else "0"}:a:${spec.substringAfterLast(':')}" }
                val seconds = secondsArg(delayMs)
                "$prepare && nice -n 19 ionice -c3 ffmpeg -nostdin -v error -i ${q(path)} -itsoffset $seconds -i ${q(path)}" +
                    " -map 0:v $maps -map 0:s? -c copy -map_metadata 0 -movflags +faststart+use_metadata_tags" +
                    " -metadata $TAG=$delayMs ${q(work)} && mv ${q(work)} ${q(path)}"
            }
            else -> null
        }
    }

    /** `-itsoffset` takes seconds: `0.160`, `-0.160`. Built by hand so no locale can turn the point into a comma. */
    fun secondsArg(ms: Long): String {
        val sign = if (ms < 0) "-" else ""
        val abs = if (ms < 0) -ms else ms
        return "$sign${abs / 1000}.${(abs % 1000).toString().padStart(3, '0')}"
    }

    /** FR-317-4 — which audio stream the lips are followed against: the one in the title's original
     *  language, the default among several, else the first. Null when there is more than one audio track
     *  and none of them is the original language — a dub has no lips to follow (`no_original_track`).
     *  A file whose ONLY audio is untagged is measured on it.
     *
     *  [tracks] are the file's audio streams in container order, as (specifier, language, isDefault).
     */
    fun measuredTrack(tracks: List<Triple<String, String?, Boolean>>, originalLanguage: String?, sameLanguage: (String?, String?) -> Boolean): String? {
        if (tracks.isEmpty()) return null
        if (tracks.size == 1) return tracks[0].first
        val inOriginal = tracks.filter { sameLanguage(it.second, originalLanguage) }
        if (inOriginal.isEmpty()) return null
        return (inOriginal.firstOrNull { it.third } ?: inOriginal.first()).first
    }
}
