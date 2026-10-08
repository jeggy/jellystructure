package dev.jellystructure.publish

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Publish_item
import dev.jellystructure.log.Logger
import dev.jellystructure.model.DashboardRow
import dev.jellystructure.model.PublishFieldDto
import dev.jellystructure.model.PublishItemDto
import dev.jellystructure.model.PublishListDto
import dev.jellystructure.model.PublishTargetDto
import dev.jellystructure.nowEpochSec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 307 (FR-307-2) — one thing a public database could be told, as proposed; the queue freezes [payload]. */
data class PublishProposal(
    val target: String,
    val kind: String,
    val subject: String,
    val label: String,
    val payload: String,
    val reason: String,
)

/** 307 (FR-307-4) — one send: [error] null = the target accepted it; [answer] is its reply, one line. */
data class PublishSendResult(val error: String?, val answer: String)

/** 307 — the one door out to a public database. Tests pass a fake; production uses [LivePublishSender]. */
fun interface PublishSender {
    suspend fun send(target: String, kind: String, payload: String): PublishSendResult
}

/**
 * 307 (FR-307-4) — the only place a public write is made. `PublishCallSiteTest` checks no other file calls
 * [dev.jellystructure.music.Lrclib.publishInstrumental].
 */
object LivePublishSender : PublishSender {
    override suspend fun send(target: String, kind: String, payload: String): PublishSendResult = when {
        target == PublishQueue.LRCLIB && kind == PublishQueue.INSTRUMENTAL ->
            dev.jellystructure.music.Lrclib.publishInstrumental(payload).let { PublishSendResult(it.error, it.answer) }
        else -> PublishSendResult("jellystructure doesn’t know how to publish this", "")
    }
}

/**
 * Phase 307 — nothing goes to a public database until someone presses Publish.
 *
 * - FR-307-1 — the queue lives in `publish_item`, so a restart loses nothing.
 * - FR-307-2 — [propose] adds items and stops; a subject already waiting, publishing, published or failed is not
 *   queued again, and a dismissed one only when its payload would differ (FR-307-5).
 * - FR-307-4 — [publish] moves items to `publishing` and wakes the one worker ([drain]): one item at a time, in
 *   queue order, the frozen payload sent byte for byte. A second press while it runs adds to the same run.
 * - FR-307-7 — nothing here runs on its own: no step, scan, schedule or AI job calls [publish].
 */
