package dev.jellystructure.subtitles

import dev.jellystructure.subtitles.SubtitleTiming.Fit
import kotlin.math.abs

/** Phase 273 (FR-273-1) — what a sidecar is, judged against its own video. The wire value is stored and sent
 *  to the admin only; nothing here reaches Ravilo (FR-273-17 only removes a track from a list). */
enum class Verdict(val wire: String) {
    IN_SYNC("in_sync"),
    OFF("off"),
    OFF_MID_FILE("off_mid_file"),
    OTHER_EPISODE("other_episode"),
    NOT_THIS_VIDEO("not_this_video"),
    LONGER_VIDEO("longer_video"),
    CANT_TELL("cant_tell");

    companion object { fun of(wire: String): Verdict? = entries.firstOrNull { it.wire == wire } }
}

/** FR-273-2 — which rung of the reference ladder a verdict came from. */
enum class RefKind(val wire: String) {
    EMBEDDED("embedded"), SIBLING("sibling"), SPEECH("speech");

    companion object { fun of(wire: String?): RefKind? = entries.firstOrNull { it.wire == wire } }
}

/** Why a sidecar has no verdict (FR-273-1's `cant_tell` reasons). */
object CantTell {
    const val NO_REFERENCE = "no_reference"
    const val REFERENCE_UNUSABLE = "reference_unusable"
    const val MONO_AUDIO = "mono_audio"
    const val TOO_FEW_CUES = "too_few_cues"
    const val WEAK = "weak"
}

data class Judgement(
    val verdict: Verdict,
    val reason: String? = null,
    val refKind: RefKind? = null,
    val fit: Fit? = null,
    /** The largest misalignment in the file were it left as it is (offset plus drift), for `off` verdicts. */
    val worstMs: Long? = null,
    /** For `other_episode`: the key of the video it belongs to. */
    val matchKey: String? = null,
    val chunks: List<SubtitleTiming.ChunkShift> = emptyList(),
    /** For `off`: the steady drift the chunks show on top of [fit]'s speed. Non-zero ⇒ a sync must search
     *  for the speed itself (Bazarr's golden-section search). */
    val driftMsPerHour: Long = 0,
)

/**
 * Phase 273 (FR-273-4) — the thresholds from the research, in one place. Against a subtitle reference: fits
 * when ρ ≥ 0.35, or ρ ≥ 0.2 with z ≥ 5; does not fit when ρ < 0.2 and z < 5. Against a speech track: fits when
 * z ≥ 5 and the own file beats every neighbour by 0.8; a neighbour wins by the same margin; speech alone never
 * concludes `not_this_video` (it condemned two right subtitles in the research).
 */
object VerdictRules {
    const val MIN_CUES = 40
    /** Under this many cues a minute an embedded track is a forced or signs track not flagged forced. */
    const val MIN_REF_CUES_PER_MIN = 3.0
    /** Offsets under this are in sync; from here a subtitle is `off`. */
    const val OFF_MS = 1_000L
    /** FR-273-17 — an `off` subtitle this far out anywhere in the file is not offered while Bazarr fixes it. */
    const val HIDE_OFF_MS = 2_000L

    fun fitsSubtitle(f: Fit): Boolean = f.rho >= 0.35 || (f.rho >= 0.2 && f.z >= 5.0)
    fun clearlyNotSubtitle(f: Fit): Boolean = f.rho < 0.2 && f.z < 5.0

    /** FR-273-2 rung 1 — whether an embedded track can serve as a reference. */
    fun usableReference(cues: List<Cue>, durationMs: Long?): Boolean {
        if (cues.size < MIN_CUES) return false
        val spanMs = durationMs?.takeIf { it > 0 } ?: (cues.last().endMs - cues.first().startMs)
        if (spanMs <= 0) return false
        return cues.size / (spanMs / 60_000.0) >= MIN_REF_CUES_PER_MIN
    }

    /** FR-273-4 — cues well past the end of the file: a compilation, a longer cut, or junk. */
    fun longerVideo(lastCueMs: Long, durationMs: Long?): Boolean =
        durationMs != null && durationMs > 0 && lastCueMs > durationMs * 1.10 + 60_000

