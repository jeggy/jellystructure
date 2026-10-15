package dev.jellystructure.support

import dev.jellystructure.config.AiConfig
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.SupportConfig
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.shared.tv.SupportThread
import dev.jellystructure.support.SupportService.Outcome
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 320 (FR-320-19) — the support lane's rules, against the real store and no socket: ownership, platforms,
 * reopening, same-here's window, limits, what the viewer wire never carries, unread, who owes the next message, the flag,
 * delete and retention. The routes' own status codes and the four availability configs are in [SupportRoutesTest].
 */
internal class SupportHarness(tag: String) {
    private val path = "/tmp/jellystructure-test-support-$tag-${getpid()}.db"
    var cfg = AppConfig(ai = AiConfig(apiKey = "sk-test"), support = SupportConfig(enabled = true))
    /** Epoch seconds; tests move it. */
    var now = 1_800_000_000L
    val db: JellystructureDb = createDatabase(path)
    val viewerPushes = mutableListOf<Pair<String, Long>>()
    val adminPushes = mutableListOf<JobEvent>()
    var service = newService()

    fun newService(): SupportService = SupportService(db, { cfg }, languageOf = { "da" }, nameOf = { "Name-of-$it" }, clock = { now }).also {
        it.pushViewer = { u, n -> viewerPushes += u to n }
        it.pushAdmin = { e -> adminPushes += e }
    }

    fun configure(c: SupportConfig.() -> SupportConfig) { cfg = cfg.copy(support = cfg.support.c()) }

    fun file(user: String = "u-anna", device: String = "d1", platform: String? = "phone", body: String = "It stops after ten minutes"): SupportThread =
        ok(service.file(user, platform, device, body))

    fun <T> ok(o: Outcome<T>): T = when (o) {
        is Outcome.Ok -> o.value
        is Outcome.Refused -> fail("expected Ok, got ${o.status} ${o.code}")
    }

    fun refused(o: Outcome<*>): Outcome.Refused = assertIs<Outcome.Refused>(o)

    fun close() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$path$suffix") }
    }
}

internal inline fun support(tag: String, body: SupportHarness.() -> Unit) {
    val h = SupportHarness(tag)
    try { h.body() } finally { h.close() }
}

class SupportServiceTest {
    private val json = Json { encodeDefaults = true }

