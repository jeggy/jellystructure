package dev.jellystructure.ui

import dev.jellystructure.model.SupportAdminMessage
import dev.jellystructure.model.SupportAdminTicket
import dev.jellystructure.model.SupportLogEntry
import dev.jellystructure.model.SupportSummary

/**
 * DEB-1500 — the words of the admin's Support tab and Support log, and the small rules that choose them: which filter asks
 * for what, what the viewer's presence line reads in English, which destinations a forward may name. **Pure — no DOM and no
 * `kotlinx.browser`**, so it sits in `commonMain` where `linuxX64Test` can run it (the admin frontend has no test target of
 * its own). The moment a function here needs the DOM it goes back to `wasmJsMain` and loses its tests.
 *
 * Every string is built from what the server pushed (FR-8: *each is built from `event` and `eventArgs`*). Nothing here counts
 * tickets, adds to a count or works out a state: the page renders, and these only name.
 */
internal object SupportText {
    // ── FR-6 — the filters ───────────────────────────────────────────────────────────────────────────────────────────

    class Filter(val key: String, val label: String, val query: String, val count: (SupportSummary) -> Int?)

    /** FR-6, in the order the segmented control draws them. The counts are the summary's, never a count of rows. */
    val FILTERS = listOf(
        Filter("all", "All", "") { null },
        Filter("open", "Open", "state=OPEN") { it.open },
        Filter("owes", "Needs a reply", "owes=ADMIN") { it.owedByAdmin },
        Filter("overdue", "Overdue", "overdue=true") { it.overdue },
        Filter("resolved", "Resolved", "state=RESOLVED") { null },
        Filter("flagged", "Flagged", "flagged=true") { null },
    )

    fun filterOf(key: String?): Filter = FILTERS.firstOrNull { it.key == key } ?: FILTERS.first()

    /**
     * Whether a pushed row belongs on the list [filterKey] shows — the same test `GET /api/support/tickets` applies, so an event
     * can replace a row, add one or drop one without asking again. The server hides flagged tickets from every filter but
     * *Flagged* (FR-320-12), and so does this.
     */
    fun matches(filterKey: String, t: SupportAdminTicket): Boolean = when (filterKey) {
        "flagged" -> t.flagged
        else -> !t.flagged && when (filterKey) {
            "open" -> t.state == "OPEN"
            "owes" -> t.owes == "ADMIN"
            "overdue" -> t.overdue
            "resolved" -> t.state == "RESOLVED"
            else -> true
        }
    }

    fun ticketsPath(filter: Filter): String = "/api/support/tickets" + if (filter.query.isEmpty()) "" else "?${filter.query}"

    // ── names ────────────────────────────────────────────────────────────────────────────────────────────────────────

    fun stateName(state: String): String = when (state) {
        "OPEN" -> "Open"
        "IN_PROGRESS" -> "In progress"
        "RESOLVED" -> "Resolved"
        else -> state
    }

    /** FR-10.1 — a flagged ticket reads *Flagged* whatever its stored state. */
    fun stateLabel(t: SupportAdminTicket): String = if (t.flagged) "Flagged" else stateName(t.state)

    /** The badge colour of [stateLabel] (`badge ok` · `badge bad` · `badge info` in wf.css). */
    fun stateBadge(t: SupportAdminTicket): String = when {
        t.flagged -> "bad"
        t.state == "RESOLVED" -> "ok"
        else -> "info"
    }

    fun className(c: String?): String = c?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—"

    val CLASSES = listOf("BUG", "REQUEST", "QUESTION", "ACCOUNT", "OTHER")

    /** The holder as the admin's table names it. A seat shows its name; the real holder, not what the viewer is told. */
    fun holderLabel(holder: String, seat: String? = null): String = when (holder) {
        "AI_AGENT" -> "AI Agent"
        "HERVOR" -> "Hervør · support"
        "ADMIN" -> "Admins"
        "SEAT" -> seat ?: "A seat"
        else -> holder
    }

