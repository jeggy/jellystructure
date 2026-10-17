package dev.jellystructure.support

import dev.jellystructure.auth.SeatTokenCheck
import dev.jellystructure.config.AppConfig
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.model.SupportAdminList
import dev.jellystructure.model.SupportAiTranscript
import dev.jellystructure.model.SupportAiTurn
import dev.jellystructure.model.SupportAdminMessage
import dev.jellystructure.model.SupportAdminThread
import dev.jellystructure.model.SupportAdminTicket
import dev.jellystructure.model.SupportLog
import dev.jellystructure.model.SupportLogEntry
import dev.jellystructure.model.SupportSummary
import dev.jellystructure.shared.tv.SupportMessageView
import dev.jellystructure.shared.tv.SupportThread
import dev.jellystructure.shared.tv.SupportTicketList
import dev.jellystructure.shared.tv.SupportTicketRow
import dev.jellystructure.shared.tv.SupportUnread
import dev.jellystructure.shared.tv.SupportWire
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Phase 320 (FR-320-2 … FR-320-17) — the support lane's store and rules. The routes are thin: every decision (who may
 * see a ticket, who owes the next message, what a limit is) is here, so the tests drive it without a socket.
 *
 * Three things are deliberate:
 *  - **The viewer is an argument, never a lookup** (FR-320-3). A ticket that is not [userId]'s is "not found" in
 *    every method, so a number reveals nothing about whether it exists.
 *  - **Availability is checked by the routes, not here** (FR-320-1). Reads and the retention pass must keep working
 *    while the lane is unavailable; only the pushes are gated, in [changed].
 *  - **Nothing is deleted when the lane becomes unavailable.** The only deleters are the viewer's own `DELETE`, an
 *    admin's `DELETE`, and the retention pass.
 */