    // ── FR-320-3 — ownership ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anotherViewersNumberIsNotFoundOnEveryViewerCall() = support("own") {
        val n = file("u-anna").ticket.number
        assertNull(service.thread("u-ben", n))
        assertEquals(404, refused(service.post("u-ben", n, "phone", "d9", "hello")).status)
        assertEquals(404, refused(service.sameHere("u-ben", n, "phone", "d9")).status)
        assertFalse(service.markRead("u-ben", n, 99))
        assertFalse(service.deleteOwn("u-ben", n))
        assertEquals(0, service.list("u-ben").tickets.size)
        assertEquals(0, service.unread("u-ben").tickets)
        // And a number that was never issued answers the same way, so a number reveals nothing.
        assertNull(service.thread("u-ben", 9_999))
        assertEquals(404, refused(service.sameHere("u-ben", 9_999, "phone", "d9")).status)
        assertEquals(1, service.list("u-anna").tickets.size)
    }

    // ── FR-320-4 — the receipt ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun aFilingAnswersWithTheWholeThreadTheReceiptAndTheStartingState() = support("file") {
        val t = file()
        assertEquals(1L, t.ticket.number)
        assertEquals("OPEN", t.ticket.state)
        assertEquals("ADMIN", t.ticket.holder)
        assertEquals(listOf("MESSAGE", "SYSTEM"), t.messages.map { it.kind })
        val receipt = t.messages.last()
        assertEquals("RECEIVED", receipt.event)
        assertEquals(listOf("1"), receipt.eventArgs)
        assertNull(receipt.body, "a system line is a code, never a sentence")
        assertEquals("It stops after ten minutes", t.messages.first().body)
        val admin = service.adminThread(1)!!
        assertEquals("ADMIN", admin.ticket.owes)
        assertEquals(now, admin.ticket.owedSince)
        assertEquals("da", admin.ticket.viewerLanguage, "the viewer's resolved ui_language, at the moment they filed")
    }

    // ── FR-320-5 — never filed from a TV ─────────────────────────────────────────────────────────────────────────────

    @Test fun aTvATizenACastAndNoPlatformAreRefusedOnFileAndPostButSameHereIsAllowed() = support("plat") {
        for (p in listOf("tv", "tizen", "cast", "cast-audio", null)) {
            assertEquals(403, refused(service.file("u-anna", p, "d1", "hello")).status, "file from $p")
        }
        assertEquals(0, service.list("u-anna").tickets.size)
        val n = file(platform = "phone").ticket.number
        for (p in listOf("tv", "tizen", "cast", "cast-audio", null)) {
            assertEquals(403, refused(service.post("u-anna", n, p, "d1", "hello")).status, "post from $p")
        }
        for (p in listOf("phone", "web", "mac", "linux")) assertIs<Outcome.Ok<*>>(service.post("u-anna", n, p, "d1", "from $p"))
        val tv = ok(service.sameHere("u-anna", n, "tv", "tv-1"))
        assertEquals("SAME_HERE", tv.messages.last().kind)
    }

    // ── FR-320-6/7 — reopening ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun aViewerMessageReopensAResolvedTicketAndSaysSo() = support("reopen-msg") {
        val n = file().ticket.number
        ok(service.setState(n, "RESOLVED"))
        val t = ok(service.post("u-anna", n, "phone", "d1", "Still broken"))
        assertEquals("OPEN", t.ticket.state)
        assertEquals("ADMIN", t.ticket.holder)
        assertEquals("REOPENED", t.messages.last { it.kind == "SYSTEM" }.event)
        assertEquals("ADMIN", service.adminThread(n)!!.ticket.owes)
    }

    @Test fun aSameHereReopensAResolvedTicketExactlyLikeAMessage() = support("reopen-same") {
        val n = file().ticket.number
        ok(service.setState(n, "RESOLVED"))
        val t = ok(service.sameHere("u-anna", n, "tv", "tv-1"))
        assertEquals("OPEN", t.ticket.state)
        assertEquals(listOf("SAME_HERE", "SYSTEM"), t.messages.takeLast(2).map { it.kind })
        assertEquals("REOPENED", t.messages.last().event)
        assertEquals("ADMIN", service.adminThread(n)!!.ticket.owes)
    }

    @Test fun sameHereAllowsOnePerDeviceInSixHoursAndARepeatIsASilentOk() = support("window") {
        val n = file().ticket.number
        fun count() = service.thread("u-anna", n)!!.messages.count { it.kind == "SAME_HERE" }
        ok(service.sameHere("u-anna", n, "tv", "tv-1"))
        assertEquals(1, count())
        now += 5 * 3600
        val repeat = ok(service.sameHere("u-anna", n, "tv", "tv-1"))
        assertEquals(1, count(), "a second tap is not an error and writes nothing")
        assertEquals(1, repeat.messages.count { it.kind == "SAME_HERE" })
        ok(service.sameHere("u-anna", n, "tv", "tv-2"))
        assertEquals(2, count(), "another device has its own window")
        now += 3600 + 1
        ok(service.sameHere("u-anna", n, "tv", "tv-1"))
        assertEquals(3, count(), "six hours after the first one it counts again")
    }

    // ── FR-320-11 — limits, counted from the store ───────────────────────────────────────────────────────────────────

    @Test fun theTicketLimitAnswers429WithRetryAfterAndSurvivesARestart() = support("tickets") {
        configure { copy(ticketsPerDay = 2) }
        file(); now += 60; file()
        val r = refused(service.file("u-anna", "phone", "d1", "a third"))
        assertEquals(429, r.status)
        assertEquals(DAY - 60, r.retryAfterSeconds, "until the oldest filing leaves the rolling day")
        service = newService()   // a restart: the count is the store's, not memory's
        assertEquals(429, refused(service.file("u-anna", "phone", "d1", "a third")).status)
        assertIs<Outcome.Ok<*>>(service.file("u-ben", "phone", "d2", "another viewer is unaffected"))
        now += DAY - 60 + 1
        assertIs<Outcome.Ok<*>>(service.file("u-anna", "phone", "d1", "a day later"))
    }

    @Test fun theMessageLimitCountsEveryTicketOfTheViewer() = support("messages") {
        configure { copy(messagesPerDay = 3) }
        val a = file().ticket.number          // 1
        val b = file(body = "second").ticket.number   // 2
        assertIs<Outcome.Ok<*>>(service.post("u-anna", a, "phone", "d1", "three"))   // 3
        val r = refused(service.post("u-anna", b, "phone", "d1", "four"))
        assertEquals(429, r.status)
        assertTrue((r.retryAfterSeconds ?: 0) > 0)
        assertEquals(429, refused(service.file("u-anna", "phone", "d1", "five")).status, "filing writes a message too")
    }

    @Test fun anEmptyBodyAndAnOverLongOneAre400WithAReasonAndALimitIsInCodePoints() = support("body") {
        configure { copy(maxBodyChars = 10) }
        assertEquals("body_empty", refused(service.file("u-anna", "phone", "d1", "   \n")).code)
        assertEquals(400, refused(service.file("u-anna", "phone", "d1", "")).status)
        val long = refused(service.file("u-anna", "phone", "d1", "a".repeat(11)))
        assertEquals(400, long.status)
        assertEquals("body_too_long", long.code)
        assertTrue("10" in long.reason, long.reason)
        val n = file(body = "a".repeat(10)).ticket.number
        // An emoji is one character to the viewer and two UTF-16 units: ten of them are within a limit of ten.
        assertIs<Outcome.Ok<*>>(service.post("u-anna", n, "phone", "d1", "😀".repeat(10)))
        assertEquals(400, refused(service.post("u-anna", n, "phone", "d1", "😀".repeat(11))).status)
    }

    @Test fun theBodyIsStoredVerbatim() = support("verbatim") {
        val raw = "  <script>alert(1)</script>\n  trailing  "
        assertEquals(raw, file(body = raw).messages.first().body)
    }

    // ── FR-320-8 — what the viewer wire never carries ────────────────────────────────────────────────────────────────

    @Test fun theViewerJsonCarriesNoInternalFieldAndNoAdminOnlyLineAndASeatIsTeam() = support("wire") {
        val n = file().ticket.number
        ok(service.setClass(n, "BUG"))
        ok(service.forward(n, "root", "SEAT", "Hjalti-the-seat", "ADMIN", "internal note about the cause xyz"))
        ok(service.setFlag(n, true))
        db.supportQueries.setEnglishSummary("a secret english summary", n)
        ok(service.reply(n, "root", "We are looking at it"))
        val thread = service.thread("u-anna", n)!!
        val text = json.encodeToString(SupportThread.serializer(), thread) +
            json.encodeToString(dev.jellystructure.shared.tv.SupportTicketList.serializer(), service.list("u-anna"))
        for (leak in listOf("owes", "owedSince", "holderSeat", "englishSummary", "flagged", "ADMIN_ONLY", "FLAGGED",
            "internal note", "Hjalti", "secret english", "SEAT", "viewerLanguage", "ownerId")) {
            assertFalse(leak in text, "the viewer wire carries `$leak`: $text")
        }
        assertEquals("TEAM", thread.ticket.holder)
        assertEquals("TEAM", thread.messages.single { it.event == "FORWARDED" }.eventArgs.single())
        assertEquals("root", thread.messages.last().authorLabel, "an admin's label is their Jellyfin user name")
        // The admin's side has all of it.
        val admin = service.adminThread(n)!!
        assertEquals("Hjalti-the-seat", admin.ticket.holderSeat)
        assertEquals("a secret english summary", admin.ticket.englishSummary)
        assertTrue(admin.messages.any { it.visibility == "ADMIN_ONLY" && it.body == "internal note about the cause xyz" })
        assertEquals("Name-of-u-anna", admin.ticket.ownerName)
    }

    // ── FR-320-9 — unread ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun unreadCountsOthersMessagesAndStateOrHolderChangesAndTheMarkerNeverMovesBack() = support("unread") {
        val n = file().ticket.number
        fun unread() = service.thread("u-anna", n)!!.ticket.unread
        assertEquals(0, unread(), "the viewer's own message and the receipt are not unread")
        ok(service.reply(n, "root", "one"))
        val second = ok(service.reply(n, "root", "two")).messages.last().id
        assertEquals(2, unread())
        ok(service.setClass(n, "QUESTION"))
        assertEquals(2, unread(), "a classification changes neither state nor holder")
        ok(service.setState(n, "IN_PROGRESS"))
        ok(service.forward(n, "root", "ADMIN", null, "ADMIN", ""))
        assertEquals(4, unread())
        assertEquals(1, service.unread("u-anna").tickets)
        assertTrue(service.markRead("u-anna", n, second))
        assertEquals(2, unread())
        assertTrue(service.markRead("u-anna", n, 1))
        assertEquals(2, unread(), "the marker never moves back")
        assertTrue(service.markRead("u-anna", n, 1_000_000))
        assertEquals(0, unread(), "and never past what exists")
        assertEquals(0, service.unread("u-anna").tickets)
        ok(service.reply(n, "root", "three"))
        assertEquals(1, unread())
    }

    @Test fun theAdminsUnreadCountsWhatTheViewerDidAndTheFlagAddsNone() = support("adminunread") {
        val n = file().ticket.number
        ok(service.sameHere("u-anna", n, "tv", "tv-1"))
        ok(service.post("u-anna", n, "phone", "d1", "more"))
        fun unread() = service.adminThread(n)!!.ticket.unread
        assertEquals(3, unread())
        assertTrue(service.adminMarkRead(n, service.adminThread(n)!!.messages.last().id))
        assertEquals(0, unread())
        ok(service.reply(n, "root", "answer"))
        assertEquals(0, unread(), "an admin's own reply is not unread for an admin")
    }

    // ── FR-320-12/13 — who owes the viewer the next message ──────────────────────────────────────────────────────────

    @Test fun owesAndOverdueFollowForwardReplyAndTheViewersNextMessage() = support("owes") {
        val t0 = now
        val n = file().ticket.number
        fun row() = service.adminThread(n)!!.ticket
        now = t0 + 24 * 3600
        assertFalse(row().overdue, "exactly owed_reply_hours is not yet overdue")
        now = t0 + 24 * 3600 + 1
        assertTrue(row().overdue)
        assertEquals(SupportSummaryOf(open = 1, owedByAdmin = 1, overdue = 1), SupportSummaryOf.of(service.summary()))

        ok(service.reply(n, "root", "answer"))
        assertEquals("NONE", row().owes)
        assertFalse(row().overdue)

        val t1 = now
        ok(service.post("u-anna", n, "phone", "d1", "no change"))
        assertEquals("ADMIN" to t1, row().owes to row().owedSince)

        now += 100
        val t2 = now
        val fwd = ok(service.forward(n, "root", "SEAT", "ops", "HERVOR", ""))
        assertEquals("HERVOR" to t2, fwd.ticket.owes to fwd.ticket.owedSince, "a forward starts a new obligation")
        assertEquals("SEAT", row().holder)
        assertEquals("ops", row().holderSeat)

        // The seat may not answer into the thread, so the viewer's next message is owed by an admin (Hervør is off).
        now += 100
        val t3 = now
        ok(service.post("u-anna", n, "phone", "d1", "hello?"))
        assertEquals("ADMIN" to t3, row().owes to row().owedSince)
        // A chatty viewer does not keep the ticket from going overdue: the obligation keeps its age.
        now += 100
        ok(service.post("u-anna", n, "phone", "d1", "hello??"))
        assertEquals(t3, row().owedSince)

        // With Hervør switched on, the same message is owed by her.
        configure { copy(hervorEnabled = true) }
        ok(service.reply(n, "root", "answer"))
        now += 100
        ok(service.post("u-anna", n, "phone", "d1", "again"))
        assertEquals("HERVOR", row().owes)
    }

    @Test fun aForwardNeedsAKnownHolderASeatNameAndAnAnswerer() = support("fwd") {
        val n = file().ticket.number
        assertEquals(400, refused(service.forward(n, "root", "AI_AGENT", null, "ADMIN", "")).status)
        assertEquals(400, refused(service.forward(n, "root", "SEAT", "  ", "ADMIN", "")).status)
        assertEquals(400, refused(service.forward(n, "root", "SEAT", "ops", "SEAT", "")).status, "a seat never owes the viewer")
        assertEquals(400, refused(service.forward(n, "root", "ADMIN", null, "NONE", "")).status)
        assertEquals(404, refused(service.forward(77, "root", "ADMIN", null, "ADMIN", "")).status)
    }

    @Test fun stateChangesWriteOneViewerLineAndResolvedOwesNothing() = support("state") {
        val n = file().ticket.number
        val t = ok(service.setState(n, "RESOLVED"))
        assertEquals("NONE", t.ticket.owes)
        assertFalse(t.ticket.overdue)
        val v = service.thread("u-anna", n)!!
        assertEquals("STATE_CHANGED", v.messages.last().event)
        assertEquals(listOf("RESOLVED"), v.messages.last().eventArgs)
        val before = service.thread("u-anna", n)!!.messages.size
        ok(service.setState(n, "RESOLVED"))
        assertEquals(before, service.thread("u-anna", n)!!.messages.size, "no change writes nothing")
        assertEquals(400, refused(service.setState(n, "DONE")).status)
        assertEquals(400, refused(service.setClass(n, "FEATURE")).status)
        // Leaving RESOLVED hands the obligation back to the holder.
        assertEquals("ADMIN", ok(service.setState(n, "OPEN")).ticket.owes)
    }

    // ── FR-320-12 — the flag ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aFlaggedTicketLooksExactlyAsItDidToTheViewerAndIsHiddenFromTheAdminList() = support("flag") {
        val n = file().ticket.number
        ok(service.reply(n, "root", "hello"))
        val before = json.encodeToString(SupportThread.serializer(), service.thread("u-anna", n)!!)
        val pushesBefore = viewerPushes.size
        now += 500
        ok(service.setFlag(n, true))
        assertEquals(before, json.encodeToString(SupportThread.serializer(), service.thread("u-anna", n)!!), "nothing the viewer sees moved")
        assertEquals(pushesBefore, viewerPushes.size, "and nothing was pushed to them")
        assertTrue(service.adminList().tickets.isEmpty(), "hidden unless asked for")
        assertEquals(n, service.adminList(SupportService.AdminFilter(flagged = true)).tickets.single().number)
        assertEquals(0, service.summary().open, "the summary is over unflagged tickets")
        // Their messages still post, and an admin may still reply by hand.
        assertIs<Outcome.Ok<*>>(service.post("u-anna", n, "phone", "d1", "still here"))
        assertIs<Outcome.Ok<*>>(service.reply(n, "root", "by hand"))
        ok(service.setFlag(n, false))
        assertEquals(1, service.adminList().tickets.size)
    }

    // ── FR-320-17 — delete and retention ─────────────────────────────────────────────────────────────────────────────

    @Test fun aViewerDeletesTheirTicketAndItsMessagesInOneGo() = support("delete") {
        val n = file().ticket.number
        ok(service.reply(n, "root", "x"))
        assertTrue(db.supportQueries.messages(n).executeAsList().isNotEmpty())
        assertTrue(service.deleteOwn("u-anna", n))
        assertTrue(db.supportQueries.messages(n).executeAsList().isEmpty())
        assertNull(db.supportQueries.ticket(n).executeAsOneOrNull())
        assertFalse(service.deleteOwn("u-anna", n))
    }

    @Test fun theRetentionPassDeletesOnlyTicketsResolvedAndQuietForTheRetentionPeriod() = support("retention") {
        val stale = file().ticket.number
        ok(service.setState(stale, "RESOLVED"))
        val open = file(body = "still open").ticket.number
        val fresh = file(body = "resolved recently").ticket.number
        now += 300 * DAY
        ok(service.setState(fresh, "RESOLVED"))
        now += 70 * DAY   // stale: 370 days quiet; fresh: 70 days; open: never resolved
        // The lane is unavailable and the pass still runs: an absent key must not keep personal data forever.
        cfg = cfg.copy(ai = AiConfig(apiKey = ""))
        assertEquals(1, service.retentionPass())
        assertNull(db.supportQueries.ticket(stale).executeAsOneOrNull())
        assertTrue(db.supportQueries.messages(stale).executeAsList().isEmpty())
        assertTrue(db.supportQueries.ticket(open).executeAsOneOrNull() != null)
        assertTrue(db.supportQueries.ticket(fresh).executeAsOneOrNull() != null)
        assertEquals(0, service.retentionPass(), "idempotent")
    }

    // ── FR-320-10 — pushes ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun everyVisibleChangePushesToTheViewerAndTheAdminAndNothingGoesWhileUnavailable() = support("push") {
        val n = file().ticket.number
        assertEquals(listOf("u-anna" to n), viewerPushes.toList())
        val e = assertIs<JobEvent.SupportTicketChanged>(adminPushes.single())
        assertEquals(n, e.number)
        assertEquals("u-anna", e.ticket!!.ownerId)
        assertEquals(1, e.summary.open, "the counts are pushed, so the admin keeps none of its own")
        viewerPushes.clear(); adminPushes.clear()
        ok(service.reply(n, "root", "hi"))
        assertEquals(1, viewerPushes.size)
        service.deleteOwn("u-anna", n)
        val gone = assertIs<JobEvent.SupportTicketChanged>(adminPushes.last())
        assertTrue(gone.deleted)
        assertNull(gone.ticket)
        // Unavailable: no support_changed and no admin event.
        val m = file().ticket.number
        viewerPushes.clear(); adminPushes.clear()
        cfg = cfg.copy(ai = AiConfig(apiKey = ""))
        ok(service.post("u-anna", m, "phone", "d1", "hello"))
        ok(service.reply(m, "root", "hello"))
        assertTrue(viewerPushes.isEmpty() && adminPushes.isEmpty())
    }

    @Test fun theSupportTicketEventCarriesItsTypeOnTheWs() {
        val ev: JobEvent = JobEvent.SupportTicketChanged(41, null, dev.jellystructure.model.SupportSummary(1, 1, 0), deleted = true)
        val text = Json { classDiscriminator = "type" }.encodeToString(JobEvent.serializer(), ev)
        assertTrue("\"type\":\"support_ticket\"" in text, text)
        val back = assertIs<JobEvent.SupportTicketChanged>(Json { classDiscriminator = "type" }.decodeFromString(JobEvent.serializer(), text))
        assertEquals(41L, back.number)
    }

    private data class SupportSummaryOf(val open: Int, val owedByAdmin: Int, val overdue: Int) {
        companion object { fun of(s: dev.jellystructure.model.SupportSummary) = SupportSummaryOf(s.open, s.owedByAdmin, s.overdue) }
    }

    private val DAY = SupportService.DAY
}