    fun owesLabel(owes: String): String = when (owes) {
        "AI_AGENT" -> "AI Agent"
        "HERVOR" -> "Hervør"
        "ADMIN" -> "Admins"
        "NONE" -> "Nobody"
        else -> owes
    }

    /** `Forwarded to …` takes the viewer-wire holder in `eventArgs`, so `TEAM` can appear as well as the three real ones. */
    private fun forwardTarget(arg: String?): String = when (arg) {
        "AI_AGENT" -> "AI Agent"
        "HERVOR" -> "Hervør"
        "ADMIN" -> "admins"
        "TEAM", "SEAT" -> "the team"
        null -> "someone"
        else -> arg
    }

    // ── FR-8 — the thread ────────────────────────────────────────────────────────────────────────────────────────────

    /** A `SYSTEM` line in English, and the tone class round 3 drew it in (`hand`/`fwd` · `ok` · `reopen`, or none). */
    class SysLine(val text: String, val tone: String)

    fun systemLine(event: String?, args: List<String>, number: Long): SysLine = when (event) {
        "RECEIVED" -> SysLine("Received · #$number", "")
        "FORWARDED" -> SysLine("Forwarded to ${forwardTarget(args.firstOrNull())}", "fwd")
        "STATE_CHANGED" -> SysLine("State: ${stateName(args.firstOrNull() ?: "")}", if (args.firstOrNull() == "RESOLVED") "ok" else "")
        "CLASSIFIED" -> SysLine("Classified as ${className(args.firstOrNull())}", "")
        "REOPENED" -> SysLine("Reopened by the viewer", "reopen")
        "FLAGGED" -> SysLine("Flagged as abuse", "")
        "UNFLAGGED" -> SysLine("Flag removed", "")
        else -> SysLine(event ?: "", "")
    }

    /** FR-8 — *Same here · from {platform}*. */
    fun sameHereLine(platform: String?): String = "Same here" + if (platform.isNullOrBlank()) "" else " · from $platform"

    /** FR-8 — a body longer than 14 lines is clamped, with **Show the rest** inside the bubble. */
    const val CLAMP_LINES = 14

    fun isClamped(body: String): Boolean = body.count { it == '\n' } + 1 > CLAMP_LINES

    /** FR-8 — *#41 · {ownerName} · {platform of the first message} · opened {time}* ([opened] is the browser's own clock). */
    fun threadMeta(t: SupportAdminTicket, messages: List<SupportAdminMessage>, opened: String): String {
        val platform = messages.firstOrNull { it.kind == "MESSAGE" && it.author == "VIEWER" }?.devicePlatform
        return listOfNotNull("#${t.number}", t.ownerName, platform?.takeIf { it.isNotBlank() }, "opened $opened").joinToString(" · ")
    }

    /** FR-8 / FR-14 — the title is `englishTitle`, else *Ticket #41*. */
    fun threadTitle(t: SupportAdminTicket): String = t.englishTitle?.takeIf { it.isNotBlank() } ?: "Ticket #${t.number}"

    /** FR-9. */
    fun composerPlaceholder(adminName: String): String = "Reply as $adminName · admin — it sends itself and lands in the thread"

    const val CLOSED_COMPOSER = "Support is closed — open it above to reply."
    const val FLAGGED_COMPOSER = "Replies are off while this ticket is flagged."
    const val FLAGGED_BANNER = "Flagged as abuse. No reply will be sent, not even AI Agent's. The viewer sees an open ticket and nothing about the flag."

    // ── FR-10 — the rail ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * FR-10.6 — the English form of the presence line the viewer reads (the Ravilo spec's FR-9 table), chosen from the
     * viewer's wire alone, top row first. A flagged ticket shows the line from before the flag, unchanged, because the
     * viewer is told nothing (FR-320-12): nothing here looks at `flagged`.
     */
    fun viewerSees(t: SupportAdminTicket, messages: List<SupportAdminMessage>, aiComposing: Boolean): String {
        if (t.state == "RESOLVED") return "Resolved"
        val answered = messages.any { it.kind == "MESSAGE" && it.visibility == "VIEWER" && it.author != "VIEWER" }
        if (!answered && !aiComposing) return "Open · waiting for a first reply"
        return when (t.holder) {
            "AI_AGENT" -> "Open · with AI Agent"
            "HERVOR" -> "Open · with Hervør"
            else -> "Open · with support"
        }
    }

