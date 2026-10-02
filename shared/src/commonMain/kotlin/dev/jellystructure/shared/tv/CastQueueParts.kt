package dev.jellystructure.shared.tv

import kotlinx.serialization.json.Json

// ─── R359 — a long queue still casts: no message over the Cast limit ─────────────────────────────
//
// A Cast v2 message is at most 64 KB; the device closes the connection on a bigger one (a 487-song LOAD from the Mac,
// 2026-10-02: closed 25 ms after it was sent). One rule here, for both senders and the receiver (FR-R359-6): every
// message on our side stays under [CAST_MESSAGE_BUDGET_BYTES]; a queue that does not fit goes as a window plus parts.

/** FR-R359-1 — what any one message may carry, encoded: a margin under the 64 KB frame. */
const val CAST_MESSAGE_BUDGET_BYTES = 48 * 1024

/**
 * FR-R359-1 — room a LOAD keeps for what is not [CastLoadData]: the media's content id, type and song card (title ·
 * artist · album · cover), the request's own fields and the frame around them. The card is one song's strings.
 */
const val CAST_LOAD_ENVELOPE_BYTES = 8 * 1024

/**
 * The size of [json] on the wire, counted the way the larger encoder writes it: UTF-8 bytes, plus one for every `/`
 * (Android's `JSONObject` writes it as `\/`, and every cover URL is full of them).
 */
fun castWireBytes(json: String): Int = json.encodeToByteArray().size + json.count { it == '/' }

/** A fresh name for one sender's queue (FR-R359-3): a part names the queue it belongs to. */
fun newCastQueueId(): String = kotlin.random.Random.nextLong().toULong().toString(36)

/** FR-R359-3 — the LOAD to send and the `queue_part` commands that follow it at once, in this order. */
data class CastLoadPlan(val load: CastLoadData, val parts: List<CastCommand>)

/**
 * FR-R359-1/3 — [data] as it can be sent. A film, or a queue that fits whole, is one LOAD as before (named [queueId]).
 * A longer queue's LOAD carries a window: the current song, the one before it (so *previous* works at once), as many
 * after it as fit, then as many before it as still fit; the rest follows in parts — first the songs after the window,
 * in order, then the ones before it, last part first — so the receiver's run only ever grows at one end or the other.
 * [loadBudget] is for the encoded [CastLoadData]; every part, encoded, is at most [partBudget].
 */
fun castLoadPlan(
    data: CastLoadData,
    queueId: String,
    json: Json = RaviloWireJsonWithDefaults,
    loadBudget: Int = CAST_MESSAGE_BUDGET_BYTES - CAST_LOAD_ENVELOPE_BYTES,
    partBudget: Int = CAST_MESSAGE_BUDGET_BYTES,
): CastLoadPlan {
    val tracks = data.tracks
    if (tracks.isEmpty()) return CastLoadPlan(data, emptyList())
    fun bytes(d: CastLoadData) = castWireBytes(json.encodeToString(CastLoadData.serializer(), d))
    val whole = data.copy(queueId = queueId, queueTotal = null, queueStart = 0)
    val n = tracks.size
    // Each song's share of the array: its JSON and a comma (one comma more than the array has, so the sums are upper bounds).
    val sizes = tracks.map { castWireBytes(json.encodeToString(CastTrackItem.serializer(), it)) + 1 }
    if (sizes.sum() + bytes(whole.copy(tracks = emptyList())) <= loadBudget && bytes(whole) <= loadBudget) return CastLoadPlan(whole, emptyList())

    val cur = data.currentIndex.coerceIn(0, n - 1)
    // The numbers the window will carry are at most n: written as n, the base is an upper bound too.
    val base = bytes(data.copy(tracks = emptyList(), queueId = queueId, queueTotal = n, queueStart = n, currentIndex = n))
    var used = base + sizes[cur]
    fun take(i: Int): Boolean = (used + sizes[i] <= loadBudget).also { if (it) used += sizes[i] }
    var lo = cur
    var hi = cur
    if (cur > 0 && take(cur - 1)) lo = cur - 1
    while (hi + 1 < n && take(hi + 1)) hi++
    while (lo - 1 >= 0 && take(lo - 1)) lo--
    fun window() = data.copy(tracks = tracks.subList(lo, hi + 1).toList(), currentIndex = cur - lo, queueId = queueId, queueTotal = n, queueStart = lo)
    var load = window()
    // The sums are upper bounds, so this only trims if a song's own JSON grew inside the list (it does not today).
    while (bytes(load) > loadBudget && hi > lo) { if (hi > cur) hi-- else lo++; load = window() }

    val partBase = castWireBytes(json.encodeToString(CastCommand.serializer(), CastCommand("queue_part", queueId = queueId, offset = n, tracks = emptyList())))
    val ranges = castChunks(sizes, hi + 1, n, partBase, partBudget, backward = false) +
        castChunks(sizes, 0, lo, partBase, partBudget, backward = true)
    val parts = ranges.map { r -> CastCommand("queue_part", queueId = queueId, offset = r.first, tracks = tracks.subList(r.first, r.last + 1).toList()) }
    return CastLoadPlan(load, parts)
}