class SupportService(
    private val db: JellystructureDb,
    private val config: () -> AppConfig,
    /** The viewer's resolved `ui_language` (R162), at the moment they file. */
    private val languageOf: (userId: String) -> String = { "en" },
    /** A viewer's Jellyfin user name, for the admin's rows. Falls back to the id. */
    private val nameOf: (userId: String) -> String? = { null },
    private val clock: () -> Long = { dev.jellystructure.nowEpochSec() },
) {
    private val q get() = db.supportQueries

    /** DEB-1508 (FR-1) — Hervør's seat token. Its liveness is half of the effective switch ([hervorEffective]). */
    val seatTokens = SupportSeatTokens(db, clock)
    private val json = Json { ignoreUnknownKeys = true }
    private val argsSerializer = ListSerializer(String.serializer())

    /** `support_changed` to that viewer's opted-in sockets (FR-320-10). Wired to [dev.jellystructure.tv.TvEventBus]. */
    var pushViewer: (userId: String, ticket: Long) -> Unit = { _, _ -> }
    /** `support_ticket` on `/ws` (FR-320-10). Wired to the broadcaster. */
    var pushAdmin: (JobEvent) -> Unit = {}
    /** 321 (FR-321-3) — a ticket `AI Agent` holds was filed, or the viewer wrote on it: queue it. Wired to [dev.jellystructure.ai.AiJobs]. */
    var askAgent: (ticket: Long) -> Unit = {}
    /** 321 (FR-320-17) — a ticket was deleted: what the AI rail still holds of it goes too. */
    var ticketDeleted: (ticket: Long) -> Unit = {}
    /** 321 (FR-321-3) — whether a ticket is queued for, or out with, `AI Agent`: the server state behind `aiComposing`. */
    var agentComposing: (ticket: Long) -> Boolean = { false }

    /** FR-320-1 — `[support] enabled` and a non-blank `[ai] api_key`; nothing else. */
    val available: Boolean get() = config().let { it.support.availableWith(it.ai) }

    /** 321 (FR-321-1) — `AI Agent` is on: AI, a key, `[ai.support]` and `[support] enabled`. While it is, a new ticket starts with it. */
    val agentActive: Boolean get() = config().let { it.ai.supportActive(it.support) }

    /**
     * DEB-1508 (FR-8) — Hervør is on: `[support] hervor_enabled` **and** a live seat token. Every reader of the switch reads
     * this (the forward target, who owes the next message, the summary), so a hand edit of `config.toml` with no token
     * cannot send a ticket to someone who cannot answer.
     */
    val hervorEffective: Boolean get() = limits.hervorEnabled && seatTokens.hasLive

    sealed class Outcome<out T> {
        data class Ok<T>(val value: T, val created: Boolean = false) : Outcome<T>()
        /** [status] is the HTTP status; [code] a stable machine word; [reason] a line a client may show. */
        data class Refused(val status: Int, val code: String, val reason: String, val retryAfterSeconds: Long? = null) : Outcome<Nothing>()
    }

    private val limits get() = config().support
    private fun now() = clock()

    // ── Viewer reads (FR-320-8, FR-320-9) ────────────────────────────────────────────────────────────────────────────

    fun list(userId: String): SupportTicketList =
        SupportTicketList(q.ticketsOf(userId).executeAsList().map { viewerRow(it) })

    fun thread(userId: String, number: Long): SupportThread? {
        val t = own(userId, number) ?: return null
        return viewerThread(t)
    }

    fun unread(userId: String): SupportUnread =
        SupportUnread(q.ticketsOf(userId).executeAsList().count { viewerUnreadOf(it) > 0 })

    /** FR-320-9 — the marker only ever moves forward, and never past what exists. */
    fun markRead(userId: String, number: Long, upTo: Long): Boolean {
        val t = own(userId, number) ?: return false
        val clamped = minOf(upTo, q.lastMessageId(t.id).executeAsOne())
        if (clamped > 0) q.moveViewerRead(clamped, t.id)
        return true
    }

    // ── Viewer writes (FR-320-4 … FR-320-7, FR-320-17) ───────────────────────────────────────────────────────────────

    fun file(userId: String, platform: String?, deviceId: String, body: String): Outcome<SupportThread> {
        platformRefusal(platform)?.let { return it }
        bodyRefusal(body)?.let { return it }
        val t = now()
        val since = t - DAY
        if (q.ticketsSince(userId, since).executeAsOne() >= limits.ticketsPerDay) {
            return limited("tickets_per_day", q.oldestTicketSince(userId, since).executeAsOneOrNull()?.MIN, t)
        }
        messageLimit(userId, t)?.let { return it }
        val language = languageOf(userId).ifBlank { "en" }
        // 321 (FR-321-2) — while `AI Agent` is on it holds a new ticket; otherwise nothing differs from phase 320.
        val agent = agentActive
        val id = db.transactionWithResult {
            q.insertTicket(userId, STATE_OPEN, if (agent) HOLDER_AI_AGENT else HOLDER_ADMIN, if (agent) OWES_AI_AGENT else OWES_ADMIN, t, language, t, t)
            val id = q.lastInsertId().executeAsOne()
            q.insertMessage(id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_VIEWER, null, body, null, null, VISIBLE, platform, deviceId, t, ACTOR_VIEWER, null)
            q.insertMessage(id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_RECEIVED, args(id.toString()), VISIBLE, null, null, t, ACTOR_VIEWER, null)
            id
        }
        asked(id)
        changed(id, viewerVisible = true)
        return Outcome.Ok(viewerThread(q.ticket(id).executeAsOne()), created = true)
    }

    fun post(userId: String, number: Long, platform: String?, deviceId: String, body: String): Outcome<SupportThread> {
        platformRefusal(platform)?.let { return it }
        val tk = own(userId, number) ?: return notFound()
        bodyRefusal(body)?.let { return it }
        val t = now()
        messageLimit(userId, t)?.let { return it }
        db.transaction {
            q.insertMessage(tk.id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_VIEWER, null, body, null, null, VISIBLE, platform, deviceId, t, null, null)
            viewerSpoke(tk, t)
        }
        asked(tk.id)
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(viewerThread(q.ticket(tk.id).executeAsOne()))
    }

    /** FR-320-7 — allowed from every platform, the TV included. A repeat inside the window is a 200, not an error. */
    fun sameHere(userId: String, number: Long, platform: String?, deviceId: String): Outcome<SupportThread> {
        val tk = own(userId, number) ?: return notFound()
        val t = now()
        if (q.sameHereSince(tk.id, deviceId, t - SAME_HERE_WINDOW).executeAsOne() > 0) {
            return Outcome.Ok(viewerThread(tk))
        }
        db.transaction {
            q.insertMessage(tk.id, SupportWire.KIND_SAME_HERE, SupportWire.AUTHOR_VIEWER, null, null, null, null, VISIBLE, platform, deviceId, t, null, null)
            viewerSpoke(tk, t)
        }
        asked(tk.id)
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(viewerThread(q.ticket(tk.id).executeAsOne()))
    }

    fun deleteOwn(userId: String, number: Long): Boolean {
        val tk = own(userId, number) ?: return false
        delete(tk.id)
        return true
    }

    // ── Admin (FR-320-12, FR-320-14) ─────────────────────────────────────────────────────────────────────────────────

    class AdminFilter(
        val state: String? = null, val holder: String? = null, val owes: String? = null,
        val flagged: Boolean = false, val overdue: Boolean? = null,
    )

    fun adminList(f: AdminFilter = AdminFilter()): SupportAdminList = SupportAdminList(
        q.allTickets().executeAsList().filter { (it.flagged == 1L) == f.flagged }.matching(f, seat = false),
    )

    private fun List<dev.jellystructure.db.Support_ticket>.matching(f: AdminFilter, seat: Boolean): List<SupportAdminTicket> =
        filter { f.state == null || it.state == f.state }
            .filter { f.holder == null || it.holder == f.holder }
            .filter { f.owes == null || it.owes == f.owes }
            .map { adminRow(it, seat) }
            .filter { f.overdue == null || it.overdue == f.overdue }

    fun adminThread(number: Long): SupportAdminThread? = q.ticket(number).executeAsOneOrNull()?.let { adminThreadOf(it) }

    fun summary(): SupportSummary {
        val t = now()
        val live = q.allTickets().executeAsList().filter { it.flagged == 0L }
        return SupportSummary(
            open = live.count { it.state != STATE_RESOLVED },
            owedByAdmin = live.count { it.owes == OWES_ADMIN },
            overdue = live.count { isOverdue(it.owes, it.owed_since, t) },
            laneOpen = limits.enabled,
            hervorEnabled = hervorEffective,
            owedByHervor = live.count { it.owes == OWES_HERVOR },
        )
    }

    /** An admin's reply: a `MESSAGE` by `ADMIN`, and nothing is owed until the viewer next writes (FR-320-12). */
    fun reply(number: Long, adminName: String, body: String): Outcome<SupportAdminThread> {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return notFound()
        bodyRefusal(body)?.let { return it }
        val t = now()
        db.transaction {
            q.insertMessage(tk.id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_ADMIN, adminName, body, null, null, VISIBLE, null, null, t, null, null)
            q.setOwes(OWES_NONE, t, t, tk.id)
        }
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne()))
    }

    /**
     * FR-320-12/13 — moves the ticket. The viewer gets one `FORWARDED` line carrying the viewer-wire holder only; the
     * English note is an `ADMIN_ONLY` line. [owes] must be `ADMIN` or `HERVOR`: a seat that may not answer can hold a
     * ticket, but it never owes the viewer the next message.
     */
    fun forward(number: Long, adminName: String, holder: String, seat: String?, owes: String, note: String): Outcome<SupportAdminThread> {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return notFound()
        if (holder !in FORWARD_HOLDERS) return bad("holder", "holder must be ADMIN, HERVOR or SEAT")
        if (owes !in FORWARD_OWES) return bad("owes", "owes must be ADMIN or HERVOR")
        val seatName = seat?.trim()?.takeIf { it.isNotEmpty() }
        if (holder == HOLDER_SEAT && (seatName == null || seatName.length > MAX_SEAT_CHARS)) {
            return bad("seat", "a seat holder needs a seat name of at most $MAX_SEAT_CHARS characters")
        }
        if (note.codePointCount() > MAX_NOTE_CHARS) return bad("note", "note is longer than $MAX_NOTE_CHARS characters")
        val t = now()
        db.transaction {
            q.setRouting(tk.state, holder, if (holder == HOLDER_SEAT) seatName else null, owes, t, t, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_FORWARDED, args(viewerHolder(holder)), VISIBLE, null, null, t, ACTOR_ADMIN, adminName)
            if (note.isNotBlank()) {
                q.insertMessage(tk.id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_ADMIN, adminName, note, null, null, ADMIN_ONLY, null, null, t, null, null)
            }
        }
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne()))
    }

    /**
     * FR-320-12 — `RESOLVED` owes nothing, so a resolved ticket is never overdue. Leaving `RESOLVED` hands the
     * obligation back to whoever holds it, by the same rule a viewer's message uses. Same state = no line, no push.
     */
    fun setState(number: Long, state: String, adminName: String? = null): Outcome<SupportAdminThread> {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return notFound()
        if (state !in STATES) return bad("state", "state must be OPEN, IN_PROGRESS or RESOLVED")
        if (state == tk.state) return Outcome.Ok(adminThreadOf(tk))
        val t = now()
        db.transaction {
            val owes = when {
                state == STATE_RESOLVED -> OWES_NONE
                tk.state == STATE_RESOLVED -> owesFor(tk.holder)
                else -> tk.owes
            }
            val since = if (owes == tk.owes) tk.owed_since else t
            q.setRouting(state, tk.holder, tk.holder_seat, owes, since, t, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_STATE_CHANGED, args(state), VISIBLE, null, null, t, ACTOR_ADMIN, adminName)
        }
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne()))
    }

    fun setClass(number: Long, ticketClass: String, adminName: String? = null): Outcome<SupportAdminThread> {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return notFound()
        if (ticketClass !in CLASSES) return bad("class", "class must be BUG, REQUEST, QUESTION, ACCOUNT or OTHER")
        if (ticketClass == tk.class_) return Outcome.Ok(adminThreadOf(tk))
        val t = now()
        db.transaction {
            q.setClass(ticketClass, t, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_CLASSIFIED, args(ticketClass), VISIBLE, null, null, t, ACTOR_ADMIN, adminName)
        }
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne()))
    }

    /** FR-320-12 — an `ADMIN_ONLY` line and nothing the viewer can see: no push to the viewer, no unread. */
    fun setFlag(number: Long, flagged: Boolean, adminName: String? = null): Outcome<SupportAdminThread> {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return notFound()
        if ((tk.flagged == 1L) == flagged) return Outcome.Ok(adminThreadOf(tk))
        val t = now()
        db.transaction {
            q.setFlag(if (flagged) 1L else 0L, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, if (flagged) EVENT_FLAGGED else EVENT_UNFLAGGED, null, ADMIN_ONLY, null, null, t, ACTOR_ADMIN, adminName)
        }
        changed(tk.id, viewerVisible = false)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne()))
    }

    fun adminMarkRead(number: Long, upTo: Long): Boolean {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return false
        val clamped = minOf(upTo, q.lastMessageId(tk.id).executeAsOne())
        if (clamped > 0) q.moveAdminRead(clamped, tk.id)
        changed(tk.id, viewerVisible = false)
        return true
    }

    fun adminDelete(number: Long): Boolean {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return false
        delete(tk.id)
        return true
    }

    /** FR-320-17 — tickets `RESOLVED` with no activity for `retention_days`. Runs whether or not the lane is available. */
    fun retentionPass(): Int {
        val days = limits.retentionDays
        if (days <= 0) return 0
        val ids = q.staleResolved(now() - days * DAY).executeAsList()
        ids.forEach { delete(it, push = false) }
        return ids.size
    }

    // ── AI Agent (phase 321) ─────────────────────────────────────────────────────────────────────────────────────────

    /** One entry of the thread as `AI Agent` reads it — `VIEWER`-visible only, and never an author's name. */
    class AgentEntry(val id: Long, val author: String?, val kind: String, val body: String?, val event: String?, val args: List<String>)

    /** The ticket as the request is built from it. [ownerId] is for the per-viewer count and never leaves the server. */
    class AgentTicket(
        val number: Long, val ownerId: String, val language: String, val ticketClass: String?, val holder: String, val owes: String,
        val flagged: Boolean, val lastViewerMessageId: Long, val entries: List<AgentEntry>,
    )

    fun agentTicket(number: Long): AgentTicket? = q.ticket(number).executeAsOneOrNull()?.let { tk ->
        AgentTicket(
            number = tk.id, ownerId = tk.owner_user_id, language = tk.viewer_language, ticketClass = tk.class_, holder = tk.holder,
            owes = tk.owes, flagged = tk.flagged == 1L, lastViewerMessageId = q.lastViewerMessageId(tk.id).executeAsOne(),
            entries = q.messages(tk.id).executeAsList().filter { it.visibility == VISIBLE }.map {
                AgentEntry(it.id, it.author, it.kind, it.body, it.event, decodeArgs(it.event_args))
            },
        )
    }

    /** What `AI Agent` took from a request: the class it chose, and the English summary. */
    class Classification(val ticketClass: String?, val summary: String?, val title: String? = null, val englishTitle: String? = null)

    enum class AgentApplied { APPLIED, GONE, HANDLED, SUPERSEDED }

    /**
     * FR-321-9 — a result may act only on a ticket `AI Agent` still holds, still owes, that is not flagged, and that
     * the viewer has not written on since the request was built. Otherwise it is discarded (billed, never applied).
     */
    private fun heldByAgent(number: Long, askedUpTo: Long): Pair<dev.jellystructure.db.Support_ticket?, AgentApplied> {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return null to AgentApplied.GONE
        if (tk.holder != HOLDER_AI_AGENT || tk.flagged == 1L || tk.owes != OWES_AI_AGENT) return null to AgentApplied.HANDLED
        if (q.lastViewerMessageId(tk.id).executeAsOne() > askedUpTo) return null to AgentApplied.SUPERSEDED
        return tk to AgentApplied.APPLIED
    }

    /** FR-321-5 — the class and summary are stored whatever happens next: `ADMIN_ONLY` and `VIEWER` `CLASSIFIED` lines. */
    private fun classify(tk: dev.jellystructure.db.Support_ticket, c: Classification, t: Long) {
        c.summary?.takeIf { it.isNotBlank() }?.let { q.setEnglishSummary(it, tk.id) }
        // DEB-1500 (FR-13) — each title is written only while it is still null; once set, `AI Agent` never changes it.
        if (c.title != null || c.englishTitle != null) q.setTitles(c.title, c.englishTitle, tk.id)
        val cls = c.ticketClass?.takeIf { it in CLASSES } ?: return
        if (cls == tk.class_) return
        q.setClass(cls, t, tk.id)
        q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_CLASSIFIED, args(cls), ADMIN_ONLY, null, null, t, ACTOR_AI_AGENT, null)
        q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_CLASSIFIED, args(cls), VISIBLE, null, null, t, ACTOR_AI_AGENT, null)
    }

    private fun agentLine(id: Long, body: String, visibility: String, t: Long) =
        q.insertMessage(id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_AI_AGENT, AI_AGENT_LABEL, body, null, null, visibility, null, null, t, null, null)

    /** FR-321-5 — a reply that passed every rule: a `MESSAGE` by `AI_AGENT`, and nothing is owed until the viewer next writes. */
    fun agentSend(number: Long, askedUpTo: Long, c: Classification, reply: String): AgentApplied {
        val (tk, why) = heldByAgent(number, askedUpTo)
        if (tk == null) return why
        val t = now()
        db.transaction {
            classify(tk, c, t)
            agentLine(tk.id, reply, VISIBLE, t)
            q.setOwes(OWES_NONE, t, t, tk.id)
        }
        changed(tk.id, viewerVisible = true)
        return AgentApplied.APPLIED
    }

    /**
     * FR-321-6/7 — the ticket moves to [to] (`ADMIN` or `HERVOR`), with [notice] (the server's own fixed line, written as
     * `AI Agent`'s `MESSAGE`; null when `AI Agent` cannot speak) and one `ADMIN_ONLY` note for whoever holds it next.
     */
    fun agentForward(number: Long, askedUpTo: Long, c: Classification, to: String, notice: String?, note: String): AgentApplied {
        val (tk, why) = heldByAgent(number, askedUpTo)
        if (tk == null) return why
        val t = now()
        db.transaction {
            classify(tk, c, t)
            if (notice != null) agentLine(tk.id, notice, VISIBLE, t)
            q.setRouting(tk.state, to, null, to, tk.owed_since, t, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_FORWARDED, args(viewerHolder(to)), VISIBLE, null, null, t, ACTOR_AI_AGENT, null)
            if (note.isNotBlank()) agentLine(tk.id, note, ADMIN_ONLY, t)
        }
        changed(tk.id, viewerVisible = true)
        return AgentApplied.APPLIED
    }

    /**
     * FR-321-5 — abuse: flagged, with an `ADMIN_ONLY` note. Nothing the viewer can see changes: no reply, no class line,
     * no holder change, no push to the viewer.
     */
    fun agentFlag(number: Long, askedUpTo: Long, c: Classification, note: String): AgentApplied {
        val (tk, why) = heldByAgent(number, askedUpTo)
        if (tk == null) return why
        val t = now()
        db.transaction {
            c.summary?.takeIf { it.isNotBlank() }?.let { q.setEnglishSummary(it, tk.id) }
            q.setFlag(1L, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, EVENT_FLAGGED, null, ADMIN_ONLY, null, null, t, ACTOR_AI_AGENT, null)
            if (note.isNotBlank()) agentLine(tk.id, note, ADMIN_ONLY, t)
        }
        changed(tk.id, viewerVisible = false)
        return AgentApplied.APPLIED
    }

    /**
     * FR-321-9 — `AI Agent` is gone: every ticket it holds goes to `ADMIN`, with the `FORWARDED` line and an `ADMIN_ONLY`
     * reason but no notice. A ticket nobody owes anything on (already answered) keeps owing nothing. A flagged ticket
     * moves too, quietly: the line is `ADMIN_ONLY` and the viewer is not pushed. Returns the numbers moved.
     */
    fun agentForwardAllHeld(reason: String): List<Long> {
        val held = q.ticketsHeldBy(HOLDER_AI_AGENT).executeAsList()
        val t = now()
        for (tk in held) {
            db.transaction {
                val owes = if (tk.owes == OWES_NONE) OWES_NONE else OWES_ADMIN
                q.setRouting(tk.state, HOLDER_ADMIN, null, owes, tk.owed_since, if (tk.flagged == 1L) tk.updated_at else t, tk.id)
                q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_FORWARDED, args(HOLDER_ADMIN),
                    if (tk.flagged == 1L) ADMIN_ONLY else VISIBLE, null, null, t, ACTOR_SYSTEM, null)
                agentLine(tk.id, reason, ADMIN_ONLY, t)
            }
            changed(tk.id, viewerVisible = tk.flagged == 0L)
        }
        return held.map { it.id }
    }

    /** A push for a ticket whose `aiComposing` just changed, or whose request was dropped (FR-321-3). */
    fun agentChanged(number: Long) {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return
        changed(tk.id, viewerVisible = tk.flagged == 0L)
    }

    // The transcript (FR-321-10) — written by SupportAgent as a request goes out and as its answer is read.

    fun agentTurnSent(ticket: Long, ownerId: String, batchId: String, customId: String, model: String, sent: String) {
        q.insertAiTurn(ticket, ownerId, batchId, customId, now(), model, sent)
    }

    fun agentTurnAnswered(batchId: String, customId: String, answer: String?, verdict: String, input: Long, output: Long, cost: Long) {
        q.setAiTurnAnswer(answer, verdict, input, output, cost, batchId, customId)
    }

    /** FR-321-8 — what `AI Agent` has spent on this viewer's tickets in the rolling day, and the replies it has sent on this ticket. */
    fun agentRequestsSince(ownerId: String, since: Long): Long = q.aiTurnsSince(ownerId, since).executeAsOne()
    fun agentRepliesSent(ticket: Long): Long = q.aiTurnsSentOf(ticket).executeAsOne()

    /**
     * DEB-1663 (FR-321-7) — the ticket owner's Jellyfin user name, as the admin row resolves it, for the forward line's greeting.
     * Null when it does not resolve (never the id). It is filled into a line after the answer comes back and never sent out.
     */
    fun agentOwnerName(ownerId: String): String? = nameOf(ownerId)?.trim()?.takeIf { it.isNotEmpty() }

    /** DEB-1663 — every stored answer of the ticket's turns, oldest first, for the line language of a forward with no readable answer. */
    fun agentStoredAnswers(number: Long): List<String> = q.aiTurnsOf(number).executeAsList().mapNotNull { it.answer }

    fun agentTranscript(number: Long): SupportAiTranscript? {
        q.ticket(number).executeAsOneOrNull() ?: return null
        return SupportAiTranscript(number, q.aiTurnsOf(number).executeAsList().map {
            SupportAiTurn(it.created_at, it.model, it.sent, it.answer, it.verdict, it.input_tokens, it.output_tokens, it.cost_micro_usd)
        })
    }

    /** FR-321-3 — a result for an older message was dropped: the ticket is asked again, so the newer message is answered. */
    fun askAgentAgain(number: Long) = asked(number)

    /** FR-321-3 — a viewer's message on a ticket `AI Agent` holds, and the ticket not flagged: ask it. */
    private fun asked(id: Long) {
        if (!agentActive) return
        val tk = q.ticket(id).executeAsOneOrNull() ?: return
        if (tk.holder == HOLDER_AI_AGENT && tk.flagged == 0L && tk.owes == OWES_AI_AGENT) askAgent(tk.id)
    }

    // ── Hervør (DEB-1508) ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * FR-3 — what a `jss_` bearer comes to: unknown or revoked, a live token while she is switched off, or a live token
     * that may call the allowlisted routes.
     */
    fun checkSeatToken(plaintext: String): SeatTokenCheck {
        val id = seatTokens.validate(plaintext) ?: return SeatTokenCheck.Invalid
        return if (hervorEffective) SeatTokenCheck.Live(id) else SeatTokenCheck.Disabled
    }

    /** FR-6 — not flagged, and she holds it, owes it, or has written in it. Any other ticket does not exist for her. */
    private fun hervorsTicket(tk: dev.jellystructure.db.Support_ticket): Boolean =
        tk.flagged == 0L && (tk.holder == HOLDER_HERVOR || tk.owes == OWES_HERVOR || q.hervorWroteIn(tk.id).executeAsOne() > 0)

    private fun hervorsTicket(number: Long) = q.ticket(number).executeAsOneOrNull()?.takeIf { hervorsTicket(it) }

    /** FR-4/6 — the list the seat reads: only [hervorsTicket]s, whatever the query asks (a `flagged=true` is ignored). */
    fun seatList(f: AdminFilter = AdminFilter()): SupportAdminList {
        val wrote = q.ticketsHervorWroteIn().executeAsList().toSet()
        return SupportAdminList(
            q.allTickets().executeAsList()
                .filter { it.flagged == 0L && (it.holder == HOLDER_HERVOR || it.owes == OWES_HERVOR || it.id in wrote) }
                .matching(f, seat = true),
        )
    }

    fun seatThread(number: Long): SupportAdminThread? = hervorsTicket(number)?.let { adminThreadOf(it, seat = true) }

    /**
     * FR-11 — a reply as Hervør, the six rules in order: hers (404), support available (409 `support_unavailable`), her turn
     * (409 `not_hervors_turn`), a body (400), no internal string (422 `internal_string`), within her two rates (429).
     */
    fun seatReply(number: Long, tokenId: String, body: String): Outcome<SupportAdminThread> {
        val tk = hervorsTicket(number) ?: return notFound()
        seatTurnRefusal(tk)?.let { return it }
        bodyRefusal(body)?.let { return it }
        SupportAi.internalKind(body)?.let {
            return Outcome.Refused(422, "internal_string", "The reply contains $it. Remove it and send again")
        }
        val t = now()
        hervorRateRefusal(tk.id, t)?.let { return it }
        db.transaction {
            q.insertSeatMessage(tk.id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_HERVOR, HERVOR_LABEL, body, null, null, VISIBLE, t, ACTOR_HERVOR, HERVOR_LABEL, tokenId)
            q.setOwes(OWES_NONE, t, t, tk.id)
        }
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne(), seat = true))
    }

    /**
     * FR-12 — a forward as Hervør, FR-11's rules 1–3 and then: the holder is `ADMIN` or `SEAT` (never her own), [owes] is
     * `ADMIN` or `HERVOR`, and the English [note] is required. Both lines are hers: the `FORWARDED` line's actor and the
     * note's author.
     */
    fun seatForward(number: Long, tokenId: String, holder: String, seat: String?, owes: String, note: String): Outcome<SupportAdminThread> {
        val tk = hervorsTicket(number) ?: return notFound()
        seatTurnRefusal(tk)?.let { return it }
        if (holder != HOLDER_ADMIN && holder != HOLDER_SEAT) return bad("holder", "holder must be ADMIN or SEAT")
        if (owes !in FORWARD_OWES) return bad("owes", "owes must be ADMIN or HERVOR")
        val seatName = seat?.trim()?.takeIf { it.isNotEmpty() }
        if (holder == HOLDER_SEAT && (seatName == null || seatName.length > MAX_SEAT_CHARS)) {
            return bad("seat", "a seat holder needs a seat name of at most $MAX_SEAT_CHARS characters")
        }
        if (note.isBlank()) return bad("note", "a note is required: say in English why you are handing the ticket on")
        if (note.codePointCount() > MAX_SEAT_NOTE_CHARS) return bad("note", "note is longer than $MAX_SEAT_NOTE_CHARS characters")
        val t = now()
        db.transaction {
            q.setRouting(tk.state, holder, if (holder == HOLDER_SEAT) seatName else null, owes, t, t, tk.id)
            q.insertSeatMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_FORWARDED, args(viewerHolder(holder)), VISIBLE, t, ACTOR_HERVOR, HERVOR_LABEL, tokenId)
            q.insertSeatMessage(tk.id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_HERVOR, HERVOR_LABEL, note, null, null, ADMIN_ONLY, t, ACTOR_HERVOR, HERVOR_LABEL, tokenId)
        }
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne(), seat = true))
    }

    /** FR-11 rules 2 and 3 — shared by the reply and the forward. */
    private fun seatTurnRefusal(tk: dev.jellystructure.db.Support_ticket): Outcome.Refused? = when {
        !available -> Outcome.Refused(409, "support_unavailable", "Support is switched off")
        tk.owes != OWES_HERVOR && tk.holder != HOLDER_HERVOR -> Outcome.Refused(409, "not_hervors_turn", "This ticket is no longer hers to answer")
        else -> null
    }

    /** FR-11 rule 6 — bounds a seat stuck in a loop: an hour across all tickets, a rolling day on one. Counted from the store. */
    private fun hervorRateRefusal(ticket: Long, t: Long): Outcome.Refused? {
        val hour = t - HOUR
        if (q.hervorRepliesSince(hour).executeAsOne() >= limits.hervorMessagesPerHour) {
            return limited("hervor_messages_per_hour", q.oldestHervorReplySince(hour).executeAsOneOrNull()?.MIN, t, HOUR)
        }
        val day = t - DAY
        if (q.hervorRepliesOnTicketSince(ticket, day).executeAsOne() >= limits.hervorMessagesPerTicketPerDay) {
            return limited("hervor_messages_per_ticket_per_day", q.oldestHervorReplyOnTicketSince(ticket, day).executeAsOneOrNull()?.MIN, t, DAY)
        }
        return null
    }

    /**
     * FR-9 — Hervør is gone (switched off, her token revoked, or the effective value false any other way): every ticket she
     * holds goes to `ADMIN` with the `FORWARDED` line and an `ADMIN_ONLY` reason but **no notice** (she cannot speak), and
     * every ticket she owes is owed by `ADMIN`. **`owed_since` keeps its age**, so a late ticket is overdue at once. A
     * flagged ticket moves too, quietly. Switching her back on moves nothing back. [by] is the admin who pressed it, or
     * null for the server. Returns the numbers moved.
     */
    fun handBackFromHervor(by: String?): List<Long> {
        val t = now()
        val actor = if (by != null) ACTOR_ADMIN else ACTOR_SYSTEM
        val rows = q.allTickets().executeAsList().filter { it.holder == HOLDER_HERVOR || it.owes == OWES_HERVOR }
        for (tk in rows) {
            val flagged = tk.flagged == 1L
            val touched = if (flagged) tk.updated_at else t
            db.transaction {
                if (tk.holder == HOLDER_HERVOR) {
                    q.setRouting(tk.state, HOLDER_ADMIN, null, if (tk.owes == OWES_HERVOR) OWES_ADMIN else tk.owes, tk.owed_since, touched, tk.id)
                    q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_FORWARDED, args(HOLDER_ADMIN),
                        if (flagged) ADMIN_ONLY else VISIBLE, null, null, t, actor, by)
                    q.insertMessage(tk.id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_ADMIN, by ?: "System", "Hervør was switched off", null, null, ADMIN_ONLY, null, null, t, null, null)
                } else {
                    q.setOwes(OWES_ADMIN, tk.owed_since, touched, tk.id)
                }
            }
            changed(tk.id, viewerVisible = !flagged && tk.holder == HOLDER_HERVOR)
        }
        return rows.map { it.id }
    }

    // ── The Support log (DEB-1500, FR-12) ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Newest first, [limit] at most, strictly older than the entry [before] names (an entry's own [SupportLogEntry.id]).
     * It is built from the `SYSTEM` lines of both visibilities, every reply a viewer can read by `AI_AGENT`, `HERVOR` or
     * `ADMIN`, and every `AI Agent` verdict. **No body, note, summary or title reaches an entry**: a line is named by its
     * event and the facts that go with it (a holder, a state, a class), never by its text (FR-320-16).
     */
    fun log(before: Long? = null, limit: Int = LOG_PAGE): SupportLog {
        val n = limit.coerceIn(1, LOG_MAX).toLong()
        val cursor = before ?: Long.MAX_VALUE
        val at = before?.let { logTimeOf(it) } ?: Long.MAX_VALUE
        val owners = HashMap<Long, String>()
        fun ownerName(ticket: Long): String? = owners.getOrPut(ticket) {
            q.ticket(ticket).executeAsOneOrNull()?.owner_user_id?.let { nameOf(it) ?: it } ?: ""
        }.ifEmpty { null }
        val fromMessages = q.logMessages(at, cursor, n).executeAsList().mapNotNull { m ->
            val args = decodeArgs(m.event_args)
            if (m.kind == SupportWire.KIND_MESSAGE) {
                return@mapNotNull SupportLogEntry(m.id * 2, m.at, m.ticket, m.author.orEmpty(), m.author_label, "replied")
            }
            val action = when (m.event) {
                SupportWire.EVENT_RECEIVED -> "received"
                SupportWire.EVENT_FORWARDED -> "forwarded"
                SupportWire.EVENT_STATE_CHANGED -> "state_changed"
                SupportWire.EVENT_CLASSIFIED -> "classified"
                SupportWire.EVENT_REOPENED -> "reopened"
                EVENT_FLAGGED -> "flagged"
                EVENT_UNFLAGGED -> "flag_removed"
                else -> return@mapNotNull null
            }
            val actor = m.actor ?: ACTOR_SYSTEM
            SupportLogEntry(m.id * 2, m.at, m.ticket, actor, if (actor == ACTOR_VIEWER) ownerName(m.ticket) else m.actor_label, action,
                // A received line's only arg is the ticket's own number, which the entry already carries.
                if (action == "received") emptyList() else args)
        }
        val fromTurns = q.logTurns(at, cursor, n).executeAsList().map { t ->
            SupportLogEntry(t.id * 2 + 1, t.at, t.ticket, ACTOR_AI_AGENT, AI_AGENT_LABEL, "ai_verdict", listOf(verdictForLog(t.verdict.orEmpty())))
        }
        return SupportLog((fromMessages + fromTurns).sortedWith(compareByDescending<SupportLogEntry> { it.at }.thenByDescending { it.id }).take(n.toInt()))
    }

    /** The time of the entry a cursor names, or the far future (so an unknown cursor reads from the top). */
    private fun logTimeOf(id: Long): Long =
        (if (id % 2L == 0L) q.logMessageAt(id / 2).executeAsOneOrNull() else q.logTurnAt(id / 2).executeAsOneOrNull()) ?: Long.MAX_VALUE

    /**
     * A verdict as the log names it: `SENT`, `FORWARDED: <rule>`, `FLAGGED` or `DISCARDED: <why>`. A rule can quote the
     * string that failed it (rule 4 names the internal string a reply held), so anything in quotation marks is cut.
     */
    private fun verdictForLog(verdict: String): String =
        verdict.replace(Regex("\\s*\\(\"[^\"]*\"\\)"), "").replace(Regex("\"[^\"]*\""), "").trim()

    // ── internals ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun own(userId: String, number: Long) =
        q.ticket(number).executeAsOneOrNull()?.takeIf { it.owner_user_id == userId }

    private fun delete(id: Long, push: Boolean = true) {
        val tk = q.ticket(id).executeAsOneOrNull() ?: return
        db.transaction {
            q.deleteMessages(id)
            q.deleteAiTurns(id)   // 321 (FR-321-10) — the transcript goes with the ticket
            q.deleteTicket(id)
        }
        ticketDeleted(id)
        if (push && available) {
            pushViewer(tk.owner_user_id, id)
            pushAdmin(JobEvent.SupportTicketChanged(number = id, ticket = null, summary = summary(), deleted = true))
        }
    }

    /** FR-320-6/13 — a viewer's message or same-here: reopen a resolved ticket, and say who owes the next message. */
    private fun viewerSpoke(tk: dev.jellystructure.db.Support_ticket, t: Long) {
        if (tk.state == STATE_RESOLVED) {
            q.setRouting(STATE_OPEN, HOLDER_ADMIN, null, OWES_ADMIN, t, t, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_REOPENED, null, VISIBLE, null, null, t, ACTOR_VIEWER, null)
            return
        }
        val owes = owesFor(tk.holder)
        // An obligation already standing keeps its age: a chatty viewer must not keep a ticket from going overdue.
        val since = if (tk.owes == owes) tk.owed_since else t
        q.setOwes(owes, since, t, tk.id)
    }

    /** FR-320-13 — the holder if it may answer, else Hervør when she is switched on, else an admin. */
    private fun owesFor(holder: String): String {
        val hervor = hervorEffective
        val mayAnswer = holder == HOLDER_ADMIN || holder == HOLDER_AI_AGENT || (holder == HOLDER_HERVOR && hervor)
        return when {
            mayAnswer -> holder
            hervor -> OWES_HERVOR
            else -> OWES_ADMIN
        }
    }

    private fun isOverdue(owes: String, owedSince: Long, t: Long) =
        owes != OWES_NONE && t - owedSince > limits.owedReplyHours * 3600L

    /** The holder as a viewer may see it (FR-320-8): a seat that may not answer is `TEAM`, never its name. */
    private fun viewerHolder(holder: String) = if (holder == HOLDER_SEAT) SupportWire.HOLDER_TEAM else holder

    private fun preview(id: Long): String =
        q.firstMessage(id).executeAsOneOrNull()?.body.orEmpty().takeCodePoints(PREVIEW_CHARS)

    private fun viewerUnreadOf(tk: dev.jellystructure.db.Support_ticket): Int =
        q.viewerUnread(tk.id, tk.viewer_read_message_id).executeAsOne().toInt()

    private fun viewerRow(tk: dev.jellystructure.db.Support_ticket) = SupportTicketRow(
        number = tk.id, state = tk.state, holder = viewerHolder(tk.holder), ticketClass = tk.class_, title = tk.title,
        preview = preview(tk.id), lastActivity = tk.updated_at, unread = viewerUnreadOf(tk),
    )

    private fun viewerThread(tk: dev.jellystructure.db.Support_ticket) = SupportThread(
        aiComposing = tk.holder == HOLDER_AI_AGENT && tk.flagged == 0L && agentComposing(tk.id),
        ticket = viewerRow(tk),
        messages = q.messages(tk.id).executeAsList().filter { it.visibility == VISIBLE }.map {
            SupportMessageView(
                id = it.id, kind = it.kind, author = it.author, authorLabel = it.author_label, body = it.body,
                event = it.event, eventArgs = decodeArgs(it.event_args), createdAt = it.created_at,
            )
        },
    )

    /** [seat] — DEB-1508 (FR-7): the viewer stays anonymous to Hervør, so the owner's id and name are the empty string. */
    private fun adminRow(tk: dev.jellystructure.db.Support_ticket, seat: Boolean = false) = SupportAdminTicket(
        number = tk.id, ownerId = if (seat) "" else tk.owner_user_id, ownerName = if (seat) "" else nameOf(tk.owner_user_id) ?: tk.owner_user_id,
        state = tk.state, holder = tk.holder, holderSeat = tk.holder_seat, owes = tk.owes, owedSince = tk.owed_since,
        overdue = isOverdue(tk.owes, tk.owed_since, now()), ticketClass = tk.class_, englishSummary = tk.english_summary,
        englishTitle = tk.english_title, title = tk.title, viewerLanguage = tk.viewer_language, flagged = tk.flagged == 1L, preview = preview(tk.id),
        lastActivity = tk.updated_at, unread = q.adminUnread(tk.id, tk.admin_read_message_id).executeAsOne().toInt(),
    )

    private fun adminThreadOf(tk: dev.jellystructure.db.Support_ticket, seat: Boolean = false) = SupportAdminThread(
        ticket = adminRow(tk, seat),
        aiComposing = tk.holder == HOLDER_AI_AGENT && tk.flagged == 0L && agentComposing(tk.id),
        messages = q.messages(tk.id).executeAsList().map {
            SupportAdminMessage(
                id = it.id, kind = it.kind, author = it.author,
                // FR-7 — an admin's name is the household's, and Hervør is told only that an admin wrote it.
                authorLabel = if (seat && it.author == SupportWire.AUTHOR_ADMIN) "Admin" else it.author_label, body = it.body,
                event = it.event, eventArgs = decodeArgs(it.event_args), visibility = it.visibility,
                devicePlatform = it.device_platform, createdAt = it.created_at,
            )
        },
    )

    /** FR-320-10 — one place sends both pushes; nothing goes while the lane is unavailable (FR-320-1). */
    private fun changed(id: Long, viewerVisible: Boolean) {
        if (!available) return
        val tk = q.ticket(id).executeAsOneOrNull() ?: return
        if (viewerVisible) pushViewer(tk.owner_user_id, id)
        pushAdmin(JobEvent.SupportTicketChanged(number = id, ticket = adminRow(tk), summary = summary()))
    }

    private fun args(vararg a: String) = json.encodeToString(argsSerializer, a.toList())
    private fun decodeArgs(raw: String?): List<String> =
        raw?.let { runCatching { json.decodeFromString(argsSerializer, it) }.getOrNull() }.orEmpty()

    // ── refusals ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun notFound() = Outcome.Refused(404, "not_found", "No such ticket")
    private fun bad(field: String, reason: String) = Outcome.Refused(400, "invalid_$field", reason)

    /** FR-320-5 — a product rule, not a security boundary: the header is self-reported. */
    fun platformRefusal(platform: String?): Outcome.Refused? =
        if (platform?.lowercase() in WRITE_PLATFORMS) null
        else Outcome.Refused(403, "platform_not_allowed", "Tickets are filed from a phone, a computer or the web")

    private fun bodyRefusal(body: String): Outcome.Refused? {
        if (body.isBlank()) return Outcome.Refused(400, "body_empty", "Write something first")
        val max = limits.maxBodyChars
        if (body.codePointCount() > max) return Outcome.Refused(400, "body_too_long", "That is longer than $max characters")
        return null
    }

    private fun messageLimit(userId: String, t: Long): Outcome.Refused? {
        val since = t - DAY
        if (q.messagesSince(since, userId).executeAsOne() < limits.messagesPerDay) return null
        return limited("messages_per_day", q.oldestMessageSince(since, userId).executeAsOneOrNull()?.MIN, t)
    }

    /** 429 with `Retry-After`: the moment the oldest counted entry leaves the rolling day. */
    private fun limited(what: String, oldest: Long?, t: Long, window: Long = DAY): Outcome.Refused {
        val wait = ((oldest ?: t) + window - t).coerceAtLeast(1L)
        return Outcome.Refused(429, "limit_$what", if (window == DAY) "Too many today — try again later" else "Too many this hour — try again later", retryAfterSeconds = wait)
    }

    companion object {
        const val STATE_OPEN = "OPEN"
        const val STATE_IN_PROGRESS = "IN_PROGRESS"
        const val STATE_RESOLVED = "RESOLVED"
        val STATES = setOf(STATE_OPEN, STATE_IN_PROGRESS, STATE_RESOLVED)
        val CLASSES = setOf("BUG", "REQUEST", "QUESTION", "ACCOUNT", "OTHER")

        const val HOLDER_ADMIN = "ADMIN"
        const val HOLDER_HERVOR = "HERVOR"
        const val HOLDER_AI_AGENT = "AI_AGENT"
        const val HOLDER_SEAT = "SEAT"
        val FORWARD_HOLDERS = setOf(HOLDER_ADMIN, HOLDER_HERVOR, HOLDER_SEAT)

        const val OWES_ADMIN = "ADMIN"
        const val OWES_HERVOR = "HERVOR"
        const val OWES_NONE = "NONE"
        const val OWES_AI_AGENT = "AI_AGENT"
        val FORWARD_OWES = setOf(OWES_ADMIN, OWES_HERVOR)

        /** 321 (FR-320-8, FR-321-11) — the literal label on every reply `AI Agent` writes. The only disclosure a viewer gets. */
        const val AI_AGENT_LABEL = "AI Agent"

        const val EVENT_FLAGGED = "FLAGGED"
        const val EVENT_UNFLAGGED = "UNFLAGGED"

        const val VISIBLE = "VIEWER"
        const val ADMIN_ONLY = "ADMIN_ONLY"

        /** DEB-1500 (FR-12) — who wrote a `SYSTEM` line. A line written before the column existed has none and reads as [ACTOR_SYSTEM]. */
        const val ACTOR_VIEWER = "VIEWER"
        const val ACTOR_ADMIN = "ADMIN"
        const val ACTOR_AI_AGENT = "AI_AGENT"
        const val ACTOR_SYSTEM = "SYSTEM"
        const val ACTOR_HERVOR = "HERVOR"

        /** DEB-1508 (FR-11) — the label on every line a seat call writes. */
        const val HERVOR_LABEL = "Hervør"

        /** FR-320-5 — a phone, a computer and the web file; a TV, a Tizen TV, a cast receiver or no header do not. */
        val WRITE_PLATFORMS = setOf("phone", "web", "mac", "linux")

        const val LOG_PAGE = 50
        const val LOG_MAX = 200

        const val HOUR = 3_600L
        const val DAY = 86_400L
        const val SAME_HERE_WINDOW = 6 * 3600L
        const val PREVIEW_CHARS = 140
        const val MAX_SEAT_CHARS = 64
        const val MAX_NOTE_CHARS = 2_000
        /** DEB-1508 (FR-12) — Hervør's forward note is required, English, and shorter than an admin's. */
        const val MAX_SEAT_NOTE_CHARS = 600
    }
}

/** Unicode code points, not UTF-16 units: a limit in "characters" must not cost an emoji two. */
internal fun String.codePointCount(): Int = count { !it.isLowSurrogate() }

internal fun String.takeCodePoints(n: Int): String {
    var seen = 0
    for (i in indices) {
        if (!this[i].isLowSurrogate()) {
            if (seen == n) return substring(0, i)
            seen++
        }
    }
    return this
}