    /** The age of an obligation, `3 h` · `2 d`, from the two instants the server sent ([now] is epoch seconds). */
    fun age(owedSince: Long, now: Long): String {
        val s = (now - owedSince).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3_600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3_600} h"
            else -> "${s / 86_400} d"
        }
    }

    class Destination(val key: String, val label: String, val disabled: Boolean)

    /**
     * FR-11 — **Admins** always, **Hervør · support** only while `hervorEnabled`. A destination equal to the current holder is
     * disabled. The Owner row is not built (it needs Hervør to post, which she cannot yet).
     */
    fun destinations(t: SupportAdminTicket, summary: SupportSummary?): List<Destination> = buildList {
        add(Destination("ADMIN", "Admins", t.holder == "ADMIN"))
        if (summary?.hervorEnabled == true) add(Destination("HERVOR", "Hervør · support", t.holder == "HERVOR"))
    }

    /** FR-10.9 — what a turn cost, `$0.0123`; the ledger keeps micro-dollars. */
    fun usd(microUsd: Long): String = "$" + (microUsd / 1_000_000) + "." + (microUsd % 1_000_000).toString().padStart(6, '0').take(4)

    // ── FR-12 — the log ──────────────────────────────────────────────────────────────────────────────────────────────

    fun actorName(e: SupportLogEntry): String = e.actorLabel?.takeIf { it.isNotBlank() } ?: when (e.actor) {
        "AI_AGENT" -> "AI Agent"
        "HERVOR" -> "Hervør"
        "ADMIN" -> "An admin"
        "VIEWER" -> "The viewer"
        else -> "The server"
    }

    /** The mark beside an actor (`tk-av` + this): `ai` · `hv` · `ad` · `me` (a viewer) · `none`. */
    fun actorMark(e: SupportLogEntry): String = when (e.actor) {
        "AI_AGENT" -> "ai"
        "HERVOR" -> "hv"
        "ADMIN" -> "ad"
        "VIEWER" -> "me"
        else -> "none"
    }

    /** The action as a phrase after the actor's name. Built from the code and its args, never from any ticket text. */
    fun logAction(e: SupportLogEntry): String = when (e.action) {
        "received" -> "filed the ticket"
        "forwarded" -> "forwarded it to ${forwardTarget(e.args.firstOrNull())}"
        "state_changed" -> when (e.args.firstOrNull()) {
            "RESOLVED" -> "resolved it"
            "OPEN" -> "set it to Open"
            else -> "set it to ${stateName(e.args.firstOrNull() ?: "")}"
        }
        "classified" -> "classified it as ${className(e.args.firstOrNull()).lowercase()}"
        "reopened" -> "reopened it by writing again"
        "flagged" -> "flagged it as abuse"
        "flag_removed" -> "removed the flag"
        "replied" -> "replied"
        "ai_verdict" -> verdictPhrase(e.args.firstOrNull().orEmpty())
        else -> e.action
    }

    /** `SENT` · `FORWARDED: <rule>` · `FLAGGED`, plus a `| TITLE_DROPPED: …` the verdict may carry beside it (FR-13). */
    private fun verdictPhrase(verdict: String): String {
        val main = verdict.substringBefore(" |").trim()
        val dropped = verdict.substringAfter(" |", "").trim().removePrefix("TITLE_DROPPED:").trim().takeIf { it.isNotEmpty() }
        val base = when {
            main == "SENT" -> "answered the viewer"
            main == "FLAGGED" -> "flagged it as abuse"
            main.startsWith("FORWARDED") -> "forwarded it" + main.removePrefix("FORWARDED").removePrefix(":").trim().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
            main.startsWith("DISCARDED") -> "set its answer aside" + main.removePrefix("DISCARDED").removePrefix(":").trim().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
            else -> main
        }
        return "AI verdict · $base" + (dropped?.let { " · title not stored: $it" } ?: "")
    }
}