class PublishQueue(
    private val db: JellystructureDb,
    private val sender: PublishSender = LivePublishSender,
    /** FR-307-4 — after each answer (the album's History line for an LRCLIB song). */
    private val onOutcome: suspend (Publish_item, String?) -> Unit = { _, _ -> },
) {
    companion object {
        const val LRCLIB = "lrclib"
        const val INSTRUMENTAL = "instrumental"
        const val WAITING = "waiting"
        const val PUBLISHING = "publishing"
        const val PUBLISHED = "published"
        const val FAILED = "failed"
        const val DISMISSED = "dismissed"
        const val RECEIPT = 50L

        /** FR-307-3 — each target's name, its own page, and one plain sentence of what publishing means. */
        val TARGETS = mapOf(
            LRCLIB to PublishTargetDto(LRCLIB, "LRCLIB", "https://lrclib.net",
                "LRCLIB is a public lyrics database. Publishing adds an entry anyone can read, from this server’s address. It cannot be taken back from here."),
        )
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val q get() = db.publishQueries
    private val worker = Mutex()

    /** Where [publish] launches the worker (set in Main.kt); tests call [drain] themselves. */
    var scope: CoroutineScope? = null

    data class ProposeResult(val queued: Int, val already: Int, val declined: Int)

    /** FR-307-2 / FR-307-5 — add what is new, skip what is already live, and what the admin declined unless it changed. */
    fun propose(items: List<PublishProposal>, by: String): ProposeResult {
        var queued = 0; var already = 0; var declined = 0
        val now = nowEpochSec()
        db.transaction {
            for (p in items) {
                if (q.liveFor(p.target, p.kind, p.subject).executeAsOneOrNull() != null) { already++; continue }
                val last = q.lastDismissedFor(p.target, p.kind, p.subject).executeAsOneOrNull()
                if (last != null && last.payload == p.payload) { declined++; continue }
                q.insertItem(p.target, p.kind, p.subject, p.label, p.payload, p.reason, now, by)
                queued++
            }
        }
        return ProposeResult(queued, already, declined)
    }

    /** FR-307-1 / acceptance 6 — at start: an item a restart left `publishing` goes back to `waiting`, never sent twice. */
    suspend fun recover(): Int {
        val n = q.byState(PUBLISHING).executeAsList().size
        if (n > 0) { q.resetPublishing(); Logger.info("publish queue: $n item${if (n == 1) "" else "s"} left publishing by a restart back to waiting", "publish") }
        return n
    }

    fun item(id: Long): Publish_item? = q.byId(id).executeAsOneOrNull()
    fun inState(state: String): List<Publish_item> = q.byState(state).executeAsList()
    fun waitingCount(): Int = q.countState(WAITING).executeAsOne().toInt()

    /**
     * FR-307-4 — *Publish* / *Publish all n* / *Try again*: waiting or failed items become `publishing` and the worker
     * is woken. Answers how many were handed over (an item already publishing or published is not handed over twice).
     */
    fun publish(ids: Collection<Long>, by: String): Int {
        val now = nowEpochSec()
        var n = 0
        db.transaction {
            for (id in ids.distinct()) {
                val before = q.byId(id).executeAsOneOrNull()?.state ?: continue
                if (before != WAITING && before != FAILED) continue
                q.markPublishing(now, by, id)
                n++
            }
        }
        if (n > 0) scope?.launch { drain() }
        return n
    }

    /** FR-307-5 — *Don't publish* / *Don't publish any*. */
    fun dismiss(ids: Collection<Long>, by: String): Int {
        val now = nowEpochSec()
        var n = 0
        db.transaction {
            for (id in ids.distinct()) {
                val before = q.byId(id).executeAsOneOrNull()?.state ?: continue
                if (before != WAITING && before != FAILED) continue
                q.dismiss(now, by, id)
                n++
            }
        }
        return n
    }

    /** FR-307-5 — *Queue again* on a dismissed item; refused when the same subject has been queued since. */
    fun queueAgain(id: Long, by: String): Boolean {
        val it = q.byId(id).executeAsOneOrNull() ?: return false
        if (it.state != DISMISSED) return false
        if (q.liveFor(it.target, it.kind, it.subject).executeAsOneOrNull() != null) return false
        q.queueAgain(nowEpochSec(), by, id)
        return q.byId(id).executeAsOneOrNull()?.state == WAITING
    }

    /**
     * FR-307-4 — the one worker: sends the `publishing` items one at a time, oldest queued first, until none is left.
     * A second caller while it runs returns at once (the running one picks its items up); the re-check after the lock
     * is released closes the gap where a press lands just as the worker finishes.
     */
    suspend fun drain() {
        while (true) {
            if (!worker.tryLock()) return
            try {
                while (true) {
                    val next = q.nextPublishing().executeAsOneOrNull() ?: break
                    sendOne(next)
                }
            } finally { worker.unlock() }
            if (q.nextPublishing().executeAsOneOrNull() == null) return
        }
    }

    private suspend fun sendOne(item: Publish_item) {
        // Phase 310 (dev review item 5) — only this coroutine's OWN cancellation ends the drain; a foreign one (Ktor's Curl
        // engine can hand one call's cancellation to another) is a failed send like any other, so the press is not lost.
        val r = try { sender.send(item.target, item.kind, item.payload) } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            PublishSendResult("${TARGETS[item.target]?.name ?: item.target} could not be reached", e.message?.take(200) ?: "")
        } catch (e: Throwable) {
            PublishSendResult("${TARGETS[item.target]?.name ?: item.target} could not be reached", e.message?.take(200) ?: "")
        }
        val state = if (r.error == null) PUBLISHED else FAILED
        q.markOutcome(state, nowEpochSec(), (r.error ?: r.answer).take(300), item.id)
        Logger.info("publish ${item.target} #${item.id} (${item.label}): ${if (r.error == null) "published · ${r.answer}" else "failed · ${r.error}"}", "publish")
        runCatching { onOutcome(item, r.error) }
    }

    // ── what the Dashboard shows ──

    /** FR-307-3 — *Waiting to publish*: its own domain chip, for information, fixed here; absent at zero (FR-285-5). */
    fun dashboardRow(): DashboardRow? {
        val n = waitingCount().takeIf { it > 0 } ?: return null
        val names = inState(WAITING).map { it.target }.distinct().mapNotNull { TARGETS[it]?.name }
        return DashboardRow(
            id = "publish_waiting", domain = "public", severity = "info", label = "Waiting to publish",
            sentence = "Nothing goes to ${names.joinToString(" or ").ifBlank { "a public database" }} until you press Publish — see exactly what would be sent.",
            count = n, unit = "thing", fix = "here", action = "Review…", opens = "publish_queue",
        )
    }

    fun list(): PublishListDto {
        val waiting = inState(WAITING); val publishing = inState(PUBLISHING); val failed = inState(FAILED)
        val published = q.recentPublished(RECEIPT).executeAsList()
        val dismissed = inState(DISMISSED).sortedByDescending { it.decided_at ?: 0L }
        val targets = (waiting + publishing + failed + published + dismissed).map { it.target }.distinct().mapNotNull { TARGETS[it] }
        return PublishListDto(targets, waiting.map(::dto), publishing.map(::dto), failed.map(::dto), published.map(::dto), dismissed.map(::dto))
    }

    private fun dto(i: Publish_item) = PublishItemDto(
        id = i.id, target = i.target, kind = i.kind, subject = i.subject, label = i.label, reason = i.reason, state = i.state,
        payload = i.payload, fields = fields(i.target, i.kind, i.payload), queuedAt = i.queued_at, queuedBy = i.queued_by,
        decidedAt = i.decided_at, decidedBy = i.decided_by, sentAt = i.sent_at, answer = i.answer, attempts = i.attempts.toInt(),
    )

    /** FR-307-3 — the payload field by field, as it will be sent; an unknown shape shows its keys as they are. */
    fun fields(target: String, kind: String, payload: String): List<PublishFieldDto> {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return listOf(PublishFieldDto("Body", payload))
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        if (target == LRCLIB && kind == INSTRUMENTAL) {
            val len = s("duration")?.toDoubleOrNull()?.toInt()?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "—"
            val noLyrics = s("plainLyrics").isNullOrEmpty() && s("syncedLyrics").isNullOrEmpty()
            return listOf(
                PublishFieldDto("Track name", s("trackName") ?: "—"),
                PublishFieldDto("Artist", s("artistName") ?: "—"),
                PublishFieldDto("Album", s("albumName") ?: "—"),
                PublishFieldDto("Length", len),
                PublishFieldDto("Lyrics", if (noLyrics) "none — marks it instrumental" else "included"),
            )
        }
        return (o as JsonObject).map { (k, v) -> PublishFieldDto(k, (v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }
    }
}