/**
 * The runs of [from] until [until] that each fit [budget] with [base] around them ([sizes] per item); every run holds at
 * least one item. Forward from [from], or — [backward] — from [until] down, the run nearest [until] first.
 */
internal fun castChunks(sizes: List<Int>, from: Int, until: Int, base: Int, budget: Int, backward: Boolean): List<IntRange> {
    val out = mutableListOf<IntRange>()
    if (!backward) {
        var a = from
        while (a < until) {
            var used = base + sizes[a]
            var b = a + 1
            while (b < until && used + sizes[b] <= budget) { used += sizes[b]; b++ }
            out += a until b
            a = b
        }
    } else {
        var b = until
        while (b > from) {
            var used = base + sizes[b - 1]
            var a = b - 1
            while (a > from && used + sizes[a - 1] <= budget) { used += sizes[a - 1]; a-- }
            out += a until b
            b = a
        }
    }
    return out
}

/**
 * FR-R359-4 — the receiver's run of a queue that is still arriving ([tracks] starting at [start]) with one more part:
 * the new run and its start, or null when the part does not join the run at either end (yet — a part sent later can
 * arrive first). A part the run already holds changes nothing.
 */
fun castQueueAttach(tracks: List<CastTrackItem>, start: Int, offset: Int, part: List<CastTrackItem>): Pair<List<CastTrackItem>, Int>? = when {
    part.isEmpty() -> tracks to start
    offset == start + tracks.size -> (tracks + part) to start
    offset + part.size == start -> (part + tracks) to offset
    offset >= start && offset + part.size <= start + tracks.size -> tracks to start
    else -> null
}

/**
 * FR-R359-4 — every part in [waiting] that belongs to [queueId] and joins the run ([castQueueAttach]), for as long as
 * one more joins; the ones that joined (and any the run already held) leave [waiting]. The run and its start after.
 */
fun castQueueAttachAll(tracks: List<CastTrackItem>, start: Int, queueId: String?, waiting: MutableList<CastCommand>): Pair<List<CastTrackItem>, Int> {
    var run = tracks to start
    var joined = true
    while (joined) {
        joined = false
        val each = waiting.iterator()
        while (each.hasNext()) {
            val p = each.next()
            if (p.queueId != queueId) continue
            val next = castQueueAttach(run.first, run.second, p.offset ?: continue, p.tracks ?: emptyList()) ?: continue
            each.remove()
            if (next != run) { run = next; joined = true }
        }
    }
    return run
}

/** FR-R359-4 — where *next* goes in a queue whose run may still be arriving. */
sealed interface CastNext {
    data class To(val index: Int) : CastNext
    /** The song after this one (or, wrapping, the first) is in a part still on its way: wait for it, do not end. */
    data object Wait : CastNext
    /** The queue is over (repeat's rule). */
    data object End : CastNext
}

/**
 * FR-R359-4 — *next* from [index] in a run of [runSize] songs starting at [runStart] of a queue of [total] ([total]
 * null: the run is the whole queue). [repeat] is `off` · `all` · `one`; a song's own end ([byViewer] false) under `one`
 * plays it again. 286's rule, with one more answer: past the run's end while songs are still coming, wait.
 */
