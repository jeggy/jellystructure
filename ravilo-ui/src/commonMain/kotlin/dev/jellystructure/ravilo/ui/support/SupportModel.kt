package dev.jellystructure.ravilo.ui.support

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.ravilo.ui.components.weekdayAbbrKey
import dev.jellystructure.ravilo.ui.screens.monthKey
import dev.jellystructure.shared.tv.EVENTS_FEATURE_SUPPORT
import dev.jellystructure.shared.tv.SupportMessageView
import dev.jellystructure.shared.tv.SupportTicketRow
import dev.jellystructure.shared.tv.SupportWire
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * DEB-1501 — what the support screens say, as pure functions of the viewer's wire (the ticket row, its messages, the
 * language). No composable and no state here: the tables of FR-3, FR-4, FR-9 and FR-10 are tested directly, and the
 * screens only draw what these return.
 */
object SupportModel {
    /** FR-4 — a body longer than this many lines is clamped, with *Show the rest* inside the bubble. */
    const val CLAMP_LINES = 14

    /** FR-7 — the support events are asked for by every app that may file; a TV never does (FR-1). */
    fun withSupportFeature(base: Set<String>, mayFile: Boolean): Set<String> =
        if (mayFile) base + EVENTS_FEATURE_SUPPORT else base

    /** FR-3 — the ticket's `title`, else *Ticket #41*. The first message is never the title (the owner's title rule). */
    fun title(ticket: SupportTicketRow, lang: String): String =
        ticket.title?.takeIf { it.isNotBlank() } ?: t("support.ticket_n", lang, mapOf("n" to ticket.number.toString()))

    /** FR-11 — the three classes round 3 has a string for. `ACCOUNT`, `OTHER` and a null class draw no chip. */
    fun classLabel(ticketClass: String?, lang: String): String? = when (ticketClass) {
        "BUG" -> t("support.class_bug", lang)
        "REQUEST" -> t("support.class_request", lang)
        "QUESTION" -> t("support.class_question", lang)
        else -> null
    }

    // ── FR-9 — the presence line ─────────────────────────────────────────────────────────────────────────────────

    /** The mark beside the line (FR-13): empty, a check, `AI Agent`'s round dot, Hervør's square, or the support mark. */
    enum class Mark { EMPTY, CHECK, AI_AGENT, HERVOR, SUPPORT }

    data class Presence(val text: String, val mark: Mark)

    /** FR-9 — has anyone but the viewer written on this ticket? System lines are not messages. */
    fun answered(messages: List<SupportMessageView>): Boolean =
        messages.any { it.kind == SupportWire.KIND_MESSAGE && it.author != SupportWire.AUTHOR_VIEWER }

    /**
     * FR-9, top row first. [answered] is null for a list row, which carries no messages: the *waiting* row cannot be
     * chosen there, so the row reads its holder (true, and what the thread's header says once a reply is out).
     */
    fun presence(ticket: SupportTicketRow, answered: Boolean?, aiComposing: Boolean, lang: String): Presence {
        if (ticket.state == SupportWire.STATE_RESOLVED) return Presence(t("support.state_resolved", lang), Mark.CHECK)
        if (answered == false && !aiComposing) return Presence(t("support.pres_waiting", lang), Mark.EMPTY)
        return when (ticket.holder) {
            SupportWire.HOLDER_AI_AGENT -> Presence(t("support.pres_with", lang, mapOf("name" to AI_AGENT)), Mark.AI_AGENT)
            SupportWire.HOLDER_HERVOR -> Presence(t("support.pres_with", lang, mapOf("name" to HERVOR)), Mark.HERVOR)
            else -> Presence(t("support.pres_with", lang, mapOf("name" to t("support.badge_support", lang))), Mark.SUPPORT)
        }
    }

    const val AI_AGENT = "AI Agent"
    const val HERVOR = "Hervør"

    // ── FR-10 — system lines ─────────────────────────────────────────────────────────────────────────────────────

