package dev.jellystructure.ravilo.ui.screens

import kotlin.math.max
import kotlin.math.min

/**
 * DEB-2205 (FR-3, FR-4) — a direct play watches its own decode health and its buffer, and leaves for the ladder before the
 * picture stops. The pure rules, so the thresholds are unit tests and not a device session: [DecodeHealthMonitor] reads the
 * decoder (FR-3), [DirectPlayHandover] reads the buffer and the path (FR-4); [PlayerStore] acts on what they say.
 */

// ── FR-3 — the decode signals ──

/** FR-3 (a) — the window the dropped-frame share is measured over. */
internal const val DECODE_DROP_WINDOW_MS = 10_000L
/** FR-3 (a) — more than this share of the rendered frames dropped, over [DECODE_DROP_WINDOW_MS], is a decoder that cannot keep up. */
internal const val DECODE_DROP_SHARE = 0.02
/** A window that rendered fewer frames than this says nothing (a pause, a seek, a title card): never a signal. */
internal const val DECODE_MIN_RENDERED = 120L
/** FR-3 (b) — the picture frozen this long… */
internal const val DECODE_FROZEN_MS = 3_000L
/** …with at least this much buffered ahead (so the buffer is not what ran out). */
internal const val DECODE_FROZEN_BUFFER_MS = 5_000L

internal enum class DecodeSignal { DROPPED_FRAMES, FROZEN_WITH_BUFFER }

/**
 * FR-3 (a) — dropped frames above 2 % of the rendered ones over any 10 s window. Fed the renderer's cumulative counts every
 * tick; [reset] on a new item, a new stream and whenever the play is not playing (a pause or a seek would mix two plays).
 */
internal class DecodeHealthMonitor {
    private class Sample(val atMs: Long, val dropped: Long, val rendered: Long)
    private val samples = ArrayDeque<Sample>()

    fun reset() = samples.clear()

    /** [playing]: the first frame is out, the viewer wants it playing and the player is neither seeking nor starting. */
    fun observe(nowMs: Long, dropped: Long, rendered: Long, playing: Boolean): DecodeSignal? {
        if (!playing || rendered < (samples.lastOrNull()?.rendered ?: 0L) || dropped < (samples.lastOrNull()?.dropped ?: 0L)) {
            // Not playing, or the counters went back (a new decoder): start the window again from here.
            samples.clear()
            if (playing) samples.addLast(Sample(nowMs, dropped, rendered))
            return null
        }
        samples.addLast(Sample(nowMs, dropped, rendered))
        // Keep one sample at or before the window's start, so the window spans the full 10 s.
        while (samples.size > 2 && samples[1].atMs <= nowMs - DECODE_DROP_WINDOW_MS) samples.removeFirst()
        val oldest = samples.first()
        if (nowMs - oldest.atMs < DECODE_DROP_WINDOW_MS) return null
        val renderedIn = rendered - oldest.rendered
        val droppedIn = dropped - oldest.dropped
        return if (renderedIn >= DECODE_MIN_RENDERED && droppedIn > DECODE_DROP_SHARE * renderedIn) DecodeSignal.DROPPED_FRAMES else null
    }
}

/** FR-3 (b) — a freeze (what [FrozenMeter] reads) that has lasted 3 s with 5 s or more buffered ahead. */
internal fun frozenDecodeSignal(heldMs: Long, bufferAheadMs: Long): DecodeSignal? =
    if (heldMs >= DECODE_FROZEN_MS && bufferAheadMs >= DECODE_FROZEN_BUFFER_MS) DecodeSignal.FROZEN_WITH_BUFFER else null

// ── FR-4 — leave a direct play before it runs dry ──

/** FR-4 — the buffer ahead below which a falling buffer fires the rule. */
internal const val HANDOVER_FALLING_UNDER_MS = 15_000L
/** FR-4 — …that has fallen for this long. */
internal const val HANDOVER_FALLING_FOR_MS = 3_000L
/** FR-4 — the player's bandwidth estimate × this, under the file's stream bitrate, fires the rule… */
internal const val HANDOVER_ESTIMATE_SHARE = 0.8
/** FR-4 — …while under this much is buffered. */
internal const val HANDOVER_ESTIMATE_UNDER_MS = 30_000L
/** FR-4 — under this much buffered the restream happens at once. */
internal const val HANDOVER_IMMEDIATE_UNDER_MS = 4_000L
/** FR-4 — H = position + buffered − this: the stream is left while there is still picture in the buffer. */
internal const val HANDOVER_MARGIN_MS = 2_000L
/** FR-4 — the switch lands within this of H (the poll is coarser than the cut). */
internal const val HANDOVER_SWITCH_LEAD_MS = 250L
/** FR-4 — the rule reads a bandwidth estimate only when it rests on this many transfers and bytes (FR-309-13). */
internal const val HANDOVER_ESTIMATE_MIN_SAMPLES = 3
internal const val HANDOVER_ESTIMATE_MIN_BYTES = 2_000_000L

/** FR-4 — the target buffer in time: stock Media3's 50 s, which is more than the spec's ≥ 30 s. */
internal const val DIRECT_BUFFER_TARGET_MS = 50_000L
/** FR-4 — the buffer the thresholds are written for: 30 s. A device that holds less scales them to what it can hold. */
internal const val DIRECT_BUFFER_REFERENCE_MS = 30_000L

/**
 * FR-4 — how many ms of a stream of [streamBps] the device can buffer: the memory class (MB of heap) × 0.25 as bytes, over the
 * stream's byte rate, never more than [DIRECT_BUFFER_TARGET_MS]. 0 streamBps (unknown, or not a direct play) is the target.
 */
