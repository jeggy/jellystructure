package dev.jellystructure.ravilo.ui.support

import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.SupportMessageView
import dev.jellystructure.shared.tv.SupportTicketRow
import dev.jellystructure.shared.tv.SupportWire
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * DEB-1501 — FR-9's presence table, FR-10's system-line table (an unknown event is not drawn), the title fallback, the
 * clamp, FR-4's times and FR-11's strings. Stand-in data is round 3's and nothing else: Eyð, #41 / #38 / #36, Hervør, Hallur.
 */
class SupportModelTest {
    private fun ticket(
        n: Long = 41, state: String = "OPEN", holder: String = "AI_AGENT", cls: String? = null, title: String? = null,
    ) = SupportTicketRow(number = n, state = state, holder = holder, ticketClass = cls, title = title)

    private fun msg(id: Long, kind: String = "MESSAGE", author: String? = "VIEWER", label: String? = null, event: String? = null, args: List<String> = emptyList()) =
        SupportMessageView(id = id, kind = kind, author = author, authorLabel = label, body = if (kind == "MESSAGE") "text" else null, event = event, eventArgs = args, createdAt = 1_000L)

    // ── FR-9 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a resolved ticket reads Resolved with a check, whoever holds it`() {
        val p = SupportModel.presence(ticket(state = "RESOLVED", holder = "HERVOR"), answered = true, aiComposing = true, lang = "en")
        assertEquals("Resolved", p.text)
        assertEquals(SupportModel.Mark.CHECK, p.mark)
    }

    @Test fun `no reply yet and nobody composing reads waiting with an empty mark`() {
        val p = SupportModel.presence(ticket(), answered = false, aiComposing = false, lang = "en")
        assertEquals("Open · waiting for a first reply", p.text)
        assertEquals(SupportModel.Mark.EMPTY, p.mark)
    }

    @Test fun `while AI Agent is composing the line is its holder's, not waiting`() {
        val p = SupportModel.presence(ticket(), answered = false, aiComposing = true, lang = "en")
        assertEquals("Open · with AI Agent", p.text)
        assertEquals(SupportModel.Mark.AI_AGENT, p.mark)
    }

    @Test fun `each holder has its own line and mark`() {
        assertEquals("Open · with AI Agent" to SupportModel.Mark.AI_AGENT, SupportModel.presence(ticket(holder = "AI_AGENT"), true, false, "en").let { it.text to it.mark })
        assertEquals("Open · with Hervør" to SupportModel.Mark.HERVOR, SupportModel.presence(ticket(holder = "HERVOR"), true, false, "en").let { it.text to it.mark })
        for (h in listOf("ADMIN", "TEAM")) {
            val p = SupportModel.presence(ticket(holder = h), true, false, "en")
            assertEquals("Open · with support", p.text)
            assertEquals(SupportModel.Mark.SUPPORT, p.mark)
        }
    }

    @Test fun `IN_PROGRESS reads as open`() {
        assertEquals("Open · with support", SupportModel.presence(ticket(state = "IN_PROGRESS", holder = "ADMIN"), true, false, "en").text)
    }

    @Test fun `a list row has no messages and reads its holder`() {
        assertEquals("Open · with AI Agent", SupportModel.presence(ticket(), answered = null, aiComposing = false, lang = "en").text)
    }

    @Test fun `system lines and the viewer's own messages are not an answer`() {
        assertFalse(SupportModel.answered(listOf(msg(1), msg(2, kind = "SYSTEM", author = null, event = "RECEIVED"), msg(3, kind = "SAME_HERE"))))
        assertTrue(SupportModel.answered(listOf(msg(1), msg(2, author = "AI_AGENT", label = "AI Agent"))))
    }

    // ── FR-10 ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun line(m: SupportMessageView, t: SupportTicketRow = ticket(), lang: String = "en") = SupportModel.systemLine(m, t, lang)

    @Test fun `the receipt carries the class and the title once the ticket has both`() {
        val l = line(msg(1, "SYSTEM", null, event = "RECEIVED", args = listOf("41")), ticket(cls = "BUG", title = "Sound drops in the sitting room"))
        assertEquals("Received · #41 · logged as a bug · titled “Sound drops in the sitting room”", l?.text)
    }

    @Test fun `the receipt is bare until it has a class and a title`() {
        val m = msg(1, "SYSTEM", null, event = "RECEIVED")
        assertEquals("Received · #41", line(m, ticket())?.text)
        assertEquals("Received · #41", line(m, ticket(cls = "BUG"))?.text)
        assertEquals("Received · #41", line(m, ticket(title = "Sound drops"))?.text)
        // ACCOUNT and OTHER have no string in round 3, so they draw no chip and cannot be named in the receipt either.
        assertEquals("Received · #41", line(m, ticket(cls = "ACCOUNT", title = "Sound drops"))?.text)
    }

    @Test fun `CLASSIFIED is not drawn`() {
        assertNull(line(msg(1, "SYSTEM", null, event = "CLASSIFIED", args = listOf("BUG"))))
    }

    @Test fun `FORWARDED names the holder as FR-9 does`() {
        fun forwarded(h: String) = line(msg(1, "SYSTEM", null, event = "FORWARDED", args = listOf(h)))?.text
        assertEquals("AI Agent is handling this", forwarded("AI_AGENT"))
        assertEquals("Hervør is handling this", forwarded("HERVOR"))
        assertEquals("support is handling this", forwarded("ADMIN"))
        assertEquals("support is handling this", forwarded("TEAM"))
    }

    @Test fun `a state change to RESOLVED reads Resolved and any other state reads Open`() {
        fun changed(s: String) = line(msg(1, "SYSTEM", null, event = "STATE_CHANGED", args = listOf(s)))?.text
        assertEquals("Resolved", changed("RESOLVED"))
        assertEquals("Open", changed("OPEN"))
        assertEquals("Open", changed("IN_PROGRESS"))
    }

    @Test fun `REOPENED is amber and SAME_HERE is a kind of its own`() {
        val re = line(msg(1, "SYSTEM", null, event = "REOPENED"))
        assertEquals("Reopened — you wrote again", re?.text)
        assertTrue(re!!.accent)
        assertEquals("You said same here", line(msg(2, "SAME_HERE"))?.text)
        assertFalse(line(msg(2, "SAME_HERE"))!!.accent)
    }

    @Test fun `an event this app does not know is not drawn`() {
        assertNull(line(msg(1, "SYSTEM", null, event = "TRANSFERRED_TO_MARS", args = listOf("x"))))
        assertNull(line(msg(1, "SYSTEM", null, event = null)))
    }

    @Test fun `a plain message is not a system line`() {
        assertNull(line(msg(1)))
    }

    // ── FR-3 / FR-4 ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the title is the ticket's title, else Ticket n, and never the first message`() {
        assertEquals("Remember audio language per series", SupportModel.title(ticket(n = 38, title = "Remember audio language per series"), "en"))
        assertEquals("Ticket #38", SupportModel.title(ticket(n = 38), "en"))
        assertEquals("Ticket #38", SupportModel.title(ticket(n = 38, title = "  "), "en"))
        assertEquals("Ticket #38", SupportModel.title(SupportTicketRow(number = 38, state = "OPEN", holder = "ADMIN", preview = "Sound drops in S02E04"), "en"))
    }

    @Test fun `Account, Other and no class draw no chip`() {
        assertEquals("Bug", SupportModel.classLabel("BUG", "en"))
        assertEquals("Request", SupportModel.classLabel("REQUEST", "en"))
        assertEquals("Question", SupportModel.classLabel("QUESTION", "en"))
        assertNull(SupportModel.classLabel("ACCOUNT", "en"))
        assertNull(SupportModel.classLabel("OTHER", "en"))
        assertNull(SupportModel.classLabel(null, "en"))
    }

    @Test fun `authors are headed by who wrote them`() {
        assertEquals(SupportModel.Author("AI Agent", SupportModel.Badge.AI, SupportModel.Mark.AI_AGENT), SupportModel.author(msg(1, author = "AI_AGENT", label = "AI Agent")))
        assertEquals(SupportModel.Author("Hervør", SupportModel.Badge.AI_SUPPORT, SupportModel.Mark.HERVOR), SupportModel.author(msg(1, author = "HERVOR", label = "Hervør")))
        assertEquals(SupportModel.Author("Hallur", SupportModel.Badge.ADMIN, SupportModel.Mark.SUPPORT), SupportModel.author(msg(1, author = "ADMIN", label = "Hallur")))
        assertEquals("Eyð · you", SupportModel.youLabel("Eyð", "en"))
    }

    // ── the clamp (FR-4) ──────────────────────────────────────────────────────────────────────────────────────────

    /** What overflows is decided by the layout, so the bubble itself is tested in SupportScreensTest; the rule is this number. */
    @Test fun `a body is clamped at fourteen lines`() {
        assertEquals(14, SupportModel.CLAMP_LINES)
    }

    // ── times (FR-3, FR-4) ────────────────────────────────────────────────────────────────────────────────────────

    private val utc = TimeZone.UTC
    // Fri 2026-10-16 15:30:00 UTC
    private val now = Instant.parse("2026-10-16T15:30:00Z")

    private fun at(iso: String) = Instant.parse(iso).epochSeconds

    @Test fun `today is HH-mm, within a week the weekday, otherwise the date`() {
        assertEquals("09:05", SupportModel.time(at("2026-10-16T09:05:00Z"), now, utc, "en"))
        assertEquals("Thu", SupportModel.time(at("2026-10-15T23:59:00Z"), now, utc, "en"))
        assertEquals("Sat", SupportModel.time(at("2026-10-10T08:00:00Z"), now, utc, "en"))
        assertEquals("9 Oct", SupportModel.time(at("2026-10-09T08:00:00Z"), now, utc, "en"))
        assertTrue("2025" in SupportModel.time(at("2025-12-24T08:00:00Z"), now, utc, "en"))
    }

    // ── FR-7 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `every app that may file asks for support events, and a TV does not`() {
        assertEquals(setOf("sessions", "support"), SupportModel.withSupportFeature(setOf("sessions"), mayFile = true))
        assertEquals(setOf("sessions"), SupportModel.withSupportFeature(setOf("sessions"), mayFile = false))
    }

    // ── FR-11 ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private val keys = listOf(
        "support.title", "support.my_tickets", "support.write", "support.placeholder", "support.placeholder_reopen", "support.first_hint",
        "support.sent_from_phone", "support.you", "support.badge_support", "support.badge_ai", "support.ai_writing", "support.state_open",
        "support.state_resolved", "support.class_bug", "support.class_request", "support.class_question", "support.pres_waiting",
        "support.pres_with", "support.sys_received", "support.sys_handling", "support.sys_reopened", "support.sending",
        "support.not_sent_offline", "support.try_again", "support.offline_bar", "support.show_rest", "support.empty_title",
        "support.empty_body", "support.tv_said_same_here",
        "support.ticket_n", "support.new_ticket", "support.sys_received_bare", "support.limit", "support.too_long",
    )

    @Test fun `every support string resolves in every language`() {
        for (lang in SUPPORTED_LANGUAGES) for (k in keys) {
            val v = t(k, lang.code, mapOf("n" to "41", "name" to "x", "class" to "bug", "title" to "y"))
            assertTrue(v.isNotBlank() && v != k, "$k in ${lang.code} resolved to '$v'")
        }
    }

    @Test fun `round 3's strings are in Danish and Faroese, character for character`() {
        assertEquals("Mine sager", t("support.my_tickets", "da"))
        assertEquals("Mínar sakir", t("support.my_tickets", "fo"))
        assertEquals("Modtaget · #41 · registreret som fejl · med titlen “Lyd”", t("support.sys_received", "da", mapOf("n" to "41", "class" to "fejl", "title" to "Lyd")))
        assertEquals("Móttikið · #41 · skrásett sum feilur · við heitinum “Ljóð”", t("support.sys_received", "fo", mapOf("n" to "41", "class" to "feilur", "title" to "Ljóð")))
        assertEquals("Opin · hjá support", t("support.pres_with", "fo", mapOf("name" to "support")))
        assertEquals("Skriva til supportina", t("support.write", "fo"))
    }

    /** The five strings of FR-11's second table. Until DEB-1504 lands, da and fo carry English — this keeps the debt visible. */
    @Test fun `the five new strings still fall back to English in Danish and Faroese`() {
        for (k in listOf("support.ticket_n", "support.new_ticket", "support.sys_received_bare", "support.limit", "support.too_long")) {
            for (lang in listOf("da", "fo")) {
                val v = t(k, lang, mapOf("n" to "41"))
                if (v == t(k, "en", mapOf("n" to "41"))) println("DEB-1504: $k is still English in $lang")
                assertNotEquals(k, v)
            }
        }
    }
}