    /** One centred, muted line. [accent] is the amber of *Reopened* (FR-10). */
    data class SystemLine(val text: String, val accent: Boolean = false)

    /**
     * FR-10. Null for an entry that is not drawn: `CLASSIFIED`, and any `event` this app does not know (a newer server
     * may add one, and an old app must not show a code). [ticket] supplies what the receipt reads *now* — its class and
     * its title — and the number.
     */
    fun systemLine(m: SupportMessageView, ticket: SupportTicketRow, lang: String): SystemLine? {
        if (m.kind == SupportWire.KIND_SAME_HERE) return SystemLine(t("support.tv_said_same_here", lang))
        if (m.kind != SupportWire.KIND_SYSTEM) return null
        return when (m.event) {
            SupportWire.EVENT_RECEIVED -> {
                val n = ticket.number.toString()
                val cls = classLabel(ticket.ticketClass, lang)
                val title = ticket.title?.takeIf { it.isNotBlank() }
                SystemLine(
                    if (cls != null && title != null) t("support.sys_received", lang, mapOf("n" to n, "class" to cls.lowercase(), "title" to title))
                    else t("support.sys_received_bare", lang, mapOf("n" to n)),
                )
            }
            SupportWire.EVENT_FORWARDED -> SystemLine(t("support.sys_handling", lang, mapOf("name" to holderName(m.eventArgs.firstOrNull(), lang))))
            SupportWire.EVENT_STATE_CHANGED ->
                SystemLine(t(if (m.eventArgs.firstOrNull() == SupportWire.STATE_RESOLVED) "support.state_resolved" else "support.state_open", lang))
            SupportWire.EVENT_REOPENED -> SystemLine(t("support.sys_reopened", lang), accent = true)
            else -> null
        }
    }

    /** FR-9's naming of a holder code, for `FORWARDED`. */
    fun holderName(holder: String?, lang: String): String = when (holder) {
        SupportWire.HOLDER_AI_AGENT -> AI_AGENT
        SupportWire.HOLDER_HERVOR -> HERVOR
        else -> t("support.badge_support", lang)
    }

    // ── FR-4 — a bubble's author ─────────────────────────────────────────────────────────────────────────────────

    enum class Badge { NONE, AI, AI_SUPPORT, ADMIN }

    /** FR-4 — the left-hand author line: the name, and which badges follow it. */
    data class Author(val name: String, val badge: Badge, val mark: Mark)

    fun author(m: SupportMessageView): Author = when (m.author) {
        SupportWire.AUTHOR_AI_AGENT -> Author(AI_AGENT, Badge.AI, Mark.AI_AGENT)
        SupportWire.AUTHOR_HERVOR -> Author(HERVOR, Badge.AI_SUPPORT, Mark.HERVOR)
        else -> Author(m.authorLabel?.takeIf { it.isNotBlank() } ?: "", Badge.ADMIN, Mark.SUPPORT)
    }

    /** FR-4 — the viewer's own header, *{name} · you*. */
    fun youLabel(name: String, lang: String): String = t("support.you", lang, mapOf("name" to name))

    // ── times ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * FR-3/FR-4 — `HH:mm` today, the weekday within a week, otherwise the date. [epochSeconds] is what the wire carries;
     * [now] and [zone] are the viewer's clock, passed in so the rule is testable.
     */
    fun time(epochSeconds: Long, now: Instant, zone: TimeZone, lang: String): String {
        val at = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone)
        val today = now.toLocalDateTime(zone)
        val days = today.date.toEpochDays() - at.date.toEpochDays()
        return when {
            days == 0L || days < 0L -> "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
            days <= 6L -> t(weekdayAbbrKey(at.date.dayOfWeek), lang)
            at.date.year == today.date.year ->
                t("date.day_month", lang, mapOf("day" to at.date.day.toString(), "month" to t(monthKey(at.date.month), lang)))
            else ->
                t("date.month_day_year", lang, mapOf("month" to t(monthKey(at.date.month), lang), "day" to at.date.day.toString(), "year" to at.date.year.toString()))
        }
    }
}
