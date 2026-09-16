package dev.jellystructure.model

import kotlin.math.abs

/**
 * Phase 223 (FR-223-6) — the neighbour rule and the drag arithmetic of the segment editor, written ONCE
 * here so the clamp the operator feels under the pointer and the 422 the server would otherwise answer
 * are the same function ("one predicate, two consumers", the shape 185 FR-185-6 set). Pure; no DOM, no DB.
 *
 * Markers of different kinds are half-open intervals that must not intersect. Touching (end == next
 * start) is fine. Open-ended credits run to the end of the file. The stinger is a point inside the
 * credits by definition and never counts against them. A write is judged against the unit's other
 * markers AS STORED, and refused only when its overlap with one of them is LARGER after than before —
 * so a new overlap and a worsened one are refused, while any write that shrinks or clears an overlap
 * detection got wrong (775 credits rows start inside the intro on production) goes through.
 */
object SegmentEditRules {
    const val MIN_LENGTH_MS = 100L

    /** A file whose length is unknown: an open-ended credits marker still runs "to the end", which is
     *  then simply very far away. */
    const val UNBOUNDED_MS = Long.MAX_VALUE / 4

    val ORDER = listOf("recap", "intro", "preview", "credits", "stinger")

    data class Marker(val kind: String, val startMs: Long, val endMs: Long?)

    data class SnapTarget(val ms: Long, val label: String)

    /** Why a write was refused — the long form is the 422 body and the toast, the short form the bubble. */
    data class Refusal(val kind: String, val otherKind: String, val otherBefore: Boolean, val atMs: Long) {
        val message: String
            get() = if (otherBefore) "${label(kind)} can't start before the ${label(otherKind)} ${verb(otherKind, "end")} (${fmt(atMs)})"
            else "${label(kind)} can't end after the ${label(otherKind)} ${verb(otherKind, "start")} (${fmt(atMs)})"
        val stopsAt: String get() = "stops at ${label(otherKind)} ${if (otherBefore) "end" else "start"}"
    }

    fun label(kind: String): String = when (kind) {
        "recap" -> "recap"; "intro" -> "intro"; "preview" -> "next time"; "credits" -> "credits"; "stinger" -> "after-credits"
        else -> kind
    }

    /** "the credits start" but "the intro starts" — credits are plural in English. */
    fun verb(kind: String, base: String): String = if (kind == "credits") base else base + "s"

    fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** The half-open interval a marker occupies on a file of [durationMs]. Open-ended credits run to the
     *  end; any other marker without an end is a point and occupies one millisecond, so "inside" and
     *  "touching" are different things. */
    fun interval(m: Marker, durationMs: Long): Pair<Long, Long> {
        val end = when {
            m.endMs != null -> maxOf(m.endMs, m.startMs + 1)
            m.kind == "credits" -> maxOf(ceiling(durationMs), m.startMs + 1)
            else -> m.startMs + 1
        }
        return m.startMs to end
    }

    /** Milliseconds two markers share. Same kind, or the stinger against the credits: never. */
    fun overlapMs(a: Marker, b: Marker, durationMs: Long): Long {
        if (a.kind == b.kind) return 0L
        if ((a.kind == "stinger" && b.kind == "credits") || (a.kind == "credits" && b.kind == "stinger")) return 0L
        val (aStart, aEnd) = interval(a, durationMs)
        val (bStart, bEnd) = interval(b, durationMs)
        return (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)
    }

    /** Null when the write is acceptable. [before] is the marker as stored (null when it is new). */
    fun refusal(before: Marker?, after: Marker, others: List<Marker>, durationMs: Long): Refusal? {
        for (o in others) {
            if (o.kind == after.kind) continue
            val was = before?.let { overlapMs(it, o, durationMs) } ?: 0L
            val now = overlapMs(after, o, durationMs)
            if (now > was) {
                val (oStart, oEnd) = interval(o, durationMs)
                // Which side collided is judged from where the marker RESTED, not where the pointer is: a
                // credits marker dragged clean past the intro's start still "can't start before the intro ends".
                val otherBefore = oStart < (before?.startMs ?: after.startMs)
                return Refusal(after.kind, o.kind, otherBefore, if (otherBefore) oEnd else oStart)
            }
        }
        return null
    }

    fun acceptEdit(before: Marker?, after: Marker, others: List<Marker>, durationMs: Long): String? =
        refusal(before, after, others, durationMs)?.message

    private fun ceiling(durationMs: Long): Long = if (durationMs > 0) durationMs else UNBOUNDED_MS

    /** The marker slid so that it starts at [toStartMs], its length preserved, kept inside the file. An
     *  endless marker slides its start alone. */
    fun slid(m: Marker, toStartMs: Long, durationMs: Long): Marker {
        val len = m.endMs?.let { it - m.startMs }
        val maxStart = (ceiling(durationMs) - (len ?: 0L)).coerceAtLeast(0L)
        val s = toStartMs.coerceIn(0L, maxStart)
        return m.copy(startMs = s, endMs = len?.let { s + it })
    }

    /** The marker with its start moved to [toStartMs] and its end kept — never inverted, never off the file. */
    fun withStart(m: Marker, toStartMs: Long, durationMs: Long): Marker {
        val max = m.endMs?.let { (it - MIN_LENGTH_MS).coerceAtLeast(0L) } ?: ceiling(durationMs)
        return m.copy(startMs = toStartMs.coerceIn(0L, max))
    }

    /** The marker with its end moved to [toEndMs] and its start kept. */
    fun withEnd(m: Marker, toEndMs: Long, durationMs: Long): Marker {
        val min = m.startMs + MIN_LENGTH_MS
        return m.copy(endMs = toEndMs.coerceIn(min, maxOf(ceiling(durationMs), min)))
    }

    /**
     * The furthest value between [from] (the resting position, always acceptable) and [to] (where the
     * pointer is) at which [propose] still passes the neighbour rule, plus the refusal one step beyond
     * it — the thing the bubble names. Overlap grows monotonically along a single edge move or a slide,
     * so a binary search over the millisecond range is exact and cheap (≤ ~40 evaluations).
     */
    fun clampMove(before: Marker, others: List<Marker>, durationMs: Long, from: Long, to: Long, propose: (Long) -> Marker): Pair<Long, Refusal?> {
        if (from == to) return to to null
        refusal(before, propose(to), others, durationMs) ?: return to to null
        var lo = from
        var hi = to
        while (abs(hi - lo) > 1) {
            val mid = lo + (hi - lo) / 2
            if (refusal(before, propose(mid), others, durationMs) == null) lo = mid else hi = mid
        }
        return lo to refusal(before, propose(hi), others, durationMs)
    }

    /** The nearest target within [radiusMs] of [ms]; earlier entries win ties, so callers order by
     *  priority (evidence before the playhead). */
    fun nearestSnap(ms: Long, targets: List<SnapTarget>, radiusMs: Long): SnapTarget? {
        var best: SnapTarget? = null
        var bestD = radiusMs + 1
        for (t in targets) {
            val d = abs(t.ms - ms)
            if (d <= radiusMs && d < bestD) { best = t; bestD = d }
        }
        return best
    }

    /** The whole second nearest to [ms] when it is within [radiusMs]; the zoom strip's grid. */
    fun wholeSecondSnap(ms: Long, radiusMs: Long): SnapTarget? {
        val ws = ((ms + 500) / 1000) * 1000
        return if (abs(ws - ms) <= radiusMs) SnapTarget(ws, "") else null
    }
}
