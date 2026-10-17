package dev.jellystructure.ui

import dev.jellystructure.model.SupportAdminMessage
import dev.jellystructure.model.SupportAdminTicket
import dev.jellystructure.model.SupportLogEntry
import dev.jellystructure.model.SupportSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-1500 — the Support tab's words and rules (`SupportText`), which sit in `commonMain` so they can be tested here: the
 * admin frontend has no test target of its own. Stand-in data is round 3's only (viewers Jákup Hansen and Ása Dam, ticket
 * #41); no real viewer or title.
 */
class SupportAdminTextTest {
    private fun ticket(
        number: Long = 41, state: String = "OPEN", holder: String = "AI_AGENT", owes: String = "AI_AGENT", flagged: Boolean = false,
        overdue: Boolean = false, englishTitle: String? = null, preview: String = "Sound drops",
    ) = SupportAdminTicket(
        number = number, ownerId = "u-jakup", ownerName = "Jákup Hansen", state = state, holder = holder, owes = owes,
        flagged = flagged, overdue = overdue, englishTitle = englishTitle, preview = preview,
    )

    private fun msg(id: Long, kind: String = "MESSAGE", author: String? = "VIEWER", visibility: String = "VIEWER", platform: String? = "phone",
                    event: String? = null, args: List<String> = emptyList()) =
        SupportAdminMessage(id = id, kind = kind, author = author, visibility = visibility, devicePlatform = platform, event = event, eventArgs = args)

