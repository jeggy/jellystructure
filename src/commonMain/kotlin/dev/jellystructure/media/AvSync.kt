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
    /** FR-317-30 (the 2026-10-13 fifth amendment) — one of this window's own numbers came back non-finite
     *  and the sidecar sent `null` in its place, rather than the `NaN` that is not JSON. **A window with no
     *  face track is not this**: that one reports `offset_frames: null` too and is nobody's fault.
     *  Additive and defaulted — a row stored before the flag existed reads `false`, which is what every
     *  such row was. */
    @SerialName("nonfinite") val nonFinite: Boolean = false,
    /** FR-317-32 (the 2026-10-13 seventh amendment) — the window's decoded audio did not reach the video
     *  frames it would have been compared against, so it was **refused**: no detection, no lip features,
     *  and the window reports exactly as one with no face track does. ffmpeg does not pad a dropped audio
     *  packet, so a gap mid-window shifts everything after it and the lips would have been measured
     *  against speech from elsewhere in the file. Additive and defaulted, like [nonFinite]. */
    @SerialName("audio_short") val audioShort: Boolean = false,
    /** FR-317-32 — how many 25 fps frames of video this window's audio features fell short of, past the
     *  six-frame construction gap that every healthy window has. Signed and never clamped, and reported on
     *  **every** window, refused or not: a clean window stores a 0 or a ±1, so the healthy distribution is
     *  readable out of `file_av_sync` and the bar can be re-drawn from evidence rather than from a guess.
     *  0 for every row stored before the field existed, which is what a row with no measurement of it can
     *  honestly say. */
    @SerialName("audio_short_frames") val audioShortFrames: Int = 0,
)

/** One face track inside a window: the evidence the title page folds open. */
@Serializable
data class AvSyncFace(
    @SerialName("at_s") val atS: Double,
    val frames: Int = 0,
    @SerialName("offset_frames") val offsetFrames: Int = 0,
    val conf: Double = 0.0,
    /** FR-317-30 — as [AvSyncWindow.nonFinite], for this one face track's own reading. */
    @SerialName("nonfinite") val nonFinite: Boolean = false,
)

/** FR-317-4 — one of the file's OTHER audio streams, matched to the measured one by its loudness envelope.
 *  [lagFrames] is positive when this stream runs EARLY of the measured one, the same sign as everything
 *  else here; null when every window was silent on it. */
@Serializable
data class AvSyncOther(
    val audio: String,
    @SerialName("lag_frames") val lagFrames: Int? = null,
    val corr: Double = 0.0,
    /** FR-317-30 — this stream's correlation came back non-finite; `corr` is the 0.0 a coerced `null`
     *  leaves, which is below [AvSyncAdvice.IN_STEP_CORR] and so is never read as *in step*. */
    @SerialName("nonfinite") val nonFinite: Boolean = false,
)

/** FR-317-5 — why nothing was measured. A file with a reason is `unknown`, never "clean". */
object AvSyncReason {
    const val ANIMATION = "animation"
    const val TOO_SHORT = "too_short"
    const val NO_ORIGINAL_TRACK = "no_original_track"
    const val NO_FACES = "no_faces"

    /** FR-317-30 — a number in the measurement came back non-finite. Deliberately NOT one of
     *  [dev.jellystructure.media.AvSyncService.SKIP_REASONS]: nothing was decided about this file, so a new
     *  pipeline version measures it again, where `animation` or `too_short` would stand for ever. */
    const val NONFINITE = "nonfinite"

    /** FR-317-32 — every window of this file was refused: part of its audio did not decode, and lips are
     *  never compared against audio that is not there. Like [NONFINITE] it is deliberately NOT one of
     *  [dev.jellystructure.media.AvSyncService.SKIP_REASONS] — nothing was decided about the file, so a new
     *  pipeline version measures it again. Unlike [NONFINITE] it is never passed to [AvSyncVerdict.of]'s
     *  `reason` parameter: a refused window is *there is nothing to measure here*, so a file with two clean
     *  windows beside one refused window keeps its verdict. */
    const val SHORT_AUDIO = "short_audio"