    fun judge(
        own: Fit?,
        refKind: RefKind?,
        neighbours: Map<String, Fit>,
        firstCueMs: Long,
        lastCueMs: Long,
        durationMs: Long?,
        chunks: List<SubtitleTiming.ChunkShift> = emptyList(),
        noRefReason: String = CantTell.NO_REFERENCE,
    ): Judgement {
        if (longerVideo(lastCueMs, durationMs)) return Judgement(Verdict.LONGER_VIDEO, refKind = refKind, fit = own)
        if (own == null || refKind == null) return Judgement(Verdict.CANT_TELL, reason = noRefReason)

        if (refKind == RefKind.SPEECH) {
            val bestOther = neighbours.maxByOrNull { it.value.z }
            val margin = bestOther?.let { own.z - it.value.z }
            if (own.z >= 5.0 && (margin == null || margin >= 0.8)) return timing(own, refKind, firstCueMs, lastCueMs, emptyList())
            if (bestOther != null && bestOther.value.z >= 5.0 && bestOther.value.z - own.z >= 0.8)
                return Judgement(Verdict.OTHER_EPISODE, refKind = refKind, fit = own, matchKey = bestOther.key)
            return Judgement(Verdict.CANT_TELL, reason = CantTell.WEAK, refKind = refKind, fit = own)
        }

        if (fitsSubtitle(own)) return timing(own, refKind, firstCueMs, lastCueMs, chunks)
        val other = neighbours.entries
            .filter { fitsSubtitle(it.value) && it.value.rho > own.rho + 0.15 }
            .maxByOrNull { it.value.rho }
        if (other != null) return Judgement(Verdict.OTHER_EPISODE, refKind = refKind, fit = own, matchKey = other.key)
        if (clearlyNotSubtitle(own)) return Judgement(Verdict.NOT_THIS_VIDEO, refKind = refKind, fit = own)
        return Judgement(Verdict.CANT_TELL, reason = CantTell.WEAK, refKind = refKind, fit = own)
    }

    private fun timing(fit: Fit, refKind: RefKind, firstMs: Long, lastMs: Long, chunks: List<SubtitleTiming.ChunkShift>): Judgement {
        val summary = SubtitleTiming.summarise(chunks)
        val worst = maxOf(SubtitleTiming.worstMs(fit, firstMs, lastMs), summary?.worstMs ?: 0L)
        if (summary != null && !summary.linear)
            return Judgement(Verdict.OFF_MID_FILE, refKind = refKind, fit = fit, worstMs = worst, chunks = chunks)
        val drift = summary?.driftMsPerHour ?: 0L
        val scaled = abs(fit.scale - 1.0) > 1e-6
        if (scaled || abs(fit.shiftMs) >= OFF_MS || worst >= OFF_MS)
            return Judgement(Verdict.OFF, refKind = refKind, fit = fit, worstMs = worst, chunks = chunks,
                driftMsPerHour = if (abs(drift) >= OFF_MS) drift else 0L)
        return Judgement(Verdict.IN_SYNC, refKind = refKind, fit = fit, worstMs = worst, chunks = chunks)
    }

    /**
     * FR-273-17 — whether a viewer is offered this sidecar. Not for its video, timed for a longer one, off by
     * [HIDE_OFF_MS] or more anywhere, or doubted by the speech track alone (owner decision 2): not offered.
     * Everything else, including every `cant_tell` a subtitle reference could not settle, is offered.
     */
    fun offered(verdict: Verdict, reason: String?, refKind: RefKind?, worstMs: Long?): Boolean = when (verdict) {
        Verdict.NOT_THIS_VIDEO, Verdict.OTHER_EPISODE, Verdict.LONGER_VIDEO -> false
        Verdict.OFF, Verdict.OFF_MID_FILE -> (worstMs ?: 0L) < HIDE_OFF_MS
        Verdict.CANT_TELL -> !(reason == CantTell.WEAK && refKind == RefKind.SPEECH)
        Verdict.IN_SYNC -> true
    }
}