    // ── FR-6 ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theFiltersAskTheSpecsQueriesAndTakeTheirCountsFromTheSummaryNeverFromRows() {
        val queries = SupportText.FILTERS.associate { it.key to SupportText.ticketsPath(it) }
        assertEquals("/api/support/tickets", queries["all"])
        assertEquals("/api/support/tickets?state=OPEN", queries["open"])
        assertEquals("/api/support/tickets?owes=ADMIN", queries["owes"])
        assertEquals("/api/support/tickets?overdue=true", queries["overdue"])
        assertEquals("/api/support/tickets?state=RESOLVED", queries["resolved"])
        assertEquals("/api/support/tickets?flagged=true", queries["flagged"])
        assertEquals(listOf("All", "Open", "Needs a reply", "Overdue", "Resolved", "Flagged"), SupportText.FILTERS.map { it.label })
        val s = SupportSummary(open = 5, owedByAdmin = 3, overdue = 1, laneOpen = true)
        assertEquals(listOf(null, 5, 3, 1, null, null), SupportText.FILTERS.map { it.count(s) })
        assertEquals("all", SupportText.filterOf("nonsense").key, "an unknown filter is All")
    }

    @Test fun anEventRowIsKeptOrDroppedByTheSameTestTheServerFiltersWith() {
        val open = ticket()
        val resolved = ticket(state = "RESOLVED", owes = "NONE")
        val flagged = ticket(flagged = true)
        val late = ticket(overdue = true, owes = "ADMIN", holder = "ADMIN")
        assertTrue(SupportText.matches("all", open) && SupportText.matches("all", resolved))
        assertFalse(SupportText.matches("all", flagged), "a flagged ticket is on no list but Flagged (FR-320-12)")
        assertTrue(SupportText.matches("flagged", flagged))
        assertFalse(SupportText.matches("flagged", open))
        assertTrue(SupportText.matches("open", open)); assertFalse(SupportText.matches("open", resolved))
        assertTrue(SupportText.matches("resolved", resolved)); assertFalse(SupportText.matches("resolved", open))
        assertTrue(SupportText.matches("owes", late)); assertFalse(SupportText.matches("owes", open))
        assertTrue(SupportText.matches("overdue", late)); assertFalse(SupportText.matches("overdue", open))
        assertFalse(SupportText.matches("open", ticket(flagged = true)), "and not on Open either")
    }

    // ── FR-8 ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun everySystemLineIsBuiltFromItsEventAndItsArgsInEnglish() {
        fun line(event: String, vararg args: String) = SupportText.systemLine(event, args.toList(), 41).text
        assertEquals("Received · #41", line("RECEIVED", "41"))
        assertEquals("Forwarded to admins", line("FORWARDED", "ADMIN"))
        assertEquals("Forwarded to Hervør", line("FORWARDED", "HERVOR"))
        assertEquals("Forwarded to the team", line("FORWARDED", "TEAM"))
        assertEquals("State: Resolved", line("STATE_CHANGED", "RESOLVED"))
        assertEquals("State: Open", line("STATE_CHANGED", "OPEN"))
        assertEquals("Classified as Bug", line("CLASSIFIED", "BUG"))
        assertEquals("Reopened by the viewer", line("REOPENED"))
        assertEquals("Flagged as abuse", line("FLAGGED"))
        assertEquals("Flag removed", line("UNFLAGGED"))
        assertEquals("fwd", SupportText.systemLine("FORWARDED", listOf("ADMIN"), 41).tone)
        assertEquals("ok", SupportText.systemLine("STATE_CHANGED", listOf("RESOLVED"), 41).tone)
        assertEquals("reopen", SupportText.systemLine("REOPENED", emptyList(), 41).tone)
        // A newer server's event is shown by its code, not dropped: the admin is the one reader who can use it.
        assertEquals("SOMETHING_NEW", SupportText.systemLine("SOMETHING_NEW", emptyList(), 41).text)
    }

    @Test fun aSameHereNamesItsPlatformAndABodyIsClampedPastFourteenLines() {
        assertEquals("Same here · from tv", SupportText.sameHereLine("tv"))
        assertEquals("Same here", SupportText.sameHereLine(null))
        assertFalse(SupportText.isClamped((1..14).joinToString("\n") { "line $it" }), "fourteen lines is not past fourteen")
        assertTrue(SupportText.isClamped((1..15).joinToString("\n") { "line $it" }))
        assertFalse(SupportText.isClamped("x".repeat(5_000)), "the clamp is by line; the CSS wraps a long one")
    }

    @Test fun theHeaderNamesTheTicketTheViewerTheFirstMessagesPlatformAndWhenItOpened() {
        val t = ticket(englishTitle = "Sound drops in Nordvest S02E04 on the sitting-room TV")
        val messages = listOf(msg(1, platform = "phone"), msg(2, kind = "SYSTEM", author = null, platform = null), msg(3, author = "ADMIN", platform = null))
        assertEquals("#41 · Jákup Hansen · phone · opened Tue 20:14", SupportText.threadMeta(t, messages, "Tue 20:14"))
        assertEquals("#41 · Jákup Hansen · opened Tue 20:14", SupportText.threadMeta(t, emptyList(), "Tue 20:14"))
        assertEquals("Sound drops in Nordvest S02E04 on the sitting-room TV", SupportText.threadTitle(t))
        assertEquals("Ticket #41", SupportText.threadTitle(ticket()), "no title yet: the number, never the preview")
        assertEquals("Ticket #41", SupportText.threadTitle(ticket(englishTitle = "  ")))
    }

    // ── FR-10 ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun viewerSeesIsTheEnglishPresenceLineChosenFromTheViewersWireTopRowFirst() {
        val firstOnly = listOf(msg(1), msg(2, kind = "SYSTEM", author = null))
        val answered = firstOnly + msg(3, author = "AI_AGENT")
        assertEquals("Resolved", SupportText.viewerSees(ticket(state = "RESOLVED"), answered, false))
        assertEquals("Open · waiting for a first reply", SupportText.viewerSees(ticket(), firstOnly, false))
        assertEquals("Open · with AI Agent", SupportText.viewerSees(ticket(), firstOnly, true), "while AI Agent is writing, the ticket is not waiting")
        assertEquals("Open · with AI Agent", SupportText.viewerSees(ticket(holder = "AI_AGENT"), answered, false))
        assertEquals("Open · with Hervør", SupportText.viewerSees(ticket(holder = "HERVOR"), answered, false))
        assertEquals("Open · with support", SupportText.viewerSees(ticket(holder = "ADMIN"), answered, false))
        assertEquals("Open · with support", SupportText.viewerSees(ticket(holder = "SEAT"), answered, false), "a seat reads as support to the viewer")
        assertEquals("Open · with support", SupportText.viewerSees(ticket(state = "IN_PROGRESS", holder = "ADMIN"), answered, false), "IN_PROGRESS reads as open")
        // An ADMIN_ONLY line is not something the viewer was written.
        val noteOnly = firstOnly + msg(4, author = "AI_AGENT", visibility = "ADMIN_ONLY")
        assertEquals("Open · waiting for a first reply", SupportText.viewerSees(ticket(), noteOnly, false))
    }

    @Test fun aFlaggedTicketShowsTheLineFromBeforeTheFlagBecauseTheViewerIsToldNothing() {
        val answered = listOf(msg(1), msg(2, author = "AI_AGENT"))
        assertEquals(SupportText.viewerSees(ticket(), answered, false), SupportText.viewerSees(ticket(flagged = true), answered, false))
        assertEquals("Flagged", SupportText.stateLabel(ticket(flagged = true)), "the rail's own badge does say it")
        assertEquals("bad", SupportText.stateBadge(ticket(flagged = true)))
        assertEquals("ok", SupportText.stateBadge(ticket(state = "RESOLVED")))
        assertEquals("In progress", SupportText.stateLabel(ticket(state = "IN_PROGRESS")))
    }

    @Test fun forwardNamesAdminsAlwaysAndHervorOnlyWhileSheIsOnAndNeverTheCurrentHolder() {
        val off = SupportSummary(laneOpen = true, hervorEnabled = false)
        val on = SupportSummary(laneOpen = true, hervorEnabled = true)
        assertEquals(listOf("ADMIN"), SupportText.destinations(ticket(holder = "AI_AGENT"), off).map { it.key })
        assertEquals(listOf("ADMIN"), SupportText.destinations(ticket(), null).map { it.key }, "before the summary is read, Hervør is not offered")
        assertEquals(listOf("ADMIN", "HERVOR"), SupportText.destinations(ticket(), on).map { it.key })
        assertEquals(listOf(false, false), SupportText.destinations(ticket(holder = "AI_AGENT"), on).map { it.disabled })
        assertEquals(listOf(true, false), SupportText.destinations(ticket(holder = "ADMIN"), on).map { it.disabled })
        assertEquals(listOf(false, true), SupportText.destinations(ticket(holder = "HERVOR"), on).map { it.disabled })
        // Round 3's Owner row is not built: it needs Hervør to post, and she cannot yet.
        assertTrue(SupportText.destinations(ticket(), on).none { it.key == "OWNER" })
    }

    @Test fun theRailsOwesLineHasAnAgeInTheUnitThatFits() {
        assertEquals("just now", SupportText.age(1_000, 1_030))
        assertEquals("5 min", SupportText.age(1_000, 1_300))
        assertEquals("3 h", SupportText.age(0, 3 * 3_600 + 10))
        assertEquals("2 d", SupportText.age(0, 2 * 86_400 + 5))
        assertEquals("just now", SupportText.age(2_000, 1_000), "a clock that is behind the server's never shows a negative age")
    }

    @Test fun theComposerCopyIsThePlaceholderTheClosedLaneAndTheFlagAsSpecified() {
        assertEquals("Reply as Hallur · admin — it sends itself and lands in the thread", SupportText.composerPlaceholder("Hallur"))
        assertEquals("Support is closed — open it above to reply.", SupportText.CLOSED_COMPOSER)
        assertEquals("Replies are off while this ticket is flagged.", SupportText.FLAGGED_COMPOSER)
        assertTrue(SupportText.FLAGGED_BANNER.startsWith("Flagged as abuse. No reply will be sent, not even AI Agent's."))
        assertFalse("by " in SupportText.FLAGGED_BANNER, "the banner names no admin, because the flag line does not record one")
    }

    // ── FR-12 ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun entry(action: String, actor: String = "ADMIN", label: String? = "Hallur", vararg args: String) =
        SupportLogEntry(id = 1, at = 1, ticket = 41, actor = actor, actorLabel = label, action = action, args = args.toList())

    @Test fun theLogWordsEachEntryFromItsCodeAndNamesItsActor() {
        assertEquals("filed the ticket", SupportText.logAction(entry("received", "VIEWER", "Ása Dam")))
        assertEquals("forwarded it to admins", SupportText.logAction(entry("forwarded", args = arrayOf("ADMIN"))))
        assertEquals("resolved it", SupportText.logAction(entry("state_changed", args = arrayOf("RESOLVED"))))
        assertEquals("set it to Open", SupportText.logAction(entry("state_changed", args = arrayOf("OPEN"))))
        assertEquals("classified it as bug", SupportText.logAction(entry("classified", "AI_AGENT", null, "BUG")))
        assertEquals("reopened it by writing again", SupportText.logAction(entry("reopened", "VIEWER")))
        assertEquals("flagged it as abuse", SupportText.logAction(entry("flagged")))
        assertEquals("removed the flag", SupportText.logAction(entry("flag_removed")))
        assertEquals("replied", SupportText.logAction(entry("replied", "HERVOR", "Hervør")))
        assertEquals("Hallur", SupportText.actorName(entry("flagged")))
        assertEquals("AI Agent", SupportText.actorName(entry("ai_verdict", "AI_AGENT", null)))
        assertEquals("The server", SupportText.actorName(entry("forwarded", "SYSTEM", null)))
        assertEquals(listOf("ai", "hv", "ad", "me", "none"), listOf("AI_AGENT", "HERVOR", "ADMIN", "VIEWER", "SYSTEM").map { SupportText.actorMark(entry("x", it)) })
    }

    @Test fun anAiVerdictReadsAsWhatItDidAndAMentionedDroppedTitleIsNamedByRuleOnly() {
        fun verdict(v: String) = SupportText.logAction(entry("ai_verdict", "AI_AGENT", null, v))
        assertEquals("AI verdict · answered the viewer", verdict("SENT"))
        assertEquals("AI verdict · flagged it as abuse", verdict("FLAGGED"))
        assertEquals("AI verdict · forwarded it · rule 3 — the reply promises something", verdict("FORWARDED: rule 3 — the reply promises something"))
        assertEquals("AI verdict · answered the viewer · title not stored: title rule 4 — contains an internal string",
            verdict("SENT | TITLE_DROPPED: title rule 4 — contains an internal string"))
        assertEquals("AI verdict · set its answer aside · the ticket was deleted", verdict("DISCARDED: the ticket was deleted"))
    }

    @Test fun aCostIsInDollarsToFourPlaces() {
        assertEquals("$0.0000", SupportText.usd(0))
        assertEquals("$0.0123", SupportText.usd(12_345))
        assertEquals("$1.5000", SupportText.usd(1_500_000))
        assertEquals("$0.0001", SupportText.usd(100))
    }

    @Test fun anUnknownClassAndAMissingOneReadWithoutAGap() {
        assertEquals("—", SupportText.className(null))
        assertEquals("Question", SupportText.className("QUESTION"))
        assertNull(SupportText.CLASSES.firstOrNull { it == "ABUSE" }, "ABUSE is the model's class; an admin's select never offers it")
    }

    // ── DEB-1508 (FR-15) — the Hervør strip's words ──────────────────────────────────────────────────────────────────

    @Test fun theHervorStripSaysWhoIssuedTheTokenWhenItWasLastUsedAndWhetherSheIsOn() {
        val live = dev.jellystructure.model.SupportSeatTokenLive(issuedAt = 1, issuedBy = "Hallur", lastUsedAt = null)
        assertEquals(
            "Hervør's token was issued Oct 17, 20:08 by Hallur · last used never. She is switched off, so tickets go to admins.",
            SupportText.hervorStrip(live, on = false, issued = "Oct 17, 20:08", lastUsed = null),
        )
        assertEquals(
            "Hervør answers what AI Agent forwards to her · token issued Oct 17, 20:08 by Hallur · last used 3m ago.",
            SupportText.hervorStrip(live, on = true, issued = "Oct 17, 20:08", lastUsed = "3m ago"),
        )
        assertEquals("Hervør has no token, so she can't read or answer tickets.", SupportText.HERVOR_NO_TOKEN)
        assertEquals("Revoked. Hervør is switched off, and her tickets are with admins.", SupportText.HERVOR_REVOKED)
        assertEquals("Token issued — copy it now, it won't be shown again:", SupportText.HERVOR_TOKEN_SHOWN)
        assertTrue("never the viewer's name or id" in SupportText.HERVOR_READS)
    }
}