    /** The one quiet line the Checks card shows for a skipped file (FR-317-10). */
    fun label(reason: String): String = when (reason) {
        ANIMATION -> "skipped — animation has no lips to follow"
        TOO_SHORT -> "skipped — shorter than two minutes"
        NO_ORIGINAL_TRACK -> "skipped — no track in the original language; a dub has no lips to follow"
        NO_FACES -> "no faces to measure — nothing on screen the model can read lips from"
        NONFINITE -> "not measured — the measurement produced a number that is not a number"
        SHORT_AUDIO -> "not measured — part of the audio did not decode, so there were no words under the lips"
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

    fun isConfident(w: AvSyncWindow): Boolean =
        w.offsetFrames != null && !w.nonFinite && w.conf >= CONF_MIN && w.frames >= FRAMES_MIN

    /**
     * FR-317-30 — did any number in this measurement come back non-finite? A `NaN` correlation is *no
     * answer*, and a file whose answer rests on one is not a measured file: the row stores
     * [AvSyncReason.NONFINITE] and reads `UNKNOWN`, rather than being counted among the clean readings on
     * the strength of a number the model could not produce.
     *
     * The rule lives here, with the other rules, so the job that stores the row and anything that later
     * re-reads it cannot disagree about it.
     */
    fun hasNonFinite(windows: List<AvSyncWindow>, others: List<AvSyncOther> = emptyList()): Boolean =
        windows.any { w -> w.nonFinite || w.faces.any { it.nonFinite } } || others.any { it.nonFinite }

    /**
     * FR-317-32 — was any window refused because its audio did not reach its picture? Unlike
     * [hasNonFinite] this is **never** turned into [of]'s `reason` parameter: a refused window is *there is
     * nothing to measure here*, exactly as a window with no face track is, so a file with two clean windows
     * beside one refused window keeps its `offset`. The refused windows are simply never confident,
     * FR-317-17's second triple then asks three more places in the file, and only a file whose damage is
     * everywhere reaches `unknown` and says [AvSyncReason.SHORT_AUDIO].
     */
    fun hasShortAudio(windows: List<AvSyncWindow>): Boolean = windows.any { it.audioShort }

    /** FR-317-32 — the worst shortfall among the refused windows, for the one line [check] logs on the
     *  `integrity` channel whatever the verdict. Null when nothing was refused. */
    fun worstAudioShortFrames(windows: List<AvSyncWindow>): Int? =
        windows.filter { it.audioShort }.maxOfOrNull { it.audioShortFrames }

    /**
     * FR-317-17 (amendment 2026-10-12) — is another triple of windows worth asking for? Yes whenever the
     * pass so far yields **neither** [AvSyncState.IN_SYNC] **nor** [AvSyncState.OFFSET]: too few confident
     * windows to measure anything (the original rule), or confident windows that disagree.
     *
     * Why the second arm: `drift` needs [DRIFT_MIN_WINDOWS] confident windows on a line, and three windows
     * can never reach four — so three confident windows that disagree, which is exactly the signature of a
     * steady drift, could only ever come out `unknown`, and `unknown` is never counted and never shown
     * (FR-317-7). A drifting film would have vanished silently. The bar is never lowered to meet the
     * windows; more of the file is measured until there are enough of them.
     */
    fun wantsMoreWindows(sofar: AvSyncReading): Boolean =
        sofar.state != AvSyncState.IN_SYNC && sofar.state != AvSyncState.OFFSET

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
            // FR-317-30 — `no_faces` would be a lie about a window whose number came back non-finite: the
            // model found a face and read it, and what it read was not a number. Said plainly, it is the
            // difference between *nothing to measure* and *the measurement failed*.
            val nonFinite = hasNonFinite(windows)
            // FR-317-32 — and `no_faces` would be the same lie about a window the model was never shown:
            // it was refused before any detection ran, because its audio had not reached its picture.
            // `nonfinite` stays the headline where both appear — after this change a non-finite number is
            // by definition not explained by short audio, so it is the one that is news.
            val shortAudio = !nonFinite && hasShortAudio(windows)
            return AvSyncReading(
                AvSyncState.UNKNOWN, confidentWindows = confident.size,
                reason = when {
                    nonFinite -> AvSyncReason.NONFINITE
                    shortAudio -> AvSyncReason.SHORT_AUDIO
                    windows.isEmpty() -> null
                    else -> AvSyncReason.NO_FACES
                },
                why = when {
                    nonFinite -> AvSyncReason.label(AvSyncReason.NONFINITE)
                    shortAudio -> AvSyncReason.label(AvSyncReason.SHORT_AUDIO)
                    confident.isEmpty() -> "no window read clearly enough to measure"
                    else -> "only one window read clearly enough to measure"
                },
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
 *  reason it was left). Where there is no command and a banner can be rendered, [noCommandReason] is the
 *  one sentence that says why (FR-317-24) — built here, beside the command, so the two cannot disagree. */
data class AvSyncFix(
    val what: String,
    val experience: String,
    val who: String,
    val suggestion: String,
    val command: String?,
    val shifted: List<String> = emptyList(),
    val left: List<String> = emptyList(),
    val noCommandReason: String? = null,
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

    /** The containers a shift can be written into — one list each, read by both the command below and the
     *  check-time id probe ([dev.jellystructure.media.AvSyncService.readMkvAudioIds]), so the two cannot
     *  drift apart (FR-317-21). An `.mka`/`.mks` carries no video and is never flagged, but it is a
     *  container `--sync` fits, and the probe and the command must agree about that. */
    val MATROSKA_EXTENSIONS = setOf("mkv", "mka", "mks")
    val MP4_EXTENSIONS = setOf("mp4", "m4v", "mov")

    fun isMatroska(path: String): Boolean = extensionOf(path) in MATROSKA_EXTENSIONS

    private fun extensionOf(path: String) = path.substringAfterLast('.').lowercase()

    /** FR-317-24 — the two sentences a banner shows where there is no command. */
    const val NO_COMMAND_CONTAINER =
        "No one-line fix for this container: the shift can be written into MKV and MP4 only. " +
            "Remux it to MKV first and this check will offer the command."
    const val NO_COMMAND_NO_IDS =
        "No command yet: this file's own track numbers could not be read, and a shift without them could " +
            "move the wrong track. Re-run the check with ↻ above; if it keeps failing, the file's header is " +
            "what to look at."

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
        // FR-317-25 — one clause per left track, naming the arm of `inStep` it actually failed and no other
        // number: a lag measured at 0.55 correlation is not a measurement, and quoting it reads as *in step*
        // right before the sentence excludes the track. The null-lag arm comes first, because a track with
        // nothing in common has a correlation of 0.0 and would otherwise never reach its own clause.
        val leftWords = left.map { t ->
            when {
                t.lagFrames == null -> "${t.label} could not be compared with the main audio, so it is left as it is"
                t.corr < IN_STEP_CORR ->
                    "${t.label} could not be matched against the main audio (correlation ${twoDecimals(t.corr)}, " +
                        "below $IN_STEP_CORR), so it is left as it is: it is different audio, not a delayed copy of it"
                else ->
                    "${t.label} is itself ${AvSyncVerdict.signedMs(t.lagFrames * AvSyncVerdict.MS_PER_FRAME)} out of step " +
                        "with the main audio, so it is left as it is: one shift cannot put both right"
            }
        }
        val suggestion = buildString {
            append("$move — a remux, no re-encode: the picture and the sound are unchanged, only their timing. ")
            append(if (shifted.size == 1) "It moves the measured track, ${measured.label}. " else "It moves ${shifted.size} tracks together: ${shifted.joinToString(", ") { it.label }}. ")
            if (leftWords.isNotEmpty()) append(leftWords.joinToString("; ").replaceFirstChar { it.uppercase() }).append(". ")
            append("The new file is written in the hidden ${WorkFiles.DIR} folder beside this one and renamed over it, so a torrent copy keeps seeding and Radarr/Sonarr never see a half-written file.")
        }
        val command = command(path, delayMs, listOf(measured) + others, shifted.map { it.specifier }.toSet())
        return AvSyncFix(
            what, experience, who, suggestion, command, shifted.map { it.label }, left.map { it.label },
            noCommandReason = if (command == null) noCommandReason(path, delayMs) else null,
        )
    }

    /**
     * FR-317-24 — why there is no command, for the branches a banner can actually render.
     *
     * A zero shift gets no sentence: `command` refuses it, but a flagged `OFFSET` is never 0 ms
     * (`EARLY_FRAMES`/`LATE_FRAMES`), so the state cannot be rendered and no wording is invented for it.
     * An mp4 always has a command, so its null is the same unreachable branch. A `drift` never reaches
     * here — its own `suggestion` explains the absence in full, and a second sentence would say it twice.
     */
    fun noCommandReason(path: String, delayMs: Long): String? {
        if (delayMs == 0L) return null
        val ext = extensionOf(path)
        return when {
            ext in MATROSKA_EXTENSIONS -> NO_COMMAND_NO_IDS
            ext in MP4_EXTENSIONS -> null
            else -> NO_COMMAND_CONTAINER
        }
    }

    /** A correlation as a reader can weigh it against the bar: two decimals, built by hand so no locale
     *  can turn the point into a comma. */
    fun twoDecimals(v: Double): String {
        val hundredths = kotlin.math.round(kotlin.math.abs(v) * 100).toLong()
        return "${if (v < 0) "−" else ""}${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    /** FR-317-11 — the command, or null for a container neither mkvmerge nor a stream-copy mp4 remux fits.
     *  [allAudio] is every audio stream of the file (the mp4 remux has to re-map all of them, in order);
     *  [shiftedSpecs] the ones that move. */
    fun command(path: String, delayMs: Long, allAudio: List<AvSyncTrack>, shiftedSpecs: Set<String>): String? {
        if (delayMs == 0L) return null
        val work = WorkFiles.pathFor(path, WorkFiles.Kind.AV_SHIFT)
        val prepare = WorkFiles.prepareCommand(path)
        val ext = extensionOf(path)
        return when {
            ext in MATROSKA_EXTENSIONS -> {
                val moved = allAudio.filter { it.specifier in shiftedSpecs }
                val ids = moved.mapNotNull { it.mkvId }
                // FR-317-22 — whole or absent. Without mkvmerge's own ids a `--sync` would move the wrong
                // track; with only some of them it would move fewer tracks than the prose above says it
                // moves, leaving a dub behind at the old timing and the page saying two different things.
                if (ids.isEmpty() || ids.size != moved.size) return null
                val tags = WorkFiles.pathFor(path, WorkFiles.Kind.AV_TAGS)
                val syncs = ids.joinToString(" ") { "--sync $it:$delayMs" }
                "$prepare && printf '<Tags><Tag><Simple><Name>$TAG</Name><String>$delayMs</String></Simple></Tag></Tags>' > ${q(tags)}" +
                    " && nice -n 19 ionice -c3 mkvmerge -q -o ${q(work)} --global-tags ${q(tags)} $syncs ${q(path)}" +
                    " && mv ${q(work)} ${q(path)} && rm ${q(tags)}"
            }
            ext in MP4_EXTENSIONS -> {
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