fun castNextIndex(index: Int, runSize: Int, runStart: Int, total: Int?, repeat: String, byViewer: Boolean): CastNext {
    val arriving = total != null && runSize < total
    return when {
        repeat == "one" && !byViewer -> CastNext.To(index)
        index < runSize - 1 -> CastNext.To(index + 1)
        arriving && runStart + runSize < total -> CastNext.Wait
        repeat == "all" || (repeat == "one" && byViewer && runSize > 0) -> if (arriving && runStart > 0) CastNext.Wait else CastNext.To(0)
        else -> CastNext.End
    }
}

/** FR-R359-4 — *previous* at the run's first song while the songs before it are still coming: wait for them. */
fun castPreviousWaits(index: Int, runStart: Int, total: Int?): Boolean = index <= 0 && total != null && runStart > 0

/**
 * FR-R359-5 — a receiver's [status] as it can be sent: itself when it fits [budget]; otherwise the status without its
 * queue, saying how many `queue_part` messages follow ([CastReceiverMessage.queueParts]), then those parts in order.
 * A sender older than R359 ignores the parts and keeps the queue it holds.
 */
fun castQueueReply(status: CastReceiverMessage, json: Json, budget: Int = CAST_MESSAGE_BUDGET_BYTES): List<CastReceiverMessage> {
    val queue = status.queue ?: return listOf(status)
    fun bytes(m: CastReceiverMessage) = castWireBytes(json.encodeToString(CastReceiverMessage.serializer(), m))
    if (bytes(status) <= budget) return listOf(status)
    val n = queue.size
    val sizes = queue.map { castWireBytes(json.encodeToString(CastTrackItem.serializer(), it)) + 1 }
    val base = bytes(CastReceiverMessage(type = "queue_part", queue = emptyList(), queueOffset = n, queueRev = status.queueRev, queueSize = n))
    val ranges = castChunks(sizes, 0, n, base, budget, backward = false)
    return listOf(status.copy(queue = null, queueParts = ranges.size)) + ranges.map { r ->
        CastReceiverMessage(type = "queue_part", queue = queue.subList(r.first, r.last + 1).toList(), queueOffset = r.first, queueRev = status.queueRev, queueSize = n)
    }
}

/** FR-R359-5 — [msg] with its queue only if it fits (the `ended` report: the queue there is a courtesy, never needed). */
fun castQueueIfFits(msg: CastReceiverMessage, json: Json, budget: Int = CAST_MESSAGE_BUDGET_BYTES): CastReceiverMessage =
    if (msg.queue == null || castWireBytes(json.encodeToString(CastReceiverMessage.serializer(), msg)) <= budget) msg else msg.copy(queue = null)

/**
 * FR-R359-5 — a sender putting a receiver's queue back together from its `queue_part` messages. A part of another
 * revision than the one being collected starts over with that one (the newest wins).
 */
class CastQueueAssembly {
    private var rev: Int? = null
    private var size = -1
    private val runs = HashMap<Int, List<CastTrackItem>>()

    /** One `queue_part`: the whole queue once every part of its revision is in, else null. */
    fun part(msg: CastReceiverMessage): List<CastTrackItem>? {
        val offset = msg.queueOffset ?: return null
        val run = msg.queue ?: return null
        val n = msg.queueSize ?: return null
        if (msg.queueRev != rev || n != size) { rev = msg.queueRev; size = n; runs.clear() }
        runs[offset] = run
        val out = ArrayList<CastTrackItem>(n)
        while (out.size < n) out += runs[out.size]?.takeIf { it.isNotEmpty() } ?: return null
        if (out.size != n) return null
        runs.clear(); rev = null; size = -1
        return out
    }

    /** The queue arrived whole another way (a status that carried it): nothing being collected is wanted any more. */
    fun reset() { runs.clear(); rev = null; size = -1 }
}

/** FR-R359-7 — the sender's log line for a music LOAD: `487 in the queue, 31 KB + 4 parts`. */
fun castLoadLog(songs: Int, loadBytes: Int, parts: Int): String =
    "$songs in the queue, ${(loadBytes + 512) / 1024} KB" + (if (parts > 0) " + $parts part${if (parts == 1) "" else "s"}" else "")
