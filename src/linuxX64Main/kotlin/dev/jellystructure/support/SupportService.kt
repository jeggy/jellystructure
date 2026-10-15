package dev.jellystructure.support

import dev.jellystructure.config.AppConfig
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.model.SupportAdminList
import dev.jellystructure.model.SupportAdminMessage
import dev.jellystructure.model.SupportAdminThread
import dev.jellystructure.model.SupportAdminTicket
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
    private val json = Json { ignoreUnknownKeys = true }
    private val argsSerializer = ListSerializer(String.serializer())

    /** `support_changed` to that viewer's opted-in sockets (FR-320-10). Wired to [dev.jellystructure.tv.TvEventBus]. */
    var pushViewer: (userId: String, ticket: Long) -> Unit = { _, _ -> }
    /** `support_ticket` on `/ws` (FR-320-10). Wired to the broadcaster. */
    var pushAdmin: (JobEvent) -> Unit = {}

    /** FR-320-1 — `[support] enabled` and a non-blank `[ai] api_key`; nothing else. */
    val available: Boolean get() = config().let { it.support.availableWith(it.ai) }

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
        val id = db.transactionWithResult {
            q.insertTicket(userId, STATE_OPEN, HOLDER_ADMIN, OWES_ADMIN, t, language, t, t)
            val id = q.lastInsertId().executeAsOne()
            q.insertMessage(id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_VIEWER, null, body, null, null, VISIBLE, platform, deviceId, t)
            q.insertMessage(id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_RECEIVED, args(id.toString()), VISIBLE, null, null, t)
            id
        }
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
            q.insertMessage(tk.id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_VIEWER, null, body, null, null, VISIBLE, platform, deviceId, t)
            viewerSpoke(tk, t)
        }
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
            q.insertMessage(tk.id, SupportWire.KIND_SAME_HERE, SupportWire.AUTHOR_VIEWER, null, null, null, null, VISIBLE, platform, deviceId, t)
            viewerSpoke(tk, t)
        }
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

    fun adminList(f: AdminFilter = AdminFilter()): SupportAdminList {
        val rows = q.allTickets().executeAsList()
            .filter { (it.flagged == 1L) == f.flagged }
            .filter { f.state == null || it.state == f.state }
            .filter { f.holder == null || it.holder == f.holder }
            .filter { f.owes == null || it.owes == f.owes }
            .map { adminRow(it) }
            .filter { f.overdue == null || it.overdue == f.overdue }
        return SupportAdminList(rows)
    }

    fun adminThread(number: Long): SupportAdminThread? = q.ticket(number).executeAsOneOrNull()?.let { adminThreadOf(it) }

    fun summary(): SupportSummary {
        val t = now()
        val live = q.allTickets().executeAsList().filter { it.flagged == 0L }
        return SupportSummary(
            open = live.count { it.state != STATE_RESOLVED },
            owedByAdmin = live.count { it.owes == OWES_ADMIN },
            overdue = live.count { isOverdue(it.owes, it.owed_since, t) },
        )
    }

    /** An admin's reply: a `MESSAGE` by `ADMIN`, and nothing is owed until the viewer next writes (FR-320-12). */
    fun reply(number: Long, adminName: String, body: String): Outcome<SupportAdminThread> {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return notFound()
        bodyRefusal(body)?.let { return it }
        val t = now()
        db.transaction {
            q.insertMessage(tk.id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_ADMIN, adminName, body, null, null, VISIBLE, null, null, t)
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
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_FORWARDED, args(viewerHolder(holder)), VISIBLE, null, null, t)
            if (note.isNotBlank()) {
                q.insertMessage(tk.id, SupportWire.KIND_MESSAGE, SupportWire.AUTHOR_ADMIN, adminName, note, null, null, ADMIN_ONLY, null, null, t)
            }
        }
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne()))
    }

    /**
     * FR-320-12 — `RESOLVED` owes nothing, so a resolved ticket is never overdue. Leaving `RESOLVED` hands the
     * obligation back to whoever holds it, by the same rule a viewer's message uses. Same state = no line, no push.
     */
    fun setState(number: Long, state: String): Outcome<SupportAdminThread> {
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
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_STATE_CHANGED, args(state), VISIBLE, null, null, t)
        }
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne()))
    }

    fun setClass(number: Long, ticketClass: String): Outcome<SupportAdminThread> {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return notFound()
        if (ticketClass !in CLASSES) return bad("class", "class must be BUG, REQUEST, QUESTION, ACCOUNT or OTHER")
        if (ticketClass == tk.class_) return Outcome.Ok(adminThreadOf(tk))
        val t = now()
        db.transaction {
            q.setClass(ticketClass, t, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_CLASSIFIED, args(ticketClass), VISIBLE, null, null, t)
        }
        changed(tk.id, viewerVisible = true)
        return Outcome.Ok(adminThreadOf(q.ticket(tk.id).executeAsOne()))
    }

    /** FR-320-12 — an `ADMIN_ONLY` line and nothing the viewer can see: no push to the viewer, no unread. */
    fun setFlag(number: Long, flagged: Boolean): Outcome<SupportAdminThread> {
        val tk = q.ticket(number).executeAsOneOrNull() ?: return notFound()
        if ((tk.flagged == 1L) == flagged) return Outcome.Ok(adminThreadOf(tk))
        val t = now()
        db.transaction {
            q.setFlag(if (flagged) 1L else 0L, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, if (flagged) EVENT_FLAGGED else EVENT_UNFLAGGED, null, ADMIN_ONLY, null, null, t)
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

    // ── internals ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun own(userId: String, number: Long) =
        q.ticket(number).executeAsOneOrNull()?.takeIf { it.owner_user_id == userId }

    private fun delete(id: Long, push: Boolean = true) {
        val tk = q.ticket(id).executeAsOneOrNull() ?: return
        db.transaction {
            q.deleteMessages(id)
            q.deleteTicket(id)
        }
        if (push && available) {
            pushViewer(tk.owner_user_id, id)
            pushAdmin(JobEvent.SupportTicketChanged(number = id, ticket = null, summary = summary(), deleted = true))
        }
    }

    /** FR-320-6/13 — a viewer's message or same-here: reopen a resolved ticket, and say who owes the next message. */
    private fun viewerSpoke(tk: dev.jellystructure.db.Support_ticket, t: Long) {
        if (tk.state == STATE_RESOLVED) {
            q.setRouting(STATE_OPEN, HOLDER_ADMIN, null, OWES_ADMIN, t, t, tk.id)
            q.insertMessage(tk.id, SupportWire.KIND_SYSTEM, null, null, null, SupportWire.EVENT_REOPENED, null, VISIBLE, null, null, t)
            return
        }
        val owes = owesFor(tk.holder)
        // An obligation already standing keeps its age: a chatty viewer must not keep a ticket from going overdue.
        val since = if (tk.owes == owes) tk.owed_since else t
        q.setOwes(owes, since, t, tk.id)
    }

    /** FR-320-13 — the holder if it may answer, else Hervør when she is switched on, else an admin. */
    private fun owesFor(holder: String): String {
        val hervor = limits.hervorEnabled
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
        number = tk.id, state = tk.state, holder = viewerHolder(tk.holder), ticketClass = tk.class_,
        preview = preview(tk.id), lastActivity = tk.updated_at, unread = viewerUnreadOf(tk),
    )

    private fun viewerThread(tk: dev.jellystructure.db.Support_ticket) = SupportThread(
        ticket = viewerRow(tk),
        messages = q.messages(tk.id).executeAsList().filter { it.visibility == VISIBLE }.map {
            SupportMessageView(
                id = it.id, kind = it.kind, author = it.author, authorLabel = it.author_label, body = it.body,
                event = it.event, eventArgs = decodeArgs(it.event_args), createdAt = it.created_at,
            )
        },
    )

    private fun adminRow(tk: dev.jellystructure.db.Support_ticket) = SupportAdminTicket(
        number = tk.id, ownerId = tk.owner_user_id, ownerName = nameOf(tk.owner_user_id) ?: tk.owner_user_id,
        state = tk.state, holder = tk.holder, holderSeat = tk.holder_seat, owes = tk.owes, owedSince = tk.owed_since,
        overdue = isOverdue(tk.owes, tk.owed_since, now()), ticketClass = tk.class_, englishSummary = tk.english_summary,
        viewerLanguage = tk.viewer_language, flagged = tk.flagged == 1L, preview = preview(tk.id),
        lastActivity = tk.updated_at, unread = q.adminUnread(tk.id, tk.admin_read_message_id).executeAsOne().toInt(),
    )

    private fun adminThreadOf(tk: dev.jellystructure.db.Support_ticket) = SupportAdminThread(
        ticket = adminRow(tk),
        messages = q.messages(tk.id).executeAsList().map {
            SupportAdminMessage(
                id = it.id, kind = it.kind, author = it.author, authorLabel = it.author_label, body = it.body,
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
    private fun limited(what: String, oldest: Long?, t: Long): Outcome.Refused {
        val wait = ((oldest ?: t) + DAY - t).coerceAtLeast(1L)
        return Outcome.Refused(429, "limit_$what", "Too many today — try again later", retryAfterSeconds = wait)
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
        val FORWARD_OWES = setOf(OWES_ADMIN, OWES_HERVOR)

        const val EVENT_FLAGGED = "FLAGGED"
        const val EVENT_UNFLAGGED = "UNFLAGGED"

        const val VISIBLE = "VIEWER"
        const val ADMIN_ONLY = "ADMIN_ONLY"

        /** FR-320-5 — a phone, a computer and the web file; a TV, a Tizen TV, a cast receiver or no header do not. */
        val WRITE_PLATFORMS = setOf("phone", "web", "mac", "linux")

        const val DAY = 86_400L
        const val SAME_HERE_WINDOW = 6 * 3600L
        const val PREVIEW_CHARS = 140
        const val MAX_SEAT_CHARS = 64
        const val MAX_NOTE_CHARS = 2_000
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