internal fun directBufferMs(memoryClassMb: Int, streamBps: Long): Long {
    if (streamBps <= 0 || memoryClassMb <= 0) return DIRECT_BUFFER_TARGET_MS
    val bytes = memoryClassMb.toDouble() * 1024.0 * 1024.0 * 0.25
    return min(DIRECT_BUFFER_TARGET_MS, (bytes * 8_000.0 / streamBps).toLong())
}

/** FR-4 — the share every threshold keeps: 1 where the device holds the 30 s the spec writes them for, less where it cannot. */
internal fun handoverScale(bufferMs: Long): Double = (bufferMs.toDouble() / DIRECT_BUFFER_REFERENCE_MS).coerceIn(0.05, 1.0)

/** What the rule decided. */
internal sealed interface HandoverDecision {
    /** Why: `falling` (the buffer under 15 s and falling for 3 s) or `estimate` (the path's estimate × 0.8 under the file's bitrate). */
    val reason: String

    /** FR-4's immediate form: under 4 s buffered, restream at the current position at once. */
    data class Immediate(override val reason: String) : HandoverDecision

    /** FR-4: ask for the ladder at [atMs] (H = position + buffered − 2 s); keep the direct stream until the playhead gets there. */
    data class Ahead(val atMs: Long, override val reason: String) : HandoverDecision
}

/**
 * FR-4 — the rule, one per direct play. Fired once: the first tick it holds returns the decision, every later one null, until [reset].
 *
 * It fires when either is true:
 *  - the buffer ahead is under 15 s and has fallen for 3 s (it was higher 3 s ago and never rose by more than a second at a time);
 *  - the player's real bandwidth estimate × 0.8 is under the file's stream bitrate while under 30 s is buffered.
 * Both thresholds, and the 4 s of the immediate form, scale by [scale] on a device that cannot hold 30 s at this bitrate.
 */
internal class DirectPlayHandover(private val streamBps: Long, private val scale: Double = 1.0) {
    private class Sample(val atMs: Long, val aheadMs: Long)
    private val ahead = ArrayDeque<Sample>()
    private var fired = false

    fun reset() { ahead.clear(); fired = false }

    /**
     * [playing]: first frame out, the viewer wants it playing, not seeking. [estimateBps]: the player's bandwidth estimate where it
     * rests on enough transfers (see [HANDOVER_ESTIMATE_MIN_SAMPLES]); null where it is still guessing.
     */
    fun observe(nowMs: Long, positionMs: Long, bufferAheadMs: Long, estimateBps: Long?, playing: Boolean): HandoverDecision? {
        if (fired || streamBps <= 0) return null
        if (!playing || bufferAheadMs < 0) { ahead.clear(); return null }
        ahead.addLast(Sample(nowMs, bufferAheadMs))
        while (ahead.size > 1 && ahead[1].atMs <= nowMs - HANDOVER_FALLING_FOR_MS) ahead.removeFirst()
        val reason = when {
            fallingUnder(bufferAheadMs, nowMs) -> "falling"
            estimateBps != null && estimateBps * HANDOVER_ESTIMATE_SHARE < streamBps && bufferAheadMs < HANDOVER_ESTIMATE_UNDER_MS * scale -> "estimate"
            else -> null
        } ?: return null
        fired = true
        if (bufferAheadMs < HANDOVER_IMMEDIATE_UNDER_MS * scale) return HandoverDecision.Immediate(reason)
        val margin = min(HANDOVER_MARGIN_MS.toDouble(), bufferAheadMs / 2.0).toLong()
        return HandoverDecision.Ahead(positionMs + bufferAheadMs - margin, reason)
    }

    private fun fallingUnder(bufferAheadMs: Long, nowMs: Long): Boolean {
        if (bufferAheadMs >= HANDOVER_FALLING_UNDER_MS * scale) return false
        val first = ahead.first()
        if (nowMs - first.atMs < HANDOVER_FALLING_FOR_MS - 250) return false    // not yet 3 s of history
        if (first.aheadMs - bufferAheadMs < 500) return false                   // no net fall
        // Never rising by more than a second between two looks: a chunk landing breaks "falling".
        return ahead.zipWithNext().none { (a, b) -> b.aheadMs - a.aheadMs > 1_000 }
    }
}

/**
 * FR-4 — the moment to cut to the ladder stream for a hand-over to [handoverAtMs]: the playhead is within
 * [HANDOVER_SWITCH_LEAD_MS] of it. The stream starts at the later of H and where the playhead is: the encoder's first segment is H's.
 */
internal fun handoverSwitchDue(positionMs: Long, handoverAtMs: Long): Boolean = positionMs >= handoverAtMs - HANDOVER_SWITCH_LEAD_MS

internal fun handoverStartMs(positionMs: Long, handoverAtMs: Long): Long = max(handoverAtMs, positionMs)

/**
 * FR-4 — a hand-over waiting for its switch is dropped when the viewer left the place it was made for: a seek back before where it
 * was asked (by more than 1.5 s), or a seek forward past H by more than 30 s. A ticket that arrives after H is not stale — it
 * switches at once ("the first segment that exists").
 */
internal fun handoverStale(positionMs: Long, askedAtPositionMs: Long, handoverAtMs: Long): Boolean =
    positionMs < askedAtPositionMs - 1_500 || positionMs > handoverAtMs + 30_000

/** FR-4 (and FR-3) — the one log line a hand-over writes, like FR-5.4's: [what] left, with how much was buffered, and where it landed. */
internal fun handoverLine(what: String, bufferedMs: Long, landed: String): String =
    "recovery: $what with ${bufferedMs / 1000} s buffered → $landed (DEB-2205 FR-4)"
